package com.teolo.magixproxy.command;

import com.teolo.magixproxy.ProxyConfig;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * /hub (alias /lobby): sends the player to the hub server from any server of the network.
 *
 * The player reads network.hub.teleporting and is moved network.hub_delay_seconds later (a
 * second /hub in the meantime does not start another one). The connection goes through the
 * normal pre-connect event, so ServerGuard still applies the login rule (before /login the
 * player stays where he is).
 */
public final class HubCommand implements SimpleCommand {

    private final Object plugin;
    private final ProxyServer proxy;
    private final ProxyConfig config;
    /** Players with a teleport already counting down. */
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    public HubCommand(Object plugin, ProxyServer proxy, ProxyConfig config) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.config = config;
    }

    /** Registers /hub and its alias /lobby on the proxy. */
    public static void register(Object plugin, ProxyServer proxy, ProxyConfig config) {
        CommandMeta meta = proxy.getCommandManager().metaBuilder("hub")
                .aliases("lobby")
                .plugin(plugin)
                .build();
        proxy.getCommandManager().register(meta, new HubCommand(plugin, proxy, config));
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(text(null, "hub.players-only", ""));
            return;
        }
        String hub = config.hubServer;
        Optional<RegisteredServer> target = proxy.getServer(hub);
        if (target.isEmpty()) {
            player.sendMessage(text(player, "hub.unavailable", hub));
            return;
        }
        boolean already = player.getCurrentServer()
                .map(s -> s.getServerInfo().getName().equalsIgnoreCase(hub)).orElse(false);
        if (already) {
            player.sendMessage(text(player, "hub.already-here", hub));
            return;
        }
        if (!pending.add(player.getUniqueId())) {
            return; // already on its way
        }
        player.sendMessage(text(player, "hub.teleporting", hub));
        proxy.getScheduler().buildTask(plugin, () -> {
            pending.remove(player.getUniqueId());
            if (player.isActive()) {
                connect(player, target.get(), hub);
            }
        }).delay(Math.max(0, config.hubDelaySeconds), TimeUnit.SECONDS).schedule();
    }

    private void connect(Player player, RegisteredServer target, String hub) {
        if (player.getCurrentServer().map(s -> s.getServerInfo().getName().equalsIgnoreCase(hub)).orElse(false)) {
            return; // got there another way in the meantime
        }
        player.createConnectionRequest(target).connect().thenAccept(result -> {
            if (result.isSuccessful()) {
                return;
            }
            if (result.getStatus() == com.velocitypowered.api.proxy.ConnectionRequestBuilder.Status.SERVER_DISCONNECTED) {
                player.sendMessage(text(player, "hub.unavailable", hub));
            }
            // CONNECTION_CANCELLED: ServerGuard already told the player why (login first).
        });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }

    private Component text(Player player, String key, String server) {
        String message = config.message(player == null ? null : player.getUniqueId(), "network." + key);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(message.replace("{server}", server));
    }
}
