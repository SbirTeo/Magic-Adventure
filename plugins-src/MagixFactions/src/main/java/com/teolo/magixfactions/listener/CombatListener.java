package com.teolo.magixfactions.listener;

import com.teolo.magixfactions.lang.Messages;
import com.teolo.magixfactions.manage.FactionManager;
import com.teolo.magixfactions.manage.PlayerStatsManager;
import com.teolo.magixfactions.model.Faction;
import com.teolo.magixfactions.model.RelationType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PvP di fazione: due cose in un solo posto.
 * <ol>
 *   <li><b>Fuoco amico impedito</b>: il danno fra membri della stessa fazione, e fra alleati, viene
 *       ANNULLATO (non "niente crediti": proprio non ci si puo' colpire). Configurabile separatamente per
 *       fazione e per alleati ({@code combat.friendly-fire.*}).</li>
 *   <li><b>Uccisioni/morti valide</b> per le classifiche (K/D), con tre difese anti "fake kill" fra amici
 *       (oltre al fatto che gli amici non possono colpirsi):
 *       <ul>
 *         <li><b>vita minima</b> della vittima: dev'essere viva da almeno
 *             {@code combat.min-victim-lifetime-seconds};</li>
 *         <li><b>cooldown stessa vittima</b>: ri-ucciderla entro {@code combat.kill-cooldown-minutes} non
 *             da' crediti;</li>
 *         <li><b>stesso IP/alt</b>: niente crediti se uccisore e vittima condividono l'indirizzo di rete
 *             ({@code combat.same-ip-no-credit}).</li>
 *       </ul>
 *       Una kill non valida non conta ne' come uccisione per l'uccisore ne' come morte per la vittima
 *       (il K/D resta pulito). Le morti non-PvP (ambiente) non incidono sul K/D.</li>
 * </ol>
 */
public final class CombatListener implements Listener {

    private final JavaPlugin plugin;
    private final FactionManager fm;
    private final PlayerStatsManager stats;
    private final com.teolo.magixfactions.manage.ScoreManager score;
    private final Messages M;

    /** Anti-spam del messaggio di fuoco amico, per-giocatore. */
    private final Map<UUID, Long> lastDenyMsg = new HashMap<>();
    /** Ultima uccisione valida di una vittima da parte di un uccisore: uccisore -> (vittima -> istante ms). */
    private final Map<UUID, Map<UUID, Long>> lastKill = new HashMap<>();
    /** Istante (ms) dell'ultimo spawn/respawn/ingresso della vittima, per la vita minima. */
    private final Map<UUID, Long> spawnedAt = new HashMap<>();

    public CombatListener(JavaPlugin plugin, FactionManager fm, PlayerStatsManager stats,
                          com.teolo.magixfactions.manage.ScoreManager score, Messages messages) {
        this.plugin = plugin; this.fm = fm; this.stats = stats; this.score = score; this.M = messages;
    }

    // ------------------------------ CONFIG -------------------------------
    private boolean ffFaction() { return plugin.getConfig().getBoolean("combat.friendly-fire.faction", true); }
    private boolean ffAllies()  { return plugin.getConfig().getBoolean("combat.friendly-fire.allies", true); }
    private long killCooldownMs() {
        return Math.max(0, plugin.getConfig().getLong("combat.kill-cooldown-minutes", 10)) * 60_000L;
    }
    private boolean sameIpNoCredit() { return plugin.getConfig().getBoolean("combat.same-ip-no-credit", true); }
    private long minLifetimeMs() {
        return Math.max(0, plugin.getConfig().getLong("combat.min-victim-lifetime-seconds", 60)) * 1000L;
    }

    // --------------------------- FUOCO AMICO -----------------------------
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = resolvePlayer(e.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        if (isFriendly(attacker, victim)) {
            e.setCancelled(true);
            denyFriendlyFire(attacker);
        }
    }

    /** true se i due sono della stessa fazione (con {@code ff.faction}) o alleati (con {@code ff.allies}). */
    private boolean isFriendly(Player a, Player b) {
        Faction fa = fm.getFaction(a.getUniqueId());
        Faction fb = fm.getFaction(b.getUniqueId());
        if (fa == null || fb == null) return false;
        if (fa.getId() == fb.getId()) return ffFaction();
        return ffAllies() && fm.effectiveRelation(fa.getId(), fb.getId()) == RelationType.ALLY;
    }

    private void denyFriendlyFire(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastDenyMsg.get(p.getUniqueId());
        if (last != null && now - last < 1500) return;      // anti-spam
        lastDenyMsg.put(p.getUniqueId(), now);
        p.sendMessage(M.prefix() + M.get(p, "combat.friendly-fire"));
    }

    // ----------------------- UCCISIONI / MORTI ---------------------------
    @EventHandler
    public void onSpawn(PlayerRespawnEvent e) { spawnedAt.put(e.getPlayer().getUniqueId(), System.currentTimeMillis()); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        spawnedAt.put(u, System.currentTimeMillis());
        stats.onJoin(e.getPlayer());   // allinea il tempo totale (vanilla) e apre la finestra della giacenza media
        score.onMemberJoin(fm.getFaction(u), u);   // chiude l'integrale della fazione col vecchio stato (offline)
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        score.onMemberQuit(fm.getFaction(u));   // accredita il tempo online prima che se ne vada
        stats.onQuit(u);   // ultimo campione del tempo giocato, poi chiude la finestra
        spawnedAt.remove(u);
        lastKill.remove(u);
        lastDenyMsg.remove(u);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();   // null se morte non-PvP (ambiente): non tocca il K/D
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return;
        if (validKill(killer, victim)) {
            stats.recordKill(killer.getUniqueId());
            stats.recordDeath(victim.getUniqueId());
        }
    }

    /** Applica i filtri anti fake-kill. Se valida, registra il momento per il cooldown. */
    private boolean validKill(Player killer, Player victim) {
        // 1) mai fra compagni di fazione o alleati (difesa in piu': il fuoco amico gia' impedirebbe la morte)
        if (isFriendly(killer, victim)) return false;
        long now = System.currentTimeMillis();
        // 2) vita minima della vittima (spawn/respawn/ingresso sconosciuto = si concede)
        long min = minLifetimeMs();
        if (min > 0) {
            Long born = spawnedAt.get(victim.getUniqueId());
            if (born != null && now - born < min) return false;
        }
        // 3) stesso IP/alt: niente crediti se condividono l'indirizzo di rete
        if (sameIpNoCredit() && sameAddress(killer, victim)) return false;
        // 4) cooldown stessa vittima: ri-ucciderla troppo presto non conta
        long cd = killCooldownMs();
        if (cd > 0) {
            Map<UUID, Long> byVictim = lastKill.get(killer.getUniqueId());
            Long last = byVictim == null ? null : byVictim.get(victim.getUniqueId());
            if (last != null && now - last < cd) return false;
        }
        lastKill.computeIfAbsent(killer.getUniqueId(), k -> new HashMap<>()).put(victim.getUniqueId(), now);
        return true;
    }

    /** true se i due giocatori risultano collegati dallo stesso indirizzo di rete. */
    private boolean sameAddress(Player a, Player b) {
        InetAddress ia = a.getAddress() == null ? null : a.getAddress().getAddress();
        InetAddress ib = b.getAddress() == null ? null : b.getAddress().getAddress();
        return ia != null && ib != null && ia.equals(ib);
    }

    /** Il giocatore responsabile del danno: diretto, o il tiratore di un proiettile. */
    private Player resolvePlayer(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
        }
        return null;
    }
}
