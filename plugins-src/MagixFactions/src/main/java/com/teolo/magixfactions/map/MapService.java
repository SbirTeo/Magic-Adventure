package com.teolo.magixfactions.map;

import com.teolo.magixfactions.manage.ClaimManager;
import com.teolo.magixfactions.manage.FactionManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.Color;
import java.util.UUID;

/**
 * Crea e mantiene l'item "Mappa Fazioni". Bukkit NON serializza i renderer aggiunti a runtime:
 * dopo un riavvio la MapView di un item mappa esiste ancora (i dati mappa sono salvati da Minecraft)
 * ma perde il {@link FactionMapRenderer}, quindi mostrerebbe solo il terreno. Per questo:
 *  - l'item viene "taggato" con un dato persistente ({@link #key}) che lo identifica come mappa fazioni;
 *  - {@link #reattach(ItemStack)} riaggancia il renderer alla MapView quando serve (vedi MapListener),
 *    cosi' la mappa torna a funzionare anche dopo un riavvio, senza rifare /f map.
 *
 * <p>La mappa e' <b>personale</b>: e' taggata anche con l'UUID di chi l'ha ricevuta ({@link #ownerKey})
 * e il renderer si rifiuta di disegnarla per chiunque altro la tenga in mano (regalata/data ad un altro
 * giocatore) — vedi {@link FactionMapRenderer}. Le mappe pre-esistenti (create prima di questa modifica)
 * non hanno questo tag: per compatibilita' continuano a funzionare per chiunque le tenga (nessun owner
 * registrato = nessuna restrizione), esattamente come le mappe create senza il tag di persistenza in
 * v0.10.2 non venivano "resuscitate" dopo un riavvio. Inoltre, {@code /f map} rimuove SEMPRE le mappe
 * fazioni gia' in inventario prima di darne una nuova: non se ne puo' avere piu' di una alla volta.
 */
public final class MapService {

    private final JavaPlugin plugin;
    private final FactionManager fm;
    private final ClaimManager claims;
    private final NamespacedKey key;
    private final NamespacedKey ownerKey;
    private final NamespacedKey zoomKey;
    private final TerrainCache terrain;
    private final AvatarCache avatars;

    public MapService(JavaPlugin plugin, FactionManager fm, ClaimManager claims) {
        this.plugin = plugin; this.fm = fm; this.claims = claims;
        this.key = new NamespacedKey(plugin, "faction_map");
        this.ownerKey = new NamespacedKey(plugin, "faction_map_owner");
        this.zoomKey = new NamespacedKey(plugin, "faction_map_zoom");
        this.terrain = new TerrainCache(plugin); // cache terreno condivisa da tutti i renderer
        this.avatars = new AvatarCache(plugin); // volti dei giocatori per il marcatore della minimap
        long t0 = System.currentTimeMillis();
        MapColorUtil.init(); // precalcola la tabella colore per il dithering (una-tantum all'avvio)
        plugin.getLogger().info("[Map] Tabella colore/dithering pronta in " + (System.currentTimeMillis() - t0) + "ms.");
        terrain.startCaptureTask(plugin); // cattura opportunistica dei chunk caricati dal gioco (stile Cartographer2)
    }

    /** Offre alla cache terreno un chunk appena caricato dal GIOCO (da ChunkLoadEvent): snapshot quasi
     *  gratis di zone che i giocatori stanno gia' visitando — la rivelazione forzata serve cosi' solo per
     *  le zone che nessuno visita (spunto da Cartographer2, vedi {@link TerrainCache#offerLoadedChunk}). */
    public void offerLoadedChunk(org.bukkit.Chunk c) {
        terrain.offerLoadedChunk(c);
    }

    /**
     * Costruisce un nuovo item Mappa Fazioni centrato sul giocatore, a {@code blocksPerPixel} blocchi
     * per pixel (col renderer gia' agganciato e taggato come sua, vedi classe). Rimuove prima qualunque
     * altra Mappa Fazioni gia' in inventario: non se ne puo' avere piu' di una. Lo zoom e il nome sono
     * decisi dal chiamante (in base allo zoom per-giocatore, vedi FCommand).
     */
    public ItemStack create(Player p, double blocksPerPixel, String displayName) {
        removeExistingFactionMaps(p);

        MapView view = Bukkit.createMap(p.getWorld());
        view.setScale(cosmeticScaleFor(blocksPerPixel));
        view.setCenterX(p.getLocation().getBlockX());
        view.setCenterZ(p.getLocation().getBlockZ());
        // Freccetta di tracciamento VANILLA off: la ricentriamo noi ad ogni render, quindi la posizione
        // nativa di vanilla sarebbe sbagliata. Il renderer aggiunge invece i PROPRI cursori nativi (una
        // freccia per giocatore, nitida e rotante) via {@link MapContentBuilder#applyPlayerCursors}.
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        ensureRenderer(view, p.getUniqueId(), blocksPerPixel);

        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        meta.setDisplayName(displayName);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, p.getUniqueId().toString());
        meta.getPersistentDataContainer().set(zoomKey, PersistentDataType.DOUBLE, blocksPerPixel);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Vanilla {@link MapView.Scale} piu' vicina a {@code blocksPerPixel} — puramente cosmetica (nome
     * mostrato dal client per mappe non nostre, comportamento vanilla di fallback): il CONTENUTO che
     * disegniamo noi usa sempre il valore esatto, non questa approssimazione. CLOSEST resta il minimo
     * vanilla rappresentabile: usata anche per zoom PIU' vicini (es. 0.5, "closer") che vanilla non ha.
     */
    private static MapView.Scale cosmeticScaleFor(double blocksPerPixel) {
        if (blocksPerPixel <= 1.0) return MapView.Scale.CLOSEST;
        if (blocksPerPixel <= 2.0) return MapView.Scale.CLOSE;
        if (blocksPerPixel <= 4.0) return MapView.Scale.NORMAL;
        if (blocksPerPixel <= 8.0) return MapView.Scale.FAR;
        return MapView.Scale.FARTHEST;
    }

    /** Formatta il valore blocchi-per-pixel per la UI (0.25, 0.5, 1, ...) senza zeri inutili. */
    public static String formatZoom(double bpp) {
        if (bpp == Math.rint(bpp) && !Double.isInfinite(bpp)) return String.valueOf((long) bpp);
        return java.math.BigDecimal.valueOf(bpp).stripTrailingZeros().toPlainString();
    }

    /** Nome dell'item mappa: config {@code map.item.name-format} con {zoom} -> bpp formattato, colori tradotti. */
    public static String itemName(JavaPlugin plugin, double bpp) {
        String fmt = plugin.getConfig().getString("map.item.name-format", "&6&lMappa Fazioni &7({zoom}x)");
        return com.teolo.magixfactions.util.Colors.translate(fmt.replace("{zoom}", formatZoom(bpp)));
    }

    /** Se l'item e' una Mappa Fazioni, riaggancia il renderer alla sua MapView (idempotente). Lo zoom
     *  viene letto dal tag persistente se presente; le mappe piu' vecchie (create prima di questo tag)
     *  ricadono sull'equivalente in blocchi/pixel della loro {@link MapView.Scale} vanilla. */
    public void reattach(ItemStack item) {
        if (!isFactionMap(item)) return;
        MapMeta meta = (MapMeta) item.getItemMeta();
        MapView view = meta.getMapView();
        if (view == null) return;
        String ownerStr = meta.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        UUID owner = null;
        if (ownerStr != null) { try { owner = UUID.fromString(ownerStr); } catch (IllegalArgumentException ignored) {} }
        Double zoom = meta.getPersistentDataContainer().get(zoomKey, PersistentDataType.DOUBLE);
        double blocksPerPixel = zoom != null ? zoom : (1 << view.getScale().getValue());
        ensureRenderer(view, owner, blocksPerPixel);
    }

    /** Ricontrolla le mani del giocatore e riaggancia il renderer alle sue mappe fazioni. */
    public void reattachHands(Player p) {
        reattach(p.getInventory().getItemInMainHand());
        reattach(p.getInventory().getItemInOffHand());
    }

    /** Rimuove dall'inventario (mano/off-hand/zaino) ogni Mappa Fazioni gia' posseduta: se ne puo' avere solo una.
     *  Pubblico: il chiamante (vedi FCommand) lo invoca PRIMA di controllare se c'e' posto per la nuova mappa,
     *  cosi' liberare lo slot della vecchia conta per quel controllo. Idempotente: {@link #create} lo richiama
     *  comunque, quindi e' sicuro non chiamarlo esplicitamente. */
    public void removeExistingFactionMaps(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isFactionMap(contents[i])) inv.setItem(i, null);
        }
        if (isFactionMap(inv.getItemInOffHand())) inv.setItemInOffHand(null);
    }

    private boolean isFactionMap(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP || !item.hasItemMeta()) return false;
        return item.getItemMeta() instanceof MapMeta m
                && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /**
     * Aggiorna sul posto zoom E nome della Mappa Fazioni GIA' in inventario di un giocatore (mano/
     * off-hand/zaino), senza dover rifare {@code /f map}: utile quando lo zoom cambia (es. {@code /mf
     * admin setmap}, o al login se e' stato modificato mentre era offline) mentre gia' ne tiene una.
     * Tocca solo le SUE mappe (owner combacia); le mappe vecchie senza owner registrato (pre-v0.12.3)
     * vengono comunque aggiornate, essendo gia' "di chiunque le tenga". Aggiorna sia il tag persistente
     * (letto da {@link #reattach} dopo un riavvio) sia il renderer GIA' agganciato ({@link
     * FactionMapRenderer#setBlocksPerPixel}, cosi' il contenuto cambia subito senza aspettare un
     * riavvio/riaggancio). Il NOME e' un campo dell'ItemMeta: va riscritto con {@code setItemMeta},
     * altrimenti resta quello vecchio (es. "Vicino") anche se lo zoom visibile e' cambiato (bug osservato).
     */
    public void updateScale(Player p, double blocksPerPixel, String displayName) {
        String uuidStr = p.getUniqueId().toString();
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack updated = applyScale(contents[i], uuidStr, blocksPerPixel, displayName);
            if (updated != null) inv.setItem(i, updated);
        }
        ItemStack off = applyScale(inv.getItemInOffHand(), uuidStr, blocksPerPixel, displayName);
        if (off != null) inv.setItemInOffHand(off);
    }

    /** Se {@code item} e' una Sua Mappa Fazioni che necessita l'update, la modifica e la ritorna; altrimenti null. */
    private ItemStack applyScale(ItemStack item, String ownerUuidStr, double blocksPerPixel, String displayName) {
        if (!isFactionMap(item)) return null;
        MapMeta meta = (MapMeta) item.getItemMeta();
        String owner = meta.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (owner != null && !owner.equals(ownerUuidStr)) return null; // mappa di un altro giocatore: non toccarla

        boolean changed = false;
        MapView view = meta.getMapView();
        Double currentZoom = meta.getPersistentDataContainer().get(zoomKey, PersistentDataType.DOUBLE);
        if (currentZoom == null || currentZoom != blocksPerPixel) {
            meta.getPersistentDataContainer().set(zoomKey, PersistentDataType.DOUBLE, blocksPerPixel);
            if (view != null) view.setScale(cosmeticScaleFor(blocksPerPixel));
            if (view != null) updateLiveRendererZoom(view, blocksPerPixel);
            changed = true;
        }
        if (displayName != null && !displayName.equals(meta.getDisplayName())) { meta.setDisplayName(displayName); changed = true; }
        if (!changed) return null;

        item.setItemMeta(meta);
        return item;
    }

    /** Aggiorna lo zoom del {@link FactionMapRenderer} GIA' agganciato alla view, se presente. */
    private void updateLiveRendererZoom(MapView view, double blocksPerPixel) {
        for (MapRenderer r : view.getRenderers()) {
            if (r instanceof FactionMapRenderer fmr) { fmr.setBlocksPerPixel(blocksPerPixel); return; }
        }
    }

    /**
     * Aggiunge il FactionMapRenderer alla view solo se non c'e' gia' (evita doppioni) — se c'e' gia',
     * si limita ad aggiornarne lo zoom. Il renderer di default di Bukkit (terreno esplorato + freccetta
     * di posizione) resta AL SUO POSTO: e' lui che disegna nativamente la freccetta (v0.12.8 lo rimuoveva
     * per evitare le "due mappe sovrapposte", ma cosi' faceva sparire anche la freccetta — non e' un
     * meccanismo separato dai flag di tracciamento come pensavamo). La sovrapposizione si evita invece
     * nel renderer stesso: {@link FactionMapRenderer} ora copre SEMPRE ogni pixel in modo opaco (mai
     * trasparente), cosi' il terreno vanilla sottostante non puo' mai comparire ne' fare da "sfondo
     * diverso" — la freccetta pero' resta visibile, perche' i cursori sono un livello separato dai
     * pixel del terreno.
     */
    private void ensureRenderer(MapView view, UUID owner, double blocksPerPixel) {
        for (MapRenderer r : view.getRenderers()) {
            if (r instanceof FactionMapRenderer fmr) { fmr.setBlocksPerPixel(blocksPerPixel); return; }
        }
        view.addRenderer(new FactionMapRenderer(plugin, fm, claims, terrain, avatars, owner, blocksPerPixel));
    }

    /** Scarta il terreno cache per la colonna (bx,bz): va chiamato quando un blocco cambia li', cosi'
     *  sia l'item mappa che la minimap HUD (condividono lo stesso {@link TerrainCache}) riflettono la
     *  modifica al prossimo redraw invece di aspettare la TTL naturale (vedi {@code TerrainChangeListener}). */
    public void invalidateTerrain(org.bukkit.World w, int bx, int bz) {
        terrain.invalidate(w, bx, bz);
    }

    /**
     * Crea una MapView "headless" per la minimap HUD: stessa logica di creazione di {@link #create},
     * ma NON viene mai incapsulata in un ItemStack consegnato al giocatore — serve solo come sorgente
     * di pixel (texture) per l'ItemDisplay fittizio via pacchetti (vedi {@code minimap.MinimapManager}).
     * A differenza di {@link #create}, qui rimuoviamo TUTTI i renderer di default (nessuna freccetta da
     * disegnare: non e' una mappa "in mano", non ha senso un cursore di tracciamento sopra un HUD).
     *
     * <p>Nota di costo: {@code Bukkit.createMap} registra comunque un ID mappa persistito nei dati del
     * mondo — non e' "gratis" a lungo termine se se ne creano tante. Il chiamante DEVE mantenere una
     * sola MapView per giocatore per tutta la sessione online (mai ricrearla ad ogni aggiornamento).
     */
    public MapView createHeadless(Player p, double blocksPerPixel, UUID owner) {
        MapView view = Bukkit.createMap(p.getWorld());
        view.setScale(cosmeticScaleFor(blocksPerPixel));
        view.setCenterX(p.getLocation().getBlockX());
        view.setCenterZ(p.getLocation().getBlockZ());
        view.setTrackingPosition(false);  // non e' un item fisico in mano: la ricentriamo noi ad ogni render
        view.setUnlimitedTracking(false);
        for (MapRenderer r : new java.util.ArrayList<>(view.getRenderers())) view.removeRenderer(r);
        view.addRenderer(new MinimapRenderer(plugin, fm, claims, terrain, avatars, owner));
        return view;
    }

    /**
     * Calcola i 128x128 pixel (terreno + territori, stessa logica di {@link MapContentBuilder} riusata
     * anche dall'item mappa) centrati sulla posizione ATTUALE del giocatore, gia' convertiti nella
     * palette di byte di Minecraft ({@link org.bukkit.map.MapPalette#matchColor(java.awt.Color)}).
     * Usato dalla minimap HUD per spingere i pixel via pacchetto MAP costruito a mano (vedi
     * {@code minimap.MinimapManager}) — non passa mai da un vero {@link org.bukkit.map.MapCanvas},
     * perche' l'item e' fittizio e Bukkit non lo renderizzerebbe mai da solo (nessun vero
     * giocatore lo "tiene" secondo Bukkit).
     */
    // Cache RGB->byte-palette: MapPalette.matchColor fa una ricerca del colore piu' vicino su ~140 voci,
    // e veniva chiamata 16384 volte per render (una per pixel) — grosso costo, era buona parte dei ~28ms
    // del render. I colori distinti su una mappa sono pochi (palette terreno + tinte fazione + marcatori),
    // quindi memorizzarli rende quasi tutte le conversioni un lookup O(1). Solo main thread (render), HashMap ok.
    private final java.util.HashMap<Integer, Byte> colorCache = new java.util.HashMap<>();

    private byte matchColorCached(Color c) {
        int rgb = c.getRGB();
        Byte b = colorCache.get(rgb);
        if (b == null) {
            // Con il supersampling i colori medi sono molti e distinti: cap per non far crescere la cache
            // all'infinito (svuota e riparte; i colori tornano subito in cache al primo uso).
            if (colorCache.size() > 60000) colorCache.clear();
            b = org.bukkit.map.MapPalette.matchColor(c);
            colorCache.put(rgb, b);
        }
        return b;
    }

    // --- Memoizzazione del layer BASE della minimap (terreno+territori+cardinali+home) ---------------
    // Il base cambia SOLO quando: il centro attraversa un confine di pixel, cambia lo zoom/mondo, cambia
    // il terreno (TerrainCache.version) o i territori (ClaimManager.version). Tutto il resto (nomi dei
    // giocatori, frecce) e' disegnato sopra a ogni tick. Senza memo si ricalcolavano 16k pixel per
    // giocatore A OGNI TICK (misurato: tick mediano 60ms con UN solo giocatore col la mappa a zoom
    // lontano) — insostenibile con 40-50 giocatori. Spunto dall'architettura di Cartographer2 (dati
    // cache-ati e riusati, mai ricalcolati senza motivo).
    private static final class BaseMemo {
        int cX, cZ; double bpp; String world; int tVer, cVer; long ms; boolean complete;
        Color[][] base;
    }

    private final java.util.HashMap<UUID, BaseMemo> minimapMemo = new java.util.HashMap<>();

    /** Centro quantizzato alla griglia dei PIXEL (multipli di bpp, per bpp>=1): sotto il pixel la mappa
     *  non puo' comunque scorrere, quindi la resa e' identica ma il frame resta riusabile tra un
     *  attraversamento di pixel e l'altro. */
    private static int quantCenter(int block, double bpp) {
        int step = bpp >= 1 ? (int) Math.round(bpp) : 1;
        return Math.floorDiv(block, step) * step;
    }

    public byte[] renderPalette(Player p, double blocksPerPixel) {
        int cX = quantCenter(p.getLocation().getBlockX(), blocksPerPixel);
        int cZ = quantCenter(p.getLocation().getBlockZ(), blocksPerPixel);
        boolean dither = plugin.getConfig().getBoolean("map.dither", false) && MapColorUtil.isReady();
        if (dither) {
            // Percorso dithering (opt-in): senza memo, non vale la complessita' di memoizzare per una
            // modalita' disattivata di default.
            boolean[][] protect = new boolean[128][128];
            Color[][] colors = MapContentBuilder.computeColors(p, fm, claims, terrain, blocksPerPixel,
                    plugin, cX, cZ, 1, avatars, protect, null);
            return MapColorUtil.dither(colors, protect);
        }

        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();
        BaseMemo m = minimapMemo.get(uuid);
        boolean valid = m != null && m.cX == cX && m.cZ == cZ && m.bpp == blocksPerPixel
                && p.getWorld().getName().equals(m.world)
                && m.tVer == terrain.version() && m.cVer == claims.version()
                && now - m.ms < (m.complete ? 2000 : 500); // tetto: cambi rari (relazioni/reload) entro 2s
        if (!valid) {
            boolean[] complete = {true};
            Color[][] base = MapContentBuilder.computeColors(p, fm, claims, terrain, blocksPerPixel,
                    plugin, cX, cZ, 1, avatars, null, complete);
            if (minimapMemo.size() > 100) minimapMemo.clear(); // igiene: mai piu' di ~1 voce per giocatore attivo
            m = new BaseMemo();
            m.cX = cX; m.cZ = cZ; m.bpp = blocksPerPixel; m.world = p.getWorld().getName();
            m.tVer = terrain.version(); m.cVer = claims.version(); m.ms = now;
            m.complete = complete[0]; m.base = base;
            minimapMemo.put(uuid, m);
        }
        // Il frame (terreno + territori + cardinali + home) resta identico finche' il memo e' valido: le
        // frecce-giocatore le disegna lo shader dall'header, non sono cotte nei pixel.
        byte[] out = new byte[128 * 128];
        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 128; y++) {
                out[y * 128 + x] = matchColorCached(m.base[x][y]);
            }
        }
        return out;
    }

    // --- Header frecce minimap ------------------------------------------------------------------------
    // Sulla minimap i cursori nativi non si agganciano (vedi MapContentBuilder): le frecce dei giocatori
    // le disegna lo SHADER (nitide, ruotano fluide). I loro dati (posizione/angolo/tipo) viaggiano dal
    // plugin allo shader impacchettati come BIT nei pixel dell'header (prime 2 righe della map texture,
    // nascoste dallo shader). Ogni record e' un int a 22 bit (LSB-first): 1 bit valido, 7 px, 7 py, 5
    // bucket-yaw (0..STEPS-1), 2 tipo. ARROW_MAX/ARROW_BITS/ARROW_ANGLE_STEPS DEVONO combaciare con
    // rendertype_text.vsh/fsh (#define ARROW_MAX, il loop di decode e la formula bucket/32*2pi).
    public static final int ARROW_MAX = 6;          // max frecce sulla minimap (limite varying dello shader)
    public static final int ARROW_ANGLE_STEPS = 32; // bucket yaw (5 bit): passi di 11.25°
    private static final int ARROW_BITS = 22;       // bit per record (1 valido + 7 px + 7 py + 5 angolo + 2 tipo)
    private static final byte HDR_ON  = 18; // byte palette reso 0xFF0000 (MK0): bit "1"
    private static final byte HDR_OFF = 49; // byte palette reso 0x3737DC (MK2): bit "0"

    /**
     * Come {@link #renderPalette}, ma scrive anche l'HEADER MAGICO nei primi byte, usato dalla minimap
     * HUD (metodo studiato da NMinimap, github.com/NezuShin/NMinimap, riscritto):
     * <ul>
     *   <li><b>Pixel 0,1,2</b> = byte palette 18/4/49 (RGB 0xFF0000/0x597D27/0x3737DC): firma che dice
     *       allo shader "questa mappa e' la minimap, agganciala a schermo".</li>
     *   <li><b>Pixel 3..3+ARROW_MAX*ARROW_BITS-1</b> = fino a {@link #ARROW_MAX} record-freccia (uno per
     *       giocatore visibile, il PROPRIO incluso), ognuno {@link #ARROW_BITS} bit. Lo shader li legge e
     *       disegna una freccia per giocatore, ruotata secondo lo yaw e colorata per relazione, nitida e
     *       a risoluzione schermo (i cursori nativi non funzionano sulla minimap, vedi
     *       {@link MapContentBuilder#applyPlayerCursors}). Sostituisce il vecchio "quadrante" a freccia
     *       singola: ora la minimap mostra le stesse frecce della mappa-item.</li>
     * </ul>
     * Le prime 2 righe (dove finiscono i record) sono nascoste dal fragment shader, non si vedono.
     */
    public byte[] renderPaletteWithHeader(Player p, double blocksPerPixel) {
        byte[] out = renderPalette(p, blocksPerPixel); // terreno + territori + cardinali + home (via computeColors)
        out[0] = 18;
        out[1] = 4;
        out[2] = 49;
        // Azzera l'area record (tutti gli slot vuoti: bit valido = 0 = HDR_OFF).
        int total = ARROW_MAX * ARROW_BITS;
        for (int i = 0; i < total; i++) out[3 + i] = HDR_OFF;
        // Record delle frecce (proprio marcatore nell'ultimo slot -> disegnato per ultimo dallo shader,
        // quindi sopra gli altri). Slot vuoto = -1. IMPORTANTE: stesso centro QUANTIZZATO del contenuto
        // (renderPalette), altrimenti le frecce sarebbero sfalsate di una frazione di pixel dal terreno.
        int cX = quantCenter(p.getLocation().getBlockX(), blocksPerPixel);
        int cZ = quantCenter(p.getLocation().getBlockZ(), blocksPerPixel);
        int[] recs = MapContentBuilder.computePlayerArrows(p, fm, blocksPerPixel,
                cX, cZ, ARROW_MAX, ARROW_ANGLE_STEPS);
        for (int s = 0; s < ARROW_MAX; s++) {
            int packed = recs[s];
            if (packed < 0) continue;
            int base = 3 + s * ARROW_BITS;
            for (int b = 0; b < ARROW_BITS; b++)
                out[base + b] = ((packed >> b) & 1) == 1 ? HDR_ON : HDR_OFF;
        }
        return out;
    }

}
