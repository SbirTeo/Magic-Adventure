package com.teolo.magixauth.command;

import com.teolo.magixauth.AuthConfig;
import com.teolo.magixauth.gate.AuthGate;
import com.teolo.magixauth.gate.EntryState;
import com.teolo.magixauth.lang.Messages;
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
    private final Messages messages;

    public RegisterCommand(AuthConfig config, AuthGate gate, Messages messages) {
        this.config = config;
        this.gate = gate;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(messages.get("register-command.players-only"));
            return true;
        }
        EntryState state = gate.state(p);
        if (state == null) {
            p.sendMessage(Texts.c(config.prefix, messages.get(p, "register-command.already-in")));
            return true;
        }
        if (state.phase != Phase.REGISTRAZIONE) {
            p.sendMessage(Texts.c(config.prefix, messages.get(p, "register-command.already-registered")));
            return true;
        }
        if (args.length != 2) {
            p.sendMessage(Texts.c(config.prefix, messages.get(p, "register-command.usage")));
            return true;
        }
        if (!args[0].equals(args[1])) {
            p.sendMessage(Texts.c(config.prefix, messages.get(p, "gate.password-mismatch")));
            return true;
        }
        gate.register(p, args[0]);
        return true;
    }
}
