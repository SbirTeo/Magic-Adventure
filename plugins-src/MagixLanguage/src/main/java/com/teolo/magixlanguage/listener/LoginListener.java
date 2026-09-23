package com.teolo.magixlanguage.listener;

import com.teolo.magixlanguage.MagixLanguage;
import com.teolo.magixlanguage.geo.GeoLookup;
import com.teolo.magixlanguage.translate.PlayerLocales;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * Decide la lingua di chi sta entrando PRIMA che entri davvero, cosi' e' gia' pronta al primo
 * messaggio di benvenuto di qualunque plugin.
 *
 * <p>{@link AsyncPlayerPreLoginEvent} gira gia' fuori dal thread principale (e' il momento in cui
 * il server verifica nome/skin con Mojang): e' il posto giusto per una chiamata di rete che puo'
 * richiedere fino al timeout configurato, senza bloccare il gioco per nessuno.</p>
 */
public final class LoginListener implements Listener {

    private final MagixLanguage plugin;
    private final PlayerLocales locales;
    private final GeoLookup geo;

    public LoginListener(MagixLanguage plugin, PlayerLocales locales, GeoLookup geo) {
        this.plugin = plugin;
        this.locales = locales;
        this.geo = geo;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return; // chi non entra non ha bisogno di una lingua
        }
        PlayerLocales.Entry existing = locales.get(event.getUniqueId());
        if (existing != null) {
            return; // gia' classificato (a mano o da un ingresso precedente): non lo si ritocca
        }
        String defaultLanguage = plugin.getConfig().getString("default-language", "it");
        if (!plugin.getConfig().getBoolean("geoip.enabled", true)) {
            locales.setDetected(event.getUniqueId(), defaultLanguage, null);
            return;
        }
        String country = geo.countryOf(event.getAddress());
        String lang = country == null ? defaultLanguage
                : plugin.getConfig().getString("country-language." + country, defaultLanguage);
        locales.setDetected(event.getUniqueId(), lang, country);
    }
}
