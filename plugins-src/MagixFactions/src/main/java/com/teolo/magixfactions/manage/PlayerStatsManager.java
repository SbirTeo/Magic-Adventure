package com.teolo.magixfactions.manage;

import com.teolo.magixfactions.db.Database;
import com.teolo.magixfactions.db.DbExecutor;
import com.teolo.magixfactions.hook.Econ;
import com.teolo.magixfactions.hook.LuckPermsHook;
import com.teolo.magixfactions.model.Faction;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
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
import java.util.UUID;

/**
 * Statistiche personali dei giocatori per le CLASSIFICHE del sito (e voci di punteggio di fazione):
 * <ul>
 *   <li><b>uccisioni</b> e <b>morti</b> PvP valide (l'anti fake-kill sta in {@code CombatListener}: qui
 *       arrivano gia' filtrate), da cui il K/D;</li>
 *   <li><b>tempo di gioco</b>: il TOTALE REALE, letto dalla statistica vanilla di Minecraft
 *       ({@link Statistic#PLAY_ONE_MINUTE}, in tick), quindi include tutto lo storico del giocatore, non
 *       solo il tempo da quando esiste questa feature;</li>
 *   <li><b>giacenza media</b> personale, misurata SOLO sul tempo online DA QUANDO la feature e' attiva
 *       (non c'e' storico dei saldi): integrale Σ(saldo × secondi) / secondi online contati
 *       ({@code money_seconds}), lo stesso metodo della banca di fazione ({@link ScoreManager}).</li>
 * </ul>
 * Tutto vive sulle colonne aggiuntive della tabella {@code players} (la riga la crea {@link PowerManager}):
 * qui si fanno solo UPDATE, mai INSERT, per non entrare in conflitto con quella. Cache in memoria +
 * scrittura async serializzata, stesso pattern degli altri manager.
 */
public final class PlayerStatsManager {

    /**
     * Permesso che NASCONDE il giocatore dalle classifiche del sito (Top Giocatori). Pensato per lo staff:
     * di serie non ce l'ha nessuno (plugin.yml, default false), si assegna al grado mod in su via LuckPerms
     * (i gradi superiori ereditano da mod). Chi ce l'ha viene marcato {@code leaderboard_hidden=1} sulla
     * riga {@code players}, e il sito lo salta.
     */
    public static final String PERM_HIDE_LEADERBOARD = "magixfactions.leaderboard.hide";

    /** Stato in cache di un giocatore (rispecchia le colonne statistiche di players). */
    private static final class PS {
        long kills, deaths, playSeconds, moneySeconds;
        double moneyAvgAccum;
        boolean hidden;       // true = nascosto dalle classifiche del sito (staff col permesso)
        long lastSampledAt;   // ultimo istante (ms) campionato mentre online; 0 = non in corso (offline)
        PS(long kills, long deaths, long playSeconds, double moneyAvgAccum, long moneySeconds, boolean hidden) {
            this.kills = kills; this.deaths = deaths; this.playSeconds = playSeconds;
            this.moneyAvgAccum = moneyAvgAccum; this.moneySeconds = moneySeconds; this.hidden = hidden;
        }
    }

    private final JavaPlugin plugin;
    private final Database db;
    private final DbExecutor dbExec;
    private final Map<UUID, PS> cache = new HashMap<>();
    /** Permessi dei giocatori anche OFFLINE (per il flag "nascosto" dello staff scollegato). Puo' restare
     *  null: senza LuckPerms il flag si aggiorna solo per chi e' online (login/campionamento). */
    private LuckPermsHook luckPerms;

    public PlayerStatsManager(JavaPlugin plugin, Database db, DbExecutor dbExec) {
        this.plugin = plugin; this.db = db; this.dbExec = dbExec;
    }

    public void setLuckPerms(LuckPermsHook luckPerms) { this.luckPerms = luckPerms; }

    public void loadAll() throws SQLException {
        cache.clear();
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT uuid, kills, deaths, play_seconds, money_avg_accum, money_seconds, leaderboard_hidden FROM players")) {
            while (rs.next()) {
                UUID u = UUID.fromString(rs.getString("uuid"));
                cache.put(u, new PS(rs.getLong("kills"), rs.getLong("deaths"), rs.getLong("play_seconds"),
                        rs.getDouble("money_avg_accum"), rs.getLong("money_seconds"),
                        rs.getInt("leaderboard_hidden") != 0));
            }
        }
    }

    private PS ensure(UUID u) {
        return cache.computeIfAbsent(u, k -> new PS(0, 0, 0, 0, 0, false));
    }

    /** Tempo di gioco TOTALE del giocatore in secondi, dalla statistica vanilla (0 se non disponibile). */
    private long vanillaPlaySeconds(Player p) {
        try {
            return p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;   // la statistica è in tick (20/s)
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------ QUERY --------------------------------
    /** Il giocatore e' marcato come NASCOSTO dalle classifiche (staff col permesso)? Il flag e' persistito,
     *  quindi vale anche da OFFLINE: cosi' la sua fazione resta nascosta finche' lui ne fa parte, non solo
     *  mentre e' collegato. */
    public boolean isHidden(UUID u) { PS ps = cache.get(u); return ps != null && ps.hidden; }

    // --------------------- VISIBILITA' STAFF (anche OFFLINE) ---------------------
    // Il permesso magixfactions.leaderboard.hide di un giocatore ONLINE si legge dal Player (login e
    // campionamento). Ma lo staff spesso e' OFFLINE, e Bukkit non sa i suoi permessi: li conosce solo
    // LuckPerms (gruppi ereditati compresi, quindi mod->admin), leggendo dal proprio storage. Da qui il
    // batch periodico e la reazione all'evento di ricalcolo, gemelli di quelli di PowerManager per il tetto.

    /** Imposta il flag "nascosto" di un giocatore e lo salva solo se e' cambiato (la riga deve gia' esistere
     *  in cache: seguiamo solo chi e' passato dal server almeno una volta). */
    private void applyHidden(UUID u, boolean hidden) {
        PS ps = cache.get(u);
        if (ps == null || ps.hidden == hidden) return;
        ps.hidden = hidden;
        save(u);
        plugin.getLogger().info("[Classifiche] " + u + (hidden ? " nascosto" : " di nuovo visibile")
                + " nelle classifiche (permesso magixfactions.leaderboard.hide).");
    }

    /** Riallinea il flag di un giocatore ONLINE dal suo permesso (main thread). */
    public void applyHiddenOnline(Player p) { applyHidden(p.getUniqueId(), p.hasPermission(PERM_HIDE_LEADERBOARD)); }

    /** Riallinea il flag dai permessi gia' letti via LuckPerms (mappa permesso->valore, gruppi inclusi). */
    private void applyHiddenFrom(UUID u, Map<String, Boolean> perms) {
        applyHidden(u, perms.getOrDefault(PERM_HIDE_LEADERBOARD, Boolean.FALSE));
    }

    /**
     * Rilegge da LuckPerms il permesso di TUTTI i giocatori OFFLINE in cache e ne aggiorna il flag. Serve
     * soprattutto all'avvio: uno staff a cui il permesso era gia' assegnato PRIMA di questa funzione non
     * genera nessun evento di ricalcolo, quindi senza questo giro resterebbe visibile finche' non rientra.
     * La lettura tocca lo storage di LuckPerms: sta tutta in un task ASINCRONO, sul main torna solo
     * l'applicazione dei valori. Senza LuckPerms non fa nulla (i flag restano quelli dell'ultimo online).
     */
    public void refreshHiddenOffline() {
        if (cache.isEmpty() || luckPerms == null || !luckPerms.available()) return;
        final List<UUID> candidates = new ArrayList<>();
        for (UUID u : cache.keySet()) if (Bukkit.getPlayer(u) == null) candidates.add(u);
        if (candidates.isEmpty()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final Map<UUID, Boolean> read = new HashMap<>();
            for (UUID u : candidates) {
                Map<String, Boolean> perms = luckPerms.permissions(u);
                if (perms != null) read.put(u, perms.getOrDefault(PERM_HIDE_LEADERBOARD, Boolean.FALSE));
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                int hiddenCount = 0;
                for (Map.Entry<UUID, Boolean> e : read.entrySet()) {
                    if (Bukkit.getPlayer(e.getKey()) != null) continue;   // rientrato: ci pensa onJoin
                    if (Boolean.TRUE.equals(e.getValue())) hiddenCount++;
                    applyHidden(e.getKey(), e.getValue());
                }
                plugin.getLogger().info("[Classifiche] Visibilita' staff riletta da LuckPerms: " + hiddenCount
                        + " nascosti su " + read.size() + " giocatori offline controllati.");
            });
        });
    }

    /**
     * Aggancia l'evento di ricalcolo di LuckPerms: appena a un giocatore cambia grado/permesso, riallinea
     * ALL'ISTANTE il suo flag, anche se e' offline (permessi letti in async). Cosi' assegnare/togliere il
     * permesso si vede subito, senza aspettare il rientro o il giro periodico.
     */
    public void watchPermissions() {
        if (luckPerms == null || !luckPerms.available()) return;
        luckPerms.onUserRecalculate(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) { Bukkit.getScheduler().runTask(plugin, () -> applyHiddenOnline(p)); return; }
            if (cache.get(uuid) == null) return;   // mai passato dal server: nessuna riga da nascondere
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Map<String, Boolean> perms = luckPerms.permissions(uuid);
                if (perms == null) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (Bukkit.getPlayer(uuid) == null) applyHiddenFrom(uuid, perms);
                });
            });
        });
    }

    public long getKills(UUID u) { PS ps = cache.get(u); return ps == null ? 0 : ps.kills; }
    public long getDeaths(UUID u) { PS ps = cache.get(u); return ps == null ? 0 : ps.deaths; }
    public long getPlaySeconds(UUID u) { PS ps = cache.get(u); return ps == null ? 0 : ps.playSeconds; }

    /** Giacenza media personale: integrale Σ(saldo × secondi) / secondi online contati (0 se nessuno). */
    public double averageMoney(UUID u) {
        PS ps = cache.get(u);
        if (ps == null || ps.moneySeconds <= 0) return 0;
        return ps.moneyAvgAccum / ps.moneySeconds;
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

    /**
     * (solo dati di test) Imposta le statistiche di un giocatore FINTO in un colpo solo (uccisioni, morti,
     * tempo di gioco e giacenza media). La media si dà come coppia integrale/denominatore: {@code moneyAvg}
     * è la giacenza media desiderata, {@code moneySeconds} il tempo su cui è stata "misurata" (mettine
     * abbastanza — vedi {@link #averageMoney}). La riga {@code players} dev'essere già stata creata da
     * {@link PowerManager#registerFake} (qui si fa solo UPDATE, come per i giocatori veri). Rimosso da
     * {@link #forget}.
     */
    public void setFakeStats(UUID u, long kills, long deaths, long playSeconds, double moneyAvg, long moneySeconds) {
        PS ps = ensure(u);
        ps.kills = kills; ps.deaths = deaths; ps.playSeconds = playSeconds;
        ps.moneySeconds = moneySeconds; ps.moneyAvgAccum = moneyAvg * moneySeconds;
        save(u);
    }

    /** (solo dati di test) Toglie dalla cache le statistiche di un giocatore FINTO. La riga {@code players}
     *  la cancella {@link PowerManager#forget}; qui si ripulisce solo la cache. */
    public void forget(UUID u) { cache.remove(u); }

    // ------------------------------ EVENTI -------------------------------
    /** Registra un'uccisione PvP VALIDA (gia' filtrata dall'anti fake-kill). */
    public void recordKill(UUID killer) { ensure(killer).kills++; save(killer); }

    /** Registra una morte PvP VALIDA per la vittima (accoppiata a {@link #recordKill}). */
    public void recordDeath(UUID victim) { ensure(victim).deaths++; save(victim); }

    /** Ingresso: allinea subito il tempo totale (vanilla), la visibilita' in classifica e apre la finestra
     *  della giacenza media. */
    public void onJoin(Player p) {
        PS ps = ensure(p.getUniqueId());
        ps.playSeconds = vanillaPlaySeconds(p);
        ps.hidden = p.hasPermission(PERM_HIDE_LEADERBOARD);   // staff: fuori dalle classifiche del sito
        ps.lastSampledAt = System.currentTimeMillis();   // l'offline non conta per la giacenza media
        save(p.getUniqueId());
    }

    /** Uscita: ultimo campione (tempo totale + giacenza), poi ferma la finestra. */
    public void onQuit(UUID u) {
        PS ps = cache.get(u);
        if (ps == null) return;
        Player p = Bukkit.getPlayer(u);   // nell'evento di quit è ancora online
        if (p != null) accumulate(ps, p);
        ps.lastSampledAt = 0;
        save(u);
    }

    /**
     * Campiona TUTTI i giocatori online: aggiorna il tempo totale (vanilla) e l'integrale della giacenza
     * media. Lo chiama il task periodico (stesso passo del campionatore del punteggio) e onDisable.
     */
    public void sampleAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PS ps = ensure(p.getUniqueId());
            if (accumulate(ps, p)) save(p.getUniqueId());
        }
    }

    /** Aggiorna tempo totale (vanilla) e giacenza media di un giocatore ONLINE.
     *  @return true se qualcosa è cambiato. */
    private boolean accumulate(PS ps, Player p) {
        boolean changed = false;
        long vt = vanillaPlaySeconds(p);
        if (vt > 0 && vt != ps.playSeconds) { ps.playSeconds = vt; changed = true; }
        // Visibilita' in classifica dal permesso: segue i cambi di grado dello staff senza bisogno di rientrare.
        boolean hide = p.hasPermission(PERM_HIDE_LEADERBOARD);
        if (hide != ps.hidden) { ps.hidden = hide; changed = true; }
        long now = System.currentTimeMillis();
        if (ps.lastSampledAt <= 0) {
            ps.lastSampledAt = now;   // prima volta: apri la finestra e basta
        } else {
            long dtSec = (now - ps.lastSampledAt) / 1000L;
            if (dtSec > 0) {
                ps.moneySeconds += dtSec;
                ps.moneyAvgAccum += Econ.balance(p) * dtSec;
                ps.lastSampledAt += dtSec * 1000L;   // conserva il resto sotto il secondo
                changed = true;
            }
        }
        return changed;
    }

    private void save(UUID u) {
        PS ps = cache.get(u);
        if (ps == null) return;
        final String us = u.toString();
        final long kills = ps.kills, deaths = ps.deaths, playSeconds = ps.playSeconds, moneySeconds = ps.moneySeconds;
        final double moneyAcc = ps.moneyAvgAccum;
        final int hidden = ps.hidden ? 1 : 0;
        dbExec.submit(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement psq = c.prepareStatement(
                         "UPDATE players SET kills=?, deaths=?, play_seconds=?, money_avg_accum=?, money_seconds=?, leaderboard_hidden=? WHERE uuid=?")) {
                psq.setLong(1, kills); psq.setLong(2, deaths); psq.setLong(3, playSeconds);
                psq.setDouble(4, moneyAcc); psq.setLong(5, moneySeconds); psq.setInt(6, hidden); psq.setString(7, us);
                psq.executeUpdate();
            } catch (SQLException e) { plugin.getLogger().warning("[Stats] salvataggio: " + e.getMessage()); }
        });
    }
}
