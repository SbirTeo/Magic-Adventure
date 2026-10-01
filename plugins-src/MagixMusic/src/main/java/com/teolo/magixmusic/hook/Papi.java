package com.teolo.magixmusic.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Accesso opzionale a PlaceholderAPI: risolve nel testo i placeholder standard
 * (%player_name%, %server_online%...) e quelli che gli altri plugin Magix espongono come propria
 * expansion PAPI (%magixfactions_...%, %magixpack_glyph_...%, %magixtime_...%...) — non serve un
 * collegamento dedicato per ciascuno. Softdepend, mai un'eccezione: senza PlaceholderAPI installato
 * il testo torna invariato.
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

    /**
     * Come sopra ma per un giocatore che puo' essere OFFLINE: serve a chi risolve un messaggio
     * per qualcuno non online (es. la chat del sito, un comando dato da console su un nome).
     * PlaceholderAPI espone l'overload apposta.
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
