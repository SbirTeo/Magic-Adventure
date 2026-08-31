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
    public boolean onCommand(CommandSender m, Command c, String etichetta, String[] a) {
        boolean staff = m.hasPermission("magixmenus.admin");

        if (a.length == 0 || a[0].equalsIgnoreCase("help") || a[0].equalsIgnoreCase("aiuto")) {
            int pagina = a.length > 1 ? intero(a[1], 1) : 1;
            Help.mostra(m, "MagixMenus", "/" + etichetta + " help",
                    Help.daConfig(plugin.messaggi().section("help.sections")), pagina, staff);
            return true;
        }

        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "reload", "ricarica" -> {
                if (!staff) {
                    plugin.messaggi().send(m, "no-permission");
                    return true;
                }
                plugin.ricaricaTutto();
                plugin.messaggi().send(m, "reloaded",
                        "count", String.valueOf(plugin.menu().quanti()),
                        "errors", String.valueOf(quantiConErrori()));
                if (quantiConErrori() > 0) {
                    plugin.messaggi().send(m, "reloaded-with-errors");
                }
            }

            case "lista", "list" -> {
                if (!staff) {
                    plugin.messaggi().send(m, "no-permission");
                    return true;
                }
                lista(m);
            }

            case "info" -> {
                if (!staff) {
                    plugin.messaggi().send(m, "no-permission");
                    return true;
                }
                if (a.length < 2) {
                    plugin.messaggi().send(m, "usage-info");
                    return true;
                }
                info(m, a[1]);
            }

            case "apri", "open" -> {
                if (!staff) {
                    plugin.messaggi().send(m, "no-permission");
                    return true;
                }
                if (a.length < 2) {
                    plugin.messaggi().send(m, "usage-open");
                    return true;
                }
                apri(m, a);
            }

            default -> plugin.messaggi().send(m, "unknown-subcommand", "command", a[0]);
        }
        return true;
    }

    // ------------------------------------------------------------------ pezzi

    private void lista(CommandSender m) {
        plugin.messaggi().sendList(m, "list-header",
                "count", String.valueOf(plugin.menu().quanti()),
                "open", String.valueOf(plugin.menu().quantiAperti()));
        for (MenuDef d : plugin.menu().tutti()) {
            m.sendMessage(Colors.translate(plugin.messaggi().get("list-row",
                    "menu", d.nome(),
                    "type", d.tipo().nomeFile(),
                    "slots", String.valueOf(d.dimensione()),
                    "commands", d.comandi().isEmpty() ? "—" : "/" + String.join(", /", d.comandi()),
                    "status", d.errori().isEmpty() ? plugin.messaggi().get("status-ok")
                            : plugin.messaggi().get("status-errors", "count",
                                    String.valueOf(d.errori().size())))));
        }
    }

    private void info(CommandSender m, String nome) {
        MenuDef d = plugin.menu().trova(nome);
        if (d == null) {
            plugin.messaggi().send(m, "menu-not-found", "menu", nome);
            return;
        }
        plugin.messaggi().sendList(m, "info",
                "menu", d.nome(),
                "type", d.tipo().nomeFile(),
                "slots", String.valueOf(d.dimensione()),
                "title", d.titolo() == null ? "—" : d.titolo(),
                "update", d.aggiornamentoTick() <= 0 ? "mai" : d.aggiornamentoTick() + " tick",
                "item", String.valueOf(d.item().size()),
                "commands", d.comandi().isEmpty() ? "—" : "/" + String.join(", /", d.comandi()),
                "permission", d.permesso() == null ? "—" : d.permesso(),
                "dynamic", d.dinamico() ? "si" : "no");
        if (!d.errori().isEmpty()) {
            plugin.messaggi().send(m, "info-errors", "count", String.valueOf(d.errori().size()));
            for (String e : d.errori()) {
                m.sendMessage(Colors.translate("  &#FF6B6B• &7" + e));
            }
        }
    }

    private void apri(CommandSender m, String[] a) {
        MenuDef d = plugin.menu().trova(a[1]);
        if (d == null) {
            plugin.messaggi().send(m, "menu-not-found", "menu", a[1]);
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
            plugin.messaggi().send(m, "player-required");
            return;
        }

        List<String> argomenti = a.length > primoArgomento
                ? Arrays.asList(Arrays.copyOfRange(a, primoArgomento, a.length))
                : List.of();
        plugin.menu().apri(destinatario, d, argomenti, null);
        if (destinatario != m) {
            plugin.messaggi().send(m, "opened-for", "menu", d.nome(), "player", destinatario.getName());
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

    private static int intero(String s, int ripiego) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return ripiego;
        }
    }

    // ------------------------------------------------------------ completamento

    @Override
    public List<String> onTabComplete(CommandSender m, Command c, String etichetta, String[] a) {
        List<String> out = new ArrayList<>();
        if (!m.hasPermission("magixmenus.admin")) {
            return out;
        }
        if (a.length == 1) {
            for (String s : List.of("help", "reload", "lista", "info", "apri")) {
                if (s.startsWith(a[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
            return out;
        }
        if (a.length == 2 && (a[0].equalsIgnoreCase("apri") || a[0].equalsIgnoreCase("info"))) {
            for (MenuDef d : plugin.menu().tutti()) {
                if (d.nome().startsWith(a[1].toLowerCase(Locale.ROOT))) {
                    out.add(d.nome());
                }
            }
            return out;
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("apri")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[2].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}
