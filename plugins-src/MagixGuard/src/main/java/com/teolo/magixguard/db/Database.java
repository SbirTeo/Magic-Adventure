package com.teolo.magixguard.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Database con backend intercambiabile SQLite/MariaDB (stessa impostazione di MagixFactions).
 *
 * Schema in breve:
 *   mg_players   - un record per nickname visto sul server
 *   mg_sessions  - una riga per ogni accesso, con tutti i segnali raccolti
 *   mg_evidence  - i singoli indizi fra due account (tipo + occorrenze + dettaglio)
 *   mg_links     - il punteggio complessivo di collegamento fra due account
 *   mg_alerts    - le segnalazioni generate
 *   mg_whitelist - coppie dichiarate legittime dallo staff (fratelli, coinquilini)
 *   mg_audit     - registro append-only firmato a catena: e' cio' che rende il dossier
 *                  difendibile in un ricorso (vedi /mg verify)
 */
public final class Database {

    public enum Type { SQLITE, MARIADB }

    public static final List<String> TABLES =
            List.of("mg_players", "mg_sessions", "mg_evidence", "mg_links",
                    "mg_alerts", "mg_whitelist", "mg_audit");

    static {
        try { Class.forName("org.sqlite.JDBC"); } catch (Throwable ignored) {}
        try { Class.forName("org.mariadb.jdbc.Driver"); } catch (Throwable ignored) {}
    }

    private final Type type;
    private final HikariDataSource dataSource;

    public Database(Type type, HikariDataSource dataSource) {
        this.type = type;
        this.dataSource = dataSource;
    }

    public Type getType() { return type; }

    public Connection getConnection() throws SQLException { return dataSource.getConnection(); }

    public void close() { if (dataSource != null && !dataSource.isClosed()) dataSource.close(); }

    public static Type parseType(String s) {
        if (s != null && (s.equalsIgnoreCase("mariadb") || s.equalsIgnoreCase("mysql"))) return Type.MARIADB;
        return Type.SQLITE;
    }

    public static HikariDataSource buildDataSource(Type type, ConfigurationSection storage, String dataFolderPath) {
        HikariConfig hc = new HikariConfig();
        if (type == Type.SQLITE) {
            String file = storage != null ? storage.getString("sqlite.file", "magixguard.db") : "magixguard.db";
            hc.setJdbcUrl("jdbc:sqlite:" + dataFolderPath + "/" + file);
            hc.setMaximumPoolSize(1);
            hc.setPoolName("MagixGuard-SQLite");
        } else {
            String host = storage != null ? storage.getString("mariadb.host", "127.0.0.1") : "127.0.0.1";
            int port = storage != null ? storage.getInt("mariadb.port", 3306) : 3306;
            String db = storage != null ? storage.getString("mariadb.database", "magixguard") : "magixguard";
            hc.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + db + "?createDatabaseIfNotExist=true");
            hc.setUsername(storage != null ? storage.getString("mariadb.user", "root") : "root");
            hc.setPassword(storage != null ? storage.getString("mariadb.password", "") : "");
            hc.setMaximumPoolSize(storage != null ? storage.getInt("mariadb.pool-size", 5) : 5);
            hc.setPoolName("MagixGuard-MariaDB");
        }
        hc.setConnectionTimeout(10000);
        return new HikariDataSource(hc);
    }

    public void createSchema() throws SQLException {
        try (Connection c = getConnection(); Statement st = c.createStatement()) {
            for (String ddl : ddl(type)) st.execute(ddl);
        }
    }

    private static String[] ddl(Type type) {
        boolean sqlite = type == Type.SQLITE;
        String id = sqlite ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT AUTO_INCREMENT PRIMARY KEY";
        return new String[]{
                // ---- anagrafica account ----
                "CREATE TABLE IF NOT EXISTS mg_players (" +
                        "id " + id + ", " +
                        "uuid VARCHAR(36) NOT NULL UNIQUE, " +
                        "name VARCHAR(16) NOT NULL, " +
                        "name_lower VARCHAR(16) NOT NULL, " +
                        "first_seen BIGINT NOT NULL, " +
                        "last_seen BIGINT NOT NULL, " +
                        "session_count INT NOT NULL DEFAULT 0, " +
                        "playtime_seconds BIGINT NOT NULL DEFAULT 0, " +
                        "exempt INT NOT NULL DEFAULT 0)",
                "CREATE INDEX IF NOT EXISTS idx_mg_players_name ON mg_players(name_lower)",

                // ---- una riga per accesso, con tutti i segnali ----
                // ip in chiaro solo fino alla scadenza della retention; ip_hash (HMAC col pepper)
                // sopravvive alla anonimizzazione e permette i confronti anche dopo.
                "CREATE TABLE IF NOT EXISTS mg_sessions (" +
                        "id " + id + ", " +
                        "player_id BIGINT NOT NULL, " +
                        "join_at BIGINT NOT NULL, " +
                        "quit_at BIGINT, " +
                        "ip VARCHAR(45), " +
                        "ip_hash VARCHAR(64), " +
                        "subnet VARCHAR(64), " +
                        "subnet_hash VARCHAR(64), " +
                        "rdns VARCHAR(255), " +
                        "rdns_hash VARCHAR(64), " +
                        "hostname VARCHAR(255), " +
                        "brand VARCHAR(64), " +
                        "channels TEXT, " +
                        "channels_hash VARCHAR(64), " +
                        "locale VARCHAR(16), " +
                        "view_distance INT, " +
                        "skin_parts INT, " +
                        "main_hand VARCHAR(8), " +
                        "chat_flags VARCHAR(32), " +
                        "ping_median INT, " +
                        "pack_status VARCHAR(24), " +
                        "pack_ms INT, " +
                        "cookie_token VARCHAR(64), " +
                        "fingerprint VARCHAR(64))",
                "CREATE INDEX IF NOT EXISTS idx_mg_sessions_player ON mg_sessions(player_id)",
                "CREATE INDEX IF NOT EXISTS idx_mg_sessions_ip ON mg_sessions(ip_hash)",
                "CREATE INDEX IF NOT EXISTS idx_mg_sessions_subnet ON mg_sessions(subnet_hash)",
                "CREATE INDEX IF NOT EXISTS idx_mg_sessions_rdns ON mg_sessions(rdns_hash)",
                "CREATE INDEX IF NOT EXISTS idx_mg_sessions_cookie ON mg_sessions(cookie_token)",
                "CREATE INDEX IF NOT EXISTS idx_mg_sessions_fp ON mg_sessions(fingerprint)",
                "CREATE INDEX IF NOT EXISTS idx_mg_sessions_join ON mg_sessions(join_at)",

                // ---- indizi fra due account (a_id < b_id sempre) ----
                "CREATE TABLE IF NOT EXISTS mg_evidence (" +
                        "id " + id + ", " +
                        "a_id BIGINT NOT NULL, " +
                        "b_id BIGINT NOT NULL, " +
                        "type VARCHAR(32) NOT NULL, " +
                        "occurrences INT NOT NULL DEFAULT 1, " +
                        "first_seen BIGINT NOT NULL, " +
                        "last_seen BIGINT NOT NULL, " +
                        "detail TEXT)",
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_mg_evidence_pair ON mg_evidence(a_id, b_id, type)",
                "CREATE INDEX IF NOT EXISTS idx_mg_evidence_a ON mg_evidence(a_id)",
                "CREATE INDEX IF NOT EXISTS idx_mg_evidence_b ON mg_evidence(b_id)",

                // ---- punteggio complessivo della coppia (manual=1: forzato dallo staff) ----
                "CREATE TABLE IF NOT EXISTS mg_links (" +
                        "id " + id + ", " +
                        "a_id BIGINT NOT NULL, " +
                        "b_id BIGINT NOT NULL, " +
                        "score DOUBLE NOT NULL DEFAULT 0, " +
                        "updated_at BIGINT NOT NULL, " +
                        "alerted_at BIGINT, " +
                        "manual INT NOT NULL DEFAULT 0)",
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_mg_links_pair ON mg_links(a_id, b_id)",
                "CREATE INDEX IF NOT EXISTS idx_mg_links_score ON mg_links(score)",

                // ---- segnalazioni ----
                "CREATE TABLE IF NOT EXISTS mg_alerts (" +
                        "id " + id + ", " +
                        "a_id BIGINT NOT NULL, " +
                        "b_id BIGINT NOT NULL, " +
                        "score DOUBLE NOT NULL, " +
                        "created_at BIGINT NOT NULL, " +
                        "status VARCHAR(16) NOT NULL DEFAULT 'NUOVO', " +
                        "notes TEXT)",
                "CREATE INDEX IF NOT EXISTS idx_mg_alerts_created ON mg_alerts(created_at)",

                // ---- coppie dichiarate legittime dallo staff ----
                "CREATE TABLE IF NOT EXISTS mg_whitelist (" +
                        "id " + id + ", " +
                        "a_id BIGINT NOT NULL, " +
                        "b_id BIGINT NOT NULL, " +
                        "staff VARCHAR(64) NOT NULL, " +
                        "reason TEXT, " +
                        "created_at BIGINT NOT NULL)",
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_mg_whitelist_pair ON mg_whitelist(a_id, b_id)",

                // ---- registro append-only firmato a catena ----
                "CREATE TABLE IF NOT EXISTS mg_audit (" +
                        "id " + id + ", " +
                        "ts BIGINT NOT NULL, " +
                        "actor VARCHAR(64) NOT NULL, " +
                        "action VARCHAR(48) NOT NULL, " +
                        "subject VARCHAR(128), " +
                        "detail TEXT, " +
                        "prev_hash VARCHAR(64), " +
                        "hash VARCHAR(64) NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_mg_audit_ts ON mg_audit(ts)"
        };
    }
}
