package com.teolo.magixtime.hook;

import com.teolo.magixtime.MagixTime;
import com.teolo.magixtime.season.SeasonDef;
import com.teolo.magixtime.time.TimeSync;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * Espansione PlaceholderAPI di MagixTime: rende disponibili i placeholder
 * %magixtime_...% in qualsiasi plugin/config che usi PlaceholderAPI (scoreboard CMI, tab, sito...).
 *
 *   %magixtime_season%        -> nome colorato della stagione (es. &bInverno)
 *   %magixtime_season_key%    -> chiave della stagione (winter, summer, ...)
 *   %magixtime_next_season%   -> nome della prossima stagione
 *   %magixtime_days_to_next%  -> giorni reali al cambio di stagione
 *   %magixtime_time%          -> ora reale HH:mm (fuso del config)
 *   %magixtime_time_seconds%  -> ora reale HH:mm:ss
 *   %magixtime_date%          -> data reale dd/MM/yyyy
 *   %magixtime_timezone%      -> fuso orario usato
 *   %magixtime_mc_time%       -> ora di gioco HH:mm
 *   %magixtime_mc_ticks%      -> tick del mondo (0-23999)
 *   %magixtime_weather%       -> Sereno / Pioggia / Temporale
 *   %magixtime_weather_next%  -> minuti al prossimo cambio di meteo
 *   %magixtime_snow%          -> si/no, se la stagione accumula neve
 */
public final class MagixTimePlaceholders extends PlaceholderExpansion {

    private final MagixTime plugin;

    public MagixTimePlaceholders(MagixTime plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() { return "magixtime"; }

    @Override
    public String getAuthor() { return "teolo"; }

    @Override
    public String getVersion() { return plugin.getPluginMeta().getVersion(); }

    @Override
    public boolean persist() { return true; } // resta registrato dopo /papi reload

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        SeasonDef season = plugin.seasons().current();
        SeasonDef next = plugin.seasons().next();
        World w = worldOf(player);
        TimeSync time = plugin.time();

        return switch (params.toLowerCase(Locale.ROOT)) {
            case "season" -> season != null ? season.display() : "";
            case "season_key" -> season != null ? season.key() : "";
            case "next_season" -> next != null ? next.display() : "";
            case "days_to_next" -> String.valueOf(plugin.seasons().daysToNext());
            case "time" -> time.realTimeShort();
            case "time_seconds" -> time.realTime();
            case "date" -> time.realDate();
            case "timezone" -> plugin.zone().getId();
            case "mc_time" -> TimeSync.formatTicks(w != null ? w.getTime() : time.currentMinecraftTicks());
            case "mc_ticks" -> String.valueOf(w != null ? w.getTime() : time.currentMinecraftTicks());
            case "weather" -> plugin.weather().describe(w);
            case "weather_next" -> String.valueOf(plugin.weather().minutesLeft(w));
            case "snow" -> season != null && season.snowAccumulate() ? "si" : "no";
            default -> null; // placeholder sconosciuto
        };
    }

    /** Il mondo del giocatore se e' online e gestito, altrimenti il primo mondo gestito. */
    private World worldOf(OfflinePlayer player) {
        if (player instanceof Player p && plugin.isManaged(p.getWorld())) return p.getWorld();
        List<World> managed = plugin.managedWorlds();
        return managed.isEmpty() ? null : managed.get(0);
    }
}
