package com.teolo.magixtime.listener;

import com.teolo.magixtime.MagixTime;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Blocca i comandi di ora e meteo degli altri plugin (CMI su tutti) e di vanilla.
 *
 * Finche' MagixTime governa ora e meteo, un /time o un /weather esterno verrebbe
 * comunque riscritto entro un secondo: meglio rifiutarlo subito e spiegare che per
 * cambiarli davvero bisogna spegnere il modulo di MagixTime.
 *
 * Il blocco segue i moduli: i comandi dell'ora sono rifiutati solo se time.enabled
 * e' true, quelli del meteo solo se weather.enabled e' true.
 */
public final class CommandBlockListener implements Listener {

    private final MagixTime plugin;
    private final Set<String> timeCommands = new HashSet<>();
    private final Set<String> weatherCommands = new HashSet<>();

    public CommandBlockListener(MagixTime plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        fill(timeCommands, plugin.getConfig().getStringList("block-commands.time"));
        fill(weatherCommands, plugin.getConfig().getStringList("block-commands.weather"));
    }

    private static void fill(Set<String> target, List<String> from) {
        target.clear();
        for (String s : from) {
            String v = s.trim().toLowerCase(Locale.ROOT);
            if (!v.isEmpty()) target.add(v);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        if (intercept(e.getPlayer(), e.getMessage())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent e) {
        if (!plugin.getConfig().getBoolean("block-commands.console", true)) return;
        if (intercept(e.getSender(), e.getCommand())) e.setCancelled(true);
    }

    /**
     * Avvisa (e, in modalita' "block", rifiuta) i comandi di ora e meteo.
     * Ritorna true solo se il comando va annullato.
     */
    private boolean intercept(CommandSender sender, String raw) {
        if (!plugin.getConfig().getBoolean("block-commands.enabled", true)) return false;

        String bypass = plugin.getConfig().getString("block-commands.bypass-permission", "");
        if (bypass != null && !bypass.isBlank() && sender.hasPermission(bypass)) return false;

        String[] parts = raw.startsWith("/") ? raw.substring(1).trim().split("\\s+") : raw.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return false;
        // "/minecraft:time" e "/cmi:cmi weather" devono valere quanto le forme brevi.
        String first = stripNamespace(parts[0]).toLowerCase(Locale.ROOT);
        String pair = parts.length > 1 ? first + " " + parts[1].toLowerCase(Locale.ROOT) : null;

        boolean isTime = plugin.getConfig().getBoolean("time.enabled", true)
                && (timeCommands.contains(first) || (pair != null && timeCommands.contains(pair)));
        boolean isWeather = !isTime && plugin.getConfig().getBoolean("weather.enabled", true)
                && (weatherCommands.contains(first) || (pair != null && weatherCommands.contains(pair)));
        if (!isTime && !isWeather) return false;

        boolean block = "block".equalsIgnoreCase(plugin.getConfig().getString("block-commands.mode", "revert"));
        String key = (block ? "blocked-" : "revert-") + (isTime ? "time" : "weather");
        String seconds = String.valueOf(isTime
                ? plugin.getConfig().getInt("time.override-seconds", 60)
                : plugin.getConfig().getInt("weather.override-seconds", 120));
        notify(sender, key, seconds);
        return block;
    }

    private void notify(CommandSender sender, String key, String seconds) {
        for (String line : plugin.messages().getList(key, "seconds", seconds)) sender.sendMessage(line);
        if (sender instanceof Player p) {
            String sound = plugin.getConfig().getString("block-commands.deny-sound", "");
            if (sound != null && !sound.isBlank()) p.playSound(p.getLocation(), sound.trim(), 1f, 1f);
        }
    }

    private static String stripNamespace(String cmd) {
        int i = cmd.indexOf(':');
        return i >= 0 ? cmd.substring(i + 1) : cmd;
    }
}
