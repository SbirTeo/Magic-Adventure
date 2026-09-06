package com.teolo.magixfactions.hook;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Accesso opzionale all'economia tramite Vault. Si attiva solo se Vault + economia sono presenti. */
public final class Econ {
    private static Economy economy;

    public static void setup() { ensure(); }

    /** Risoluzione "pigra": riprova finche' un provider economia (es. CMI) non si registra. */
    private static void ensure() {
        if (economy != null) return;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    public static boolean enabled() { ensure(); return economy != null; }

    public static boolean has(OfflinePlayer p, double amount) {
        ensure();
        return economy != null && economy.has(p, amount);
    }

    /** Saldo attuale del giocatore (0 se non c'e' economia). Usato per la giacenza media personale. */
    public static double balance(OfflinePlayer p) {
        ensure();
        return economy == null ? 0 : economy.getBalance(p);
    }

    public static boolean withdraw(OfflinePlayer p, double amount) {
        ensure();
        if (economy == null) return false;
        return economy.withdrawPlayer(p, amount).transactionSuccess();
    }

    /** Accredita denaro al giocatore (per i prelievi dalla banca di fazione). */
    public static boolean deposit(OfflinePlayer p, double amount) {
        ensure();
        if (economy == null) return false;
        return economy.depositPlayer(p, amount).transactionSuccess();
    }

    /** Importo formattato dal provider economia (es. "1.000$"); fallback semplice senza economia. */
    public static String format(double amount) {
        ensure();
        if (economy != null) return economy.format(amount);
        return amount == Math.rint(amount) ? String.valueOf((long) amount) : String.valueOf(amount);
    }
}
