package com.teolo.magixproxy.db;

import com.teolo.magixproxy.ProxyConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

/**
 * The connection to the site database, the same one MagixAuth uses (table `users`).
 * Same pool settings as MagixAuth: few quick connections, and a fast failure rather than a
 * player stuck at the door waiting for a database that is not answering.
 *
 * <p>The pool rebuilds itself when it is stuck empty: on 2 October 2026 it went to zero
 * connections and never refilled for almost four hours (the database was fine, LuckPerms in the
 * same proxy kept working), and every login was refused until Velocity was restarted.
 */
public final class Database {

    /** Not more than one rebuild every so often: a database really down is not fixed by it. */
    private static final long REBUILD_EVERY_MS = 30_000;

    static {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (Throwable ignored) {
            // The driver is inside the jar: if it is missing, the first connection says so.
        }
    }

    private final ProxyConfig config;
    private final Logger log;
    private volatile HikariDataSource dataSource;
    private long lastRebuild;

    public Database(ProxyConfig config, Logger log) {
        this.config = config;
        this.log = log;
        this.dataSource = build();
    }

    private HikariDataSource build() {
        HikariConfig hc = new HikariConfig();
        // Timeouts on the driver too: without socketTimeout a connection that the database
        // accepts but never answers blocks the pool's only "add connection" thread forever.
        hc.setJdbcUrl("jdbc:mariadb://" + config.dbHost + ":" + config.dbPort + "/" + config.dbName
                + "?connectTimeout=5000&socketTimeout=15000");
        hc.setUsername(config.dbUser);
        hc.setPassword(config.dbPassword);
        hc.setPoolName("MagixProxy");
        hc.setMaximumPoolSize(4);
        hc.setMinimumIdle(1);
        hc.setConnectionTimeout(5000);
        hc.setValidationTimeout(2000);
        // Idle connections are tested every two minutes, so a dead one is replaced before a
        // player needs it.
        hc.setKeepaliveTime(120_000);
        // The pool must be able to start while the database is down (it is retried at every
        // login), instead of throwing and taking the plugin down with it.
        hc.setInitializationFailTimeout(-1);
        return new HikariDataSource(hc);
    }

    public Connection getConnection() throws SQLException {
        HikariDataSource ds = dataSource;
        try {
            return ds.getConnection();
        } catch (SQLTransientConnectionException e) {
            if (!rebuildIfStuck(ds, e)) {
                throw e;
            }
            return dataSource.getConnection();
        }
    }

    /** A pool with no connections at all after a timeout is replaced by a new one. */
    private synchronized boolean rebuildIfStuck(HikariDataSource stuck, SQLException e) {
        if (stuck != dataSource) {
            return true; // another thread has just rebuilt it: try the new one
        }
        HikariPoolMXBean pool = stuck.getHikariPoolMXBean();
        if (pool == null || pool.getTotalConnections() > 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastRebuild < REBUILD_EVERY_MS) {
            return false;
        }
        lastRebuild = now;
        log.warn("MagixProxy: il collegamento al database è rimasto senza connessioni ({}): lo ricreo.",
                describe(e));
        dataSource = build();
        // Closing can wait for a stuck thread of the old pool: never on the login path.
        Thread closer = new Thread(stuck::close, "MagixProxy-pool-close");
        closer.setDaemon(true);
        closer.start();
        return true;
    }

    /** The message with its causes: the pool's timeout hides the real reason in the cause. */
    public static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder(String.valueOf(e.getMessage()));
        Throwable cause = e.getCause();
        for (int i = 0; cause != null && cause != e && i < 4; i++, cause = cause.getCause()) {
            sb.append(" <- ").append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
        }
        return sb.toString();
    }

    public boolean reachable() {
        try (Connection c = getConnection()) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    public void close() {
        HikariDataSource ds = dataSource;
        if (!ds.isClosed()) {
            ds.close();
        }
    }
}
