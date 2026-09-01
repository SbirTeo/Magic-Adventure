package com.teolo.magixfactions.map;

import com.teolo.magixfactions.manage.ClaimManager;
import com.teolo.magixfactions.manage.FactionManager;
import com.teolo.magixfactions.model.Faction;
import com.teolo.magixfactions.model.RelationType;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.Color;

/**
 * Logica di disegno pixel condivisa tra l'item "Mappa Fazioni" ({@link FactionMapRenderer}) e la
 * minimap HUD ({@link MinimapRenderer}): stesso terreno ({@link TerrainCache}), stessi territori/
 * relazioni, stessa ombreggiatura — cosi' le due modalita' di consegna restano IDENTICHE nel
 * contenuto, senza rischio di divergenza futura tra due implementazioni copiate a mano.
 *
 * <p>Estratta da {@code FactionMapRenderer} (v0.11.x-0.12.x): il chiamante e' responsabile del
 * proprio throttle/ricentraggio (intervallo tick, owner-check per mappa personale, ecc.) — qui c'e'
 * solo la produzione dei 128x128 pixel dato un centro e una scala.
 */
final class MapContentBuilder {

    private MapContentBuilder() {}

    /** Disegna sul canvas i 128x128 pixel (terreno + territori + cardinali + home + eventuali nomi cotti).
     *  <p>{@code bakePlayerMarkers}: se true, cuoce nei pixel solo i NOMI dei giocatori — usato dalla
     *  MINIMAP HUD, che spinge byte-pixel grezzi via pacchetto e non ha cursori nativi (vedi
     *  {@link MapService#renderPalette}); le FRECCE-marcatore le disegna lo shader dai dati dell'header
     *  (vedi {@link MapService#renderPaletteWithHeader}). Se false, nulla di giocatore viene cotto: nomi e
     *  frecce sono CURSORI NATIVI ({@link #applyPlayerCursors}), nitidi e rotanti a risoluzione schermo —
     *  usato dalla mappa-ITEM in mano ({@link FactionMapRenderer}). Terreno/territori/cardinali/home
     *  restano IDENTICI su entrambe. */
    static void paint(MapCanvas canvas, Player player, FactionManager fm, ClaimManager claims,
                       TerrainCache terrain, double blocksPerPixel, JavaPlugin plugin, int centerX, int centerZ,
                       int supersampling, AvatarCache avatars, boolean bakePlayerMarkers) {
        paint(canvas, player, fm, claims, terrain, blocksPerPixel, plugin, centerX, centerZ, supersampling, avatars, bakePlayerMarkers, null);
    }

    /** Come {@link #paint(MapCanvas, Player, FactionManager, ClaimManager, TerrainCache, double, JavaPlugin,
     *  int, int, int, AvatarCache, boolean)} ma riporta in {@code completeOut[0]} se il frame era COMPLETO
     *  (nessun pixel senza dato terreno): un frame incompleto non va memoizzato a lungo (vedi
     *  {@link FactionMapRenderer}), va ritentato presto per riempire i buchi appena arrivano i dati. */
    static void paint(MapCanvas canvas, Player player, FactionManager fm, ClaimManager claims,
                       TerrainCache terrain, double blocksPerPixel, JavaPlugin plugin, int centerX, int centerZ,
                       int supersampling, AvatarCache avatars, boolean bakePlayerMarkers, boolean[] completeOut) {
        boolean dither = plugin.getConfig().getBoolean("map.dither", false) && MapColorUtil.isReady();
        boolean[][] protect = dither ? new boolean[128][128] : null;
        Color[][] pixels = computeColors(player, fm, claims, terrain, blocksPerPixel, plugin, centerX, centerZ, supersampling, avatars, protect, bakePlayerMarkers, completeOut);
        if (dither) {
            // Dithering: colori piu' ricchi SOLO sul terreno (marcatori/territori restano netti). setPixel
            // scrive direttamente il byte-palette gia' scelto.
            byte[] d = MapColorUtil.dither(pixels, protect);
            for (int x = 0; x < 128; x++)
                for (int y = 0; y < 128; y++)
                    canvas.setPixel(x, y, d[y * 128 + x]);
        } else {
            for (int x = 0; x < 128; x++)
                for (int y = 0; y < 128; y++)
                    canvas.setPixelColor(x, y, pixels[x][y]);
        }
    }

    /**
     * Calcola i 128x128 pixel (terreno + territori) centrati su (centerX, centerZ) a {@code
     * blocksPerPixel} blocchi per pixel, SENZA scrivere su un {@link MapCanvas} — usato dalla minimap
     * HUD, che non ha un canvas vero (l'item e' fittizio, i pixel vengono spinti a mano via pacchetto,
     * vedi {@code minimap.MinimapManager}). {@link #paint} e' un thin wrapper che scrive questo stesso
     * risultato su un canvas reale. {@code blocksPerPixel} e' un {@code double} (non un {@link
     * org.bukkit.map.MapView.Scale}, che supporta solo potenze di 2 da 1 in su) apposta per permettere
     * zoom PIU' vicini del vanilla "closest" (es. 0.5 = "closer", il doppio del dettaglio).
     */
    static Color[][] computeColors(Player player, FactionManager fm, ClaimManager claims,
                                    TerrainCache terrain, double blocksPerPixel, JavaPlugin plugin,
                                    int centerX, int centerZ, int supersampling, AvatarCache avatars,
                                    boolean[][] protectOut, boolean bakePlayerMarkers, boolean[] completeOut) {
        World w = player.getWorld();
        String world = w.getName();
        Faction own = fm.getFaction(player.getUniqueId());
        // Colore dei pixel SENZA dato terreno (in attesa di reveal): coerente col vuoto del mondo — beige
        // "carta" nell'overworld, viola-scuro nell'End (impostato da terrain.prepareReveal, sotto).
        int alpha = Math.max(0, Math.min(100, plugin.getConfig().getInt("map.opacity", 40)));       // condivisa item+minimap
        int borderAlpha = Math.max(0, Math.min(100, plugin.getConfig().getInt("map.opacity-borders", 60))); // condivisa item+minimap

        // Supersampling: quando un pixel copre PIU' blocchi (blocksPerPixel > 1), campionare un solo
        // blocco per pixel "salta" gli altri e da' un terreno sgranato/rumoroso. Con ss>1 campioniamo una
        // griglia ss×ss dentro l'impronta del pixel e ne mediamo colore/altezza -> resa piu' pulita e
        // fedele (anti-aliasing). Deciso dal chiamante (attivo per la mappa cartacea, NON per la minimap
        // che si ridisegna ogni tick: costerebbe troppo). Sotto 1 blocco/pixel non serve.
        int ss = Math.max(1, Math.min(4, supersampling));
        if (blocksPerPixel < 2) ss = 1;

        // Raggio max di rivelazione async (limite di sicurezza dello sweep iniziale). Default 4096 blocchi
        // (a 64 blocchi/pixel copre l'intera mappa). Il carico resta frenato da rate-limit + tetto in volo +
        // memoria dei chunk non generati (che non vengono ritentati). Vedi TerrainCache#markRevealCandidate.
        // Mondo + quota di chi guarda: le cache del terreno sono separate per mondo (senza, dopo un
        // cambio mondo la mappa restava quella di prima) e nei mondi col tetto (Nether) la quota decide
        // da dove guardare la colonna, altrimenti si vedrebbe solo la bedrock del soffitto.
        terrain.prepareReveal(w, player.getLocation().getBlockY(),
                plugin.getConfig().getInt("map.reveal-max-distance", 4096));
        Color unknown = terrain.emptyColor(); // deciso dal mondo (beige overworld / scuro End), post-prepareReveal

        // Pass 1: proprietario + terreno (altezza + colore) per ogni pixel.
        Long[][] owner = new Long[128][128];
        int[][] height = new int[128][128];
        Color[][] raw = new Color[128][128];
        boolean[][] water = new boolean[128][128]; // acqua: colore gia' a bande, NIENTE ombreggiatura rilievo
        Color[] cs = new Color[16];                // buffer campioni supersampling (ss max 4)
        boolean[] wt = new boolean[16];
        for (int x = 0; x < 128; x++) {
            int bx = centerX + (int) Math.round((x - 64) * blocksPerPixel);
            int chunkX = bx >> 4;
            for (int y = 0; y < 128; y++) {
                int bz = centerZ + (int) Math.round((y - 64) * blocksPerPixel);
                owner[x][y] = claims.owner(world, chunkX, bz >> 4);
                if (ss == 1) {
                    TerrainCache.Sample s = terrain.sampleAt(w, bx, bz, centerX, centerZ);
                    if (s != null) { height[x][y] = s.height(); raw[x][y] = s.color(); water[x][y] = s.water(); }
                    else { height[x][y] = Integer.MIN_VALUE; raw[x][y] = null; }
                } else {
                    // Voto di MAGGIORANZA sul colore (come fa vanilla per le celle multi-blocco), NON media:
                    // la media inventava colori intermedi fuori palette che sporcavano la mappa di pixel
                    // tutti leggermente diversi (effetto "sgranato"); il piu' frequente da' aree piatte e
                    // pulite come la mappa vanilla. L'altezza invece resta mediata (serve solo al rilievo).
                    long hSum = 0; int cnt = 0;
                    for (int i = 0; i < ss; i++) {
                        int sbx = centerX + (int) Math.round((x - 64 + (i + 0.5) / ss - 0.5) * blocksPerPixel);
                        for (int j = 0; j < ss; j++) {
                            int sbz = centerZ + (int) Math.round((y - 64 + (j + 0.5) / ss - 0.5) * blocksPerPixel);
                            TerrainCache.Sample s = terrain.sampleAt(w, sbx, sbz, centerX, centerZ);
                            if (s == null) continue;
                            cs[cnt] = s.color(); wt[cnt] = s.water();
                            hSum += s.height(); cnt++;
                        }
                    }
                    if (cnt > 0) {
                        int best = 0, bestN = 0;
                        for (int a = 0; a < cnt; a++) {
                            int rgbA = cs[a].getRGB(), n = 0;
                            for (int b = 0; b < cnt; b++) if (cs[b].getRGB() == rgbA) n++;
                            if (n > bestN) { bestN = n; best = a; }
                        }
                        raw[x][y] = cs[best];
                        water[x][y] = wt[best];
                        height[x][y] = (int) (hSum / cnt);
                    } else { height[x][y] = Integer.MIN_VALUE; raw[x][y] = null; }
                }
            }
        }
        // Frame completo = ogni pixel ha un dato terreno vero (nessun placeholder in attesa di reveal):
        // serve alla memoizzazione (un frame coi buchi va ritentato presto, non tenuto 2 secondi).
        if (completeOut != null) {
            boolean complete = true;
            outer:
            for (int x = 0; x < 128; x++)
                for (int y = 0; y < 128; y++)
                    if (raw[x][y] == null) { complete = false; break outer; }
            completeOut[0] = complete;
        }
        // Rivelazione radiale: ora che abbiamo raccolto i chunk non caricati visibili, ne richiediamo il
        // caricamento async dal piu' vicino al centro al piu' lontano (vedi TerrainCache#flushReveals).
        terrain.flushReveals(w);
        // Pass 2: colori finali. Bordo e interno sono entrambi fusi col terreno, con opacita' indipendenti
        // (bordo di solito piu' marcato per restare una cornice netta che separa territori adiacenti).
        // Colori e opacita' configurabili in map.colors.<own|ally|enemy>.{border,fill} e map.opacity{,-borders} (condivise).
        Color[][] out = new Color[128][128];
        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 128; y++) {
                // Ombreggiatura per rilievo confrontando col pixel a nord (gia' campionato sopra, nessun
                // secondo accesso ai chunk). L'ACQUA e' esclusa: e' piatta, il suo colore ha gia' le bande
                // di profondita' stile vanilla (vedi TerrainCache). null = snapshot non disponibile:
                // copertura opaca placeholder (MAI trasparente/null: il renderer vanilla, se agganciato,
                // non deve mai "bucare" e mostrare il suo terreno sotto il nostro — e' quello che causava
                // le due mappe sovrapposte). Copre anche la "scia" quando si ricentra.
                Color terr = water[x][y] ? raw[x][y]
                        : shaded(raw[x][y], height[x][y], y > 0 ? height[x][y - 1] : Integer.MIN_VALUE);
                Long o = owner[x][y];
                if (o == null) {
                    out[x][y] = terr != null ? terr : unknown;
                    continue;
                }
                String rel = relKey(fm, own, o);
                Color base = terr != null ? terr : unknown;
                out[x][y] = isBorder(owner, x, y, o)
                        ? blend(base, relColor(plugin, rel, "border"), borderAlpha)
                        : blend(base, relColor(plugin, rel, "fill"), alpha);
                if (protectOut != null) protectOut[x][y] = true; // overlay territorio: niente dithering (resta netto)
            }
        }
        // Snapshot del frame PRIMA dei marcatori: cosi' i pixel che marcatori/cardinali cambieranno vengono
        // marcati "protetti" (esclusi dal dithering) confrontando dopo — senza dover passare la maschera a
        // ogni metodo di disegno.
        Color[][] preMarkers = null;
        if (protectOut != null) {
            preMarkers = new Color[128][];
            for (int x = 0; x < 128; x++) preMarkers[x] = out[x].clone();
        }
        drawCompass(out);                                              // punti cardinali: su ENTRAMBE le mappe (item + minimap)
        drawHome(out, fm, own, world, blocksPerPixel, plugin, centerX, centerZ);
        // NOMI dei giocatori COTTI: solo quando richiesto (minimap HUD). Il MARCATORE vero e proprio e'
        // una FRECCIA disegnata dallo shader (vedi MapService.computePlayerArrows / renderPaletteWithHeader),
        // qui cuociamo solo il nome sotto la freccia. Sulla mappa-item nome e freccia sono entrambi nativi
        // (bakePlayerMarkers=false), vedi {@link #applyPlayerCursors}.
        if (bakePlayerMarkers) drawPlayerNames(out, player, fm, plugin, blocksPerPixel, centerX, centerZ);
        // Marca "protetti" tutti i pixel toccati dai marcatori (cardinali/home/nome): confronto
        // con lo snapshot -> restano netti (niente dithering che li renderebbe rumorosi/irriconoscibili).
        if (protectOut != null) {
            for (int x = 0; x < 128; x++)
                for (int y = 0; y < 128; y++)
                    if (out[x][y] != preMarkers[x][y]) protectOut[x][y] = true;
        }
        return out;
    }

    // Simbolo "casa" 5x5 (1 = pixel acceso), disegnato al punto ESATTO della home della propria fazione.
    private static final boolean[][] HOME_GLYPH = {
            {false, false, true,  false, false},
            {false, true,  true,  true,  false},
            {true,  true,  true,  true,  true},
            {true,  false, true,  false, true},
            {true,  true,  true,  true,  true},
    };

    /** Se il giocatore ha una fazione con una home impostata in QUESTO mondo, disegna {@link #HOME_GLYPH}
     *  al punto ESATTO della home (blocco di {@code /f sethome}). Se la home e' FUORI dall'area visibile,
     *  il simbolo NON scompare: viene "attaccato" al bordo della mappa nella DIREZIONE della home (come un
     *  indicatore). Config {@code map.home-marker.enabled}/{@code .color}. Solo la PROPRIA home. */
    private static void drawHome(Color[][] out, FactionManager fm, Faction own, String world,
                                  double blocksPerPixel, JavaPlugin plugin, int centerX, int centerZ) {
        if (own == null || !plugin.getConfig().getBoolean("map.home-marker.enabled", true)) return;
        FactionManager.Home home = fm.getHome(own.getId());
        if (home == null || !home.world.equals(world)) return;

        double pxF = worldToPixel((int) Math.floor(home.x), centerX, blocksPerPixel);
        double pyF = worldToPixel((int) Math.floor(home.z), centerZ, blocksPerPixel);
        // Se la home cade oltre il bordo, la "clampiamo" sul bordo lungo la retta centro->home (cosi' il
        // simbolo resta sempre visibile e indica la direzione della home). t=1 = posizione reale; se la
        // home e' oltre il riquadro [lo,hi] il raggio esce prima (t<1) e disegniamo li' (sul bordo).
        double lo = 3, hi = 124; // tiene il glifo 5x5 interamente dentro la mappa
        double dirX = pxF - 64, dirY = pyF - 64;
        double t = 1.0;
        if (dirX != 0 || dirY != 0) {
            double tEdge = Double.POSITIVE_INFINITY;
            if (dirX > 0) tEdge = Math.min(tEdge, (hi - 64) / dirX);
            else if (dirX < 0) tEdge = Math.min(tEdge, (lo - 64) / dirX);
            if (dirY > 0) tEdge = Math.min(tEdge, (hi - 64) / dirY);
            else if (dirY < 0) tEdge = Math.min(tEdge, (lo - 64) / dirY);
            t = Math.min(1.0, tEdge);
        }
        int px = 64 + (int) Math.round(t * dirX);
        int py = 64 + (int) Math.round(t * dirY);

        Color fill = codeToColor(plugin.getConfig().getString("map.home-marker.color", "&6"));
        Color edge = new Color(0, 0, 0);
        int left = px - 2, top = py - 2;
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 5; col++) {
                if (!HOME_GLYPH[row][col]) continue;
                for (int dx = -1; dx <= 1; dx++)
                    for (int dy = -1; dy <= 1; dy++)
                        setColor(out, left + col + dx, top + row + dy, edge);
            }
        }
        for (int row = 0; row < 5; row++)
            for (int col = 0; col < 5; col++)
                if (HOME_GLYPH[row][col]) setColor(out, left + col, top + row, fill);
    }

    private static void setColor(Color[][] out, int x, int y, Color c) {
        if (x < 0 || x > 127 || y < 0 || y > 127) return;
        out[x][y] = c;
    }

    // ---- Punti cardinali N/S/E/O (nord sempre in alto: la mappa non ruota) — su ENTRAMBE le mappe -------
    private static final Color COMPASS_FILL = Color.WHITE; // lettera
    private static final Color COMPASS_EDGE = Color.BLACK;  // contorno (leggibile su qualunque terreno)
    // Font 3x5 minimale (1 = pixel acceso), righe dall'alto in basso.
    private static final boolean[][] GLYPH_N = compassBits("101", "111", "111", "111", "101");
    private static final boolean[][] GLYPH_S = compassBits("111", "100", "111", "001", "111");
    private static final boolean[][] GLYPH_E = compassBits("111", "100", "111", "100", "111");
    private static final boolean[][] GLYPH_W = compassBits("101", "101", "101", "111", "101");

    private static boolean[][] compassBits(String... rows) {
        boolean[][] g = new boolean[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            g[i] = new boolean[rows[i].length()];
            for (int c = 0; c < rows[i].length(); c++) g[i][c] = rows[i].charAt(c) == '1';
        }
        return g;
    }

    /** Disegna N/S/E/O a ~50px dal centro (dentro il cerchio r=58 ritagliato dallo shader della minimap). */
    private static void drawCompass(Color[][] out) {
        drawGlyph(out, GLYPH_N, 63, 13);    // alto
        drawGlyph(out, GLYPH_S, 63, 110);   // basso
        drawGlyph(out, GLYPH_W, 13, 62);    // sinistra
        drawGlyph(out, GLYPH_E, 112, 62);   // destra
    }

    private static void drawGlyph(Color[][] out, boolean[][] glyph, int left, int top) {
        for (int row = 0; row < glyph.length; row++)
            for (int col = 0; col < glyph[row].length; col++)
                if (glyph[row][col])
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dy = -1; dy <= 1; dy++)
                            setColor(out, left + col + dx, top + row + dy, COMPASS_EDGE);
        for (int row = 0; row < glyph.length; row++)
            for (int col = 0; col < glyph[row].length; col++)
                if (glyph[row][col]) setColor(out, left + col, top + row, COMPASS_FILL);
    }

    // ---- Frecce giocatore (marcatore) + nomi — condivisi da mappa cartacea E minimap -----------------
    // Il MARCATORE di ogni giocatore e' una FRECCIA che punta nella direzione di sguardo, colorata per
    // relazione (bianco = tu, verde = fazione, blu = alleato, rosso = nemico), come le minimap moderne
    // (stile Cartographer). Sulla mappa-ITEM sono cursori NATIVI ({@link #applyPlayerCursors}); sulla
    // MINIMAP HUD, dove i cursori nativi non si agganciano, sono ridisegnate dallo shader a risoluzione
    // schermo: i dati (posizione/angolo/tipo) vengono calcolati da {@link #computePlayerArrows} e
    // impacchettati nell'header (vedi {@code MapService.renderPaletteWithHeader}). Qui, per la minimap,
    // cuociamo nei pixel solo il NOME sotto la freccia (la freccia la mette lo shader).

    /**
     * Calcola fino a {@code maxArrows} record-freccia per la minimap (uno per giocatore online nello
     * stesso mondo che ricade nell'area visibile; il PROPRIO e' sempre incluso e messo nell'ULTIMO slot,
     * cosi' lo shader lo disegna sopra gli altri). Ogni record e' un int a 22 bit (LSB-first): bit0 =
     * valido, 1..7 = px, 8..14 = py, 15..19 = bucket-yaw (0..{@code angleSteps}-1), 20..21 = tipo
     * (0 = tu, 1 = stessa fazione, 2 = alleato, 3 = nemico). Slot vuoto = -1. Se ci sono piu' giocatori
     * degli slot disponibili, si tengono i piu' VICINI. DEVE combaciare col decode in rendertype_text.vsh.
     */
    static int[] computePlayerArrows(Player viewer, FactionManager fm, double blocksPerPixel,
                                      int centerX, int centerZ, int maxArrows, int angleSteps) {
        int[] out = new int[maxArrows];
        java.util.Arrays.fill(out, -1);
        if (maxArrows <= 0) return out;
        Faction vf = fm.getFaction(viewer.getUniqueId());
        // Altri giocatori visibili -> {distanza^2 dal centro, record impacchettato}, ordinati per vicinanza.
        java.util.List<long[]> others = new java.util.ArrayList<>();
        for (Player o : viewer.getWorld().getPlayers()) {
            if (o.getUniqueId().equals(viewer.getUniqueId())) continue;
            if (hiddenFromMap(o)) continue; // vanish o pozione di invisibilita': nessuna freccia sulla minimap altrui
            int px = 64 + (int) Math.floor((o.getLocation().getBlockX() - centerX) / blocksPerPixel);
            int py = 64 + (int) Math.floor((o.getLocation().getBlockZ() - centerZ) / blocksPerPixel);
            if (px < 0 || px > 127 || py < 0 || py > 127) continue; // fuori dall'area visibile
            String rel = relKeyPlayer(fm, vf, fm.getFaction(o.getUniqueId()));
            int type = switch (rel) { case "own" -> 1; case "ally" -> 2; default -> 3; };
            int ang = yawBucket(o.getLocation().getYaw(), angleSteps);
            long dist2 = (long) (px - 64) * (px - 64) + (long) (py - 64) * (py - 64);
            others.add(new long[]{dist2, packArrow(px, py, ang, type)});
        }
        others.sort(java.util.Comparator.comparingLong(a -> a[0]));
        int slots = maxArrows - 1; // ultimo slot riservato al proprio marcatore
        int n = Math.min(slots, others.size());
        for (int i = 0; i < n; i++) out[i] = (int) others.get(i)[1];
        // Proprio marcatore: sempre al centro (64,64), ultimo slot -> disegnato per ultimo = sopra gli altri.
        out[maxArrows - 1] = packArrow(64, 64, yawBucket(viewer.getLocation().getYaw(), angleSteps), 0);
        return out;
    }

    /** Impacchetta un record-freccia nei 22 bit letti dallo shader (vedi {@link #computePlayerArrows}). */
    private static int packArrow(int px, int py, int angBucket, int type) {
        return 1 | ((px & 0x7F) << 1) | ((py & 0x7F) << 8) | ((angBucket & 0x1F) << 15) | ((type & 0x3) << 20);
    }

    /** Yaw (gradi) -> bucket 0..steps-1. Deve combaciare con la ricostruzione dell'angolo nello shader
     *  (angle = bucket/steps * 2pi) e con la convenzione di sguardo (-sin, cos). */
    private static int yawBucket(float yaw, int steps) {
        double y = ((yaw % 360.0) + 360.0) % 360.0;
        return (int) Math.round(y / (360.0 / steps)) % steps;
    }

    /**
     * Cuoce nei pixel della minimap il NOME dei giocatori (sotto la freccia disegnata dallo shader),
     * colorato per relazione, rispettando {@code map.marker.personal-nametag}/{@code other-nametag}. Non
     * disegna alcun marcatore: quello e' la freccia (shader). Sulla mappa-item i nomi sono invece caption
     * nativi (vedi {@link #applyPlayerCursors}).
     */
    static void drawPlayerNames(Color[][] out, Player viewer, FactionManager fm, JavaPlugin plugin,
                                 double blocksPerPixel, int centerX, int centerZ) {
        boolean personalName = plugin.getConfig().getBoolean("map.marker.personal-nametag", false);
        boolean otherName    = plugin.getConfig().getBoolean("map.marker.other-nametag", true);
        boolean enemyName    = plugin.getConfig().getBoolean("map.marker.enemy-nametag", false);
        if (!personalName && !otherName) return;
        Faction viewerFaction = fm.getFaction(viewer.getUniqueId());
        int nameSize = Math.max(4, Math.min(20, plugin.getConfig().getInt("map.marker.name-size", 7)));
        for (Player other : viewer.getWorld().getPlayers()) {
            boolean self = other.getUniqueId().equals(viewer.getUniqueId());
            if (!self && hiddenFromMap(other)) continue; // vanish/invisibilita': nessun nome sulla minimap altrui
            boolean showName = self ? personalName : otherName;
            if (!showName) continue;
            int px = self ? 64 : 64 + (int) Math.floor((other.getLocation().getBlockX() - centerX) / blocksPerPixel);
            int py = self ? 64 : 64 + (int) Math.floor((other.getLocation().getBlockZ() - centerZ) / blocksPerPixel);
            if (px < 5 || px > 122 || py < 5 || py > 118) continue; // fuori mappa / troppo al bordo per il nome
            String rel = self ? "own" : relKeyPlayer(fm, viewerFaction, fm.getFaction(other.getUniqueId()));
            if (!self && "enemy".equals(rel) && !enemyName) continue; // nemici: solo la freccia, nessun nickname
            drawName(out, other.getName(), px, py + 8, nameSize, playerNameColor(other)); // sotto la freccia, colore del GRADO
        }
    }

    // ---- Cursori NATIVI (solo mappa-item) ------------------------------------------------------------
    // A differenza dei marcatori "cotti" (drawPlayerMarkers), i cursori-mappa nativi sono disegnati dal
    // CLIENT come sprite a risoluzione schermo e ruotano in 16 direzioni: la freccia resta NITIDA a
    // qualsiasi angolo (30/45°...), non si sgrana come un marcatore cotto nei 128x128 pixel. Funzionano
    // pero' SOLO su una MapView reso come item in mano — NON sulla minimap HUD, che spinge byte-pixel
    // grezzi via pacchetto+shader dove i cursori non si riposizionano. Per questo la minimap resta sui
    // marcatori cotti e solo la mappa-item usa questi cursori (scelta concordata con l'utente).

    /**
     * Popola i cursori nativi della {@link MapCanvas} con una freccia per OGNI giocatore online nello
     * stesso mondo che ricade nell'area visibile: se stessi = {@link MapCursor.Type#PLAYER} (freccia
     * bianca), compagni di fazione = {@link MapCursor.Type#FRAME} (verde), alleati = {@link
     * MapCursor.Type#BLUE_MARKER} (blu), nemici = {@link MapCursor.Type#RED_MARKER} (rosso). La freccia
     * punta nella direzione di sguardo (yaw -> 16 direzioni). Il nome e' un caption nativo (nitido),
     * mostrabile/nascondibile con le stesse chiavi config dei marcatori cotti ({@code
     * map.marker.personal-nametag}/{@code other-nametag}). Sostituisce ogni cursore precedente (nessun
     * accumulo tra un render e l'altro).
     */
    static void applyPlayerCursors(MapCanvas canvas, Player viewer, FactionManager fm, JavaPlugin plugin,
                                    double blocksPerPixel, int centerX, int centerZ) {
        MapCursorCollection cursors = new MapCursorCollection();
        Faction viewerFaction = fm.getFaction(viewer.getUniqueId());
        boolean personalName = plugin.getConfig().getBoolean("map.marker.personal-nametag", false);
        boolean otherName    = plugin.getConfig().getBoolean("map.marker.other-nametag", true);
        boolean enemyName    = plugin.getConfig().getBoolean("map.marker.enemy-nametag", false);
        for (Player other : viewer.getWorld().getPlayers()) {
            boolean self = other.getUniqueId().equals(viewer.getUniqueId());
            if (!self && hiddenFromMap(other)) continue; // vanish/invisibilita': nessun cursore sulla mappa-item altrui
            // Posizione in pixel-mappa (stessa formula dei marcatori cotti); self e' sempre il centro.
            int px = self ? 64 : 64 + (int) Math.floor((other.getLocation().getBlockX() - centerX) / blocksPerPixel);
            int py = self ? 64 : 64 + (int) Math.floor((other.getLocation().getBlockZ() - centerZ) / blocksPerPixel);
            if (px < 0 || px > 127 || py < 0 || py > 127) continue; // fuori dall'area visibile
            // Coordinate cursore: la mappa 0..127 px mappa su -128..127 (2 unita' per pixel), centro = 0.
            byte cx = (byte) Math.max(-128, Math.min(127, px * 2 - 128));
            byte cy = (byte) Math.max(-128, Math.min(127, py * 2 - 128));
            byte dir = yawToDirection(other.getLocation().getYaw());
            String rel = self ? "own" : relKeyPlayer(fm, viewerFaction, fm.getFaction(other.getUniqueId()));
            MapCursor.Type type = self ? MapCursor.Type.PLAYER : switch (rel) {
                case "own"  -> MapCursor.Type.FRAME;        // verde: stessa fazione
                case "ally" -> MapCursor.Type.BLUE_MARKER;  // blu: alleato
                default     -> MapCursor.Type.RED_MARKER;   // rosso: nemico
            };
            boolean showName = self ? personalName : otherName;
            if (!self && "enemy".equals(rel) && !enemyName) showName = false; // nemici: solo il cursore, nessun nickname
            String caption = showName ? other.getName() : null;
            cursors.addCursor(new MapCursor(cx, cy, dir, type, true, caption));
        }
        canvas.setCursors(cursors);
    }

    /** true se il giocatore va NASCOSTO dai marcatori delle mappe altrui: e' in vanish, oppure ha
     *  l'effetto pozione di INVISIBILITA'. Il proprio marcatore non passa mai di qui (il viewer si vede
     *  sempre al centro). */
    private static boolean hiddenFromMap(Player p) {
        return isVanished(p) || p.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
    }

    /** true se il giocatore e' in VANISH. Usa la convenzione de-facto del metadata booleano {@code
     *  "vanished"} impostato da CMI, EssentialsX e SuperVanish: cosi' i loro plugin nascondono il
     *  marcatore/nome dalla mappa altrui senza che MagixFactions debba dipendere da nessuno di essi. */
    private static boolean isVanished(Player p) {
        for (org.bukkit.metadata.MetadataValue v : p.getMetadata("vanished")) {
            if (v.asBoolean()) return true;
        }
        return false;
    }

    /** Yaw del giocatore -> direzione cursore mappa (0..15, passi di 22.5°, mappa sempre nord-in-alto).
     *  Convenzione vanilla: dir 0 = punta a sud (in basso), crescente in senso orario. */
    private static byte yawToDirection(float yaw) {
        float y = ((yaw % 360f) + 360f) % 360f;
        return (byte) (Math.round(y / 22.5f) & 15);
    }

    /** Colore del nome giocatore sulle mappe = colore del suo GRADO (placeholder {@code %magixweb_namecolor%},
     *  lo STESSO usato in chat): grado default -> grigio &7, gradi colorati (es. helper verde, admin rosso)
     *  lo sovrascrivono. La RELAZIONE resta indicata dal colore della freccia/cursore, non dal nome (coerente
     *  con la chat, dove solo il tag fazione e' colorato per relazione). La targhetta scura dietro garantisce
     *  il contrasto su qualsiasi terreno.
     *  <p>Risolve PAPI/LuckPerms SOLO sul main thread (la minimap gira su runTaskTimer sincrono); fuori dal
     *  main thread, o se il placeholder non e' disponibile/vuoto, ripiega sul grigio. Formati accettati:
     *  esadecimale {@code &#RRGGBB} (quello che ritorna %magixweb_namecolor%) o legacy {@code &X}. */
    private static Color playerNameColor(Player p) {
        Color def = new Color(170, 170, 170); // &7 grigio (grado default o placeholder non disponibile)
        if (!org.bukkit.Bukkit.isPrimaryThread()) return def;
        String raw = com.teolo.magixfactions.hook.Papi.resolve(p, "%magixweb_namecolor%");
        if (raw == null) return def;
        raw = raw.trim();
        if (raw.isEmpty() || raw.indexOf('%') >= 0) return def; // vuoto o placeholder non risolto
        int h = raw.indexOf('#');
        if (h >= 0 && raw.length() >= h + 7) {
            try { return new Color(Integer.parseInt(raw.substring(h + 1, h + 7), 16)); }
            catch (NumberFormatException ignored) { return def; }
        }
        for (int i = raw.length() - 2; i >= 0; i--) {
            if (raw.charAt(i) == '&' || raw.charAt(i) == '§') return codeToColor(raw.substring(i));
        }
        return def;
    }

    // Cache delle MASCHERE-nome (name|size -> codici pixel; 0=niente, 1=targhetta, 2=testo). Indipendente
    // dal colore (applicato in drawName), cosi' un nome vale per tutte le relazioni. Solo main thread.
    private static final java.util.HashMap<String, int[][]> nameCache = new java.util.HashMap<>();
    private static final Color NAME_PLATE = Color.BLACK; // tinta verso cui scurire la targhetta
    private static final int NAME_PLATE_ALPHA = 70;      // % di scurimento della targhetta (contrasto garantito su ogni terreno)

    /** Maschera del nome (0 niente / 1 targhetta / 2 testo) a dimensione {@code size}, font NETTO (no AA:
     *  su testo piccolo l'anti-aliasing lo rendeva illeggibile). Cache per name+size. */
    private static int[][] renderName(String name, int size) {
        String key = name + "|" + size;
        int[][] cached = nameCache.get(key);
        if (cached != null) return cached;
        if (nameCache.size() > 500) nameCache.clear();
        int[][] result;
        try {
            java.awt.Font font = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, size);
            java.awt.image.BufferedImage measure = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D gm = measure.createGraphics();
            gm.setFont(font);
            java.awt.FontMetrics fmet = gm.getFontMetrics();
            int tw = Math.max(1, fmet.stringWidth(name));
            int asc = fmet.getAscent(), desc = fmet.getDescent();
            gm.dispose();
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(tw + 2, asc + desc + 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setFont(font);
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setColor(java.awt.Color.WHITE);
            g.drawString(name, 1, asc + 1);
            g.dispose();
            int W0 = img.getWidth(), H0 = img.getHeight();
            int minX = W0, maxX = -1, minY = H0, maxY = -1;
            boolean[][] on = new boolean[W0][H0];
            for (int x = 0; x < W0; x++)
                for (int y = 0; y < H0; y++)
                    if ((img.getRGB(x, y) >>> 24) >= 128) {
                        on[x][y] = true;
                        if (x < minX) minX = x; if (x > maxX) maxX = x;
                        if (y < minY) minY = y; if (y > maxY) maxY = y;
                    }
            if (maxX < 0) { result = new int[0][0]; nameCache.put(key, result); return result; }
            int padX = 2, padY = 1; // margine targhetta attorno al testo
            int W = (maxX - minX + 1) + padX * 2, H = (maxY - minY + 1) + padY * 2;
            result = new int[W][H];
            for (int x = 0; x < W; x++)
                for (int y = 0; y < H; y++)
                    result[x][y] = 1; // targhetta su tutto il riquadro
            for (int x = minX; x <= maxX; x++)
                for (int y = minY; y <= maxY; y++)
                    if (on[x][y]) result[x - minX + padX][y - minY + padY] = 2; // testo
        } catch (Throwable t) {
            result = new int[0][0]; // font non disponibili (server minimale): niente nome, non blocca
        }
        nameCache.put(key, result);
        return result;
    }

    /** Disegna il nome: targhetta scura semitrasparente (contrasto su ogni terreno) + testo NETTO del
     *  {@code color} (vivo, per relazione), centrato su {@code cx}, cima a {@code topY}. */
    private static void drawName(Color[][] out, String name, int cx, int topY, int size, Color color) {
        int[][] g = renderName(name, size);
        if (g.length == 0) return;
        int W = g.length, H = g[0].length;
        int left = cx - W / 2;
        for (int x = 0; x < W; x++) {
            for (int y = 0; y < H; y++) {
                int code = g[x][y];
                if (code == 0) continue;
                int px = left + x, py = topY + y;
                if (px < 0 || px > 127 || py < 0 || py > 127) continue;
                if (code == 2) out[px][py] = color;                                    // testo vivo, netto
                else out[px][py] = blend(out[px][py], NAME_PLATE, NAME_PLATE_ALPHA);   // targhetta scura
            }
        }
    }

    /** Coordinata pixel (double) del blocco world {@code w} data la posizione centrale della mappa e i
     *  blocchi per pixel (inverso della formula di campionamento in {@link #computeColors}). */
    private static double worldToPixel(int w, int center, double blocksPerPixel) {
        return 64 + (w - center) / blocksPerPixel;
    }

    /** Applica l'ombreggiatura stile-mappa: piu' chiaro se il pixel e' piu' alto di quello a nord, piu' scuro se piu' basso. */
    private static Color shaded(Color base, int h, int hNorth) {
        if (base == null) return null;
        double mul = 220.0 / 255.0;
        if (hNorth != Integer.MIN_VALUE) {
            if (h > hNorth) mul = 1.0;
            else if (h < hNorth) mul = 180.0 / 255.0;
        }
        return new Color(shade(base.getRed(), mul), shade(base.getGreen(), mul), shade(base.getBlue(), mul));
    }

    private static int shade(int c, double mul) {
        int v = (int) Math.round(c * mul);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** Bordo del territorio a 8 VICINI (ortogonali + diagonali). Con soli 4 vicini ortogonali la cella
     *  d'angolo CONCAVO (tutti i lati dello stesso proprietario ma la diagonale diversa) non veniva
     *  marcata bordo -> la linea si "spezzava" con un pixel mancante all'angolo interno. Aggiungendo le
     *  diagonali quel pixel viene riempito e la cornice resta continua. Le celle non rivendicate
     *  ({@code owner==null}) non disegnano mai bordo (vedi il chiamante), quindi per un territorio isolato
     *  in mezzo alla natura questo riempie SOLO gli angoli concavi senza aggiungere sporgenze; un pixel in
     *  piu' puo' comparire solo dove due territori DIVERSI si toccano in diagonale (raro e coerente). */
    private static boolean isBorder(Long[][] owner, int x, int y, Long o) {
        return diff(owner, x - 1, y, o) || diff(owner, x + 1, y, o)
                || diff(owner, x, y - 1, o) || diff(owner, x, y + 1, o)
                || diff(owner, x - 1, y - 1, o) || diff(owner, x + 1, y - 1, o)
                || diff(owner, x - 1, y + 1, o) || diff(owner, x + 1, y + 1, o);
    }

    private static boolean diff(Long[][] owner, int x, int y, Long o) {
        if (x < 0 || x > 127 || y < 0 || y > 127) return true; // bordo mappa
        Long n = owner[x][y];
        return n == null || !n.equals(o);
    }

    /** Chiave di relazione (own/ally/enemy) di chi guarda verso la fazione proprietaria. Senza fazione
     *  propria si e' nemici di tutti (nessuno stato neutro, coerente col resto del plugin). */
    private static String relKey(FactionManager fm, Faction viewer, long targetId) {
        if (viewer == null) return "enemy";
        if (viewer.getId() == targetId) return "own";
        if (fm.effectiveRelation(viewer.getId(), targetId) == RelationType.ALLY) return "ally";
        return "enemy";
    }

    /** Come {@link #relKey}, ma per un ALTRO GIOCATORE (non un territorio): usato dai marcatori-avatar
     *  della minimap. Qui NEMMENO il bersaglio senza fazione e' un caso neutro a parte (a differenza di
     *  altri punti del plugin, es. la colorazione nome in chat) — un giocatore senza fazione propria e'
     *  trattato da nemico anche come bersaglio, coerente col fatto che il modello non prevede stati
     *  neutri: {@link #relKey} gia' tratta cosi' il VIEWER senza fazione, qui lo stesso vale per il target. */
    static String relKeyPlayer(FactionManager fm, Faction viewer, Faction target) {
        if (viewer == null || target == null) return "enemy";
        if (viewer.getId() == target.getId()) return "own";
        if (fm.effectiveRelation(viewer.getId(), target.getId()) == RelationType.ALLY) return "ally";
        return "enemy";
    }

    /** Colore 'border' o 'fill' per una relazione, da config map.colors.<rel>.<which> (con default). */
    static Color relColor(JavaPlugin plugin, String rel, String which) {
        return codeToColor(plugin.getConfig().getString(
                "map.colors." + rel + "." + which, defaultColor(rel, which)));
    }

    private static String defaultColor(String rel, String which) {
        boolean border = which.equals("border");
        return switch (rel) {
            case "own"   -> border ? "&2" : "&a";
            case "ally"  -> border ? "&5" : "&d";
            case "enemy" -> border ? "&4" : "&c";
            default      -> border ? "&6" : "&e";
        };
    }

    private static Color blend(Color base, Color over, int alphaPct) {
        if (base == null) return over;
        int a = alphaPct, ia = 100 - a;
        return new Color(
                (base.getRed()   * ia + over.getRed()   * a) / 100,
                (base.getGreen() * ia + over.getGreen() * a) / 100,
                (base.getBlue()  * ia + over.getBlue()  * a) / 100);
    }

    /** Converte l'ultimo codice colore '&X' della stringa nel corrispondente RGB di Minecraft. */
    private static Color codeToColor(String s) {
        char c = 'f';
        if (s != null) {
            for (int i = s.length() - 2; i >= 0; i--) {
                if (s.charAt(i) == '&' || s.charAt(i) == '§') { c = Character.toLowerCase(s.charAt(i + 1)); break; }
            }
        }
        return switch (c) {
            case '0' -> new Color(0, 0, 0);       case '1' -> new Color(0, 0, 170);
            case '2' -> new Color(0, 170, 0);     case '3' -> new Color(0, 170, 170);
            case '4' -> new Color(170, 0, 0);     case '5' -> new Color(170, 0, 170);
            case '6' -> new Color(255, 170, 0);   case '7' -> new Color(170, 170, 170);
            case '8' -> new Color(85, 85, 85);    case '9' -> new Color(85, 85, 255);
            case 'a' -> new Color(85, 255, 85);   case 'b' -> new Color(85, 255, 255);
            case 'c' -> new Color(255, 85, 85);   case 'd' -> new Color(255, 85, 255);
            case 'e' -> new Color(255, 255, 85);  default  -> new Color(255, 255, 255);
        };
    }
}
