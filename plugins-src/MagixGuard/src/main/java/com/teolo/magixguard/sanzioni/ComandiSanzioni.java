package com.teolo.magixguard.sanzioni;

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
 * {@code /warn}, {@code /unban}, {@code /unmute}, {@code /storico}, {@code /sanzioni}.
 *
 * <p>Sono i nomi standard di proposito: lo staff non deve imparare comandi nuovi, e soprattutto
 * non deve esistere una seconda strada per sanzionare che sfugga all'archivio. Per questo i
 * comandi di moderazione di CMI vanno disabilitati.</p>
 *
 * <p>Il lavoro vero e' tutto fuori dal thread principale: risolvere un nome, leggere lo storico e
 * scrivere un provvedimento sono giri sul database, e il server non deve fermarsi ad aspettarli.</p>
 */
public final class ComandiSanzioni implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final SanzioniConfig cfg;
    private final ServizioSanzioni servizio;
    private final SanzioniDao dao;

    public ComandiSanzioni(JavaPlugin plugin, SanzioniConfig cfg, ServizioSanzioni servizio, SanzioniDao dao) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.servizio = servizio;
        this.dao = dao;
    }

    @Override
    public boolean onCommand(CommandSender chi, Command comando, String etichetta, String[] args) {
        String nome = comando.getName().toLowerCase();

        switch (nome) {
            case "ban", "tempban", "mute", "tempmute", "kick", "warn" -> {
                return sanziona(chi, nome, args);
            }
            case "unban", "unmute" -> {
                return togli(chi, nome, args);
            }
            case "storico" -> {
                return storico(chi, args);
            }
            case "sanzioni" -> {
                return riepilogo(chi, args);
            }
            default -> {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ sanzionare

    private boolean sanziona(CommandSender chi, String comando, String[] args) {
        if (args.length < 1) {
            chi.sendMessage(Testo.msg("&#FFD166Uso: &f/" + comando + " " + usoDi(comando)));
            return true;
        }

        Tipo tipo = switch (comando) {
            case "ban", "tempban" -> Tipo.BAN;
            case "mute", "tempmute" -> Tipo.MUTE;
            case "kick" -> Tipo.KICK;
            default -> Tipo.WARN;
        };

        String bersaglio = args[0];
        int daDove = 1;
        long durata;

        if (tipo.haDurata()) {
            // Due comandi, due mestieri, nessuna ambiguita': /ban e /mute sono PERMANENTI e vogliono
            // solo il motivo; /tempban e /tempmute sono quelli a tempo e la durata la pretendono.
            // (Prima /mute chiedeva la durata come /tempmute: chi scriveva "/mute Tizio spam" si vedeva
            // rifiutare il comando e credeva di aver silenziato qualcuno che invece non lo era mai stato.)
            boolean aTempo = comando.equals("tempban") || comando.equals("tempmute");
            long letta = args.length > 1 ? Durata.leggi(args[1]) : 0L;
            if (aTempo) {
                if (letta == Durata.PERMANENTE) {
                    chi.sendMessage(Testo.msg("&#FF6B6BQui la durata serve davvero. &7Per il permanente "
                            + "usa &f/" + comando.substring(4) + "&7."));
                    return true;
                }
                if (letta == 0L) {
                    chi.sendMessage(Testo.msg("&#FF6B6BDurata non riconosciuta. &7Scrivila come 30m, 6h, 3d, 2w."));
                    return true;
                }
                durata = letta;
                daDove = 2;
            } else if (letta == Durata.PERMANENTE) {
                durata = Durata.PERMANENTE;   // "permanente" scritto per abitudine: si accetta e si salta
                daDove = 2;
            } else if (letta != 0L) {
                // Il motivo comincia con una durata: quasi sicuramente si voleva la versione a tempo.
                // Meglio chiederlo che trasformare per sbaglio un "3d" in un provvedimento per sempre.
                chi.sendMessage(Testo.msg("&#FFD166&f/" + comando + " &#FFD166e' permanente e non vuole una durata. "
                        + "&7Per una sanzione a tempo usa &f/temp" + comando + " <giocatore> <durata> <motivo>&7."));
                return true;
            } else {
                durata = Durata.PERMANENTE;
            }
        } else {
            durata = 0L;
        }

        String motivo = unisci(args, daDove);
        if (motivo.isBlank()) {
            chi.sendMessage(Testo.msg("&#FF6B6BIl motivo non e' facoltativo: &7lo legge il giocatore, "
                    + "e finisce nell'elenco pubblico."));
            return true;
        }

        Politica.Esito esitoStaff = servizio.politica().controllaStaff(chi, tipo, durata);
        long durataFinale = durata;
        String autore = chi instanceof Player p ? p.getName() : "Console";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = risolvi(bersaglio);
            if (uuid == null) {
                chi.sendMessage(Testo.msg("&#FF6B6BNon conosco nessuno con quel nome. "
                        + "&7Deve essere entrato almeno una volta, o avere un account sul sito."));
                return;
            }

            long adesso = System.currentTimeMillis();
            long fine = durataFinale == Durata.PERMANENTE || !tipo.haDurata()
                    ? Durata.PERMANENTE : adesso + durataFinale;

            SanzioniConfig.Categoria categoria = cfg.categoria("manuale");
            Sanzione s = new Sanzione(0, uuid, bersaglio, tipo, "manuale", motivo,
                    categoria.ambito(), 0, adesso, fine, autore, false, null);

            int id = servizio.applica(s, esitoStaff, "staff",
                    esitoStaff.applica() ? null : "Chiesta da " + autore + ": " + esitoStaff.motivoProposta(),
                    durataFinale);

            if (id > 0) {
                chi.sendMessage(Testo.msg("&#A8DC2CFatto. &f" + bersaglio + " &7— "
                        + tipo.etichetta().toLowerCase() + ", "
                        + (tipo.haDurata() ? Durata.scrivi(durataFinale) : "immediata")
                        + ". &7Provvedimento n. " + id + "."));
            } else {
                chi.sendMessage(Testo.msg("&#FFD166Proposta inviata: &7" + esitoStaff.motivoProposta()
                        + ". &7La trovi nel gestionale, con le prove gia' allegate."));
            }
        });
        return true;
    }

    private String usoDi(String comando) {
        return switch (comando) {
            case "ban", "mute" -> "<giocatore> <motivo>  &7(permanente; a tempo: /temp" + comando + ")";
            case "tempban", "tempmute" -> "<giocatore> <durata> <motivo>  &7(es. 30m, 6h, 3d)";
            default -> "<giocatore> <motivo>";
        };
    }

    // ------------------------------------------------------------------ togliere

    private boolean togli(CommandSender chi, String comando, String[] args) {
        if (args.length < 1) {
            chi.sendMessage(Testo.msg("&#FFD166Uso: &f/" + comando + " <giocatore> [motivo]"));
            return true;
        }
        Tipo tipo = comando.equals("unban") ? Tipo.BAN : Tipo.MUTE;
        String bersaglio = args[0];
        String motivo = args.length > 1 ? unisci(args, 1) : "Revocata dallo staff";
        String autore = chi instanceof Player p ? p.getName() : "Console";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = risolvi(bersaglio);
            if (uuid == null) {
                chi.sendMessage(Testo.msg("&#FF6B6BNon conosco nessuno con quel nome."));
                return;
            }
            try {
                int id = servizio.revoca(uuid, tipo, autore, motivo);
                if (id == 0) {
                    chi.sendMessage(Testo.msg("&7Non c'e' nessun " + tipo.etichetta().toLowerCase()
                            + " attivo per &f" + bersaglio + "&7."));
                    return;
                }
                Bukkit.getScheduler().runTask(plugin,
                        () -> servizio.applicaRevocaDalSito(new Sanzione(id, uuid, bersaglio, tipo,
                                "manuale", motivo, Ambito.ENTRAMBI, 0, 0, 0, autore, false, null)));
                chi.sendMessage(Testo.msg("&#A8DC2CRevocato. &7Provvedimento n. " + id + "."));
                servizio.avvisaStaff("&#A8DC2CRevoca&f " + bersaglio + " &7— "
                        + tipo.etichetta().toLowerCase() + ", da " + autore);
            } catch (SQLException e) {
                chi.sendMessage(Testo.msg("&#FF6B6BRevoca non riuscita: il database del sito non risponde."));
                plugin.getLogger().warning("Revoca non riuscita: " + e.getMessage());
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ consultare

    private boolean storico(CommandSender chi, String[] args) {
        if (args.length < 1) {
            chi.sendMessage(Testo.msg("&#FFD166Uso: &f/storico <giocatore>"));
            return true;
        }
        String bersaglio = args[0];
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = risolvi(bersaglio);
            if (uuid == null) {
                chi.sendMessage(Testo.msg("&#FF6B6BNon conosco nessuno con quel nome."));
                return;
            }
            try {
                List<Sanzione> righe = dao.storico(uuid, 15);
                double punti = servizio.registro().punti(uuid);

                chi.sendMessage(Testo.panel("&#C046E8&lStorico di &f" + bersaglio
                        + " &8(&f" + Math.round(punti) + "&8 punti attuali)"));
                if (righe.isEmpty()) {
                    chi.sendMessage(Testo.panel("&7Nessun provvedimento. "));
                    return;
                }
                for (Sanzione s : righe) {
                    String stato = s.attiva() ? "&#FF6B6Bin corso" : "&7conclusa";
                    chi.sendMessage(Testo.panel("&8- &f" + s.tipo().etichetta() + " &7"
                            + s.durataLeggibile() + " &8| &7" + s.motivo()
                            + " &8| " + stato + " &8| &7n." + s.id()));
                }
            } catch (SQLException e) {
                chi.sendMessage(Testo.msg("&#FF6B6BArchivio non raggiungibile."));
            }
        });
        return true;
    }

    private boolean riepilogo(CommandSender chi, String[] args) {
        boolean suDiAltri = args.length > 0;
        if (suDiAltri && !chi.hasPermission("magixguard.staff")) {
            chi.sendMessage(Testo.msg("&#FF6B6BPuoi vedere solo le tue."));
            return true;
        }
        String bersaglio = suDiAltri ? args[0] : (chi instanceof Player p ? p.getName() : null);
        if (bersaglio == null) {
            chi.sendMessage(Testo.msg("&#FFD166Uso: &f/sanzioni <giocatore>"));
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = risolvi(bersaglio);
            if (uuid == null) {
                chi.sendMessage(Testo.msg("&#FF6B6BNon conosco nessuno con quel nome."));
                return;
            }
            try {
                List<Sanzione> attive = dao.attiveInGioco(uuid);
                double punti = servizio.registro().punti(uuid);
                double manca = servizio.registro().mancanteAllaProssima(punti);

                chi.sendMessage(Testo.panel("&#C046E8&lSanzioni di &f" + bersaglio));
                if (attive.isEmpty()) {
                    chi.sendMessage(Testo.panel("&#A8DC2CNessun provvedimento in corso."));
                } else {
                    for (Sanzione s : attive) {
                        chi.sendMessage(Testo.panel("&8- &f" + s.tipo().etichetta() + " &7"
                                + s.motivo() + " &8| &7"
                                + (s.fine() == Durata.PERMANENTE ? "non scade"
                                        : "finisce fra " + Durata.mancante(s.fine()))));
                    }
                }
                chi.sendMessage(Testo.panel("&7Punti: &f" + Math.round(punti)
                        + (manca < 0 ? "" : " &8(&7ne mancano " + Math.round(manca)
                                + " al prossimo provvedimento&8)")));
                chi.sendMessage(Testo.panel("&8I punti dimezzano ogni "
                        + Math.round(cfg.dimezzamentoGiorni) + " giorni."));
            } catch (SQLException e) {
                chi.sendMessage(Testo.msg("&#FF6B6BArchivio non raggiungibile."));
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
    private UUID risolvi(String nome) {
        Player online = Bukkit.getPlayerExact(nome);
        if (online != null) {
            return online.getUniqueId();
        }
        try {
            UUID daSito = dao.uuidDalNome(nome);
            if (daSito != null) {
                return daSito;
            }
        } catch (SQLException ignored) {
            // il sito non risponde: si prova comunque con quello che sa Bukkit
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(nome);
        return off.hasPlayedBefore() || off.isOnline() ? off.getUniqueId() : off.getUniqueId();
    }

    private static String unisci(String[] args, int da) {
        if (da >= args.length) {
            return "";
        }
        return String.join(" ", java.util.Arrays.copyOfRange(args, da, args.length)).trim();
    }

    @Override
    public List<String> onTabComplete(CommandSender chi, Command comando, String etichetta, String[] args) {
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
            String c = comando.getName().toLowerCase();
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
