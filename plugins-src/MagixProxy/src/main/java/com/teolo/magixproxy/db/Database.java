package com.teolo.magixproxy.db;

import com.teolo.magixproxy.ProxyConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The connection to the site database, the same one MagixAuth uses (table `users`).
 * Same pool settings as MagixAuth: few quick connections, and a fast failure rather than a
 * player stuck at the door waiting for a database that is not answering.
 */
public final class Database {

    static {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (Throwable ignored) {
            // The driver is inside the jar: if it is missing, the first connection says so.
        }
    }

    private final HikariDataSource dataSource;

    public Database(ProxyConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:mariadb://" + config.dbHost + ":" + config.dbPort + "/" + config.dbName);
        hc.setUsername(config.dbUser);
        hc.setPassword(config.dbPassword);
        hc.setPoolName("MagixProxy");
        hc.setMaximumPoolSize(4);
        hc.setMinimumIdle(1);
        hc.setConnectionTimeout(5000);
        hc.setValidationTimeout(2000);
        // The pool must be able to start while the database is down (it is retried at every
        // login), instead of throwing and taking the plugin down with it.
        hc.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(hc);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public boolean reachable() {
        try (Connection c = getConnection()) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    public void close() {
        if (!dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
