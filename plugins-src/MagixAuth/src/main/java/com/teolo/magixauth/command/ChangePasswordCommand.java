package com.teolo.magixauth.command;

import com.teolo.magixauth.AuthConfig;
import com.teolo.magixauth.MagixAuth;
import com.teolo.magixauth.crypt.OtpCodes;
import com.teolo.magixauth.crypt.Password;
import com.teolo.magixauth.db.AuthDao;
import com.teolo.magixauth.gate.AuthGate;
import com.teolo.magixauth.model.Account;
import com.teolo.magixauth.util.Texts;
import com.teolo.magixauth.util.DurationText;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;

/**
 * /changepassword &lt;vecchia&gt; &lt;nuova&gt; &lt;conferma&gt; [codice]
 *
 * La password di adesso viene chiesta e non e' una formalita': senza, chiunque passasse
 * davanti al computer di un altro lasciato collegato potrebbe riscrivergli la password e
 * prendersi l'account, sul server e sul sito insieme.
 *
 * Il codice serve solo a chi ha la verifica in due passaggi attiva, ed e' l'ultimo argomento
 * perche' per tutti gli altri non deve nemmeno esistere.
 */
public final class ChangePasswordCommand implements CommandExecutor {

    private final MagixAuth plugin;
    private final AuthConfig config;
    private final AuthDao dao;
    private final AuthGate gate;

    public ChangePasswordCommand(MagixAuth plugin, AuthConfig config, AuthDao dao, AuthGate gate) {
        this.plugin = plugin;
        this.config = config;
        this.dao = dao;
        this.gate = gate;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Solo un giocatore puo' cambiare la propria password.");
            return true;
        }
        // Non dovrebbe passare di qui — al cancello i comandi sono filtrati — ma un
        // controllo in piu' su chi non ha ancora dimostrato di essere se stesso non guasta.
        if (gate.isFrozen(p)) {
            return true;
        }
        if (args.length < 3 || args.length > 4) {
            p.sendMessage(Texts.c(config.prefix,
                    "&7Uso: &f/changepassword <vecchia> <nuova> <ripeti nuova> [codice]"));
            return true;
        }

        String vecchia = args[0];
        String nuova = args[1];
        String conferma = args[2];
        String code = args.length == 4 ? args[3] : null;

        if (!nuova.equals(conferma)) {
            p.sendMessage(Texts.c(config.prefix, Texts.NON_COINCIDONO));
            return true;
        }
        String no = Password.whyNot(nuova, p.getName(), config.minPasswordLength);
        if (no != null) {
            p.sendMessage(Texts.c(config.prefix, "&c" + no));
            return true;
        }
        if (nuova.equals(vecchia)) {
            p.sendMessage(Texts.c(config.prefix,
                    "&cLa password nuova deve essere diversa da quella di adesso."));
            return true;
        }

        // Il confronto bcrypt costa qualche centinaio di millisecondi: farlo qui fermerebbe
        // il server a ogni tentativo.
        plugin.async(() -> change(p, vecchia, nuova, code));
        return true;
    }

    private void change(Player p, String vecchia, String nuova, String code) {
        try {
            Account account = dao.byUuid(p.getUniqueId());
            if (account == null || !account.registered()) {
                message(p, "&cNon risulti registrato.");
                return;
            }
            if (!Password.matchesHash(vecchia, account.passwordHash)) {
                message(p, "&cLa password attuale non e' corretta.");
                return;
            }

            // Chi ha il secondo fattore lo usa anche qui: e' il momento in cui un account
            // rubato verrebbe chiuso per sempre al legittimo proprietario.
            if (account.haOtp()) {
                if (code == null) {
                    message(p, "&7Hai la verifica in due passaggi: aggiungi il codice in fondo.\n"
                            + "&7Uso: &f/changepassword <vecchia> <nuova> <ripeti> <codice>");
                    return;
                }
                if (account.otpLocked()) {
                    message(p, Texts.otpLocked(DurationText.until(account.totpLockedUntil)));
                    return;
                }
                String secret = OtpCodes.decryptSecret(account.totpSecretCifrato, config.otpKeyBase64);
                long step = secret == null ? -1
                        : OtpCodes.checkPassword(OtpCodes.base32Decode(secret), code, account.totpLastStep);
                if (step < 0) {
                    dao.otpFailed(account.siteId, config.maxAttempts, config.lockoutMinutes);
                    message(p, Texts.OTP_NO);
                    return;
                }
                dao.otpStepSpent(account.siteId, step);
            }

            dao.changePassword(account.siteId, Password.fingerprint(nuova));
            // Tutti gli altri dispositivi ripassano dalla password: era il senso del cambio.
            dao.revokeSessions(p.getUniqueId());
            message(p, "&aPassword cambiata.&r &7Vale anche su &fmagicadventure.it&7.");

        } catch (SQLException e) {
            plugin.getLogger().warning("MagixAuth: cambio password di " + p.getName()
                    + " fallito (" + e.getMessage() + ").");
            message(p, "&cNon sono riuscito a salvare la password nuova. Riprova.");
        }
    }

    private void message(Player p, String text) {
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                p.sendMessage(Texts.c(config.prefix, text));
            }
        });
    }
}
