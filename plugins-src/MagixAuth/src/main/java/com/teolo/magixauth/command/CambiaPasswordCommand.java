package com.teolo.magixauth.command;

import com.teolo.magixauth.AuthConfig;
import com.teolo.magixauth.MagixAuth;
import com.teolo.magixauth.crypt.OtpCodici;
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
public final class CambiaPasswordCommand implements CommandExecutor {

    private final MagixAuth plugin;
    private final AuthConfig config;
    private final AuthDao dao;
    private final AuthGate gate;

    public CambiaPasswordCommand(MagixAuth plugin, AuthConfig config, AuthDao dao, AuthGate gate) {
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
        if (gate.fermo(p)) {
            return true;
        }
        if (args.length < 3 || args.length > 4) {
            p.sendMessage(Texts.c(config.prefisso,
                    "&7Uso: &f/changepassword <vecchia> <nuova> <ripeti nuova> [codice]"));
            return true;
        }

        String vecchia = args[0];
        String nuova = args[1];
        String conferma = args[2];
        String codice = args.length == 4 ? args[3] : null;

        if (!nuova.equals(conferma)) {
            p.sendMessage(Texts.c(config.prefisso, Texts.NON_COINCIDONO));
            return true;
        }
        String no = Password.perche_no(nuova, p.getName(), config.passwordMinima);
        if (no != null) {
            p.sendMessage(Texts.c(config.prefisso, "&c" + no));
            return true;
        }
        if (nuova.equals(vecchia)) {
            p.sendMessage(Texts.c(config.prefisso,
                    "&cLa password nuova deve essere diversa da quella di adesso."));
            return true;
        }

        // Il confronto bcrypt costa qualche centinaio di millisecondi: farlo qui fermerebbe
        // il server a ogni tentativo.
        plugin.async(() -> cambia(p, vecchia, nuova, codice));
        return true;
    }

    private void cambia(Player p, String vecchia, String nuova, String codice) {
        try {
            Account account = dao.perUuid(p.getUniqueId());
            if (account == null || !account.registrato()) {
                messaggio(p, "&cNon risulti registrato.");
                return;
            }
            if (!Password.corrisponde(vecchia, account.passwordHash)) {
                messaggio(p, "&cLa password attuale non e' corretta.");
                return;
            }

            // Chi ha il secondo fattore lo usa anche qui: e' il momento in cui un account
            // rubato verrebbe chiuso per sempre al legittimo proprietario.
            if (account.haOtp()) {
                if (codice == null) {
                    messaggio(p, "&7Hai la verifica in due passaggi: aggiungi il codice in fondo.\n"
                            + "&7Uso: &f/changepassword <vecchia> <nuova> <ripeti> <codice>");
                    return;
                }
                if (account.otpBloccato()) {
                    messaggio(p, Texts.otpBloccato(DurationText.finoA(account.totpBloccatoFino)));
                    return;
                }
                String segreto = OtpCodici.decifraSegreto(account.totpSecretCifrato, config.chiaveOtpBase64);
                long passo = segreto == null ? -1
                        : OtpCodici.verifica(OtpCodici.base32Decode(segreto), codice, account.totpUltimoPasso);
                if (passo < 0) {
                    dao.otpFallito(account.idSito, config.tentativiMassimi, config.bloccoMinuti);
                    messaggio(p, Texts.OTP_NO);
                    return;
                }
                dao.otpPassoSpeso(account.idSito, passo);
            }

            dao.cambiaPassword(account.idSito, Password.impronta(nuova));
            // Tutti gli altri dispositivi ripassano dalla password: era il senso del cambio.
            dao.revocaSessioni(p.getUniqueId());
            messaggio(p, "&aPassword cambiata.&r &7Vale anche su &fmagicadventure.it&7.");

        } catch (SQLException e) {
            plugin.getLogger().warning("MagixAuth: cambio password di " + p.getName()
                    + " fallito (" + e.getMessage() + ").");
            messaggio(p, "&cNon sono riuscito a salvare la password nuova. Riprova.");
        }
    }

    private void messaggio(Player p, String testo) {
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                p.sendMessage(Texts.c(config.prefisso, testo));
            }
        });
    }
}
