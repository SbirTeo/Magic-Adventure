package com.teolo.magixfactions.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Gestione del database con backend intercambiabile: SQLite o MariaDB.
 */
public final class Database {

    public enum Type { SQLITE, MARIADB }

    /** Tabelle gestite (usate anche dalla migrazione generica). */
    public static final List<String> TABLES =
            List.of("players", "factions", "faction_members", "claims", "relations", "member_permissions",
                    "overclaim_timers", "faction_homes");

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
        if (s != null && (s.equalsIgnoreCase("mariadb") || s.equalsIgnoreCase("mysql"))) {
            return Type.MARIADB;
        }
        return Type.SQLITE;
    }

    public static HikariDataSource buildDataSource(Type type, ConfigurationSection storage, String dataFolderPath) {
        HikariConfig hc = new HikariConfig();
        if (type == Type.SQLITE) {
            String file = storage != null ? storage.getString("sqlite.file", "magixfactions.db") : "magixfactions.db";
            hc.setJdbcUrl("jdbc:sqlite:" + dataFolderPath + "/" + file);
            hc.setMaximumPoolSize(1);
            hc.setPoolName("MagixFactions-SQLite");
        } else {
            String host = storage != null ? storage.getString("mariadb.host", "127.0.0.1") : "127.0.0.1";
            int port = storage != null ? storage.getInt("mariadb.port", 3306) : 3306;
            String db = storage != null ? storage.getString("mariadb.database", "magixfactions") : "magixfactions";
            hc.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + db + "?createDatabaseIfNotExist=true");
            hc.setUsername(storage != null ? storage.getString("mariadb.user", "root") : "root");
            hc.setPassword(storage != null ? storage.getString("mariadb.password", "") : "");
            hc.setMaximumPoolSize(storage != null ? storage.getInt("mariadb.pool-size", 10) : 10);
            hc.setPoolName("MagixFactions-MariaDB");
        }
        hc.setConnectionTimeout(10000);
        return new HikariDataSource(hc);
    }

    public void createSchema() throws SQLException {
        try (Connection c = getConnection(); Statement st = c.createStatement()) {
            for (String ddl : ddl(type)) {
                st.execute(ddl);
            }
            // Migrazioni incrementali: la colonna si aggiunge solo se davvero non c'e'.
            aggiungiSeManca(c, st, "players", "map_rows", "INT DEFAULT 0");
            // Avanzamento verso il prossimo punto di Potenza, cosi' chi si scollega a meta' recupero non
            // ricomincia da capo (vedi PowerManager.tickOnline).
            aggiungiSeManca(c, st, "players", "power_progress", "INT DEFAULT 0");
            aggiungiSeManca(c, st, "factions", "bank", "DOUBLE DEFAULT 0");
            aggiungiSeManca(c, st, "claims", "paid", "DOUBLE DEFAULT 0");
        }
    }

    /**
     * Aggiunge una colonna a una tabella, ma solo se non c'e' gia'.
     *
     * Prima si tirava l'ALTER a occhi chiusi ignorando l'eccezione. Su SQLite passava in
     * silenzio; MariaDB invece risponde con un pacchetto d'errore (1060, "Duplicate column
     * name") che il driver stampa nel log del server, e a ogni avvio comparivano quattro WARN
     * che sembravano un guasto senza esserlo. Chiedere prima l'elenco delle colonne costa una
     * query per tabella, una volta sola all'avvio.
     */
    private void aggiungiSeManca(Connection c, Statement st, String tabella, String colonna, String tipo)
            throws SQLException {
        String cercata = colonna.toLowerCase(Locale.ROOT);
        if (colonne(c, tabella).contains(cercata)) return;
        try {
            st.execute("ALTER TABLE " + tabella + " ADD COLUMN " + colonna + " " + tipo);
        } catch (SQLException e) {
            // Se nonostante tutto la colonna adesso c'e' (metadati incompleti, o due avvii che si
            // sovrappongono), l'obiettivo e' raggiunto lo stesso: e' il solo caso che si ingoia.
            if (!colonne(c, tabella).contains(cercata)) throw e;
        }
    }

    /** I nomi delle colonne di una tabella, minuscoli. */
    private Set<String> colonne(Connection c, String tabella) throws SQLException {
        // Il catalogo restringe la ricerca al database in uso: senza, su MariaDB una tabella
        // omonima in un altro database dello stesso server verrebbe scambiata per la nostra.
        String catalogo = c.getCatalog();
        if (catalogo != null && catalogo.isEmpty()) catalogo = null;
        Set<String> out = new HashSet<>();
        try (ResultSet rs = c.getMetaData().getColumns(catalogo, null, tabella, null)) {
            while (rs.next()) {
                String nome = rs.getString("COLUMN_NAME");
                if (nome != null) out.add(nome.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    public static List<String> ddl(Type type) {
        String autoId = (type == Type.SQLITE)
                ? "INTEGER PRIMARY KEY AUTOINCREMENT"
                : "BIGINT PRIMARY KEY AUTO_INCREMENT";
        List<String> l = new ArrayList<>();
        l.add("CREATE TABLE IF NOT EXISTS players (" +
                "uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16), " +
                "power DOUBLE DEFAULT 0, max_power DOUBLE DEFAULT 10, last_seen BIGINT DEFAULT 0, " +
                "map_rows INT DEFAULT 0, power_progress INT DEFAULT 0)");
        // NB: sui database gia' esistenti resta la colonna 'minimap_on', non piu' usata da quando la
        // minimap HUD e' un PERMESSO (magixfactions.minimap) e non piu' un interruttore per-giocatore.
        // Non viene cancellata: una DROP COLUMN su dati altrui non ripaga il poco spazio che libera.
        l.add("CREATE TABLE IF NOT EXISTS factions (" +
                "id " + autoId + ", name VARCHAR(64), tag VARCHAR(16), description VARCHAR(255), " +
                "leader VARCHAR(36), power DOUBLE DEFAULT 0, created_at BIGINT DEFAULT 0, " +
                "member_limit_bonus BIGINT DEFAULT 0, bank DOUBLE DEFAULT 0)");
        l.add("CREATE TABLE IF NOT EXISTS faction_members (" +
                "uuid VARCHAR(36) PRIMARY KEY, faction_id BIGINT, rank VARCHAR(32), " +
                "rank_since BIGINT DEFAULT 0, joined_at BIGINT DEFAULT 0)");
        l.add("CREATE TABLE IF NOT EXISTS claims (" +
                "world VARCHAR(64), chunk_x INT, chunk_z INT, faction_id BIGINT, paid DOUBLE DEFAULT 0, " +
                "PRIMARY KEY(world, chunk_x, chunk_z))");
        l.add("CREATE TABLE IF NOT EXISTS relations (" +
                "faction_id BIGINT, other_id BIGINT, type VARCHAR(16), " +
                "PRIMARY KEY(faction_id, other_id))");
        l.add("CREATE TABLE IF NOT EXISTS member_permissions (" +
                "uuid VARCHAR(36), node VARCHAR(48), allowed INT DEFAULT 1, " +
                "PRIMARY KEY(uuid, node))");
        l.add("CREATE TABLE IF NOT EXISTS overclaim_timers (" +
                "faction_id BIGINT PRIMARY KEY, since BIGINT DEFAULT 0, last_loss BIGINT DEFAULT 0)");
        l.add("CREATE TABLE IF NOT EXISTS faction_homes (" +
                "faction_id BIGINT PRIMARY KEY, world VARCHAR(64), x DOUBLE, y DOUBLE, z DOUBLE, " +
                "yaw REAL, pitch REAL)");
        return l;
    }

    public long countRows(String table) throws SQLException {
        try (Connection c = getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
}
