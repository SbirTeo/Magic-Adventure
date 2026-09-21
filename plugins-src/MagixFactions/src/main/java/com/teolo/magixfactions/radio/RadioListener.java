package com.teolo.magixfactions.radio;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Fa sentire la radio dello spawn a chi entra ({@code radio.play-on-join}). Con un po' di ritardo, cosi'
 * il client ha finito di caricarsi ed e' gia' nel mondo: senza, il pacchetto del suono arriverebbe prima
 * che il giocatore sia davvero a spawn. Il brano parte dall'inizio (Minecraft non sa riprenderlo a meta',
 * vedi {@link RadioService}); al primo cambio di brano il giocatore si riallinea con tutti gli altri.
 */
public final class RadioListener implements Listener {

    private final JavaPlugin plugin;
    private final RadioService radio;

    public RadioListener(JavaPlugin plugin, RadioService radio) {
        this.plugin = plugin;
        this.radio = radio;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!radio.isPlayOnJoin()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (e.getPlayer().isOnline() && radio.wouldPlayFor(e.getPlayer())) radio.refreshFor(e.getPlayer());
        }, 60L);
    }
}
