package com.teolo.magixmenus.command;

import com.teolo.magixmenus.MagixMenus;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

/**
 * Il comando che apre un menu: {@code /negozio}, {@code /spawn}, quello che c'e' scritto nel file.
 *
 * Non e' dichiarato in plugin.yml perche' i menu si aggiungono scrivendo un file, e a quel punto
 * il comando deve esistere senza riavviare il server: viene quindi registrato a mano nella mappa
 * dei comandi e tolto a ogni {@code reload}. Il prezzo e' che questi comandi non compaiono nel
 * plugin.yml — in cambio, aggiungere un menu e' davvero solo aggiungere un file.
 */
public final class MenuCommand extends Command {

    private final MagixMenus plugin;

    /**
     * Il menu che questo comando apre. NON e' finale: a ogni reload il comando viene
     * ripuntato al menu aggiornato invece di essere buttato e rifatto.
     *
     * Il motivo e' che la mappa dei comandi del server non si lascia ripulire mentre gira: su
     * Paper {@code getKnownCommands()} e' di sola lettura, e il primo tentativo di togliere le
     * vecchie voci faceva fallire l'intero {@code /menus reload}. Riusare l'oggetto gia'
     * registrato aggira il problema senza trucchi.
     *
     * A null quando il menu non esiste piu': il comando resta li' (non si puo' togliere) ma lo
     * dice, invece di aprire il vuoto.
     */
    private volatile String menu;

    public MenuCommand(MagixMenus plugin, String name, List<String> alias, String menu,
                       String permesso, String description) {
        super(name, description, "/" + name, alias);
        this.plugin = plugin;
        this.menu = menu;
        if (permesso != null && !permesso.isBlank()) {
            setPermission(permesso);
        }
    }

    /** Dopo un reload: quale menu apre adesso, e con quale permesso. */
    public void punta(String menu, String permesso) {
        this.menu = menu;
        setPermission(permesso != null && !permesso.isBlank() ? permesso : null);
    }

    public String menu() {
        return menu;
    }

    @Override
    public boolean execute(CommandSender mittente, String label, String[] arguments) {
        if (menu == null) {
            plugin.messages().send(mittente, "menu-removed", "command", label);
            return true;
        }
        if (!(mittente instanceof Player p)) {
            mittente.sendMessage(plugin.messages().get("players-only"));
            return true;
        }
        plugin.menu().openByName(p, menu, Arrays.asList(arguments), null);
        return true;
    }
}
