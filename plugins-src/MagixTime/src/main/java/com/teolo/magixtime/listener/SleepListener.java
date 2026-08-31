package com.teolo.magixtime.listener;

import com.teolo.magixtime.MagixTime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;

/**
 * Con doDaylightCycle spenta il letto non salta la notte: senza un avviso i
 * giocatori pensano che il letto sia rotto.
 */
public final class SleepListener implements Listener {

    private final MagixTime plugin;

    public SleepListener(MagixTime plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBed(PlayerBedEnterEvent e) {
        if (!plugin.getConfig().getBoolean("time.sleep-notify", true)) return;
        if (!plugin.getConfig().getBoolean("time.enabled", true)) return;
        if (e.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
        if (!plugin.isManaged(e.getPlayer().getWorld())) return;
        e.getPlayer().sendActionBar(plugin.messages().component("sleep-notice",
                "mctime", com.teolo.magixtime.time.TimeSync.formatTicks(e.getPlayer().getWorld().getTime())));
    }
}
