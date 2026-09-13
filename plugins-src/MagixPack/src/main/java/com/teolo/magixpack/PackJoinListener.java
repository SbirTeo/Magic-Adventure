package com.teolo.magixpack;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Rende OBBLIGATORIO il resource pack: lo invia a ogni giocatore al join e, se il client non lo
 * carica, lo espelle con un messaggio configurabile. Porto generico del listener gia' collaudato in
 * MagixFactions.
 *
 * <p>Il flag "force" del pacchetto da solo non basta (il client puo' ignorarlo, il download puo'
 * fallire): qui si chiude il cerchio lato server sull'ESITO reale ({@link PlayerResourcePackStatusEvent}).
 * Salvaguardie contro il lockout: chi ha {@code magixpack.resourcepack.bypass} (default op) non
 * viene mai espulso, e se il servizio HTTP non e' partito nessuno viene espulso.
 */
public final class PackJoinListener implements Listener {

    private final JavaPlugin plugin;
    private final PackService service;
    private final Map<UUID, BukkitTask> pending = new HashMap<>();
    private int consecutiveDownloadFailures;

    public PackJoinListener(JavaPlugin plugin, PackService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!service.isRequired()) return;
        if (!service.isAvailable()) {
            plugin.getLogger().warning("[Pack] Pacchetto obbligatorio ma non servibile "
                    + "(public-host non configurato o server HTTP non avviato): nessuna espulsione.");
            return;
        }
        Player p = e.getPlayer();
        int delay = Math.max(0, plugin.getConfig().getInt("pack.send-delay-ticks", 20));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            service.sendTo(p);
            if (!isExempt(p)) armTimeout(p);
        }, delay);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        Player p = e.getPlayer();
        plugin.getLogger().info("[Pack] " + p.getName() + ": " + e.getStatus());

        if (!service.isRequired() || isExempt(p)) { cancelTimeout(p.getUniqueId()); return; }

        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                cancelTimeout(p.getUniqueId());
                consecutiveDownloadFailures = 0;
            }
            case ACCEPTED, DOWNLOADED -> armTimeout(p);
            case DECLINED -> kick(p, "declined");
            case FAILED_DOWNLOAD, INVALID_URL -> {
                consecutiveDownloadFailures++;
                if (consecutiveDownloadFailures % 3 == 0) {
                    plugin.getLogger().severe("[Pack] " + consecutiveDownloadFailures
                            + " download falliti di fila: verifica che " + service.publicUrl()
                            + " sia raggiungibile dai client (porta aperta sul firewall del server).");
                }
                kick(p, "failed");
            }
            case FAILED_RELOAD, DISCARDED -> kick(p, "failed");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancelTimeout(e.getPlayer().getUniqueId());
    }

    private void armTimeout(Player p) {
        cancelTimeout(p.getUniqueId());
        int seconds = plugin.getConfig().getInt("pack.timeout-seconds", 30);
        if (seconds <= 0) return;
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(p.getUniqueId());
            if (p.isOnline()) kick(p, "timeout");
        }, seconds * 20L);
        pending.put(p.getUniqueId(), task);
    }

    private void cancelTimeout(UUID uuid) {
        BukkitTask task = pending.remove(uuid);
        if (task != null) task.cancel();
    }

    private void kick(Player p, String reason) {
        cancelTimeout(p.getUniqueId());
        String raw = plugin.getConfig().getString("pack.kick-messages." + reason);
        if (raw == null || raw.isBlank()) {
            raw = plugin.getConfig().getString("pack.kick-messages.declined",
                    "&cIl pacchetto risorse e' OBBLIGATORIO su questo server.");
        }
        var message = LegacyComponentSerializer.legacyAmpersand().deserialize(raw.replace("\\n", "\n"));
        Runnable doKick = () -> {
            if (!p.isOnline()) return;
            plugin.getLogger().info("[Pack] " + p.getName() + " espulso: pacchetto non caricato (" + reason + ").");
            p.kick(message);
        };
        if (Bukkit.isPrimaryThread()) doKick.run();
        else Bukkit.getScheduler().runTask(plugin, doKick);
    }

    private boolean isExempt(Player p) {
        return p.hasPermission("magixpack.resourcepack.bypass");
    }
}
