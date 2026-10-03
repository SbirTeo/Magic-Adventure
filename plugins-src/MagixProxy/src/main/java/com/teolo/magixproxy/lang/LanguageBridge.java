package com.teolo.magixproxy.lang;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

/**
 * MagixLanguage on the proxy, if it is installed: the texts of messages.yml in the language each
 * player chose on the network.
 *
 * MagixLanguage translates this plugin's messages.yml by itself (it translates every Magix plugin
 * installed where it runs) and answers {@code translate(plugin, uuid, key, placeholders)}. It is
 * looked up through the plugin manager, without a compile dependency: missing plugin, missing
 * translation or any error means null, and the caller keeps the Italian of messages.yml.
 */
public final class LanguageBridge {

    /** The name MagixLanguage files this plugin's texts under: its data folder, i.e. its id. */
    private static final String CATALOG = "magixproxy";

    private final Object instance;
    private final Method translate;

    private LanguageBridge(Object instance, Method translate) {
        this.instance = instance;
        this.translate = translate;
    }

    /** The bridge, or null when MagixLanguage is not on the proxy (texts stay Italian). */
    public static LanguageBridge find(ProxyServer proxy, Logger log) {
        Object instance = proxy.getPluginManager().getPlugin("magixlanguage")
                .flatMap(c -> c.getInstance()).orElse(null);
        if (instance == null) {
            log.info("MagixProxy: MagixLanguage non c'è sul proxy: i testi restano in italiano.");
            return null;
        }
        try {
            Method m = instance.getClass().getMethod("translate", String.class, UUID.class, String.class, Map.class);
            log.info("MagixProxy: testi nella lingua di ogni giocatore (MagixLanguage).");
            return new LanguageBridge(instance, m);
        } catch (NoSuchMethodException e) {
            log.warn("MagixProxy: questa versione di MagixLanguage non traduce i plugin del proxy: testi in italiano.");
            return null;
        }
    }

    /** The translated text of {@code key}, or null. */
    public String translate(UUID playerId, String key) {
        try {
            Object text = translate.invoke(instance, CATALOG, playerId, key, Map.of());
            return text instanceof String s ? s : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
