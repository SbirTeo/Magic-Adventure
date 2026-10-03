package com.teolo.magixfactions.manage;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Chi e' fermo (AFK) non fa crescere niente: ne' la Potenza, ne' la giacenza media personale, ne'
 * quella della banca di fazione. Lo stato lo decide MagixGuard, che mette sul giocatore il segno
 * (metadata Bukkit) {@link #METADATA} finche' e' fermo: qui lo si legge senza dipendere da MagixGuard.
 *
 * <p>Le medie si chiudono a ogni cambio di stato (come all'ingresso e all'uscita), non al campione
 * periodico: per questo il giro gira ogni secondo e avvisa {@link ScoreManager} e
 * {@link PlayerStatsManager} appena un giocatore diventa fermo o torna.</p>
 */
public final class AfkTracker {

    /** Lo stesso nome di {@code AfkGuard.AFK_METADATA} in MagixGuard. */
    public static final String METADATA = "magix_afk";

    private final FactionManager fm;
    private final ScoreManager score;
    private final PlayerStatsManager stats;
    private final Set<UUID> afk = new HashSet<>();

    public AfkTracker(FactionManager fm, ScoreManager score, PlayerStatsManager stats) {
        this.fm = fm;
        this.score = score;
        this.stats = stats;
    }

    /** True se MagixGuard considera quel giocatore fermo adesso. */
    public static boolean isAfk(Player p) {
        return p != null && p.hasMetadata(METADATA);
    }

    /** Il giro di ogni secondo: chiude le medie di chi ha appena cambiato stato. */
    public void tick() {
        Set<UUID> online = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            online.add(u);
            boolean ora = isAfk(p);
            if (ora == afk.contains(u)) {
                continue;
            }
            if (ora) {
                afk.add(u);
                score.onMemberAfk(fm.getFaction(u));
                stats.onAfk(p);
            } else {
                afk.remove(u);
                score.onMemberBack(fm.getFaction(u), u);
                stats.onBack(p);
            }
        }
        afk.retainAll(online);
    }
}
