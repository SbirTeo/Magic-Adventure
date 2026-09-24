package com.teolo.magixauth.command;

import com.teolo.magixauth.AuthConfig;
import com.teolo.magixauth.gate.AuthGate;
import com.teolo.magixauth.lang.Messages;
import com.teolo.magixauth.model.Phase;
import com.teolo.magixauth.gate.EntryState;
import com.teolo.magixauth.util.Texts;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /login <password> — l'ingresso di chi ha gia' un account.
 *
 * Nota su cosa si perde scrivendo la password in un comando: finisce in `logs/latest.log`,
 * e resta nella cronologia del client (freccia in su). Non passa invece dalla chat pubblica
 * ne' dal ponte con la chat del sito, perche' i comandi non transitano di li'.
 */
public final class LoginCommand implements CommandExecutor {

    private final AuthConfig config;
    private final AuthGate gate;
    private final Messages messages;

    public LoginCommand(AuthConfig config, AuthGate gate, Messages messages) {
        this.config = config;
        this.gate = gate;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(messages.get("login-command.players-only"));
            return true;
        }
        EntryState state = gate.state(p);
        if (state == null) {
            p.sendMessage(Texts.c(messages.get(p, "login-command.already-in")));
            return true;
        }
        if (state.phase == Phase.REGISTRAZIONE) {
            p.sendMessage(Texts.c(messages.get(p, "login-command.no-account")));
            return true;
        }
        if (state.phase == Phase.OTP) {
            p.sendMessage(Texts.c(messages.get(p, "login-command.needs-otp")));
            return true;
        }
        if (args.length != 1) {
            p.sendMessage(Texts.c(messages.get(p, "login-command.usage")));
            p.sendMessage(Texts.c(messages.get(p, "login-command.all-commands")));
            return true;
        }
        gate.tryPassword(p, args[0]);
        return true;
    }
}
