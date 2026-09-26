package com.teolo.magixtime.listener;

import com.teolo.magixtime.MagixTime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.world.TimeSkipEvent;

/**
 * Con doDaylightCycle spenta Minecraft non fa piu' scorrere il tempo da solo, ma dormire
 * resta un meccanismo A PARTE: se abbastanza giocatori vanno a letto, il salto alla mattina
 * (TimeSkipEvent, motivo NIGHT_SKIP) scatta comunque. Senza annullarlo qui, quel salto veniva
 * letto da TimeSync come un cambio esterno (come un /time set): restava valido per
 * time.override-seconds e poi il sole tornava in avanti da solo fino a riagganciare l'ora vera,
 * anche per un giro intero se il salto capitava lontano dall'orario reale — il sole che "gira
 * all'infinito" segnalato dai giocatori. Annullare qui il salto tiene fede al messaggio di
 * sleep-notice: la notte non si puo' davvero saltare dormendo.
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

    @EventHandler(priority = EventPriority.HIGH)
    public void onTimeSkip(TimeSkipEvent e) {
        if (e.getSkipReason() != TimeSkipEvent.SkipReason.NIGHT_SKIP) return;
        if (!plugin.getConfig().getBoolean("time.enabled", true)) return;
        if (!plugin.isManaged(e.getWorld())) return;
        e.setCancelled(true);
    }
}
