package com.teolo.magixcosmetics.cosmetic;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Segna chi entra in un combattimento PvP (anche a distanza, con un proiettile): serve a
 * {@link HaloManager} per nascondere l'aureola durante lo scontro e per qualche secondo dopo,
 * cosi' non fa da bersaglio colorato in mezzo a un combattimento. Priorita' MONITOR e
 * {@code ignoreCancelled}: un colpo negato da un altro plugin (fuoco amico, protezione di zona)
 * non deve contare come combattimento.
 */
public final class HaloCombatListener implements Listener {

    private final HaloManager halo;

    public HaloCombatListener(HaloManager halo) {
        this.halo = halo;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = resolvePlayer(e.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        halo.markCombat(attacker.getUniqueId());
        halo.markCombat(victim.getUniqueId());
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
