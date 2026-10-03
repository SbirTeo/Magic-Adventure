package com.teolo.magixfactions.manage;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chi e' fermo (AFK) non fa crescere niente: ne' la Potenza, ne' la giacenza media personale, ne'
 * quella della banca di fazione. Lo stato lo decide MagixGuard, che mette sul giocatore il segno
 * (metadata Bukkit) {@link #METADATA} finche' e' fermo: qui lo si legge senza dipendere da MagixGuard.
 *
 * <p>Le medie leggono lo stato da QUI ({@link #isTrackedAfk}), non dal segno: cosi' lo stato cambia
 * solo in {@link #tick()}, dopo aver chiuso l'intervallo appena trascorso col valore di prima, e un
 * campione periodico che cade fra il segno messo e il giro non puo' attribuire minuti di gioco al
 * tempo da fermi (o viceversa).</p>
 */
public final class AfkTracker implements Listener {

    /** Lo stesso nome di {@code AfkGuard.AFK_METADATA} in MagixGuard. */
    public static final String METADATA = "magix_afk";

    private final FactionManager fm;
    private final ScoreManager score;
    private final PlayerStatsManager stats;
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();

    public AfkTracker(FactionManager fm, ScoreManager score, PlayerStatsManager stats) {
        this.fm = fm;
        this.score = score;
        this.stats = stats;
        // Dopo un reload di MagixFactions chi era gia' fermo resta fermo, senza chiudere niente:
        // gli intervalli sono gia' stati chiusi all'avvio con lo stato di allora.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isAfk(p)) afk.add(p.getUniqueId());
        }
        score.setAfkTracker(this);
        stats.setAfkTracker(this);
    }

    /** True se MagixGuard considera quel giocatore fermo adesso (il segno, letto dal vivo). */
    public static boolean isAfk(Player p) {
        return p != null && p.hasMetadata(METADATA);
    }

    /** Lo stato su cui si calcolano le medie: cambia solo in {@link #tick()}. */
    public boolean isTrackedAfk(UUID u) {
        return afk.contains(u);
    }

    /** Il giro di ogni secondo: chiude le medie col vecchio stato, poi lo cambia. */
    public void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            boolean ora = isAfk(p);
            if (ora == afk.contains(u)) {
                continue;
            }
            score.flush(fm.getFaction(u));   // l'intervallo appena trascorso, con lo stato di prima
            if (ora) {
                stats.onAfk(p);               // accredita il tempo giocato fin qui, poi chiude
                afk.add(u);
            } else {
                afk.remove(u);
                stats.onBack(p);              // la giacenza media riparte da adesso
            }
        }
    }

    /** Dopo l'uscita (gli altri listener hanno gia' chiuso i conti con lo stato giusto). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        afk.remove(e.getPlayer().getUniqueId());
    }
}
