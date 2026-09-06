package com.teolo.magixfactions.manage;

import com.teolo.magixfactions.db.Database;
import com.teolo.magixfactions.db.DbExecutor;
import com.teolo.magixfactions.hook.Econ;
import com.teolo.magixfactions.model.Faction;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Statistiche personali dei giocatori per le CLASSIFICHE del sito (e voci di punteggio di fazione):
 * <ul>
 *   <li><b>uccisioni</b> e <b>morti</b> PvP valide (l'anti fake-kill sta in {@code CombatListener}: qui
 *       arrivano gia' filtrate), da cui il K/D;</li>
 *   <li><b>tempo di gioco</b> totale (secondi online);</li>
 *   <li><b>giacenza media</b> personale, misurata SOLO sul tempo online con lo stesso metodo dell'integrale
 *       della banca di fazione ({@link ScoreManager}): un integrale Σ(saldo × secondi) diviso i secondi
 *       giocati. Cosi' parcheggiare soldi da offline non gonfia la media.</li>
 * </ul>
 * Tutto vive sulle colonne aggiuntive della tabella {@code players} (la riga la crea {@link PowerManager}):
 * qui si fanno solo UPDATE, mai INSERT, per non entrare in conflitto con quella. Cache in memoria +
 * scrittura async serializzata, stesso pattern degli altri manager.
 */
public final class PlayerStatsManager {

    /** Stato in cache di un giocatore (rispecchia le colonne statistiche di players). */
    private static final class PS {
        long kills, deaths, playSeconds;
        double moneyAvgAccum;
        long lastSampledAt;   // ultimo istante (ms) campionato mentre online; 0 = non in corso (offline)
        PS(long kills, long deaths, long playSeconds, double moneyAvgAccum) {
            this.kills = kills; this.deaths = deaths; this.playSeconds = playSeconds; this.moneyAvgAccum = moneyAvgAccum;
        }
    }

    private final JavaPlugin plugin;
    private final Database db;
    private final DbExecutor dbExec;
    private final Map<UUID, PS> cache = new HashMap<>();

    public PlayerStatsManager(JavaPlugin plugin, Database db, DbExecutor dbExec) {
        this.plugin = plugin; this.db = db; this.dbExec = dbExec;
    }

    public void loadAll() throws SQLException {
        cache.clear();
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, kills, deaths, play_seconds, money_avg_accum FROM players")) {
            while (rs.next()) {
                UUID u = UUID.fromString(rs.getString("uuid"));
                cache.put(u, new PS(rs.getLong("kills"), rs.getLong("deaths"),
                        rs.getLong("play_seconds"), rs.getDouble("money_avg_accum")));
            }
        }
    }

    private PS ensure(UUID u) {
        return cache.computeIfAbsent(u, k -> new PS(0, 0, 0, 0));
    }

    // ------------------------------ QUERY --------------------------------
    public long getKills(UUID u) { PS ps = cache.get(u); return ps == null ? 0 : ps.kills; }
    public long getDeaths(UUID u) { PS ps = cache.get(u); return ps == null ? 0 : ps.deaths; }
    public long getPlaySeconds(UUID u) { PS ps = cache.get(u); return ps == null ? 0 : ps.playSeconds; }

    /** Giacenza media personale: integrale Σ(saldo × secondi) / secondi giocati (0 se non ha ancora giocato). */
    public double averageMoney(UUID u) {
        PS ps = cache.get(u);
        if (ps == null || ps.playSeconds <= 0) return 0;
        return ps.moneyAvgAccum / ps.playSeconds;
    }

    /** Rapporto uccisioni/morti; con 0 morti vale il numero di uccisioni (evita la divisione per zero). */
    public double kd(UUID u) {
        PS ps = cache.get(u);
        if (ps == null) return 0;
        return ps.deaths <= 0 ? ps.kills : (double) ps.kills / ps.deaths;
    }

    /** Uccisioni totali di una fazione = somma di quelle dei suoi membri (come la Potenza). */
    public long factionKills(Faction f) {
        long sum = 0;
        for (UUID u : f.getMembers().keySet()) sum += getKills(u);
        return sum;
    }

    /** Morti totali di una fazione = somma di quelle dei suoi membri. */
    public long factionDeaths(Faction f) {
        long sum = 0;
        for (UUID u : f.getMembers().keySet()) sum += getDeaths(u);
        return sum;
    }

    // ------------------------------ EVENTI -------------------------------
    /** Registra un'uccisione PvP VALIDA (gia' filtrata dall'anti fake-kill). */
    public void recordKill(UUID killer) { ensure(killer).kills++; save(killer); }

    /** Registra una morte PvP VALIDA per la vittima (accoppiata a {@link #recordKill}). */
    public void recordDeath(UUID victim) { ensure(victim).deaths++; save(victim); }

    /** Ingresso: apre la finestra di campionamento del tempo giocato da adesso (l'offline non conta). */
    public void onJoin(UUID u) { ensure(u).lastSampledAt = System.currentTimeMillis(); }

    /** Uscita: chiude la finestra con un ultimo campione, poi la ferma. */
    public void onQuit(UUID u) {
        PS ps = cache.get(u);
        if (ps == null) return;
        accumulate(ps, u);
        ps.lastSampledAt = 0;
        save(u);
    }

    /**
     * Campiona TUTTI i giocatori online: aggiunge il tempo trascorso al totale giocato e il prodotto
     * saldo × secondi all'integrale della giacenza media. Lo chiama il task periodico (stesso passo del
     * campionatore del punteggio) e onDisable.
     */
    public void sampleAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PS ps = ensure(p.getUniqueId());
            if (accumulate(ps, p.getUniqueId())) save(p.getUniqueId());
        }
    }

    /** Accumula sul giocatore i secondi online (interi) trascorsi dall'ultimo campione e la giacenza.
     *  @return true se qualcosa e' cambiato (c'era del tempo da contare). */
    private boolean accumulate(PS ps, UUID u) {
        long now = System.currentTimeMillis();
        if (ps.lastSampledAt <= 0) { ps.lastSampledAt = now; return false; }   // prima volta: apri e basta
        long dtSec = (now - ps.lastSampledAt) / 1000L;
        if (dtSec <= 0) return false;
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        ps.playSeconds += dtSec;
        ps.moneyAvgAccum += Econ.balance(op) * dtSec;
        ps.lastSampledAt += dtSec * 1000L;   // conserva il resto sotto il secondo
        return true;
    }

    private void save(UUID u) {
        PS ps = cache.get(u);
        if (ps == null) return;
        final String us = u.toString();
        final long kills = ps.kills, deaths = ps.deaths, playSeconds = ps.playSeconds;
        final double moneyAcc = ps.moneyAvgAccum;
        dbExec.submit(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement psq = c.prepareStatement(
                         "UPDATE players SET kills=?, deaths=?, play_seconds=?, money_avg_accum=? WHERE uuid=?")) {
                psq.setLong(1, kills); psq.setLong(2, deaths); psq.setLong(3, playSeconds);
                psq.setDouble(4, moneyAcc); psq.setString(5, us);
                psq.executeUpdate();
            } catch (SQLException e) { plugin.getLogger().warning("[Stats] salvataggio: " + e.getMessage()); }
        });
    }
}
