package com.teolo.magixcosmetics.command;

import com.teolo.magixcosmetics.MagixCosmetics;
import com.teolo.magixcosmetics.cosmetic.HaloManager;
import com.teolo.magixcosmetics.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** /halo: accende, spegne e sceglie il colore della propria aureola (VIP). */
public final class HaloCommand implements CommandExecutor, TabCompleter {

    private final MagixCosmetics plugin;
    private final Messages msg;

    public HaloCommand(MagixCosmetics plugin, Messages msg) {
        this.plugin = plugin;
        this.msg = msg;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { msg.send(sender, "players-only"); return true; }
        if (!plugin.halo().enabled()) { msg.send(sender, "halo-disabled"); return true; }
        if (!p.hasPermission(HaloManager.HALO_PERMISSION)) { msg.send(sender, "halo-no-vip"); return true; }

        if (args.length >= 1 && args[0].equalsIgnoreCase("setcolor")) { setColor(p, args); return true; }

        boolean currentlyOn = !plugin.halo().isDisabled(p.getUniqueId());
        boolean want;
        if (args.length >= 1) {
            String a = args[0].toLowerCase(Locale.ROOT);
            if (a.equals("on")) want = true;
            else if (a.equals("off")) want = false;
            else if (a.equals("toggle")) want = !currentlyOn;
            else { msg.send(sender, "halo-usage"); return true; }
        } else {
            want = !currentlyOn;   // nessun argomento = inverti
        }

        if (want && currentlyOn) { msg.send(sender, "halo-already-on"); return true; }
        if (!want && !currentlyOn) { msg.send(sender, "halo-already-off"); return true; }

        plugin.halo().toggle(p.getUniqueId(), want);
        msg.send(sender, want ? "halo-set-on" : "halo-set-off");
        return true;
    }

    // ------------------------------------------------------------- colore

    private void setColor(Player p, String[] args) {
        if (args.length < 2) { msg.send(p, "halo-usage"); return; }
        String name = args[1].toLowerCase(Locale.ROOT);
        HaloManager halo = plugin.halo();

        if (!halo.colorNames().contains(name)) {
            msg.send(p, "halo-setcolor-unknown", "colors", joinLabels(halo.colorNames()));
            return;
        }
        if (!p.hasPermission(HaloManager.colorPermission(name))) {
            msg.send(p, "halo-setcolor-no-permission", "color", msg.colorLabel(name));
            return;
        }
        halo.setColor(p.getUniqueId(), name);
        msg.send(p, "halo-setcolor-set", "color", msg.colorLabel(name));
    }

    private String joinLabels(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(msg.colorLabel(n));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("on", "off", "setcolor"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("setcolor")) {
            List<String> names = plugin.halo().colorNames();
            if (sender instanceof Player p) {
                List<String> allowed = new ArrayList<>();
                for (String n : names) if (p.hasPermission(HaloManager.colorPermission(n))) allowed.add(n);
                return filter(allowed, args[1]);
            }
            return filter(names, args[1]);
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
