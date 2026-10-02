package com.teolo.magixproxy.profile;

import com.teolo.magixproxy.ProxyConfig;
import com.teolo.magixproxy.db.AccountDao;
import com.teolo.magixproxy.db.Database;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is entering: UUID and skin, decided at the proxy door.
 *
 * Same rules as MagixAuth's gate (AuthGate.decide), so that a backend receives, through modern
 * forwarding, exactly the profile it would have built by itself: the UUID of the site account
 * if the name has one, the offline UUID of the name otherwise, and the Mojang-signed skin.
 * MagixAuth on the backend then sees the proposed UUID already right and changes nothing.
 */
public final class ProfileListener {

    private final ProxyConfig config;
    private final AccountDao accounts;
    private final MojangLookup mojang;
    private final Logger log;

    /** Names whose account could not be read: they are refused at LoginEvent. */
    private final Set<String> unknown = ConcurrentHashMap.newKeySet();

    public ProfileListener(ProxyConfig config, AccountDao accounts, MojangLookup mojang, Logger log) {
        this.config = config;
        this.accounts = accounts;
        this.mojang = mojang;
        this.log = log;
    }

    @Subscribe
    public EventTask onGameProfileRequest(GameProfileRequestEvent event) {
        // Database and Mojang are network calls: never on the proxy's I/O threads.
        return EventTask.async(() -> decide(event));
    }

    private void decide(GameProfileRequestEvent event) {
        String name = event.getUsername();
        UUID uuid;
        try {
            UUID account = accounts.uuidOf(name);
            uuid = account != null ? account : AccountDao.offlineUuid(name);
        } catch (SQLException e) {
            // Nobody can say who this is. Same choice as MagixAuth: keep them out for a few
            // minutes rather than let anyone in with someone else's UUID.
            log.warn("MagixProxy: database non raggiungibile all'ingresso di {} ({}).", name, Database.describe(e));
            unknown.add(key(name));
            return;
        }

        GameProfile original = event.getGameProfile();
        List<GameProfile.Property> properties = new ArrayList<>(original.getProperties());
        if (config.skinFromMojang) {
            MojangLookup.Found found = mojang.skinOf(uuid, name);
            if (found.failed()) {
                log.warn("MagixProxy: skin di {} non recuperata ({}). Si riprova al prossimo ingresso.",
                        name, found.reason());
            }
            String[] skin = found.skin();
            if (skin != null) {
                properties.removeIf(p -> "textures".equals(p.getName()));
                // The Mojang signature must stay: without it the client drops a skin it did not
                // ask for and keeps the default one.
                properties.add(new GameProfile.Property("textures", skin[0],
                        skin.length > 1 && skin[1] != null ? skin[1] : ""));
            }
        }
        event.setGameProfile(new GameProfile(uuid, original.getName(), properties));
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        if (unknown.remove(key(event.getPlayer().getUsername()))) {
            event.setResult(ResultedEvent.ComponentResult.denied(
                    LegacyComponentSerializer.legacyAmpersand().deserialize(config.message("login.kick-database"))));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        unknown.remove(key(event.getPlayer().getUsername()));
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
