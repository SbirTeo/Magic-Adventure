package com.teolo.magixmusic.command;

import com.teolo.magixmusic.MagixMusic;
import com.teolo.magixmusic.lang.Messages;
import com.teolo.magixmusic.radio.RadioService;
import com.teolo.magixmusic.radio.VolumeStore;
import com.teolo.magixmusic.util.Help;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * /magixmusic (alias /radio, /music): regola la radio musicale dello spawn per il SINGOLO giocatore
 * (volume 0-100, 0 = spenta), preferenza personale salvata in {@link VolumeStore}. Senza argomento
 * mostra lo stato. La sincronia e la riproduzione le gestisce {@link RadioService}; qui si cambia solo
 * il volume del giocatore e gli si dà un riscontro immediato.
 */
public final class MagixMusicCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "magixmusic.admin";

    private final MagixMusic plugin;
    private final Messages msg;
    private final RadioService radio;
    private final VolumeStore volumes;

    public MagixMusicCommand(MagixMusic plugin, Messages msg, RadioService radio, VolumeStore volumes) {
        this.plugin = plugin;
        this.msg = msg;
        this.radio = radio;
        this.volumes = volumes;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("magixmusic.use") && !sender.hasPermission(ADMIN)) {
            msg.send(sender, "no-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("help") || sub.equals("?")) {
            help(sender, args.length >= 2 ? page(args[1]) : 1);
            return true;
        }
        if (sub.equals("reload")) {
            if (!sender.hasPermission(ADMIN)) { msg.send(sender, "no-permission"); return true; }
            plugin.reloadAll();
            msg.send(sender, "reloaded");
            return true;
        }
        // Salta al brano successivo: azione GLOBALE (una radio sola), riservata allo staff.
        if (sub.equals("next") || sub.equals("skip")) {
            if (!sender.hasPermission(ADMIN)) { msg.send(sender, "no-permission"); return true; }
            if (!radio.isEnabled()) { msg.send(sender, "disabled-globally"); return true; }
            radio.skip();
            msg.send(sender, "skipped", "track", trackName());
            return true;
        }

        if (!(sender instanceof Player p)) { msg.send(sender, "players-only"); return true; }
        if (!radio.isEnabled()) { msg.send(sender, "disabled-globally"); return true; }

        int cur = radio.effectiveVolume(p.getUniqueId()); // 0-100 (il salvato, o il default se mai regolato)
        if (sub.isEmpty()) {
            if (cur > 0) msg.send(sender, "status-on", "volume", String.valueOf(cur), "track", trackName());
            else msg.send(sender, "status-off");
            return true;
        }
        // Riascolta il brano in onda dall'inizio, solo per chi lo chiede (utile a chi è appena entrato).
        if (sub.equals("replay") || sub.equals("riascolta")) {
            if (cur <= 0) { msg.send(sender, "status-off"); return true; }
            radio.refreshFor(p);
            msg.send(sender, "replayed", "track", trackName());
            return true;
        }

        boolean turningOn = false;
        int newVol;
        switch (sub) {
            case "on": case "si": case "sì":
                newVol = radio.defaultVolume() > 0 ? radio.defaultVolume() : 100; turningOn = true; break;
            case "off": case "no":
                newVol = 0; break;
            case "up": case "+": case "su":
                newVol = Math.min(100, Math.max(0, cur) + radio.volumeStep()); break;
            case "down": case "-": case "giu": case "giù":
                newVol = Math.max(0, cur - radio.volumeStep()); break;
            default:
                Integer parsed = parseVolume(sub);
                if (parsed == null) { msg.send(sender, "usage"); return true; }
                newVol = Math.max(0, Math.min(100, parsed));
        }
        volumes.set(p.getUniqueId(), newVol);
        if (newVol > 0) {
            radio.refreshFor(p); // fa ripartire il brano in corso al nuovo volume: riscontro immediato
            msg.send(sender, turningOn ? "turned-on" : "volume", "volume", String.valueOf(newVol), "track", trackName());
        } else {
            radio.stopFor(p);
            msg.send(sender, sub.equals("off") || sub.equals("no") ? "turned-off" : "volume-off");
        }
        return true;
    }

    private void help(CommandSender sender, int page) {
        org.bukkit.configuration.ConfigurationSection h = msg.section("help");
        String title = h != null ? h.getString("title", "MagixMusic") : "MagixMusic";
        Help.show(sender, title, "/radio help", Help.fromConfig(msg.section("help.sections")),
                page, sender.hasPermission(ADMIN));
    }

    /** Nome del brano in onda per i messaggi, o il testo "silenzio" se la radio non sta suonando. */
    private String trackName() {
        String n = radio.currentTrackName();
        return n == null ? msg.get("none-playing") : n;
    }

    /** Il numero di pagina scritto dall'utente; qualsiasi cosa strana vale 1. */
    private static int page(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 1; }
    }

    /** Interpreta un volume scritto a mano (0-100); null se non è un numero. */
    private static Integer parseVolume(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("on", "off", "up", "down", "replay", "help"));
            if (sender.hasPermission(ADMIN)) { base.add("next"); base.add("reload"); }
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
