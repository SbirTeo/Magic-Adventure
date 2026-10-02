package com.teolo.magixcosmetics.command;

import com.teolo.magixcosmetics.MagixCosmetics;
import com.teolo.magixcosmetics.cosmetic.FireworkLook;
import com.teolo.magixcosmetics.cosmetic.FireworkManager;
import com.teolo.magixcosmetics.lang.Messages;
import com.teolo.magixcosmetics.util.Help;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** /firework: personalizza il proprio firework d'ingresso (colori, forma, scia...), tutto a permessi. */
public final class FireworkCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "magixcosmetics.admin";

    private final MagixCosmetics plugin;
    private final Messages msg;

    public FireworkCommand(MagixCosmetics plugin, Messages msg) {
        this.plugin = plugin;
        this.msg = msg;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { msg.send(sender, "players-only"); return true; }
        FireworkManager fw = plugin.firework();
        if (!fw.enabled()) { msg.send(p, "firework-disabled"); return true; }
        if (!p.hasPermission(FireworkManager.USE)) { msg.send(p, "firework-no-permission"); return true; }

        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        if (!sub.isEmpty() && sub.chars().allMatch(Character::isDigit)) { help(p, page(sub)); return true; }
        String[] rest = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        switch (sub) {
            case "info" -> info(p);
            case "help", "?" -> help(p, rest.length >= 1 ? page(rest[0]) : 1);
            case "color" -> color(p, rest, false);
            case "fade" -> color(p, rest, true);
            case "shape" -> shape(p, rest);
            case "flicker" -> flag(p, rest, true);
            case "trail" -> flag(p, rest, false);
            case "preview" -> preview(p);
            case "reset" -> { fw.reset(p.getUniqueId()); msg.send(p, "firework-reset"); }
            case "on", "off" -> toggle(p, sub.equals("on"));
            default -> msg.send(p, "unknown-subcommand-firework");
        }
        return true;
    }

    // ------------------------------------------------------------- info

    private void info(Player p) {
        FireworkManager fw = plugin.firework();
        FireworkLook look = fw.effective(p);
        msg.sendList(p, "firework-info",
                "state", msg.get(fw.isOff(p.getUniqueId()) ? "firework-state-off" : "firework-state-on"),
                "colors", String.join(", ", look.colors),
                "fade", look.fade.isEmpty() ? msg.get("firework-none") : String.join(", ", look.fade),
                "shape", look.shape,
                "flicker", yesNo(look.flicker),
                "trail", yesNo(look.trail));
    }

    private String yesNo(Boolean b) { return msg.get(Boolean.TRUE.equals(b) ? "firework-yes" : "firework-no"); }

    // ------------------------------------------------------------- scelte

    private void color(Player p, String[] args, boolean fade) {
        FireworkManager fw = plugin.firework();
        if (fade && !p.hasPermission(FireworkManager.PERM_FADE)) { msg.send(p, "firework-no-permission-fade"); return; }
        if (args.length == 0) {
            msg.send(p, fade ? "firework-usage-fade" : "firework-usage-color");
            return;
        }
        // "/firework fade clear" toglie la sfumatura.
        if (fade && args.length == 1 && args[0].equalsIgnoreCase("clear")) {
            fw.setFade(p.getUniqueId(), new ArrayList<>());
            msg.send(p, "firework-fade-cleared");
            return;
        }
        if (args.length > fw.maxColors()) {
            msg.send(p, "firework-too-many-colors", "max", String.valueOf(fw.maxColors()));
            return;
        }
        List<String> tokens = new ArrayList<>();
        for (String raw : args) {
            String t = raw.toLowerCase(Locale.ROOT);
            if (fw.parse(t) == null) {
                msg.send(p, "firework-color-unknown", "color", raw, "colors", String.join(", ", fw.paletteNames()));
                return;
            }
            if (!fw.mayUseColor(p, t)) {
                msg.send(p, "firework-color-no-permission", "color", t);
                return;
            }
            tokens.add(t);
        }
        if (fade) fw.setFade(p.getUniqueId(), tokens); else fw.setColors(p.getUniqueId(), tokens);
        msg.send(p, fade ? "firework-fade-set" : "firework-color-set", "colors", String.join(", ", tokens));
    }

    private void shape(Player p, String[] args) {
        if (args.length < 1) { msg.send(p, "firework-usage-shape", "shapes", String.join(", ", FireworkManager.SHAPES.keySet())); return; }
        String shape = args[0].toLowerCase(Locale.ROOT);
        if (!FireworkManager.SHAPES.containsKey(shape)) {
            msg.send(p, "firework-shape-unknown", "shapes", String.join(", ", FireworkManager.SHAPES.keySet()));
            return;
        }
        if (!p.hasPermission(FireworkManager.shapePermission(shape))) {
            msg.send(p, "firework-shape-no-permission", "shape", shape);
            return;
        }
        plugin.firework().setShape(p.getUniqueId(), shape);
        msg.send(p, "firework-shape-set", "shape", shape);
    }

    private void flag(Player p, String[] args, boolean flicker) {
        String perm = flicker ? FireworkManager.PERM_FLICKER : FireworkManager.PERM_TRAIL;
        if (!p.hasPermission(perm)) { msg.send(p, flicker ? "firework-no-permission-flicker" : "firework-no-permission-trail"); return; }
        if (args.length < 1 || !(args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
            msg.send(p, flicker ? "firework-usage-flicker" : "firework-usage-trail");
            return;
        }
        boolean on = args[0].equalsIgnoreCase("on");
        if (flicker) plugin.firework().setFlicker(p.getUniqueId(), on); else plugin.firework().setTrail(p.getUniqueId(), on);
        msg.send(p, flicker ? (on ? "firework-flicker-on" : "firework-flicker-off") : (on ? "firework-trail-on" : "firework-trail-off"));
    }

    private void preview(Player p) {
        if (!p.hasPermission(FireworkManager.PERM_PREVIEW)) { msg.send(p, "firework-no-permission-preview"); return; }
        long wait = plugin.firework().preview(p);
        if (wait > 0) msg.send(p, "firework-preview-wait", "seconds", String.valueOf(wait));
    }

    private void toggle(Player p, boolean on) {
        if (!p.hasPermission(FireworkManager.PERM_TOGGLE)) { msg.send(p, "firework-no-permission-toggle"); return; }
        plugin.firework().setOff(p.getUniqueId(), !on);
        msg.send(p, on ? "firework-set-on" : "firework-set-off");
    }

    // ------------------------------------------------------------- aiuto

    private void help(CommandSender sender, int page) {
        org.bukkit.configuration.ConfigurationSection h = msg.section("help");
        String title = h != null ? h.getString("title", "MagixCosmetics") : "MagixCosmetics";
        Help.show(sender, msg::forPlayer, title, "/firework help",
                Help.fromConfig(msg.section("help.sections"), sender, msg::forPlayer, msg::listForPlayer),
                page, sender.hasPermission(ADMIN));
    }

    private static int page(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 1; }
    }

    // ------------------------------------------------------------- tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player p) || !p.hasPermission(FireworkManager.USE)) return Collections.emptyList();
        FireworkManager fw = plugin.firework();
        if (args.length == 1) {
            return filter(List.of("info", "color", "fade", "shape", "flicker", "trail", "preview", "reset", "on", "off", "help"), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String last = args[args.length - 1];
        switch (sub) {
            case "color", "fade" -> {
                List<String> allowed = new ArrayList<>();
                for (String n : fw.paletteNames()) if (fw.mayUseColor(p, n)) allowed.add(n);
                if (sub.equals("fade")) allowed.add("clear");
                return filter(allowed, last);
            }
            case "shape" -> {
                if (args.length != 2) return Collections.emptyList();
                List<String> allowed = new ArrayList<>();
                for (String s : FireworkManager.SHAPES.keySet()) if (p.hasPermission(FireworkManager.shapePermission(s))) allowed.add(s);
                return filter(allowed, last);
            }
            case "flicker", "trail" -> { return args.length == 2 ? filter(List.of("on", "off"), last) : Collections.emptyList(); }
            default -> { return Collections.emptyList(); }
        }
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }
}
