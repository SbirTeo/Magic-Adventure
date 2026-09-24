package com.teolo.magixfactions.listener;

import com.teolo.magixfactions.hook.Papi;
import com.teolo.magixfactions.lang.Messages;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attesa immobile prima del teletrasporto di {@code /f home} (config {@code home-warmup.seconds}):
 * muoversi (cambiare blocco, non solo direzione dello sguardo) annulla il teletrasporto in corso.
 */
public final class HomeWarmupListener implements Listener {

    private record Warmup(BukkitTask task, Location from) {}

    private final JavaPlugin plugin;
    private final Messages M;
    private final Map<UUID, Warmup> pending = new ConcurrentHashMap<>();

    public HomeWarmupListener(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.M = messages;
    }

    private void msgKey(Player p, String path, String... kv) {
        p.sendMessage(Papi.resolve(p, M.get(p, path, kv)));
    }

    /**
     * Avvia il warmup per il giocatore {@code p}: se {@code home-warmup.seconds} e' 0 (o meno)
     * esegue {@code onComplete} subito, senza attesa. Un warmup gia' in corso per lo stesso
     * giocatore viene rimpiazzato (si riparte da capo).
     */
    public void start(Player p, Runnable onComplete) {
        int seconds = Math.max(0, plugin.getConfig().getInt("home-warmup.seconds", 5));
        cancel(p.getUniqueId());
        if (seconds <= 0) { onComplete.run(); return; }
        msgKey(p, "home.warmup-start", "seconds", String.valueOf(seconds));
        UUID uuid = p.getUniqueId();
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pending.remove(uuid);
            if (p.isOnline()) onComplete.run();
        }, seconds * 20L);
        pending.put(uuid, new Warmup(task, p.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Warmup w = pending.get(e.getPlayer().getUniqueId());
        if (w == null) return;
        Location to = e.getTo();
        if (to == null || sameBlock(w.from(), to)) return;
        cancel(e.getPlayer().getUniqueId());
        msgKey(e.getPlayer(), "home.warmup-cancelled");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancel(e.getPlayer().getUniqueId());
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getWorld() == b.getWorld()
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private void cancel(UUID uuid) {
        Warmup w = pending.remove(uuid);
        if (w != null) w.task().cancel();
    }
}
