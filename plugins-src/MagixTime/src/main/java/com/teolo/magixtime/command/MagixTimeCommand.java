package com.teolo.magixtime.command;

import com.teolo.magixtime.MagixTime;
import com.teolo.magixtime.lang.Messages;
import com.teolo.magixtime.util.Help;
import com.teolo.magixtime.season.SeasonDef;
import com.teolo.magixtime.time.TimeSync;
import com.teolo.magixtime.weather.WeatherManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** /magixtime (alias /mtime): informazioni e gestione di ora, stagione e meteo. */
public final class MagixTimeCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "magixtime.admin";

    private final MagixTime plugin;
    private final Messages msg;

    public MagixTimeCommand(MagixTime plugin, Messages msg) {
        this.plugin = plugin;
        this.msg = msg;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String [] args) {
        if (!sender.hasPermission("magixtime.use") && !sender.hasPermission(ADMIN)) {
            msg.send(sender, "no-permission");
            return true;
        }
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        // Il numero da solo sfoglia l'aiuto: e' quello che mandano le frecce in fondo all'elenco.
        if (!sub.isEmpty() && sub.chars().allMatch(Character::isDigit)) { help(sender, page(sub)); return true; }
        switch (sub) {
            case "info" -> info(sender);
            case "help", "?" -> help(sender, args.length >= 2 ? page(args[1]) : 1);
            case "season", "stagione" -> season(sender, args);
            case "weather", "meteo" -> weather(sender, args);
            case "worlds", "mondi" -> worlds(sender);
            case "sync" -> sync(sender);
            case "pause" -> pause(sender, true);
            case "resume" -> pause(sender, false);
            case "reload" -> reload(sender);
            default -> msg.send(sender, "unknown-subcommand");
        }
        return true;
    }

    // ------------------------------------------------------------- aiuto

    /**
     * /mtime help [pagina] - l'elenco dei comandi.
     *
     * Le voci stanno in messages.yml (help.sections) e le impagina {@link Help}, la stessa classe
     * degli altri plugin Magix: sezioni, frecce per sfogliare, ogni riga cliccabile. I comandi che
     * spostano l'ora li vede solo chi ha magixtime.admin.
     */
    private void help(CommandSender sender, int page) {
        org.bukkit.configuration.ConfigurationSection h = msg.section("help");
        String title = h != null ? h.getString("title", "MagixTime") : "MagixTime";
        Help.show(sender, msg::forPlayer, title, "/mtime help",
                Help.fromConfig(msg.section("help.sections"), sender, msg::forPlayer, msg::listForPlayer),
                page, sender.hasPermission(ADMIN));
    }

    /** Il numero di pagina scritto dall'utente; qualsiasi cosa strana vale 1. */
    private static int page(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 1; }
    }

    // ------------------------------------------------------------- info

    private void info(CommandSender sender) {
        World w = referenceWorld(sender);
        SeasonDef season = plugin.seasons().current();
        SeasonDef next = plugin.seasons().next();
        TimeSync time = plugin.time();
        long ticks = w != null ? w.getTime() : time.currentMinecraftTicks();

        msg.sendList(sender, "info",
                "version", plugin.getPluginMeta().getVersion(),
                "realtime", time.realTime(),
                "realdate", time.realDate(),
                "timezone", plugin.zone().getId(),
                "mctime", TimeSync.formatTicks(ticks),
                "mcticks", String.valueOf(ticks),
                "mcday", w != null ? String.valueOf(w.getFullTime() / 24000L) : "-",
                "season", season != null ? season.display() : "-",
                "nextseason", next != null ? next.display() : "-",
                "days", String.valueOf(plugin.seasons().daysToNext()),
                "weather", plugin.weather().describe(w),
                "weathernext", String.valueOf(plugin.weather().minutesLeft(w)));
        if (time.isPaused()) msg.send(sender, "info-paused");
        if (!time.isDaylightCycleOff()) msg.send(sender, "info-gamerule-warning");
        long timeOverride = time.overrideLeft(w);
        if (timeOverride > 0) msg.send(sender, "info-override-time", "seconds", String.valueOf(timeOverride));
        if (time.isCatchingUp(w)) msg.send(sender, "info-catchup");
        long weatherOverride = plugin.weather().overrideLeft(w);
        if (weatherOverride > 0) msg.send(sender, "info-override-weather", "seconds", String.valueOf(weatherOverride));
    }

    // ------------------------------------------------------------- season

    private void season(CommandSender sender, String[] args) {
        if (!plugin.seasons().isEnabled()) {
            msg.send(sender, "disabled-module", "module", "seasons");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("set")) {
            if (!sender.hasPermission(ADMIN)) { msg.send(sender, "no-permission"); return; }
            if (args.length < 3) { msg.send(sender, "season-unknown", "list", String.join(", ", plugin.seasons().keys())); return; }
            if (args[2].equalsIgnoreCase("auto")) {
                plugin.seasons().force(null);
                msg.send(sender, "season-auto", "season", plugin.seasons().current().display());
                return;
            }
            if (!plugin.seasons().force(args[2])) {
                msg.send(sender, "season-unknown", "list", String.join(", ", plugin.seasons().keys()));
                return;
            }
            msg.send(sender, "season-forced", "season", plugin.seasons().current().display());
            return;
        }

        SeasonDef s = plugin.seasons().current();
        SeasonDef next = plugin.seasons().next();
        msg.sendList(sender, "season-details",
                "season", s.display(),
                "key", s.key(),
                "rain", String.valueOf(s.rainChance()),
                "thunder", String.valueOf(s.thunderChance()),
                "snow", s.snowAccumulate() ? "si" : "no",
                "next", next != null ? next.display() : "-",
                "days", String.valueOf(plugin.seasons().daysToNext()));
    }

    // ------------------------------------------------------------- weather

    private void weather(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) { msg.send(sender, "no-permission"); return; }
        if (!plugin.getConfig().getBoolean("weather.enabled", true)) {
            msg.send(sender, "disabled-module", "module", "weather");
            return;
        }
        if (args.length < 2) { msg.send(sender, "weather-unknown"); return; }
        WeatherManager.Phase.Type type = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "clear", "sereno" -> WeatherManager.Phase.Type.CLEAR;
            case "rain", "pioggia" -> WeatherManager.Phase.Type.RAIN;
            case "storm", "thunder", "temporale" -> WeatherManager.Phase.Type.STORM;
            default -> null;
        };
        if (type == null) { msg.send(sender, "weather-unknown"); return; }

        int minutes = 30;
        if (args.length >= 3) {
            try { minutes = Math.max(1, Integer.parseInt(args[2])); } catch (NumberFormatException ignored) {}
        }
        plugin.weather().force(type, minutes);
        msg.send(sender, "weather-set",
                "weather", plugin.weather().describe(referenceWorld(sender)),
                "minutes", String.valueOf(minutes));
    }

    /** Diagnostica: stato reale di ogni mondo gestito, mondo per mondo. */
    private void worlds(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) { msg.send(sender, "no-permission"); return; }
        TimeSync time = plugin.time();
        msg.sendList(sender, "worlds-header",
                "count", String.valueOf(plugin.managedWorlds().size()),
                "gamerule", time.isDaylightCycleOff() ? "spenta" : "&cATTIVA");
        for (World w : plugin.managedWorlds()) {
            String state;
            if (time.isLinked(w)) state = "&8orologio condiviso";
            else if (time.isCatchingUp(w)) state = "&erecupero in corso";
            else if (time.overrideLeft(w) > 0) state = "&eforzato (" + time.overrideLeft(w) + "s)";
            else state = "&aallineato";
            msg.sendList(sender, "worlds-line",
                    "world", w.getName(),
                    "mctime", TimeSync.formatTicks(w.getTime()),
                    "ticks", String.valueOf(w.getTime()),
                    "day", String.valueOf(w.getFullTime() / 24000L),
                    "weather", plugin.weather().describe(w),
                    "state", state);
        }
    }

    // ------------------------------------------------------------- staff

    private void sync(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) { msg.send(sender, "no-permission"); return; }
        if (!plugin.getConfig().getBoolean("time.enabled", true)) {
            msg.send(sender, "disabled-module", "module", "time");
            return;
        }
        msg.send(sender, "synced", "worlds", String.valueOf(plugin.time().sync()));
    }

    private void pause(CommandSender sender, boolean pause) {
        if (!sender.hasPermission(ADMIN)) { msg.send(sender, "no-permission"); return; }
        if (plugin.time().isPaused() == pause) {
            msg.send(sender, pause ? "already-paused" : "already-running");
            return;
        }
        plugin.time().setPaused(pause);
        msg.send(sender, pause ? "paused" : "resumed");
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) { msg.send(sender, "no-permission"); return; }
        plugin.reloadEverything();
        msg.send(sender, "reloaded");
    }

    /** Mondo di riferimento per le informazioni: quello del giocatore, o il primo gestito. */
    private World referenceWorld(CommandSender sender) {
        if (sender instanceof Player p && plugin.isManaged(p.getWorld())) return p.getWorld();
        List<World> managed = plugin.managedWorlds();
        return managed.isEmpty() ? Bukkit.getWorlds().get(0) : managed.get(0);
    }

    // ------------------------------------------------------------- tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String [] args) {
        boolean admin = sender.hasPermission(ADMIN);
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("info", "help", "season"));
            if (admin) base.addAll(List.of("weather", "worlds", "sync", "pause", "resume", "reload"));
            return filter(base, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("season") && admin) {
            return filter(List.of("set"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("season") && args[1].equalsIgnoreCase("set") && admin) {
            List<String> keys = new ArrayList<>(plugin.seasons().keys());
            keys.add("auto");
            return filter(keys, args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("weather") && admin) {
            return filter(List.of("clear", "rain", "storm"), args[1]);
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
