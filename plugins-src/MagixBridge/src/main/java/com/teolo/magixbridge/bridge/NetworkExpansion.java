package com.teolo.magixbridge.bridge;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * %network_...%: the placeholders of the other game modes, read from the database (see
 * PlaceholderBridge). The server name is the first piece, up to the first underscore: server
 * names (network.server-name) have none.
 */
public final class NetworkExpansion extends PlaceholderExpansion {

    /** Staff guide list (StaffGuide.placeholders): pairs placeholder, what it shows.
     *  check_config.py [9] blocks the commit if a placeholder resolved below is missing here. */
    public static final String[] DOCS = {
            "%network_<server>_<placeholder>%", "Il valore di un placeholder di un'altra modalita', "
                    + "senza il suo plugin installato qui: <server> e' il network.server-name di quel "
                    + "server, <placeholder> quello originale senza i %. Esempio sull'hub: "
                    + "%network_faction_magixfactions_faction% = la fazione del giocatore. Funziona solo "
                    + "per i placeholder che quel server pubblica (bridge.player-placeholders e "
                    + "bridge.global-placeholders del SUO config); vuoto finche' non e' mai stato pubblicato.",
            "%network_<server>_online%", "Quanti giocatori sono connessi adesso su quel server (es. "
                    + "%network_faction_online%).",
            "%network_online%", "Quanti giocatori sono connessi adesso su tutta la rete.",
    };

    private final PlaceholderBridge bridge;
    private final String version;

    public NetworkExpansion(PlaceholderBridge bridge, String version) {
        this.bridge = bridge;
        this.version = version;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "network";
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
        String p = params.toLowerCase(Locale.ROOT);
        if (p.equals("online")) {
            return String.valueOf(bridge.onlineTotal());
        }
        int cut = p.indexOf('_');
        if (cut <= 0 || cut == p.length() - 1) {
            return null; // not <server>_<something>: PAPI leaves it as it is
        }
        String server = p.substring(0, cut);
        String key = p.substring(cut + 1);
        if (key.equals("online")) {
            return String.valueOf(bridge.onlineOn(server));
        }
        String value = bridge.value(server, player == null ? null : player.getUniqueId(), key);
        return value == null ? "" : value;
    }
}
