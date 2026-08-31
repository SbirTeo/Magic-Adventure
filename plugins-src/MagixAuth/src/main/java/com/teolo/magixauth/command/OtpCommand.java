package com.teolo.magixauth.command;

import com.teolo.magixauth.AuthConfig;
import com.teolo.magixauth.gate.AuthGate;
import com.teolo.magixauth.gate.StatoIngresso;
import com.teolo.magixauth.util.Testi;
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

    public OtpCommand(AuthConfig config, AuthGate gate) {
        this.config = config;
        this.gate = gate;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Solo un giocatore puo' verificarsi.");
            return true;
        }
        StatoIngresso stato = gate.stato(p);
        if (stato == null || !stato.aspettaCodice()) {
            p.sendMessage(Testi.c(config.prefisso, "&7Non ti sta venendo chiesto nessun codice."));
            return true;
        }
        if (args.length != 1) {
            p.sendMessage(Testi.c(config.prefisso, "&7Uso: &f/otp <codice a sei cifre>"));
            return true;
        }
        gate.provaCodice(p, args[0]);
        return true;
    }
}
