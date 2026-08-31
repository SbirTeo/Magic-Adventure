package com.teolo.magixguard.sanctions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Le violazioni: i fatti che portano punti, separati dai provvedimenti che ne conseguono.
 *
 * <p>Vivono nella stessa tabella dei provvedimenti (il database del sito) per una ragione
 * pratica: il registro punti va letto a ogni violazione, e farlo su due database diversi
 * vorrebbe dire due connessioni e nessuna transazione possibile.</p>
 *
 * <p>Le prove tecniche vere — indirizzi, impronte dei client — restano invece nello schema
 * della profilazione, dove il sito non arriva.</p>
 */
public final class ViolationsDao {

    private final SiteDb db;

    public ViolationsDao(SiteDb db) {
        this.db = db;
    }

    /**
     * Crea la tabella se non c'e'. La creiamo dal plugin, come fa MagixWeb con le sue: cosi'
     * il modulo funziona al primo avvio senza aspettare che qualcuno lanci una migrazione.
     */
    public void assicuraTabella() throws SQLException {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS punishment_violations ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY,"
                    + "mc_uuid CHAR(36) NOT NULL,"
                    + "mc_username VARCHAR(32) NOT NULL,"
                    + "category VARCHAR(48) NOT NULL,"
                    + "points INT NOT NULL DEFAULT 0,"
                    + "source VARCHAR(32) NOT NULL DEFAULT 'sistema',"
                    + "detail MEDIUMTEXT NULL,"
                    // Quando una sanzione viene revocata, le violazioni che l'hanno fatta
                    // scattare smettono di contare: se il ricorso e' stato accolto, quei punti
                    // non sono mai esistiti.
                    + "cancelled TINYINT(1) NOT NULL DEFAULT 0,"
                    + "punishment_id INT NULL,"
                    + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "KEY idx_uuid (mc_uuid, cancelled),"
                    + "KEY idx_data (created_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        }
    }

    /** Registra il fatto. Ritorna il numero assegnato. */
    public int inserisci(Violation v) throws SQLException {
        String sql = "INSERT INTO punishment_violations "
                + "(mc_uuid, mc_username, category, points, source, detail, created_at) "
                + "VALUES (?,?,?,?,?,?,?)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, v.uuid().toString());
            ps.setString(2, v.nome());
            ps.setString(3, v.categoria());
            ps.setInt(4, v.punti());
            ps.setString(5, v.fonte());
            ps.setString(6, v.dettaglio());
            ps.setTimestamp(7, new Timestamp(v.quando()));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Le violazioni che contano ancora per il registro punti di un giocatore. */
    public List<Violation> perPunti(UUID uuid) throws SQLException {
        String sql = "SELECT * FROM punishment_violations WHERE mc_uuid = ? AND cancelled = 0 "
                + "AND points > 0 ORDER BY id DESC LIMIT 300";
        List<Violation> out = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Violation(
                            rs.getInt("id"),
                            UUID.fromString(rs.getString("mc_uuid")),
                            rs.getString("mc_username"),
                            rs.getString("category"),
                            rs.getInt("points"),
                            rs.getString("source"),
                            rs.getString("detail"),
                            rs.getTimestamp("created_at").getTime()));
                }
            }
        }
        return out;
    }

    /** Le ultime violazioni di un giocatore, per il rapporto e per /storico. */
    public List<Violation> ultime(UUID uuid, int quante) throws SQLException {
        String sql = "SELECT * FROM punishment_violations WHERE mc_uuid = ? ORDER BY id DESC LIMIT "
                + Math.max(1, quante);
        List<Violation> out = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Violation(
                            rs.getInt("id"),
                            UUID.fromString(rs.getString("mc_uuid")),
                            rs.getString("mc_username"),
                            rs.getString("category"),
                            rs.getInt("points"),
                            rs.getString("source"),
                            rs.getString("detail"),
                            rs.getTimestamp("created_at").getTime()));
                }
            }
        }
        return out;
    }

    /** Collega la violazione al provvedimento che ha fatto scattare. */
    public void collega(int idViolazione, int idSanzione) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE punishment_violations SET punishment_id = ? WHERE id = ?")) {
            ps.setInt(1, idSanzione);
            ps.setInt(2, idViolazione);
            ps.executeUpdate();
        }
    }

    /**
     * Annulla le violazioni legate a un provvedimento revocato: quei punti non contano piu'.
     * Se non lo si facesse, un ricorso accolto lascerebbe comunque il giocatore a un passo
     * dalla soglia successiva — cioe' mezzo punito lo stesso.
     */
    public int annullaPerSanzione(int idSanzione) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE punishment_violations SET cancelled = 1 WHERE punishment_id = ?")) {
            ps.setInt(1, idSanzione);
            return ps.executeUpdate();
        }
    }
}
