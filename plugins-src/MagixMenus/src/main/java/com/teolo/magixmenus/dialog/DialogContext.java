package com.teolo.magixmenus.dialog;

import com.teolo.magixmenus.MagixMenus;
import com.teolo.magixmenus.actions.Context;
import com.teolo.magixmenus.menu.OpenMenu;
import com.teolo.magixmenus.menu.MenuDef;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * Il {@link Context} di una finestra di dialogo.
 *
 * Le azioni sono le stesse degli item, ma qui due di loro non hanno un corrispettivo: le pagine
 * non esistono (un dialogo non ne ha) e "aggiorna" significa riaprire la finestra da capo, perche'
 * una schermata gia' mandata al client non si puo' ridisegnare a pezzi come un inventario.
 */
final class DialogContext implements Context {

    private final MagixMenus plugin;
    private final Player player;
    private final Map<String, String> variabili;
    private final MenuDef menu;
    private final OpenMenu provenienza;

    DialogContext(MagixMenus plugin, Player player, Map<String, String> variabili,
                    MenuDef menu, OpenMenu provenienza) {
        this.plugin = plugin;
        this.player = player;
        this.variabili = variabili;
        this.menu = menu;
        this.provenienza = provenienza;
    }

    /** Lo stesso dialogo con delle variabili in piu' (i campi appena compilati). */
    DialogContext con(Map<String, String> altre) {
        return new DialogContext(plugin, player, altre, menu, provenienza);
    }

    MenuDef menu() {
        return menu;
    }

    @Override
    public Player player() {
        return player;
    }

    @Override
    public Map<String, String> variabili() {
        return variabili;
    }

    @Override
    public void close() {
        player.closeDialog();
    }

    @Override
    public void refresh() {
        plugin.dialogs().open(player, menu, arguments(), provenienza);
    }

    @Override
    public void page(String where) {
        // Un dialogo non ha pagine: l'azione non fa niente invece di lamentarsi, cosi' lo stesso
        // gruppo di azioni si puo' riusare fra un menu a pagine e un dialogo.
    }

    @Override
    public void back() {
        if (provenienza == null) {
            close();
            return;
        }
        player.closeDialog();
        plugin.menu().open(player, provenienza.definizione(), List.of(), null);
    }

    @Override
    public void openMenu(String nameAndArgs) {
        String[] pieces = nameAndArgs.trim().split("\\s+");
        List<String> args = pieces.length > 1
                ? List.of(java.util.Arrays.copyOfRange(pieces, 1, pieces.length))
                : List.of();
        player.closeDialog();
        plugin.menu().openByName(player, pieces[0], args, null);
    }

    /** Gli argomenti con cui era stato aperto, ricavati dalle variabili. */
    private List<String> arguments() {
        List<String> out = new java.util.ArrayList<>();
        for (int i = 1; variabili.containsKey("arg_" + i); i++) {
            out.add(variabili.get("arg_" + i));
        }
        return out;
    }
}
