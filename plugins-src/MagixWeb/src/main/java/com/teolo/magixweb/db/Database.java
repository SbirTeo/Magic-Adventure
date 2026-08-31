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
        // Forza il caricamento del driver nel classloader di QUESTO plugin: senza questo,
        // DriverManager puo' aver gia' inizializzato il suo elenco driver usando il classloader
        // di un altro plugin (es. MagixFactions, che shada la sua PROPRIA copia relocata di
        // org.mariadb sotto un package diverso) e non trovare il nostro driver ("No suitable driver").
        // Stesso pattern gia' usato in MagixFactions/db/Database.java.
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

        // Verifica connessione + crea la tabella se non esiste (idempotente, allineata allo schema del sito)
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            // `link_codes` non si crea piu': /link non esiste, l'account nasce in gioco con
            // MagixAuth e le stesse credenziali aprono il sito. La tabella resta nel database
            // finche' non la si cancella a mano, ma nessuno la scrive piu'.

            // Grado/prefisso LuckPerms di ogni giocatore, letto dal sito per mostrare il tag.
            // La collation e' fissata ESPLICITAMENTE a utf8mb4_unicode_ci per combaciare con
            // users.mc_uuid del sito: senza, MariaDB usa la sua default (uca1400_ai_ci) e il
            // JOIN tra le due tabelle fallisce con "Illegal mix of collations".
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
            // Tabella gia' esistente creata prima dei gradi multipli (prefissi impilati)
            st.execute("ALTER TABLE mc_ranks ADD COLUMN IF NOT EXISTS tags_json TEXT NULL AFTER tag_color");
            st.execute("ALTER TABLE mc_ranks ADD COLUMN IF NOT EXISTS name_color CHAR(7) NULL AFTER tags_json");
            st.execute("ALTER TABLE mc_ranks ADD COLUMN IF NOT EXISTS groups_json TEXT NULL AFTER name_color");
            // Prefisso COMPLETO di LuckPerms (codici colore inclusi): serve a rendere il grado
            // nei messaggi scritti dal sito, quando il giocatore non e' in partita e i
            // placeholder %luckperms_prefix% non si risolverebbero.
            st.execute("ALTER TABLE mc_ranks ADD COLUMN IF NOT EXISTS prefix_raw VARCHAR(255) NULL AFTER groups_json");
            // Primo accesso al server: lo mostra il profilo sul sito. Lo riempie RankSync,
            // al join e (per chi ha gia' giocato) con un recupero all'avvio.
            st.execute("ALTER TABLE mc_ranks ADD COLUMN IF NOT EXISTS first_join DATETIME NULL");

            // Elenco dei gruppi del gioco, specchiato per il pannello permessi del sito.
            st.execute("CREATE TABLE IF NOT EXISTS web_groups (" +
                    "name VARCHAR(64) NOT NULL PRIMARY KEY," +
                    "display VARCHAR(64) NOT NULL," +
                    "color CHAR(7) NULL," +
                    "weight INT NOT NULL DEFAULT 0," +
                    "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            // Chat live della home: 'game' = specchio della chat pubblica del server,
            // 'web' = scritto dal sito e in attesa di essere pubblicato in gioco (delivered=0).
            // COLLATE esplicito come sopra: mc_uuid viene joinata con mc_ranks/users.
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

            // Guida per amministratori: un capitolo per plugin, riscritto a ogni avvio.
            // La crea anche la migrazione del sito; averla qui vuol dire che il server puo'
            // pubblicare la guida senza aspettare che qualcuno lanci una migrazione a mano.
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
