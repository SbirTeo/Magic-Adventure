package com.teolo.magixlanguage.hook;

import com.teolo.magixlanguage.MagixLanguage;
import com.teolo.magixlanguage.translate.PlayerLocales;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.Locale;

/**
 * Espansione PlaceholderAPI di MagixLanguage: rende disponibile la lingua rilevata/scelta in
 * qualsiasi altro plugin o config che usi PlaceholderAPI (scoreboard, tab, sito...).
 *
 *   %magixlanguage_lang%     -> codice lingua attuale (it, en, es, de)
 *   %magixlanguage_country%  -> paese rilevato dal GeoIP (- se nessuno o rilevazione disattivata)
 *   %magixlanguage_source%   -> "geoip" o "manual"
 */
public final class MagixLanguagePlaceholders extends PlaceholderExpansion {

    /** Elenco per la guida staff (StaffGuide.placeholders): coppie placeholder, cosa mostra.
     *  check_config.py [9] blocca il commit se qui manca un placeholder risolto sotto. */
    public static final String[] DOCS = {
            "%magixlanguage_lang%", "Codice della lingua del giocatore (it, en, es, de).",
            "%magixlanguage_country%", "Paese rilevato dal GeoIP (- se nessuno o rilevazione spenta).",
            "%magixlanguage_source%", "Da dove viene la lingua: geoip (rilevata) o manual (scelta dal giocatore).",
    };

    private final MagixLanguage plugin;

    public MagixLanguagePlaceholders(MagixLanguage plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() { return "magixlanguage"; }

    @Override
    public String getAuthor() { return "teolo"; }

    @Override
    public String getVersion() { return plugin.getPluginMeta().getVersion(); }

    @Override
    public boolean persist() { return true; } // resta registrato dopo /papi reload

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) {
            return "";
        }
        PlayerLocales.Entry entry = plugin.locales().get(player.getUniqueId());
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "lang" -> entry != null ? entry.lang() : plugin.getConfig().getString("default-language", "it");
            case "country" -> entry != null && entry.country() != null ? entry.country() : "-";
            case "source" -> entry != null && entry.source() == PlayerLocales.Source.MANUAL ? "manual" : "geoip";
            default -> null;
        };
    }
}
