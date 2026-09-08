package com.teolo.magixfactions.manage;

import com.teolo.magixfactions.db.Database;
import com.teolo.magixfactions.db.DbExecutor;
import com.teolo.magixfactions.model.Faction;
import com.teolo.magixfactions.model.Rank;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DATI DI TEST: fazioni e giocatori FINTI per popolare il server (e il sito) prima dell'apertura al
 * pubblico, con un comando per rimuoverli tutti in un colpo solo quando si apre davvero.
 *
 * <p><b>Come restano riconoscibili.</b> Ogni cosa creata qui viene annotata su due tabelle-registro
 * dedicate — {@code fake_factions} (gli id delle fazioni finte) e {@code fake_players} (gli UUID dei
 * giocatori finti). Il registro è persistente: si può creare oggi e cancellare fra settimane, anche dopo
 * riavvii, senza dover indovinare quali dati fossero finti. La rimozione tocca ESCLUSIVAMENTE ciò che è
 * elencato lì, quindi non può intaccare fazioni o giocatori veri.
 *
 * <p><b>Cosa NON crea.</b> Nessun territorio (claim): i chunk finti sporcherebbero la mappa reale intorno
 * allo spawn. Le fazioni finte hanno membri, banca, Potenza e statistiche (uccisioni, morti, tempo di
 * gioco, ricchezza media), quindi compaiono in {@code /f list}, {@code /f top} e nelle classifiche del
 * sito; il numero di territori resta 0.
 */
public final class FakeDataManager {

    private final JavaPlugin plugin;
    private final Database db;
    private final DbExecutor dbExec;
    private final FactionManager fm;
    private final PowerManager power;
    private final PlayerStatsManager stats;
    private final ScoreManager score;

    /** Registri in memoria (rispecchiano le tabelle omonime), per il conteggio e la rimozione. */
    private final Set<Long> fakeFactions = new LinkedHashSet<>();
    private final Set<UUID> fakePlayers = new LinkedHashSet<>();

    public FakeDataManager(JavaPlugin plugin, Database db, DbExecutor dbExec, FactionManager fm,
                           PowerManager power, PlayerStatsManager stats, ScoreManager score) {
        this.plugin = plugin; this.db = db; this.dbExec = dbExec;
        this.fm = fm; this.power = power; this.stats = stats; this.score = score;
    }

    // --------------------------------- SCHEMA / LOAD ---------------------------------

    /** Crea (se mancano) le due tabelle-registro. Va chiamato all'avvio, dopo {@code createSchema} del DB. */
    public void ensureSchema() throws SQLException {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS fake_factions (id BIGINT PRIMARY KEY)");
            st.execute("CREATE TABLE IF NOT EXISTS fake_players (uuid VARCHAR(36) PRIMARY KEY)");
        }
    }

    /** Carica i registri in memoria (chi è finto). */
    public void loadAll() throws SQLException {
        fakeFactions.clear(); fakePlayers.clear();
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT id FROM fake_factions")) {
                while (rs.next()) fakeFactions.add(rs.getLong("id"));
            }
            try (ResultSet rs = st.executeQuery("SELECT uuid FROM fake_players")) {
                while (rs.next()) {
                    try { fakePlayers.add(UUID.fromString(rs.getString("uuid"))); } catch (IllegalArgumentException ignored) {}
                }
            }
        }
    }

    public int factionCount() { return fakeFactions.size(); }
    public int playerCount() { return fakePlayers.size(); }
    public boolean hasAny() { return !fakeFactions.isEmpty() || !fakePlayers.isEmpty(); }

    // ------------------------------------ CREAZIONE ----------------------------------

    /** Esito di una generazione o rimozione: quante fazioni e quanti giocatori sono stati toccati. */
    public static final class Result {
        public final int factions, players;
        public Result(int factions, int players) { this.factions = factions; this.players = players; }
    }

    /**
     * Genera {@code count} fazioni finte, ognuna con un leader e un numero casuale di membri fra
     * {@code minMembers} e {@code maxMembers} (leader incluso). Tutto sul main thread (tocca le cache dei
     * manager); le scritture su DB partono in async serializzato come nel resto del plugin.
     */
    public Result generate(int count, int minMembers, int maxMembers) throws SQLException {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long now = System.currentTimeMillis();
        int minM = Math.max(1, minMembers);
        int maxM = Math.max(minM, maxMembers);

        Set<String> usedFactionNames = new HashSet<>();
        Set<String> usedPlayerNames = new HashSet<>();
        int madeFactions = 0, madePlayers = 0;
        List<Rank> ladder = new ArrayList<>();
        for (int i = 0; i < fm.ranks().size(); i++) ladder.add(fm.ranks().byIndex(i));

        for (int i = 0; i < count; i++) {
            String fName = uniqueFactionName(usedFactionNames, r);
            if (fName == null) break; // esauriti i nomi disponibili: ci fermiamo qui
            usedFactionNames.add(fName.toLowerCase(Locale.ROOT));

            long createdAt = now - (long) (r.nextInt(1, 121)) * 86_400_000L - r.nextInt(0, 86_400_000);

            // Leader
            UUID leaderUuid = UUID.randomUUID();
            String leaderName = uniquePlayerName(usedPlayerNames, r);
            usedPlayerNames.add(leaderName.toLowerCase(Locale.ROOT));
            int leaderMax = 10 + r.nextInt(0, 21);
            registerFakePlayer(leaderUuid, leaderName, r.nextInt(-2, leaderMax + 1), leaderMax, r);
            madePlayers++;

            Faction f = fm.createFactionAt(fName, fName, leaderUuid, createdAt);
            fakeFactions.add(f.getId());
            markFactionFake(f.getId());

            // Membri extra
            int members = r.nextInt(minM, maxM + 1);
            for (int m = 1; m < members; m++) {
                UUID u = UUID.randomUUID();
                String pn = uniquePlayerName(usedPlayerNames, r);
                usedPlayerNames.add(pn.toLowerCase(Locale.ROOT));
                int max = 10 + r.nextInt(0, 21);
                registerFakePlayer(u, pn, r.nextInt(-2, max + 1), max, r);
                madePlayers++;
                Rank rank = ladder.isEmpty() ? null : ladder.get(r.nextInt(ladder.size()));
                fm.addMember(f, u, rank != null ? rank.getId() : Rank.LEADER_ID);
            }

            // Banca + medie del punteggio, seminate coerenti coi valori attuali cosi' la classifica ha
            // subito numeri sensati invece di partire da zero e assestarsi solo col tempo.
            double bank = r.nextInt(0, 40) * 500.0; // 0..19500
            fm.setBank(f, bank);
            seedScoreAverages(f, createdAt, now, bank);

            madeFactions++;
        }

        // Ricalcola subito punteggio/dettaglio (e lo salva) cosi' /f top e il sito riflettono i nuovi dati.
        if (madeFactions > 0) score.sampleAll();
        return new Result(madeFactions, madePlayers);
    }

    /** Crea un giocatore finto con Potenza e statistiche casuali (riga players + statistiche + registro). */
    private void registerFakePlayer(UUID u, String name, int pw, int max, ThreadLocalRandom r) {
        power.registerFake(u, name, pw, max);
        long kills = r.nextInt(0, 400);
        long deaths = r.nextInt(0, 400);
        long playSeconds = (long) r.nextInt(1, 300) * 3600L; // 1..300 ore
        double moneyAvg = r.nextInt(0, 200) * 100.0;         // 0..19900
        long moneySeconds = playSeconds;                     // denominatore ampio: la media "tiene"
        stats.setFakeStats(u, kills, deaths, playSeconds, moneyAvg, moneySeconds);
        fakePlayers.add(u);
        markPlayerFake(u);
    }

    /**
     * Semina gli integrali delle medie (banca/potenza) in modo che la giacenza media = {@code bank} e la
     * potenza media = potenza attuale della fazione, subito, senza aspettare il campionatore. Lo fa
     * impostando i denominatori sopra la soglia di {@link ScoreManager} e gli accumulatori di conseguenza.
     */
    private void seedScoreAverages(Faction f, long createdAt, long now, double bank) {
        double windowSec = Math.max(score.sampleIntervalSeconds() + 1, (now - createdAt) / 1000.0);
        double activeSec = Math.max(score.sampleIntervalSeconds() + 1, windowSec * 0.4); // "online" ~40% del tempo
        f.setScoreSince(createdAt);
        f.setScoreSampledAt(now);
        f.setBankActiveSeconds(activeSec);
        f.setBankAvgAccum(bank * activeSec);
        f.setPowerAvgAccum(power.factionPower(f) * windowSec);
        // created_at e score_since retrodatati vanno anche su DB (createFactionAt li ha già scritti col
        // createdAt passato; qui riscriviamo score_since/gli accumulatori tramite saveScoreSample).
        fm.saveScoreSample(f);
    }

    // ------------------------------------- RIMOZIONE ---------------------------------

    /**
     * Rimuove TUTTE le fazioni e i giocatori finti (solo quelli nei registri), pulendo cache e database, e
     * svuota i registri. Sicuro da chiamare più volte: se non c'è nulla di finto, non fa niente.
     */
    public Result clearAll() {
        int removedFactions = 0, removedPlayers = 0;

        // 1) Fazioni: disband() pulisce membri, claim, relazioni, home, timer e cache in un colpo.
        for (long id : new ArrayList<>(fakeFactions)) {
            Faction f = fm.getById(id);
            if (f != null) { fm.disband(f); }
            else { rawDeleteFaction(id); } // riga orfana (già sciolta a mano): pulizia di sicurezza
            removedFactions++;
        }
        fakeFactions.clear();

        // 2) Giocatori: via cache + riga players, e per sicurezza eventuali appartenenze/permessi residui.
        for (UUID u : new ArrayList<>(fakePlayers)) {
            power.forget(u);
            stats.forget(u);
            rawDeleteMemberRows(u);
            removedPlayers++;
        }
        fakePlayers.clear();

        // 3) Svuota i registri.
        dbExec.submit(() -> {
            try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM fake_factions");
                st.executeUpdate("DELETE FROM fake_players");
            } catch (SQLException e) { plugin.getLogger().warning("[Fake] svuotamento registri: " + e.getMessage()); }
        });

        // Aggiorna i massimi/punteggi rimasti (i valori di riferimento cambiano quando spariscono fazioni).
        score.sampleAll();
        return new Result(removedFactions, removedPlayers);
    }

    // --------------------------------- REGISTRI (DB) ---------------------------------

    private void markFactionFake(long id) {
        dbExec.submit(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("INSERT INTO fake_factions (id) VALUES (?)")) {
                ps.setLong(1, id); ps.executeUpdate();
            } catch (SQLException e) { plugin.getLogger().warning("[Fake] registro fazione: " + e.getMessage()); }
        });
    }

    private void markPlayerFake(UUID u) {
        final String us = u.toString();
        dbExec.submit(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("INSERT INTO fake_players (uuid) VALUES (?)")) {
                ps.setString(1, us); ps.executeUpdate();
            } catch (SQLException e) { plugin.getLogger().warning("[Fake] registro giocatore: " + e.getMessage()); }
        });
    }

    private void rawDeleteFaction(long id) {
        dbExec.submit(() -> {
            try (Connection c = db.getConnection()) {
                for (String sql : new String[]{
                        "DELETE FROM faction_members WHERE faction_id=?",
                        "DELETE FROM claims WHERE faction_id=?",
                        "DELETE FROM relations WHERE faction_id=? OR other_id=?",
                        "DELETE FROM faction_homes WHERE faction_id=?",
                        "DELETE FROM overclaim_timers WHERE faction_id=?",
                        "DELETE FROM factions WHERE id=?"}) {
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setLong(1, id);
                        if (sql.contains("other_id")) ps.setLong(2, id);
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException e) { plugin.getLogger().warning("[Fake] rimozione fazione orfana: " + e.getMessage()); }
        });
    }

    private void rawDeleteMemberRows(UUID u) {
        final String us = u.toString();
        dbExec.submit(() -> {
            try (Connection c = db.getConnection()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM faction_members WHERE uuid=?")) {
                    ps.setString(1, us); ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM member_permissions WHERE uuid=?")) {
                    ps.setString(1, us); ps.executeUpdate();
                }
            } catch (SQLException e) { plugin.getLogger().warning("[Fake] rimozione appartenenze: " + e.getMessage()); }
        });
    }

    // ------------------------------------- NOMI --------------------------------------

    private static final String[] FACTION_A = {
            "Draghi", "Lupi", "Corvi", "Fenici", "Titani", "Ombre", "Aquile", "Vipere", "Leoni", "Falchi",
            "Spettri", "Cavalieri", "Guardiani", "Predoni", "Signori", "Furie", "Lame", "Colossi", "Sciacalli", "Grifoni" };
    private static final String[] FACTION_B = {
            "Neri", "Rossi", "d'Oro", "d'Argento", "di Ferro", "Eterni", "Selvaggi", "Perduti", "Antichi", "Feroci",
            "Silenti", "Ardenti", "Gelidi", "Oscuri", "Sacri", "Ribelli", "Immortali", "del Nord", "del Caos", "di Sangue" };

    private static final String[] PLAYER_NAMES = {
            "Marco", "Luca", "Alex", "Dark", "Shadow", "Kira", "Nova", "Rex", "Milo", "Zeno",
            "Aria", "Bolt", "Cyrus", "Drex", "Enzo", "Faro", "Gaia", "Hades", "Iris", "Jax",
            "Kilo", "Lyra", "Mira", "Nyx", "Orion", "Pyra", "Quinn", "Raven", "Storm", "Theo",
            "Ulf", "Vega", "Wolf", "Xeno", "Yuri", "Zara", "Ember", "Frost", "Ghost", "Blaze" };

    /** Un nome di fazione mai usato (né dalle finte in questo giro, né da fazioni reali/finte esistenti). */
    private String uniqueFactionName(Set<String> usedThisRun, ThreadLocalRandom r) {
        for (int attempt = 0; attempt < 400; attempt++) {
            String base = FACTION_A[r.nextInt(FACTION_A.length)] + " " + FACTION_B[r.nextInt(FACTION_B.length)];
            String name = base.length() <= 15 ? base : FACTION_A[r.nextInt(FACTION_A.length)];
            if (name.length() > 15) name = name.substring(0, 15);
            String key = name.toLowerCase(Locale.ROOT);
            if (usedThisRun.contains(key)) continue;
            if (fm.getByName(name) != null) continue;
            return name;
        }
        return null;
    }

    /** Un nome di giocatore valido (max 16, niente spazi) mai usato in questo giro né da un giocatore reale. */
    private String uniquePlayerName(Set<String> usedThisRun, ThreadLocalRandom r) {
        for (int attempt = 0; attempt < 1000; attempt++) {
            String base = PLAYER_NAMES[r.nextInt(PLAYER_NAMES.length)].replace(" ", "");
            String name = base + r.nextInt(1, 1000);
            if (name.length() > 16) name = name.substring(0, 16);
            String key = name.toLowerCase(Locale.ROOT);
            if (usedThisRun.contains(key)) continue;
            if (power.findByName(name) != null) continue; // non riusare il nome di un giocatore già noto
            return name;
        }
        // fallback quasi impossibile: suffisso dal tempo
        return "Test" + (System.nanoTime() % 100000);
    }
}
