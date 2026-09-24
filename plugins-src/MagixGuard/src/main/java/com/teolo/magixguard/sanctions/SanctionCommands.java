package com.teolo.magixguard.sanctions;

import com.teolo.magixguard.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * I comandi di moderazione: {@code /ban}, {@code /tempban}, {@code /mute}, {@code /kick},
 * {@code /warn}, {@code /unban}, {@code /unmute}, {@code /history}, {@code /sanctions}.
 *
 * <p>Sono i nomi standard di proposito: lo staff non deve imparare comandi nuovi, e soprattutto
 * non deve esistere una seconda strada per sanzionare che sfugga all'archivio. Per questo i
 * comandi di moderazione di CMI vanno disabilitati.</p>
 *
 * <p>Il lavoro vero e' tutto fuori dal thread principale: risolvere un nome, leggere lo storico e
 * scrivere un provvedimento sono giri sul database, e il server non deve fermarsi ad aspettarli.</p>
 */
public final class SanctionCommands implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final SanctionsConfig cfg;
    private final SanctionsService service;
    private final SanctionsDao dao;
    private final Messages messages;

    public SanctionCommands(JavaPlugin plugin, SanctionsConfig cfg, SanctionsService service, SanctionsDao dao,
                             Messages messages) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.service = service;
        this.dao = dao;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender chi, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();

        switch (name) {
            case "ban", "tempban", "mute", "tempmute", "kick", "warn" -> {
                return sanction(chi, name, args);
            }
            case "unban", "unmute" -> {
                return remove(chi, name, args);
            }
            case "history" -> {
                return history(chi, args);
            }
            case "sanctions" -> {
                return riepilogo(chi, args);
            }
            default -> {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ sanzionare

    private boolean sanction(CommandSender chi, String command, String[] args) {
        if (args.length < 1) {
            chi.sendMessage(Text.msg(messages.get(chi, "commands.usage",
                    "comando", command, "argomenti", usoDi(chi, command))));
            return true;
        }

        Type type = switch (command) {
            case "ban", "tempban" -> Type.BAN;
            case "mute", "tempmute" -> Type.MUTE;
            case "kick" -> Type.KICK;
            default -> Type.WARN;
        };

        String target = args[0];
        int fromWhere = 1;
        long duration;

        if (type.hasDuration()) {
            // Due comandi, due mestieri, nessuna ambiguita': /ban e /mute sono PERMANENTI e vogliono
            // solo il motivo; /tempban e /tempmute sono quelli a tempo e la durata la pretendono.
            // (Prima /mute chiedeva la durata come /tempmute: chi scriveva "/mute Tizio spam" si vedeva
            // rifiutare il comando e credeva di aver silenziato qualcuno che invece non lo era mai stato.)
            boolean aTempo = command.equals("tempban") || command.equals("tempmute");
            long letta = args.length > 1 ? Duration.read(args[1]) : 0L;
            if (aTempo) {
                if (letta == Duration.PERMANENTE) {
                    chi.sendMessage(Text.msg(messages.get(chi, "commands.duration-required",
                            "comando", command.substring(4))));
                    return true;
                }
                if (letta == 0L) {
                    chi.sendMessage(Text.msg(messages.get(chi, "commands.duration-invalid")));
                    return true;
                }
                duration = letta;
                fromWhere = 2;
            } else if (letta == Duration.PERMANENTE) {
                duration = Duration.PERMANENTE;   // "permanente" scritto per abitudine: si accetta e si salta
                fromWhere = 2;
            } else if (letta != 0L) {
                // Il motivo comincia con una durata: quasi sicuramente si voleva la versione a tempo.
                // Meglio chiederlo che trasformare per sbaglio un "3d" in un provvedimento per sempre.
                chi.sendMessage(Text.msg(messages.get(chi, "commands.permanent-no-duration",
                        "comando", command, "comando-tempo", "temp" + command)));
                return true;
            } else {
                duration = Duration.PERMANENTE;
            }
        } else {
            duration = 0L;
        }

        String reason = unisci(args, fromWhere);
        if (reason.isBlank()) {
            chi.sendMessage(Text.msg(messages.get(chi, "commands.reason-required")));
            return true;
        }

        Policy.Outcome staffOutcome = service.policy().checkStaff(chi, type, duration);
        long finalDuration = duration;
        String autore = chi instanceof Player p ? p.getName() : "Console";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = risolvi(target);
            if (uuid == null) {
                chi.sendMessage(Text.msg(messages.get(chi, "commands.unknown-player")));
                return;
            }

            long now = System.currentTimeMillis();
            long fine = finalDuration == Duration.PERMANENTE || !type.hasDuration()
                    ? Duration.PERMANENTE : now + finalDuration;

            SanctionsConfig.Category category = cfg.category("manuale");
            Sanction s = new Sanction(0, uuid, target, type, "manuale", reason,
                    category.scope(), 0, now, fine, autore, false, null);

            int id = service.apply(s, staffOutcome, "staff",
                    staffOutcome.apply() ? null : "Chiesta da " + autore + ": " + staffOutcome.proposedReason(),
                    finalDuration);

            if (id > 0) {
                chi.sendMessage(Text.msg(messages.get(chi, "commands.applied",
                        "nome", target, "tipo", messages.typeLabel(chi, type).toLowerCase(),
                        "durata", type.hasDuration() ? Duration.write(finalDuration)
                                : messages.get(chi, "commands.duration-immediate"),
                        "id", String.valueOf(id))));
            } else {
                chi.sendMessage(Text.msg(messages.get(chi, "commands.proposed",
                        "motivo", staffOutcome.proposedReason())));
            }
        });
        return true;
    }

    private String usoDi(CommandSender chi, String command) {
        return switch (command) {
            case "ban", "mute" -> "<giocatore> <motivo>  "
                    + messages.get(chi, "commands.usage-hint-permanent", "comando", command);
            case "tempban", "tempmute" -> "<giocatore> <durata> <motivo>  "
                    + messages.get(chi, "commands.usage-hint-duration-example");
            default -> "<giocatore> <motivo>";
        };
    }

    // ------------------------------------------------------------------ togliere

    private boolean remove(CommandSender chi, String command, String[] args) {
        if (args.length < 1) {
            chi.sendMessage(Text.msg(messages.get(chi, "commands.remove-usage", "comando", command)));
            return true;
        }
        Type type = command.equals("unban") ? Type.BAN : Type.MUTE;
        String target = args[0];
        String reason = args.length > 1 ? unisci(args, 1) : "Revocata dallo staff";
        String autore = chi instanceof Player p ? p.getName() : "Console";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = risolvi(target);
            if (uuid == null) {
                chi.sendMessage(Text.msg(messages.get(chi, "commands.remove-unknown-player")));
                return;
            }
            try {
                int id = service.revoke(uuid, type, autore, reason);
                if (id == 0) {
                    chi.sendMessage(Text.msg(messages.get(chi, "commands.remove-nothing-active",
                            "tipo", messages.typeLabel(chi, type).toLowerCase(), "nome", target)));
                    return;
                }
                Bukkit.getScheduler().runTask(plugin,
                        () -> service.applyRevokeFromSite(new Sanction(id, uuid, target, type,
                                "manuale", reason, Scope.ENTRAMBI, 0, 0, 0, autore, false, null)));
                chi.sendMessage(Text.msg(messages.get(chi, "commands.revoked", "id", String.valueOf(id))));
                service.notifyStaff("commands.revoke-broadcast", to -> new String[] {
                        "nome", target, "tipo", messages.typeLabel(to, type).toLowerCase(), "autore", autore });
            } catch (SQLException e) {
                chi.sendMessage(Text.msg(messages.get(chi, "commands.revoke-failed")));
                plugin.getLogger().warning("Revoca non riuscita: " + e.getMessage());
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ consultare

    private boolean history(CommandSender chi, String[] args) {
        if (args.length < 1) {
            chi.sendMessage(Text.msg(messages.get(chi, "commands.history-usage")));
            return true;
        }
        String target = args[0];
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = risolvi(target);
            if (uuid == null) {
                chi.sendMessage(Text.msg(messages.get(chi, "commands.remove-unknown-player")));
                return;
            }
            try {
                List<Sanction> rows = dao.history(uuid, 15);
                double points = service.log().points(uuid);

                chi.sendMessage(Text.panel(messages.get(chi, "commands.history-header",
                        "nome", target, "punti", String.valueOf(Math.round(points)))));
                if (rows.isEmpty()) {
                    chi.sendMessage(Text.panel(messages.get(chi, "commands.history-empty")));
                    return;
                }
                for (Sanction s : rows) {
                    String stato = messages.get(chi, s.activate()
                            ? "commands.history-status-active" : "commands.history-status-closed");
                    chi.sendMessage(Text.panel(messages.get(chi, "commands.history-row",
                            "tipo", messages.typeLabel(chi, s.type()), "durata", s.readableDuration(),
                            "motivo", s.reason(), "stato", stato, "id", String.valueOf(s.id()))));
                }
            } catch (SQLException e) {
                chi.sendMessage(Text.msg(messages.get(chi, "commands.history-error")));
            }
        });
        return true;
    }

    private boolean riepilogo(CommandSender chi, String[] args) {
        boolean suDiAltri = args.length > 0;
        if (suDiAltri && !chi.hasPermission("magixguard.staff")) {
            chi.sendMessage(Text.msg(messages.get(chi, "commands.sanctions-staff-only")));
            return true;
        }
        String target = suDiAltri ? args[0] : (chi instanceof Player p ? p.getName() : null);
        if (target == null) {
            chi.sendMessage(Text.msg(messages.get(chi, "commands.sanctions-usage")));
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = risolvi(target);
            if (uuid == null) {
                chi.sendMessage(Text.msg(messages.get(chi, "commands.remove-unknown-player")));
                return;
            }
            try {
                List<Sanction> attive = dao.attiveInGioco(uuid);
                double points = service.log().points(uuid);
                double remaining = service.log().mancanteAllaProssima(points);

                chi.sendMessage(Text.panel(messages.get(chi, "commands.sanctions-header", "nome", target)));
                if (attive.isEmpty()) {
                    chi.sendMessage(Text.panel(messages.get(chi, "commands.sanctions-none")));
                } else {
                    for (Sanction s : attive) {
                        String scadenza = s.fine() == Duration.PERMANENTE
                                ? messages.get(chi, "commands.sanctions-expiry-never")
                                : messages.get(chi, "commands.sanctions-expiry-in",
                                        "tempo", Duration.mancante(s.fine()));
                        chi.sendMessage(Text.panel(messages.get(chi, "commands.sanctions-row",
                                "tipo", messages.typeLabel(chi, s.type()), "motivo", s.reason(),
                                "scadenza", scadenza)));
                    }
                }
                String mancano = remaining < 0 ? "" : messages.get(chi, "commands.sanctions-points-remaining",
                        "numero", String.valueOf(Math.round(remaining)));
                chi.sendMessage(Text.panel(messages.get(chi, "commands.sanctions-points",
                        "punti", String.valueOf(Math.round(points)), "mancano", mancano)));
                chi.sendMessage(Text.panel(messages.get(chi, "commands.sanctions-halflife",
                        "giorni", String.valueOf(Math.round(cfg.halfLifeDays)))));
            } catch (SQLException e) {
                chi.sendMessage(Text.msg(messages.get(chi, "commands.archive-unreachable")));
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ utilita'

    /**
     * Da nickname a uuid. Prima chi e' collegato, poi l'account sul sito, e solo in ultimo
     * quello che Bukkit ricava dal nome: su un server non premium quest'ultimo e' calcolato
     * dal nome, quindi funziona anche per chi non e' mai entrato — ma e' bene provarlo per ultimo.
     */
    private UUID risolvi(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        try {
            UUID fromSite = dao.uuidFromName(name);
            if (fromSite != null) {
                return fromSite;
            }
        } catch (SQLException ignored) {
            // il sito non risponde: si prova comunque con quello che sa Bukkit
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return off.hasPlayedBefore() || off.isOnline() ? off.getUniqueId() : off.getUniqueId();
    }

    private static String unisci(String[] args, int da) {
        if (da >= args.length) {
            return "";
        }
        return String.join(" ", java.util.Arrays.copyOfRange(args, da, args.length)).trim();
    }

    @Override
    public List<String> onTabComplete(CommandSender chi, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[0].toLowerCase())) {
                    out.add(p.getName());
                }
            }
            return out;
        }
        if (args.length == 2) {
            String c = command.getName().toLowerCase();
            if (c.equals("tempban") || c.equals("tempmute")) {   // /ban e /mute sono permanenti: niente durata
                for (String d : List.of("30m", "1h", "6h", "24h", "3d", "7d", "30d")) {
                    if (d.startsWith(args[1].toLowerCase())) {
                        out.add(d);
                    }
                }
            }
        }
        return out;
    }
}
