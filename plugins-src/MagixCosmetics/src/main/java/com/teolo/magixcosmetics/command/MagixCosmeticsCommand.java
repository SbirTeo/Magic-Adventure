package com.teolo.magixcosmetics.command;

import com.teolo.magixcosmetics.MagixCosmetics;
import com.teolo.magixcosmetics.cosmetic.HaloManager;
import com.teolo.magixcosmetics.lang.Messages;
import com.teolo.magixcosmetics.util.Help;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** /magixcosmetics (alias /cosmetics): stato dei propri cosmetici e accensione dell'aureola. */
public final class MagixCosmeticsCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "magixcosmetics.admin";

    private final MagixCosmetics plugin;
    private final Messages msg;

    public MagixCosmeticsCommand(MagixCosmetics plugin, Messages msg) {
        this.plugin = plugin;
        this.msg = msg;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        // Il numero da solo sfoglia l'aiuto: e' quello che mandano le frecce in fondo all'elenco.
        if (!sub.isEmpty() && sub.chars().allMatch(Character::isDigit)) { help(sender, page(sub)); return true; }
        switch (sub) {
            case "info" -> info(sender);
            case "help", "?" -> help(sender, args.length >= 2 ? page(args[1]) : 1);
            case "reload" -> reload(sender);
            default -> msg.send(sender, "unknown-subcommand");
        }
        return true;
    }

    // ------------------------------------------------------------- info

    private void info(CommandSender sender) {
        msg.sendList(sender, "info", "version", plugin.getPluginMeta().getVersion());
        if (!(sender instanceof Player p)) {
            msg.sendList(sender, plugin.halo().enabled() ? "halo-status-server-on" : "halo-status-server-off");
            return;
        }
        HaloManager halo = plugin.halo();
        if (!halo.enabled()) { msg.sendList(sender, "halo-disabled-server"); return; }
        if (!p.hasPermission(HaloManager.HALO_PERMISSION)) { msg.sendList(sender, "halo-vip-only"); return; }
        if (halo.isDisabled(p.getUniqueId())) { msg.sendList(sender, "halo-off"); return; }
        String color = halo.activeColorName(p);
        if (color == null) { msg.sendList(sender, "halo-no-color"); return; }
        if (halo.isInCombat(p.getUniqueId())) { msg.sendList(sender, "halo-in-combat", "color", msg.colorLabel(color)); return; }
        msg.sendList(sender, "halo-on", "color", msg.colorLabel(color));
    }

    // ------------------------------------------------------------- aiuto

    private void help(CommandSender sender, int page) {
        org.bukkit.configuration.ConfigurationSection h = msg.section("help");
        String title = h != null ? h.getString("title", "MagixCosmetics") : "MagixCosmetics";
        Help.show(sender, msg::forPlayer, title, "/cosmetics help",
                Help.fromConfig(msg.section("help.sections"), sender, msg::forPlayer, msg::listForPlayer),
                page, sender.hasPermission(ADMIN));
    }

    private static int page(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 1; }
    }

    // ------------------------------------------------------------- staff

    private void reload(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) { msg.send(sender, "no-permission"); return; }
        plugin.reloadEverything();
        msg.send(sender, "reloaded");
    }

    // ------------------------------------------------------------- tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission(ADMIN);
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("info", "help"));
            if (admin) base.add("reload");
            return filter(base, args[0]);
        }
        return Collections.emptyList();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }
}
