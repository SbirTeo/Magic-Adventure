package com.teolo.magixfactions.manage;

import com.teolo.magixfactions.db.Database;
import com.teolo.magixfactions.db.DbExecutor;
import com.teolo.magixfactions.lang.Messages;
import com.teolo.magixfactions.model.Faction;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Modulo 3 - Decadimento territori per SOVRACCARICO ("decay").
 *
 * <p><b>Da non confondere con l'"overclaim" (conquista di guerra)</b>: quello e' l'ATTO con cui una
 * fazione strappa un chunk di confine a un NEMICO gia' indebolito (vedi {@code FCommand.claim}, messaggi
 * {@code claim.overclaim-*}). Questo, invece, e' la PENALITA' AUTOMATICA e interna: se una fazione
 * possiede piu' territori del suo tetto ({@code cap = maxPower x claims.max-percent%}), i membri online
 * ricevono un titolo + suono di pericolo; trascorse {@code decay.grace-hours} (default 48h) la fazione
 * perde 1 territorio ogni {@code decay.loss-interval-hours} (default 24h) finche' non rientra nel cap
 * (es. invitando un altro giocatore).
 *
 * <p>Le due meccaniche possono sfiorarsi (una fazione sovraccarica e' spesso anche raidabile), ma hanno
 * SOGLIE diverse: la raidabilita' guarda la Potenza ATTUALE ({@code power < territori}), il decadimento
 * guarda il TETTO ({@code territori > maxPower x 20%}). Nomi separati apposta per non confonderle.
 *
 * <p>Stato persistente su {@code overclaim_timers} (nome storico della tabella mantenuto: rinominarla
 * imporrebbe una migrazione DB su SQLite+MariaDB, e non e' mai visibile ai giocatori). Config e messaggi
 * usano il prefisso {@code decay.*}; per retrocompatibilita' si leggono ancora i vecchi {@code overclaim.*}
 * come fallback, cosi' un config non ancora migrato continua a funzionare.
 */
public final class DecayManager {

    private static final class Timer {
        long since;      // quando la fazione e' andata sopra il cap
        long lastLoss;   // ultima perdita di territorio (0 = nessuna ancora)
        Timer(long since, long lastLoss) { this.since = since; this.lastLoss = lastLoss; }
    }

    private final JavaPlugin plugin;
    private final Database db;
    private final DbExecutor dbExec;
    private final FactionManager fm;
    private final ClaimManager claims;
    private final PowerManager power;
    private final Messages M;
    private final Map<Long, Timer> timers = new HashMap<>();

    public DecayManager(JavaPlugin plugin, Database db, DbExecutor dbExec, FactionManager fm,
                        ClaimManager claims, PowerManager power, Messages messages) {
        this.plugin = plugin; this.db = db; this.dbExec = dbExec; this.fm = fm; this.claims = claims;
        this.power = power; this.M = messages;
    }

    // ---- lettura config con retrocompatibilita' (decay.* nuovo, overclaim.* vecchio come fallback) ----
    private int cfgInt(String key, int def) {
        return plugin.getConfig().getInt("decay." + key, plugin.getConfig().getInt("overclaim." + key, def));
    }
    private double cfgDouble(String key, double def) {
        return plugin.getConfig().getDouble("decay." + key, plugin.getConfig().getDouble("overclaim." + key, def));
    }
    private String cfgString(String key, String def) {
        return plugin.getConfig().getString("decay." + key, plugin.getConfig().getString("overclaim." + key, def));
    }

    public int warnIntervalSeconds() { return Math.max(1, cfgInt("warn-interval-seconds", 60)); }
    private long graceMs() { return (long) (cfgDouble("grace-hours", 48) * 3600_000L); }
    private long lossMs()  { return (long) (cfgDouble("loss-interval-hours", 24) * 3600_000L); }

    public void loadAll() throws SQLException {
        timers.clear();
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT faction_id, since, last_loss FROM overclaim_timers")) {
            while (rs.next()) timers.put(rs.getLong("faction_id"), new Timer(rs.getLong("since"), rs.getLong("last_loss")));
        }
    }

    /**
     * Controllo immediato per una fazione: da chiamare quando cambia il numero di membri
     * (uscita/kick/join), cosi' il timer del decadimento parte ESATTAMENTE al momento dell'evento
     * o si azzera subito se si rientra nel cap.
     */
    public void checkNow(Faction f) {
        int owned = claims.count(f.getId());
        int cap = claims.maxClaims(power.factionMaxPower(f));
        if (owned > cap) {
            if (!timers.containsKey(f.getId())) {
                Timer t = new Timer(System.currentTimeMillis(), 0);
                timers.put(f.getId(), t);
                save(f.getId(), t);
            }
        } else if (timers.remove(f.getId()) != null) {
            delete(f.getId());
        }
    }

    /** Eseguito periodicamente (ogni warn-interval): avvisa e, se scaduti i tempi, fa perdere territori. */
    public void tick() {
        long now = System.currentTimeMillis();
        long grace = graceMs(), loss = lossMs();
        for (Faction f : new ArrayList<>(fm.all())) {
            int owned = claims.count(f.getId());
            int cap = claims.maxClaims(power.factionMaxPower(f));
            Timer t = timers.get(f.getId());

            if (owned > cap) {
                if (t == null) { t = new Timer(now, 0); timers.put(f.getId(), t); save(f.getId(), t); }
                warn(f, owned, cap);

                long firstLoss = t.since + grace;
                long nextLoss = (t.lastLoss == 0) ? firstLoss : t.lastLoss + loss;
                if (now >= nextLoss) {
                    double lostPaid = claims.removeOneClaim(f.getId(), fm.homeChunkKey(f.getId()));
                    if (lostPaid >= 0) {
                        t.lastLoss = now; save(f.getId(), t);
                        broadcast(f, M.get("decay.lost"));
                        // Anche il territorio perso per sovraccarico RIMBORSA la % configurata di quanto
                        // fu pagato (scelta utente 2026-07-17): stesso motore di /f unclaim, alla banca.
                        double refund = claims.refundFor(lostPaid);
                        if (refund > 0) {
                            fm.setBank(f, f.getBank() + refund);
                            broadcast(f, M.get("decay.lost-refund",
                                    "refund", com.teolo.magixfactions.hook.Econ.format(refund),
                                    "bank", com.teolo.magixfactions.hook.Econ.format(f.getBank())));
                        }
                        if (claims.count(f.getId()) <= cap) { timers.remove(f.getId()); delete(f.getId()); }
                    }
                }
            } else if (t != null) {
                timers.remove(f.getId());
                delete(f.getId());
            }
        }
    }

    // ------------------------------ avvisi -------------------------------
    private void warn(Faction f, int owned, int cap) {
        String title = color(M.get("decay.warn-title"));
        String sub = color(M.get("decay.warn-subtitle")
                .replace("{owned}", String.valueOf(owned)).replace("{cap}", String.valueOf(cap)));
        String sound = cfgString("sound", "entity.wither.spawn");
        float vol = (float) cfgDouble("sound-volume", 1.0);
        float pitch = (float) cfgDouble("sound-pitch", 1.0);
        for (UUID u : f.getMembers().keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.sendTitle(title, sub, 5, 50, 10);
            if (sound != null && !sound.isEmpty()) {
                try { p.playSound(p.getLocation(), sound, vol, pitch); } catch (Exception ignored) {}
            }
        }
    }

    private void broadcast(Faction f, String message) {
        for (UUID u : f.getMembers().keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(M.prefix() + message);
        }
    }

    private static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }

    // --------------------------- persistenza -----------------------------
    private void save(long fid, Timer t) {
        final long since = t.since, lastLoss = t.lastLoss; // snapshot: il Timer e' mutato sul main thread
        dbExec.submit(() -> {
            try (Connection c = db.getConnection()) {
                try (PreparedStatement d = c.prepareStatement("DELETE FROM overclaim_timers WHERE faction_id=?")) {
                    d.setLong(1, fid); d.executeUpdate();
                }
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO overclaim_timers (faction_id, since, last_loss) VALUES (?,?,?)")) {
                    ins.setLong(1, fid); ins.setLong(2, since); ins.setLong(3, lastLoss); ins.executeUpdate();
                }
            } catch (SQLException e) { plugin.getLogger().warning("[Decay] save: " + e.getMessage()); }
        });
    }

    private void delete(long fid) {
        dbExec.submit(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement d = c.prepareStatement("DELETE FROM overclaim_timers WHERE faction_id=?")) {
                d.setLong(1, fid); d.executeUpdate();
            } catch (SQLException e) { plugin.getLogger().warning("[Decay] delete: " + e.getMessage()); }
        });
    }
}
