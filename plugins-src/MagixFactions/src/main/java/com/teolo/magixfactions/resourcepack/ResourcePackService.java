package com.teolo.magixfactions.resourcepack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.Bukkit;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Costruisce (dalle risorse incluse nel jar, cartella {@code resourcepack/}) e serve via un piccolo
 * server HTTP integrato ({@code com.sun.net.httpserver.HttpServer}, gia' nella JDK: nessuna dipendenza
 * nuova) il resource pack necessario allo shader della minimap HUD. I client Minecraft scaricano
 * resource pack solo via URL HTTP/HTTPS raggiungibile — da qui il bisogno di questo piccolo server e
 * della porta dedicata sul VPS (aperta con conferma esplicita dell'utente, vedi config
 * {@code minimap.resourcepack.port}).
 *
 * <p>Con {@code map.minimap.resourcepack.required: true} (default) il pack e' OBBLIGATORIO: viene
 * inviato a ogni giocatore al join e chi non lo carica viene espulso — vedi {@link ResourcePackListener}.
 *
 * <p>Fase 0/1 del piano: al momento il pack contiene solo uno shader "passthrough" (copia identica
 * dell'originale vanilla di questa versione, {@code item.vsh}), per verificare che l'intera pipeline
 * (build zip → hash → download client → accettazione) funzioni PRIMA di aggiungere qualunque logica
 * di marcatore/riposizionamento — un bug in uno shader "core" e' condiviso da ogni entita' con quel
 * render-type per ogni giocatore col pacchetto, quindi si procede a piccoli passi verificati.
 */
public final class ResourcePackService {

    /** File da includere nello zip, path relativo alla cartella "resourcepack/" nel classpath del jar.
     *  Da Paper/Minecraft 26.2 (2026-07-23) i due file NON si chiamano piu' "rendertype_text.vsh/.fsh":
     *  Mojang li ha accorpati in un unico "text.vsh/.fsh" condiviso da testo-mondo/GUI/see-through
     *  (selezionati da #if IS_GUI/IS_SEE_THROUGH dentro il file) — verificato scaricando il client jar
     *  vanilla reale ed estraendone gli shader core. Vedi la nota in cima a text.vsh/text.fsh. I vecchi
     *  file (validi solo fino a 26.1.2) restano nel sorgente come *.legacy-26.1.2, non zippati. */
    private static final String[] BUNDLED_FILES = {
            "pack.mcmeta",
            "assets/minecraft/shaders/core/text.vsh",
            "assets/minecraft/shaders/core/text.fsh",

            // --- Logo del server nel tablist (font default esteso) ---
            //
            // Il logo e' iniettato come glifo bitmap (carattere PUA ) DENTRO il font
            // default di Minecraft: cosi' funziona anche nelle stringhe "legacy" con &-codes
            // del tablist di CMI, che non possono specificare un font component. Il provider
            // "reference" verso include/default lascia intatti tutti i glifi vanilla.
            // ascent/height sono placeholder sostituiti da config (tablist.logo.*) per poter
            // calibrare posizione e dimensione con un semplice riavvio, senza ricompilare.
            "assets/minecraft/font/default.json",
            "assets/magicadventure/textures/gui/logo.png",

            // --- MagixAuth: schermate di accesso e tasti del tastierino ---
            //
            // Stanno QUI e non in un pacchetto separato perche' il client ne applica uno
            // solo: mandandone due, uno dei due veniva scaricato e poi scartato in silenzio,
            // e il giocatore si trovava davanti una schermata fatta di caratteri che il suo
            // client non conosceva. Un pacchetto solo significa anche un download solo.
            //
            // Il namespace resta "magixauth": i file sono suoi, e il giorno che quel plugin
            // non ci fosse piu' basta togliere queste righe.
            "assets/minecraft/lang/it_it.json",
            "assets/magixauth/font/gui.json",
            "assets/magixauth/textures/gui/login.png",
            "assets/magixauth/textures/gui/codice.png",
            "assets/magixauth/items/vuoto.json",
            "assets/magixauth/models/item/vuoto.json",
            "assets/magixauth/textures/item/vuoto.png",
            "assets/magixauth/items/tasto_0.json",
            "assets/magixauth/models/item/tasto_0.json",
            "assets/magixauth/textures/item/tasto_0.png",
            "assets/magixauth/items/tasto_1.json",
            "assets/magixauth/models/item/tasto_1.json",
            "assets/magixauth/textures/item/tasto_1.png",
            "assets/magixauth/items/tasto_2.json",
            "assets/magixauth/models/item/tasto_2.json",
            "assets/magixauth/textures/item/tasto_2.png",
            "assets/magixauth/items/tasto_3.json",
            "assets/magixauth/models/item/tasto_3.json",
            "assets/magixauth/textures/item/tasto_3.png",
            "assets/magixauth/items/tasto_4.json",
            "assets/magixauth/models/item/tasto_4.json",
            "assets/magixauth/textures/item/tasto_4.png",
            "assets/magixauth/items/tasto_5.json",
            "assets/magixauth/models/item/tasto_5.json",
            "assets/magixauth/textures/item/tasto_5.png",
            "assets/magixauth/items/tasto_6.json",
            "assets/magixauth/models/item/tasto_6.json",
            "assets/magixauth/textures/item/tasto_6.png",
            "assets/magixauth/items/tasto_7.json",
            "assets/magixauth/models/item/tasto_7.json",
            "assets/magixauth/textures/item/tasto_7.png",
            "assets/magixauth/items/tasto_8.json",
            "assets/magixauth/models/item/tasto_8.json",
            "assets/magixauth/textures/item/tasto_8.png",
            "assets/magixauth/items/tasto_9.json",
            "assets/magixauth/models/item/tasto_9.json",
            "assets/magixauth/textures/item/tasto_9.png",
            "assets/magixauth/items/tasto_cancella.json",
            "assets/magixauth/models/item/tasto_cancella.json",
            "assets/magixauth/textures/item/tasto_cancella.png",
            "assets/magixauth/items/tasto_conferma.json",
            "assets/magixauth/models/item/tasto_conferma.json",
            "assets/magixauth/textures/item/tasto_conferma.png",
            "assets/magixauth/items/display.json",
            "assets/magixauth/models/item/display.json",
            "assets/magixauth/textures/item/display.png",
    };

    private final JavaPlugin plugin;
    private volatile HttpServer server;
    private volatile ExecutorService httpPool;
    private BukkitTask watchdog;
    private int port;
    private byte[] packZip;
    private byte[] sha1;
    private String publicUrl;

    public ResourcePackService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Costruisce lo zip in memoria e avvia il server HTTP. Non lancia mai: logga ed esce se qualcosa fallisce
     *  (la minimap resta semplicemente indisponibile, coerente con la degradazione morbida gia' usata altrove). */
    public void start() {
        port = plugin.getConfig().getInt("map.minimap.resourcepack.port", 8443);
        String host = plugin.getConfig().getString("map.minimap.resourcepack.public-host", "");
        if (host == null || host.isBlank()) {
            plugin.getLogger().warning("[Minimap] minimap.resourcepack.public-host non configurato: resource pack disabilitato.");
            return;
        }
        try {
            packZip = buildZip();
            sha1 = sha1(packZip);
            publicUrl = "http://" + host + ":" + port + "/pack.zip";
        } catch (Exception e) {
            plugin.getLogger().warning("[Minimap] Impossibile costruire il resource pack: " + e.getMessage());
            return;
        }
        if (startHttp()) startWatchdog();
    }

    /**
     * Avvia (o RIavvia) la sola parte HTTP, con lo zip gia' costruito. E' separata da {@link #start()}
     * perche' il watchdog la richiama quando il servizio smette di rispondere.
     *
     * <p><b>Perche' cosi'</b> — bug reale (agosto 2026): dopo qualche ora di uptime nessuno riusciva
     * piu' a scaricare il pacchetto (coda di connessioni piena sulla porta e thread
     * {@code HTTP-Dispatcher} fermo in lettura). Due cause sommate:
     * <ul>
     *   <li>con l'executor di default ({@code setExecutor(null)}) ogni richiesta veniva eseguita
     *       <i>sullo stesso thread dispatcher</i>: bastava UNA connessione che non completava mai la
     *       richiesta (scanner di porte su una porta esposta, client sparito senza chiudere) per
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
                Thread t = new Thread(r, "MagixFactions-Pack-HTTP-" + n.incrementAndGet());
                t.setDaemon(true); // non deve mai trattenere lo spegnimento del server
                return t;
            });
            s.setExecutor(pool);
            s.start();
            server = s;
            httpPool = pool;
            plugin.getLogger().info("[Minimap] Resource pack servito su " + publicUrl + " (" + packZip.length + " byte).");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[Minimap] Impossibile avviare il server del resource pack: " + e.getMessage());
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

    /** Controllo periodico in loopback: se il servizio non risponde piu', viene riavviato da solo. */
    private void startWatchdog() {
        int seconds = plugin.getConfig().getInt("map.minimap.resourcepack.watchdog-seconds", 120);
        if (seconds <= 0) return;
        long ticks = Math.max(20L, seconds * 20L);
        watchdog = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::healthCheck, ticks, ticks);
    }

    /** Eseguito ASINCRONO (fa I/O di rete): non tocca nulla dell'API di Bukkit. */
    private void healthCheck() {
        if (server == null) return;
        if (probeLocal()) return;
        plugin.getLogger().warning("[ResourcePack] Il server HTTP del pacchetto non risponde piu' in locale: "
                + "riavvio automatico (senza, nessun giocatore riuscirebbe piu' a scaricare il pack).");
        stopHttp();
        if (startHttp()) plugin.getLogger().info("[ResourcePack] Server HTTP del pacchetto riavviato correttamente.");
        else plugin.getLogger().severe("[ResourcePack] Riavvio del server HTTP del pacchetto FALLITO: "
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

    /**
     * Invia il resource pack al giocatore. Il flag "force" del pacchetto vale
     * {@link #isRequired()}: con pack OBBLIGATORIO il client vanilla non offre piu' il tasto "No"
     * (chi rifiuta comunque si scollega da solo). Il client e' pero' libero di ignorare quel flag
     * (client modificati, mod che disattivano i pack, download fallito): l'espulsione vera e propria
     * la applica {@link ResourcePackListener}, che e' la rete di sicurezza lato server.
     */
    public void sendTo(Player p) {
        if (!isAvailable()) return;
        String prompt = plugin.getConfig().getString("map.minimap.resourcepack.prompt",
                "&eQuesto server richiede il pacchetto risorse di MAGICADVENTURE.");
        p.setResourcePack(UUID.randomUUID(), publicUrl, sha1,
                com.teolo.magixfactions.util.Colors.translate(prompt.replace("\\n", "\n")),
                isRequired());
    }

    /** true = il pacchetto e' OBBLIGATORIO: inviato a tutti al join e chi non lo carica viene espulso
     *  (vedi {@link ResourcePackListener}). false = comportamento storico, facoltativo e inviato solo
     *  a chi ha la minimap HUD abilitata. */
    public boolean isRequired() {
        return plugin.getConfig().getBoolean("map.minimap.resourcepack.required", true);
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

    private byte[] buildZip() throws IOException {
        // Dimensione minimap a schermo (frazione, es. 0.22) da config: sostituita nel vertex shader al
        // posto del placeholder __MAP_SIZE__. Clampata in un intervallo ragionevole per non generare uno
        // shader assurdo (0 = invisibile, valori enormi = riempie lo schermo).
        double size = plugin.getConfig().getDouble("map.minimap.screen-size", 0.22);
        size = Math.max(0.05, Math.min(0.6, size));
        String sizeStr = String.format(java.util.Locale.ROOT, "%.4f", size);
        // Forma minimap: quadrata o rotonda (default rotonda) -> #define SQUARE nel fragment shader.
        boolean square = "square".equalsIgnoreCase(plugin.getConfig().getString("map.minimap.shape", "round"));
        String squareStr = square ? "1" : "0";
        // Cornice minimap: spessore (px) e colore, sostituiti nel fragment shader (BORDER_WIDTH / BORDER_INNER).
        double borderSize = plugin.getConfig().getDouble("map.minimap.border-size", 3.0);
        borderSize = Math.max(0.0, Math.min(10.0, borderSize));
        String borderWidthStr = String.format(java.util.Locale.ROOT, "%.1f", borderSize);
        // Levigatura smart dei bordi (Scale2x): attiva solo con stile "magix" (config map.style);
        // con "vanilla" i pixel restano nudi come sulla mappa vanilla pura.
        boolean smooth = !"vanilla".equalsIgnoreCase(plugin.getConfig().getString("map.style", "magix"));
        String smoothStr = smooth ? "1" : "0";
        float[] bc = parseBorderColor(plugin.getConfig().getString("map.minimap.border-color", "#5A5A5A"));
        String brStr = String.format(java.util.Locale.ROOT, "%.4f", bc[0]);
        String bgStr = String.format(java.util.Locale.ROOT, "%.4f", bc[1]);
        String bbStr = String.format(java.util.Locale.ROOT, "%.4f", bc[2]);

        // Logo nel tablist: altezza del glifo (px renderizzati) e "ascent" (quanto la texture sale
        // sopra la linea di base). Clampati in un intervallo sano cosi' un valore sbagliato nel config
        // non genera un font.json rifiutato dal client. Vedi assets/minecraft/font/default.json.
        int logoHeight = plugin.getConfig().getInt("tablist.logo.height", 40);
        logoHeight = Math.max(8, Math.min(256, logoHeight));
        int logoAscent = plugin.getConfig().getInt("tablist.logo.ascent", 22);
        // Regola di Minecraft: ascent non puo' superare height, altrimenti il pacchetto e' invalido.
        logoAscent = Math.max(-128, Math.min(logoHeight, logoAscent));
        String logoHeightStr = String.valueOf(logoHeight);
        String logoAscentStr = String.valueOf(logoAscent);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (String path : BUNDLED_FILES) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("resourcepack/" + path)) {
                    if (in == null) throw new IOException("Risorsa mancante nel jar: resourcepack/" + path);
                    byte[] data = in.readAllBytes();
                    if (path.endsWith(".vsh")) { // il vertex shader ha il placeholder della dimensione
                        data = new String(data, java.nio.charset.StandardCharsets.UTF_8)
                                .replace("__MAP_SIZE__", sizeStr)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    } else if (path.endsWith(".fsh")) { // il fragment shader ha i placeholder di forma e cornice
                        data = new String(data, java.nio.charset.StandardCharsets.UTF_8)
                                .replace("__SQUARE__", squareStr)
                                .replace("__SMOOTH__", smoothStr)
                                .replace("__BORDER_WIDTH__", borderWidthStr)
                                .replace("__BORDER_R__", brStr)
                                .replace("__BORDER_G__", bgStr)
                                .replace("__BORDER_B__", bbStr)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    } else if (path.endsWith("font/default.json")) { // il font del logo ha i placeholder di dimensione/posizione
                        data = new String(data, java.nio.charset.StandardCharsets.UTF_8)
                                .replace("__LOGO_ASCENT__", logoAscentStr)
                                .replace("__LOGO_HEIGHT__", logoHeightStr)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    }
                    zip.putNextEntry(new ZipEntry(path));
                    zip.write(data);
                    zip.closeEntry();
                }
            }
        }
        return buffer.toByteArray();
    }

    /** Colore cornice minimap -> {r,g,b} normalizzati 0..1 per lo shader. Accetta "#RRGGBB"/"RRGGBB" oppure
     *  un codice colore Minecraft "&X" (i 16 classici). Fallback: grigio (90,90,90) come il default storico. */
    private static float[] parseBorderColor(String s) {
        int r = 90, g = 90, b = 90;
        if (s != null && !s.isBlank()) {
            String t = s.trim();
            if (t.startsWith("#")) t = t.substring(1);
            if (t.length() == 6 && t.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                r = Integer.parseInt(t.substring(0, 2), 16);
                g = Integer.parseInt(t.substring(2, 4), 16);
                b = Integer.parseInt(t.substring(4, 6), 16);
            } else if (t.length() >= 2 && (t.charAt(0) == '&' || t.charAt(0) == '§')) {
                int[] c = mcCodeColor(Character.toLowerCase(t.charAt(1)));
                r = c[0]; g = c[1]; b = c[2];
            }
        }
        return new float[]{ r / 255f, g / 255f, b / 255f };
    }

    private static int[] mcCodeColor(char c) {
        return switch (c) {
            case '0' -> new int[]{0, 0, 0};        case '1' -> new int[]{0, 0, 170};
            case '2' -> new int[]{0, 170, 0};      case '3' -> new int[]{0, 170, 170};
            case '4' -> new int[]{170, 0, 0};      case '5' -> new int[]{170, 0, 170};
            case '6' -> new int[]{255, 170, 0};    case '7' -> new int[]{170, 170, 170};
            case '8' -> new int[]{85, 85, 85};     case '9' -> new int[]{85, 85, 255};
            case 'a' -> new int[]{85, 255, 85};    case 'b' -> new int[]{85, 255, 255};
            case 'c' -> new int[]{255, 85, 85};    case 'd' -> new int[]{255, 85, 255};
            case 'e' -> new int[]{255, 255, 85};   case 'f' -> new int[]{255, 255, 255};
            default  -> new int[]{90, 90, 90};
        };
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e); // SHA-1 e' sempre disponibile in ogni JVM standard
        }
    }
}
