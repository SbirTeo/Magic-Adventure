package com.teolo.magixweb.db;

import com.teolo.magixweb.MagixWeb;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {

    private final HikariDataSource ds;

    static {
        // Force the driver into THIS plugin's classloader. Without it, DriverManager may have
        // already built its driver list from another plugin's classloader (MagixFactions shades
        // its OWN relocated copy of org.mariadb under a different package) and then fails to see
        // ours with "No suitable driver". Same pattern as MagixFactions/db/Database.java.
        try { Class.forName("org.mariadb.jdbc.Driver"); } catch (Throwable ignored) {}
    }

    public Database(MagixWeb plugin) {
        FileConfiguration cfg = plugin.getConfig();
        String host = cfg.getString("mariadb.host", "127.0.0.1");
        int port = cfg.getInt("mariadb.port", 3306);
        String db = cfg.getString("mariadb.database", "magicadventure_web");
        String user = cfg.getString("mariadb.user", "magicweb");
        String pass = cfg.getString("mariadb.password", "");

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + db + "?useUnicode=true&characterEncoding=utf8");
        hc.setUsername(user);
        hc.setPassword(pass);
        hc.setMaximumPoolSize(3);
        hc.setPoolName("MagixWeb-Pool");
        this.ds = new HikariDataSource(hc);

        // Check the connection and create the tables if missing (idempotent, matching the site schema)
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            // `link_codes` is no longer created: /link is gone, the account is born in game
            // through MagixAuth, and the same credentials open the site. The table stays in the
            // database until someone drops it by hand, but nothing writes to it any more.

            // Each player's LuckPerms group and prefix, read by the site to show their tag.
            // The collation is pinned EXPLICITLY to utf8mb4_unicode_ci to match the site's
            // users.mc_uuid: without it MariaDB picks its own default (uca1400_ai_ci) and the
            // join between the two tables dies with "Illegal mix of collations".
            st.execute("CREATE TABLE IF NOT EXISTS mc_ranks (" +
                    "mc_uuid CHAR(36) NOT NULL PRIMARY KEY," +
                    "mc_username VARCHAR(32) NOT NULL," +
                    "group_name VARCHAR(64) NOT NULL," +
                    "group_display VARCHAR(64) NOT NULL," +
                    "tag_text VARCHAR(64) NULL," +
                    "tag_color CHAR(7) NULL," +
                    "tags_json TEXT NULL," +
                    "name_color CHAR(7) NULL," +
                    "weight INT NOT NULL DEFAULT 0," +
                    "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            // For tables created before stacked prefixes (multiple groups) existed
            st.execute("ALTER TABLE mc_ranks ADD COLUMN IF NOT EXISTS tags_json TEXT NULL AFTER tag_color");
            st.execute("ALTER TABLE mc_ranks ADD COLUMN IF NOT EXISTS name_color CHAR(7) NULL AFTER tags_json");
            st.execute("ALTER TABLE mc_ranks ADD COLUMN IF NOT EXISTS groups_json TEXT NULL AFTER name_color");
            // The COMPLETE LuckPerms prefix, colour codes included: the site needs it to render
            // someone's rank inside messages it writes itself, when the player is offline and
            // the %luckperms_prefix% placeholder would resolve to nothing.
            st.execute("ALTER TABLE mc_ranks ADD COLUMN IF NOT EXISTS prefix_raw VARCHAR(255) NULL AFTER groups_json");
            // First time they joined the server, shown on their profile page. RankSync fills it
            // on join, and backfills it at startup for players who were already around.
            st.execute("ALTER TABLE mc_ranks ADD COLUMN IF NOT EXISTS first_join DATETIME NULL");

            // The game's groups, mirrored for the site's permissions panel.
            st.execute("CREATE TABLE IF NOT EXISTS web_groups (" +
                    "name VARCHAR(64) NOT NULL PRIMARY KEY," +
                    "display VARCHAR(64) NOT NULL," +
                    "color CHAR(7) NULL," +
                    "weight INT NOT NULL DEFAULT 0," +
                    "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            // The live chat on the home page: 'game' mirrors the server's public chat, 'web' is
            // written on the site and waiting to be spoken in game (delivered = 0).
            // Explicit COLLATE as above: mc_uuid is joined against mc_ranks and users.
            st.execute("CREATE TABLE IF NOT EXISTS web_chat (" +
                    "id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY," +
                    "source ENUM('web','game') NOT NULL DEFAULT 'web'," +
                    "mc_uuid CHAR(36) NULL," +
                    "mc_username VARCHAR(32) NOT NULL," +
                    "message VARCHAR(256) NOT NULL," +
                    "delivered TINYINT(1) NOT NULL DEFAULT 0," +
                    "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "KEY idx_consegna (delivered, source, id)," +
                    "KEY idx_data (created_at)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // The administrators' guide: one chapter per plugin, rewritten at every startup.
            // The site's migration creates it too; having it here means the server can publish
            // the guide without waiting for someone to run a migration by hand.
            st.execute("CREATE TABLE IF NOT EXISTS guide_staff (" +
                    "plugin VARCHAR(64) NOT NULL PRIMARY KEY," +
                    "title VARCHAR(160) NOT NULL," +
                    "version VARCHAR(32) NOT NULL DEFAULT ''," +
                    "sort_order INT NOT NULL DEFAULT 100," +
                    "body_html MEDIUMTEXT NOT NULL," +
                    "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            plugin.getLogger().info("MagixWeb: connesso a MariaDB (" + db + ").");
        } catch (SQLException e) {
            plugin.getLogger().severe("MagixWeb: impossibile connettersi al database! " + e.getMessage());
        }
    }

    public Connection getConnection() throws SQLException {
        return ds.getConnection();
    }

    public void close() {
        if (ds != null && !ds.isClosed()) {
            ds.close();
        }
    }
}
