package com.teolo.magixguard.db;

import com.teolo.magixguard.analyze.EvidenceType;
import com.teolo.magixguard.model.PlayerRef;
import com.teolo.magixguard.model.Rows;
import com.teolo.magixguard.model.SessionSnapshot;
import com.teolo.magixguard.util.Detail;
import com.teolo.magixguard.util.Hashing;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tutte le query del plugin in un solo posto.
 *
 * Nota sulla portabilita': niente ON CONFLICT / ON DUPLICATE KEY. Le scritture passano tutte
 * dallo stesso thread ({@link DbExecutor}), quindi un SELECT seguito da INSERT o UPDATE e'
 * sicuro e funziona identico su SQLite e MariaDB.
 */
public final class GuardDao {

    /** Sentinella per le sessioni ancora aperte nei confronti temporali. */
    private static final long OPEN = Long.MAX_VALUE;

    private final Database db;

    public GuardDao(Database db) {
        this.db = db;
    }

    // ============================== ACCOUNT ==============================

    /** Crea o aggiorna l'anagrafica dell'account e restituisce il suo id interno. */
    public long upsertPlayer(UUID uuid, String name, long now) throws SQLException {
        try (Connection c = db.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM mg_players WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long id = rs.getLong(1);
                        try (PreparedStatement up = c.prepareStatement(
                                "UPDATE mg_players SET name=?, name_lower=?, last_seen=?, session_count=session_count+1 WHERE id=?")) {
                            up.setString(1, name);
                            up.setString(2, name.toLowerCase());
                            up.setLong(3, now);
                            up.setLong(4, id);
                            up.executeUpdate();
                        }
                        return id;
                    }
                }
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO mg_players (uuid, name, name_lower, first_seen, last_seen, session_count) VALUES (?,?,?,?,?,1)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ins.setString(1, uuid.toString());
                ins.setString(2, name);
                ins.setString(3, name.toLowerCase());
                ins.setLong(4, now);
                ins.setLong(5, now);
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    if (keys.next()) return keys.getLong(1);
                }
            }
            throw new SQLException("impossibile creare il record giocatore per " + name);
        }
    }

    public Optional<PlayerRef> findPlayerByName(String name) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, uuid, name, first_seen, last_seen, session_count, playtime_seconds " +
                             "FROM mg_players WHERE name_lower=?")) {
            ps.setString(1, name.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readPlayer(rs)) : Optional.empty();
            }
        }
    }

    public Optional<PlayerRef> findPlayer(long id) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, uuid, name, first_seen, last_seen, session_count, playtime_seconds " +
                             "FROM mg_players WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readPlayer(rs)) : Optional.empty();
            }
        }
    }

    private static PlayerRef readPlayer(ResultSet rs) throws SQLException {
        return new PlayerRef(rs.getLong("id"), UUID.fromString(rs.getString("uuid")), rs.getString("name"),
                rs.getLong("first_seen"), rs.getLong("last_seen"),
                rs.getInt("session_count"), rs.getLong("playtime_seconds"));
    }

    public boolean isExempt(long playerId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT exempt FROM mg_players WHERE id=?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) != 0;
            }
        }
    }

    public void setExempt(long playerId, boolean exempt) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE mg_players SET exempt=? WHERE id=?")) {
            ps.setInt(1, exempt ? 1 : 0);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    // ============================== SESSIONI ==============================

    public long insertSession(SessionSnapshot s) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO mg_sessions (player_id, join_at, ip, ip_hash, subnet, subnet_hash, " +
                             "rdns, rdns_hash, hostname) VALUES (?,?,?,?,?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, s.playerId);
            ps.setLong(2, s.joinAt);
            ps.setString(3, s.ip);
            ps.setString(4, s.ipHash);
            ps.setString(5, s.subnet);
            ps.setString(6, s.subnetHash);
            ps.setString(7, s.rdns);
            ps.setString(8, s.rdnsHash);
            ps.setString(9, s.hostname);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
            throw new SQLException("impossibile creare la sessione per " + s.name);
        }
    }

    /** Completa la sessione con i dati del client, che arrivano qualche secondo dopo il login. */
    public void updateSessionClient(SessionSnapshot s) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE mg_sessions SET brand=?, channels=?, channels_hash=?, locale=?, view_distance=?, " +
                             "skin_parts=?, main_hand=?, chat_flags=?, cookie_token=?, fingerprint=?, rdns=?, rdns_hash=? " +
                             "WHERE id=?")) {
            ps.setString(1, s.brand);
            ps.setString(2, s.channels);
            ps.setString(3, s.channelsHash);
            ps.setString(4, s.locale);
            setInt(ps, 5, s.viewDistance);
            setInt(ps, 6, s.skinParts);
            ps.setString(7, s.mainHand);
            ps.setString(8, s.chatFlags);
            ps.setString(9, s.cookieToken);
            ps.setString(10, s.fingerprint);
            ps.setString(11, s.rdns);
            ps.setString(12, s.rdnsHash);
            ps.setLong(13, s.sessionId);
            ps.executeUpdate();
        }
    }

    public void updateSessionPack(long sessionId, String status, Integer ms) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE mg_sessions SET pack_status=?, pack_ms=? WHERE id=?")) {
            ps.setString(1, status);
            setInt(ps, 2, ms);
            ps.setLong(3, sessionId);
            ps.executeUpdate();
        }
    }

    public void closeSession(long sessionId, long playerId, long quitAt, Integer pingMedian, long playedSeconds)
            throws SQLException {
        try (Connection c = db.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE mg_sessions SET quit_at=?, ping_median=? WHERE id=?")) {
                ps.setLong(1, quitAt);
                setInt(ps, 2, pingMedian);
                ps.setLong(3, sessionId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE mg_players SET playtime_seconds=playtime_seconds+?, last_seen=? WHERE id=?")) {
                ps.setLong(1, Math.max(0, playedSeconds));
                ps.setLong(2, quitAt);
                ps.setLong(3, playerId);
                ps.executeUpdate();
            }
        }
    }

    /** Chiude le sessioni rimaste aperte da un arresto brusco del server (crash, kill). */
    public int closeDanglingSessions(long now) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE mg_sessions SET quit_at=? WHERE quit_at IS NULL")) {
            ps.setLong(1, now);
            return ps.executeUpdate();
        }
    }

    public List<Rows.Session> sessionsOf(long playerId, int limit) throws SQLException {
        List<Rows.Session> out = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM mg_sessions WHERE player_id=? ORDER BY join_at DESC LIMIT ?")) {
            ps.setLong(1, playerId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readSession(rs));
            }
        }
        return out;
    }

    private static Rows.Session readSession(ResultSet rs) throws SQLException {
        long quit = rs.getLong("quit_at");
        return new Rows.Session(
                rs.getLong("id"), rs.getLong("player_id"), rs.getLong("join_at"),
                rs.wasNull() ? null : quit,
                rs.getString("ip"), rs.getString("rdns"), rs.getString("hostname"), rs.getString("brand"),
                rs.getString("channels"), rs.getString("locale"),
                getInt(rs, "view_distance"), getInt(rs, "skin_parts"), rs.getString("main_hand"),
                getInt(rs, "ping_median"), rs.getString("pack_status"),
                rs.getString("cookie_token"), rs.getString("fingerprint"));
    }

    // ====================== RICERCA DEI CANDIDATI ======================

    /** Colonne su cui e' lecito cercare corrispondenze (elenco chiuso: niente SQL costruito da input). */
    public enum MatchColumn {
        IP_HASH("ip_hash"), SUBNET_HASH("subnet_hash"), RDNS_HASH("rdns_hash"),
        COOKIE_TOKEN("cookie_token"), FINGERPRINT("fingerprint"), CHANNELS_HASH("channels_hash");

        private final String column;
        MatchColumn(String column) { this.column = column; }
        public String column() { return column; }
    }

    /** Account (diversi da quello indicato) che in passato hanno presentato lo stesso valore. */
    public Set<Long> playersMatching(MatchColumn col, String value, long excludePlayerId, int limit)
            throws SQLException {
        Set<Long> out = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return out;
        String sql = "SELECT DISTINCT player_id FROM mg_sessions WHERE " + col.column() + "=? AND player_id<>? LIMIT ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            ps.setLong(2, excludePlayerId);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
        }
        return out;
    }

    /**
     * Quanti account distinti hanno usato questo IP. E' la misura di affollamento: su rete mobile
     * italiana (CGNAT) lo stesso IP puo' essere condiviso da decine di persone estranee fra loro.
     */
    public int countPlayersOnIpHash(String ipHash) throws SQLException {
        if (ipHash == null) return 0;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(DISTINCT player_id) FROM mg_sessions WHERE ip_hash=?")) {
            ps.setString(1, ipHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Ultimo accesso di quell'account sullo stesso IP: serve a distinguere "stesso periodo" da "storico". */
    public Long lastJoinOnIpHash(long playerId, String ipHash) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT MAX(join_at) FROM mg_sessions WHERE player_id=? AND ip_hash=?")) {
            ps.setLong(1, playerId);
            ps.setString(2, ipHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? null : v;
                }
            }
        }
        return null;
    }

    /**
     * Staffetta: account usciti dallo stesso IP nei secondi immediatamente precedenti a questo join.
     * Restituisce coppie [playerId, quitAt].
     */
    public List<long[]> handoffCandidates(String ipHash, long joinAt, long windowSeconds, long excludePlayerId)
            throws SQLException {
        List<long[]> out = new ArrayList<>();
        if (ipHash == null) return out;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player_id, MAX(quit_at) FROM mg_sessions WHERE ip_hash=? AND player_id<>? " +
                             "AND quit_at IS NOT NULL AND quit_at>=? AND quit_at<=? GROUP BY player_id")) {
            ps.setString(1, ipHash);
            ps.setLong(2, excludePlayerId);
            ps.setLong(3, joinAt - windowSeconds * 1000L);
            ps.setLong(4, joinAt);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new long[]{rs.getLong(1), rs.getLong(2)});
            }
        }
        return out;
    }

    /**
     * Quante volte i due account sono stati collegati NELLO STESSO MOMENTO.
     * E' la principale prova a discolpa: due fratelli giocano insieme, un alt no.
     */
    public int countOverlappingSessions(long aId, long bId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM mg_sessions a JOIN mg_sessions b " +
                             "ON a.player_id=? AND b.player_id=? " +
                             "AND a.join_at < COALESCE(b.quit_at, ?) AND b.join_at < COALESCE(a.quit_at, ?)")) {
            ps.setLong(1, aId);
            ps.setLong(2, bId);
            ps.setLong(3, OPEN);
            ps.setLong(4, OPEN);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Impronta client piu' recente di un account (per confrontarla con quella di un altro). */
    public String latestFingerprint(long playerId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT fingerprint FROM mg_sessions WHERE player_id=? AND fingerprint IS NOT NULL " +
                             "ORDER BY join_at DESC LIMIT 1")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Ultimo IP in chiaro noto di un account (per il dossier; puo' essere gia' anonimizzato). */
    public String latestIp(long playerId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ip FROM mg_sessions WHERE player_id=? AND ip IS NOT NULL ORDER BY join_at DESC LIMIT 1")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ============================== PROVE ==============================

    /**
     * Registra (o aggiorna) un indizio. Le occorrenze contano quante volte lo abbiamo osservato:
     * servono a descrivere il fenomeno nel dossier, NON ad aumentare il punteggio - il peso di un
     * tipo di prova resta quello configurato, altrimenti chi gioca tanto verrebbe punito due volte.
     */
    public void upsertEvidence(long aId, long bId, EvidenceType type, long ts, Detail detail) throws SQLException {
        long a = Math.min(aId, bId);
        long b = Math.max(aId, bId);
        try (Connection c = db.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, occurrences FROM mg_evidence WHERE a_id=? AND b_id=? AND type=?")) {
                ps.setLong(1, a);
                ps.setLong(2, b);
                ps.setString(3, type.name());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long id = rs.getLong(1);
                        try (PreparedStatement up = c.prepareStatement(
                                "UPDATE mg_evidence SET occurrences=occurrences+1, last_seen=?, detail=? WHERE id=?")) {
                            up.setLong(1, ts);
                            up.setString(2, detail == null ? null : detail.toString());
                            up.setLong(3, id);
                            up.executeUpdate();
                        }
                        return;
                    }
                }
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO mg_evidence (a_id, b_id, type, occurrences, first_seen, last_seen, detail) " +
                            "VALUES (?,?,?,1,?,?,?)")) {
                ins.setLong(1, a);
                ins.setLong(2, b);
                ins.setString(3, type.name());
                ins.setLong(4, ts);
                ins.setLong(5, ts);
                ins.setString(6, detail == null ? null : detail.toString());
                ins.executeUpdate();
            }
        }
    }

    /** Aggiorna il conteggio di una prova a discolpa senza incrementarlo a ogni ricalcolo. */
    public void setEvidenceOccurrences(long aId, long bId, EvidenceType type, int occurrences, long ts, Detail detail)
            throws SQLException {
        long a = Math.min(aId, bId);
        long b = Math.max(aId, bId);
        try (Connection c = db.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE mg_evidence SET occurrences=?, last_seen=?, detail=? WHERE a_id=? AND b_id=? AND type=?")) {
                ps.setInt(1, occurrences);
                ps.setLong(2, ts);
                ps.setString(3, detail == null ? null : detail.toString());
                ps.setLong(4, a);
                ps.setLong(5, b);
                ps.setString(6, type.name());
                if (ps.executeUpdate() > 0) return;
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO mg_evidence (a_id, b_id, type, occurrences, first_seen, last_seen, detail) " +
                            "VALUES (?,?,?,?,?,?,?)")) {
                ins.setLong(1, a);
                ins.setLong(2, b);
                ins.setString(3, type.name());
                ins.setInt(4, occurrences);
                ins.setLong(5, ts);
                ins.setLong(6, ts);
                ins.setString(7, detail == null ? null : detail.toString());
                ins.executeUpdate();
            }
        }
    }

    public List<Rows.Evidence> evidenceFor(long aId, long bId) throws SQLException {
        long a = Math.min(aId, bId);
        long b = Math.max(aId, bId);
        List<Rows.Evidence> out = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM mg_evidence WHERE a_id=? AND b_id=? ORDER BY type")) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    EvidenceType t = EvidenceType.parse(rs.getString("type"));
                    if (t == null) continue;
                    out.add(new Rows.Evidence(rs.getLong("id"), rs.getLong("a_id"), rs.getLong("b_id"), t,
                            rs.getInt("occurrences"), rs.getLong("first_seen"), rs.getLong("last_seen"),
                            rs.getString("detail")));
                }
            }
        }
        return out;
    }

    // ============================== COLLEGAMENTI ==============================

    public void upsertLink(long aId, long bId, double score, long now) throws SQLException {
        long a = Math.min(aId, bId);
        long b = Math.max(aId, bId);
        try (Connection c = db.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE mg_links SET score=?, updated_at=? WHERE a_id=? AND b_id=?")) {
                ps.setDouble(1, score);
                ps.setLong(2, now);
                ps.setLong(3, a);
                ps.setLong(4, b);
                if (ps.executeUpdate() > 0) return;
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO mg_links (a_id, b_id, score, updated_at) VALUES (?,?,?,?)")) {
                ins.setLong(1, a);
                ins.setLong(2, b);
                ins.setDouble(3, score);
                ins.setLong(4, now);
                ins.executeUpdate();
            }
        }
    }

    public void setLinkManual(long aId, long bId, boolean manual, double score, long now) throws SQLException {
        upsertLink(aId, bId, score, now);
        long a = Math.min(aId, bId);
        long b = Math.max(aId, bId);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE mg_links SET manual=? WHERE a_id=? AND b_id=?")) {
            ps.setInt(1, manual ? 1 : 0);
            ps.setLong(2, a);
            ps.setLong(3, b);
            ps.executeUpdate();
        }
    }

    public Optional<Rows.Link> getLink(long aId, long bId) throws SQLException {
        long a = Math.min(aId, bId);
        long b = Math.max(aId, bId);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM mg_links WHERE a_id=? AND b_id=?")) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readLink(rs)) : Optional.empty();
            }
        }
    }

    /** Collegamenti di un account sopra la soglia, dal piu' forte al piu' debole. */
    public List<Rows.Link> linksOf(long playerId, double minScore) throws SQLException {
        List<Rows.Link> out = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM mg_links WHERE (a_id=? OR b_id=?) AND score>=? ORDER BY score DESC")) {
            ps.setLong(1, playerId);
            ps.setLong(2, playerId);
            ps.setDouble(3, minScore);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readLink(rs));
            }
        }
        return out;
    }

    public void markAlerted(long aId, long bId, long now) throws SQLException {
        long a = Math.min(aId, bId);
        long b = Math.max(aId, bId);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE mg_links SET alerted_at=? WHERE a_id=? AND b_id=?")) {
            ps.setLong(1, now);
            ps.setLong(2, a);
            ps.setLong(3, b);
            ps.executeUpdate();
        }
    }

    private static Rows.Link readLink(ResultSet rs) throws SQLException {
        long alerted = rs.getLong("alerted_at");
        return new Rows.Link(rs.getLong("a_id"), rs.getLong("b_id"), rs.getDouble("score"),
                rs.getLong("updated_at"), rs.wasNull() ? null : alerted, rs.getInt("manual") != 0);
    }

    // ============================== WHITELIST ==============================

    public boolean isWhitelisted(long aId, long bId) throws SQLException {
        long a = Math.min(aId, bId);
        long b = Math.max(aId, bId);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM mg_whitelist WHERE a_id=? AND b_id=?")) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void addWhitelist(long aId, long bId, String staff, String reason, long now) throws SQLException {
        long a = Math.min(aId, bId);
        long b = Math.max(aId, bId);
        if (isWhitelisted(a, b)) return;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO mg_whitelist (a_id, b_id, staff, reason, created_at) VALUES (?,?,?,?,?)")) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            ps.setString(3, staff);
            ps.setString(4, reason);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    public boolean removeWhitelist(long aId, long bId) throws SQLException {
        long a = Math.min(aId, bId);
        long b = Math.max(aId, bId);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM mg_whitelist WHERE a_id=? AND b_id=?")) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            return ps.executeUpdate() > 0;
        }
    }

    public Optional<Rows.Whitelist> getWhitelist(long aId, long bId) throws SQLException {
        long a = Math.min(aId, bId);
        long b = Math.max(aId, bId);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM mg_whitelist WHERE a_id=? AND b_id=?")) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Rows.Whitelist(rs.getLong("a_id"), rs.getLong("b_id"),
                        rs.getString("staff"), rs.getString("reason"), rs.getLong("created_at")));
            }
        }
    }

    // ============================== ALERT ==============================

    public long insertAlert(long aId, long bId, double score, long now) throws SQLException {
        long a = Math.min(aId, bId);
        long b = Math.max(aId, bId);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO mg_alerts (a_id, b_id, score, created_at, status) VALUES (?,?,?,?,'NUOVO')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            ps.setDouble(3, score);
            ps.setLong(4, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        return -1;
    }

    public List<Rows.Alert> recentAlerts(int limit) throws SQLException {
        List<Rows.Alert> out = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM mg_alerts ORDER BY created_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Rows.Alert(rs.getLong("id"), rs.getLong("a_id"), rs.getLong("b_id"),
                            rs.getDouble("score"), rs.getLong("created_at"),
                            rs.getString("status"), rs.getString("notes")));
                }
            }
        }
        return out;
    }

    public boolean setAlertStatus(long alertId, String status, String notes) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE mg_alerts SET status=?, notes=? WHERE id=?")) {
            ps.setString(1, status);
            ps.setString(2, notes);
            ps.setLong(3, alertId);
            return ps.executeUpdate() > 0;
        }
    }

    // ============================== REGISTRO FIRMATO ==============================

    /**
     * Aggiunge una riga al registro append-only. Ogni riga include l'impronta della precedente:
     * se qualcuno modifica o cancella una riga a posteriori (per esempio per "costruire" prove
     * dopo un ban), la catena non torna piu' e {@link #verifyChain()} lo segnala.
     */
    public String appendAudit(String actor, String action, String subject, String detail, long ts) throws SQLException {
        try (Connection c = db.getConnection()) {
            String prev = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT hash FROM mg_audit ORDER BY id DESC LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) prev = rs.getString(1);
            }
            String hash = auditHash(prev, ts, actor, action, subject, detail);
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO mg_audit (ts, actor, action, subject, detail, prev_hash, hash) VALUES (?,?,?,?,?,?,?)")) {
                ins.setLong(1, ts);
                ins.setString(2, actor);
                ins.setString(3, action);
                ins.setString(4, subject);
                ins.setString(5, detail);
                ins.setString(6, prev);
                ins.setString(7, hash);
                ins.executeUpdate();
            }
            return hash;
        }
    }

    public static String auditHash(String prev, long ts, String actor, String action, String subject, String detail) {
        return Hashing.sha256((prev == null ? "" : prev) + "|" + ts + "|" + actor + "|" + action + "|"
                + (subject == null ? "" : subject) + "|" + (detail == null ? "" : detail));
    }

    /** Esito della verifica della catena: righe controllate e id della prima riga incoerente. */
    public record ChainCheck(int rows, Long firstBrokenId, String lastHash) {
        public boolean ok() { return firstBrokenId == null; }
    }

    public ChainCheck verifyChain() throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM mg_audit ORDER BY id ASC");
             ResultSet rs = ps.executeQuery()) {
            String prev = null;
            int rows = 0;
            String last = null;
            while (rs.next()) {
                rows++;
                String stored = rs.getString("hash");
                String storedPrev = rs.getString("prev_hash");
                String expected = auditHash(prev, rs.getLong("ts"), rs.getString("actor"), rs.getString("action"),
                        rs.getString("subject"), rs.getString("detail"));
                boolean prevMatches = (prev == null && storedPrev == null) || (prev != null && prev.equals(storedPrev));
                if (!expected.equals(stored) || !prevMatches) {
                    return new ChainCheck(rows, rs.getLong("id"), stored);
                }
                prev = stored;
                last = stored;
            }
            return new ChainCheck(rows, null, last);
        }
    }

    public List<Rows.Audit> auditFor(String subjectLike, int limit) throws SQLException {
        List<Rows.Audit> out = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM mg_audit WHERE subject LIKE ? ORDER BY ts DESC LIMIT ?")) {
            ps.setString(1, subjectLike);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Rows.Audit(rs.getLong("id"), rs.getLong("ts"), rs.getString("actor"),
                            rs.getString("action"), rs.getString("subject"), rs.getString("detail"),
                            rs.getString("prev_hash"), rs.getString("hash")));
                }
            }
        }
        return out;
    }

    // ============================== MANUTENZIONE ==============================

    /**
     * Anonimizza le sessioni piu' vecchie della retention: sparisce l'IP leggibile, restano gli
     * hash (che permettono i confronti) e i collegamenti gia' calcolati.
     */
    public int anonymizeOlderThan(long cutoff) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE mg_sessions SET ip=NULL, rdns=NULL, hostname=NULL " +
                             "WHERE join_at<? AND (ip IS NOT NULL OR rdns IS NOT NULL OR hostname IS NOT NULL)")) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        }
    }

    public long count(String table) throws SQLException {
        if (!Database.TABLES.contains(table)) throw new IllegalArgumentException("tabella non prevista: " + table);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public List<PlayerRef> playersByIds(Collection<Long> ids) throws SQLException {
        List<PlayerRef> out = new ArrayList<>();
        if (ids.isEmpty()) return out;
        StringBuilder sb = new StringBuilder(
                "SELECT id, uuid, name, first_seen, last_seen, session_count, playtime_seconds FROM mg_players WHERE id IN (");
        for (int i = 0; i < ids.size(); i++) sb.append(i == 0 ? "?" : ",?");
        sb.append(')');
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sb.toString())) {
            int i = 1;
            for (Long id : ids) ps.setLong(i++, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readPlayer(rs));
            }
        }
        return out;
    }

    // ============================== utilita' ==============================

    private static void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) ps.setNull(index, java.sql.Types.INTEGER);
        else ps.setInt(index, value);
    }

    private static Integer getInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }
}
