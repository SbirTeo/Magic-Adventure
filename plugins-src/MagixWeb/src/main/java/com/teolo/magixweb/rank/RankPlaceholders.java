package com.teolo.magixweb.rank;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Placeholder di PlaceholderAPI esposti da MagixWeb.
 *
 * <p><b>%magixweb_namecolor%</b> — codice colore (formato {@code &#RRGGBB}) del grado di peso
 * piu' alto del giocatore, lo STESSO che il sito usa per scrivere il suo nome. Serve nei formati
 * di chat: senza, il nome eredita l'ultimo colore del prefisso (quindi il grado piu' BASSO se
 * i prefissi sono impilati) e gioco e sito non coincidono.
 */
public class RankPlaceholders extends PlaceholderExpansion {

    private final RankSync rankSync;
    private final String version;

    public RankPlaceholders(RankSync rankSync, String version) {
        this.rankSync = rankSync;
        this.version = version;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "magixweb";
    }

    @Override
    public @NotNull String getAuthor() {
        return "teolo";
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true; // resta registrato tra un /papi reload e l'altro
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (!"namecolor".equalsIgnoreCase(params)) {
            return null; // placeholder sconosciuto: PAPI lo lascia invariato
        }
        if (!(player instanceof Player online) || !online.isOnline()) {
            return "";
        }
        String color = rankSync.nameColorOf(online);
        return color == null ? "" : "&" + color; // "#FF5555" -> "&#FF5555"
    }
}
