package com.teolo.magixpack;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * MagixPack: il proprietario UNICO del resource pack del server.
 *
 * <p>Obiettivo (in migrazione): un solo pacchetto, costruito e servito da qui, in cui gli altri
 * plugin registrano i loro asset — il logo del tablist e le schermate di MagixAuth (oggi dentro
 * MagixFactions), lo shader della minimap, la pergamena del tablist. Il client Minecraft applica
 * UN solo pacchetto: tenerlo in un posto solo evita che due pacchetti si scartino a vicenda.
 *
 * <p><b>Stato attuale (Fase 1):</b> scheletro inerte. Con {@code pack.enabled: false} (default) non
 * avvia nessun server HTTP e non tocca nulla: cosi' puo' stare sul server accanto a MagixFactions
 * (che per ora serve ancora il pacchetto) senza conflitti. La pipeline vera — server HTTP, zip,
 * pacchetto obbligatorio, registrazione degli asset dei plugin — arriva nelle fasi successive.
 */
public final class MagixPack extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getConfig().getBoolean("pack.enabled", false)) {
            getLogger().info("MagixPack abilitato (pacchetto DISATTIVO: il resource pack e' ancora servito da MagixFactions).");
            return;
        }
        // Fase 2+: qui partira' il PackService (server HTTP + zip + pacchetto obbligatorio).
        getLogger().warning("MagixPack: 'pack.enabled' e' true ma la pipeline del pacchetto non e' ancora implementata in questa versione.");
    }

    @Override
    public void onDisable() {
        // Fase 2+: fermare il PackService (server HTTP + watchdog).
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("§dMagixPack §8» §7Configurazione ricaricata.");
            return true;
        }
        sender.sendMessage("§dMagixPack §8» §7Uso: §f/" + label + " reload");
        return true;
    }
}
