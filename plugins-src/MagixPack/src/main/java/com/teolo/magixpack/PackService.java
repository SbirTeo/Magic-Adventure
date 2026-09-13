package com.teolo.magixpack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Il motore del resource pack di MagixPack: costruisce UN pacchetto (dai file base + da quelli
 * depositati dai plugin "fornitori"), lo serve via un piccolo server HTTP interno
 * ({@code com.sun.net.httpserver}, gia' nella JDK) e lo tiene scaricabile con un watchdog.
 *
 * <p><b>Fornitori (approccio A).</b> MagixPack non conosce shader, logo o pergamena: sono i singoli
 * plugin a produrre i loro asset (gia' con le sostituzioni fatte, es. la dimensione della minimap
 * nello shader) e a depositarli sotto {@code plugins/MagixPack/contrib/<plugin>/<path-nel-pack>}.
 * Al momento della build, MagixPack parte dai file base (in {@code basepack/} nel jar, oggi solo
 * {@code pack.mcmeta}) e vi aggiunge tutto quello che trova sotto {@code contrib/}. Trasporto via
 * file: nessun accoppiamento a tempo di compilazione fra plugin buildati separatamente, e regge
 * anche gli asset "dinamici".
 *
 * <p>La logica HTTP (executor dedicato, timeout, watchdog di riavvio) e' la stessa gia' collaudata
 * nel vecchio servizio di MagixFactions: un pacchetto che smette di essere scaricabile chiuderebbe
 * fuori l'intero server, quindi si mantiene la stessa robustezza.
 */
public final class PackService {

    private final JavaPlugin plugin;
    private final Path contribDir;

    private volatile HttpServer server;
    private volatile ExecutorService httpPool;
    private BukkitTask watchdog;
    private int port;
    private byte[] packZip;
    private byte[] sha1;
    private String publicUrl;

    public PackService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.contribDir = plugin.getDataFolder().toPath().resolve("contrib");
    }

    /** La cartella in cui i plugin fornitori depositano i loro asset. */
    public Path contribDir() {
        return contribDir;
    }

    /** Costruisce lo zip e avvia il server HTTP. Non lancia mai: logga ed esce se qualcosa fallisce
     *  (coerente con la degradazione morbida — meglio "niente pack" che un crash all'avvio). */
    public void start() {
        port = plugin.getConfig().getInt("pack.port", 8443);
        String host = plugin.getConfig().getString("pack.public-host", "");
        if (host == null || host.isBlank()) {
            plugin.getLogger().warning("[Pack] pack.public-host non configurato: resource pack disabilitato.");
            return;
        }
        try {
            packZip = buildZip();
            sha1 = sha1(packZip);
            publicUrl = "http://" + host + ":" + port + "/pack.zip";
        } catch (Exception e) {
            plugin.getLogger().warning("[Pack] Impossibile costruire il resource pack: " + e.getMessage());
            return;
        }
        if (startHttp()) startWatchdog();
    }

    /** Ricostruisce lo zip (rileggendo i file dei fornitori) e, se il server e' su, aggiorna cio' che
     *  serve. Torna true se la ricostruzione e' riuscita. Da chiamare dopo che i fornitori hanno
     *  depositato i loro asset, o su richiesta ({@code /magixpack rebuild}). */
    public synchronized boolean rebuild() {
        try {
            byte[] zip = buildZip();
            packZip = zip;
            sha1 = sha1(zip);
            plugin.getLogger().info("[Pack] Pacchetto ricostruito (" + zip.length + " byte).");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[Pack] Ricostruzione del pacchetto fallita: " + e.getMessage());
            return false;
        }
    }

    private synchronized boolean startHttp() {
        // Vanno impostate PRIMA della prima create(): sun.net.httpserver.ServerConfig le legge una
        // volta sola. Senza, una lettura appesa (scanner di porte, client sparito) puo' congelare i
        // download di tutti fino al riavvio del server.
        System.setProperty("sun.net.httpserver.maxReqTime", "30");
        System.setProperty("sun.net.httpserver.maxRspTime", "120");
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(port), 50);
            s.createContext("/pack.zip", this::handle);
            AtomicInteger n = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "MagixPack-HTTP-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
            s.setExecutor(pool);
            s.start();
            server = s;
            httpPool = pool;
            plugin.getLogger().info("[Pack] Resource pack servito su " + publicUrl + " (" + packZip.length + " byte).");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[Pack] Impossibile avviare il server del resource pack: " + e.getMessage());
            server = null;
            httpPool = null;
            return false;
        }
    }

    private synchronized void stopHttp() {
        HttpServer s = server;
        server = null;
        if (s != null) {
            try { s.stop(0); } catch (Exception ignored) { }
        }
        ExecutorService pool = httpPool;
        httpPool = null;
        if (pool != null) pool.shutdownNow();
    }

    public void stop() {
        if (watchdog != null) { watchdog.cancel(); watchdog = null; }
        stopHttp();
    }

    public boolean isAvailable() {
        return server != null && packZip != null;
    }

    public boolean isRequired() {
        return plugin.getConfig().getBoolean("pack.required", true);
    }

    public String publicUrl() {
        return publicUrl;
    }

    /** Invia il resource pack al giocatore, con il flag "force" pari a {@link #isRequired()}. */
    public void sendTo(Player p) {
        if (!isAvailable()) return;
        String prompt = plugin.getConfig().getString("pack.prompt",
                "&eQuesto server richiede il pacchetto risorse di MAGICADVENTURE.");
        Component promptComp = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(prompt.replace("\\n", "\n"));
        p.setResourcePack(UUID.randomUUID(), publicUrl, sha1, promptComp, isRequired());
    }

    // --- watchdog -------------------------------------------------------------------------------

    private void startWatchdog() {
        int seconds = plugin.getConfig().getInt("pack.watchdog-seconds", 120);
        if (seconds <= 0) return;
        long ticks = Math.max(20L, seconds * 20L);
        watchdog = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::healthCheck, ticks, ticks);
    }

    private void healthCheck() {
        if (server == null) return;
        if (probeLocal()) return;
        plugin.getLogger().warning("[Pack] Il server HTTP del pacchetto non risponde piu' in locale: riavvio automatico.");
        stopHttp();
        if (startHttp()) plugin.getLogger().info("[Pack] Server HTTP del pacchetto riavviato correttamente.");
        else plugin.getLogger().severe("[Pack] Riavvio del server HTTP del pacchetto FALLITO.");
    }

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

    // --- HTTP + build ---------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try {
            byte[] body = packZip; // riferimento stabile: rebuild() puo' sostituirlo mentre serviamo
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        } catch (Exception e) {
            // Client sparito a meta' download: normale, non deve sporcare la console.
        } finally {
            exchange.close();
        }
    }

    /** Costruisce lo zip: prima i file base ({@code basepack/} nel jar), poi tutto cio' che i
     *  fornitori hanno depositato sotto {@code contrib/}. In caso di percorsi in conflitto vince
     *  l'ultimo (con un avviso in console). {@code pack.mcmeta} resta quello base. */
    private byte[] buildZip() throws IOException {
        List<String> written = new ArrayList<>();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            // 1) file base dal jar (oggi solo pack.mcmeta)
            addResource(zip, "basepack/pack.mcmeta", "pack.mcmeta", written);

            // 2) contributi dei plugin fornitori
            if (Files.isDirectory(contribDir)) {
                for (Path pluginDir : listDirs(contribDir)) {
                    Path root = pluginDir; // contrib/<plugin>/...
                    try (Stream<Path> walk = Files.walk(root)) {
                        List<Path> files = walk.filter(Files::isRegularFile).toList();
                        for (Path f : files) {
                            String entry = root.relativize(f).toString().replace('\\', '/');
                            if (entry.equals("pack.mcmeta")) continue; // il pack.mcmeta e' di MagixPack
                            if (written.contains(entry)) {
                                plugin.getLogger().warning("[Pack] Percorso in conflitto (vince l'ultimo): " + entry);
                            }
                            zip.putNextEntry(new ZipEntry(entry));
                            zip.write(Files.readAllBytes(f));
                            zip.closeEntry();
                            written.add(entry);
                        }
                    }
                }
            }
        }
        return buffer.toByteArray();
    }

    private void addResource(ZipOutputStream zip, String jarPath, String entry, List<String> written) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(jarPath)) {
            if (in == null) throw new IOException("Risorsa base mancante nel jar: " + jarPath);
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(in.readAllBytes());
            zip.closeEntry();
            written.add(entry);
        }
    }

    private static List<Path> listDirs(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory).sorted().toList();
        }
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
