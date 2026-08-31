package com.teolo.magixfactions.listener;

import com.teolo.magixfactions.map.MapService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Riaggancia il {@link com.teolo.magixfactions.map.FactionMapRenderer} alle Mappe Fazioni quando un
 * giocatore le tiene in mano (dopo un riavvio il renderer viene perso: vedi {@link MapService}).
 * Copre i casi comuni: login, cambio slot hotbar, scambio mani, cambio mondo.
 */
public final class MapListener implements Listener {

    private final JavaPlugin plugin;
    private final MapService maps;

    public MapListener(JavaPlugin plugin, MapService maps) { this.plugin = plugin; this.maps = maps; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // 1 tick di ritardo: al login i dati mappa potrebbero non essere ancora caricati.
        Player p = e.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> maps.reattachHands(p));
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        maps.reattach(e.getPlayer().getInventory().getItem(e.getNewSlot()));
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        maps.reattach(e.getMainHandItem());
        maps.reattach(e.getOffHandItem());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        maps.reattachHands(e.getPlayer());
    }
}
