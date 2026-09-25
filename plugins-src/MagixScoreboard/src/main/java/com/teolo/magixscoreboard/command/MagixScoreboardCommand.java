package com.teolo.magixscoreboard.command;

import com.teolo.magixscoreboard.MagixScoreboard;
import com.teolo.magixscoreboard.board.BoardManager;
import com.teolo.magixscoreboard.lang.Messages;
import com.teolo.magixscoreboard.model.BoardDefinition;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** /magixscoreboard (alias /mscoreboard, /msb): stato, aiuto e comandi di amministrazione. */
public final class MagixScoreboardCommand implements CommandExecutor, TabCompleter {

    private static final String USE = "magixscoreboard.use";
    private static final String ADMIN = "magixscoreboard.admin";

    private final MagixScoreboard plugin;
    private final Messages msg;
    private final BoardManager boards;

    public MagixScoreboardCommand(MagixScoreboard plugin, Messages msg, BoardManager boards) {
        this.plugin = plugin;
        this.msg = msg;
        this.boards = boards;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(USE) && !sender.hasPermission(ADMIN)) {
            msg.send(sender, "no-permission");
            return true;
        }
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        // Il numero da solo sfoglia l'aiuto: e' quello che mandano le frecce in fondo all'elenco.
        if (!sub.isEmpty() && sub.chars().allMatch(Character::isDigit)) { help(sender, page(sub)); return true; }
        switch (sub) {
            case "info" -> info(sender);
            case "help", "?" -> help(sender, args.length >= 2 ? page(args[1]) : 1);
            case "toggle" -> toggle(sender);
            case "list" -> list(sender);
            case "debug" -> debug(sender, args);
            case "reload" -> reload(sender);
            default -> msg.send(sender, "unknown-subcommand");
        }
        return true;
    }

    // ------------------------------------------------------------- aiuto

    private void help(CommandSender sender, int page) {
        org.bukkit.configuration.ConfigurationSection h = msg.section("help");
        String title = h != null ? h.getString("title", "MagixScoreboard") : "MagixScoreboard";
        com.teolo.magixscoreboard.util.Help.show(sender, msg::forPlayer, title, "/mscoreboard help",
                com.teolo.magixscoreboard.util.Help.fromConfig(msg.section("help.sections"), sender,
                        msg::forPlayer, msg::listForPlayer),
                page, sender.hasPermission(ADMIN));
    }

    private static int page(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 1; }
    }

    // ------------------------------------------------------------- info / toggle

    private void info(CommandSender sender) {
        if (!(sender instanceof Player player)) { msg.send(sender, "players-only"); return; }
        boolean hidden = boards.isHidden(player);
        BoardDefinition selected = boards.select(player);
        msg.sendList(sender, "info",
                "version", plugin.getPluginMeta().getVersion(),
                "board", selected != null ? selected.id() : "-",
                "hidden", hidden ? msg.get("state-hidden") : msg.get("state-visible"));
    }

    private void toggle(CommandSender sender) {
        if (!(sender instanceof Player player)) { msg.send(sender, "players-only"); return; }
        boolean nowHidden = boards.toggleHidden(player);
        msg.send(sender, nowHidden ? "toggled-off" : "toggled-on");
    }

    // ------------------------------------------------------------- staff

    private void list(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) { msg.send(sender, "no-permission"); return; }
        List<BoardDefinition> defs = boards.definitions();
        msg.sendList(sender, "list-header",
                "count", String.valueOf(defs.size()),
                "priority", String.join(" > ", boards.priorityOrder()),
                "worldguard", boards.worldGuardEnabled() ? msg.get("state-visible") : msg.get("state-hidden"));
        for (BoardDefinition def : defs) {
            msg.sendList(sender, "list-line",
                    "id", def.id(),
                    "state", def.enabled() ? msg.get("state-visible") : msg.get("state-hidden"),
                    "weight", String.valueOf(def.weight()),
                    "permission", def.permission().isEmpty() ? "-" : def.permission(),
                    "worlds", def.worlds().isEmpty() ? "*" : String.join(",", def.worlds()),
                    "regions", def.regions().isEmpty() ? "*" : String.join(",", def.regions()),
                    "placeholders", def.placeholderConditions().isEmpty() ? "-" : String.join(" && ", def.placeholderConditions()));
        }
    }

    private void debug(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) { msg.send(sender, "no-permission"); return; }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { msg.send(sender, "player-not-found", "player", args[1]); return; }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            msg.send(sender, "players-only");
            return;
        }
        BoardDefinition selected = boards.select(target);
        msg.sendList(sender, "debug-header",
                "player", target.getName(),
                "world", target.getWorld().getName(),
                "selected", selected != null ? selected.id() : "-");
        for (BoardDefinition def : boards.definitions()) {
            boolean matches = def.matches(target, plugin.worldGuardHook());
            msg.sendList(sender, "debug-line",
                    "id", def.id(),
                    "matches", matches ? msg.get("state-visible") : msg.get("state-hidden"),
                    "weight", String.valueOf(def.weight()),
                    "score", String.valueOf(def.specificity(boards.priorityOrder())));
        }
    }

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
            List<String> base = new ArrayList<>(List.of("info", "help", "toggle"));
            if (admin) base.addAll(List.of("list", "debug", "reload"));
            return filter(base, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug") && admin) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
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
