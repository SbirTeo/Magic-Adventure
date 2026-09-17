package com.teolo.magixfactions.minimap;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.AutoWrapper;
import com.comphenix.protocol.wrappers.BukkitConverters;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.teolo.magixfactions.manage.PowerManager;
import com.teolo.magixfactions.map.MapService;
import com.teolo.magixfactions.util.Colors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Gestisce le "entita' fittizie" (mai vere Entity Bukkit, solo pacchetti ProtocolLib inviati al
 * singolo giocatore) che compongono la minimap HUD.
 *
 * <p><b>Architettura v3 (metodo studiato dal sorgente di NMinimap, github.com/NezuShin/NMinimap,
 * GPL-3.0 — riscritto qui, non copiato):</b>
 * <ol>
 *   <li>Un <b>ItemFrame fittizio INVISIBILE</b> (flag metadata condivisi index 0 = {@code 0x20}: nasconde
 *       la cornice di legno ma NON il contenuto/mappa) con dentro una mappa vanilla.</li>
 *   <li>L'item frame e' <b>montato come passeggero del giocatore</b> (pacchetto MOUNT): il client lo
 *       tiene sempre agganciato, quindi non scompare quando ci si muove/teletrasporta e non "scatta".</li>
 *   <li>La mappa contiene un <b>header magico</b> nei primi pixel della riga 0 (byte palette 18/4/49 —
 *       RGB 0xFF0000/0x597D27/0x3737DC), scritto da {@link MapService#renderPaletteWithHeader}.</li>
 *   <li>Il resource pack (override dello shader "text", chiamato {@code rendertype_text.vsh/.fsh} fino
 *       a Paper/MC 26.1.2 e {@code text.vsh/.fsh} da 26.2 — Mojang li ha accorpati in un unico file con
 *       varianti a compile-time, vedi la nota in text.vsh) riconosce l'header nel vertex shader e
 *       sovrascrive la posizione a schermo del contenuto con coordinate NDC fisse (angolo alto a destra),
 *       scartando la riga header nel fragment.</li>
 * </ol>
 * In questa versione (26.x) il CONTENUTO di una mappa in un item frame e' renderizzato dallo shader
 * "text" (verificato empiricamente su 26.1.2: {@code entity}/{@code item} non lo toccavano; ipotesi
 * ragionevole che valga ancora su 26.2 dato che Mojang ha solo rinominato/accorpato i file, non il
 * render-type concettuale — da confermare in-game).
 *
 * <p><b>Perche' invisibile E montato:</b> col solo mount (senza invisibile) la CORNICE fisica del quadro
 * riempiva la visuale in prima persona (osservato). Col solo teleport periodico (senza mount) la mappa
 * spariva muovendosi/teletrasportandosi (l'entita' restava indietro/fuori range). Insieme: mount = mai
 * fuori range, invisibile = niente cornice; e lo shader riposiziona il solo contenuto a schermo.
 *
 * <p><b>Pacchetto MAP e MOUNT (struttura verificata via reflection su questa esatta versione — non
 * assunta da documentazione):</b> MAP = [MapId, byte scale, boolean locked, Optional&lt;decorations&gt;,
 * Optional&lt;MapPatch&gt;]; MOUNT ({@code ClientboundSetPassengersPacket}) = {@code int vehicle},
 * {@code int[] passengers}, con unico costruttore pubblico che prende un'{@code Entity} NMS (da cui
 * ricava {@code vehicle}) — i passeggeri vanno poi sovrascritti via reflection col nostro ID fittizio.
 */
public final class MinimapManager {

    /** Flag "invisibile" nei byte condivisi di stato entita' (metadata index 0). Nasconde la cornice. */
    private static final byte ENTITY_FLAG_INVISIBLE = 0x20;

    private final JavaPlugin plugin;
    private final MapService maps;
    private final PowerManager power;
    private final boolean available;
    // 4 quadri fittizi per giocatore, uno per ogni facing orizzontale (N/E/S/W): un ItemFrame mostra la
    // mappa SOLO sulla faccia frontale, quindi guardando verso il suo "retro" sparisce (backface culling).
    // Spawnandone 4 con le 4 direzioni, qualunque direzione guardi c'e' sempre un quadro rivolto verso di
    // te. Condividono la STESSA MapView/map-id: un solo pacchetto MAP aggiorna tutti e 4.
    private static final EnumWrappers.Direction[] FACINGS = {
            EnumWrappers.Direction.NORTH, EnumWrappers.Direction.EAST,
            EnumWrappers.Direction.SOUTH, EnumWrappers.Direction.WEST};

    private final Map<UUID, int[]> frameIds = new HashMap<>(); // i 4 entity id dei quadri del giocatore
    private final Map<UUID, MapView> views = new HashMap<>();
    private final Map<UUID, BukkitTask> tasks = new HashMap<>();
    private final Map<UUID, Location> lastRemountPos = new HashMap<>(); // per rimontare mentre ci si muove (anti-flash)

    private static final Class<?> MAP_ID_CLASS;
    private static final Class<?> MAP_PATCH_CLASS;
    private static final Class<?> MOUNT_CLASS;
    private static final AutoWrapper<MapIdBox> MAP_ID_WRAPPER;
    private static final AutoWrapper<MapPatchBox> MAP_PATCH_WRAPPER;
    private static final java.lang.reflect.Constructor<?> MOUNT_CTOR;
    private static final java.lang.reflect.Field MOUNT_PASSENGERS_FIELD;

    static {
        try {
            MAP_ID_CLASS = Class.forName("net.minecraft.world.level.saveddata.maps.MapId");
            MAP_PATCH_CLASS = Class.forName("net.minecraft.world.level.saveddata.maps.MapItemSavedData$MapPatch");
            MOUNT_CLASS = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPassengersPacket");
            Class<?> nmsEntityClass = Class.forName("net.minecraft.world.entity.Entity");
            MOUNT_CTOR = MOUNT_CLASS.getConstructor(nmsEntityClass);
            MOUNT_PASSENGERS_FIELD = MOUNT_CLASS.getDeclaredField("passengers");
            MOUNT_PASSENGERS_FIELD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e); // rinomina NMS al prossimo aggiornamento di Minecraft: da rivedere
        }
        MAP_ID_WRAPPER = AutoWrapper.wrap(MapIdBox.class, MAP_ID_CLASS);
        MAP_PATCH_WRAPPER = AutoWrapper.wrap(MapPatchBox.class, MAP_PATCH_CLASS);
    }

    /** Scatola per il record NMS MapId (un solo campo int, mappato per posizione da AutoWrapper). */
    private static final class MapIdBox { public int id; }

    /** Scatola per il record NMS MapItemSavedData.MapPatch, stesso ordine dei campi verificato via reflection. */
    private static final class MapPatchBox {
        public int startX, startY, width, height;
        public byte[] mapColors;
    }

    /** Handle NMS di un'entita' Bukkit via il metodo pubblico "getHandle()" (reflection generica per non
     *  referenziare CraftPlayer, il cui package cambia da versione a versione). */
    private static Object nmsHandle(org.bukkit.entity.Entity entity) {
        try {
            return entity.getClass().getMethod("getHandle").invoke(entity);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public MinimapManager(JavaPlugin plugin, MapService maps, PowerManager power) {
        this.plugin = plugin;
        this.maps = maps;
        this.power = power;
        boolean found = plugin.getServer().getPluginManager().getPlugin("ProtocolLib") != null;
        this.available = found;
        if (!found) {
            plugin.getLogger().warning("[Minimap] ProtocolLib non trovato: la minimap HUD resta disabilitata.");
            return;
        }
        registerMountRewriter();
        prewarm();
    }

    /**
     * "Riscalda" all'AVVIO le parti costose-la-prima-volta di ProtocolLib (init dei registry serializer,
     * caching delle strutture pacchetto via reflection, converter item/direction) creando i pacchetti a
     * vuoto SENZA inviarli. Senza questo, il PRIMO giocatore che attiva la minimap dopo un riavvio pagava
     * ~70ms di warmup nel proprio tick di login (misurato con [MinimapPerf]); qui lo spostiamo nello
     * startup del server, dove uno spike non si nota. Le attivazioni successive erano gia' <5ms.
     */
    private void prewarm() {
        try {
            ProtocolManager pm = ProtocolLibrary.getProtocolManager();
            ItemStack dummy = new ItemStack(org.bukkit.Material.FILLED_MAP);
            Object nmsItem = BukkitConverters.getItemStackConverter().getGeneric(dummy);
            for (EnumWrappers.Direction d : FACINGS) EnumWrappers.getDirectionConverter().getGeneric(d);

            PacketContainer spawn = pm.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
            spawn.getEntityTypeModifier().write(0, EntityType.ITEM_FRAME);

            PacketContainer meta = pm.createPacket(PacketType.Play.Server.ENTITY_METADATA);
            meta.getDataValueCollectionModifier().write(0, List.of(
                    new WrappedDataValue(0, WrappedDataWatcher.Registry.get(Byte.class), ENTITY_FLAG_INVISIBLE),
                    new WrappedDataValue(8, WrappedDataWatcher.Registry.getDirectionSerializer(),
                            EnumWrappers.getDirectionConverter().getGeneric(EnumWrappers.Direction.SOUTH)),
                    new WrappedDataValue(9, WrappedDataWatcher.Registry.getItemStackSerializer(false), nmsItem),
                    new WrappedDataValue(10, WrappedDataWatcher.Registry.get(Integer.class), 0)));

            pm.createPacket(PacketType.Play.Server.MAP).getModifier(); // warm strutture pacchetto MAP

            // Verifica che il pacchetto ENTITY_TELEPORT sia costruibile con gli accessor standard su QUESTA
            // versione: serve per la modalita' "detach" (quadri spostati davanti al giocatore invece che
            // montati, cosi' non intercettano piu' i click di attacco/scavo). Se la struttura e' diversa,
            // teleportOk resta false e si resta automaticamente sul mount (nessuna regressione).
            // Verifica di poter scrivere la posizione nel percorso annidato di ENTITY_TELEPORT su questa
            // versione (1.21.2+): id + struct[0]=PositionMoveRotation -> struct[0]=Vec3 (3 double). Se la
            // struttura differisse, si resta sui quadri montati (fallback, nessuna regressione).
            // CON RETRY: il singolo tentativo all'avvio e' fallito una volta con "generic is null"
            // (2026-07-17, primo boot con la cache classi CDS: race di inizializzazione di ProtocolLib,
            // il campo PositionMoveRotation del pacchetto appena creato non era ancora inizializzato)
            // lasciando il detach spento per l'intera sessione. Ora si riprova fino a 5 volte, 10s
            // l'una dall'altra, marcando teleportOk appena riesce.
            tryTeleportCheck(pm, 1);

            // Riscalda anche MapPalette.matchColor (init tabella colori + JIT): viene chiamata 16384 volte
            // per render (una per pixel), era buona parte dei ~32ms del primo pushMapContent dopo il riavvio.
            for (int v = 0; v < 256; v += 8) org.bukkit.map.MapPalette.matchColor(new java.awt.Color(v, v, v));

            plugin.getLogger().info("[Minimap] Pre-warm ProtocolLib completato.");
        } catch (Exception e) {
            plugin.getLogger().warning("[Minimap] Pre-warm fallito (non critico): " + e.getMessage());
        }
    }

    // true se ENTITY_TELEPORT e' costruibile su questa versione (verificato nel prewarm): abilita il detach.
    private volatile boolean teleportOk = false;

    /** Tenta il check del teleport; se fallisce (race di init di ProtocolLib a freddo), riprova fino a
     *  5 volte a distanza di 10s. Senza successo si resta sui quadri montati (fallback sicuro). */
    private void tryTeleportCheck(ProtocolManager pm, int attempt) {
        try {
            writeTeleport(pm.createPacket(PacketType.Play.Server.ENTITY_TELEPORT), 0, 0.0, 0.0, 0.0);
            teleportOk = true;
            plugin.getLogger().info("[Minimap] ENTITY_TELEPORT: posizione scrivibile, modalita' detach-frames disponibile"
                    + (attempt > 1 ? " (tentativo " + attempt + ")" : "") + ".");
        } catch (Throwable t) {
            if (attempt >= 5) {
                plugin.getLogger().warning("[Minimap] ENTITY_TELEPORT NON compatibile dopo " + attempt
                        + " tentativi (" + t + "): resto sui quadri montati (i click potrebbero restare ostacolati).");
                return;
            }
            plugin.getLogger().info("[Minimap] Check ENTITY_TELEPORT fallito (tentativo " + attempt + ": " + t
                    + "), riprovo tra 10s.");
            Bukkit.getScheduler().runTaskLater(plugin, () -> tryTeleportCheck(pm, attempt + 1), 200L);
        }
    }

    // Ancore dei quadri in modalita' detach: una CORONA di punti FISSI attorno alla testa del giocatore,
    // INDIPENDENTI dalla direzione di sguardo. Il "flash" della minimap nei cambi di direzione rapidi /
    // caduta libera era FRUSTUM CULLING: la vecchia ancora unica "davanti agli occhi" era calcolata dalla
    // rotazione nota al SERVER, che insegue quella del client con la latenza di rete — durante un flick il
    // quadro restava dove guardavi PRIMA, usciva dal campo visivo e il client lo scartava per qualche
    // frame (minimap sparita = flash). Con la corona (8 ancore orizzontali ogni 45°, 4 a +45° di pitch,
    // 4 a -45°, zenit e nadir = 18) in QUALSIASI direzione si guardi c'e' sempre almeno un quadro gia'
    // dentro il frustum (gap angolare max ~31°, sotto il semi-FOV verticale ~35° a FOV 70): RUOTARE la
    // visuale non richiede alcun pacchetto, quindi la latenza di rete non c'entra piu'. Le ancore si
    // spostano solo quando il giocatore SI MUOVE (teleport per-tick con soglia). Ogni ancora ha il facing
    // fisso rivolto verso il giocatore (il quadro renderizza solo il fronte). Piu' quadri visibili insieme
    // disegnano lo stesso HUD sovrapposto: nessun effetto visivo. Lo shader riposiziona comunque il
    // contenuto nell'angolo dello schermo, da qualunque quadro provenga.
    // RAGGIO 5.6: oltre la portata di interazione con le entita' anche in CREATIVA (5.0) — i quadri non
    // intercettano mai i click (attacco/uova/scavo), nemmeno quando un'ancora finisce esatta sul mirino.
    private static final double ANCHOR_R = 5.6;

    private record Anchor(double x, double y, double z, EnumWrappers.Direction facing) {}

    private static final Anchor[] DETACH_ANCHORS = buildDetachAnchors();

    private static Anchor[] buildDetachAnchors() {
        double r = ANCHOR_R, a = r / Math.sqrt(2);
        return new Anchor[]{
                // anello orizzontale (altezza occhi), ogni 45° — facing = verso il giocatore
                new Anchor( 0, 0, -r, EnumWrappers.Direction.SOUTH), // N
                new Anchor( a, 0, -a, EnumWrappers.Direction.SOUTH), // NE
                new Anchor( r, 0,  0, EnumWrappers.Direction.WEST),  // E
                new Anchor( a, 0,  a, EnumWrappers.Direction.NORTH), // SE
                new Anchor( 0, 0,  r, EnumWrappers.Direction.NORTH), // S
                new Anchor(-a, 0,  a, EnumWrappers.Direction.NORTH), // SW
                new Anchor(-r, 0,  0, EnumWrappers.Direction.EAST),  // W
                new Anchor(-a, 0, -a, EnumWrappers.Direction.SOUTH), // NW
                // anello a +45° di pitch (sopra la testa, ai 4 punti cardinali)
                new Anchor( 0, a, -a, EnumWrappers.Direction.SOUTH),
                new Anchor( a, a,  0, EnumWrappers.Direction.WEST),
                new Anchor( 0, a,  a, EnumWrappers.Direction.NORTH),
                new Anchor(-a, a,  0, EnumWrappers.Direction.EAST),
                // anello a -45° di pitch (sotto i piedi, ai 4 punti cardinali)
                new Anchor( 0, -a, -a, EnumWrappers.Direction.SOUTH),
                new Anchor( a, -a,  0, EnumWrappers.Direction.WEST),
                new Anchor( 0, -a,  a, EnumWrappers.Direction.NORTH),
                new Anchor(-a, -a,  0, EnumWrappers.Direction.EAST),
                // zenit e nadir (sguardo verticale puro)
                new Anchor(0,  r, 0, EnumWrappers.Direction.DOWN),
                new Anchor(0, -r, 0, EnumWrappers.Direction.UP),
        };
    }

    // Ultima base (occhi) a cui sono state posizionate le ancore: si riposiziona solo se cambiata (fermi
    // o in sola rotazione = zero pacchetti di teleport).
    private final Map<UUID, Location> lastDetachBase = new HashMap<>();

    /** Modalita' detach attiva? Solo se il pacchetto e' compatibile E il config lo consente. */
    private boolean detachFrames() {
        return teleportOk && plugin.getConfig().getBoolean("map.minimap.detach-frames", true);
    }

    /** Base della corona di ancore = posizione OCCHI del giocatore (dipende solo dalla posizione, mai
     *  dalla rotazione: ruotare la visuale non sposta nulla). */
    private static Location anchorBase(Player viewer) {
        return viewer.getEyeLocation();
    }

    /** Teleporta ogni quadro fittizio alla PROPRIA ancora della corona attorno a {@code base} (detach). */
    private void positionFrames(Player viewer, int[] ids, Location base) {
        ProtocolManager pm = ProtocolLibrary.getProtocolManager();
        for (int i = 0; i < ids.length; i++) {
            Anchor an = DETACH_ANCHORS[i % DETACH_ANCHORS.length];
            try {
                PacketContainer tp = pm.createPacket(PacketType.Play.Server.ENTITY_TELEPORT);
                writeTeleport(tp, ids[i], base.getX() + an.x(), base.getY() + an.y(), base.getZ() + an.z());
                pm.sendServerPacket(viewer, tp);
            } catch (Exception ignored) { /* struttura diversa: gestito dal fallback al mount in prewarm */ }
        }
    }

    // Reflection NMS per costruire PositionMoveRotation/Vec3 direttamente (lazy, cache dei costruttori).
    // PERCHE' non i wrapper annidati di ProtocolLib (versione precedente): sui boot con la cache classi
    // CDS (2026-07-17) il pacchetto creato da ProtocolLib nasceva col campo PositionMoveRotation NULL
    // ("generic is null", riproducibile a ogni tentativo) -> detach spento. Costruire l'istanza noi,
    // come gia' facciamo per il MOUNT, non dipende dall'ordine di inizializzazione di nessuno.
    private static Class<?> pmrClass, vec3Class;
    private static java.lang.reflect.Constructor<?> pmrCtor, vec3Ctor;

    /** Scrive id + posizione assoluta nel pacchetto ENTITY_TELEPORT (formato 1.21.2+): il {@code
     *  PositionMoveRotation} viene COSTRUITO da noi (posizione data, delta zero, rotazioni zero) e
     *  scritto nel campo; il set dei "relative flags" resta/diventa VUOTO (= posizione assoluta),
     *  onGround irrilevante per un ItemFrame. */
    private static void writeTeleport(PacketContainer tp, int id, double x, double y, double z) throws Exception {
        tp.getIntegers().write(0, id);
        if (pmrCtor == null) {
            pmrClass = Class.forName("net.minecraft.world.entity.PositionMoveRotation");
            vec3Class = Class.forName("net.minecraft.world.phys.Vec3");
            vec3Ctor = vec3Class.getConstructor(double.class, double.class, double.class);
            pmrCtor = pmrClass.getConstructor(vec3Class, vec3Class, float.class, float.class);
        }
        Object pmr = pmrCtor.newInstance(vec3Ctor.newInstance(x, y, z),
                vec3Ctor.newInstance(0.0, 0.0, 0.0), 0.0f, 0.0f);
        tp.getModifier().withType(pmrClass).write(0, pmr);
        // Anche il Set<Relative> puo' nascere null nel pacchetto appena creato: vuoto = assoluta.
        var sets = tp.getModifier().withType(java.util.Set.class);
        if (sets.size() > 0 && sets.read(0) == null) sets.write(0, java.util.Collections.emptySet());
    }

    public boolean isAvailable() { return available; }

    public boolean isActive(Player viewer) { return frameIds.containsKey(viewer.getUniqueId()); }

    /**
     * Fonde l'ID del quadro fittizio in OGNI pacchetto MOUNT reale in uscita per un giocatore con la
     * minimap attiva: senza, un vero cambio di passeggeri (es. monta un cavallo) manderebbe un MOUNT
     * con SOLO i passeggeri reali, "smontando" implicitamente il nostro quadro.
     */
    private void registerMountRewriter() {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                PacketAdapter.params(plugin, PacketType.Play.Server.MOUNT).listenerPriority(ListenerPriority.LOW)) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Player viewer = event.getPlayer();
                if (detachFrames()) return; // detach: i quadri NON sono passeggeri, non fonderli nei MOUNT reali
                int[] ours = frameIds.get(viewer.getUniqueId());
                if (ours == null) return;
                PacketContainer packet = event.getPacket();
                if (packet.getIntegers().read(0) != viewer.getEntityId()) return; // veicolo != questo giocatore
                int[] current = packet.getIntegerArrays().read(0);
                // Fonde tutti i NOSTRI id passeggeri che non sono gia' presenti, preservando i reali.
                java.util.List<Integer> merged = new java.util.ArrayList<>();
                if (current != null) for (int id : current) merged.add(id);
                for (int id : ours) if (!merged.contains(id)) merged.add(id);
                packet.getIntegerArrays().write(0, merged.stream().mapToInt(Integer::intValue).toArray());
            }
        });
    }

    /**
     * Spawna (o rimpiazza) un ItemFrame fittizio INVISIBILE con la Mappa Fazioni VERA (header magico) e
     * lo monta come passeggero del giocatore. Avvia un task che aggiorna periodicamente solo il CONTENUTO
     * (i pixel cambiano muovendosi) — mai la posizione, gestita dal mount. L'area mostrata e' SEMPRE
     * quella dello zoom `/f map` del giocatore ({@link PowerManager#getResolvedZoomFactor}), mai un
     * valore indipendente — cosi' minimap e mappa cartacea vedono sempre la stessa porzione di mondo.
     */
    public void activate(Player viewer) {
        if (!available) return;
        deactivate(viewer);

        long t0 = System.nanoTime();
        double blocksPerPixel = power.getResolvedZoomFactor(viewer.getUniqueId());
        MapView view = maps.createHeadless(viewer, blocksPerPixel, viewer.getUniqueId());
        views.put(viewer.getUniqueId(), view);
        long tCreate = System.nanoTime();

        Object nmsItem = mapNmsItem(view);
        // In detach: un quadro per ogni ancora della corona, gia' spawnato sulla SUA posizione finale
        // (facing fisso verso il giocatore). In mount: 4 quadri co-locati (FACINGS), il mount li aggancia.
        boolean detach = detachFrames();
        Location base = detach ? anchorBase(viewer) : viewer.getLocation();
        ProtocolManager pm = ProtocolLibrary.getProtocolManager();

        int count = detach ? DETACH_ANCHORS.length : FACINGS.length;
        int[] ids = new int[count];
        try {
            for (int i = 0; i < count; i++) {
                ids[i] = FakeEntityIds.next();
                Location loc = detach
                        ? base.clone().add(DETACH_ANCHORS[i].x(), DETACH_ANCHORS[i].y(), DETACH_ANCHORS[i].z())
                        : base;
                spawnFrame(pm, viewer, ids[i], loc, nmsItem, detach ? DETACH_ANCHORS[i].facing() : FACINGS[i]);
            }
            frameIds.put(viewer.getUniqueId(), ids);
            if (detach) lastDetachBase.put(viewer.getUniqueId(), base.clone()); else sendMount(viewer, ids);
            long tSpawn = System.nanoTime();
            pushMapContent(viewer, view, blocksPerPixel);
            long tPush = System.nanoTime();

            long interval = Math.max(1, plugin.getConfig().getLong("map.render-interval-ticks", 1));
            BukkitTask task;
            if (detach) {
                // Un task ogni tick: riposiziona SEMPRE i quadri davanti al giocatore (li tiene centrati
                // nella vista, cosi' restano resi durante i movimenti/rotazioni), e rinfresca il CONTENUTO
                // ogni "interval" tick.
                int[] tick = {0};
                long iv = interval;
                task = plugin.getServer().getScheduler().runTaskTimer(plugin,
                        () -> refreshDetached(viewer, ids, view, tick, iv), 1L, 1L);
            } else {
                task = plugin.getServer().getScheduler().runTaskTimer(plugin,
                        () -> refreshContent(viewer, ids, view), interval, interval);
            }
            tasks.put(viewer.getUniqueId(), task);

            // Diagnostica TEMPORANEA (login lag): stampa i tempi solo se l'attivazione ha superato 5ms,
            // suddivisi per fase, per capire quale parte pesa (createMap vs spawn/mount vs primo render).
            long totalMs = (tPush - t0) / 1_000_000;
            if (totalMs >= 5) {
                plugin.getLogger().info(String.format(
                        "[MinimapPerf] activate(%s) %dms totali = createHeadless %dms + spawn/mount %dms + pushMapContent %dms",
                        viewer.getName(), totalMs, (tCreate - t0) / 1_000_000,
                        (tSpawn - tCreate) / 1_000_000, (tPush - tSpawn) / 1_000_000));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Minimap] Errore invio pacchetti (ItemFrame): " + e.getMessage());
        }
    }

    /** Item NMS (FILLED_MAP con la MapView data) da mettere in ogni quadro fittizio. */
    private static Object mapNmsItem(MapView view) {
        ItemStack mapItem = new ItemStack(org.bukkit.Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        meta.setMapView(view);
        mapItem.setItemMeta(meta);
        return BukkitConverters.getItemStackConverter().getGeneric(mapItem);
    }

    /** Spawna e configura UN quadro fittizio invisibile con la mappa data, rivolto verso {@code facing}. */
    private void spawnFrame(ProtocolManager pm, Player viewer, int entityId, Location loc, Object nmsItem,
                            EnumWrappers.Direction facing) throws Exception {
        PacketContainer spawn = pm.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        spawn.getIntegers().write(0, entityId);
        spawn.getUUIDs().write(0, UUID.randomUUID());
        spawn.getEntityTypeModifier().write(0, EntityType.ITEM_FRAME);
        spawn.getDoubles().write(0, loc.getX()).write(1, loc.getY()).write(2, loc.getZ());

        Object nmsFacing = EnumWrappers.getDirectionConverter().getGeneric(facing);
        PacketContainer meta2 = pm.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        meta2.getIntegers().write(0, entityId);
        meta2.getDataValueCollectionModifier().write(0, List.of(
                // index 0 = flag condivisi entita': INVISIBILE -> niente cornice di legno, solo il contenuto.
                new WrappedDataValue(0, WrappedDataWatcher.Registry.get(Byte.class), ENTITY_FLAG_INVISIBLE),
                new WrappedDataValue(8, WrappedDataWatcher.Registry.getDirectionSerializer(), nmsFacing),
                new WrappedDataValue(9, WrappedDataWatcher.Registry.getItemStackSerializer(false), nmsItem),
                new WrappedDataValue(10, WrappedDataWatcher.Registry.get(Integer.class), 0)
        ));
        pm.sendServerPacket(viewer, spawn);
        pm.sendServerPacket(viewer, meta2);
    }

    /**
     * Re-invia il pacchetto MOUNT per il quadro gia' attivo del giocatore. Va richiamato dopo eventi che
     * il client tratta resettando i passeggeri (teleport, cambio mondo, respawn) — altrimenti la minimap
     * "si smonta" e sparisce. Chiamato dal listener di lifecycle.
     */
    public void remount(Player viewer) {
        if (!available) return;
        int[] ids = frameIds.get(viewer.getUniqueId());
        if (ids == null) return;
        if (detachFrames()) { // detach: riposiziona la corona, non montare
            Location base = anchorBase(viewer);
            positionFrames(viewer, ids, base);
            lastDetachBase.put(viewer.getUniqueId(), base.clone());
        } else {
            sendMount(viewer, ids);
        }
    }

    /**
     * Ricrea da zero i quadri fittizi alla posizione ATTUALE del giocatore, riusando la stessa MapView
     * (nessun nuovo id mappa → nessun leak). Va usato dopo un TELEPORT (specie lungo): il client scarica
     * le entita' finte quando finiscono fuori range durante il salto, quindi un semplice {@link #remount}
     * rimonterebbe entita' che il client non conosce piu' → mappa sparita. Distrugge + rispawna + rimonta.
     */
    public void resend(Player viewer) {
        if (!available) return;
        UUID uuid = viewer.getUniqueId();
        int[] ids = frameIds.get(uuid);
        MapView view = views.get(uuid);
        if (ids == null || view == null) return;
        try {
            ProtocolManager pm = ProtocolLibrary.getProtocolManager();
            PacketContainer destroy = pm.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
            List<Integer> list = new java.util.ArrayList<>();
            for (int id : ids) list.add(id);
            destroy.getIntLists().write(0, list);
            pm.sendServerPacket(viewer, destroy);

            Object nmsItem = mapNmsItem(view);
            boolean detach = detachFrames();
            Location base = detach ? anchorBase(viewer) : viewer.getLocation();
            for (int i = 0; i < ids.length; i++) {
                Anchor an = DETACH_ANCHORS[i % DETACH_ANCHORS.length];
                Location loc = detach ? base.clone().add(an.x(), an.y(), an.z()) : base;
                spawnFrame(pm, viewer, ids[i], loc, nmsItem, detach ? an.facing() : FACINGS[i]);
            }
            if (detach) lastDetachBase.put(uuid, base.clone()); else sendMount(viewer, ids);
            pushMapContent(viewer, view, power.getResolvedZoomFactor(uuid));
            lastRemountPos.put(uuid, base.clone());
        } catch (Exception e) {
            plugin.getLogger().warning("[Minimap] Errore resend dopo teleport: " + e.getMessage());
        }
    }

    /** Costruisce e invia il pacchetto MOUNT: veicolo = giocatore VERO, passeggeri = SOLO i nostri quadri. */
    private void sendMount(Player viewer, int[] ids) {
        try {
            Object nmsPlayer = nmsHandle(viewer);
            Object packet = MOUNT_CTOR.newInstance(nmsPlayer);
            MOUNT_PASSENGERS_FIELD.set(packet, ids.clone());
            PacketContainer container = new PacketContainer(PacketType.Play.Server.MOUNT, packet);
            ProtocolLibrary.getProtocolManager().sendServerPacket(viewer, container);
        } catch (Exception e) {
            plugin.getLogger().warning("[Minimap] Errore invio MOUNT: " + e.getMessage());
        }
    }

    /** Ricalcola solo il CONTENUTO (pixel) e lo reinvia. Si auto-cancella se il giocatore e' offline o se
     *  questo task e' ormai "orfano" (rimpiazzato da un test piu' recente, o rimosso). */
    private void refreshContent(Player viewer, int[] ids, MapView view) {
        UUID uuid = viewer.getUniqueId();
        if (!viewer.isOnline() || frameIds.get(uuid) != ids) {
            BukkitTask stale = tasks.remove(uuid);
            if (stale != null) stale.cancel();
            return;
        }
        // Anti-flash: mentre il giocatore si MUOVE, il quadro montato (renderizzato dalla sua posizione
        // interpolata dell'ultimo tick) resta indietro rispetto alla camera che avanza e puo' uscire dal
        // campo visivo per un frame (sparisce = "flash"), specie muovendosi nella direzione di sguardo.
        // Rimontarlo mentre ci si muove lo riaggancia alla posizione attuale, riducendo il ritardo. Quando
        // si sta fermi NON si rimonta (evita reseat inutili che potrebbero a loro volta far sfarfallare).
        Location cur = viewer.getLocation();
        Location prev = lastRemountPos.get(uuid);
        if (prev == null || prev.getWorld() != cur.getWorld() || prev.distanceSquared(cur) > 0.04) {
            sendMount(viewer, ids);
            lastRemountPos.put(uuid, cur.clone());
        }
        // Zoom LIVE (blocchi/pixel) letto a ogni refresh: la minimap segue SEMPRE lo zoom `/f map` del
        // giocatore (default config o override `/mf admin setmap`), come la mappa-item e la mappa in chat,
        // senza dover ricreare i quadri quando lo zoom cambia.
        pushMapContent(viewer, view, power.getResolvedZoomFactor(uuid));
    }

    /** Come {@link #refreshContent} ma per la modalita' DETACH: gira ogni tick, riposiziona la corona di
     *  ancore SOLO se il giocatore si e' spostato (la rotazione non sposta nulla: le ancore sono fisse
     *  attorno a lui — e' questo che elimina il flash nei flick di visuale) e rinfresca il CONTENUTO ogni
     *  {@code interval} tick. */
    private void refreshDetached(Player viewer, int[] ids, MapView view, int[] tick, long interval) {
        UUID uuid = viewer.getUniqueId();
        if (!viewer.isOnline() || frameIds.get(uuid) != ids) {
            BukkitTask stale = tasks.remove(uuid);
            if (stale != null) stale.cancel();
            return;
        }
        Location base = anchorBase(viewer);
        Location prev = lastDetachBase.get(uuid);
        if (prev == null || prev.getWorld() != base.getWorld() || prev.distanceSquared(base) > 1.0e-6) {
            positionFrames(viewer, ids, base);
            lastDetachBase.put(uuid, base.clone());
        }
        // Refresh ADATTIVO del contenuto: in volo veloce (elytra/caduta, >1 blocco/tick) il giocatore
        // attraversa pixel a ogni tick e la memoizzazione non puo' aiutare -> ridisegnare 16k pixel ogni
        // tick faceva calare i TPS proprio durante i viaggi in elytra (segnalato dall'utente). A quella
        // velocita' i dettagli non si leggono comunque: rendiamo il refresh 4x piu' raro, e appena si
        // rallenta torna alla frequenza piena.
        long iv = viewer.getVelocity().lengthSquared() > 1.0 ? interval * 4 : interval;
        if (++tick[0] >= iv) {
            tick[0] = 0;
            // Zoom LIVE letto a ogni refresh: la minimap segue sempre lo zoom `/f map` del giocatore
            // (default o `/mf admin setmap`), in pari passo con la mappa-item e la mappa in chat.
            pushMapContent(viewer, view, power.getResolvedZoomFactor(uuid));
        }
    }

    /**
     * Calcola i pixel (via {@link MapService#renderPaletteWithHeader}, header magico nella riga 0) e li
     * spinge al giocatore con un pacchetto MAP costruito a mano — mai dal rendering automatico di Bukkit.
     */
    private void pushMapContent(Player viewer, MapView view, double blocksPerPixel) {
        byte[] palette = maps.renderPaletteWithHeader(viewer, blocksPerPixel);

        MapIdBox idBox = new MapIdBox();
        idBox.id = view.getId();
        MapPatchBox patchBox = new MapPatchBox();
        patchBox.startX = 0; patchBox.startY = 0; patchBox.width = 128; patchBox.height = 128;
        patchBox.mapColors = palette;

        ProtocolManager pm = ProtocolLibrary.getProtocolManager();
        PacketContainer packet = pm.createPacket(PacketType.Play.Server.MAP);
        StructureModifier<Object> mod = packet.getModifier();
        mod.write(0, MAP_ID_WRAPPER.unwrap(idBox));
        mod.write(1, (byte) cosmeticScaleValue(blocksPerPixel));
        mod.write(2, false);
        mod.write(3, Optional.empty());
        mod.write(4, Optional.of(MAP_PATCH_WRAPPER.unwrap(patchBox)));

        pm.sendServerPacket(viewer, packet);
    }

    /** Valore "scale" (0..4, vanilla) del pacchetto MAP piu' vicino a {@code blocksPerPixel} — solo
     *  cosmetico/protocollo, il client non ne ricava altro dato che i pixel li spingiamo noi a mano.
     *  "closer" (0.5) non ha un vero equivalente vanilla: usa lo stesso valore di "closest" (0). */
    private static int cosmeticScaleValue(double blocksPerPixel) {
        if (blocksPerPixel <= 1.0) return 0;
        if (blocksPerPixel <= 2.0) return 1;
        if (blocksPerPixel <= 4.0) return 2;
        if (blocksPerPixel <= 8.0) return 3;
        return 4;
    }

    /**
     * Spinge SUBITO il contenuto (zoom compreso) al giocatore, senza aspettare il prossimo giro del task
     * di refresh — usato da {@code /mf admin setmap} cosi' la minimap cambia raggio nello stesso istante
     * della mappa-item, invece che entro il prossimo tick di refresh (fino a {@code
     * map.render-interval-ticks} tick di ritardo, percepibile se il valore e' alto). Non fa nulla se la
     * minimap del giocatore non e' attiva.
     */
    public void refreshNow(Player viewer) {
        if (!available) return;
        UUID uuid = viewer.getUniqueId();
        MapView view = views.get(uuid);
        if (view == null || !frameIds.containsKey(uuid)) return;
        pushMapContent(viewer, view, power.getResolvedZoomFactor(uuid));
    }

    /** Distrugge (se presente) il display fittizio del giocatore e ferma il suo task di refresh. */
    public void deactivate(Player viewer) {
        if (!available) return;
        views.remove(viewer.getUniqueId());
        lastRemountPos.remove(viewer.getUniqueId());
        lastDetachBase.remove(viewer.getUniqueId());
        BukkitTask task = tasks.remove(viewer.getUniqueId());
        if (task != null) task.cancel();
        int[] ids = frameIds.remove(viewer.getUniqueId());
        if (ids == null) return;
        try {
            ProtocolManager pm = ProtocolLibrary.getProtocolManager();
            PacketContainer destroy = pm.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
            List<Integer> list = new java.util.ArrayList<>();
            for (int id : ids) list.add(id);
            destroy.getIntLists().write(0, list);
            pm.sendServerPacket(viewer, destroy);
        } catch (Exception e) {
            plugin.getLogger().warning("[Minimap] Errore rimozione: " + e.getMessage());
        }
    }

    /**
     * Diagnostica TEMPORANEA: stampa nel log la struttura reale dei pacchetti usati (MAP) per questa
     * esatta versione del protocollo. Tenuta per verifiche future se Minecraft cambiasse questi record.
     */
    public void dumpMapPacketStructure(org.bukkit.command.CommandSender to) {
        PacketContainer p = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.MAP);
        StructureModifier<Object> mod = p.getModifier();
        StringBuilder sb = new StringBuilder("[Minimap] Struttura pacchetto MAP (").append(mod.size()).append(" campi totali):\n");
        for (int i = 0; i < mod.size(); i++) {
            java.lang.reflect.Field f = mod.getField(i);
            sb.append("  [").append(i).append("] ").append(f.getType().getName()).append(" ").append(f.getName())
              .append(" | generic=").append(f.getGenericType()).append("\n");
        }
        sb.append("[Minimap] Campi diretti di MapPatch (").append(MAP_PATCH_CLASS.getName()).append("):\n");
        for (java.lang.reflect.Field f : MAP_PATCH_CLASS.getDeclaredFields()) {
            sb.append("  ").append(f.getType().getName()).append(" ").append(f.getName()).append("\n");
        }
        plugin.getLogger().info(sb.toString());
        to.sendMessage(Colors.translate("&aStruttura pacchetti stampata in console."));
    }
}
