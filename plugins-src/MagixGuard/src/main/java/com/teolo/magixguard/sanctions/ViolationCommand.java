package com.teolo.magixguard.sanctions;

import com.teolo.magixguard.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;

/**
 * {@code /mgviolation <giocatore> <categoria> [dettaglio...]} — l'ingresso per chi rileva
 * dall'esterno, cioe' oggi <b>Grim</b>.
 *
 * <p>E' l'adattatore piu' semplice possibile, ed e' voluto. Grim (come Vulcan, come qualunque
 * altro) sa gia' eseguire un comando quando un giocatore supera una certa soglia di violazioni:
 * gli si fa eseguire questo. Il risultato e' che <b>MagixGuard non dipende dall'API di nessun
 * anticheat</b> — ne' dalla sua versione, ne' dal fatto che continui a esistere.</p>
 *
 * <p>Nel {@code punishments.yml} di Grim la riga da aggiungere e' questa:</p>
 * <pre>
 *   commands:
 *     - "40:40 mgviolation %player% cheat.movimento %check_name% (vl %vl%)"
 * </pre>
 *
 * <p>Chi decide <i>quando</i> chiamare e' l'anticheat; chi decide <i>cosa succede</i> e' il
 * registro punti. Le due cose restano separate, e si possono tarare una senza toccare l'altra.</p>
 */
public final class ViolationCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final Detector detector;
    private final SanctionsDao dao;
    private final Messages messages;

    public ViolationCommand(JavaPlugin plugin, Detector detector, SanctionsDao dao, Messages messages) {
        this.plugin = plugin;
        this.detector = detector;
        this.dao = dao;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender chi, Command command, String label, String[] args) {
        if (args.length < 2) {
            chi.sendMessage(Text.msg(messages.get(chi, "violation-command.usage")));
            chi.sendMessage(Text.panel(messages.get(chi, "violation-command.usage-hint")));
            return true;
        }

        String target = args[0];
        String category = args[1];
        String dettaglio = args.length > 2
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";

        Player online = Bukkit.getPlayerExact(target);
        String chiHaChiamato = chi instanceof Player p ? p.getName() : "console";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = online != null ? online.getUniqueId() : search(target);
            if (uuid == null) {
                plugin.getLogger().warning("Violazione ignorata: giocatore sconosciuto '" + target + "'.");
                return;
            }
            String prove = "Rilevata dall'anticheat.\n"
                    + "Chiamata da: " + chiHaChiamato + '\n'
                    + "Quando: " + com.teolo.magixguard.util.Fmt.dateTime(System.currentTimeMillis()) + '\n'
                    + (dettaglio.isBlank() ? "" : "Dettaglio riportato: " + dettaglio + '\n')
                    + (online != null
                        ? "Posizione: " + online.getWorld().getName() + ' '
                          + online.getLocation().getBlockX() + ", "
                          + online.getLocation().getBlockY() + ", "
                          + online.getLocation().getBlockZ() + '\n'
                        : "Il giocatore non era collegato al momento della chiamata.\n")
                    + "\nLimiti: questo e' il verdetto di un programma esterno, riportato tale e quale. "
                    + "Vale quanto vale la taratura di quell'anticheat: prima di un provvedimento grave "
                    + "conviene guardare il replay o le sue statistiche.";

            detector.rileva(uuid, online != null ? online.getName() : target,
                    category, "anticheat", prove);
        });
        return true;
    }

    private UUID search(String name) {
        try {
            UUID fromSite = dao.uuidFromName(name);
            if (fromSite != null) {
                return fromSite;
            }
        } catch (SQLException ignored) {
            // il sito non risponde: si prova con quello che sa Bukkit
        }
        org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return off.hasPlayedBefore() ? off.getUniqueId() : null;
    }
}
