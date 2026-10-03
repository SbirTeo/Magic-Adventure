package com.teolo.magixproxy;

import com.google.inject.Inject;
import com.teolo.magixproxy.command.HubCommand;
import com.teolo.magixproxy.db.AccountDao;
import com.teolo.magixproxy.db.Database;
import com.teolo.magixproxy.lang.LanguageBridge;
import com.teolo.magixproxy.motd.MotdListener;
import com.teolo.magixproxy.network.ServerGuard;
import com.teolo.magixproxy.network.SwitchCookies;
import com.teolo.magixproxy.profile.MojangLookup;
import com.teolo.magixproxy.profile.ProfileListener;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * The MagicAdventure network, seen from the Velocity proxy.
 *
 * It decides, once and at the proxy door, the UUID and the skin a player enters with, with the
 * same rules MagixAuth applies on the servers: the proxy and every server behind it must agree
 * on the UUID (see ProfileListener). And it keeps the network walked from the main server: first
 * entry there, other servers only after the MagixAuth login (see ServerGuard). And it answers the
 * server-list ping with the MagixEssentials MOTD (see motd/MotdListener).
 */
@Plugin(
        id = "magixproxy",
        name = "MagixProxy",
        version = "0.7.0",
        description = "The MagicAdventure network on the Velocity proxy",
        url = "https://magicadventure.it",
        authors = {"teolo"},
        // Optional: with it, the texts reach every player in his language (see LanguageBridge).
        dependencies = {@Dependency(id = "magixlanguage", optional = true)}
)
public final class MagixProxy {

    private final ProxyServer proxy;
    private final Logger log;
    private final Path dataDirectory;

    private Database database;

    @Inject
    public MagixProxy(ProxyServer proxy, Logger log, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.log = log;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        ProxyConfig config;
        try {
            config = ProxyConfig.load(dataDirectory, log);
        } catch (Exception e) {
            // Without the database settings nobody can be recognised: better say it loudly at
            // startup than refuse every player later without a reason in the log.
            log.error("MagixProxy: config.yml/messages.yml illeggibili ({}). Plugin NON attivo.", e.toString());
            return;
        }

        config.useLanguage(LanguageBridge.find(proxy, log));

        database = new Database(config, log);
        if (!database.reachable()) {
            log.warn("MagixProxy: il database del sito non risponde ora; si riprova a ogni ingresso.");
        }

        MojangLookup mojang = new MojangLookup(config.premiumTimeoutMillis, config.skinCacheMinutes, log,
                dataDirectory.resolve("skins.tsv"));
        if (config.skinFromMojang) {
            // The first HTTPS call of a JVM pays for TLS and DNS all at once: better now than
            // on the first player who connects.
            proxy.getScheduler().buildTask(this, mojang::warmUp).schedule();
        }

        proxy.getEventManager().register(this,
                new ProfileListener(config, new AccountDao(database), mojang, log));
        proxy.getEventManager().register(this, new ServerGuard(proxy, config, database, log));
        // Cookie requests of a server being switched to stay on the proxy (see SwitchCookies).
        proxy.getEventManager().register(this, new SwitchCookies());
        HubCommand.register(this, proxy, config);
        if (config.motdEnabled && !config.motdFile.isEmpty()) {
            proxy.getEventManager().register(this, new MotdListener(proxy, log,
                    Path.of(config.motdFile).toAbsolutePath().normalize(),
                    config.motdDefaultIcon.isEmpty() ? null : Path.of(config.motdDefaultIcon).toAbsolutePath().normalize()));
        }
        if (proxy.getServer(config.mainServer).isEmpty()) {
            log.error("MagixProxy: network.main_server \"{}\" non è fra i [servers] di velocity.toml: "
                    + "nessuno potrà entrare finché non si corregge.", config.mainServer);
        }
        log.info("MagixProxy: attivo (UUID e skin decisi dal proxy, skin da Mojang {}; server principale {}, "
                        + "altri server {}, uscendo da un altro server si torna al principale: {}).",
                config.skinFromMojang ? "accesa" : "spenta", config.mainServer,
                config.othersRequireLogin ? "solo dopo il login" : "liberi",
                config.fallbackToMain ? "sì" : "no");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (database != null) {
            database.close();
        }
    }
}
