package com.teolo.magixlanguage.store;

import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * The language of every player, shared by the whole network (faction, hub, the Velocity proxy and
 * any game mode to come): one table in the site database, {@code language_players}. A language
 * chosen with /language set on the hub is the one the faction and the proxy use too.
 *
 * <p>The credentials are not copied anywhere: they are read from the config.yml of MagixAuth on
 * the same machine (the first readable file of {@code database.shared_with}), the same way
 * MagixProxy does. No file, no section, no answer from the database: every method answers
 * "nothing known" and the plugin goes on with its local players.yml, as before the network.</p>
 *
 * <p>No Bukkit and no Velocity here: the same class runs on both. The driver is MariaDB's,
 * shaded in the jar and used directly (no DriverManager, which does not see a plugin's
 * classes).</p>
 */
public final class LanguageStore {

    /** One row: language, how it was chosen ("geoip" or "manual"), country seen by GeoIP or null. */
    public record Row(String lang, String source, String country) {}

    private final String url;
    private final Properties credentials = new Properties();
    private final Logger log;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MagixLanguage-store");
        t.setDaemon(true);
        return t;
    });

    /** Last failure logged, so a database that is down does not fill the log at every login. */
    private volatile long lastWarning;

    private LanguageStore(String url, String user, String password, Logger log) {
        this.url = url;
        this.credentials.setProperty("user", user);
        this.credentials.setProperty("password", password);
        this.log = log;
    }

    /**
     * The store, with the credentials of the first readable file among {@code candidates} (paths
     * relative to the server folder), or null when none has a {@code database} section.
     */
    public static LanguageStore open(List<Path> candidates, Logger log) {
        for (Path file : candidates) {
            Path f = file.toAbsolutePath().normalize();
            if (!Files.isReadable(f)) {
                continue;
            }
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                Object root = new Yaml().load(r);
                Object db = root instanceof Map<?, ?> m ? m.get("database") : null;
                if (!(db instanceof Map<?, ?> d) || d.get("host") == null || d.get("name") == null) {
                    continue;
                }
                String url = "jdbc:mariadb://" + d.get("host") + ":" + (d.get("port") == null ? 3306 : d.get("port"))
                        + "/" + d.get("name") + "?connectTimeout=3000&socketTimeout=5000";
                LanguageStore store = new LanguageStore(url, String.valueOf(d.get("user")),
                        String.valueOf(d.get("password")), log);
                log.info("MagixLanguage: lingua dei giocatori condivisa nel database (credenziali da " + f + ").");
                return store;
            } catch (Exception e) {
                log.warning("MagixLanguage: " + f + " illeggibile (" + e + ").");
            }
        }
        log.warning("MagixLanguage: nessun file di database.shared_with leggibile: la lingua resta solo in "
                + "players.yml di questo server (non condivisa con gli altri).");
        return null;
    }

    private Connection connect() throws SQLException {
        Connection c = new org.mariadb.jdbc.Driver().connect(url, credentials);
        if (c == null) {
            throw new SQLException("indirizzo del database non valido");
        }
        return c;
    }

    /** Creates the table if it is not there yet. False if the database does not answer. */
    public boolean createTable() {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS language_players ("
                    + "uuid CHAR(36) NOT NULL PRIMARY KEY, "
                    + "lang VARCHAR(8) NOT NULL, "
                    + "source VARCHAR(8) NOT NULL, "
                    + "country VARCHAR(8) NULL, "
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                    + ") CHARACTER SET utf8mb4");
            return true;
        } catch (SQLException e) {
            warn("tabella language_players non creata", e);
            return false;
        }
    }

    /** The row of a player, or null if he has none (or the database does not answer). */
    public Row get(UUID playerId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT lang, source, country FROM language_players WHERE uuid = ?")) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Row(rs.getString(1), rs.getString(2), rs.getString(3)) : null;
            }
        } catch (SQLException e) {
            warn("lingua di " + playerId + " non letta", e);
            return null;
        }
    }

    /** Writes the row now (the caller is already off the main thread). */
    public void put(UUID playerId, Row row) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO language_players (uuid, lang, source, country) VALUES (?, ?, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE lang = VALUES(lang), source = VALUES(source), "
                             + "country = COALESCE(VALUES(country), country)")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, row.lang());
            ps.setString(3, row.source());
            ps.setString(4, row.country());
            ps.executeUpdate();
        } catch (SQLException e) {
            warn("lingua di " + playerId + " non salvata", e);
        }
    }

    /** Like {@link #put}, on the store's own thread: for callers on a game thread. */
    public void putLater(UUID playerId, Row row) {
        writer.execute(() -> put(playerId, row));
    }

    /**
     * Brings the languages a server knew before the network (its players.yml) into the table.
     * A row already there stays, except a GeoIP guess, which a manual choice replaces.
     */
    public int importAll(Map<UUID, Row> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO language_players (uuid, lang, source, country) VALUES (?, ?, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE "
                             + "lang = IF(source = 'geoip' AND VALUES(source) = 'manual', VALUES(lang), lang), "
                             + "source = IF(source = 'geoip' AND VALUES(source) = 'manual', 'manual', source)")) {
            for (Map.Entry<UUID, Row> e : rows.entrySet()) {
                ps.setString(1, e.getKey().toString());
                ps.setString(2, e.getValue().lang());
                ps.setString(3, e.getValue().source());
                ps.setString(4, e.getValue().country());
                ps.addBatch();
            }
            ps.executeBatch();
            return rows.size();
        } catch (SQLException e) {
            warn("players.yml non portato nel database", e);
            return 0;
        }
    }

    public void close() {
        writer.shutdown();
    }

    private void warn(String what, SQLException e) {
        long now = System.currentTimeMillis();
        if (now - lastWarning > 60_000) {
            lastWarning = now;
            log.warning("MagixLanguage: " + what + " (" + e.getMessage() + "): si usa players.yml di questo server.");
        }
    }
}
