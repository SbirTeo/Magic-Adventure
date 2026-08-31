package com.teolo.magixauth.db;

import com.teolo.magixauth.AuthConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * La connessione al database del sito.
 *
 * Qui non c'e' scelta fra SQLite e MariaDB come negli altri plugin, e non e' una svista:
 * MagixAuth non ha un database suo. Legge e scrive la tabella `users` di magicadventure.it,
 * perche' l'account di gioco e quello del sito sono lo stesso account. Un archivio locale
 * separato vorrebbe dire due password da tenere allineate a mano, cioe' esattamente il
 * problema che questo plugin esiste per non avere.
 */
public final class Database {

    static {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (Throwable ignored) {
            // Il driver e' dentro al jar: se manca qui, il fallimento arriva alla prima
            // connessione con un messaggio piu' utile di questo.
        }
    }

    private final HikariDataSource dataSource;

    public Database(AuthConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:mariadb://" + config.dbHost + ":" + config.dbPorta + "/" + config.dbNome);
        hc.setUsername(config.dbUtente);
        hc.setPassword(config.dbPassword);
        hc.setPoolName("MagixAuth");
        // Poche connessioni ma reattive: le richieste sono brevi (una lettura al pre-login,
        // una scrittura al login) e concentrate nei momenti in cui la gente entra.
        hc.setMaximumPoolSize(6);
        hc.setMinimumIdle(2);
        // Meglio fallire in fretta che tenere un giocatore fermo davanti a un cartello: se
        // il database non risponde entro questo tempo, il gate decide senza di lui.
        hc.setConnectionTimeout(5000);
        hc.setValidationTimeout(2000);
        this.dataSource = new HikariDataSource(hc);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /** Il database risponde? Serve all'avvio, per non scoprirlo al primo giocatore. */
    public boolean raggiungibile() {
        try (Connection c = getConnection()) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
