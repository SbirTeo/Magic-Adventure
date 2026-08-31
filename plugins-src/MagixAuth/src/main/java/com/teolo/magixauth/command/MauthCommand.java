package com.teolo.magixauth.command;

import com.teolo.magixauth.AuthConfig;
import com.teolo.magixauth.MagixAuth;
import com.teolo.magixauth.crypt.Password;
import com.teolo.magixauth.db.AuthDao;
import com.teolo.magixauth.gate.AuthGate;
import com.teolo.magixauth.gate.PoliticaOtp;
import com.teolo.magixauth.model.Account;
import com.teolo.magixauth.util.Aiuto;
import com.teolo.magixauth.util.Testi;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * /mauth — gli strumenti di chi amministra il login.
 *
 * Volutamente pochi comandi: quasi tutto si aggiusta da solo, e i pochi casi che restano
 * sono "questo giocatore ha dimenticato la password" e "voglio capire perche' non entra".
 */
public final class MauthCommand implements CommandExecutor, TabCompleter {

    private final MagixAuth plugin;
    private final AuthConfig config;
    private final AuthDao dao;
    private final PoliticaOtp politica;
    private final AuthGate gate;

    public MauthCommand(MagixAuth plugin, AuthConfig config, AuthDao dao,
                        PoliticaOtp politica, AuthGate gate) {
        this.plugin = plugin;
        this.config = config;
        this.dao = dao;
        this.politica = politica;
        this.gate = gate;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            aiuto(sender, 1);
            return true;
        }
        // Il numero da solo sfoglia le pagine: e' quello che mandano le frecce.
        if (args[0].matches("\\d+")) {
            aiuto(sender, Integer.parseInt(args[0]));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.ricarica();
                rispondi(sender, "&aConfigurazione ricaricata.");
            }
            case "info" -> info(sender);
            case "reset" -> {
                if (!sender.hasPermission("magixauth.reset")) {
                    rispondi(sender, "&cNon hai il permesso di azzerare le password.");
                    return true;
                }
                if (args.length < 2) {
                    rispondi(sender, "&7Uso: &f/mauth reset <giocatore>");
                    return true;
                }
                reset(sender, args[1]);
            }
            case "register" -> {
                if (!sender.hasPermission("magixauth.reset")) {
                    rispondi(sender, "&cNon hai il permesso di registrare giocatori.");
                    return true;
                }
                if (args.length < 3) {
                    rispondi(sender, "&7Uso: &f/mauth register <giocatore> <password>");
                    return true;
                }
                registra(sender, args[1], args[2]);
            }
            case "unlock" -> {
                if (!sender.hasPermission("magixauth.unlock")) {
                    rispondi(sender, "&cNon hai il permesso di togliere i blocchi.");
                    return true;
                }
                if (args.length < 2) {
                    rispondi(sender, "&7Uso: &f/mauth unlock <giocatore|indirizzo>");
                    return true;
                }
                sblocca(sender, args[1]);
            }
            case "sessions" -> {
                if (args.length < 2) {
                    rispondi(sender, "&7Uso: &f/mauth sessions <giocatore>");
                    return true;
                }
                sessioni(sender, args[1]);
            }
            default -> aiuto(sender, 1);
        }
        return true;
    }

    /**
     * L'elenco dei comandi.
     *
     * Sta tutto in una lista invece che in una sfilza di messaggi perche' cosi' si puo'
     * impaginare, filtrare per permesso e rendere cliccabile senza riscriverlo ogni volta.
     */
    private void aiuto(CommandSender sender, int pagina) {
        List<Aiuto.Voce> voci = List.of(
                Aiuto.Voce.di("/login", "<password>", "entra con la tua password").in("Il tuo account"),
                Aiuto.Voce.di("/register", "<password> <ripeti>", "registrati al primo ingresso").in("Il tuo account"),
                Aiuto.Voce.di("/otp", "<codice>", "il codice della verifica in due passaggi").in("Il tuo account"),
                Aiuto.Voce.di("/changepassword", "<vecchia> <nuova> <ripeti> [codice]",
                        "cambia la password (vale anche sul sito)").in("Il tuo account"),
                Aiuto.Voce.di("/logout", "", "chiude l'accesso in gioco e sul sito").in("Il tuo account"),
                Aiuto.Voce.staff("/mauth info", "", "stato del plugin e della verifica").in("Staff"),
                Aiuto.Voce.staff("/mauth register", "<giocatore> <password>",
                        "registra un giocatore o gli riscrive la password").in("Staff"),
                Aiuto.Voce.staff("/mauth reset", "<giocatore>", "azzera la password di chi l'ha dimenticata").in("Staff"),
                Aiuto.Voce.staff("/mauth unlock", "<giocatore|indirizzo>",
                        "toglie l'attesa a chi ha sbagliato troppe volte").in("Staff"),
                Aiuto.Voce.staff("/mauth sessions", "<giocatore>", "dimentica i suoi accessi ricordati").in("Staff"),
                Aiuto.Voce.staff("/mauth reload", "", "rilegge la configurazione").in("Staff"));

        Aiuto.mostra(sender, "MagixAuth", "/mauth", voci, pagina,
                sender.hasPermission("magixauth.admin"));
    }

    private void info(CommandSender sender) {
        rispondi(sender, "&7Account condiviso con &fmagicadventure.it&7 (tabella users).");
        rispondi(sender, "&7Chiave OTP: " + (config.chiaveOtpPronta() ? "&apronta" : "&cmancante o non valida"));
        rispondi(sender, "&7LuckPerms: " + (politica.luckPermsPresente()
                ? "&acollegato&7, track \"&f" + config.trackStaff + "&7\" con &f"
                  + politica.quantiGruppiStaff() + "&7 gruppi"
                : "&eassente&7 (verifica obbligatoria solo per i web-admin)"));
        rispondi(sender, "&7Sessione dispositivo: &f" + config.oreSessione + "&7 ore");
        rispondi(sender, "&7Fermi al cancello adesso: &f" + quantiFermi());
    }

    private int quantiFermi() {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (gate.fermo(p)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Azzera la password di chi l'ha dimenticata e non ha la verifica in due passaggi
     * (chi ce l'ha si reimposta la password da solo sul sito, senza disturbare nessuno).
     *
     * Non ne assegna una nuova: la riga torna semplicemente senza password, e al prossimo
     * ingresso il giocatore ripassa dalla registrazione e se ne sceglie una. Cosi' non
     * esiste nessun momento in cui una password provvisoria gira per la chat o per un
     * messaggio privato.
     */
    private void reset(CommandSender sender, String nome) {
        plugin.async(() -> {
            try {
                Account account = dao.perNome(nome);
                if (account == null) {
                    rispondi(sender, "&cNessun account con questo nome.");
                    return;
                }
                dao.cambiaPassword(account.idSito, null);
                dao.revocaSessioni(account.uuid);
                rispondi(sender, "&aPassword di &f" + account.nome + "&a azzerata. "
                        + "&7Al prossimo ingresso ne sceglie una nuova.");

                Player online = Bukkit.getPlayer(account.uuid);
                if (online != null) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            online.kick(Testi.c("&eLa tua password e' stata azzerata&r\n\n"
                                    + "&7Rientra e scegline una nuova.")));
                }
            } catch (SQLException e) {
                rispondi(sender, "&cNon riesco a scrivere nel database: " + e.getMessage());
            }
        });
    }

    /**
     * Registra un giocatore al posto suo, o gli riscrive la password.
     *
     * Serve per i casi che il recupero automatico non copre: chi ha perso sia la password sia
     * il telefono con l'app, e va rimesso dentro a mano.
     *
     * Se il nome non e' mai stato visto, l'account nasce adesso — con l'UUID che il server
     * gli darebbe comunque al primo ingresso, cosi' quando entrera' si ritrovera' il suo.
     */
    private void registra(CommandSender sender, String nome, String password) {
        String no = Password.perche_no(password, nome, config.passwordMinima);
        if (no != null) {
            rispondi(sender, "&c" + no);
            return;
        }
        plugin.async(() -> {
            try {
                Account account = dao.perNome(nome);
                String impronta = Password.impronta(password);

                if (account == null) {
                    int id = dao.registra(AuthDao.uuidOffline(nome), nome, impronta, null);
                    if (id < 0) {
                        rispondi(sender, "&cNon sono riuscito a creare l'account.");
                        return;
                    }
                    rispondi(sender, "&aAccount &f" + nome + "&a creato.");
                } else {
                    dao.cambiaPassword(account.idSito, impronta);
                    dao.revocaSessioni(account.uuid);
                    rispondi(sender, "&aPassword di &f" + account.nome + "&a impostata.");
                }
                // La password gliela deve comunicare qualcuno a voce: scriverla qui in chat
                // la lascerebbe nei registri del server e sotto gli occhi di chi passa.
                rispondi(sender, "&7Comunicagliela a voce, e digli di cambiarla con "
                        + "&f/changepassword&7 appena entra.");

            } catch (SQLException e) {
                rispondi(sender, "&cNon riesco a scrivere nel database: " + e.getMessage());
            }
        });
    }

    /**
     * Toglie l'attesa a chi si e' chiuso fuori sbagliando la password (o il codice) troppe volte.
     *
     * Il blocco vive sull'INDIRIZZO di rete, non sul nome: e' cio' che impedisce di chiudere
     * fuori un altro sapendone soltanto il nick. Chi amministra pero' conosce il giocatore, e
     * il giocatore appena bloccato non e' nemmeno online per poter chiedere al server da dove
     * arriva. L'indirizzo si ritrova percio' per due strade, e si usano tutte e due:
     *
     *  - il nome annotato sull'ultimo tentativo fallito da quell'indirizzo;
     *  - gli indirizzi da cui quel giocatore era gia' entrato in passato (i dispositivi
     *    ricordati), che coprono anche i blocchi piu' vecchi dell'annotazione.
     *
     * Chi ha l'indirizzo sotto mano lo puo' passare direttamente: e' la via d'uscita quando
     * il blocco non porta nessun nome riconoscibile.
     */
    private void sblocca(CommandSender sender, String chi) {
        // Un nick di Minecraft e' fatto solo di lettere, cifre e trattini bassi: se compare
        // un punto o dei due punti, quello che ho in mano e' un indirizzo.
        if (chi.indexOf('.') >= 0 || chi.indexOf(':') >= 0) {
            plugin.async(() -> {
                try {
                    dao.azzeraTentativi(chi);
                    rispondi(sender, "&aIndirizzo &f" + chi + "&a sbloccato.");
                } catch (SQLException e) {
                    rispondi(sender, "&cNon riesco a scrivere nel database: " + e.getMessage());
                }
            });
            return;
        }

        plugin.async(() -> {
            try {
                // Prima il nome cosi' com'e' stato scritto: chi resta fuori mentre prova a
                // REGISTRARSI non ha ancora un account, e sarebbe l'unico a non poter essere
                // aiutato proprio nel momento in cui ne ha bisogno.
                Set<String> indirizzi = new LinkedHashSet<>(dao.indirizziBloccatiDi(chi));

                Account account = dao.perNome(chi);
                boolean otp = false;
                if (account != null) {
                    indirizzi.addAll(dao.indirizziBloccatiDi(account.nome));
                    indirizzi.addAll(dao.indirizziBloccatiNoti(account.uuid));
                    Player online = Bukkit.getPlayer(account.uuid);
                    if (online != null && online.getAddress() != null) {
                        indirizzi.add(online.getAddress().getAddress().getHostAddress());
                    }
                    otp = dao.sbloccaOtp(account.idSito);
                }

                for (String ip : indirizzi) {
                    dao.azzeraTentativi(ip);
                }

                String nome = account != null ? account.nome : chi;
                if (indirizzi.isEmpty() && !otp) {
                    if (account == null) {
                        rispondi(sender, "&eNessun account con questo nome e nessun blocco a suo carico.");
                    } else {
                        rispondi(sender, "&e" + nome + " non risulta bloccato: puo' gia' entrare.");
                    }
                    rispondi(sender, "&7Se il blocco resta, passami l'indirizzo: "
                            + "&f/mauth unlock <indirizzo>&7.");
                    return;
                }

                if (!indirizzi.isEmpty()) {
                    rispondi(sender, "&aSbloccato &f" + nome + "&a: "
                            + indirizzi.size() + (indirizzi.size() == 1 ? " indirizzo" : " indirizzi")
                            + " &7(" + String.join(", ", indirizzi) + "&7)");
                }
                if (otp) {
                    rispondi(sender, "&aTolto anche il blocco del codice in due passaggi.");
                }
                rispondi(sender, "&7Puo' rientrare adesso.");

            } catch (SQLException e) {
                rispondi(sender, "&cNon riesco a scrivere nel database: " + e.getMessage());
            }
        });
    }

    /** Dimentica i dispositivi: al prossimo ingresso la password torna obbligatoria ovunque. */
    private void sessioni(CommandSender sender, String nome) {
        plugin.async(() -> {
            try {
                Account account = dao.perNome(nome);
                if (account == null) {
                    rispondi(sender, "&cNessun account con questo nome.");
                    return;
                }
                dao.revocaSessioni(account.uuid);
                rispondi(sender, "&aDispositivi di &f" + account.nome + "&a dimenticati.");
            } catch (SQLException e) {
                rispondi(sender, "&cNon riesco a scrivere nel database: " + e.getMessage());
            }
        });
    }

    private void rispondi(CommandSender sender, String testo) {
        sender.sendMessage(Testi.c(config.prefisso, testo));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : List.of("info", "register", "reset", "unlock", "sessions", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
            return out;
        }
        if (args.length == 2 && List.of("reset", "unlock", "sessions", "register")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> out = new ArrayList<>();
            for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
                String nome = p.getName();
                if (nome != null && nome.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(nome);
                }
            }
            return out;
        }
        return List.of();
    }
}
