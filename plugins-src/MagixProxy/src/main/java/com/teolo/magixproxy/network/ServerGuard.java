package com.teolo.magixproxy.network;

import com.teolo.magixproxy.ProxyConfig;
import com.teolo.magixproxy.db.Database;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Nobody reaches another game mode without passing through the main server.
 *
 * Two rules, checked on every server connection (first entry, /server, a menu sending the
 * player elsewhere, the fallback after a kick):
 *
 *  1. the first server of a connection is always the main one (network.main_server), whatever
 *     the client or the try list asks for;
 *  2. any other server opens only to a player who has already logged in with MagixAuth: a valid
 *     row in auth_sessions for his UUID and his address, the same thing MagixAuth checks to let
 *     him in without the password. Before /login the answer is "stay where you are".
 *
 * MagixAuth runs on every server anyway, so this is not the only lock: it is the one that keeps
 * the player on the main server, as the network is meant to be walked.
 */
public final class ServerGuard {

    private final ProxyServer proxy;
    private final ProxyConfig config;
    private final Database database;
    private final Logger log;

    public ServerGuard(ProxyServer proxy, ProxyConfig config, Database database, Logger log) {
        this.proxy = proxy;
        this.config = config;
        this.database = database;
        this.log = log;
    }

    @Subscribe
    public EventTask onServerPreConnect(ServerPreConnectEvent event) {
        return EventTask.async(() -> decide(event));
    }

    private void decide(ServerPreConnectEvent event) {
        Optional<RegisteredServer> target = event.getResult().getServer();
        if (target.isEmpty()) {
            return;
        }
        String main = config.mainServer;
        Player player = event.getPlayer();
        String wanted = target.get().getServerInfo().getName();
        if (wanted.equalsIgnoreCase(main)) {
            return;
        }

        if (event.getPreviousServer() == null) {
            // First entry: always the main server.
            Optional<RegisteredServer> mainServer = proxy.getServer(main);
            if (mainServer.isPresent()) {
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(mainServer.get()));
            } else {
                log.warn("MagixProxy: il server principale \"{}\" non esiste nella config di Velocity: "
                        + "{} resta fuori.", main, player.getUsername());
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                player.disconnect(text("network.main-missing"));
            }
            return;
        }

        if (!config.othersRequireLogin) {
            return;
        }
        boolean loggedIn;
        try {
            loggedIn = hasSession(player);
        } catch (SQLException e) {
            log.warn("MagixProxy: sessione di {} non verificabile ({}): resta dov'e'.",
                    player.getUsername(), e.getMessage());
            loggedIn = false;
        }
        if (!loggedIn) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.sendMessage(text("network.login-first"));
            log.info("MagixProxy: {} ha chiesto {} senza aver fatto il login: resta dov'e'.",
                    player.getUsername(), wanted);
        }
    }

    /**
     * Thrown out of a server that is not the main one (it is shutting down, restarting, or it kicked
     * him): the player goes to the main server instead of being disconnected from the network.
     *
     * Today that means hub -> faction; the day the hub is the main server, a faction restart sends
     * everyone to the hub. Out of the main server itself the player leaves the network (there is
     * nowhere else to walk from), and a failed attempt to enter another server leaves him where
     * he already is. A ban issued on the server he leaves still holds: the main server refuses him
     * at its own door (MagixGuard checks bans at every login).
     */
    @Subscribe(order = PostOrder.LATE)
    public void onKickedFromServer(KickedFromServerEvent event) {
        if (!config.fallbackToMain || event.kickedDuringServerConnect()) {
            return;
        }
        String from = event.getServer().getServerInfo().getName();
        if (from.equalsIgnoreCase(config.mainServer)) {
            return;
        }
        Optional<RegisteredServer> main = proxy.getServer(config.mainServer);
        if (main.isEmpty()) {
            return;
        }
        String message = config.message("network.moved-to-main").replace("{server}", from);
        event.setResult(KickedFromServerEvent.RedirectPlayer.create(main.get(),
                LegacyComponentSerializer.legacyAmpersand().deserialize(message)));
        log.info("MagixProxy: {} è uscito da {}: lo porto sul server principale {}.",
                event.getPlayer().getUsername(), from, config.mainServer);
    }

    /** A valid MagixAuth session for this player, from the address he is connected from now. */
    private boolean hasSession(Player player) throws SQLException {
        String ip = player.getRemoteAddress() instanceof InetSocketAddress a && a.getAddress() != null
                ? a.getAddress().getHostAddress() : "";
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM auth_sessions WHERE mc_uuid = ? AND ip = ? AND expires_at > NOW() LIMIT 1")) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, ip);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private net.kyori.adventure.text.Component text(String key) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(config.message(key));
    }
}
