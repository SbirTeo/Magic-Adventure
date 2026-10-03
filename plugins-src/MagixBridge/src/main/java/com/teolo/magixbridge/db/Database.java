package com.teolo.magixbridge.db;

import com.teolo.magixbridge.MagixBridge;
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

    public Database(MagixBridge plugin) {
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
        hc.setPoolName("MagixBridge-Pool");
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
            // The player's language (it/en/es/de), mirrored from MagixLanguage the same way as
            // the rank above: LanguageSync fills it on join and on every /language set.
            st.execute("ALTER TABLE mc_ranks ADD COLUMN IF NOT EXISTS language CHAR(2) NULL");
            // The colour of the player's VIP halo (MagixCosmetics) as #RRGGBB, NULL = no halo:
            // the site draws it over their face like the top supporter's crown. See HaloSync.
            st.execute("ALTER TABLE mc_ranks ADD COLUMN IF NOT EXISTS halo_color CHAR(7) NULL");

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
                    "server VARCHAR(32) NOT NULL DEFAULT 'faction'," +
                    "mc_uuid CHAR(36) NULL," +
                    "mc_username VARCHAR(32) NOT NULL," +
                    "message VARCHAR(256) NOT NULL," +
                    "delivered TINYINT(1) NOT NULL DEFAULT 0," +
                    "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "KEY idx_consegna (delivered, source, id)," +
                    "KEY idx_server_consegna (server, delivered, source, id)," +
                    "KEY idx_server_id (server, id)," +
                    "KEY idx_data (created_at)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            // One chat per server of the network (0.14.0): a table made before gets the column
            // here (the site's migration 2026-09-30-chat-per-server.sql does the same thing).
            st.execute("ALTER TABLE web_chat " +
                    "ADD COLUMN IF NOT EXISTS server VARCHAR(32) NOT NULL DEFAULT 'faction' AFTER source, " +
                    "ADD INDEX IF NOT EXISTS idx_server_consegna (server, delivered, source, id), " +
                    "ADD INDEX IF NOT EXISTS idx_server_id (server, id)");

            // Store delivery split by game mode (0.15.0): every queued command names the server
            // that runs it. The table is the site's (it writes the orders); it gets the column
            // here too, like web_chat above, so the delivery works before the site's migration
            // 2026-10-03-store-comandi-per-server.sql is run. Older rows were all the faction's.
            // Its own try: a database without the store yet must not stop the rest.
            try {
                st.execute("ALTER TABLE store_command_queue " +
                        "ADD COLUMN IF NOT EXISTS server VARCHAR(32) NOT NULL DEFAULT 'faction' AFTER mc_username, " +
                        "ADD INDEX IF NOT EXISTS idx_server_pending (server, executed_at, id)");
            } catch (SQLException e) {
                plugin.getLogger().warning("MagixBridge: coda dello store non aggiornata (" + e.getMessage() + ")");
            }

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

            // Cache delle traduzioni del SITO (testo di pagina, non catalogo di un plugin): il
            // sito accoda qui le frasi che incontra e non ha ancora, MagixBridge le smalta un tanto
            // alla volta chiedendole a MagixLanguage (vedi language/SiteTranslationWorker) e le
            // riscrive tradotte. phrase_hash e' lo sha1 del testo italiano, calcolato dal sito.
            st.execute("CREATE TABLE IF NOT EXISTS site_translations (" +
                    "lang CHAR(2) NOT NULL," +
                    "phrase_hash CHAR(40) NOT NULL," +
                    "source_text MEDIUMTEXT NOT NULL," +
                    "translated_text MEDIUMTEXT NULL," +
                    "status ENUM('pending','done','failed') NOT NULL DEFAULT 'pending'," +
                    "attempts INT NOT NULL DEFAULT 0," +
                    "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                    "PRIMARY KEY (lang, phrase_hash)," +
                    "KEY idx_stato (status, updated_at)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // The bridge between game modes (see bridge/PlaceholderBridge): who is online on which
            // server right now, and the placeholder values a server publishes for the others.
            st.execute("CREATE TABLE IF NOT EXISTS network_presence (" +
                    "mc_uuid CHAR(36) NOT NULL PRIMARY KEY," +
                    "server VARCHAR(32) NOT NULL," +
                    "seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "KEY idx_server_seen (server, seen_at)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            st.execute("CREATE TABLE IF NOT EXISTS network_placeholders (" +
                    "server VARCHAR(32) NOT NULL," +
                    "mc_uuid CHAR(36) NOT NULL DEFAULT ''," +
                    "placeholder VARCHAR(96) NOT NULL," +
                    "value TEXT NULL," +
                    "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                    "PRIMARY KEY (server, mc_uuid, placeholder)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // Voice (the site's voice chat): who is speaking in a proximity room right now (the
            // browser writes it through the site, with a short expiry; voice/SpeakingIndicator reads
            // it), and the voice-only mutes given from the admin panel (voice/VoiceModeration).
            st.execute("CREATE TABLE IF NOT EXISTS voice_speaking (" +
                    "mc_uuid CHAR(36) NOT NULL PRIMARY KEY," +
                    "room VARCHAR(64) NOT NULL," +
                    "until_at DATETIME(3) NOT NULL," +
                    "KEY idx_room_until (room, until_at)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            st.execute("CREATE TABLE IF NOT EXISTS voice_mutes (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "mc_uuid CHAR(36) NOT NULL," +
                    "mc_username VARCHAR(32) NOT NULL," +
                    "ends_at DATETIME NOT NULL," +
                    "staff VARCHAR(32) NOT NULL," +
                    "reason VARCHAR(200) NULL," +
                    "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "lifted_at DATETIME NULL," +
                    "lifted_by VARCHAR(32) NULL," +
                    "KEY idx_uuid_end (mc_uuid, ends_at)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            plugin.getLogger().info("MagixBridge: connesso a MariaDB (" + db + ").");
        } catch (SQLException e) {
            plugin.getLogger().severe("MagixBridge: impossibile connettersi al database! " + e.getMessage());
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
