package com.teolo.magixlanguage.command;

import com.teolo.magixlanguage.MagixLanguage;
import com.teolo.magixlanguage.lang.Messages;
import com.teolo.magixlanguage.translate.PlayerLocales;
import com.teolo.magixlanguage.translate.TranslationPacing;
import com.teolo.magixlanguage.translate.TranslationSync;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** /magixlanguage (alias /language, /lang): lingua propria, lingua di altri, sincronizzazione dei cataloghi. */
public final class MagixLanguageCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "magixlanguage.admin";

    private final MagixLanguage plugin;
    private final Messages msg;

    public MagixLanguageCommand(MagixLanguage plugin, Messages msg) {
        this.plugin = plugin;
        this.msg = msg;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("magixlanguage.use") && !sender.hasPermission(ADMIN)) {
            msg.send(sender, "no-permission");
            return true;
        }
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> info(sender);
            case "set" -> set(sender, args);
            case "sync" -> sync(sender, args);
            case "status" -> status(sender);
            case "reload" -> reload(sender);
            case "help", "?" -> help(sender);
            default -> msg.send(sender, "unknown-subcommand");
        }
        return true;
    }

    private void info(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            msg.send(sender, "players-only");
            return;
        }
        PlayerLocales.Entry entry = plugin.locales().get(player.getUniqueId());
        String lang = entry != null ? entry.lang() : plugin.getConfig().getString("default-language", "it");
        String source = entry != null && entry.source() == PlayerLocales.Source.MANUAL ? "manuale" : "auto";
        String country = entry != null && entry.country() != null ? entry.country() : "-";
        msg.sendList(sender, "info",
                "version", plugin.getPluginMeta().getVersion(),
                "lang", lang,
                "source", source,
                "country", country);
    }

    private void set(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg.send(sender, "unknown-subcommand");
            return;
        }
        String lang = args[1].toLowerCase(Locale.ROOT);
        List<String> supported = plugin.getConfig().getStringList("supported-languages");
        if (!supported.contains(lang)) {
            msg.send(sender, "set-invalid", "list", String.join(", ", supported));
            return;
        }
        if (args.length >= 3) {
            if (!sender.hasPermission(ADMIN)) {
                msg.send(sender, "no-permission");
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                msg.send(sender, "player-not-found", "player", args[2]);
                return;
            }
            plugin.locales().setManual(target.getUniqueId(), lang);
            msg.send(sender, "set-other", "player", target.getName() != null ? target.getName() : args[2], "lang", lang);
            return;
        }
        if (!(sender instanceof Player player)) {
            msg.send(sender, "players-only");
            return;
        }
        plugin.locales().setManual(player.getUniqueId(), lang);
        msg.send(sender, "set-self", "lang", lang);
    }

    private void sync(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            msg.send(sender, "no-permission");
            return;
        }
        boolean force = args.length >= 2 && args[1].equalsIgnoreCase("force");
        boolean started = plugin.startSync(force, result -> Bukkit.getScheduler().runTask(plugin, () -> msg.send(sender, "sync-done",
                "plugins", String.valueOf(result.pluginsScanned()),
                "translated", String.valueOf(result.keysTranslated()),
                "reused", String.valueOf(result.keysReused()),
                "failed", String.valueOf(result.translationFailures()))));
        if (!started) {
            msg.send(sender, "sync-already-running");
            return;
        }
        msg.send(sender, force ? "sync-running-force" : "sync-running");
    }

    private void status(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) {
            msg.send(sender, "no-permission");
            return;
        }
        TranslationSync.Result result = plugin.lastSyncResult();
        if (result == null) {
            msg.send(sender, "status-none");
            return;
        }
        msg.send(sender, "status-header");
        for (java.util.Map.Entry<String, TranslationSync.PluginStats> e : result.perPlugin().entrySet()) {
            TranslationSync.PluginStats s = e.getValue();
            sender.sendMessage(msg.get("status-line",
                    "plugin", e.getKey(),
                    "total", String.valueOf(s.totalKeys()),
                    "translated", String.valueOf(s.translated()),
                    "reused", String.valueOf(s.reused()),
                    "missing", String.valueOf(s.missing())));
        }
        msg.send(sender, "status-footer",
                "plugins", String.valueOf(result.pluginsScanned()),
                "translated", String.valueOf(result.keysTranslated()),
                "reused", String.valueOf(result.keysReused()),
                "failed", String.valueOf(result.translationFailures()));

        java.time.Instant paused = plugin.pacing().pausedUntil();
        if (paused != null) {
            msg.send(sender, "status-paused", "in",
                    TranslationPacing.formatWait(java.time.Duration.between(java.time.Instant.now(), paused)));
        } else if (result.translationFailures() > 0) {
            java.time.Instant next = plugin.pacing().nextPluginAttempt(TranslationSync.retryIntervalMinutes(plugin));
            if (next != null) {
                msg.send(sender, "status-next-attempt", "in",
                        TranslationPacing.formatWait(java.time.Duration.between(java.time.Instant.now(), next)));
            }
        }

        java.util.Map<String, int[]> web = plugin.siteTranslationStatus();
        if (!web.isEmpty()) {
            msg.send(sender, "status-web-header");
            for (java.util.Map.Entry<String, int[]> e : web.entrySet()) {
                int[] c = e.getValue(); // {pronte, in attesa, fallite}
                sender.sendMessage(msg.get("status-web-line",
                        "lang", e.getKey(),
                        "done", String.valueOf(c[0]),
                        "pending", String.valueOf(c[1]),
                        "failed", String.valueOf(c[2])));
            }
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) {
            msg.send(sender, "no-permission");
            return;
        }
        plugin.reloadEverything();
        msg.send(sender, "reloaded");
    }

    private void help(CommandSender sender) {
        boolean admin = sender.hasPermission(ADMIN);
        org.bukkit.configuration.ConfigurationSection sections = msg.section("help.sections");
        if (sections == null) {
            return;
        }
        for (String key : sections.getKeys(false)) {
            org.bukkit.configuration.ConfigurationSection s = sections.getConfigurationSection(key);
            if (s == null || (s.getBoolean("staff", false) && !admin)) {
                continue;
            }
            sender.sendMessage("&#C046E8&l" + s.getString("title", key));
            for (String line : s.getStringList("entries")) {
                sender.sendMessage("&7" + line);
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission(ADMIN);
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("info", "set", "help"));
            if (admin) base.addAll(List.of("sync", "status", "reload"));
            return filter(base, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return filter(plugin.getConfig().getStringList("supported-languages"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sync") && admin) {
            return filter(List.of("force"), args[1]);
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
