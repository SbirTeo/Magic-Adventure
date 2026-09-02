package com.teolo.magixauth.command;

import com.teolo.magixauth.AuthConfig;
import com.teolo.magixauth.gate.AuthGate;
import com.teolo.magixauth.gate.EntryState;
import com.teolo.magixauth.model.Phase;
import com.teolo.magixauth.util.Texts;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /register &lt;password&gt; &lt;conferma&gt; — il primo ingresso.
 *
 * La conferma si chiede subito, nello stesso comando: una password scelta male e digitata una
 * volta sola diventa un giocatore chiuso fuori dal proprio account il giorno dopo, e uno
 * scambio di messaggi con lo staff per rimetterlo dentro.
 */
public final class RegisterCommand implements CommandExecutor {

    private final AuthConfig config;
    private final AuthGate gate;

    public RegisterCommand(AuthConfig config, AuthGate gate) {
        this.config = config;
        this.gate = gate;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Solo un giocatore puo' registrarsi.");
            return true;
        }
        EntryState state = gate.state(p);
        if (state == null) {
            p.sendMessage(Texts.c(config.prefix, "&7Sei gia' dentro."));
            return true;
        }
        if (state.phase != Phase.REGISTRAZIONE) {
            p.sendMessage(Texts.c(config.prefix,
                    "&7Hai gia' un account: usa &f/login <password>&7."));
            return true;
        }
        if (args.length != 2) {
            p.sendMessage(Texts.c(config.prefix, "&7Uso: &f/register <password> <ripeti password>"));
            return true;
        }
        if (!args[0].equals(args[1])) {
            p.sendMessage(Texts.c(config.prefix, Texts.NON_COINCIDONO));
            return true;
        }
        gate.register(p, args[0]);
        return true;
    }
}
