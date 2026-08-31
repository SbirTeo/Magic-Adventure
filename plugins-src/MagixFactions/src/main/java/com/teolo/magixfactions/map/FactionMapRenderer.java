package com.teolo.magixfactions.map;

import com.teolo.magixfactions.manage.ClaimManager;
import com.teolo.magixfactions.manage.FactionManager;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * Renderer per l'item "Mappa Fazioni": disegna sopra il terreno i territori delle fazioni,
 * colorati in base alla relazione di chi tiene la mappa (tua/alleata/nemica/altrui).
 * Il disegno vero e proprio (terreno + territori) e' in {@link MapContentBuilder}, condiviso anche
 * con la minimap HUD ({@link MinimapRenderer}) — qui c'e' solo throttle, ricentraggio e owner-check.
 *
 * <p>La mappa segue SEMPRE il giocatore (si ricentra ad ogni chiamata), ma con un ritmo molto piu'
 * fitto di prima (config {@code map.item.render-interval-ticks}, default 5) cosi' ogni singolo
 * spostamento del centro e' minuscolo (in genere sotto il blocco) e non si percepisce come uno scatto
 * — la mappa scorre in modo fluido invece di "saltare". Questo e' sostenibile perche' la parte
 * davvero costosa (fotografare i chunk, {@link TerrainCache#sampleAt}) e' gia' protetta a monte da
 * una cache lunga + un budget per finestra temporale indipendenti da quanto spesso chiamiamo render():
 * il ciclo in {@link MapContentBuilder}, quando i chunk sono gia' in cache, fa solo letture in
 * memoria (nessuna nuova chiamata pesante a Bukkit/NMS).
 *
 * <p>La mappa e' personale: se {@link #owner} e' impostato (item creato dopo l'aggiunta di questa
 * regola), il rendering si rifiuta di disegnare per chiunque NON sia quel giocatore — cosi' regalare o
 * far tenere la propria mappa a qualcun altro non funziona. Le mappe piu' vecchie (owner non registrato,
 * {@code null}) restano utilizzabili da chiunque per compatibilita', vedi {@link MapService}.
 */
public final class FactionMapRenderer extends MapRenderer {

    private static final long TICK_MS = 50L; // durata nominale di un tick di server (1/20 di secondo)

    private final JavaPlugin plugin;
    private final FactionManager fm;
    private final ClaimManager claims;
    private final TerrainCache terrain;
    private final AvatarCache avatars;
    private final UUID owner; // null = mappa vecchia, senza owner registrato: nessuna restrizione
    private volatile double blocksPerPixel; // mutabile: /mf admin setmap aggiorna lo zoom di una mappa gia' in mano

    public FactionMapRenderer(JavaPlugin plugin, FactionManager fm, ClaimManager claims, TerrainCache terrain,
                               AvatarCache avatars, UUID owner, double blocksPerPixel) {
        super(true); // per-giocatore
        this.plugin = plugin; this.fm = fm; this.claims = claims; this.terrain = terrain; this.avatars = avatars;
        this.owner = owner;
        this.blocksPerPixel = blocksPerPixel;
    }

    /** Aggiorna lo zoom di una mappa GIA' in mano, senza doverla ricreare (vedi {@link MapService#updateScale}). */
    void setBlocksPerPixel(double blocksPerPixel) { this.blocksPerPixel = blocksPerPixel; }

    private long lastRender = 0L;

    // --- Memoizzazione del frame (fix TPS: tick mediano 60ms con UN giocatore a zoom lontano) ---------
    // Il contenuto pixel dipende SOLO da: centro (quantizzato alla griglia pixel), zoom, mondo, versione
    // terreno (TerrainCache) e versione territori (ClaimManager). Se nulla e' cambiato, NON si ridipinge:
    // il MapCanvas conserva i pixel del render precedente e si aggiornano solo i cursori nativi (frecce,
    // separati dai pixel e quasi gratis). Prima si ricalcolavano 16k pixel (x9 col supersampling) a OGNI
    // tick anche da fermi — insostenibile con 40-50 giocatori. Tetto di eta' (2s; 0.5s se il frame aveva
    // buchi in attesa di reveal) per catturare i cambi rari non versionati (relazioni, /mf reload).
    private int lastCX, lastCZ;
    private double lastBpp = -1;
    private String lastWorld;
    private int lastTVer = -1, lastCVer = -1;
    private long lastPaintMs;
    private boolean lastComplete;
    private boolean firstPaintDone; // primo paint post-aggancio: reso a ss=1 (leggero) per non pesare sul join

    @Override
    public void render(MapView view, MapCanvas canvas, Player player) {
        // Mappa personale: se ha un owner registrato e non sei tu, non disegnare nulla (la mappa
        // resta bianca/vanilla in mano a chiunque non sia il proprietario originale).
        if (owner != null && !player.getUniqueId().equals(owner)) return;

        // Ricentraggio e ridisegno restano atomici (stessa chiamata): altrimenti la vista si sposta
        // subito ma i pixel restano quelli vecchi fino al ridisegno successivo, e si vede uno scatto.
        // Intervallo configurabile in TICK (non ms): un margine di 5ms assorbe il piccolo jitter del
        // timer di sistema attorno al confine dei 50ms, cosi' non si salta un tick per un soffio.
        int intervalTicks = Math.max(1, plugin.getConfig().getInt("map.render-interval-ticks", 1));
        long intervalMs = intervalTicks * TICK_MS - 5L;
        // Refresh ADATTIVO: in volo veloce (elytra/caduta, >1 blocco/tick) si attraversa un pixel a ogni
        // tick e la memoizzazione non aiuta -> il pieno ridisegno per-tick faceva calare i TPS durante i
        // viaggi in elytra. Refresh 4x piu' raro finche' si vola; da fermi/camminando torna pieno.
        if (player.getVelocity().lengthSquared() > 1.0) intervalMs = intervalMs * 4 + 150;
        long now = System.currentTimeMillis();
        if (now - lastRender < intervalMs) return;
        lastRender = now;

        // Centro QUANTIZZATO alla griglia dei pixel (multipli di bpp per bpp>=1): sotto il pixel la mappa
        // non puo' comunque scorrere, la resa e' identica ma il frame resta riusabile finche' non si
        // attraversa un confine di pixel.
        int step = blocksPerPixel >= 1 ? (int) Math.round(blocksPerPixel) : 1;
        int cX = Math.floorDiv(player.getLocation().getBlockX(), step) * step;
        int cZ = Math.floorDiv(player.getLocation().getBlockZ(), step) * step;
        view.setCenterX(cX); view.setCenterZ(cZ);

        try {
            boolean dither = plugin.getConfig().getBoolean("map.dither", false);
            boolean memoValid = !dither && cX == lastCX && cZ == lastCZ && blocksPerPixel == lastBpp
                    && player.getWorld().getName().equals(lastWorld)
                    && lastTVer == terrain.version() && lastCVer == claims.version()
                    && now - lastPaintMs < (lastComplete ? 2000 : 500);
            if (!memoValid) {
                int ss = plugin.getConfig().getInt("map.item.supersampling", 2);
                // PRIMO paint dopo il (ri)aggancio (login/riavvio): cache fredde -> il frame pieno con
                // supersampling (9x campioni) concentrava decine di ms nel tick del join. Il primo frame
                // esce a ss=1 (veloce, subito visibile) e viene marcato incompleto: il refresh entro 0.5s
                // lo rimpiazza con quello pieno, a cache ormai calde. Costo visivo: mezzo secondo di
                // terreno leggermente meno liscio al login.
                boolean first = !firstPaintDone;
                if (first) ss = 1;
                // Mappa-item: NON cuociamo i marcatori-giocatore nei pixel (bakePlayerMarkers=false) — li
                // disegniamo come CURSORI NATIVI (frecce nitide a risoluzione schermo), fuori dal memo.
                boolean[] complete = {true};
                MapContentBuilder.paint(canvas, player, fm, claims, terrain, blocksPerPixel, plugin, cX, cZ, ss, avatars, false, complete);
                if (first) { complete[0] = false; firstPaintDone = true; }
                // FIRMA magica per lo shader del resource pack (pixel 0-2 riga 0, inverso della firma
                // minimap): identifica questa come Mappa Fazioni -> resa FULLBRIGHT (leggibile di notte)
                // e upscaling smart, come la minimap. La riga 0 viene nascosta dallo shader stesso.
                canvas.setPixel(0, 0, (byte) 49);
                canvas.setPixel(1, 0, (byte) 4);
                canvas.setPixel(2, 0, (byte) 18);
                lastCX = cX; lastCZ = cZ; lastBpp = blocksPerPixel;
                lastWorld = player.getWorld().getName();
                lastTVer = terrain.version(); lastCVer = claims.version();
                lastPaintMs = now; lastComplete = complete[0];
            }
            MapContentBuilder.applyPlayerCursors(canvas, player, fm, plugin, blocksPerPixel, cX, cZ);
        } catch (Exception e) {
            plugin.getLogger().warning("[MapDebug] Eccezione durante il paint del terreno/territori: " + e);
            for (StackTraceElement el : e.getStackTrace()) plugin.getLogger().warning("    at " + el);
        }
    }
}
