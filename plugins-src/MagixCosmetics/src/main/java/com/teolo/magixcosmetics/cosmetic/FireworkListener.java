package com.teolo.magixcosmetics.cosmetic;

import org.bukkit.entity.Firework;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Il firework d'ingresso: lo fa partire quando un giocatore entra in questo server, e annulla
 * il danno della sua esplosione (e' un cosmetico, non deve ferire nessuno, ne' giocatori ne' mob).
 */
public final class FireworkListener implements Listener {

    private final FireworkManager firework;

    public FireworkListener(FireworkManager firework) {
        this.firework = firework;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        firework.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework fw && firework.isOurs(fw)) event.setCancelled(true);
    }
}
