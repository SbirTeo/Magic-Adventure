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
 * Tutte le query sulle tabelle delle sanzioni, in un posto solo.
 *
 * <p>Regola che questa classe fa rispettare: il plugin <b>crea</b> i provvedimenti, il sito no.
 * Il sito scrive solo ricorsi e decisioni dello staff (revoche, conferme dalla coda), e quelle
 * qui si <b>leggono</b> per eseguirle in partita.</p>
 */
public final class SanctionsDao {

    private final SiteDb db;

    public SanctionsDao(SiteDb db) {
        this.db = db;
    }

    // ------------------------------------------------------------------ scrittura

    /** Scrive un provvedimento e ne restituisce il numero assegnato dal database. */
    public int inserisci(Sanction s) throws SQLException {
        String sql = "INSERT INTO punishments (mc_uuid, mc_username, type, category, reason, scope, "
                + "points, starts_at, ends_at, staff_name, automatic, status, report_hash, created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?, 'attiva', ?, NOW())";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, s.uuid().toString());
            ps.setString(2, s.name());
            ps.setString(3, s.type().code());
            ps.setString(4, s.category());
            ps.setString(5, s.reason());
            ps.setString(6, s.scope().code());
            ps.setInt(7, s.points());
            ps.setTimestamp(8, new Timestamp(s.beginning()));
            if (s.fine() == Duration.PERMANENTE) {
                ps.setNull(9, java.sql.Types.TIMESTAMP);
            } else {
                ps.setTimestamp(9, new Timestamp(s.fine()));
            }
            ps.setString(10, s.staff());
            ps.setBoolean(11, s.automaticMode());
            ps.setString(12, s.impronta());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Revoca dal gioco (per esempio con /unban): la marca gia' come applicata. */
    public int revoke(int id, String staff, String reason) throws SQLException {
        String sql = "UPDATE punishments SET status = 'revocata', revoked_by = ?, revoked_at = NOW(), "
                + "revoke_reason = ?, revoke_applied = 1 WHERE id = ? AND status = 'attiva'";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, staff);
            ps.setString(2, reason);
            ps.setInt(3, id);
            return ps.executeUpdate();
        }
    }

    /** Revoca l'ultima sanzione attiva di quel tipo per quel giocatore. Ritorna l'id, o 0. */
    public int revokeLast(UUID uuid, Type type, String staff, String reason) throws SQLException {
        Integer id = null;
        String search = "SELECT id FROM punishments WHERE mc_uuid = ? AND type = ? AND status = 'attiva' "
                + "AND (ends_at IS NULL OR ends_at > NOW()) ORDER BY id DESC LIMIT 1";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(search)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, type.code());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    id = rs.getInt(1);
                }
            }
        }
        if (id == null) {
            return 0;
        }
        return revoke(id, staff, reason) > 0 ? id : 0;
    }

    // ------------------------------------------------------------------ lettura

    /** I provvedimenti attivi di un giocatore, gia' filtrati per ambito di gioco. */
    public List<Sanction> attiveInGioco(UUID uuid) throws SQLException {
        String sql = "SELECT * FROM punishments WHERE mc_uuid = ? AND status = 'attiva' "
                + "AND scope IN ('gioco','entrambi') AND (ends_at IS NULL OR ends_at > NOW()) "
                + "ORDER BY id DESC";
        return interroga(sql, uuid.toString());
    }

    /** Tutto lo storico di un giocatore, dal piu' recente. */
    public List<Sanction> history(UUID uuid, int quante) throws SQLException {
        String sql = "SELECT * FROM punishments WHERE mc_uuid = ? ORDER BY id DESC LIMIT " + Math.max(1, quante);
        return interroga(sql, uuid.toString());
    }

    /**
     * Le sanzioni che contano per il registro punti: non revocate e con punti sopra zero.
     * Il decadimento non si calcola qui — e' una cosa che si fa sui dati, non nel database,
     * cosi' la formula resta una sola e leggibile.
     */
    public List<Sanction> perPoints(UUID uuid) throws SQLException {
        String sql = "SELECT * FROM punishments WHERE mc_uuid = ? AND status <> 'revocata' AND points > 0 "
                + "ORDER BY id DESC LIMIT 200";
        return interroga(sql, uuid.toString());
    }

    private List<Sanction> interroga(String sql, String parametro) throws SQLException {
        List<Sanction> out = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, parametro);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
        }
        return out;
    }

    private static Sanction read(ResultSet rs) throws SQLException {
        Timestamp fine = rs.getTimestamp("ends_at");
        return new Sanction(
                rs.getInt("id"),
                UUID.fromString(rs.getString("mc_uuid")),
                rs.getString("mc_username"),
                Type.da(rs.getString("type")),
                rs.getString("category"),
                rs.getString("reason"),
                Scope.da(rs.getString("scope")),
                rs.getInt("points"),
                rs.getTimestamp("starts_at").getTime(),
                fine == null ? Duration.PERMANENTE : fine.getTime(),
                rs.getString("staff_name"),
                rs.getBoolean("automatic"),
                rs.getString("report_hash"));
    }

    // ------------------------------------------------------------------ coda e proposte

    /** Mette una proposta in coda: la decidera' una persona dal gestionale. */
    public void proponi(Sanction s, long durationMillis, String fonte, String dettaglio) throws SQLException {
        String sql = "INSERT INTO punishment_queue (mc_uuid, mc_username, type, category, reason, scope, "
                + "duration_seconds, points, source, proposed_by, detail, report_hash, status, created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'attesa', NOW())";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, s.uuid().toString());
            ps.setString(2, s.name());
            ps.setString(3, s.type().code());
            ps.setString(4, s.category());
            ps.setString(5, s.reason());
            ps.setString(6, s.scope().code());
            if (durationMillis == Duration.PERMANENTE) {
                ps.setNull(7, java.sql.Types.INTEGER);
            } else {
                ps.setInt(7, (int) (durationMillis / 1000));
            }
            ps.setInt(8, s.points());
            ps.setString(9, fonte);
            ps.setString(10, s.staff());
            ps.setString(11, dettaglio);
            ps.setString(12, s.impronta());
            ps.executeUpdate();
        }
    }

    /** Una proposta confermata dallo staff sul sito e non ancora eseguita in partita. */
    public record Proposta(int id, UUID uuid, String name, Type type, String category, String reason,
                           Scope scope, Long durationSeconds, int points, String decisaDa) { }

    public List<Proposta> proposteConfermate() throws SQLException {
        String sql = "SELECT * FROM punishment_queue WHERE status = 'confermata' AND punishment_id IS NULL "
                + "ORDER BY id ASC LIMIT 20";
        List<Proposta> out = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int duration = rs.getInt("duration_seconds");
                out.add(new Proposta(
                        rs.getInt("id"),
                        UUID.fromString(rs.getString("mc_uuid")),
                        rs.getString("mc_username"),
                        Type.da(rs.getString("type")),
                        rs.getString("category"),
                        rs.getString("reason"),
                        Scope.da(rs.getString("scope")),
                        rs.wasNull() ? null : (long) duration,
                        rs.getInt("points"),
                        rs.getString("decided_by")));
            }
        }
        return out;
    }

    /** Segna che la proposta e' diventata quel provvedimento: cosi' non si esegue due volte. */
    public void codaEseguita(int idCoda, int sanctionId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE punishment_queue SET punishment_id = ? WHERE id = ?")) {
            ps.setInt(1, sanctionId);
            ps.setInt(2, idCoda);
            ps.executeUpdate();
        }
    }

    /** Le revoche decise dal gestionale e non ancora eseguite in gioco. */
    public List<Sanction> revocheDaApplicare() throws SQLException {
        String sql = "SELECT * FROM punishments WHERE status = 'revocata' AND revoke_applied = 0 LIMIT 20";
        List<Sanction> out = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(read(rs));
            }
        }
        return out;
    }

    public void revokeApplied(int id) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE punishments SET revoke_applied = 1 WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ regolamento

    /** Quante segnalazioni ancora aperte ha mandato quella persona. */
    public int reportApertiDi(String whoReports) throws SQLException {
        String sql = "SELECT COUNT(*) FROM punishment_queue WHERE source = 'report' "
                + "AND proposed_by = ? AND status = 'attesa'";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, whoReports);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Deposita il blocco del regolamento generato dalla configurazione. */
    public void writeRules(String html, String version) throws SQLException {
        String sql = "INSERT INTO punishment_rules (id, body_html, version, updated_at) "
                + "VALUES (1, ?, ?, NOW()) ON DUPLICATE KEY UPDATE body_html = VALUES(body_html), "
                + "version = VALUES(version), updated_at = NOW()";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, html);
            ps.setString(2, version);
            ps.executeUpdate();
        }
    }

    /** L'uuid di un giocatore mai visto in partita, cercato fra gli account del sito. */
    public UUID uuidFromName(String name) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT mc_uuid FROM users WHERE mc_username = ? LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? UUID.fromString(rs.getString(1)) : null;
            }
        }
    }
}
