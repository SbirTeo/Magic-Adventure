package com.teolo.magixmenus.hook;

import com.teolo.magixmenus.MagixMenus;
import com.teolo.magixmenus.menu.MenuAperto;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * I placeholder %magixmenus_...%, utilizzabili ovunque legga PlaceholderAPI (tab, scoreboard,
 * altri menu, il sito).
 *
 * <pre>
 * %magixmenus_aperto%      il menu che il giocatore ha aperto adesso, vuoto se nessuno
 * %magixmenus_pagina%      la pagina in cui si trova
 * %magixmenus_pagine%      quante pagine ha
 * %magixmenus_quanti%      quanti menu esistono sul server
 * %magixmenus_aperti%      quanti menu ci sono aperti in questo momento
 * %magixmenus_esiste_<nome>% si/no
 * </pre>
 *
 * Il primo serve piu' di quanto sembri: permette a un item di un menu di sapere da dove si sta
 * guardando, e quindi a uno stesso menu condiviso di comportarsi in due modi.
 */
public final class Placeholders extends PlaceholderExpansion {

    private final MagixMenus plugin;

    public Placeholders(MagixMenus plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "magixmenus";
    }

    @Override
    public String getAuthor() {
        return "teolo";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;   // sopravvive a /papi reload: altrimenti i menu smetterebbero di rispondere
    }

    @Override
    public String onRequest(OfflinePlayer chi, String parametro) {
        String p = parametro.toLowerCase(Locale.ROOT);

        if (p.equals("quanti")) {
            return String.valueOf(plugin.menu().quanti());
        }
        if (p.equals("aperti")) {
            return String.valueOf(plugin.menu().quantiAperti());
        }
        if (p.startsWith("esiste_")) {
            return plugin.menu().trova(p.substring(7)) != null ? "si" : "no";
        }

        if (!(chi instanceof Player giocatore)) {
            return "";
        }
        MenuAperto aperto = plugin.menu().apertoDi(giocatore);
        return switch (p) {
            case "aperto" -> aperto == null ? "" : aperto.definizione().nome();
            case "pagina" -> aperto == null ? "" : aperto.variabili().getOrDefault("pagina", "1");
            case "pagine" -> aperto == null ? "" : aperto.variabili().getOrDefault("pagine", "1");
            default -> null;
        };
    }
}
