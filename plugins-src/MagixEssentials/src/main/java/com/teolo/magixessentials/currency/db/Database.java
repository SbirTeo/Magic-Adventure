package com.teolo.magixessentials.currency.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Il database condiviso delle valute di RETE ("shared: true" in currencies.yml): solo MariaDB,
 * mai SQLite — il server sta dietro Velocity con piu' backend (hub, factions...), e un file
 * locale non lo vedrebbero entrambi. Stessa forma del Database di MagixAuth (un backend solo,
 * niente switch sqlite/mariadb come in MagixFactions/MagixGuard, che invece possono restare
 * locali a un server solo).
 */
public final class Database {

    static {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (Throwable ignored) {
            // Il driver e' dentro al jar (shaded): se manca qui, il fallimento arriva alla prima
            // connessione con un messaggio piu' utile di questo.
        }
    }

    /** Tabella delle valute condivise: un saldo per valuta e per giocatore. */
    private static final String TABLE = "me_currency_balances";

    private final HikariDataSource dataSource;

    public Database(ConfigurationSection conf) {
        String host = conf.getString("host", "127.0.0.1");
        int port = conf.getInt("port", 3306);
        String name = conf.getString("name", "magicadventure_essentials");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + name + "?createDatabaseIfNotExist=true");
        hc.setUsername(conf.getString("user", "root"));
        hc.setPassword(conf.getString("password", ""));
        hc.setPoolName("MagixEssentials-Currency");
        hc.setMaximumPoolSize(Math.max(1, conf.getInt("pool-size", 3)));
        hc.setConnectionTimeout(5000);
        this.dataSource = new HikariDataSource(hc);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /** Crea la tabella se manca: niente da preparare a mano prima di accendere il modulo. */
    public void createSchema() throws SQLException {
        try (Connection c = getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "currency_id VARCHAR(32) NOT NULL, "
                    + "player_uuid VARCHAR(36) NOT NULL, "
                    + "balance BIGINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (currency_id, player_uuid))");
        }
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
