package com.teolo.magixlanguage.velocity;

import com.google.inject.Inject;
import com.teolo.magixlanguage.store.LanguageStore;
import com.teolo.magixlanguage.translate.ProxyTranslationSync;
import com.teolo.magixlanguage.translate.PluginScope;
import com.teolo.magixlanguage.translate.TranslationPacing;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * MagixLanguage on the Velocity proxy: the same jar as on the game servers, with this entry point
 * (Velocity reads velocity-plugin.json, Paper reads plugin.yml; neither loads the other's class).
 *
 * <p>On the proxy it does what it does on every server: it translates the Magix plugins installed
 * there (MagixProxy) into {@code plugins/magixlanguage/translations/}, and it answers the other
 * proxy plugins with the text in the player's language. The language is the network one, from the
 * shared table (see {@link LanguageStore}): read at login, again at every server switch, and at most
 * every {@link #REFRESH_MILLIS} while a player stays on the same server. GeoIP detection and the
 * /language command stay on the game servers, where the player is first classified.</p>
 *
 * <p>For other proxy plugins (no compile dependency needed, they look the instance up through the
 * plugin manager): {@link #language(UUID)} and {@link #translate(String, UUID, String, Map)}.</p>
 */
@Plugin(
        id = "magixlanguage",
        name = "MagixLanguage",
        // Same as the pom (the annotation cannot read it): the other plugins compile against 0.4.14.
        version = "0.4.14",
        description = "La lingua di ogni giocatore e la traduzione dei messaggi dei plugin Magix, su tutta la rete",
        url = "https://magicadventure.it",
        authors = {"teolo"}
)
public final class MagixLanguageVelocity {

    /** How old a language read from the database may be before it is read again. */
    private static final long REFRESH_MILLIS = 30_000;

    private final ProxyServer proxy;
    private final Path dataDirectory;
    private final Logger log = Logger.getLogger("MagixLanguage");

    private Map<String, Object> config = Map.of();
    private Map<String, Object> defaults = Map.of();
    private LanguageStore store;
    private TranslationPacing pacing;

    private record Known(String lang, long readAt) {}

    private final Map<UUID, Known> languages = new ConcurrentHashMap<>();
    /** plugin -> lang -> key -> text; emptied after every sync. */
    private final Map<String, Map<String, Map<String, Object>>> catalogs = new ConcurrentHashMap<>();
    private final AtomicBoolean syncRunning = new AtomicBoolean();
    private volatile int lastMissing;

    @Inject
    public MagixLanguageVelocity(ProxyServer proxy, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("config.yml");
            if (!Files.exists(file)) {
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) Files.copy(in, file);
                }
            }
            defaults = readResource("/config.yml");
            config = readFile(file);
        } catch (Exception e) {
            log.warning("MagixLanguage: config.yml illeggibile (" + e + "): uso i valori di serie.");
        }
        pacing = new TranslationPacing(dataDirectory.toFile(), log);
        if (bool("database.enabled", true)) {
            store = LanguageStore.open(strings("database.shared_with").stream().map(Path::of).toList(), log);
        }
        if (store != null) {
            proxy.getScheduler().buildTask(this, () -> store.createTable()).schedule();
        }
        if (bool("translations.sync-on-start", true)) {
            proxy.getScheduler().buildTask(this, this::sync).delay(5, TimeUnit.SECONDS).schedule();
        }
        proxy.getScheduler().buildTask(this, this::resyncIfDue).delay(5, TimeUnit.MINUTES)
                .repeat(5, TimeUnit.MINUTES).schedule();
        log.info("MagixLanguage: attivo sul proxy (lingua di default " + defaultLanguage() + ", lingua "
                + (store != null ? "condivisa nel database" : "NON condivisa: database non disponibile") + ").");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (store != null) {
            store.close();
        }
    }

    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        return EventTask.async(() -> refresh(id));
    }

    @Subscribe
    public EventTask onServerPostConnect(ServerPostConnectEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        return EventTask.async(() -> refresh(id));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        languages.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ for the other proxy plugins

    /** The player's language: the network one, or default-language if he has none yet. */
    public String language(UUID playerId) {
        Known known = languages.get(playerId);
        if (known == null || System.currentTimeMillis() - known.readAt() > REFRESH_MILLIS) {
            proxy.getScheduler().buildTask(this, () -> refresh(playerId)).schedule();
        }
        return known != null && known.lang() != null ? known.lang() : defaultLanguage();
    }

    /**
     * The text of {@code key} of a proxy plugin's messages.yml in the player's language, with the
     * {name} placeholders replaced; null when there is no translation (the caller keeps its own
     * Italian text). Never throws.
     *
     * @param pluginName the plugin's data folder on the proxy, i.e. its id (e.g. "magixproxy")
     */
    public String translate(String pluginName, UUID playerId, String key, Map<String, String> placeholders) {
        try {
            if (playerId == null) {
                return null;
            }
            String lang = language(playerId);
            Object value = catalog(pluginName, lang).get(key);
            if (!(value instanceof String text)) {
                return null;
            }
            if (placeholders != null) {
                for (Map.Entry<String, String> e : placeholders.entrySet()) {
                    text = text.replace("{" + e.getKey() + "}", e.getValue());
                }
            }
            return text;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ internals

    private void refresh(UUID playerId) {
        if (store == null) {
            return;
        }
        LanguageStore.Row row = store.get(playerId);
        languages.put(playerId, new Known(row == null ? null : row.lang(), System.currentTimeMillis()));
    }

    private Map<String, Object> catalog(String pluginName, String lang) {
        return catalogs.computeIfAbsent(pluginName, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(lang, l -> ProxyTranslationSync.loadFlatCatalog(
                        dataDirectory.resolve("translations").resolve(pluginName).resolve(l + ".yml").toFile()));
    }

    private void resyncIfDue() {
        if (lastMissing > 0 && bool("translations.auto-translate.enabled", true)
                && pacing.nextPluginAttempt(number("translations.auto-translate.retry-interval-minutes", 180)) == null) {
            sync();
        }
    }

    private void sync() {
        if (!syncRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            List<String> installed = proxy.getPluginManager().getPlugins().stream()
                    .map(c -> c.getDescription().getId()).toList();
            List<String> plugins = PluginScope.toTranslate(installed, strings("translations.plugins"),
                    bool("translations.auto-discover", true), "magixlanguage");
            Path pluginsFolder = dataDirectory.toAbsolutePath().getParent();
            ProxyTranslationSync.Settings settings = new ProxyTranslationSync.Settings(
                    plugins, strings("translations.files"), strings("supported-languages"),
                    bool("translations.auto-translate.enabled", true),
                    number("translations.auto-translate.timeout-ms", 4000),
                    number("translations.auto-translate.delay-ms", 150),
                    string("translations.auto-translate.contact-email", ""),
                    Math.max(30, number("translations.auto-translate.retry-interval-minutes", 180)),
                    number("translations.auto-translate.pause-after-block-minutes", 120));
            lastMissing = new ProxyTranslationSync(pluginsFolder, dataDirectory, pacing, log).run(settings);
        } catch (RuntimeException e) {
            log.warning("MagixLanguage: sincronizzazione del proxy fallita (" + e + ").");
        } finally {
            catalogs.clear();
            syncRunning.set(false);
        }
    }

    private String defaultLanguage() {
        return string("default-language", "it");
    }

    // ------------------------------------------------------------------ config (no Bukkit here)

    private Object value(String path) {
        Object v = lookup(config, path);
        return v != null ? v : lookup(defaults, path);
    }

    private String string(String path, String fallback) {
        Object v = value(path);
        return v == null ? fallback : v.toString();
    }

    private boolean bool(String path, boolean fallback) {
        Object v = value(path);
        return v == null ? fallback : Boolean.parseBoolean(v.toString());
    }

    private int number(String path, int fallback) {
        Object v = value(path);
        try {
            return v == null ? fallback : Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private List<String> strings(String path) {
        Object v = value(path);
        return v instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Object lookup(Map<String, Object> root, String path) {
        Object node = root;
        for (String part : path.split("\\.")) {
            if (!(node instanceof Map)) {
                return null;
            }
            node = ((Map<String, Object>) node).get(part);
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readFile(Path file) throws Exception {
        try (var r = Files.newBufferedReader(file)) {
            Object root = new Yaml().load(r);
            return root instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readResource(String name) throws Exception {
        try (InputStream in = MagixLanguageVelocity.class.getResourceAsStream(name)) {
            if (in == null) return Map.of();
            Object root = new Yaml().load(in);
            return root instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }
    }
}
