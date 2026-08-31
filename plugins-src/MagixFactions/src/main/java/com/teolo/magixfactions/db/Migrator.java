package com.teolo.magixfactions.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Migrazione GENERICA dei dati da un database all'altro (SQLite <-> MariaDB).
 * Copia tabella per tabella leggendo le colonne a runtime: continua a funzionare
 * anche quando aggiungeremo nuove tabelle/colonne con le feature di gioco.
 */
public final class Migrator {

    private Migrator() {}

    /** Copia tutti i dati da {@code source} a {@code target}. Restituisce righe copiate per tabella. */
    public static Map<String, Integer> migrate(Database source, Database target, Logger log) throws SQLException {
        target.createSchema();
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (Connection src = source.getConnection();
             Connection dst = target.getConnection()) {
            dst.setAutoCommit(false);
            for (String table : Database.TABLES) {
                try (Statement del = dst.createStatement()) {
                    del.executeUpdate("DELETE FROM " + table);
                }
                int n = copyTable(src, dst, table);
                counts.put(table, n);
                if (log != null) log.info("Migrato " + table + ": " + n + " righe");
            }
            dst.commit();
        }
        return counts;
    }

    private static int copyTable(Connection src, Connection dst, String table) throws SQLException {
        try (Statement sel = src.createStatement();
             ResultSet rs = sel.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();

            StringBuilder names = new StringBuilder();
            StringBuilder placeholders = new StringBuilder();
            for (int i = 1; i <= cols; i++) {
                if (i > 1) { names.append(","); placeholders.append(","); }
                names.append(md.getColumnName(i));
                placeholders.append("?");
            }
            String sql = "INSERT INTO " + table + " (" + names + ") VALUES (" + placeholders + ")";

            int n = 0;
            try (PreparedStatement ps = dst.prepareStatement(sql)) {
                while (rs.next()) {
                    for (int i = 1; i <= cols; i++) {
                        ps.setObject(i, rs.getObject(i));
                    }
                    ps.addBatch();
                    if (++n % 500 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
            return n;
        }
    }
}
