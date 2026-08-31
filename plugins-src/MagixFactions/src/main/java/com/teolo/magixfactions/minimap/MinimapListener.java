package com.teolo.magixfactions.minimap;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Mantiene viva la minimap HUD attraverso gli eventi che il client tratta scaricando/rimuovendo le
 * entita' finte o resettandone il montaggio (teleport, cambio mondo, respawn). Un teleport lungo fa
 * uscire le entita' finte dal range → il client le scarta: NON basta rimontarle (non le conosce piu'),
 * vanno RICREATE alla nuova posizione ({@link MinimapManager#resend}). Il resend va fatto qualche tick
 * DOPO l'evento: al momento dell'evento il client non ha ancora applicato il teleport, e il giocatore
 * non e' ancora alla posizione nuova (problema osservato in produzione: "se mi teletrasporto la mappa scompare").
 */
public final class MinimapListener implements Listener {

    private final JavaPlugin plugin;
    private final MinimapManager minimap;

    public MinimapListener(JavaPlugin plugin, MinimapManager minimap) {
        this.plugin = plugin;
        this.minimap = minimap;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        resendLater(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        resendLater(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        resendLater(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        minimap.deactivate(e.getPlayer()); // ferma il task e libera lo stato (niente leak)
    }

    /** Ricrea i quadri finti alla posizione post-evento. 3 tick di ritardo: da' tempo al client di
     *  applicare davvero il teleport (posizione/entita' aggiornate) prima di rispawnare. */
    private void resendLater(Player p) {
        if (!minimap.isActive(p)) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && minimap.isActive(p)) minimap.resend(p);
        }, 3L);
    }
}
