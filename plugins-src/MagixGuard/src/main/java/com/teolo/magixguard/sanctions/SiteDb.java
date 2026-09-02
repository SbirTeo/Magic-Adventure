package com.teolo.magixguard.sanctions;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Il collegamento al database del SITO, dove vivono sanzioni, ricorsi e coda.
 *
 * <p>E' una connessione a parte rispetto a quella della profilazione, e non e' un dettaglio
 * tecnico: gli IP e le impronte dei client restano nel loro schema, dove il sito non arriva.
 * Se il sito venisse compromesso, quei dati non sarebbero raggiungibili.</p>
 */
public final class SiteDb {

    static {
        // Come in MagixWeb e MagixFactions: il driver va caricato nel classloader di QUESTO
        // plugin, altrimenti DriverManager puo' aver gia' costruito il suo elenco con quello
        // di un altro plugin e rispondere "No suitable driver".
        try { Class.forName("org.mariadb.jdbc.Driver"); } catch (Throwable ignored) { }
    }

    private final HikariDataSource ds;

    public SiteDb(SanctionsConfig cfg) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:mariadb://" + cfg.siteHost + ":" + cfg.sitePort + "/" + cfg.siteDatabase
                + "?useUnicode=true&characterEncoding=utf8");
        hc.setUsername(cfg.siteUser);
        hc.setPassword(cfg.sitePassword);
        hc.setMaximumPoolSize(cfg.sitePool);
        hc.setPoolName("MagixGuard-Sito");
        this.ds = new HikariDataSource(hc);
    }

    public Connection getConnection() throws SQLException {
        return ds.getConnection();
    }

    /** Una prova di connessione, per dirlo subito nel log invece che al primo ban. */
    public boolean raggiungibile() {
        try (Connection c = ds.getConnection()) {
            return c.isValid(3);
        } catch (SQLException e) {
            return false;
        }
    }

    public void close() {
        if (ds != null && !ds.isClosed()) {
            ds.close();
        }
    }
}
