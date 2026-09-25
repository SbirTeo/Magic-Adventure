package com.teolo.magixlanguage.geo;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Risale al paese di un indirizzo IP interrogando un servizio GeoIP esterno (di serie
 * ip-api.com, gratuito e senza chiave), con una cache su disco per non richiedere lo stesso IP
 * ad ogni ingresso.
 *
 * <p>Come {@link com.teolo.magixlanguage.util.ConfigAlign}, non lancia mai: un servizio esterno
 * che non risponde non deve mai impedire a qualcuno di entrare, ne' rallentarlo oltre il timeout
 * configurato. In quel caso {@link #countryOf} restituisce {@code null}, e chi chiama ricade sulla
 * lingua di default.</p>
 */
public final class GeoLookup {

    /** Un IP privato/locale non ha un paese: non ha senso nemmeno provare a chiederlo. */
    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([a-zA-Z]+)\"");
    private static final Pattern COUNTRY_CODE = Pattern.compile("\"countryCode\"\\s*:\\s*\"([A-Za-z]{2})\"");

    private final Logger log;
    private final HttpClient client;
    private final String providerUrlTemplate;
    private final int timeoutMillis;
    private final long cacheMillis;
    private final Path cacheFile;

    private record Entry(String countryCode, long expiresAt) {
        boolean valid() {
            return System.currentTimeMillis() < expiresAt;
        }
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Object cacheLock = new Object();
    private volatile boolean dirty = false;

    public GeoLookup(Logger log, String providerUrlTemplate, int timeoutMillis, int cacheDays, Path cacheFile) {
        this.log = log;
        this.providerUrlTemplate = providerUrlTemplate;
        this.timeoutMillis = Math.max(200, timeoutMillis);
        this.cacheMillis = Math.max(1, cacheDays) * 24L * 60 * 60 * 1000;
        this.cacheFile = cacheFile;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMillis))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        loadFromDisk();
    }

    /**
     * Il codice paese ISO 3166-1 alpha-2 (es. "IT") di questo indirizzo, o {@code null} se
     * l'indirizzo e' privato/locale, il servizio non ha risposto in tempo, o non sa dare
     * una risposta. Va chiamato da un thread che puo' bloccarsi (mai dal thread principale del
     * server): tipicamente {@code AsyncPlayerPreLoginEvent}, che gira gia' fuori da li'.
     */
    public String countryOf(InetAddress address) {
        if (address == null) {
            return null;
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isSiteLocalAddress()) {
            // Un IP privato (127.0.0.1, 192.168.x.x, un tunnel di sviluppo...) non ha un paese:
            // chiederlo al servizio esterno sprecherebbe solo una richiesta e darebbe "fail".
            return null;
        }
        String ip = address.getHostAddress();
        Entry cached = cache.get(ip);
        if (cached != null && cached.valid()) {
            return cached.countryCode();
        }

        String url = providerUrlTemplate.replace("{ip}", ip);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("User-Agent", "MagixLanguage (magicadventure.it)")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warning("MagixLanguage: GeoIP ha risposto " + response.statusCode() + " per " + ip + ".");
                return null;
            }
            String body = response.body();
            Matcher status = STATUS.matcher(body);
            if (!status.find() || !"success".equalsIgnoreCase(status.group(1))) {
                // "fail" e' una risposta vera (IP riservato, bogon...): si tiene a mente per non
                // richiederla di continuo, ma senza un paese da restituire.
                remember(ip, null);
                return null;
            }
            Matcher country = COUNTRY_CODE.matcher(body);
            String code = country.find() ? country.group(1).toUpperCase(Locale.ROOT) : null;
            remember(ip, code);
            return code;
        } catch (Exception e) {
            log.warning("MagixLanguage: GeoIP non raggiungibile per " + ip + " (" + e + "). "
                    + "Uso la lingua di default per questo ingresso.");
            return null;
        }
    }

    private void remember(String ip, String countryCode) {
        cache.put(ip, new Entry(countryCode, System.currentTimeMillis() + cacheMillis));
        dirty = true;
        saveToDisk();
    }

    /** Carica la cache dal disco all'avvio: formato semplice, una riga per IP. */
    private void loadFromDisk() {
        if (cacheFile == null || !Files.isReadable(cacheFile)) {
            return;
        }
        int loaded = 0;
        try {
            for (String line : Files.readAllLines(cacheFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                // formato: ip<TAB>countryCode(o vuoto se "fail")<TAB>scadenza-epoch-millis
                String[] parts = line.split("\t", -1);
                if (parts.length < 3 || parts[0].isBlank()) continue;
                long expiresAt;
                try {
                    expiresAt = Long.parseLong(parts[2]);
                } catch (NumberFormatException nfe) {
                    continue;
                }
                if (expiresAt <= System.currentTimeMillis()) continue;
                cache.put(parts[0], new Entry(parts[1].isEmpty() ? null : parts[1], expiresAt));
                loaded++;
            }
            if (loaded > 0) {
                log.info("MagixLanguage: " + loaded + " indirizzi ricaricati dalla cache GeoIP.");
            }
        } catch (IOException e) {
            log.warning("MagixLanguage: impossibile leggere la cache GeoIP (" + e + "). Si riparte vuota.");
        }
    }

    /** Riscrittura atomica, come MojangLookup: mai un file a meta' se il server viene fermato mentre scrive. */
    private void saveToDisk() {
        if (cacheFile == null || !dirty) {
            return;
        }
        synchronized (cacheLock) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Entry> e : cache.entrySet()) {
                Entry entry = e.getValue();
                if (!entry.valid()) continue;
                sb.append(e.getKey()).append('\t')
                        .append(entry.countryCode() == null ? "" : entry.countryCode()).append('\t')
                        .append(entry.expiresAt()).append('\n');
            }
            try {
                Path dir = cacheFile.getParent();
                if (dir != null) Files.createDirectories(dir);
                Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
                Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException noAtomic) {
                    Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
                }
                dirty = false;
            } catch (IOException e) {
                log.warning("MagixLanguage: impossibile salvare la cache GeoIP (" + e + ").");
            }
        }
    }
}
