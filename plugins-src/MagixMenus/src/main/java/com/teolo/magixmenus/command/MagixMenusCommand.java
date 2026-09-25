package com.teolo.magixmenus.command;

import com.teolo.magixmenus.MagixMenus;
import com.teolo.magixmenus.menu.MenuDef;
import com.teolo.magixmenus.util.Help;
import com.teolo.magixmenus.util.Colors;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Il comando di servizio: {@code /magixmenus}, con gli alias {@code /menus} e {@code /mm}.
 *
 * I menu veri hanno ognuno il proprio comando (vedi {@link MenuCommand}); questo serve a chi li
 * amministra — ricaricare, vedere quali ci sono, aprirne uno a qualcun altro, capire perche' uno
 * non funziona.
 */
public final class MagixMenusCommand implements CommandExecutor, TabCompleter {

    private final MagixMenus plugin;

    public MagixMenusCommand(MagixMenus plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender m, Command c, String label, String[] a) {
        boolean staff = m.hasPermission("magixmenus.admin");

        if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
            int page = a.length > 1 ? intero(a[1], 1) : 1;
            Help.show(m, plugin.messages()::forPlayer, "MagixMenus", "/" + label + " help",
                    Help.fromConfig(plugin.messages().section("help.sections"), m,
                            plugin.messages()::forPlayer, plugin.messages()::listForPlayer), page, staff);
            return true;
        }

        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (!staff) {
                    plugin.messages().send(m, "no-permission");
                    return true;
                }
                plugin.reloadAll();
                plugin.messages().send(m, "reloaded",
                        "count", String.valueOf(plugin.menu().quanti()),
                        "errors", String.valueOf(quantiConErrori()));
                if (quantiConErrori() > 0) {
                    plugin.messages().send(m, "reloaded-with-errors");
                }
            }

            case "list" -> {
                if (!staff) {
                    plugin.messages().send(m, "no-permission");
                    return true;
                }
                list(m);
            }

            case "info" -> {
                if (!staff) {
                    plugin.messages().send(m, "no-permission");
                    return true;
                }
                if (a.length < 2) {
                    plugin.messages().send(m, "usage-info");
                    return true;
                }
                info(m, a[1]);
            }

            case "open" -> {
                if (!staff) {
                    plugin.messages().send(m, "no-permission");
                    return true;
                }
                if (a.length < 2) {
                    plugin.messages().send(m, "usage-open");
                    return true;
                }
                open(m, a);
            }

            default -> plugin.messages().send(m, "unknown-subcommand", "command", a[0]);
        }
        return true;
    }

    // ------------------------------------------------------------------ pezzi

    private void list(CommandSender m) {
        plugin.messages().sendList(m, "list-header",
                "count", String.valueOf(plugin.menu().quanti()),
                "open", String.valueOf(plugin.menu().quantiAperti()));
        for (MenuDef d : plugin.menu().tutti()) {
            m.sendMessage(Colors.translate(plugin.messages().get("list-row",
                    "menu", d.name(),
                    "type", d.type().fileName(),
                    "slots", String.valueOf(d.dimensione()),
                    "commands", d.commands().isEmpty() ? "—" : "/" + String.join(", /", d.commands()),
                    "status", d.errori().isEmpty() ? plugin.messages().get("status-ok")
                            : plugin.messages().get("status-errors", "count",
                                    String.valueOf(d.errori().size())))));
        }
    }

    private void info(CommandSender m, String name) {
        MenuDef d = plugin.menu().find(name);
        if (d == null) {
            plugin.messages().send(m, "menu-not-found", "menu", name);
            return;
        }
        plugin.messages().sendList(m, "info",
                "menu", d.name(),
                "type", d.type().fileName(),
                "slots", String.valueOf(d.dimensione()),
                "title", d.title() == null ? "—" : d.title(),
                "update", d.aggiornamentoTick() <= 0 ? "mai" : d.aggiornamentoTick() + " tick",
                "item", String.valueOf(d.item().size()),
                "commands", d.commands().isEmpty() ? "—" : "/" + String.join(", /", d.commands()),
                "permission", d.permesso() == null ? "—" : d.permesso(),
                "dynamic", d.dinamico() ? "si" : "no");
        if (!d.errori().isEmpty()) {
            plugin.messages().send(m, "info-errors", "count", String.valueOf(d.errori().size()));
            for (String e : d.errori()) {
                m.sendMessage(Colors.translate("  &#FF6B6B• &7" + e));
            }
        }
    }

    private void open(CommandSender m, String[] a) {
        MenuDef d = plugin.menu().find(a[1]);
        if (d == null) {
            plugin.messages().send(m, "menu-not-found", "menu", a[1]);
            return;
        }

        Player destinatario;
        int primoArgomento;
        if (a.length > 2 && Bukkit.getPlayerExact(a[2]) != null) {
            destinatario = Bukkit.getPlayerExact(a[2]);
            primoArgomento = 3;
        } else if (m instanceof Player p) {
            destinatario = p;
            primoArgomento = 2;
        } else {
            plugin.messages().send(m, "player-required");
            return;
        }

        List<String> arguments = a.length > primoArgomento
                ? Arrays.asList(Arrays.copyOfRange(a, primoArgomento, a.length))
                : List.of();
        plugin.menu().open(destinatario, d, arguments, null);
        if (destinatario != m) {
            plugin.messages().send(m, "opened-for", "menu", d.name(), "player", destinatario.getName());
        }
    }

    private int quantiConErrori() {
        int n = 0;
        for (MenuDef d : plugin.menu().tutti()) {
            if (!d.errori().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private static int intero(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------ completamento

    @Override
    public List<String> onTabComplete(CommandSender m, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!m.hasPermission("magixmenus.admin")) {
            return out;
        }
        if (a.length == 1) {
            for (String s : List.of("help", "reload", "list", "info", "open")) {
                if (s.startsWith(a[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
            return out;
        }
        if (a.length == 2 && (a[0].equalsIgnoreCase("open") || a[0].equalsIgnoreCase("info"))) {
            for (MenuDef d : plugin.menu().tutti()) {
                if (d.name().startsWith(a[1].toLowerCase(Locale.ROOT))) {
                    out.add(d.name());
                }
            }
            return out;
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("open")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[2].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}
