package com.teolo.magixmusic.hook;

import com.teolo.magixmusic.MagixMusic;
import com.teolo.magixmusic.radio.RadioService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * Espansione PlaceholderAPI di MagixMusic: rende disponibili i placeholder %magixmusic_...% a
 * qualsiasi plugin/config che usi PlaceholderAPI. Servono soprattutto al menu di MagixMenus per
 * mostrare in DIRETTA il volume, la barra e il brano in onda del singolo giocatore.
 *
 *   %magixmusic_volume%   -> volume personale 0-100 (0 = radio spenta per lui)
 *   %magixmusic_bar%      -> barra a 10 segmenti del volume (es. ███████░░░)
 *   %magixmusic_state%    -> "Accesa" se il volume &gt; 0, altrimenti "Spenta"
 *   %magixmusic_track%    -> nome del brano in onda (o "—" se nulla)
 *   %magixmusic_enabled%  -> 1 se la radio e' accesa in generale (config), altrimenti 0
 */
public final class Placeholders extends PlaceholderExpansion {

    private static final int BAR_SEGMENTS = 10;

    private final MagixMusic plugin;
    private final RadioService radio;

    public Placeholders(MagixMusic plugin, RadioService radio) {
        this.plugin = plugin;
        this.radio = radio;
    }

    @Override
    public String getIdentifier() { return "magixmusic"; }

    @Override
    public String getAuthor() { return "teolo"; }

    @Override
    public String getVersion() { return plugin.getPluginMeta().getVersion(); }

    @Override
    public boolean persist() { return true; } // resta registrato dopo /papi reload

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "enabled":
                return radio.isEnabled() ? "1" : "0";
            case "track": {
                String n = radio.currentTrackName();
                return n == null ? "—" : n;
            }
            default:
                break;
        }
        // I placeholder che dipendono dal giocatore: senza un giocatore non hanno senso.
        if (player == null) return "";
        int volume = radio.effectiveVolume(player.getUniqueId());
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "volume" -> String.valueOf(volume);
            case "state" -> volume > 0 ? "Accesa" : "Spenta";
            case "bar" -> bar(volume);
            default -> null;
        };
    }

    /** Barra a {@link #BAR_SEGMENTS} segmenti: pieni fino al volume, vuoti il resto. */
    private static String bar(int volume) {
        int filled = Math.round(volume / 100.0f * BAR_SEGMENTS);
        StringBuilder sb = new StringBuilder(BAR_SEGMENTS);
        for (int i = 0; i < BAR_SEGMENTS; i++) sb.append(i < filled ? '█' : '░');
        return sb.toString();
    }
}
