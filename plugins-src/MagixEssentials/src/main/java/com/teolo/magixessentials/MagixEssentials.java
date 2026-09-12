package com.teolo.magixessentials;

import com.teolo.magixessentials.tab.TabManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * MagixEssentials: raccoglie le utilita' "di base" del server. Per ora gestisce SOLO il tablist
 * (la lista giocatori, tasto Tab); l'idea a lungo termine e' che assorba cio' che oggi fa CMI.
 *
 * <p>Il tablist e' volutamente separato in {@link TabManager}: la classe principale si limita ad
 * accenderlo/spegnerlo e a offrire {@code /magixessentials reload}.
 */
public final class MagixEssentials extends JavaPlugin {

    private TabManager tabManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tabManager = new TabManager(this);
        tabManager.start();
        getLogger().info("MagixEssentials abilitato (tablist "
                + (getConfig().getBoolean("tablist.enabled", true) ? "attivo" : "disattivato") + ").");
    }

    @Override
    public void onDisable() {
        if (tabManager != null) tabManager.stop();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            if (tabManager != null) { tabManager.stop(); tabManager.start(); }
            sender.sendMessage("§dMagixEssentials §8» §7Configurazione ricaricata.");
            return true;
        }
        sender.sendMessage("§dMagixEssentials §8» §7Uso: §f/" + label + " reload");
        return true;
    }
}
