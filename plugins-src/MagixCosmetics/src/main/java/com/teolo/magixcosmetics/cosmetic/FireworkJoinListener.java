package com.teolo.magixcosmetics.cosmetic;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Fa partire il firework di benvenuto quando un giocatore entra in questo server. */
public final class FireworkJoinListener implements Listener {

    private final FireworkManager firework;

    public FireworkJoinListener(FireworkManager firework) {
        this.firework = firework;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        firework.onJoin(event.getPlayer());
    }
}
