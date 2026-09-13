package com.teolo.magixpack;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * MagixPack: il proprietario UNICO del resource pack del server.
 *
 * <p>Il client Minecraft applica UN solo pacchetto: tenerlo in un posto solo evita che due
 * pacchetti si scartino a vicenda. MagixPack costruisce e serve quell'unico pacchetto; gli altri
 * plugin ("fornitori") ci mettono i loro asset depositandoli sotto
 * {@code plugins/MagixPack/contrib/<plugin>/<path-nel-pack>} — vedi {@link PackService}.
 *
 * <p><b>Interruttore.</b> Con {@code pack.enabled: false} (default durante la migrazione) MagixPack
 * resta inerte: non avvia il server HTTP e non tocca nulla, cosi' puo' convivere con MagixFactions
 * che per ora serve ancora il pacchetto. Va acceso solo quando MagixFactions smette di servirlo,
 * altrimenti i due pacchetti si scarterebbero a vicenda.
 */
public final class MagixPack extends JavaPlugin {

    private PackService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getConfig().getBoolean("pack.enabled", false)) {
            getLogger().info("MagixPack abilitato (pacchetto DISATTIVO: il resource pack e' ancora servito da MagixFactions).");
            return;
        }
        service = new PackService(this);
        // I fornitori depositano i loro asset durante il PROPRIO onEnable: l'ordine fra plugin non e'
        // garantito, quindi la costruzione/avvio del pacchetto si rimanda di qualche tick, quando
        // tutti hanno gia' scritto. Un task schedulato all'enable parte comunque a server avviato.
        long delay = Math.max(1L, getConfig().getLong("pack.rebuild-delay-ticks", 40));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            service.start();
            if (service.isAvailable()) {
                Bukkit.getPluginManager().registerEvents(new PackJoinListener(this, service), this);
            }
        }, delay);
    }

    @Override
    public void onDisable() {
        if (service != null) service.stop();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("rebuild")) {
            if (service == null) { sender.sendMessage("§dMagixPack §8» §7Pacchetto disattivo (pack.enabled: false)."); return true; }
            boolean ok = service.rebuild();
            sender.sendMessage("§dMagixPack §8» §7" + (ok ? "Pacchetto ricostruito." : "Ricostruzione fallita (vedi console)."));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("§dMagixPack §8» §7Config ricaricata. §8(porta/host cambiano solo al riavvio; per rifare lo zip usa §f/" + label + " rebuild§8)");
            return true;
        }
        sender.sendMessage("§dMagixPack §8» §7Uso: §f/" + label + " rebuild§7 | §f/" + label + " reload");
        return true;
    }
}
