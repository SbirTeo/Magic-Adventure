package com.teolo.magixauth.command;

import com.teolo.magixauth.AuthConfig;
import com.teolo.magixauth.MagixAuth;
import com.teolo.magixauth.db.AuthDao;
import com.teolo.magixauth.gate.AuthGate;
import com.teolo.magixauth.model.Account;
import com.teolo.magixauth.util.Texts;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;

/**
 * /logout — chiude l'accesso dappertutto.
 *
 * Serve per una situazione precisa: si e' giocato dal computer di qualcun altro. Siccome
 * l'accesso viene ricordato per ventiquattr'ore in base all'INDIRIZZO di rete, uscire e
 * basta lascerebbe li' una porta aperta — chiunque da quello stesso indirizzo potrebbe
 * entrare col nostro nome senza sapere la password. Questo comando la chiude.
 *
 * Chiude tre cose insieme, che e' il punto:
 *  - l'accesso ricordato in gioco: al prossimo ingresso si ridigita la password;
 *  - la sessione sul sito, compreso il "resta collegato" di eventuali browser;
 *  - la verifica in due passaggi gia' superata: tornera' a chiedere il codice.
 *
 * Quello che NON fa, di proposito: disattivare la verifica in due passaggi sull'account.
 * Sarebbe la prima cosa che farebbe chi ha rubato un account, e la si toglie solo dal sito,
 * dove per arrivarci bisogna gia' aver passato il codice.
 */
public final class LogoutCommand implements CommandExecutor {

    private final MagixAuth plugin;
    private final AuthConfig config;
    private final AuthDao dao;
    private final AuthGate gate;

    public LogoutCommand(MagixAuth plugin, AuthConfig config, AuthDao dao, AuthGate gate) {
        this.plugin = plugin;
        this.config = config;
        this.dao = dao;
        this.gate = gate;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Solo un giocatore puo' chiudere il proprio accesso.");
            return true;
        }
        // Chi e' ancora al cancello non ha niente da chiudere: non e' mai entrato.
        if (gate.fermo(p)) {
            return true;
        }
        // Nessun argomento e nessuna conferma: chi lo scrive sa cosa vuole, e l'unica
        // conseguenza e' dover ridigitare la password. Una richiesta di conferma qui
        // servirebbe solo a rallentare chi ha fretta di chiudere un accesso altrui.
        plugin.async(() -> chiudi(p));
        return true;
    }

    private void chiudi(Player p) {
        try {
            Account account = dao.perUuid(p.getUniqueId());

            // In gioco: via l'accesso ricordato per questo indirizzo e per tutti gli altri.
            dao.revocaSessioni(p.getUniqueId());

            // Sul sito: `session_epoch` fa cadere le sessioni aperte, e i "resta collegato"
            // vanno buttati a parte perche' sopravvivrebbero alla chiusura del browser.
            if (account != null) {
                dao.chiudiSessioniSito(account.idSito);
            }

            plugin.getLogger().info("MagixAuth: " + p.getName() + " ha chiuso l'accesso ovunque.");

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    p.kick(Texts.c("&aAccesso chiuso&r\n\n"
                            + "&7Sei stato disconnesso dal gioco e dal sito.\n"
                            + "&7Al prossimo ingresso ti verranno chiesti di nuovo\n"
                            + "&7la password" + (account != null && account.haOtp()
                            ? " e il codice di verifica." : ".")));
                }
            });

        } catch (SQLException e) {
            plugin.getLogger().warning("MagixAuth: /logout di " + p.getName()
                    + " fallito (" + e.getMessage() + ").");
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    p.sendMessage(Texts.c(config.prefisso,
                            "&cNon sono riuscito a chiudere l'accesso adesso. Riprova."));
                }
            });
        }
    }
}
