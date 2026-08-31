package com.teolo.magixmenus.hook;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Il ponte con l'economia del server, se c'e'.
 *
 * Vault e' un softdepend: senza, MagixMenus funziona lo stesso e i requisiti sui soldi
 * rispondono "no" invece di far saltare il menu. Il collegamento si tenta una volta all'avvio e
 * si puo' ritentare a ogni reload, perche' un plugin di economia puo' essere installato dopo.
 */
public final class EconomyHook {

    private static Economy economia;

    private EconomyHook() {
    }

    /** Cerca un plugin di economia registrato su Vault. Chiamata all'avvio e a ogni reload. */
    public static void collega() {
        economia = null;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        try {
            RegisteredServiceProvider<Economy> p = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (p != null) {
                economia = p.getProvider();
            }
        } catch (Throwable ignored) {
            // Vault presente ma senza nessuna economia dietro: e' un caso normale, non un guasto.
        }
    }

    public static boolean disponibile() {
        return economia != null;
    }

    public static String nome() {
        return economia == null ? "nessuna" : economia.getName();
    }

    public static double saldo(Player p) {
        return economia == null ? 0 : economia.getBalance(p);
    }

    public static void dai(Player p, double quanto) {
        if (economia != null && quanto > 0) {
            economia.depositPlayer(p, quanto);
        }
    }

    /** @return false se i soldi non bastavano: in quel caso non viene tolto niente. */
    public static boolean togli(Player p, double quanto) {
        if (economia == null) {
            return false;
        }
        if (quanto <= 0) {
            return true;
        }
        if (economia.getBalance(p) < quanto) {
            return false;
        }
        return economia.withdrawPlayer(p, quanto).transactionSuccess();
    }
}
