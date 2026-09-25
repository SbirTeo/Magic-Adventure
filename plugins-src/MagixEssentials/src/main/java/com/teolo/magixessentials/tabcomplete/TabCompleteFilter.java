package com.teolo.magixessentials.tabcomplete;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Iterator;

/**
 * Toglie dall'elenco dei comandi che il client tiene per l'autocompletamento (il TAB dopo aver
 * scritto "/") quelli per cui il giocatore non ha il permesso.
 *
 * <p>Il server compila quell'elenco da TUTTI i comandi registrati, non solo da quelli che il
 * giocatore puo' davvero usare: senza questo filtro, digitando "/" chiunque vede — e puo'
 * completare col TAB — anche i comandi di staff di ogni plugin del server. Il permesso VERO
 * (l'esecuzione vera e propria) resta comunque negato: qui si toglie solo il suggerimento, non
 * si aggiunge ne' si toglie nessun controllo di accesso.</p>
 *
 * <p>{@link PlayerCommandSendEvent} e' l'evento apposta: si scatena ogni volta che il server sta
 * per mandare al client quell'elenco (al login e a ogni cambio dei comandi registrati), e la sua
 * collezione di nomi si puo' modificare sul posto.</p>
 */
public final class TabCompleteFilter implements Listener {

    private final JavaPlugin plugin;
    private boolean registrato;

    public TabCompleteFilter(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registrato = true;
    }

    public void stop() {
        if (!registrato) return;
        PlayerCommandSendEvent.getHandlerList().unregister(this);
        registrato = false;
    }

    @EventHandler
    public void onCommandSend(PlayerCommandSendEvent e) {
        Player player = e.getPlayer();
        CommandMap mappa = Bukkit.getCommandMap();
        Iterator<String> it = e.getCommands().iterator();
        while (it.hasNext()) {
            // Un nome che la mappa non conosce non si tocca: senza sapere di che comando si
            // tratta non si puo' dire se il permesso manchi davvero, e nasconderlo a caso
            // farebbe piu' danno di un suggerimento di troppo.
            Command comando = mappa.getCommand(it.next());
            if (comando != null && !comando.testPermissionSilent(player)) {
                it.remove();
            }
        }
    }
}
