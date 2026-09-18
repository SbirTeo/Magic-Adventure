package com.teolo.magixpack.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Costruisce (fondendo i contenuti registrati da ogni plugin) e serve via un piccolo server HTTP
 * integrato ({@code com.sun.net.httpserver.HttpServer}, gia' nella JDK: nessuna dipendenza nuova)
 * il resource pack UNICO del server. Un client Minecraft applica un solo pacchetto alla volta:
 * ecco perche' non e' ognuno dei plugin a spedire il proprio, ma tutti registrano il loro
 * contenuto qui (vedi {@link #register}) e questo plugin li fonde in un unico zip.
 *
 * <p>Con {@code required: true} (default) il pack e' OBBLIGATORIO: viene inviato a ogni
 * giocatore al join e chi non lo carica viene espulso — vedi {@link PackListener}.
 */
public final class PackService {

    /** File propri di MagixPack (percorso relativo alla cartella "resourcepack/" nel classpath
     *  del jar): oggi solo il manifesto dello zip. Gli altri plugin registrano il resto. */
    private static final String[] OWN_FILES = { "pack.mcmeta" };

    private final JavaPlugin plugin;
    /** Contenuto registrato da ogni plugin: nome del plugin -> (percorso nello zip -> bytes). */
    private final Map<String, Map<String, byte[]>> contributions = new ConcurrentHashMap<>();
    private volatile HttpServer server;
    private volatile ExecutorService httpPool;
    private BukkitTask watchdog;
    private volatile boolean started;
    private int port;
    private volatile byte[] packZip;
    private volatile byte[] sha1;
    private volatile String publicUrl;

    public PackService(JavaPlugin plugin) {
        this.plugin = plugin;
        // Creata subito, anche vuota: e' il posto dove lo staff mette le proprie personalizzazioni,
        // deve essere visibile senza dover indovinare il nome giusto (vedi readOverrides()).
        new java.io.File(plugin.getDataFolder(), OVERRIDES_DIR).mkdirs();
    }

    /** Registra (o sostituisce) il contenuto di {@code owner} nel pacchetto: percorso nello zip
     *  (es. "assets/magixauth/font/gui.json") -> bytes. Il chiamante ha gia' risolto i propri
     *  segnaposto (es. dal proprio config): qui ci si limita a fondere. Se il servizio e' gia'
     *  partito, lo zip viene ricostruito e riservito subito. */
    public synchronized void register(Plugin owner, Map<String, byte[]> files) {
        contributions.put(owner.getName(), new LinkedHashMap<>(files));
        plugin.getLogger().info("[Pack] " + owner.getName() + " ha registrato " + files.size()
                + " file nel pacchetto.");
        if (started) rebuild();
    }

    /** Toglie il contenuto registrato da {@code owner} (es. al suo onDisable). */
    public synchronized void unregister(Plugin owner) {
        if (contributions.remove(owner.getName()) != null) {
            plugin.getLogger().info("[Pack] " + owner.getName() + " ha tolto il proprio contenuto dal pacchetto.");
            if (started) rebuild();
        }
    }

    /** Prima costruzione: va chiamata quando TUTTI i plugin hanno gia' avuto modo di registrarsi
     *  (vedi {@code ServerLoadEvent} in {@link com.teolo.magixpack.MagixPack}). */
    public synchronized void start() {
        started = true;
        rebuild();
    }

    /** /mpack reload: rilegge la configurazione (porta, host, messaggi...) e ricostruisce subito
     *  il pacchetto con le registrazioni gia' in mano. Se il servizio non e' ancora partito non fa
     *  nulla: ci pensera' {@link #start()} al momento giusto. */
    public synchronized void reloadConfig() {
        if (started) rebuild();
    }

    /** Ricostruisce lo zip dalle registrazioni correnti e (ri)avvia il server HTTP. Non lancia
     *  mai: logga ed esce se qualcosa fallisce (il pacchetto resta semplicemente indisponibile,
     *  degradazione morbida coerente col resto del progetto). */
    private synchronized void rebuild() {
        port = plugin.getConfig().getInt("port", 8443);
        String host = plugin.getConfig().getString("public-host", "");
        if (host == null || host.isBlank()) {
            plugin.getLogger().warning("[Pack] public-host non configurato: pacchetto disabilitato.");
            stopHttp();
            packZip = null;
            return;
        }
        try {
            packZip = buildZip();
            sha1 = sha1(packZip);
            publicUrl = "http://" + host + ":" + port + "/pack.zip";
        } catch (Exception e) {
            plugin.getLogger().warning("[Pack] Impossibile costruire il pacchetto: " + e.getMessage());
            return;
        }
        stopHttp();
        if (startHttp()) startWatchdog();
    }

    // --------------------------------------------------------------------------------------------
    // Server HTTP
    // --------------------------------------------------------------------------------------------

    /**
     * Avvia (o RIavvia) la sola parte HTTP, con lo zip gia' costruito.
     *
     * <p><b>Perche' cosi'</b> — bug reale (agosto 2026, ereditato da MagixFactions prima
     * dell'estrazione di questo plugin): dopo qualche ora di uptime nessuno riusciva piu' a
     * scaricare il pacchetto (coda di connessioni piena sulla porta e thread bloccato in
     * lettura). Due cause sommate:
     * <ul>
     *   <li>con l'executor di default ogni richiesta veniva eseguita sullo STESSO thread
     *       dispatcher: bastava una connessione che non completava mai la richiesta per
     *       congelare i download di tutti fino al riavvio del server;</li>
     *   <li>i limiti di tempo del server HTTP della JDK sono disattivati di default: senza
     *       {@code maxReqTime}/{@code maxRspTime} quella lettura resta appesa per sempre.</li>
     * </ul>
     */
    private synchronized boolean startHttp() {
        // Vanno impostate PRIMA della prima create(): sun.net.httpserver.ServerConfig le legge una
        // volta sola, all'inizializzazione della classe.
        System.setProperty("sun.net.httpserver.maxReqTime", "30");   // s per ricevere la richiesta
        System.setProperty("sun.net.httpserver.maxRspTime", "120");  // s per consegnare la risposta
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(port), 50);
            s.createContext("/pack.zip", this::handle);
            AtomicInteger n = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "MagixPack-HTTP-" + n.incrementAndGet());
                t.setDaemon(true); // non deve mai trattenere lo spegnimento del server
                return t;
            });
            s.setExecutor(pool);
            s.start();
            server = s;
            httpPool = pool;
            plugin.getLogger().info("[Pack] Pacchetto servito su " + publicUrl + " (" + packZip.length + " byte).");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[Pack] Impossibile avviare il server del pacchetto: " + e.getMessage());
            server = null;
            httpPool = null;
            return false;
        }
    }

    private synchronized void stopHttp() {
        HttpServer s = server;
        server = null;
        if (s != null) {
            try { s.stop(0); } catch (Exception ignored) { /* in spegnimento non c'e' nulla di utile da fare */ }
        }
        ExecutorService pool = httpPool;
        httpPool = null;
        // shutdownNow interrompe anche gli scambi eventualmente bloccati: la lettura su SocketChannel
        // e' interrompibile, quindi un thread appeso muore invece di restare li' per sempre.
        if (pool != null) pool.shutdownNow();
    }

    public void stop() {
        if (watchdog != null) { watchdog.cancel(); watchdog = null; }
        stopHttp();
    }

    public boolean isAvailable() {
        return server != null && packZip != null;
    }

    // --------------------------------------------------------------------------------------------
    // Watchdog: il pacchetto deve restare scaricabile anche dopo giorni di uptime
    // --------------------------------------------------------------------------------------------

    private void startWatchdog() {
        if (watchdog != null) { watchdog.cancel(); watchdog = null; }
        int seconds = plugin.getConfig().getInt("watchdog-seconds", 120);
        if (seconds <= 0) return;
        long ticks = Math.max(20L, seconds * 20L);
        watchdog = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::healthCheck, ticks, ticks);
    }

    /** Eseguito ASINCRONO (fa I/O di rete): non tocca nulla dell'API di Bukkit. */
    private void healthCheck() {
        if (server == null) return;
        if (probeLocal()) return;
        plugin.getLogger().warning("[Pack] Il server HTTP del pacchetto non risponde piu' in locale: "
                + "riavvio automatico (senza, nessun giocatore riuscirebbe piu' a scaricare il pack).");
        stopHttp();
        if (startHttp()) plugin.getLogger().info("[Pack] Server HTTP del pacchetto riavviato correttamente.");
        else plugin.getLogger().severe("[Pack] Riavvio del server HTTP del pacchetto FALLITO: "
                + "il pacchetto non e' scaricabile finche' non si riavvia il server.");
    }

    /** Scarica il pack da 127.0.0.1 con timeout stretti: vero solo se arriva tutto e della misura giusta. */
    private boolean probeLocal() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/pack.zip").toURL().openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            int code = c.getResponseCode();
            try (InputStream in = c.getInputStream()) {
                return code == 200 && in.readAllBytes().length == packZip.length;
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // --------------------------------------------------------------------------------------------
    // Invio al giocatore
    // --------------------------------------------------------------------------------------------

    /**
     * Invia il resource pack al giocatore. Il flag "force" del pacchetto vale
     * {@link #isRequired()}: con pack OBBLIGATORIO il client vanilla non offre piu' il tasto "No"
     * (chi rifiuta comunque si scollega da solo). Il client e' pero' libero di ignorare quel flag
     * (client modificati, mod che disattivano i pack, download fallito): l'espulsione vera e propria
     * la applica {@link PackListener}, che e' la rete di sicurezza lato server.
     */
    public void sendTo(Player p) {
        if (!isAvailable()) return;
        String prompt = plugin.getConfig().getString("prompt",
                "&eQuesto server richiede il pacchetto risorse di MAGICADVENTURE.");
        p.setResourcePack(UUID.randomUUID(), publicUrl, sha1,
                com.teolo.magixpack.util.Colors.translate(prompt), isRequired());
    }

    /** true = il pacchetto e' OBBLIGATORIO: inviato a tutti al join e chi non lo carica viene
     *  espulso (vedi {@link PackListener}). false = facoltativo, nessuna espulsione. */
    public boolean isRequired() {
        return plugin.getConfig().getBoolean("required", true);
    }

    /** URL pubblico da cui i client scaricano lo zip (null finche' il servizio non e' partito): serve
     *  al listener per scrivere in console un errore diagnostico utile quando i download falliscono. */
    public String publicUrl() {
        return publicUrl;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, packZip.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(packZip);
            }
        } catch (Exception e) {
            // Client sparito a meta' download: e' normale, non deve sporcare la console ne' propagarsi.
        } finally {
            exchange.close(); // sempre: niente scambi (e socket) lasciati appesi
        }
    }

    // --------------------------------------------------------------------------------------------
    // Costruzione dello zip
    // --------------------------------------------------------------------------------------------

    /** Cartella (nella cartella DATI del plugin sul server, non nel jar) dove lo staff mette a mano
     *  file da includere o sostituire nel pacchetto, senza toccare codice ne' aspettare un deploy:
     *  vedi {@link #readOverrides()}. */
    static final String OVERRIDES_DIR = "overrides";

    /** File propri di MagixPack + il contenuto registrato da ogni plugin, in ordine di
     *  registrazione, + le sostituzioni manuali dello staff (vedi {@link #readOverrides()}, che
     *  VINCONO sempre su tutto il resto). Un percorso gia' scritto da un contributo precedente (fra
     *  due plugin, non contro un override manuale) viene scartato con un avviso: e' un conflitto
     *  vero, non qualcosa da risolvere a caso. */
    private byte[] buildZip() throws IOException {
        Map<String, byte[]> merged = new LinkedHashMap<>();
        for (String path : OWN_FILES) merged.put(path, ownResource(path));

        for (Map.Entry<String, Map<String, byte[]>> e : contributions.entrySet()) {
            for (Map.Entry<String, byte[]> f : e.getValue().entrySet()) {
                if (merged.containsKey(f.getKey())) {
                    plugin.getLogger().warning("[Pack] " + e.getKey() + " ha provato a registrare '"
                            + f.getKey() + "', gia' presente nel pacchetto: scartato.");
                    continue;
                }
                merged.put(f.getKey(), f.getValue());
            }
        }

        Map<String, byte[]> overrides = readOverrides();
        int replaced = 0;
        for (Map.Entry<String, byte[]> o : overrides.entrySet()) {
            if (merged.put(o.getKey(), o.getValue()) != null) replaced++;
        }
        if (!overrides.isEmpty()) {
            plugin.getLogger().info("[Pack] " + overrides.size() + " file da " + OVERRIDES_DIR + "/ inclusi"
                    + " nel pacchetto (" + replaced + " sostituiscono contenuto gia' presente).");
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, byte[]> e : merged.entrySet()) writeEntry(zip, e.getKey(), e.getValue());
        }
        return buffer.toByteArray();
    }

    private static void writeEntry(ZipOutputStream zip, String path, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(data);
        zip.closeEntry();
    }

    private byte[] ownResource(String path) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("resourcepack/" + path)) {
            if (in == null) throw new IOException("Risorsa mancante nel jar: resourcepack/" + path);
            return in.readAllBytes();
        }
    }

    /**
     * Legge {@code plugins/MagixPack/overrides/} sul server (creata vuota al primo avvio): ogni
     * file li' dentro entra nel pacchetto allo stesso percorso relativo a questa cartella (es.
     * {@code overrides/assets/minecraft/textures/gui/container/inventory.png} diventa
     * {@code assets/minecraft/textures/gui/container/inventory.png} nello zip), e VINCE su
     * qualunque contenuto gia' presente (file propri di MagixPack o registrato da un plugin) — e'
     * la via per personalizzare il pacchetto a mano, senza toccare codice ne' aspettare un deploy:
     * basta metterci un file e fare {@code /mpack reload} (stesso principio di Oraxen, che tiene le
     * proprie risorse fuori dal jar, nella cartella dati del plugin).
     */
    private Map<String, byte[]> readOverrides() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        java.io.File dir = new java.io.File(plugin.getDataFolder(), OVERRIDES_DIR);
        if (!dir.isDirectory()) return out;
        java.nio.file.Path root = dir.toPath();
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root)) {
            walk.filter(java.nio.file.Files::isRegularFile).forEach(p -> {
                try {
                    String rel = root.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                    out.put(rel, java.nio.file.Files.readAllBytes(p));
                } catch (IOException e) {
                    plugin.getLogger().warning("[Pack] " + OVERRIDES_DIR + "/" + p.getFileName()
                            + " illeggibile: " + e.getMessage());
                }
            });
        } catch (IOException e) {
            plugin.getLogger().warning("[Pack] Impossibile leggere " + OVERRIDES_DIR + "/: " + e.getMessage());
        }
        return out;
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e); // SHA-1 e' sempre disponibile in ogni JVM standard
        }
    }
}
