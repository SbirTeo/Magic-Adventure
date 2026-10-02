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

/**
 * /hub (alias /lobby): sends the player to the hub server from any server of the network.
 *
 * The connection goes through the normal pre-connect event, so ServerGuard still applies the
 * login rule (before /login the player stays where he is).
 */
public final class HubCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final ProxyConfig config;

    public HubCommand(ProxyServer proxy, ProxyConfig config) {
        this.proxy = proxy;
        this.config = config;
    }

    /** Registers /hub and its alias /lobby on the proxy. */
    public static void register(Object plugin, ProxyServer proxy, ProxyConfig config) {
        CommandMeta meta = proxy.getCommandManager().metaBuilder("hub")
                .aliases("lobby")
                .plugin(plugin)
                .build();
        proxy.getCommandManager().register(meta, new HubCommand(proxy, config));
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(text("hub.players-only", ""));
            return;
        }
        String hub = config.hubServer;
        Optional<RegisteredServer> target = proxy.getServer(hub);
        if (target.isEmpty()) {
            player.sendMessage(text("hub.unavailable", hub));
            return;
        }
        boolean already = player.getCurrentServer()
                .map(s -> s.getServerInfo().getName().equalsIgnoreCase(hub)).orElse(false);
        if (already) {
            player.sendMessage(text("hub.already-here", hub));
            return;
        }
        player.createConnectionRequest(target.get()).connect().thenAccept(result -> {
            if (result.isSuccessful()) {
                player.sendMessage(text("hub.sent", hub));
            } else if (result.getStatus() == com.velocitypowered.api.proxy.ConnectionRequestBuilder.Status.SERVER_DISCONNECTED) {
                player.sendMessage(text("hub.unavailable", hub));
            }
            // CONNECTION_CANCELLED: ServerGuard already told the player why (login first).
        });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }

    private Component text(String key, String server) {
        return LegacyComponentSerializer.legacyAmpersand()
                .deserialize(config.message("network." + key).replace("{server}", server));
    }
}
