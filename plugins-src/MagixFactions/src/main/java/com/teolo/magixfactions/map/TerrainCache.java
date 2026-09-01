package com.teolo.magixfactions.map;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.Color;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sorgente di terreno per l'item "Mappa Fazioni".
 *
 * <p>Problema che risolve: la mappa vanilla dipinge il terreno SOLO per i chunk caricati attorno al
 * giocatore; con render/view distance basse (FAST) resta un piccolo cerchio centrale e tutto il resto
 * e' sfondo vuoto. Dato che la nostra mappa si ricentra di continuo sul giocatore, perde anche la
 * persistenza dell'esplorazione. Qui campioniamo noi il terreno: per ogni colonna prendiamo il blocco
 * piu' alto e lo mappiamo a un colore stile-mappa (l'ombreggiatura per rilievo la fa il chiamante,
 * riusando le altezze gia' campionate, vedi {@link FactionMapRenderer}).
 *
 * <p><b>IMPORTANTE (regressione TPS v0.11.0):</b> "FAST" e' un limite di DISEGNO del client, non
 * incide su quali chunk sono caricati lato server — quelli entro la view-distance del server sono
 * gia' caricati a prescindere, ed e' da li' che arriva la stragrande maggioranza del terreno mostrato.
 * v0.11.0 forzava pero' il caricamento sincrono di OGNI chunk mancante, troppo aggressivo (fino a
 * 24/sec) e senza throttle-atomico col ridisegno: ha fatto crollare i TPS.
 *
 * <p><b>v0.11.4 — anche i chunk GIA' caricati non sono gratis:</b> profilato con spark, {@code
 * ChunkSnapshot} (copia sezioni/blockstate del chunk) e' un'operazione non banale, e il render di una
 * mappa a scala CLOSE puo' toccare fino a 256 chunk distinti. Con la TTL breve di prima (20s), ogni
 * volta che scadeva si rifacevano TUTTI gli snapshot visibili in un colpo solo, in un singolo tick —
 * i picchi di TPS visti in gioco. Ora: TTL molto piu' lunga (il terreno cambia raramente) + un
 * **budget globale per finestra temporale** ({@link #BUDGET_PER_WINDOW} snapshot ogni {@link
 * #BUDGET_WINDOW_MS}) che limita quanti snapshot "costosi" si possono davvero rifare in una singola
 * chiamata: se il budget e' esaurito si serve il dato scaduto-ma-ancora-buono (o null) e si riprova
 * alla prossima chiamata, spalmando il costo nel tempo invece di un unico picco.
 *
 * <p><b>v0.12.11 — RIMOSSA la "rivelazione" di chunk non caricati (crash reale in produzione via
 * Watchdog):</b> per rivelare gradualmente chunk esplorati-ma-non-caricati (v0.11.2) chiamavamo {@code
 * World.isChunkGenerated()}. Si e' scoperto (thread-dump del Paper Watchdog durante un pregen con
 * Chunky) che questa chiamata NON e' la semplice lettura in memoria che sembra: puo' BLOCCARE il thread
 * principale in attesa del sistema chunk (va in {@code BlockableEventLoop.managedBlock}) quando quel
 * sistema e' sotto forte carico I/O — anche una volta sola ha bloccato il thread per 15-20+ secondi,
 * facendo scattare il Watchdog ripetutamente. Non esiste un modo sicuro/non-bloccante di chiamarla da
 * Bukkit in queste condizioni, quindi la soluzione e' non chiamarla mai piu' da qui: ora si legge SOLO
 * cio' che e' gia' caricato ({@code isChunkLoaded}, mai bloccante), il resto resta vuoto finche' il gioco
 * non lo carica per conto suo. Si perde la rivelazione "lenta" di chunk esplorati-ma-scaricati, ma e' un
 * compromesso necessario: quel meccanismo si e' dimostrato capace di bloccare il server in produzione.
 *
 * <p><b>v0.18.12 — "rivelazione" delle zone lontane REINTRODOTTA in modo SICURO (async):</b> il crash
 * v0.12.11 veniva dal caricamento SINCRONO (isChunkGenerated + getChunkAt) che bloccava il main thread.
 * Ora invece usiamo {@code World.getChunkAtAsync(cx, cz, false)}: e' l'API Paper apposita che carica il
 * chunk (solo se GIA' generato, {@code gen=false}) sui thread async del sistema chunk, SENZA MAI bloccare
 * il main thread. Al completamento facciamo lo snapshot e lo mettiamo in cache; il chunk poi si scarica da
 * solo, noi teniamo solo lo snapshot. Con un forte rate-limit ({@link #ASYNC_LOADS_PER_WINDOW}) la mappa
 * si "rivela" gradualmente fino ai bordi (utile se il mondo e' pre-generato). Attivabile da config
 * {@code map.reveal-distant-chunks}.
 *
 * <p>Concorrenza: {@code MapRenderer.render} gira sul main thread, e i callback async rientrano sul main
 * thread (via {@code Bukkit.getScheduler().runTask}) prima di toccare le cache: quindi le {@link HashMap}
 * restano accedute da un solo thread.
 */
final class TerrainCache {

    /** {@code water}: la colonna e' acqua di superficie — il colore contiene GIA' la banda di
     *  profondita' stile vanilla e NON va ombreggiato col rilievo (l'acqua e' piatta). */
    record Sample(int height, Color color, boolean water) {}

    private static final long TTL = 180_000L;         // uno snapshot vale 3 minuti poi si rinfresca
    private static final int  MAX_ENTRIES = 20000;    // tetto cache snapshot prima della pulizia (alzato per la rivelazione)
    private static final int  MAX_SAMPLES = 400_000;  // tetto cache colonne (Sample) prima della pulizia
    private static final long BUDGET_WINDOW_MS = 50L; // finestra del budget di snapshot "costosi" (~1 tick)
    private static final int  BUDGET_PER_WINDOW = 8;  // snapshot (creazioni/rinfreschi) al massimo per finestra, globale
    // Cap sui nuovi Sample per finestra. 16384 (= 1 mappa intera in UN tick) concentrava tutto il costo
    // del primo frame freddo (join/teleport/cambio zoom) in un singolo tick -> picco rosso al login.
    // 6144 lo spalma su ~3 tick: il riempimento resta impercettibile (<0.2s) ma il tick respira.
    // ATTENZIONE a non scendere troppo: 1200 (provato in 0.18.7) affamava il rendering e lasciava la
    // mappa VUOTA — da non rifare. Il risultato resta in cache (sampleCache): costo una-tantum per area.
    private static final int  SAMPLE_BUDGET_PER_WINDOW = 6144;
    // Richieste async in volo al massimo. Basso di proposito: 800 saturava il sistema chunk di Paper a
    // zoom lontani (il crollo TPS a 64 blocchi/pixel); 64 lasciava comunque bunching di completamenti.
    // Poche in volo = pressione bassa sul chunk-loader.
    private static final int  MAX_PENDING_LOADS = 16;
    // Passi massimi della scansione verticale nei mondi col tetto (Nether): una colonna alta 256 si
    // percorre al massimo due volte (giu' nel solido, giu' nel vuoto). Guardia contro colonne assurde.
    private static final int  CEILING_SCAN_MAX = 320;
    // Fasce verticali di colonne tenute in cache per mondo (solo mondi col tetto: altrove ce n'e' una).
    private static final int  MAX_SLICES = 6;

    private final JavaPlugin plugin;
    private static final int MAX_NOTGEN = 262144; // tetto del set (Long ~ pochi MB): oltre, si svuota

    /**
     * Le cache di UN mondo.
     *
     * <p><b>Perche' per mondo (bug "cambio mondo, la mappa resta quella vecchia"):</b> le chiavi di
     * queste cache sono pure COORDINATE (chunk x/z, colonna x/z), che nel Nether e nell'End valgono
     * esattamente quanto nell'overworld. Con una cache sola, appena si cambiava mondo ogni colonna
     * trovava gia' pronto il campione dell'altro mondo e lo riusava: la minimap continuava a mostrare
     * il terreno di prima finche' non scadeva la TTL (minuti). Separando le cache per mondo il
     * problema sparisce alla radice, e ogni mondo si riempie per conto suo.
     */
    private static final class WorldData {
        final Map<Long, Entry> chunks = new HashMap<>();
        // Chunk gia' risultati NON generati (getChunkAtAsync gen=false -> null): non vanno mai ritentati,
        // altrimenti a zoom estremo (grande area non generata) si ritenterebbero MIGLIAIA di chunk
        // inesistenti a OGNI render, per sempre. Con questo set la rivelazione e' one-shot per chunk.
        final Set<Long> notGenerated = new HashSet<>();
        final Set<Long> pendingLoads = new HashSet<>(); // chunk (key) attualmente in caricamento async
        // Colonne gia' calcolate, per FETTA verticale. Nei mondi normali c'e' una sola fetta (0: si
        // guarda sempre dall'alto); nei mondi col TETTO la resa dipende dalla quota di chi guarda,
        // quindi una fetta ogni 16 blocchi (vedi sliceOf).
        final Map<Integer, Map<Long, TimedSample>> samples = new HashMap<>();

        Map<Long, TimedSample> samples(int slice) {
            return samples.computeIfAbsent(slice, k -> new HashMap<>());
        }
    }

    private final Map<java.util.UUID, WorldData> worlds = new HashMap<>();
    private World curWorld;      // mondo del render in corso (le chiamate a sampleAt sono tutte dello stesso)
    private WorldData curData;   // le sue cache
    private boolean curCeiling;  // mondo col TETTO (Nether): va guardato da sotto il soffitto
    private Color curEmpty = PAPER; // tinta delle colonne di puro vuoto: beige nell'overworld, scura nell'End
    private int curScanY;        // quota da cui parte la scansione verso il basso nei mondi col tetto
    private int curSlice;        // fetta verticale corrente (chiave della cache colonne)
    private long asyncWindowStart = 0L;
    private int asyncUsed = 0;
    private int asyncPerWindow = 8; // ricaricato da config a ogni finestra (map.reveal-chunks-per-second)
    // Distanza max (in blocchi²) entro cui rivelare chunk lontani. A zoom estremo (es. 64 blocchi/pixel)
    // la mappa inquadra centinaia di chunk: rivelarli TUTTI floodava il chunk-loader -> crollo TPS. Oltre
    // questa distanza dal giocatore i chunk restano beige. Impostata da computeColors (config reveal-max-distance).
    private long revealMaxDistSq = Long.MAX_VALUE;

    // Stile di resa (config map.style): "magix" = tinte per specie/sentieri; "vanilla" = colori della
    // mappa vanilla pura. Riletto una volta per render (qui, non per-pixel); al cambio si svuota la
    // cache dei Sample (i colori cotti dentro sono dello stile vecchio) e si bumpa la versione.
    private boolean styleVanilla = false;

    /** Il mondo ha un soffitto (Nether)? Li' la heightmap e' inutile: da' sempre la bedrock del tetto. */
    private static boolean hasCeiling(World w) {
        return w.getEnvironment() == World.Environment.NETHER || w.hasCeiling();
    }

    /** Fetta verticale di 16 blocchi in cui sta la quota {@code y} (chiave della cache colonne). */
    private static int sliceOf(int y) { return y >> 4; }

    /** Aggancia le cache del mondo indicato e prepara la scansione per la quota di chi guarda. */
    private void useWorld(World w, int refY) {
        curWorld = w;
        curData = worlds.computeIfAbsent(w.getUID(), k -> new WorldData());
        curCeiling = hasCeiling(w);
        // Nell'End il "vuoto" fra le isole non e' carta bianca ma il cielo scuro del vuoto: colonne vuote
        // e zone non esplorate vanno rese quasi-nere/viola scuro, non beige (bug segnalato dall'utente).
        curEmpty = w.getEnvironment() == World.Environment.THE_END ? END_VOID : PAPER;
        curSlice = curCeiling ? sliceOf(refY) : 0;
        // Si parte dal tetto della fascia di chi guarda: cosi' due giocatori nella stessa fascia
        // condividono i campioni (la cache resta utile) e la scansione parte comunque sopra la testa.
        curScanY = curCeiling ? Math.min(w.getMaxHeight() - 1, (curSlice << 4) + 15) : 0;
        // Nei mondi col tetto ogni fascia ha la sua cache di colonne: tenerne troppe costa memoria per
        // niente (nessuno guarda da 8 quote diverse insieme). Oltre MAX_SLICES si butta la piu' lontana
        // dalla fascia in uso — si ricalcola da sola se qualcuno ci torna.
        while (curData.samples.size() > MAX_SLICES) {
            int worst = curSlice, dist = -1;
            for (int sl : curData.samples.keySet()) {
                int d2 = Math.abs(sl - curSlice);
                if (d2 > dist) { dist = d2; worst = sl; }
            }
            if (dist <= 0) break;
            curData.samples.remove(worst);
        }
    }

    /** Le cache del mondo, agganciandolo se e' cambiato (fuori da un render, es. cattura chunk). */
    private WorldData data(World w) {
        if (w != curWorld || curData == null) useWorld(w, w.getSpawnLocation().getBlockY());
        return curData;
    }

    /** Imposta mondo/quota del render in corso, il raggio massimo di rivelazione + rilegge lo stile. */
    void prepareReveal(World w, int refY, int maxDistBlocks) {
        useWorld(w, refY);
        long d = Math.max(0, maxDistBlocks);
        revealMaxDistSq = d >= 3_000_000L ? Long.MAX_VALUE : d * d; // >=3M blocchi = praticamente illimitato
        boolean vanilla = "vanilla".equalsIgnoreCase(plugin.getConfig().getString("map.style", "magix"));
        if (vanilla != styleVanilla) {
            styleVanilla = vanilla;
            for (WorldData wd : worlds.values()) wd.samples.clear(); // colori calcolati con lo stile vecchio
            version++;
        }
    }

    TerrainCache(JavaPlugin plugin) { this.plugin = plugin; }

    // --- Versione del terreno (per la memoizzazione dei frame) ----------------------------------------
    // Bumpa quando il CONTENUTO visibile puo' essere cambiato: blocco modificato (invalidate), nuovo
    // snapshot entrato in cache (reveal async, budget sync, cattura opportunistica). I renderer la
    // confrontano per riusare l'ultimo frame invece di ricalcolare 16k pixel a ogni tick.
    private int version = 0;

    int version() { return version; }

    // --- Guardia anti-sovraccarico (spunto da Cartographer2 isServerOverloaded) -----------------------
    // Quando il tick medio e' gia' alto, TUTTO il lavoro mappa non urgente (rivelazione, cattura) si
    // ferma: mai aggiungere carico a un server gia' in affanno. Usa Paper Server#getAverageTickTime via
    // reflection (non e' in spigot-api): se assente, la guardia resta disattivata.
    private static java.lang.reflect.Method avgTickMethod;
    private static boolean avgTickUnavailable = false;

    static boolean overloaded() {
        if (avgTickUnavailable) return false;
        try {
            if (avgTickMethod == null) avgTickMethod = Bukkit.getServer().getClass().getMethod("getAverageTickTime");
            return ((Number) avgTickMethod.invoke(Bukkit.getServer())).doubleValue() > 45.0;
        } catch (Throwable t) {
            avgTickUnavailable = true;
            return false;
        }
    }

    // --- Cattura OPPORTUNISTICA dei chunk caricati dal gioco (spunto da Cartographer2) ----------------
    // Cartographer di default NON forza alcun caricamento: fotografa i chunk quando il GIOCO li carica
    // da solo (ChunkLoadEvent = giocatori che si muovono). Con molti giocatori online e' una fonte
    // gratuita continua: il chunk e' GIA' caricato/caldo, lo snapshot costa poco e la rivelazione
    // forzata serve solo per le zone che nessuno visita. Coda con dedupe + budget per tick + guardia.
    private record Capture(World world, long key) {}
    private final java.util.ArrayDeque<Capture> captureQueue = new java.util.ArrayDeque<>();
    private final Set<Capture> captureSet = new HashSet<>();
    private static final int CAPTURE_QUEUE_MAX = 1024;
    private static final int CAPTURES_PER_TICK = 2;

    /** Offre un chunk appena caricato dal gioco (da ChunkLoadEvent): messo in coda per lo snapshot.
     *  Vale per OGNI mondo: ora che le cache sono separate per mondo, anche Nether ed End si riempiono
     *  gratis con i chunk che il gioco carica da solo. */
    void offerLoadedChunk(org.bukkit.Chunk c) {
        long ck = key(c.getX(), c.getZ());
        Entry e = data(c.getWorld()).chunks.get(ck);
        if (e != null && System.currentTimeMillis() - e.time() < TTL) return; // gia' fresco
        Capture cap = new Capture(c.getWorld(), ck);
        if (captureSet.size() >= CAPTURE_QUEUE_MAX || !captureSet.add(cap)) return;
        captureQueue.add(cap);
    }

    /** Avvia il task per-tick che consuma la coda di cattura (budget {@link #CAPTURES_PER_TICK}). */
    void startCaptureTask(JavaPlugin pl) {
        Bukkit.getScheduler().runTaskTimer(pl, () -> {
            if (captureQueue.isEmpty() || overloaded()) return;
            long now = System.currentTimeMillis();
            for (int i = 0; i < CAPTURES_PER_TICK && !captureQueue.isEmpty(); i++) {
                Capture cap = captureQueue.poll();
                captureSet.remove(cap);
                World w = cap.world();
                long ck = cap.key();
                int cx = (int) ck, cz = (int) (ck >> 32);
                if (!w.isChunkLoaded(cx, cz)) continue;                      // gia' scaricato: pace
                Map<Long, Entry> chunks = data(w).chunks;
                Entry e = chunks.get(ck);
                if (e != null && now - e.time() < TTL) continue;             // nel frattempo gia' fotografato
                if (chunks.size() > MAX_ENTRIES / 2 && e == null) continue;  // pressione memoria: solo refresh
                chunks.put(ck, new Entry(w.getChunkAt(cx, cz).getChunkSnapshot(true, false, false), now));
                version++;
            }
        }, 1L, 1L);
        // Igiene memoria: le cache di un mondo dove non c'e' nessuno non servono a nessuno (e ora ce n'e'
        // una per mondo). Ogni 5 minuti quelle dei mondi vuoti si buttano: si rifanno da sole al rientro.
        Bukkit.getScheduler().runTaskTimer(pl, () -> {
            worlds.keySet().removeIf(id -> {
                World w = Bukkit.getWorld(id);
                return w == null || w.getPlayers().isEmpty();
            });
            if (curWorld != null && !worlds.containsKey(curWorld.getUID())) { curWorld = null; curData = null; }
        }, 6000L, 6000L);
    }

    private boolean revealEnabled() {
        return plugin.getConfig().getBoolean("map.reveal-distant-chunks", true);
    }

    // Cache del RISULTATO per colonna (altezza + colore gia' calcolati): vive in WorldData#samples.
    // Senza, OGNI render (anche a snapshot gia' in cache) rileggeva il blocco di superficie per ognuno
    // dei 16384 pixel (getHighestBlockYAt + getBlockType su PalettedContainer) — profilato con spark
    // come IL costo dominante del render (~26ms), non il colore. Con la cache un render "a caldo" e'
    // solo lookup.
    private long budgetWindowStart = 0L;
    private int budgetUsed = 0;
    private long sampleWindowStart = 0L;
    private int sampleBudgetUsed = 0;

    private record Entry(ChunkSnapshot snap, long time) {}
    private record TimedSample(Sample sample, long time) {}

    private static long key(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) | ((long) cz << 32);
    }

    /** TTL effettiva per una chiave: base + jitter deterministico 0..45s. Senza jitter, tutte le voci
     *  create nello stesso render (16k colonne in un colpo) scadevano INSIEME 3 minuti dopo -> picco
     *  periodico di ricalcoli in un singolo tick. Col jitter le scadenze si spalmano. */
    private static long ttlFor(long k) {
        return TTL + ((k * 0x9E3779B97F4A7C15L >>> 40) % 45_000L);
    }

    /** Chiave di colonna (blocchi bx,bz) — bx/bz stanno in 32 bit (mondo entro ±30M < 2^31). */
    private static long columnKey(int bx, int bz) {
        return ((long) bx & 0xFFFFFFFFL) | ((long) bz << 32);
    }

    // --- Rivelazione radiale delle zone lontane -------------------------------------------------------
    // Candidati raccolti durante UN render (chunk non caricati visibili) con la loro distanza² dal centro.
    // Ripuliti a ogni flush (chiamato a fine render). Ordinandoli per distanza crescente, richiediamo il
    // caricamento async dei chunk piu' VICINI al centro per primi -> la mappa si rivela in cerchi che si
    // espandono dall'interno verso l'esterno, invece che in ordine di scansione dei pixel (riga per riga).
    private final Map<Long, Long> frameCandidates = new HashMap<>();

    private void markRevealCandidate(WorldData d, long ck, int bx, int bz, int centerX, int centerZ) {
        if (d.pendingLoads.contains(ck) || d.notGenerated.contains(ck)) return; // in volo o gia' noto inesistente
        long dx = bx - centerX, dz = bz - centerZ;
        long distSq = dx * dx + dz * dz;
        if (distSq > revealMaxDistSq) return; // troppo lontano: non rivelare (evita il flood a zoom estremo)
        Long cur = frameCandidates.get(ck);
        if (cur == null || distSq < cur) frameCandidates.put(ck, distSq); // tiene la distanza minima (viewer piu' vicino)
    }

    /** A fine render: richiede il caricamento async dei chunk non caricati visibili piu' VICINI al
     *  centro, entro il rate-limit. Da chiamare dopo aver campionato tutti i pixel (vedi
     *  {@link MapContentBuilder}).
     *  <p><b>Niente sort completo (fix TPS v0.23.3):</b> a zoom lontano i candidati sono MIGLIAIA e
     *  questo metodo gira a OGNI render (ogni tick, per ENTRAMBE le mappe): ordinarli tutti per poi
     *  caricarne 1-2 (il budget per finestra) costava piu' della rivelazione stessa — contribuiva al
     *  crollo TPS "durante il riempimento della mappa". Ora: selezione bounded dei k minimi (k = budget
     *  disponibile, minuscolo), scansione lineare per ciascuno. */
    void flushReveals(World w) {
        if (frameCandidates.isEmpty()) return;
        if (!revealEnabled() || asyncUnavailable) { frameCandidates.clear(); return; }
        if (overloaded()) { frameCandidates.clear(); return; } // server in affanno: niente carico extra ORA
        WorldData d = data(w);
        long now = System.currentTimeMillis();
        while (d.pendingLoads.size() < MAX_PENDING_LOADS && !asyncUnavailable && asyncBudgetAllows(now)) {
            long bestKey = 0, bestDist = Long.MAX_VALUE;
            for (Map.Entry<Long, Long> en : frameCandidates.entrySet()) {
                if (en.getValue() < bestDist && !d.pendingLoads.contains(en.getKey())) {
                    bestDist = en.getValue();
                    bestKey = en.getKey();
                }
            }
            if (bestDist == Long.MAX_VALUE) break; // nessun candidato utilizzabile
            frameCandidates.remove(bestKey);
            requestAsyncLoad(w, d, (int) bestKey, (int) (bestKey >> 32), bestKey);
        }
        frameCandidates.clear();
    }

    /**
     * Carica in modo ASINCRONO il chunk (solo se gia' generato, {@code gen=false}) e ne mette lo snapshot
     * in cache. {@code getChunkAtAsync} NON blocca il main thread (a differenza del vecchio getChunkAt
     * sincrono che causava il crash v0.12.11): il caricamento e' sui thread del sistema chunk di Paper. Il
     * chunk si scarica poi da solo, noi teniamo solo lo snapshot.
     */
    @SuppressWarnings("unchecked")
    private void requestAsyncLoad(World w, WorldData d, int cx, int cz, long ck) {
        java.util.concurrent.CompletableFuture<org.bukkit.Chunk> future;
        try {
            future = (java.util.concurrent.CompletableFuture<org.bukkit.Chunk>)
                    getChunkAtAsyncMethod(w).invoke(w, cx, cz, false);
        } catch (Throwable t) {
            asyncUnavailable = true; // API non disponibile: disattiva la rivelazione (nessun errore ripetuto)
            plugin.getLogger().warning("[Map] Rivelazione zone lontane non disponibile (getChunkAtAsync): " + t);
            return;
        }
        if (future == null) return;
        d.pendingLoads.add(ck);
        future.whenComplete((chunk, ex) ->
            // Rientra SEMPRE sul main thread prima di toccare cache/chunk (HashMap non thread-safe, snapshot
            // va creato sul main thread).
            Bukkit.getScheduler().runTask(plugin, () -> {
                d.pendingLoads.remove(ck);
                if (ex == null && chunk == null) {
                    // gen=false + future risolta a null = chunk NON generato: segnalo per non ritentarlo mai piu'.
                    if (d.notGenerated.size() >= MAX_NOTGEN) d.notGenerated.clear();
                    d.notGenerated.add(ck);
                    return;
                }
                if (ex != null || chunk == null || !w.isChunkLoaded(cx, cz)) return;
                long t = System.currentTimeMillis();
                // Tetto di snapshot per tick: se troppi caricamenti async completano nello stesso tick, non
                // li snapshottiamo tutti qui (eviterebbe il picco sul main thread). Il chunk resta caricato e
                // lo prendera' il percorso sync budgetato in {@link #sampleAt} a un render successivo.
                if (t - snapTickStart >= 40) { snapTickStart = t; snapThisTick = 0; }
                if (snapThisTick >= MAX_SNAPSHOTS_PER_TICK) return;
                snapThisTick++;
                ChunkSnapshot s = chunk.getChunkSnapshot(true, false, false);
                d.chunks.put(ck, new Entry(s, t));
                version++; // nuovo terreno visibile: i frame memoizzati vanno rifatti
                if (d.chunks.size() > MAX_ENTRIES) d.chunks.values().removeIf(en -> t - en.time() > TTL);
            }));
    }

    private long snapTickStart = 0L;
    private int snapThisTick = 0;
    // Snapshot di rivelazione al massimo per tick (anti-picco): e' IL costo main-thread della rivelazione.
    // Tenuto basso (3): a zoom estremo, es. 64 blocchi/pixel, ci sono ~13k chunk da rivelare e uno snapshot
    // (copia del chunk) costa ~0.5-1ms -> con 6/tick i TPS calavano a 15-17 durante il riempimento. Con 3 il
    // main thread respira; la mappa si riempie piu' lentamente ma i TPS restano sani.
    private static final int MAX_SNAPSHOTS_PER_TICK = 3;

    private static java.lang.reflect.Method chunkAsyncMethod;
    private boolean asyncUnavailable = false;

    private static java.lang.reflect.Method getChunkAtAsyncMethod(World w) throws NoSuchMethodException {
        if (chunkAsyncMethod == null) {
            chunkAsyncMethod = w.getClass().getMethod("getChunkAtAsync", int.class, int.class, boolean.class);
        }
        return chunkAsyncMethod;
    }

    /** Rate-limit dei caricamenti async "di rivelazione": limite per finestra derivato dal config
     *  {@code map.reveal-chunks-per-second} (riletto una volta per finestra, non a ogni chiamata). */
    private boolean asyncBudgetAllows(long now) {
        if (now - asyncWindowStart >= BUDGET_WINDOW_MS) {
            asyncWindowStart = now;
            asyncUsed = 0;
            int perSec = Math.max(1, plugin.getConfig().getInt("map.reveal-chunks-per-second", 20));
            asyncPerWindow = Math.max(1, (int) Math.ceil(perSec * BUDGET_WINDOW_MS / 1000.0));
        }
        if (asyncUsed >= asyncPerWindow) return false;
        asyncUsed++;
        return true;
    }

    /** Rate-limit degli snapshot "costosi": max {@link #BUDGET_PER_WINDOW} ogni {@link #BUDGET_WINDOW_MS}. */
    private boolean budgetAllows(long now) {
        if (now - budgetWindowStart >= BUDGET_WINDOW_MS) { budgetWindowStart = now; budgetUsed = 0; }
        if (budgetUsed >= BUDGET_PER_WINDOW) return false;
        budgetUsed++;
        return true;
    }

    /** Rate-limit dei calcoli Sample: max {@link #SAMPLE_BUDGET_PER_WINDOW} ogni {@link #BUDGET_WINDOW_MS}. */
    private boolean sampleBudgetAllows(long now) {
        if (now - sampleWindowStart >= BUDGET_WINDOW_MS) { sampleWindowStart = now; sampleBudgetUsed = 0; }
        if (sampleBudgetUsed >= SAMPLE_BUDGET_PER_WINDOW) return false;
        sampleBudgetUsed++;
        return true;
    }

    /**
     * Scarta lo snapshot cache del chunk contenente (bx,bz): il prossimo {@link #sampleAt} lo rifara'
     * (soggetto al solito budget/TTL, quindi resta al sicuro per i TPS). Va chiamato quando un blocco
     * viene piazzato/rotto in quella colonna — altrimenti la mappa mostrerebbe il terreno vecchio fino
     * alla scadenza naturale della TTL (3 minuti, pensata per terreno che di norma NON cambia da solo).
     */
    void invalidate(World w, int bx, int bz) {
        WorldData d = data(w);
        long ck = key(bx >> 4, bz >> 4);
        d.chunks.remove(ck);
        d.notGenerated.remove(ck); // se era "inesistente" e ora si tocca un blocco li', il chunk esiste: riprovabile
        // Il risultato della colonna e' cambiato: ricalcolalo al prossimo render, in TUTTE le fette
        // (nei mondi col tetto la stessa colonna e' campionata una volta per quota di osservazione).
        long colK = columnKey(bx, bz);
        for (Map<Long, TimedSample> m : d.samples.values()) m.remove(colK);
        version++;
    }

    /** Altezza + colore-base (non ombreggiato) alla colonna world (bx,bz), rispetto al centro
     *  ({@code centerX},{@code centerZ}) del render corrente; null se non disponibile. Prima consulta la
     *  cache dei Sample (render a caldo = solo lookup); poi lo snapshot in cache; se il chunk non e'
     *  caricato lo segna come candidato per la rivelazione radiale ({@link #markRevealCandidate}) e usa il
     *  dato vecchio/null; se e' caricato ne fa lo snapshot (soggetto al budget). */
    Sample sampleAt(World w, int bx, int bz, int centerX, int centerZ) {
        WorldData d = data(w);
        Map<Long, TimedSample> samples = d.samples(curSlice);
        long colK = columnKey(bx, bz);
        long now = System.currentTimeMillis();
        TimedSample ts = samples.get(colK);
        if (ts != null && now - ts.time() < ttlFor(colK)) return ts.sample();

        int cx = bx >> 4, cz = bz >> 4;
        long ck = key(cx, cz);
        Entry e = d.chunks.get(ck);
        ChunkSnapshot s;
        if (e != null && now - e.time() < ttlFor(ck)) {
            s = e.snap();                                    // snapshot fresco in cache
        } else if (!w.isChunkLoaded(cx, cz)) {
            // Chunk non caricato: NON lo carichiamo mai in modo sincrono (bloccherebbe il thread, crash
            // v0.12.11). Lo segniamo come candidato per la rivelazione async radiale; intanto dato vecchio o null.
            if (revealEnabled() && !asyncUnavailable) markRevealCandidate(d, ck, bx, bz, centerX, centerZ);
            s = e != null ? e.snap() : null;
        } else if (sampleBudgetAllows(now) && budgetAllows(now)) {
            // Caricato: ne facciamo lo snapshot (doppio budget: snapshot "costosi" + calcoli sample).
            s = w.getChunkAt(cx, cz).getChunkSnapshot(true, false, false);
            d.chunks.put(ck, new Entry(s, now));
            version++;
            if (d.chunks.size() > MAX_ENTRIES) d.chunks.values().removeIf(en -> now - en.time() > TTL);
        } else {
            s = e != null ? e.snap() : null;                 // budget esaurito: vecchio o null
        }
        if (s == null) return ts != null ? ts.sample() : null;

        int lx = bx & 15, lz = bz & 15;
        int min = w.getMinHeight(), maxY = w.getMaxHeight() - 1;
        int h;
        int by;
        Material m;
        if (curCeiling) {
            // MONDI COL TETTO (Nether). Qui il blocco piu' alto della colonna e' SEMPRE la bedrock del
            // soffitto: la mappa diventava una lastra uniforme, illeggibile (bug segnalato). Si guarda
            // percio' da SOTTO il soffitto, partendo dalla fascia di quota di chi guarda: prima si
            // attraversa l'eventuale roccia sopra la testa, poi si scende nel vuoto fino al primo blocco
            // visibile — cioe' il pavimento della caverna in cui ci si trova. Scendendo di quota la
            // mappa segue il giocatore, come fa una minimap "in caverna".
            int y = clamp(curScanY, min, maxY);
            int steps = 0;
            while (y > min && !isInvisible(s.getBlockType(lx, y, lz)) && steps++ < CEILING_SCAN_MAX) y--;
            while (y > min && isInvisible(s.getBlockType(lx, y, lz)) && steps++ < CEILING_SCAN_MAX) y--;
            by = y;
            m = s.getBlockType(lx, by, lz);
            h = by;
        } else {
            h = s.getHighestBlockYAt(lx, lz);
            by = clamp(h, min, maxY);
            m = s.getBlockType(lx, by, lz);
            // Scavalca verso il basso i blocchi INVISIBILI in gioco (aria, BARRIER, LIGHT, STRUCTURE_VOID):
            // sulla mappa non devono esistere (le barriere trasparenti venivano disegnate grigie — bug
            // segnalato; la heightmap MOTION_BLOCKING le include perche' bloccano il movimento). Tetto di
            // discesa 128: copre gabbie/cupole di barrier sopra le costruzioni senza scansioni infinite.
            int steps = 0;
            while (isInvisible(m) && by - 1 >= min && steps++ < 128) { by--; m = s.getBlockType(lx, by, lz); }
        }
        if (isInvisible(m)) {
            // Colonna interamente vuota (mondo void): colore del vuoto del mondo (beige carta nell'overworld,
            // scuro nell'End), come le zone non esplorate — mai il grigio-roccia del fallback. Sample "vero"
            // (non null) cosi' il frame resta completo e la memoizzazione funziona anche su mappe piene di vuoto.
            Sample sample = new Sample(min, curEmpty, false);
            if (samples.size() > MAX_SAMPLES) samples.clear();
            samples.put(colK, new TimedSample(sample, now));
            return sample;
        }

        Sample sample;
        Color base = materialColor(m, styleVanilla);
        if (base == WATER) {
            // Acqua: BANDE DI PROFONDITA' stile vanilla (bassi fondali chiari, largo scuro, col tipico
            // pattern a scacchi sul confine delle bande dato dalla parita' x+z). E' cio' che rende gli
            // oceani vanilla "vivi" invece di una campitura blu piatta. La profondita' si misura
            // scendendo dalla superficie (tetto 10: oltre e' comunque la banda piu' scura).
            int depth = 0;
            while (depth < 10 && by - 1 - depth >= min && isWaterLike(s.getBlockType(lx, by - 1 - depth, lz))) depth++;
            double band = depth * 0.1 + (((bx + bz) & 1) * 0.2);
            int lum = band < 0.5 ? 255 : (band > 0.9 ? 180 : 220);
            sample = new Sample(h, new Color(WATER.getRed() * lum / 255, WATER.getGreen() * lum / 255,
                    WATER.getBlue() * lum / 255), true);
        } else {
            sample = new Sample(h, base, false);
        }
        if (samples.size() > MAX_SAMPLES) samples.clear();
        samples.put(colK, new TimedSample(sample, now));
        return sample;
    }

    /** Blocchi che contano come "colonna d'acqua" ai fini della profondita' (acqua + vegetazione acquatica). */
    private static boolean isWaterLike(Material m) {
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN) return true;
        String n = m.name();
        return n.contains("KELP") || n.contains("SEAGRASS");
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private static boolean isAir(Material m) {
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR;
    }

    /** Blocchi INVISIBILI o "trasparenti" sulla mappa: si ignora e si guarda cosa c'e' sotto —
     *  come fa la mappa vanilla per vetro semplice, torce, ecc. (il VETRO COLORATO invece si vede,
     *  col colore della tinta: gestito in computeMaterialColor). */
    private static boolean isInvisible(Material m) {
        if (isAir(m) || m == Material.BARRIER || m == Material.LIGHT || m == Material.STRUCTURE_VOID) return true;
        String n = m.name();
        return n.equals("GLASS") || n.equals("GLASS_PANE") || n.equals("TINTED_GLASS")
                || n.endsWith("_TORCH") || n.equals("TORCH")
                || n.equals("LANTERN") || n.equals("SOUL_LANTERN") || n.equals("CHAIN")
                || n.equals("LADDER") || n.equals("LEVER") || n.equals("TRIPWIRE")
                || n.equals("TRIPWIRE_HOOK") || n.equals("REDSTONE_WIRE") || n.contains("CANDLE");
    }

    // Tono "carta vuota" per le colonne di puro vuoto (stesso valore dell'UNKNOWN di MapContentBuilder:
    // il vuoto deve sembrare "niente qui", identico alle zone non esplorate, mai grigio-roccia).
    private static final Color PAPER = new Color(198, 178, 148);
    // Tono del vuoto nell'End: viola molto scuro, quasi nero — come il cielo del vuoto dell'End.
    private static final Color END_VOID = new Color(18, 13, 28);

    /** Tinta con cui rendere le zone SENZA terreno (vuoto + non ancora esplorato) nel mondo del render in
     *  corso: beige "carta" ovunque, viola-scuro nell'End. La usa anche {@link MapContentBuilder} per i
     *  pixel senza dato, cosi' vuoto e non-esplorato restano coerenti. */
    Color emptyColor() { return curEmpty; }

    // --- Colori base stile-mappa (luminosita' "normale" di Minecraft) ---------------------------------
    private static final Color GRASS  = new Color(127, 178, 56);
    private static final Color PLANT  = new Color(0, 124, 0);
    private static final Color WATER  = new Color(64, 64, 255);
    private static final Color LAVA   = new Color(255, 102, 0);
    private static final Color SAND   = new Color(247, 233, 163);
    private static final Color DIRT   = new Color(151, 109, 77);
    private static final Color PODZOL = new Color(129, 86, 49);
    private static final Color STONE  = new Color(112, 112, 112);
    private static final Color SNOW   = new Color(255, 255, 255);
    private static final Color ICE    = new Color(160, 160, 255);
    private static final Color CLAY   = new Color(164, 168, 184);
    private static final Color WOOD   = new Color(143, 119, 72);
    private static final Color NETHER = new Color(112, 2, 0);
    private static final Color METAL  = new Color(167, 167, 167);
    private static final Color GOLD   = new Color(250, 238, 77);
    private static final Color DIAMOND= new Color(92, 219, 213);
    private static final Color LAPIS  = new Color(74, 128, 255);
    private static final Color EMERALD= new Color(0, 217, 58);
    private static final Color QUARTZ = new Color(255, 252, 245);
    private static final Color TERRA  = new Color(153, 51, 51); // rosso terracotta/mesa
    // --- Mondo dell'End ---: senza questi END_STONE cadeva nel fallback grigio-roccia (STONE) e TUTTO
    // l'End usciva grigio e illeggibile (bug segnalato). End stone = giallo-pallido come in vanilla, ben
    // staccato dal beige del vuoto (PAPER); purpur = viola chiaro; ossidiana/uovo = quasi nero.
    private static final Color ENDSTONE = new Color(219, 213, 156); // giallo-sabbia dell'End
    private static final Color PURPUR   = new Color(169, 125, 169); // blocchi purpur delle città
    private static final Color CHORUS   = new Color(126, 90, 126);  // piante/fiori di chorus
    private static final Color OBSIDIAN = new Color(20, 18, 30);    // ossidiana / uovo del drago

    // Cache Material->Color: computeMaterialColor fa parsing di stringhe (name() + molti contains/startsWith)
    // e veniva chiamato 1 volta PER PIXEL per render (16384x) — era il grosso del costo di computeColors,
    // NON matchColor. Materiale->colore e' costante PER STILE, quindi una cache per ciascuno stile.
    private static final java.util.Map<Material, Color> COLOR_MAGIX = new java.util.EnumMap<>(Material.class);
    private static final java.util.Map<Material, Color> COLOR_VANILLA = new java.util.EnumMap<>(Material.class);

    /** Colore stile-mappa del materiale, da cache (calcolato una volta per Material e stile). */
    private static Color materialColor(Material m, boolean vanilla) {
        java.util.Map<Material, Color> cache = vanilla ? COLOR_VANILLA : COLOR_MAGIX;
        Color c = cache.get(m);
        if (c == null) {
            c = computeMaterialColor(m, vanilla);
            cache.put(m, c);
        }
        return c;
    }

    /** Mappa un materiale (blocco di superficie) al suo colore stile-mappa. Euristiche sul nome per
     *  robustezza. {@code vanilla} = resa vanilla pura (map.style): niente tinte per specie/sentieri. */
    private static Color computeMaterialColor(Material m, boolean vanilla) {
        String n = m.name();

        if (m == Material.WATER || m == Material.BUBBLE_COLUMN || n.contains("KELP")
                || n.contains("SEAGRASS") || m == Material.LILY_PAD) return WATER;
        if (m == Material.LAVA) return LAVA;

        if (!vanilla) {
            // Stile "magix" — fogliame DIFFERENZIATO per specie: si distinguono betulle, abeti, giungla,
            // ciliegi ecc. Tinte tutte NETTAMENTE piu' scure/sature del prato (GRASS 127,178,56): un bosco
            // deve leggersi come bosco. NB: la prima betulla (115,197,72) era troppo simile al prato ->
            // un bosco di betulle sembrava una pianura (bug segnalato dall'utente), da qui i toni attuali.
            if (n.equals("BIRCH_LEAVES"))    return new Color(72, 150, 48);    // betulla: verde medio (piu' chiaro della quercia, mai "prato")
            if (n.equals("SPRUCE_LEAVES"))   return new Color(0, 100, 70);     // abete: verde-teal scuro
            if (n.equals("DARK_OAK_LEAVES")) return new Color(0, 90, 0);       // quercia scura: il piu' cupo
            if (n.equals("JUNGLE_LEAVES"))   return new Color(32, 148, 26);    // giungla: verde pieno
            if (n.equals("ACACIA_LEAVES"))   return new Color(86, 130, 36);    // acacia: oliva scuro
            if (n.equals("CHERRY_LEAVES"))   return new Color(224, 163, 186);  // ciliegio: ROSA
            if (n.equals("MANGROVE_LEAVES")) return new Color(40, 115, 35);    // mangrovia
            if (n.contains("AZALEA"))        return new Color(72, 138, 52);    // azalea (anche fiorita)
            if (n.equals("DIRT_PATH"))       return new Color(196, 164, 108);  // sentieri: sabbia, ben visibili
            if (n.equals("PUMPKIN") || n.equals("CARVED_PUMPKIN")) return new Color(219, 125, 62);
            if (n.equals("RED_MUSHROOM_BLOCK"))   return new Color(178, 76, 76);
            if (n.equals("BROWN_MUSHROOM_BLOCK")) return new Color(149, 108, 76);
            if (n.equals("MUSHROOM_STEM"))        return new Color(199, 199, 199);
        }
        if (n.contains("LEAVES")) return PLANT;
        // Famiglia LEGNO per SPECIE: slab, scale, recinzioni, cancelli, botole, porte, cartelli,
        // pedane e bottoni prendono il colore del loro legno (prima cadevano TUTTI nel grigio-roccia
        // di fallback: le slab del tetto sparivano — bug segnalato). Vale anche in stile vanilla
        // (la mappa vanilla colora le slab come le assi della loro specie).
        Color wood = woodFamilyColor(n);
        if (wood != null) return wood;
        if (n.contains("STAINED_GLASS")) {                       // vetro COLORATO: si vede, col colore
            Color dye = dyeColor(n);
            if (dye != null) return dye;
        }
        if (n.equals("HAY_BLOCK")) return new Color(219, 204, 44); // balle di fieno: gialle
        if (n.equals("WHEAT") || n.equals("CARROTS") || n.equals("POTATOES")
                || n.equals("BEETROOTS") || n.equals("SWEET_BERRY_BUSH")) return PLANT; // coltivazioni
        if (n.endsWith("_BED")) {                                  // letti: colore della tinta
            Color dye = dyeColor(n);
            if (dye != null) return dye;
        }
        if (n.contains("PLANKS") || n.endsWith("_LOG") || n.endsWith("_WOOD")
                || n.endsWith("_STEM") || n.endsWith("_HYPHAE")) return WOOD;

        if (n.equals("GRASS_BLOCK") || n.contains("MOSS") || n.contains("FERN")
                || n.equals("SHORT_GRASS") || n.equals("TALL_GRASS") || n.contains("VINE")
                || n.contains("SAPLING") || n.equals("GRASS")) return GRASS;
        if (m == Material.PODZOL) return PODZOL;
        if (m == Material.MYCELIUM) return new Color(127, 63, 178);

        if (n.equals("SAND") || (n.contains("SANDSTONE") && !n.startsWith("RED"))) return SAND;
        if (n.startsWith("RED_SAND") || n.contains("RED_SANDSTONE")) return new Color(216, 127, 51);

        if (m == Material.GRAVEL || m == Material.CLAY) return CLAY;

        if (n.contains("DIRT") || m == Material.MUD || m == Material.FARMLAND
                || m == Material.MUD_BRICKS || n.contains("MUDDY")) return DIRT;

        if (n.contains("SNOW")) return SNOW;
        if (n.contains("ICE")) return ICE;

        if (n.contains("NETHERRACK") || n.contains("NETHER_WART_BLOCK") || m == Material.MAGMA_BLOCK) return NETHER;

        // Mondo dell'End: senza queste righe END_STONE (di cui è fatto tutto l'End) cadeva nel grigio
        // generico e la mappa era illeggibile. Purpur/chorus/ossidiana danno risalto alle città e ai pilastri.
        if (n.contains("END_STONE") || m == Material.END_PORTAL_FRAME) return ENDSTONE;
        if (n.contains("PURPUR")) return PURPUR;
        if (m == Material.CHORUS_PLANT || m == Material.CHORUS_FLOWER) return CHORUS;
        if (m == Material.OBSIDIAN || m == Material.CRYING_OBSIDIAN || m == Material.DRAGON_EGG) return OBSIDIAN;
        if (m == Material.END_ROD) return QUARTZ;

        // Blocchi colorati (wool/concrete/terracotta/glazed): dedotti dal prefisso colore.
        if (n.contains("TERRACOTTA") && !n.equals("TERRACOTTA")) {
            Color dye = dyeColor(n);
            return dye != null ? mix(dye, TERRA) : TERRA;
        }
        if (n.equals("TERRACOTTA")) return TERRA;
        if (n.contains("WOOL") || n.contains("CONCRETE") || n.contains("GLAZED")
                || n.contains("SHULKER") || n.contains("CARPET")) {
            Color dye = dyeColor(n);
            if (dye != null) return dye;
        }

        if (m == Material.GOLD_BLOCK || m == Material.RAW_GOLD_BLOCK) return GOLD;
        if (m == Material.IRON_BLOCK || m == Material.ANVIL || m == Material.RAW_IRON_BLOCK) return METAL;
        if (m == Material.DIAMOND_BLOCK) return DIAMOND;
        if (m == Material.EMERALD_BLOCK) return EMERALD;
        if (m == Material.LAPIS_BLOCK) return LAPIS;
        if (n.contains("QUARTZ")) return QUARTZ;

        // Roccia generica (stone/cobble/andesite/diorite/granite/deepslate/tuff/basalt/blackstone/ores/bedrock...)
        return STONE;
    }

    /** Colore del legname per SPECIE, applicato a tutta la famiglia di forme in legno (assi, slab,
     *  scale, recinzioni, cancelli, botole, porte, pedane, bottoni, cartelli). Ritorna null se il
     *  blocco non e' una forma in legno (le slab/scale di PIETRA restano alle regole generali). */
    private static Color woodFamilyColor(String n) {
        boolean woodShape = n.contains("PLANKS") || n.endsWith("_SLAB") || n.endsWith("_STAIRS")
                || n.endsWith("_FENCE") || n.endsWith("_FENCE_GATE") || n.endsWith("_TRAPDOOR")
                || n.endsWith("_DOOR") || n.endsWith("_PRESSURE_PLATE") || n.endsWith("_BUTTON")
                || n.endsWith("_SIGN");
        if (!woodShape) return null;
        if (n.startsWith("OAK_"))      return WOOD;                      // quercia: legno classico
        if (n.startsWith("SPRUCE_"))   return new Color(114, 84, 48);    // abete: marrone scuro
        if (n.startsWith("BIRCH_"))    return new Color(199, 178, 121);  // betulla: chiaro
        if (n.startsWith("JUNGLE_"))   return new Color(160, 115, 80);
        if (n.startsWith("ACACIA_"))   return new Color(168, 90, 50);    // arancio
        if (n.startsWith("DARK_OAK_")) return new Color(102, 76, 51);
        if (n.startsWith("MANGROVE_")) return new Color(118, 54, 42);
        if (n.startsWith("CHERRY_"))   return new Color(214, 162, 149);  // rosato
        if (n.startsWith("BAMBOO_"))   return new Color(193, 173, 78);
        if (n.startsWith("CRIMSON_"))  return new Color(148, 63, 97);
        if (n.startsWith("WARPED_"))   return new Color(22, 126, 134);
        return null;
    }

    /** Colore approssimato del tinte-standard leggendo il prefisso colore del nome (WHITE_, LIGHT_BLUE_, ...). */
    private static Color dyeColor(String n) {
        if (n.startsWith("LIGHT_BLUE")) return new Color(102, 153, 216);
        if (n.startsWith("LIGHT_GRAY")) return new Color(153, 153, 153);
        if (n.startsWith("WHITE"))   return new Color(199, 199, 199);
        if (n.startsWith("ORANGE"))  return new Color(216, 127, 51);
        if (n.startsWith("MAGENTA")) return new Color(178, 76, 216);
        if (n.startsWith("YELLOW"))  return new Color(229, 229, 51);
        if (n.startsWith("LIME"))    return new Color(127, 204, 25);
        if (n.startsWith("PINK"))    return new Color(242, 127, 165);
        if (n.startsWith("GRAY"))    return new Color(76, 76, 76);
        if (n.startsWith("CYAN"))    return new Color(76, 127, 153);
        if (n.startsWith("PURPLE"))  return new Color(127, 63, 178);
        if (n.startsWith("BLUE"))    return new Color(51, 76, 178);
        if (n.startsWith("BROWN"))   return new Color(102, 76, 51);
        if (n.startsWith("GREEN"))   return new Color(102, 127, 51);
        if (n.startsWith("RED"))     return new Color(153, 51, 51);
        if (n.startsWith("BLACK"))   return new Color(25, 25, 25);
        return null;
    }

    private static Color mix(Color a, Color b) {
        return new Color((a.getRed() + b.getRed()) / 2,
                (a.getGreen() + b.getGreen()) / 2,
                (a.getBlue() + b.getBlue()) / 2);
    }
}
