package com.teolo.magixfactions.listener;

import com.teolo.magixfactions.manage.PowerManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Aggancia il sistema Potenza agli eventi: ingresso, uscita e morte del giocatore. */
public final class PowerListener implements Listener {

    private final PowerManager power;

    public PowerListener(PowerManager power) { this.power = power; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { power.onJoin(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { power.onQuit(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) { power.onDeath(e.getEntity()); }
}
