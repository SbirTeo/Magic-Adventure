package com.teolo.magixproxy;

import com.google.inject.Inject;
import com.teolo.magixproxy.db.AccountDao;
import com.teolo.magixproxy.db.Database;
import com.teolo.magixproxy.profile.MojangLookup;
import com.teolo.magixproxy.profile.ProfileListener;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * The MagicAdventure network, seen from the Velocity proxy.
 *
 * Today it does one job: it decides, once and at the proxy door, the UUID and the skin a
 * player enters with, with the same rules MagixAuth applies on the servers. The proxy and every
 * server behind it must agree on the UUID; a backend changing it on its own would leave the
 * proxy with a different one (tab list, server switches, /glist).
 */
@Plugin(
        id = "magixproxy",
        name = "MagixProxy",
        version = "0.1.0",
        description = "The MagicAdventure network on the Velocity proxy",
        url = "https://magicadventure.it",
        authors = {"teolo"}
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

        database = new Database(config);
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
        log.info("MagixProxy: attivo (UUID e skin decisi dal proxy, skin da Mojang {}).",
                config.skinFromMojang ? "accesa" : "spenta");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (database != null) {
            database.close();
        }
    }
}
