package com.teolo.magixproxy;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * config.yml and messages.yml of the proxy.
 *
 * Velocity has no Bukkit YamlConfiguration and no ConfigAlign: the files are copied from the
 * jar the first time, and every key missing on the server falls back to the jar's value (read
 * from the jar copy, never hardcoded here), so a new key works even before anyone adds it.
 */
public final class ProxyConfig {

    public final String dbHost;
    public final int dbPort;
    public final String dbName;
    public final String dbUser;
    public final String dbPassword;

    public final boolean skinFromMojang;
    public final int premiumTimeoutMillis;
    public final int skinCacheMinutes;

    public final String mainServer;
    public final boolean othersRequireLogin;
    public final boolean fallbackToMain;

    public final boolean motdEnabled;
    public final String motdFile;
    public final String motdDefaultIcon;

    private final Map<String, Object> messages;
    private final Map<String, Object> defaultMessages;

    private ProxyConfig(Map<String, Object> cfg, Map<String, Object> defCfg, Map<String, Object> db,
                        Map<String, Object> messages, Map<String, Object> defaultMessages) {
        this.dbHost = string(db, defCfg, "database.host");
        this.dbPort = number(db, defCfg, "database.port");
        this.dbName = string(db, defCfg, "database.name");
        this.dbUser = string(db, defCfg, "database.user");
        this.dbPassword = string(db, defCfg, "database.password");
        this.skinFromMojang = Boolean.parseBoolean(string(cfg, defCfg, "premium.skin_from_mojang"));
        this.premiumTimeoutMillis = number(cfg, defCfg, "premium.timeout_ms");
        this.skinCacheMinutes = number(cfg, defCfg, "premium.skin_cache_minutes");
        this.mainServer = string(cfg, defCfg, "network.main_server").trim();
        this.othersRequireLogin = Boolean.parseBoolean(string(cfg, defCfg, "network.others_require_login"));
        this.fallbackToMain = Boolean.parseBoolean(string(cfg, defCfg, "network.fallback_to_main"));
        this.motdEnabled = Boolean.parseBoolean(string(cfg, defCfg, "motd.enabled"));
        this.motdFile = string(cfg, defCfg, "motd.shared_with").trim();
        this.motdDefaultIcon = string(cfg, defCfg, "motd.default_icon").trim();
        this.messages = messages;
        this.defaultMessages = defaultMessages;
    }

    public static ProxyConfig load(Path dataDirectory, Logger log) throws IOException {
        Files.createDirectories(dataDirectory);
        Map<String, Object> cfg = read(copyDefault(dataDirectory, "config.yml", log));
        Map<String, Object> msg = read(copyDefault(dataDirectory, "messages.yml", log));
        Map<String, Object> def = readResource("config.yml");
        return new ProxyConfig(cfg, def, databaseSource(cfg, def, log), msg, readResource("messages.yml"));
    }

    /**
     * Where the database credentials come from: MagixAuth's config.yml on the same machine
     * (database.shared_with), so that the password lives in one file only; our own
     * database section when that file is not there.
     */
    private static Map<String, Object> databaseSource(Map<String, Object> cfg, Map<String, Object> def,
                                                      Logger log) {
        String shared = string(cfg, def, "database.shared_with").trim();
        if (shared.isEmpty()) {
            return cfg;
        }
        Path file = Path.of(shared).toAbsolutePath().normalize();
        if (!Files.isReadable(file)) {
            log.warn("MagixProxy: {} non leggibile: uso le credenziali scritte in config.yml.", file);
            return cfg;
        }
        try {
            Map<String, Object> other = read(file);
            if (!(lookup(other, "database") instanceof Map)) {
                log.warn("MagixProxy: {} non ha una sezione database: uso config.yml.", file);
                return cfg;
            }
            log.info("MagixProxy: credenziali del database lette da {}.", file);
            return other;
        } catch (IOException | RuntimeException e) {
            log.warn("MagixProxy: {} illeggibile ({}): uso config.yml.", file, e.toString());
            return cfg;
        }
    }

    /** A text a player reads, with & colours, from messages.yml. */
    public String message(String path) {
        Object v = lookup(messages, path);
        if (v == null) {
            v = lookup(defaultMessages, path);
        }
        return v == null ? path : v.toString();
    }

    private static Path copyDefault(Path dir, String name, Logger log) throws IOException {
        Path file = dir.resolve(name);
        if (!Files.exists(file)) {
            try (InputStream in = ProxyConfig.class.getResourceAsStream("/" + name)) {
                if (in == null) {
                    throw new IOException(name + " non trovato nel jar");
                }
                Files.copy(in, file);
            }
            log.info("MagixProxy: creato {} di serie.", name);
        }
        return file;
    }

    private static Map<String, Object> read(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> m = new Yaml().load(r);
            return m == null ? Map.of() : m;
        }
    }

    private static Map<String, Object> readResource(String name) throws IOException {
        try (InputStream in = ProxyConfig.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                return Map.of();
            }
            Map<String, Object> m = new Yaml().load(in);
            return m == null ? Map.of() : m;
        }
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

    private static String string(Map<String, Object> cfg, Map<String, Object> def, String path) {
        Object v = lookup(cfg, path);
        if (v == null) {
            v = lookup(def, path);
        }
        return v == null ? "" : v.toString();
    }

    private static int number(Map<String, Object> cfg, Map<String, Object> def, String path) {
        String s = string(cfg, def, path);
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return Integer.parseInt(String.valueOf(lookup(def, path)).trim());
        }
    }
}
