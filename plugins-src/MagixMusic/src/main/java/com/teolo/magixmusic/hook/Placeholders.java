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
 *   %magixmusic_volume%    -> volume personale 0-100 (0 = radio spenta per lui)
 *   %magixmusic_bar%       -> barra a 10 segmenti del volume (es. ███████░░░)
 *   %magixmusic_state%     -> "Accesa" se il volume &gt; 0, altrimenti "Spenta"
 *   %magixmusic_track%     -> nome del brano in onda (o "—" se nulla)
 *   %magixmusic_next%      -> nome del brano successivo (o "—")
 *   %magixmusic_enabled%   -> 1 se la radio e' accesa in generale (config), altrimenti 0
 *   %magixmusic_elapsed%   -> tempo trascorso del brano, mm:ss
 *   %magixmusic_duration%  -> durata del brano, mm:ss
 *   %magixmusic_remaining% -> tempo rimanente del brano, mm:ss
 *   %magixmusic_time%      -> "trascorso / durata" (es. 1:12 / 3:05)
 *   %magixmusic_progress%  -> barra a 10 segmenti dell'avanzamento del brano
 *   %magixmusic_count%     -> quanti brani ha la scaletta
 *   %magixmusic_index%     -> posizione del brano in onda nella scaletta (da 1)
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
        // Placeholder GLOBALI (non dipendono dal giocatore): stato della radio.
        switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "enabled":
                return radio.isEnabled() ? "1" : "0";
            case "track": {
                String n = radio.currentTrackName();
                return n == null ? "—" : n;
            }
            case "next": {
                String n = radio.nextTrackName();
                return n == null ? "—" : n;
            }
            case "elapsed":
                return time(radio.elapsedSeconds());
            case "duration":
                return time(radio.durationSeconds());
            case "remaining":
                return time(Math.max(0, radio.durationSeconds() - radio.elapsedSeconds()));
            case "time":
                return time(radio.elapsedSeconds()) + " / " + time(radio.durationSeconds());
            case "progress":
                return bar(radio.durationSeconds() <= 0 ? 0
                        : Math.round(radio.elapsedSeconds() * 100.0f / radio.durationSeconds()));
            case "count":
                return String.valueOf(radio.trackCount());
            case "index":
                return String.valueOf(radio.currentIndex());
            default:
                break;
        }
        // Placeholder che dipendono dal GIOCATORE: senza un giocatore non hanno senso.
        if (player == null) return "";
        int volume = radio.effectiveVolume(player.getUniqueId());
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "volume" -> String.valueOf(volume);
            case "state" -> volume > 0 ? "Accesa" : "Spenta";
            case "bar" -> bar(volume);
            default -> null;
        };
    }

    /** Barra a {@link #BAR_SEGMENTS} segmenti: pieni fino alla percentuale (0-100), vuoti il resto. */
    private static String bar(int percent) {
        int filled = Math.max(0, Math.min(BAR_SEGMENTS, Math.round(percent / 100.0f * BAR_SEGMENTS)));
        StringBuilder sb = new StringBuilder(BAR_SEGMENTS);
        for (int i = 0; i < BAR_SEGMENTS; i++) sb.append(i < filled ? '█' : '░');
        return sb.toString();
    }

    /** Secondi in m:ss. */
    private static String time(int totalSeconds) {
        int s = Math.max(0, totalSeconds);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }
}
