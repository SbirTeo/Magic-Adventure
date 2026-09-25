package com.teolo.magixscoreboard.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Accesso opzionale a PlaceholderAPI: risolve i placeholder dentro il titolo e le righe.
 * Copre sia i placeholder standard (%player_name%, %server_online%...) sia quelli registrati dagli
 * altri plugin Magix, che si espongono ognuno come propria expansion PAPI (%magixfactions_...%,
 * %magixtime_...%, %magixweb_...%...): non serve un collegamento dedicato per ciascuno.
 */
public final class Papi {
    private static boolean available;

    public static void setup() {
        available = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    public static boolean enabled() { return available; }

    public static String resolve(Player player, String text) {
        if (!available) return text;
        try {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        } catch (Throwable t) {
            return text;
        }
    }
}
