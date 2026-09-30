package com.teolo.magixproxy.network;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.CookieRequestEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cookie requests of a server the player is only switching to are not passed to the client.
 *
 * The bug it fixes (seen on 30/09, /server hub from the faction): while logging in, MagixAuth on
 * the hub asks the client for its session cookie. Velocity forwards the request to the client,
 * but the client is still attached to the faction, and its answer reaches the FACTION, which never
 * asked for it: vanilla kicks with multiplayer.disconnect.unexpected_query_response ("unexpected
 * custom data"), and the hub waits in vain until "took too long to log in".
 *
 * So, from ServerPreConnectEvent of a player who already has a server until the new one is
 * connected (or fails), a cookie request is marked handled and never leaves the proxy. The server
 * asking gets no answer: MagixAuth stops waiting after login.cookie_wait_millis and recognises the
 * device by its address, which always works right after the login on the main server. The first
 * entry into the network (no current server) is untouched: the cookie works there as before.
 */
public final class SwitchCookies {

    /** A switch older than this is forgotten anyway (an error nobody reported back). */
    private static final long MAX_SWITCH_MILLIS = 60_000L;

    /** Players switching server, with the time the switch started. */
    private final Map<UUID, Long> switching = new ConcurrentHashMap<>();

    /** After the other listeners (ServerGuard may deny the connection). */
    @Subscribe(order = PostOrder.LAST)
    public void onPreConnect(ServerPreConnectEvent event) {
        if (event.getResult().isAllowed() && event.getPlayer().getCurrentServer().isPresent()) {
            switching.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @Subscribe
    public void onConnected(ServerConnectedEvent event) {
        switching.remove(event.getPlayer().getUniqueId());
        markOnNetwork(event.getPlayer());
    }

    /**
     * The profile property that tells the NEXT server the player is switching, not entering.
     *
     * Holding the request back (above) is not enough: Paper does not finish a login while a cookie
     * request is unanswered, so the hub waited until "took too long to log in" (seen at 17:23). The
     * clean way is that nobody asks: from the first server on, the profile Velocity forwards carries
     * this property, and MagixAuth skips its cookie when it sees it (the address is enough right
     * after the login on the main server). It travels inside modern forwarding, signed with the
     * proxy secret: a client cannot fake it, and all it can change is "ask the cookie or not".
     */
    private static void markOnNetwork(com.velocitypowered.api.proxy.Player player) {
        java.util.List<com.velocitypowered.api.util.GameProfile.Property> props =
                new java.util.ArrayList<>(player.getGameProfileProperties());
        for (com.velocitypowered.api.util.GameProfile.Property p : props) {
            if (NETWORK_PROPERTY.equals(p.getName())) return;
        }
        props.add(new com.velocitypowered.api.util.GameProfile.Property(NETWORK_PROPERTY, "1", ""));
        player.setGameProfileProperties(props);
    }

    /** Same name in MagixAuth (ConnectionListener). */
    public static final String NETWORK_PROPERTY = "magixproxy_network";

    @Subscribe(order = PostOrder.LAST)
    public void onKicked(KickedFromServerEvent event) {
        switching.remove(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        switching.remove(event.getPlayer().getUniqueId());
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onCookieRequest(CookieRequestEvent event) {
        Long since = switching.get(event.getPlayer().getUniqueId());
        if (since == null) {
            return;
        }
        if (System.currentTimeMillis() - since > MAX_SWITCH_MILLIS) {
            switching.remove(event.getPlayer().getUniqueId());
            return;
        }
        event.setResult(CookieRequestEvent.ForwardResult.handled());
    }
}
