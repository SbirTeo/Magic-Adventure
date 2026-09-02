package com.teolo.magixguard.sanctions;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /report <giocatore> <motivo>}: la segnalazione di un giocatore.
 *
 * <p>E' il canale che fa emergere quello che nessun algoritmo vede — truffe, molestie, accordi
 * fra due account, comportamenti che stanno nelle intenzioni e non nei pacchetti. Finisce nella
 * <b>stessa coda</b> dei rilevamenti automatici, cosi' lo staff ha un posto solo da guardare.</p>
 *
 * <p>Una segnalazione non e' una sanzione e non ne fa scattare nessuna da sola: apre un caso. A
 * decidere e' sempre una persona, dal gestionale.</p>
 *
 * <p>Contro l'abuso ci sono tre freni, tutti configurabili: una pausa fra una segnalazione e
 * l'altra, un numero massimo di casi aperti a testa, e un motivo che deve essere scritto davvero
 * («barare» non dice niente a chi dovra' controllare).</p>
 */
public final class ReportCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final SanctionsConfig cfg;
    private final SanctionsService service;
    private final SanctionsDao dao;

    /** Ultima segnalazione di ognuno, per far rispettare la pausa. */
    private final Map<UUID, Long> ultima = new ConcurrentHashMap<>();

    public ReportCommand(JavaPlugin plugin, SanctionsConfig cfg, SanctionsService service, SanctionsDao dao) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.service = service;
        this.dao = dao;
    }

    @Override
    public boolean onCommand(CommandSender chi, Command command, String label, String[] args) {
        if (!cfg.reportActive) {
            chi.sendMessage(Text.msg("&7Le segnalazioni sono disattivate su questo server."));
            return true;
        }
        if (!(chi instanceof Player mittente)) {
            chi.sendMessage(Text.msg("&7Le segnalazioni le mandano i giocatori. &7I casi aperti si leggono nel gestionale del sito, in Sanzioni."));
            return true;
        }
        if (args.length < 2) {
            chi.sendMessage(Text.msg("&#FFD166Uso: &f/report <giocatore> <motivo>"));
            chi.sendMessage(Text.panel("&7Scrivi cosa e' successo, non solo l'accusa: chi controllera' "
                    + "non era li'."));
            return true;
        }

        String target = args[0];
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();

        if (target.equalsIgnoreCase(mittente.getName())) {
            chi.sendMessage(Text.msg("&#FF6B6BNon puoi segnalare te stesso."));
            return true;
        }
        if (reason.length() < cfg.reportMinReason) {
            chi.sendMessage(Text.msg("&#FF6B6BSpiega meglio: &7servono almeno "
                    + cfg.reportMinReason + " caratteri. Cosa ha fatto, dove, quando."));
            return true;
        }

        long now = System.currentTimeMillis();
        long precedente = ultima.getOrDefault(mittente.getUniqueId(), 0L);
        long pause = cfg.reportCooldownSeconds * 1000L;
        if (now - precedente < pause) {
            long restano = (pause - (now - precedente)) / 1000;
            chi.sendMessage(Text.msg("&7Aspetta ancora &f" + restano + " secondi&7 prima di segnalare di nuovo."));
            return true;
        }

        // Da qui in poi si lavora sul database: fuori dal thread principale.
        Player targetOnline = Bukkit.getPlayerExact(target);
        String dettaglio = buildContext(mittente, target, targetOnline);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = risolvi(target, targetOnline);
            if (uuid == null) {
                chi.sendMessage(Text.msg("&#FF6B6BNon conosco nessuno con quel nome. &7Controlla come si scrive."));
                return;
            }
            try {
                int aperti = dao.reportApertiDi(mittente.getUniqueId().toString());
                if (aperti >= cfg.reportMaxOpen) {
                    chi.sendMessage(Text.msg("&7Hai gia' &f" + aperti + "&7 segnalazioni in attesa di risposta. "
                            + "Aspetta che lo staff le guardi."));
                    return;
                }

                // Il tipo qui e' solo un segnaposto: la segnalazione non propone una pena,
                // apre un caso. Chi lo chiude sceglie il provvedimento nel gestionale.
                Sanction caso = new Sanction(0, uuid, target, Type.WARN, "report.confermato",
                        reason, Scope.ENTRAMBI, 0, now, Duration.PERMANENTE,
                        mittente.getName(), false, null);

                dao.proponi(caso, 0L, "report", dettaglio);
                ultima.put(mittente.getUniqueId(), now);

                chi.sendMessage(Text.msg("&#A8DC2CSegnalazione inviata. &7Lo staff la trova nel gestionale "
                        + "con la tua posizione e l'ora. Grazie."));

                service.notifyStaff("&#FFD166Segnalazione&f " + mittente.getName() + " &7ha segnalato &f"
                        + target + " &7— " + reason);
            } catch (SQLException e) {
                chi.sendMessage(Text.msg("&#FF6B6BSegnalazione non inviata: riprova fra poco."));
                plugin.getLogger().warning("Segnalazione non registrata: " + e.getMessage());
            }
        });
        return true;
    }

    /**
     * Il contesto che lo staff trovera' allegato: chi ha segnalato, dov'era, dov'era il
     * segnalato, e se in quel momento era collegato. Sono le prime tre cose che serve sapere
     * per capire se andare a guardare subito o con calma.
     */
    private String buildContext(Player mittente, String target, Player targetOnline) {
        StringBuilder b = new StringBuilder();
        b.append("Segnalata da: ").append(mittente.getName()).append('\n');
        b.append("Quando: ").append(com.teolo.magixguard.util.Fmt.dateTime(System.currentTimeMillis())).append('\n');
        b.append("Chi segnala si trovava: ").append(position(mittente.getLocation())).append('\n');
        if (targetOnline != null) {
            b.append("Il segnalato era ONLINE, a ").append(position(targetOnline.getLocation())).append('\n');
            double distanza = targetOnline.getWorld().equals(mittente.getWorld())
                    ? targetOnline.getLocation().distance(mittente.getLocation()) : -1;
            b.append("Distanza fra i due: ")
             .append(distanza < 0 ? "in mondi diversi" : Math.round(distanza) + " blocchi").append('\n');
        } else {
            b.append("Il segnalato NON era collegato in quel momento.").append('\n');
        }
        b.append("Segnalato: ").append(target);
        return b.toString();
    }

    private static String position(Location l) {
        return l.getWorld().getName() + " " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    private UUID risolvi(String name, Player online) {
        if (online != null) {
            return online.getUniqueId();
        }
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

    @Override
    public List<String> onTabComplete(CommandSender chi, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.equals(chi) && p.getName().toLowerCase().startsWith(args[0].toLowerCase())) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}
