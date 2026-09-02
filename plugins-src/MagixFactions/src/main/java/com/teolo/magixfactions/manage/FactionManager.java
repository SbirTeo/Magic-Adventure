package com.teolo.magixfactions.manage;

import com.teolo.magixfactions.config.Ranks;
import com.teolo.magixfactions.db.Database;
import com.teolo.magixfactions.db.DbExecutor;
import com.teolo.magixfactions.model.Faction;
import com.teolo.magixfactions.model.Member;
import com.teolo.magixfactions.model.Rank;
import com.teolo.magixfactions.model.RelationType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.*;

/** Logica e persistenza delle fazioni (con cache in memoria). */
public final class FactionManager {

    private final JavaPlugin plugin;
    private final Database db;
    private final DbExecutor dbExec;
    private final Ranks ranks;

    private final Map<Long, Faction> byId = new HashMap<>();
    private final Map<String, Long> byName = new HashMap<>();      // nome lower -> id
    private final Map<UUID, Long> playerFaction = new HashMap<>();
    // Alleanze "desiderate" da ogni fazione: faction_id -> (other_id -> ALLY).
    // ENEMY = default (assenza di riga). Due fazioni sono alleate solo se entrambe lo desiderano.
    private final Map<Long, Map<Long, RelationType>> wishes = new HashMap<>();

    private ClaimManager claimManager; // impostato dopo la costruzione (per pulire i claim al disband)
    private DecayManager decayManager; // per far partire/azzerare il timer del decadimento (sovraccarico) al cambio membri

    /** Home di fazione (posizione salvata). */
    public static final class Home {
        public final String world; public final double x, y, z; public final float yaw, pitch;
        public Home(String world, double x, double y, double z, float yaw, float pitch) {
            this.world = world; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        }
        /** Chiave del chunk della home nel formato usato da ClaimManager: "world:cx:cz". */
        public String chunkKey() {
            return world + ":" + ((int) Math.floor(x) >> 4) + ":" + ((int) Math.floor(z) >> 4);
        }
        public Location toLocation() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }

    private final Map<Long, Home> homes = new HashMap<>();

    public FactionManager(JavaPlugin plugin, Database db, DbExecutor dbExec, Ranks ranks) {
        this.plugin = plugin;
        this.db = db;
        this.dbExec = dbExec;
        this.ranks = ranks;
    }

    /** Esegue in async serializzato una scrittura di persistenza; logga eventuali errori SQL. */
    private void write(String label, SqlWrite w) {
        dbExec.submit(() -> {
            try (Connection c = db.getConnection()) {
                w.run(c);
            } catch (SQLException e) {
                plugin.getLogger().warning("[Factions] " + label + ": " + e.getMessage());
            }
        });
    }

    @FunctionalInterface private interface SqlWrite { void run(Connection c) throws SQLException; }

    public Ranks ranks() { return ranks; }

    public void setClaimManager(ClaimManager cm) { this.claimManager = cm; }

    public void setDecayManager(DecayManager dm) { this.decayManager = dm; }

    /** Fazione per id, o null. */
    public Faction getById(long id) { return byId.get(id); }

    // ------------------------------- LOAD --------------------------------
    public void loadAll() throws SQLException {
        byId.clear(); byName.clear(); playerFaction.clear(); wishes.clear(); homes.clear();
        try (Connection c = db.getConnection()) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM factions")) {
                while (rs.next()) {
                    Faction f = new Faction(rs.getLong("id"), rs.getString("name"), rs.getString("tag"),
                            uuid(rs.getString("leader")), rs.getLong("created_at"), rs.getLong("member_limit_bonus"));
                    f.setDescription(rs.getString("description"));
                    f.setBank(rs.getDouble("bank"));
                    // Medie nel tempo per il punteggio (vedi ScoreManager). Su una fazione mai campionata
                    // (colonne appena aggiunte, o creata prima della feature) la finestra parte da ORA:
                    // senza storico non si puo' inventare una media passata, quindi si inizia a misurare
                    // dall'upgrade. Il valore corretto viene poi salvato al primo campionamento.
                    f.setBankAvgAccum(rs.getDouble("bank_avg_accum"));
                    f.setPowerAvgAccum(rs.getDouble("power_avg_accum"));
                    long sampledAt = rs.getLong("score_sampled_at");
                    long since = rs.getLong("score_since");
                    if (since <= 0) { long now = System.currentTimeMillis(); since = now; sampledAt = now; }
                    if (sampledAt <= 0) sampledAt = since;
                    f.setScoreSince(since);
                    f.setScoreSampledAt(sampledAt);
                    f.setScore(rs.getDouble("score"));
                    String detail = rs.getString("score_detail");
                    if (detail != null && !detail.isEmpty()) f.setScoreDetail(detail);
                    byId.put(f.getId(), f);
                    byName.put(f.getName().toLowerCase(Locale.ROOT), f.getId());
                }
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM faction_members")) {
                while (rs.next()) {
                    long fid = rs.getLong("faction_id");
                    Faction f = byId.get(fid);
                    if (f == null) continue;
                    UUID u = uuid(rs.getString("uuid"));
                    Member m = new Member(u, rs.getString("rank"), rs.getLong("rank_since"), rs.getLong("joined_at"));
                    f.getMembers().put(u, m);
                    playerFaction.put(u, fid);
                }
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM member_permissions")) {
                while (rs.next()) {
                    UUID u = uuid(rs.getString("uuid"));
                    Long fid = playerFaction.get(u);
                    if (fid == null) continue;
                    Member m = byId.get(fid).getMember(u);
                    if (m != null) m.getPermissionOverrides().put(rs.getString("node"), rs.getInt("allowed") != 0);
                }
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM relations")) {
                while (rs.next()) {
                    long fid = rs.getLong("faction_id");
                    long oid = rs.getLong("other_id");
                    RelationType t = RelationType.parse(rs.getString("type"));
                    if (t != RelationType.ALLY) continue;                // si salvano solo le alleanze
                    if (!byId.containsKey(fid) || !byId.containsKey(oid)) continue;   // ignora orfani
                    wishes.computeIfAbsent(fid, k -> new HashMap<>()).put(oid, t);
                }
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM faction_homes")) {
                while (rs.next()) {
                    long fid = rs.getLong("faction_id");
                    if (!byId.containsKey(fid)) continue;
                    homes.put(fid, new Home(rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"),
                            rs.getDouble("z"), rs.getFloat("yaw"), rs.getFloat("pitch")));
                }
            }
        }
        plugin.getLogger().info("Caricate " + byId.size() + " fazioni.");
    }

    // ------------------------------ QUERY --------------------------------
    public Faction getFaction(UUID player) {
        Long id = playerFaction.get(player);
        return id == null ? null : byId.get(id);
    }

    public Faction getByName(String name) {
        Long id = byName.get(name.toLowerCase(Locale.ROOT));
        return id == null ? null : byId.get(id);
    }

    public Collection<Faction> all() { return byId.values(); }

    /** Codice colore (tradotto) della relazione del lettore verso una fazione: member/ally/enemy/none.
     *  Senza fazione propria si e' nemici di tutti (nessuno stato neutro, coerente col resto del plugin). */
    public String relationColor(Faction viewer, Faction target) {
        String key;
        if (target == null) key = "none";
        else if (viewer == null) key = "enemy";
        else if (viewer.getId() == target.getId()) key = "member";
        else key = effectiveRelation(viewer.getId(), target.getId()) == RelationType.ALLY ? "ally" : "enemy";
        String def = switch (key) { case "member" -> "&a"; case "ally" -> "&d"; case "enemy" -> "&c"; default -> "&f"; };
        return com.teolo.magixfactions.util.Colors.translate(plugin.getConfig().getString("relations.colors." + key, def));
    }

    /** Grado effettivo del membro. */
    public Rank rankOf(Faction f, UUID u) {
        Member m = f.getMember(u);
        return m == null ? null : ranks.resolve(m.getRankId());
    }

    /** Permesso interno di fazione (leader = tutto; override membro > grado). */
    public boolean hasPerm(Faction f, UUID u, String node) {
        Member m = f.getMember(u);
        if (m == null) return false;
        if (m.isLeader()) return true;
        Boolean ov = m.getPermissionOverrides().get(node);
        if (ov != null) return ov;
        return ranks.resolve(m.getRankId()).has(node);
    }

    // ---------------------------- RELATIONS ------------------------------
    /** Relazione "desiderata" da {@code a} verso {@code b}: ALLY se ha richiesto/stretto alleanza, altrimenti ENEMY (default). */
    public RelationType getRelationWish(long a, long b) {
        Map<Long, RelationType> m = wishes.get(a);
        RelationType t = m == null ? null : m.get(b);
        return t == null ? RelationType.ENEMY : t;
    }

    /**
     * Relazione EFFETTIVA fra due fazioni:
     * - alleate solo se ENTRAMBI i lati desiderano ALLY;
     * - altrimenti NEMICHE (e' il default: ogni fazione e' nemica di tutte).
     */
    public RelationType effectiveRelation(long a, long b) {
        if (a == b) return RelationType.ALLY;
        if (getRelationWish(a, b) == RelationType.ALLY && getRelationWish(b, a) == RelationType.ALLY)
            return RelationType.ALLY;
        return RelationType.ENEMY;
    }

    /** Imposta la relazione desiderata da {@code a} verso {@code b}. Si salva solo ALLY; ENEMY = default (riga rimossa). */
    public void setRelationWish(long a, long b, RelationType type) {
        final boolean ally = (type == RelationType.ALLY);
        write("setRelationWish", c -> {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM relations WHERE faction_id=? AND other_id=?")) {
                del.setLong(1, a); del.setLong(2, b); del.executeUpdate();
            }
            if (ally) {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO relations (faction_id, other_id, type) VALUES (?,?,?)")) {
                    ins.setLong(1, a); ins.setLong(2, b); ins.setString(3, RelationType.ALLY.name());
                    ins.executeUpdate();
                }
            }
        });
        if (type == RelationType.ALLY) {
            wishes.computeIfAbsent(a, k -> new HashMap<>()).put(b, type);
        } else {
            Map<Long, RelationType> m = wishes.get(a);
            if (m != null) { m.remove(b); if (m.isEmpty()) wishes.remove(a); }
        }
    }

    /** Fazioni effettivamente alleate (mutuo) con {@code f}. */
    public List<Faction> alliesOf(Faction f) {
        return relatedTo(f, RelationType.ALLY);
    }

    /** Fazioni effettivamente nemiche di {@code f}. */
    public List<Faction> enemiesOf(Faction f) {
        return relatedTo(f, RelationType.ENEMY);
    }

    private List<Faction> relatedTo(Faction f, RelationType type) {
        List<Faction> out = new ArrayList<>();
        for (Faction o : byId.values()) {
            if (o.getId() == f.getId()) continue;
            if (effectiveRelation(f.getId(), o.getId()) == type) out.add(o);
        }
        return out;
    }

    /** Fazioni a cui {@code f} ha chiesto alleanza ma che non hanno ancora confermato. */
    public List<Faction> pendingAllyRequestsOf(Faction f) {
        List<Faction> out = new ArrayList<>();
        for (Faction o : byId.values()) {
            if (o.getId() == f.getId()) continue;
            if (effectiveRelation(f.getId(), o.getId()) == RelationType.ALLY) continue; // gia' alleati
            if (getRelationWish(f.getId(), o.getId()) == RelationType.ALLY) out.add(o);
        }
        return out;
    }

    /** Fazioni che hanno chiesto alleanza a {@code f} e attendono conferma. */
    public List<Faction> incomingAllyRequestsOf(Faction f) {
        List<Faction> out = new ArrayList<>();
        for (Faction o : byId.values()) {
            if (o.getId() == f.getId()) continue;
            if (effectiveRelation(f.getId(), o.getId()) == RelationType.ALLY) continue;
            if (getRelationWish(o.getId(), f.getId()) == RelationType.ALLY) out.add(o);
        }
        return out;
    }

    // ----------------------------- CREATE --------------------------------
    public Faction createFaction(String name, String tag, UUID leader) throws SQLException {
        long now = System.currentTimeMillis();
        String defDesc = plugin.getConfig().getString("faction-description.default", "");
        long id;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO factions (name, tag, description, leader, power, created_at, member_limit_bonus, " +
                     "score_since, score_sampled_at) VALUES (?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, tag);
            ps.setString(3, defDesc);
            ps.setString(4, leader.toString());
            ps.setDouble(5, 0);
            ps.setLong(6, now);
            ps.setLong(7, 0);
            // La finestra delle medie (giacenza/potenza per il punteggio) parte dalla creazione.
            ps.setLong(8, now);
            ps.setLong(9, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
        }
        Faction f = new Faction(id, name, tag, leader, now, 0);
        f.setDescription(defDesc);
        f.setScoreSince(now);
        f.setScoreSampledAt(now);
        byId.put(id, f);
        byName.put(name.toLowerCase(Locale.ROOT), id);
        addMemberInternal(f, leader, Rank.LEADER_ID, now);
        return f;
    }

    // ----------------------------- MEMBERS -------------------------------
    public void addMember(Faction f, UUID u, String rankId) {
        addMemberInternal(f, u, rankId, System.currentTimeMillis());
    }

    private void addMemberInternal(Faction f, UUID u, String rankId, long now) {
        final String us = u.toString(); final long fid = f.getId();
        write("addMember", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO faction_members (uuid, faction_id, rank, rank_since, joined_at) VALUES (?,?,?,?,?)")) {
                ps.setString(1, us);
                ps.setLong(2, fid);
                ps.setString(3, rankId);
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
        });
        f.getMembers().put(u, new Member(u, rankId, now, now));
        playerFaction.put(u, f.getId());
        if (decayManager != null) decayManager.checkNow(f); // invito: puo' azzerare il decadimento
    }

    public void removeMember(Faction f, UUID u) {
        final String us = u.toString();
        write("removeMember", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM faction_members WHERE uuid=?")) {
                ps.setString(1, us);
                ps.executeUpdate();
            }
        });
        f.getMembers().remove(u);
        playerFaction.remove(u);
        if (decayManager != null) decayManager.checkNow(f); // uscita/kick: fa partire il decadimento all'istante
    }

    public void setRank(Faction f, UUID u, String rankId) {
        long now = System.currentTimeMillis();
        final String us = u.toString();
        write("setRank", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE faction_members SET rank=?, rank_since=? WHERE uuid=?")) {
                ps.setString(1, rankId);
                ps.setLong(2, now);
                ps.setString(3, us);
                ps.executeUpdate();
            }
        });
        Member m = f.getMember(u);
        if (m != null) m.setRank(rankId, now);
    }

    public void setDescription(Faction f, String description) {
        final long fid = f.getId();
        write("setDescription", c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE factions SET description=? WHERE id=?")) {
                ps.setString(1, description);
                ps.setLong(2, fid);
                ps.executeUpdate();
            }
        });
        f.setDescription(description);
    }

    /** Imposta il saldo della banca di fazione: cache subito, colonna factions.bank in async (stesso
     *  pattern di ogni altra scrittura). Il clamp a >=0 e' nel modello ({@link Faction#setBank}). */
    public void setBank(Faction f, double value) {
        f.setBank(value);
        final long fid = f.getId(); final double v = f.getBank();
        write("setBank", c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE factions SET bank=? WHERE id=?")) {
                ps.setDouble(1, v);
                ps.setLong(2, fid);
                ps.executeUpdate();
            }
        });
    }

    /** Salva su DB il campione delle medie nel tempo (integrali banca/potenza + finestra) di una fazione,
     *  in async come ogni altra scrittura. Lo chiama il campionatore periodico di {@link com.teolo.magixfactions.manage.ScoreManager}. */
    public void saveScoreSample(Faction f) {
        final long fid = f.getId();
        final double bankAcc = f.getBankAvgAccum(), powAcc = f.getPowerAvgAccum(), sc = f.getScore();
        final long sampledAt = f.getScoreSampledAt(), since = f.getScoreSince();
        final String detail = f.getScoreDetail();
        write("saveScoreSample", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE factions SET bank_avg_accum=?, power_avg_accum=?, score_sampled_at=?, score_since=?, score=?, score_detail=? WHERE id=?")) {
                ps.setDouble(1, bankAcc);
                ps.setDouble(2, powAcc);
                ps.setLong(3, sampledAt);
                ps.setLong(4, since);
                ps.setDouble(5, sc);
                ps.setString(6, detail);
                ps.setLong(7, fid);
                ps.executeUpdate();
            }
        });
    }

    public Home getHome(long factionId) { return homes.get(factionId); }

    /** Chiave del chunk della home ("world:cx:cz") o null se non impostata. */
    public String homeChunkKey(long factionId) {
        Home h = homes.get(factionId);
        return h == null ? null : h.chunkKey();
    }

    public void setHome(Faction f, Location loc) {
        Home h = new Home(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        final long fid = f.getId();
        write("setHome", c -> {
            try (PreparedStatement d = c.prepareStatement("DELETE FROM faction_homes WHERE faction_id=?")) {
                d.setLong(1, fid); d.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO faction_homes (faction_id, world, x, y, z, yaw, pitch) VALUES (?,?,?,?,?,?,?)")) {
                ins.setLong(1, fid); ins.setString(2, h.world);
                ins.setDouble(3, h.x); ins.setDouble(4, h.y); ins.setDouble(5, h.z);
                ins.setFloat(6, h.yaw); ins.setFloat(7, h.pitch);
                ins.executeUpdate();
            }
        });
        homes.put(f.getId(), h);
    }

    /** Rimuove la home della fazione (nessun errore se non era impostata). */
    public void unsetHome(Faction f) {
        final long fid = f.getId();
        write("unsetHome", c -> {
            try (PreparedStatement d = c.prepareStatement("DELETE FROM faction_homes WHERE faction_id=?")) {
                d.setLong(1, fid); d.executeUpdate();
            }
        });
        homes.remove(fid);
    }

    public void setLeader(Faction f, UUID newLeader) {
        final long fid = f.getId(); final String us = newLeader.toString();
        write("setLeader", c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE factions SET leader=? WHERE id=?")) {
                ps.setString(1, us);
                ps.setLong(2, fid);
                ps.executeUpdate();
            }
        });
        f.setLeader(newLeader);
    }

    // ----------------------------- DISBAND -------------------------------
    public void disband(Faction f) {
        final long fid = f.getId();
        write("disband", c -> {
            for (String sql : new String[]{
                    "DELETE FROM faction_members WHERE faction_id=?",
                    "DELETE FROM claims WHERE faction_id=?",            // territori -> neutrali
                    "DELETE FROM relations WHERE faction_id=? OR other_id=?",
                    "DELETE FROM faction_homes WHERE faction_id=?",
                    "DELETE FROM overclaim_timers WHERE faction_id=?",
                    "DELETE FROM factions WHERE id=?"}) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setLong(1, fid);
                    if (sql.contains("other_id")) ps.setLong(2, fid);
                    ps.executeUpdate();
                }
            }
        });
        for (UUID u : new ArrayList<>(f.getMembers().keySet())) playerFaction.remove(u);
        byName.remove(f.getName().toLowerCase(Locale.ROOT));
        byId.remove(f.getId());
        homes.remove(f.getId());
        if (claimManager != null) claimManager.onFactionRemoved(f.getId()); // territori -> neutrali (cache)
        // pulisci la cache relazioni in entrambe le direzioni
        wishes.remove(f.getId());
        for (Map<Long, RelationType> m : wishes.values()) m.remove(f.getId());
    }

    /**
     * Gestisce l'uscita di un giocatore. Restituisce un esito testuale per il chiamante.
     * Se esce il leader: se e' l'unico -> scioglie; altrimenti passa al successore.
     */
    public LeaveResult handleLeave(Faction f, UUID leaver) throws SQLException {
        boolean isLeader = leaver.equals(f.getLeader());
        if (isLeader && f.size() <= 1) {
            disband(f);
            return LeaveResult.DISBANDED;
        }
        if (isLeader) {
            UUID successor = pickSuccessor(f, leaver);
            removeMember(f, leaver);
            setLeader(f, successor);
            setRank(f, successor, Rank.LEADER_ID);
            return LeaveResult.SUCCESSION;
        }
        removeMember(f, leaver);
        return LeaveResult.LEFT;
    }

    public enum LeaveResult { LEFT, SUCCESSION, DISBANDED }

    /** Successore: grado piu' alto; a parita', chi e' in quel grado da piu' tempo (rank_since minore). */
    public UUID pickSuccessor(Faction f, UUID exclude) {
        UUID best = null;
        int bestOrder = -1;
        long bestSince = Long.MAX_VALUE;
        for (Member m : f.getMembers().values()) {
            if (m.getUuid().equals(exclude)) continue;
            int order = ranks.rankOrder(m.getRankId());
            if (order > bestOrder || (order == bestOrder && m.getRankSince() < bestSince)) {
                best = m.getUuid(); bestOrder = order; bestSince = m.getRankSince();
            }
        }
        return best;
    }

    // -------------------------- MAX MEMBERS ------------------------------
    public int effectiveMaxMembers(Faction f) {
        int base = plugin.getConfig().getInt("members.base", 5);
        long bonus = f.getMemberLimitBonus();
        int eff = (int) (base + bonus);
        Player leader = f.getLeader() == null ? null : Bukkit.getPlayer(f.getLeader());
        if (leader != null) {
            int vip = 0;
            for (PermissionAttachmentInfo pi : leader.getEffectivePermissions()) {
                String n = pi.getPermission();
                if (pi.getValue() && n.startsWith("magixfactions.members.")) {
                    try { vip = Math.max(vip, Integer.parseInt(n.substring("magixfactions.members.".length()))); }
                    catch (NumberFormatException ignored) {}
                }
            }
            eff = Math.max(eff, vip);
        }
        return eff;
    }

    private static UUID uuid(String s) { return s == null ? null : UUID.fromString(s); }
}
