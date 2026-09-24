package com.teolo.magixauth.command;

import com.teolo.magixauth.AuthConfig;
import com.teolo.magixauth.gate.AuthGate;
import com.teolo.magixauth.gate.EntryState;
import com.teolo.magixauth.lang.Messages;
import com.teolo.magixauth.util.Texts;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /otp &lt;codice&gt; — il secondo fattore, per chi lo ha attivo.
 *
 * Ha preso il posto del tastierino a caselle: quello era piu' bello da vedere, ma restava
 * aperto dopo la verifica e i suoi tasti si potevano prendere e portare via. Sei cifre in
 * chat le sa scrivere chiunque, e non c'e' niente che possa rompersi.
 */
public final class OtpCommand implements CommandExecutor {

    private final AuthConfig config;
    private final AuthGate gate;
    private final Messages messages;

    public OtpCommand(AuthConfig config, AuthGate gate, Messages messages) {
        this.config = config;
        this.gate = gate;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(messages.get("otp-command.players-only"));
            return true;
        }
        EntryState state = gate.state(p);
        if (state == null || !state.awaitsCode()) {
            p.sendMessage(Texts.c(messages.get(p, "otp-command.not-requested")));
            return true;
        }
        if (args.length != 1) {
            p.sendMessage(Texts.c(messages.get(p, "otp-command.usage")));
            return true;
        }
        gate.tryCode(p, args[0]);
        return true;
    }
}
