package com.teolo.magixguard.sanctions;

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

    public SanctionCommands(JavaPlugin plugin, SanctionsConfig cfg, SanctionsService service, SanctionsDao dao) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.service = service;
        this.dao = dao;
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
            chi.sendMessage(Text.msg("&#FFD166Uso: &f/" + command + " " + usoDi(command)));
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
                    chi.sendMessage(Text.msg("&#FF6B6BQui la durata serve davvero. &7Per il permanente "
                            + "usa &f/" + command.substring(4) + "&7."));
                    return true;
                }
                if (letta == 0L) {
                    chi.sendMessage(Text.msg("&#FF6B6BDurata non riconosciuta. &7Scrivila come 30m, 6h, 3d, 2w."));
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
                chi.sendMessage(Text.msg("&#FFD166&f/" + command + " &#FFD166e' permanente e non vuole una durata. "
                        + "&7Per una sanzione a tempo usa &f/temp" + command + " <giocatore> <durata> <motivo>&7."));
                return true;
            } else {
                duration = Duration.PERMANENTE;
            }
        } else {
            duration = 0L;
        }

        String reason = unisci(args, fromWhere);
        if (reason.isBlank()) {
            chi.sendMessage(Text.msg("&#FF6B6BIl motivo non e' facoltativo: &7lo legge il giocatore, "
                    + "e finisce nell'elenco pubblico."));
            return true;
        }

        Policy.Outcome staffOutcome = service.policy().checkStaff(chi, type, duration);
        long finalDuration = duration;
        String autore = chi instanceof Player p ? p.getName() : "Console";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = risolvi(target);
            if (uuid == null) {
                chi.sendMessage(Text.msg("&#FF6B6BNon conosco nessuno con quel nome. "
                        + "&7Deve essere entrato almeno una volta, o avere un account sul sito."));
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
                chi.sendMessage(Text.msg("&#A8DC2CFatto. &f" + target + " &7— "
                        + type.label().toLowerCase() + ", "
                        + (type.hasDuration() ? Duration.write(finalDuration) : "immediata")
                        + ". &7Provvedimento n. " + id + "."));
            } else {
                chi.sendMessage(Text.msg("&#FFD166Proposta inviata: &7" + staffOutcome.proposedReason()
                        + ". &7La trovi nel gestionale, con le prove gia' allegate."));
            }
        });
        return true;
    }

    private String usoDi(String command) {
        return switch (command) {
            case "ban", "mute" -> "<giocatore> <motivo>  &7(permanente; a tempo: /temp" + command + ")";
            case "tempban", "tempmute" -> "<giocatore> <durata> <motivo>  &7(es. 30m, 6h, 3d)";
            default -> "<giocatore> <motivo>";
        };
    }

    // ------------------------------------------------------------------ togliere

    private boolean remove(CommandSender chi, String command, String[] args) {
        if (args.length < 1) {
            chi.sendMessage(Text.msg("&#FFD166Uso: &f/" + command + " <giocatore> [motivo]"));
            return true;
        }
        Type type = command.equals("unban") ? Type.BAN : Type.MUTE;
        String target = args[0];
        String reason = args.length > 1 ? unisci(args, 1) : "Revocata dallo staff";
        String autore = chi instanceof Player p ? p.getName() : "Console";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = risolvi(target);
            if (uuid == null) {
                chi.sendMessage(Text.msg("&#FF6B6BNon conosco nessuno con quel nome."));
                return;
            }
            try {
                int id = service.revoke(uuid, type, autore, reason);
                if (id == 0) {
                    chi.sendMessage(Text.msg("&7Non c'e' nessun " + type.label().toLowerCase()
                            + " attivo per &f" + target + "&7."));
                    return;
                }
                Bukkit.getScheduler().runTask(plugin,
                        () -> service.applyRevokeFromSite(new Sanction(id, uuid, target, type,
                                "manuale", reason, Scope.ENTRAMBI, 0, 0, 0, autore, false, null)));
                chi.sendMessage(Text.msg("&#A8DC2CRevocato. &7Provvedimento n. " + id + "."));
                service.notifyStaff("&#A8DC2CRevoca&f " + target + " &7— "
                        + type.label().toLowerCase() + ", da " + autore);
            } catch (SQLException e) {
                chi.sendMessage(Text.msg("&#FF6B6BRevoca non riuscita: il database del sito non risponde."));
                plugin.getLogger().warning("Revoca non riuscita: " + e.getMessage());
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ consultare

    private boolean history(CommandSender chi, String[] args) {
        if (args.length < 1) {
            chi.sendMessage(Text.msg("&#FFD166Uso: &f/history <giocatore>"));
            return true;
        }
        String target = args[0];
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = risolvi(target);
            if (uuid == null) {
                chi.sendMessage(Text.msg("&#FF6B6BNon conosco nessuno con quel nome."));
                return;
            }
            try {
                List<Sanction> rows = dao.history(uuid, 15);
                double points = service.log().points(uuid);

                chi.sendMessage(Text.panel("&#C046E8&lStorico di &f" + target
                        + " &8(&f" + Math.round(points) + "&8 punti attuali)"));
                if (rows.isEmpty()) {
                    chi.sendMessage(Text.panel("&7Nessun provvedimento. "));
                    return;
                }
                for (Sanction s : rows) {
                    String stato = s.activate() ? "&#FF6B6Bin corso" : "&7conclusa";
                    chi.sendMessage(Text.panel("&8- &f" + s.type().label() + " &7"
                            + s.readableDuration() + " &8| &7" + s.reason()
                            + " &8| " + stato + " &8| &7n." + s.id()));
                }
            } catch (SQLException e) {
                chi.sendMessage(Text.msg("&#FF6B6BArchivio non raggiungibile."));
            }
        });
        return true;
    }

    private boolean riepilogo(CommandSender chi, String[] args) {
        boolean suDiAltri = args.length > 0;
        if (suDiAltri && !chi.hasPermission("magixguard.staff")) {
            chi.sendMessage(Text.msg("&#FF6B6BPuoi vedere solo le tue."));
            return true;
        }
        String target = suDiAltri ? args[0] : (chi instanceof Player p ? p.getName() : null);
        if (target == null) {
            chi.sendMessage(Text.msg("&#FFD166Uso: &f/sanctions <giocatore>"));
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = risolvi(target);
            if (uuid == null) {
                chi.sendMessage(Text.msg("&#FF6B6BNon conosco nessuno con quel nome."));
                return;
            }
            try {
                List<Sanction> attive = dao.attiveInGioco(uuid);
                double points = service.log().points(uuid);
                double remaining = service.log().mancanteAllaProssima(points);

                chi.sendMessage(Text.panel("&#C046E8&lSanzioni di &f" + target));
                if (attive.isEmpty()) {
                    chi.sendMessage(Text.panel("&#A8DC2CNessun provvedimento in corso."));
                } else {
                    for (Sanction s : attive) {
                        chi.sendMessage(Text.panel("&8- &f" + s.type().label() + " &7"
                                + s.reason() + " &8| &7"
                                + (s.fine() == Duration.PERMANENTE ? "non scade"
                                        : "finisce fra " + Duration.mancante(s.fine()))));
                    }
                }
                chi.sendMessage(Text.panel("&7Punti: &f" + Math.round(points)
                        + (remaining < 0 ? "" : " &8(&7ne mancano " + Math.round(remaining)
                                + " al prossimo provvedimento&8)")));
                chi.sendMessage(Text.panel("&8I punti dimezzano ogni "
                        + Math.round(cfg.halfLifeDays) + " giorni."));
            } catch (SQLException e) {
                chi.sendMessage(Text.msg("&#FF6B6BArchivio non raggiungibile."));
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
