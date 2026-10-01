package com.teolo.magixessentials.currency;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * Espansione PlaceholderAPI delle valute: da OGNI voce di {@code currencies.yml} nascono da soli
 * {@code %magixessentials_balance_<id>%} e {@code %magixessentials_name_<id>%} — niente da
 * dichiarare un placeholder alla volta, come per i comandi (vedi {@link CurrencyManager}).
 *
 * <p>Una valuta "shared" vive nel database: {@code balance_} non puo' aspettarlo (PlaceholderAPI
 * vuole una risposta sul colpo), quindi legge una cache tenuta aggiornata da
 * {@link CurrencyManager} — vedi la sua classe per il perche'. Una valuta locale risponde da un
 * file in memoria, sempre sul colpo per davvero.</p>
 *
 * <p>Per farli funzionare anche sui server SENZA MagixEssentials, l'id di una valuta condivisa va
 * aggiunto a {@code bridge.player-placeholders} nel config di MagixBridge: vedi il suo README.</p>
 */
public final class CurrencyPlaceholders extends PlaceholderExpansion {

    /** Elenco per la guida staff (StaffGuide.placeholders): coppie placeholder, cosa mostra.
     *  check_config.py [9] blocca il commit se qui manca un placeholder risolto sotto. */
    public static final String[] DOCS = {
            "%magixessentials_balance_<id>%", "Il saldo del giocatore per la valuta <id> (es. magix). "
                    + "0 (o il saldo di partenza) se non l'ha mai vista.",
            "%magixessentials_name_<id>%", "Il nome mostrato della valuta <id> (es. \"Magix\"), "
                    + "quello di currencies.yml.",
            "%magixessentials_playtime%", "Il tempo di gioco del giocatore su QUESTO server, in secondi "
                    + "(numero crudo, storico vanilla incluso). 0 se non disponibile.",
    };

    private final JavaPlugin plugin;
    private final CurrencyManager manager;

    CurrencyPlaceholders(JavaPlugin plugin, CurrencyManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public String getIdentifier() {
        return "magixessentials";
    }

    @Override
    public String getAuthor() {
        return "teolo";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // resta registrato dopo /papi reload
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String lp = params.toLowerCase(Locale.ROOT);
        if (lp.equals("playtime")) {
            if (player == null) {
                return "";
            }
            try {
                return String.valueOf(player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L); // in tick (20/s)
            } catch (Exception e) {
                return "0";
            }
        }
        if (lp.startsWith("balance_")) {
            if (player == null) {
                return "";
            }
            Currency currency = manager.currency(lp.substring("balance_".length()));
            if (currency == null) {
                return "";
            }
            long balance = currency.shared()
                    ? manager.cachedSharedBalance(currency, player.getUniqueId())
                    : manager.localBalance(currency, player.getUniqueId());
            return String.valueOf(balance);
        }
        if (lp.startsWith("name_")) {
            Currency currency = manager.currency(lp.substring("name_".length()));
            return currency == null ? "" : currency.name();
        }
        return null; // placeholder sconosciuto
    }
}
