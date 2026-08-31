package com.teolo.magixfactions.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Accesso opzionale a PlaceholderAPI per risolvere i placeholder nelle condizioni. */
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

    /**
     * Come sopra ma per un giocatore che puo' essere OFFLINE: serve alla chat del sito, dove
     * chi scrive non e' per forza in partita ma il suo prefisso (grado LuckPerms) va risolto
     * lo stesso. PlaceholderAPI espone l'overload apposta.
     */
    public static String resolve(org.bukkit.OfflinePlayer player, String text) {
        if (!available) return text;
        try {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        } catch (Throwable t) {
            return text;
        }
    }
}
