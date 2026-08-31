package com.teolo.magixweb.rank;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The PlaceholderAPI placeholders MagixWeb offers.
 *
 * <p><b>%magixweb_namecolor%</b> — the colour code ({@code &#RRGGBB}) of the player's
 * highest-weight group, the SAME one the site uses to write their name. Chat formats need it:
 * without it the name inherits the last colour of the prefix — so the LOWEST group when prefixes
 * are stacked — and the game stops matching the site.
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
        return true; // stays registered across a /papi reload
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (!"namecolor".equalsIgnoreCase(params)) {
            return null; // unknown placeholder: PAPI leaves it as it is
        }
        if (!(player instanceof Player online) || !online.isOnline()) {
            return "";
        }
        String color = rankSync.nameColorOf(online);
        return color == null ? "" : "&" + color; // "#FF5555" -> "&#FF5555"
    }
}
