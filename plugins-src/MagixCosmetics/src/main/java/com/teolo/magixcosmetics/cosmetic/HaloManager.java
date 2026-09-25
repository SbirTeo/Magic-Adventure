package com.teolo.magixcosmetics.cosmetic;

import com.destroystokyo.paper.ParticleBuilder;
import com.teolo.magixcosmetics.MagixCosmetics;
import com.teolo.magixcosmetics.hook.LuckPermsHook;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * L'aureola che gira sopra la testa dei VIP.
 *
 * <p>E' un <b>solo puntino</b> di particella {@code DUST} che orbita in cerchio appena sopra la
 * testa: a ogni giro del task l'angolo avanza di un passo e la particella si sposta lungo il
 * cerchio. Non un anello intero ridisegnato ogni volta — quello, da fermo, sembra pulsare; qui
 * si vede un punto che ruota, con la breve scia lasciata dalle particelle che sfumano.</p>
 *
 * <p>Un task unico passa in rassegna i giocatori online e disegna solo su quelli a cui l'aureola
 * spetta: con un centinaio di persone collegate un task per ciascuno si sentirebbe.</p>
 *
 * <p>Il COLORE non e' fisso: {@code halo.colors} nel config elenca una tavolozza di nomi (giallo,
 * rosso, blu...), ognuno legato a un permesso {@code magixcosmetics.halo.color.<nome>}. Un
 * giocatore con il permesso base {@code magixcosmetics.halo} ma <b>nessun</b> permesso colore non
 * ha nessun colore assegnato e l'aureola semplicemente non si disegna; con un permesso colore la
 * mostra in automatico in quel colore, e se ne ha piu' di uno sceglie con {@code /halo setcolor}.</p>
 *
 * <p>L'aureola sparisce anche durante un combattimento PvP e per qualche secondo dopo (vedi
 * {@link HaloCombatListener}), non e' in spettatore e — se il config lo chiede — non e' in vanish
 * o invisibile: un'aureola su uno staff invisibile lo tradirebbe.</p>
 */
public final class HaloManager {

    /** Chi ha questo permesso e' un VIP e puo' vedere l'aureola sopra la testa. */
    public static final String HALO_PERMISSION = "magixcosmetics.halo";

    /**
     * La tavolozza predefinita: i nomi validi per halo.colors nel config, per il permesso
     * magixcosmetics.halo.color.<nome> e per /halo setcolor <nome>. Fissa (non letta a giro libero
     * dal config) cosi' ogni nome ha anche il suo permesso dichiarato in plugin.yml.
     */
    private static final List<String> COLOR_NAMES = List.of(
            "yellow", "red", "orange", "lime", "aqua", "blue", "purple", "pink", "white");

    private final MagixCosmetics plugin;

    // Valori letti dal config a ogni load()/reload.
    private boolean enabled;
    /** Tavolozza: nome del colore (minuscolo, es. "yellow") -> colore vero. Ordine del config. */
    private final Map<String, Color> colors = new LinkedHashMap<>();
    private double radius;
    private double height;
    private float size;
    /** Da quanti blocchi si vede (particella "a lunga distanza": oltre 32 serve, massimo 512). */
    private double viewDistance;
    private long interval;
    private double spinSpeed;
    private boolean hideWhenVanished;
    private boolean hideWhenInvisible;
    private boolean hideWhileFighting;
    private long combatCooldownMs;

    /** Chi ha spento la PROPRIA aureola con /halo off. Persistito su players.yml (vedi {@link #store}). */
    private final Set<UUID> disabled = new HashSet<>();
    /** Il colore scelto da ognuno con /halo setcolor. Persistito su players.yml (vedi {@link #store}). */
    private final Map<UUID, String> chosenColor = new HashMap<>();
    /**
     * Il colore a cui ognuno ha DIRITTO (permesso base + permesso colore, scelta compresa), preso
     * mentre e' online e ricordato su players.yml: da offline i permessi non si possono leggere, ma
     * la sua statua (vedi {@link #statueColor}) deve sapere lo stesso che aureola mostrare.
     */
    private final Map<UUID, String> entitledColor = new HashMap<>();
    private final HaloStore store;
    /** Istante (ms) dell'ultimo colpo dato o subito in PvP: serve al cooldown post-combattimento. */
    private final Map<UUID, Long> lastCombatAt = new HashMap<>();

    private BukkitTask task;
    /** Posizione del puntino lungo il cerchio: avanza di spinSpeed a ogni giro, cosi' orbita. */
    private double angle;

    /** Permessi dei giocatori offline, per l'aureola sulla loro statua. */
    private final LuckPermsHook luckPerms;
    /** Quando (ms) e' stato letto da LuckPerms l'ultima volta il diritto di un giocatore offline. */
    private final Map<UUID, Long> offlineCheckedAt = new HashMap<>();
    /** Letture offline gia' in corso: una per giocatore alla volta. */
    private final Set<UUID> offlinePending = new HashSet<>();
    /** Ogni quanto si rilegge un giocatore offline: un permesso tolto da offline si vede entro questo tempo. */
    private static final long OFFLINE_REFRESH_MS = 10 * 60_000L;

    public HaloManager(MagixCosmetics plugin) {
        this.plugin = plugin;
        this.store = new HaloStore(plugin);
        this.luckPerms = new LuckPermsHook(plugin);
        luckPerms.setup();
    }

    /** Rilegge i valori dal config.yml e le scelte personali salvate su players.yml. */
    public void load() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("halo.enabled", true);

        colors.clear();
        for (String name : COLOR_NAMES) {
            String hex = c.getString("halo.colors." + name, null);
            if (hex != null) colors.put(name, parseColor(hex));
        }

        radius = c.getDouble("halo.radius", 0.4);
        height = c.getDouble("halo.height", 2.2);
        size = (float) c.getDouble("halo.particle-size", 0.8);
        viewDistance = Math.max(1, Math.min(512, c.getDouble("halo.view-distance", 96)));
        interval = Math.max(1L, c.getLong("halo.update-interval-ticks", 1));
        spinSpeed = c.getDouble("halo.spin-speed", 0.25);
        hideWhenVanished = c.getBoolean("halo.hide-when-vanished", true);
        hideWhenInvisible = c.getBoolean("halo.hide-when-invisible", true);
        hideWhileFighting = c.getBoolean("halo.combat.hide-while-fighting", true);
        combatCooldownMs = Math.max(0, c.getLong("halo.combat.cooldown-after-combat-seconds", 30)) * 1000L;

        store.load(chosenColor, disabled, entitledColor);
        offlineCheckedAt.clear(); // dopo un reload i giocatori offline si rileggono da capo
    }

    /** Fa ripartire il task col nuovo intervallo (o non parte affatto se l'aureola e' spenta). */
    public void start() {
        stop();
        if (!enabled) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        angle += spinSpeed;
        if (angle > Math.PI * 2) angle -= Math.PI * 2;   // niente overflow su uptime lunghi
        for (Player p : Bukkit.getOnlinePlayers()) {
            remember(p);
            Color color = effectiveColor(p);
            if (color != null) drawAt(p.getLocation(), color);
        }
    }

    /** Aggiorna il colore a cui ha diritto: su disco solo quando cambia (permesso dato o tolto, setcolor). */
    private void remember(Player p) {
        setEntitled(p.getUniqueId(), p.hasPermission(HALO_PERMISSION) ? activeColorName(p) : null);
    }

    private void setEntitled(UUID id, String name) {
        if (Objects.equals(name, entitledColor.get(id))) return;
        if (name == null) entitledColor.remove(id);
        else entitledColor.put(id, name);
        store.save(chosenColor, disabled, entitledColor);
    }

    /**
     * Rilegge da LuckPerms, su un altro thread, a quale colore ha diritto un giocatore OFFLINE: la
     * prima volta che la sua statua lo chiede e poi ogni {@link #OFFLINE_REFRESH_MS}. Stesse regole
     * di {@link #activeColorName}: il colore scelto se ne ha ancora il permesso, altrimenti il primo
     * della tavolozza. Senza LuckPerms resta il colore ricordato da quando era online.
     */
    private void refreshOffline(UUID id) {
        if (!luckPerms.available() || offlinePending.contains(id)) return;
        Long at = offlineCheckedAt.get(id);
        if (at != null && System.currentTimeMillis() - at < OFFLINE_REFRESH_MS) return;
        offlinePending.add(id);
        List<String> names = new ArrayList<>(colors.keySet());
        String chosen = chosenColor.get(id);
        List<String> nodes = new ArrayList<>();
        nodes.add(HALO_PERMISSION);
        for (String n : names) nodes.add(colorPermission(n));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Boolean> perms = luckPerms.check(id, nodes);
            Bukkit.getScheduler().runTask(plugin, () -> {
                offlinePending.remove(id);
                offlineCheckedAt.put(id, System.currentTimeMillis());
                // Lettura fallita: resta quello che si sapeva. Rientrato nel frattempo: ci pensa remember().
                if (perms == null || Bukkit.getPlayer(id) != null) return;
                String entitled = null;
                if (perms.getOrDefault(HALO_PERMISSION, false)) {
                    if (chosen != null && names.contains(chosen)
                            && perms.getOrDefault(colorPermission(chosen), false)) {
                        entitled = chosen;
                    } else {
                        for (String n : names) {
                            if (perms.getOrDefault(colorPermission(n), false)) {
                                entitled = n;
                                break;
                            }
                        }
                    }
                }
                setEntitled(id, entitled);
            });
        });
    }

    /**
     * Il colore dell'aureola da mostrare sulla STATUA di un giocatore (un'entita' di MagixEntities
     * con la sua skin), o {@code null} se non ne ha una. Vale anche da offline: i permessi si
     * leggono da LuckPerms (vedi {@link #refreshOffline}); finche' la lettura non torna vale il
     * colore ricordato. Contano il permesso, il colore e /halo off; NON contano combattimento,
     * vanish, invisibilita' e spettatore, che riguardano il corpo del giocatore, non la sua statua.
     * Lo chiama MagixEntities per riflessione.
     */
    public Color statueColor(String playerName) {
        if (!enabled || playerName == null || playerName.isBlank()) return null;
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) return statueColor(online.getUniqueId());
        // Solo dalla cache del server (usercache): niente richieste a Mojang a ogni tick.
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(playerName);
        return off == null ? null : statueColor(off.getUniqueId());
    }

    /**
     * Come {@link #statueColor(String)}, per UUID: stessa regola (permesso, colore, /halo off, anche
     * da offline). La usa anche MagixWeb, per riflessione, per mostrare l'aureola sul sito.
     */
    public Color statueColor(UUID id) {
        if (!enabled || id == null) return null;
        Player online = Bukkit.getPlayer(id);
        if (online != null) remember(online);
        else refreshOffline(id);
        if (disabled.contains(id)) return null;
        String name = entitledColor.get(id);
        return name == null ? null : colors.get(name);
    }

    /**
     * Il colore con cui disegnare l'aureola di questo giocatore ADESSO, o {@code null} se non deve
     * vedersi affatto (permesso mancante, personalmente spenta, spettatore/vanish/invisibile, in
     * combattimento, o nessun colore assegnato).
     */
    public Color effectiveColor(Player p) {
        if (!enabled) return null;
        if (!p.hasPermission(HALO_PERMISSION)) return null;
        if (disabled.contains(p.getUniqueId())) return null;
        if (p.getGameMode() == GameMode.SPECTATOR) return null;
        if (hideWhenVanished && isVanished(p)) return null;
        if (hideWhenInvisible && p.hasPotionEffect(PotionEffectType.INVISIBILITY)) return null;
        if (isInCombat(p.getUniqueId())) return null;
        String name = activeColorName(p);
        return name == null ? null : colors.get(name);
    }

    /**
     * Disegna il puntino dell'aureola sopra un punto qualsiasi, con lo stesso stile e lo stesso
     * angolo del giro in corso: oltre al proprio {@link #tick()}, lo usa anche MagixEntities per
     * riprodurre l'aureola di un giocatore sopra la sua entita' in modalita' skin "mirror", per
     * riflessione (nessuna dipendenza Maven fra i due plugin — vedi {@code MagixCosmeticsHook} in
     * MagixEntities). Nessun controllo di permesso/stato qui: il chiamante decide gia' il colore
     * con {@link #effectiveColor(Player)} ({@code null} = niente aureola, non disegnare).
     */
    public void drawAt(Location base, Color color) {
        drawAt(base, color, 1.0, null);
    }

    /**
     * Come {@link #drawAt(Location, Color)}, per un'entita' ingrandita o rimpicciolita
     * ({@code scale}: altezza, giro e puntino crescono col modello) e, con {@code onlyFor} non null,
     * visibile solo a quel giocatore: serve alle copie "mirror" di MagixEntities, che ognuno vede
     * per conto suo nello stesso punto — disegnata sul mondo, ognuno vedrebbe anche l'aureola degli
     * altri sopra la propria copia.
     */
    public void drawAt(Location base, Color color, double scale, Player onlyFor) {
        if (color == null || base.getWorld() == null) return;
        double s = scale > 0 ? scale : 1.0;
        // Un solo punto, alla posizione corrente lungo il cerchio: il prossimo tick sara' poco
        // piu' avanti, e cosi' orbita. Le particelle vecchie sfumano da sole e lasciano una breve scia.
        double x = Math.cos(angle) * radius * s;
        double z = Math.sin(angle) * radius * s;
        Location at = base.clone().add(x, height * s, z);
        // DUST accetta al massimo 4.0 di grandezza.
        Particle.DustOptions dust = new Particle.DustOptions(color, (float) Math.min(4.0, size * s));
        // force(true) = particella "a lunga distanza": senza, il server la manda solo entro 32
        // blocchi e l'aureola di una statua gigante sparisce molto prima della statua. Chi la
        // riceve lo decidiamo noi, entro halo.view-distance.
        // Con DUST il "count" 1 e gli offset a zero mettono la particella esattamente li'.
        ParticleBuilder particle = new ParticleBuilder(Particle.DUST)
                .location(at).count(1).offset(0, 0, 0).extra(0).data(dust).force(true);
        if (onlyFor != null) {
            if (!onlyFor.getWorld().equals(at.getWorld())
                    || onlyFor.getLocation().distanceSquared(at) > viewDistance * viewDistance) return;
            particle.receivers(onlyFor);
        } else {
            particle.receivers(at.getNearbyPlayers(viewDistance));
        }
        particle.spawn();
    }

    // ------------------------------------------------------------- toggle personale

    public boolean isDisabled(UUID id) {
        return disabled.contains(id);
    }

    /** on=true: torna a mostrare l'aureola; on=false: la spegne solo per lui. */
    public void toggle(UUID id, boolean on) {
        if (on) disabled.remove(id);
        else disabled.add(id);
        store.save(chosenColor, disabled, entitledColor);
    }

    public boolean enabled() {
        return enabled;
    }

    // ------------------------------------------------------------- colore

    /** Tutti i nomi di colore della tavolozza, nell'ordine del config. */
    public List<String> colorNames() {
        return new ArrayList<>(colors.keySet());
    }

    /**
     * Il colore che questo giocatore vede adesso: quello scelto con /halo setcolor, se il permesso
     * ce l'ha ancora; altrimenti il primo colore della tavolozza per cui ha il permesso; se non ne
     * ha nessuno, {@code null} — niente colore, niente aureola, anche con il permesso base.
     */
    public String activeColorName(Player p) {
        String chosen = chosenColor.get(p.getUniqueId());
        if (chosen != null && colors.containsKey(chosen) && p.hasPermission(colorPermission(chosen))) {
            return chosen;
        }
        for (String name : colors.keySet()) {
            if (p.hasPermission(colorPermission(name))) return name;
        }
        return null;
    }

    /** Ricorda la scelta di colore del giocatore (il comando controlla gia' il permesso prima di chiamarlo). */
    public void setColor(UUID id, String name) {
        chosenColor.put(id, name);
        store.save(chosenColor, disabled, entitledColor);
    }

    /** Il nodo di permesso che sblocca un colore, es. "magixcosmetics.halo.color.yellow". */
    public static String colorPermission(String name) {
        return HALO_PERMISSION + ".color." + name;
    }

    // ------------------------------------------------------------- combattimento

    /** true se ha dato o subito un colpo PvP da meno di halo.combat.cooldown-after-combat-seconds. */
    public boolean isInCombat(UUID id) {
        if (!hideWhileFighting) return false;
        Long last = lastCombatAt.get(id);
        return last != null && System.currentTimeMillis() - last < combatCooldownMs;
    }

    /** Segna il momento del colpo: lo chiama {@link HaloCombatListener} su chi colpisce e su chi viene colpito. */
    public void markCombat(UUID id) {
        lastCombatAt.put(id, System.currentTimeMillis());
    }

    // ------------------------------------------------------------- utilita'

    /** Vanish alla maniera degli altri plugin Magix: metadata "vanished" a true (lo mette CMI). */
    private static boolean isVanished(Player p) {
        for (var meta : p.getMetadata("vanished")) {
            if (meta.asBoolean()) return true;
        }
        return false;
    }

    /** "#RRGGBB" (o "&#RRGGBB") -> Color; se il testo e' scritto male, ripiega sul giallo. */
    private static Color parseColor(String hex) {
        try {
            String h = hex == null ? "" : hex.trim();
            if (h.startsWith("&#")) h = h.substring(2);
            else if (h.startsWith("#")) h = h.substring(1);
            int rgb = Integer.parseInt(h, 16);
            return Color.fromRGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        } catch (Exception e) {
            return Color.fromRGB(0xFF, 0xDD, 0x33);
        }
    }
}
