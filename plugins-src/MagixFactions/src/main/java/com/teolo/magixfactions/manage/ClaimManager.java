package com.teolo.magixfactions.manage;

import com.teolo.magixfactions.db.Database;
import com.teolo.magixfactions.db.DbExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Modulo 3 - Territori (claim). Un territorio = un chunk (world + chunk_x + chunk_z) di proprieta'
 * di una fazione. Tabella {@code claims}. Tiene una cache in memoria per lookup rapidi.
 */
public final class ClaimManager {

    private final JavaPlugin plugin;
    private final Database db;
    private final DbExecutor dbExec;
    private final Map<String, Long> owners = new HashMap<>();   // "world:x:z" -> faction_id
    private final Map<Long, Integer> counts = new HashMap<>();  // faction_id -> n. territori
    // Prezzo PAGATO per ogni chunk al momento del claim (colonna claims.paid): serve al rimborso di
    // /f unclaim, che restituisce una % di QUANTO SPESO ALLORA (i prezzi incrementali cambiano nel
    // tempo, non si puo' ricalcolare). Claims pre-feature: assenti dalla mappa = pagato 0 = rimborso 0.
    private final Map<String, Double> paid = new HashMap<>();

    public ClaimManager(JavaPlugin plugin, Database db, DbExecutor dbExec) {
        this.plugin = plugin; this.db = db; this.dbExec = dbExec;
    }

    private static String key(String world, int x, int z) { return world + ":" + x + ":" + z; }

    public void loadAll() throws SQLException {
        owners.clear(); counts.clear();
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT world, chunk_x, chunk_z, faction_id, paid FROM claims")) {
            while (rs.next()) {
                long fid = rs.getLong("faction_id");
                String k = key(rs.getString("world"), rs.getInt("chunk_x"), rs.getInt("chunk_z"));
                owners.put(k, fid);
                double pd = rs.getDouble("paid");
                if (pd > 0) paid.put(k, pd);
                counts.merge(fid, 1, Integer::sum);
            }
        }
    }

    // Contatore di versione dei territori: bumpa a OGNI mutazione (claim/unclaim/decay/disband). Le mappe
    // lo usano per sapere se il layer territori e' cambiato dall'ultimo render e RIUSARE il frame
    // memoizzato invece di ricalcolare 16k pixel a ogni tick (vedi MapService/FactionMapRenderer).
    private int version = 0;

    /** Versione corrente dei territori (cambia a ogni mutazione). Solo main thread. */
    public int version() { return version; }

    private void bump() { version++; }

    /** Fazione proprietaria del chunk, o null se neutrale. */
    public Long owner(String world, int x, int z) { return owners.get(key(world, x, z)); }

    /** Numero di territori posseduti da una fazione. */
    public int count(long factionId) { return counts.getOrDefault(factionId, 0); }

    /** Tetto territori = floor(maxPowerFazione * claims.max-percent / 100). */
    public int maxClaims(int factionMaxPower) {
        double pct = plugin.getConfig().getDouble("claims.max-percent", 20);
        return (int) Math.floor(Math.max(0, factionMaxPower) * pct / 100.0);
    }

    /**
     * Il chunk (di proprieta' di {@code enemyId}) e' "esterno" se almeno un vicino ortogonale
     * NON appartiene alla stessa fazione. Serve a impedire l'overclaim dall'interno.
     */
    public boolean isBorderOf(long enemyId, String world, int x, int z) {
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : d) {
            Long n = owners.get(key(world, x + o[0], z + o[1]));
            if (n == null || n != enemyId) return true;
        }
        return false;
    }

    /** Assegna il chunk a {@code factionId} (nuovo claim o overclaim), registrando {@code paidAmount}
     *  (quanto la fazione ha pagato ORA per questo chunk: base del futuro rimborso di /f unclaim).
     *  Aggiorna cache (subito) e DB (async). */
    public void setOwner(String world, int x, int z, long factionId, double paidAmount) {
        Long prev = owners.get(key(world, x, z));
        final boolean insert = (prev == null);
        dbExec.submit(() -> {
            try (Connection c = db.getConnection()) {
                if (insert) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO claims (world, chunk_x, chunk_z, faction_id, paid) VALUES (?,?,?,?,?)")) {
                        ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, z); ps.setLong(4, factionId);
                        ps.setDouble(5, paidAmount);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE claims SET faction_id=?, paid=? WHERE world=? AND chunk_x=? AND chunk_z=?")) {
                        ps.setLong(1, factionId); ps.setDouble(2, paidAmount);
                        ps.setString(3, world); ps.setInt(4, x); ps.setInt(5, z);
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException e) { plugin.getLogger().warning("[Claims] setOwner: " + e.getMessage()); }
        });
        if (prev != null) counts.merge(prev, -1, Integer::sum);
        String k = key(world, x, z);
        owners.put(k, factionId);
        if (paidAmount > 0) paid.put(k, paidAmount); else paid.remove(k);
        counts.merge(factionId, 1, Integer::sum);
        bump();
    }

    /** Quanto fu PAGATO per il chunk al momento del claim (0 se gratis o pre-feature). */
    public double paidAt(String world, int x, int z) {
        return paid.getOrDefault(key(world, x, z), 0.0);
    }

    /** Rimborso per un territorio rilasciato/perso: {@code paidAmount} x claims.unclaim-refund-percent
     *  (default 25, clamp 0-100), troncato ai centesimi. Unica fonte della regola: usata da /f unclaim,
     *  /f unclaimall E dal decadimento da sovraccarico. */
    public double refundFor(double paidAmount) {
        double pct = Math.max(0, Math.min(100, plugin.getConfig().getDouble("claims.unclaim-refund-percent", 25)));
        return Math.floor(paidAmount * pct) / 100.0;
    }

    /** Somma di quanto PAGATO per tutti i territori della fazione (base del rimborso di /f unclaimall). */
    public double paidTotal(long factionId) {
        double sum = 0;
        for (Map.Entry<String, Long> e : owners.entrySet())
            if (e.getValue() == factionId) sum += paid.getOrDefault(e.getKey(), 0.0);
        return sum;
    }

    /**
     * Rimuove il territorio PIU' ESTERNO della fazione: quello piu' distante dal "cuore".
     * Il cuore e' il chunk della HOME ({@code homeKey}) se impostata e ancora posseduta; altrimenti
     * il baricentro dei chunk della fazione. Il chunk della home non viene MAI rimosso.
     * Cosi' con piu' gruppi si consuma prima quello piu' lontano dalla home (dai bordi verso l'interno),
     * restringendo il territorio verso la home. DB + cache.
     * @return -1 se NON e' stato rimosso nulla; altrimenti quanto era stato PAGATO per il chunk rimosso
     *         (0 se fu gratis/pre-feature) — il chiamante lo usa per il rimborso alla banca.
     */
    public double removeOneClaim(long factionId, String homeKey) {
        List<String> keys = new ArrayList<>();
        long sumX = 0, sumZ = 0;
        for (Map.Entry<String, Long> e : owners.entrySet()) {
            if (e.getValue() != factionId) continue;
            keys.add(e.getKey());
            int[] co = coords(e.getKey());
            sumX += co[0]; sumZ += co[1];
        }
        if (keys.isEmpty()) return -1;

        // Centro = la home (cuore reale) se impostata e posseduta, altrimenti il baricentro.
        double cx, cz;
        if (homeKey != null && owners.getOrDefault(homeKey, -1L) == factionId) {
            int[] hc = coords(homeKey); cx = hc[0]; cz = hc[1];
        } else {
            cx = (double) sumX / keys.size(); cz = (double) sumZ / keys.size();
        }

        String far = null;
        double best = -1;
        for (String k : keys) {
            if (k.equals(homeKey)) continue;           // la home non si rimuove mai
            int[] co = coords(k);
            double d = (co[0] - cx) * (co[0] - cx) + (co[1] - cz) * (co[1] - cz);
            if (d > best) { best = d; far = k; }
        }
        if (far == null) return -1;                    // resta solo la home: niente da rimuovere

        int zi = far.lastIndexOf(':');
        int xi = far.lastIndexOf(':', zi - 1);
        final String world = far.substring(0, xi);
        final int x = Integer.parseInt(far.substring(xi + 1, zi));
        final int z = Integer.parseInt(far.substring(zi + 1));
        dbExec.submit(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("DELETE FROM claims WHERE world=? AND chunk_x=? AND chunk_z=?")) {
                ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, z);
                ps.executeUpdate();
            } catch (SQLException e) { plugin.getLogger().warning("[Claims] removeOneClaim: " + e.getMessage()); }
        });
        owners.remove(far);
        Double lostPaid = paid.remove(far); // anche la perdita da sovraccarico RIMBORSA (scelta utente)
        counts.merge(factionId, -1, Integer::sum);
        bump();
        return lostPaid != null ? lostPaid : 0.0;
    }

    /** Estrae {chunk_x, chunk_z} dalla chiave "world:x:z". */
    private static int[] coords(String key) {
        int zi = key.lastIndexOf(':');
        int xi = key.lastIndexOf(':', zi - 1);
        return new int[]{ Integer.parseInt(key.substring(xi + 1, zi)), Integer.parseInt(key.substring(zi + 1)) };
    }

    /**
     * Rilascia (rende neutrale) il chunk indicato, se posseduto. Aggiorna cache (subito) e DB (async).
     * @return l'id della fazione che lo possedeva, o null se era gia' neutrale (niente da fare).
     */
    public Long removeClaim(String world, int x, int z) {
        String k = key(world, x, z);
        Long owner = owners.get(k);
        if (owner == null) return null;
        dbExec.submit(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM claims WHERE world=? AND chunk_x=? AND chunk_z=?")) {
                ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, z);
                ps.executeUpdate();
            } catch (SQLException e) { plugin.getLogger().warning("[Claims] removeClaim: " + e.getMessage()); }
        });
        owners.remove(k);
        paid.remove(k);
        counts.merge(owner, -1, Integer::sum);
        bump();
        return owner;
    }

    /**
     * Rilascia (rende neutrali) TUTTI i territori di una fazione. Aggiorna cache (subito) e DB (async).
     * @return quanti territori sono stati rimossi.
     */
    public int removeAll(long factionId) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Long> e : owners.entrySet()) {
            if (e.getValue() == factionId) keys.add(e.getKey());
        }
        if (keys.isEmpty()) return 0;
        dbExec.submit(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("DELETE FROM claims WHERE faction_id=?")) {
                ps.setLong(1, factionId);
                ps.executeUpdate();
            } catch (SQLException e) { plugin.getLogger().warning("[Claims] removeAll: " + e.getMessage()); }
        });
        for (String k : keys) { owners.remove(k); paid.remove(k); }
        counts.remove(factionId);
        bump();
        return keys.size();
    }

    /** Rimuove dalla cache tutti i territori di una fazione (il DB e' gia' ripulito dal disband). */
    public void onFactionRemoved(long factionId) {
        owners.entrySet().removeIf(e -> {
            if (e.getValue() == factionId) { paid.remove(e.getKey()); return true; }
            return false;
        });
        counts.remove(factionId);
        bump();
    }
}
