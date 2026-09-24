package com.teolo.magixauth.command;

import com.teolo.magixauth.AuthConfig;
import com.teolo.magixauth.MagixAuth;
import com.teolo.magixauth.crypt.Password;
import com.teolo.magixauth.db.AuthDao;
import com.teolo.magixauth.gate.AuthGate;
import com.teolo.magixauth.gate.OtpPolicy;
import com.teolo.magixauth.lang.Messages;
import com.teolo.magixauth.model.Account;
import com.teolo.magixauth.util.Help;
import com.teolo.magixauth.util.Texts;
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
    private final OtpPolicy policy;
    private final AuthGate gate;
    private final Messages messages;

    public MauthCommand(MagixAuth plugin, AuthConfig config, AuthDao dao,
                        OtpPolicy policy, AuthGate gate, Messages messages) {
        this.plugin = plugin;
        this.config = config;
        this.dao = dao;
        this.policy = policy;
        this.gate = gate;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender, 1);
            return true;
        }
        // Il numero da solo sfoglia le pagine: e' quello che mandano le frecce.
        if (args[0].matches("\\d+")) {
            help(sender, Integer.parseInt(args[0]));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reload();
                reply(sender, messages.get(sender, "admin.reload-done"));
            }
            case "info" -> info(sender);
            case "setspawn" -> {
                if (!sender.hasPermission("magixauth.admin")) {
                    reply(sender, messages.get(sender, "admin.no-permission.setspawn"));
                    return true;
                }
                setspawn(sender);
            }
            case "reset" -> {
                if (!sender.hasPermission("magixauth.reset")) {
                    reply(sender, messages.get(sender, "admin.no-permission.reset"));
                    return true;
                }
                if (args.length < 2) {
                    reply(sender, messages.get(sender, "admin.usage.reset"));
                    return true;
                }
                reset(sender, args[1]);
            }
            case "register" -> {
                if (!sender.hasPermission("magixauth.reset")) {
                    reply(sender, messages.get(sender, "admin.no-permission.register"));
                    return true;
                }
                if (args.length < 3) {
                    reply(sender, messages.get(sender, "admin.usage.register"));
                    return true;
                }
                register(sender, args[1], args[2]);
            }
            case "unlock" -> {
                if (!sender.hasPermission("magixauth.unlock")) {
                    reply(sender, messages.get(sender, "admin.no-permission.unlock"));
                    return true;
                }
                if (args.length < 2) {
                    reply(sender, messages.get(sender, "admin.usage.unlock"));
                    return true;
                }
                unlock(sender, args[1]);
            }
            case "sessions" -> {
                if (args.length < 2) {
                    reply(sender, messages.get(sender, "admin.usage.sessions"));
                    return true;
                }
                sessions(sender, args[1]);
            }
            default -> help(sender, 1);
        }
        return true;
    }

    /**
     * L'elenco dei comandi.
     *
     * Sta tutto in una lista invece che in una sfilza di messaggi perche' cosi' si puo'
     * impaginare, filtrare per permesso e rendere cliccabile senza riscriverlo ogni volta.
     */
    private void help(CommandSender sender, int page) {
        org.bukkit.configuration.ConfigurationSection h = messages.section("help");
        String title = h != null ? h.getString("title", "MagixAuth") : "MagixAuth";
        Help.show(sender, messages::get, title, "/mauth help",
                Help.fromConfig(messages.section("help.sections"), sender, messages::get, messages::getList),
                page, sender.hasPermission("magixauth.admin"));
    }

    private void info(CommandSender sender) {
        reply(sender, messages.get(sender, "admin.info.shared-account"));
        reply(sender, messages.get(sender, "admin.info.otp-key", "stato",
                messages.get(sender, config.otpKeyReady() ? "admin.info.otp-ready" : "admin.info.otp-missing")));
        String luckPerms = policy.luckPermsPresent()
                ? messages.get(sender, "admin.info.luckperms-connected",
                        "track", config.staffTrack, "numero", String.valueOf(policy.staffGroupCount()))
                : messages.get(sender, "admin.info.luckperms-absent");
        reply(sender, messages.get(sender, "admin.info.luckperms", "stato", luckPerms));
        reply(sender, messages.get(sender, "admin.info.session-hours", "ore", String.valueOf(config.sessionHours)));
        reply(sender, messages.get(sender, "admin.info.frozen", "numero", String.valueOf(frozenCount())));
    }

    private int frozenCount() {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (gate.isFrozen(p)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Fissa il cancello di login dove si trova chi da' il comando, sguardo compreso.
     *
     * Serve quando lo spawn "vero" del server e' quello di CMI, che non e' lo stesso di
     * /setworldspawn: ci si mette li', si guarda nella direzione giusta, e da quel momento
     * chi deve autenticarsi compare esattamente in quel punto invece che sullo spawn del
     * mondo. Va dato in gioco: da console non c'e' una posizione da leggere.
     */
    private void setspawn(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            reply(sender, messages.get(sender, "admin.setspawn.console-only"));
            return;
        }
        org.bukkit.Location l = p.getLocation();
        gate.setLoginSpawn(l);
        reply(sender, messages.get(sender, "admin.setspawn.done",
                "mondo", l.getWorld().getName(),
                "x", String.valueOf(Math.round(l.getX())),
                "y", String.valueOf(Math.round(l.getY())),
                "z", String.valueOf(Math.round(l.getZ()))));
        reply(sender, messages.get(sender, "admin.setspawn.hint"));
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
    private void reset(CommandSender sender, String name) {
        plugin.async(() -> {
            try {
                Account account = dao.byName(name);
                if (account == null) {
                    reply(sender, messages.get(sender, "admin.no-account"));
                    return;
                }
                dao.changePassword(account.siteId, null);
                dao.revokeSessions(account.uuid);
                reply(sender, messages.get(sender, "admin.reset.done", "nome", account.name));

                Player online = Bukkit.getPlayer(account.uuid);
                if (online != null) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            online.kick(Texts.c(messages.get(online, "admin.password-reset-kick"))));
                }
            } catch (SQLException e) {
                reply(sender, messages.get(sender, "admin.db-error", "errore", e.getMessage()));
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
    private void register(CommandSender sender, String name, String password) {
        Password.Rejection no = Password.whyNot(password, name, config.minPasswordLength);
        if (no != null) {
            reply(sender, "&c" + messages.get(sender, no.key(), no.kv()));
            return;
        }
        plugin.async(() -> {
            try {
                Account account = dao.byName(name);
                String fingerprint = Password.fingerprint(password);

                if (account == null) {
                    int id = dao.register(AuthDao.uuidOffline(name), name, fingerprint, null);
                    if (id < 0) {
                        reply(sender, messages.get(sender, "admin.register.create-failed"));
                        return;
                    }
                    reply(sender, messages.get(sender, "admin.register.created", "nome", name));
                } else {
                    dao.changePassword(account.siteId, fingerprint);
                    dao.revokeSessions(account.uuid);
                    reply(sender, messages.get(sender, "admin.register.password-set", "nome", account.name));
                }
                // La password gliela deve comunicare qualcuno a voce: scriverla qui in chat
                // la lascerebbe nei registri del server e sotto gli occhi di chi passa.
                reply(sender, messages.get(sender, "admin.register.tell-them"));

            } catch (SQLException e) {
                reply(sender, messages.get(sender, "admin.db-error", "errore", e.getMessage()));
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
    private void unlock(CommandSender sender, String chi) {
        // Un nick di Minecraft e' fatto solo di lettere, cifre e trattini bassi: se compare
        // un punto o dei due punti, quello che ho in mano e' un indirizzo.
        if (chi.indexOf('.') >= 0 || chi.indexOf(':') >= 0) {
            plugin.async(() -> {
                try {
                    dao.resetAttempts(chi);
                    reply(sender, messages.get(sender, "admin.unlock.address-done", "indirizzo", chi));
                } catch (SQLException e) {
                    reply(sender, messages.get(sender, "admin.db-error", "errore", e.getMessage()));
                }
            });
            return;
        }

        plugin.async(() -> {
            try {
                // Prima il nome cosi' com'e' stato scritto: chi resta fuori mentre prova a
                // REGISTRARSI non ha ancora un account, e sarebbe l'unico a non poter essere
                // aiutato proprio nel momento in cui ne ha bisogno.
                Set<String> addresses = new LinkedHashSet<>(dao.blockedAddressesOf(chi));

                Account account = dao.byName(chi);
                boolean otp = false;
                if (account != null) {
                    addresses.addAll(dao.blockedAddressesOf(account.name));
                    addresses.addAll(dao.knownBlockedAddresses(account.uuid));
                    Player online = Bukkit.getPlayer(account.uuid);
                    if (online != null && online.getAddress() != null) {
                        addresses.add(online.getAddress().getAddress().getHostAddress());
                    }
                    otp = dao.unlockOtp(account.siteId);
                }

                for (String ip : addresses) {
                    dao.resetAttempts(ip);
                }

                String name = account != null ? account.name : chi;
                if (addresses.isEmpty() && !otp) {
                    if (account == null) {
                        reply(sender, messages.get(sender, "admin.unlock.no-account-no-block"));
                    } else {
                        reply(sender, messages.get(sender, "admin.unlock.not-blocked", "nome", name));
                    }
                    reply(sender, messages.get(sender, "admin.unlock.hint-address"));
                    return;
                }

                if (!addresses.isEmpty()) {
                    reply(sender, messages.get(sender, addresses.size() == 1 ? "admin.unlock.done-one" : "admin.unlock.done-many",
                            "nome", name, "numero", String.valueOf(addresses.size()), "elenco", String.join(", ", addresses)));
                }
                if (otp) {
                    reply(sender, messages.get(sender, "admin.unlock.otp-cleared"));
                }
                reply(sender, messages.get(sender, "admin.unlock.can-return"));

            } catch (SQLException e) {
                reply(sender, messages.get(sender, "admin.db-error", "errore", e.getMessage()));
            }
        });
    }

    /** Dimentica i dispositivi: al prossimo ingresso la password torna obbligatoria ovunque. */
    private void sessions(CommandSender sender, String name) {
        plugin.async(() -> {
            try {
                Account account = dao.byName(name);
                if (account == null) {
                    reply(sender, messages.get(sender, "admin.no-account"));
                    return;
                }
                dao.revokeSessions(account.uuid);
                reply(sender, messages.get(sender, "admin.sessions.forgotten", "nome", account.name));
            } catch (SQLException e) {
                reply(sender, messages.get(sender, "admin.db-error", "errore", e.getMessage()));
            }
        });
    }

    private void reply(CommandSender sender, String text) {
        sender.sendMessage(Texts.c(config.prefix, text));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : List.of("info", "setspawn", "register", "reset", "unlock", "sessions", "reload")) {
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
                String name = p.getName();
                if (name != null && name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(name);
                }
            }
            return out;
        }
        return List.of();
    }
}
