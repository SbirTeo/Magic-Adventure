package com.teolo.magixpack.command;

import com.teolo.magixpack.MagixPack;
import com.teolo.magixpack.util.Colors;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/** L'unico comando di amministrazione: /mpack reload. Il permesso (magixpack.admin) e' gia'
 *  dichiarato sul comando in plugin.yml, quindi Bukkit filtra da solo chi non ce l'ha. */
public final class MagixPackCommand implements CommandExecutor {

    private final MagixPack plugin;

    public MagixPackCommand(MagixPack plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reload();
            sender.sendMessage(Colors.translate("&aMagixPack ricaricato."));
            return true;
        }
        sender.sendMessage(Colors.translate("&eUso: &a/mpack reload"));
        return true;
    }
}
