package com.teolo.magixmenus.dialogo;

import com.teolo.magixmenus.MagixMenus;
import com.teolo.magixmenus.azioni.Contesto;
import com.teolo.magixmenus.menu.MenuAperto;
import com.teolo.magixmenus.menu.MenuDef;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * Il {@link Contesto} di una finestra di dialogo.
 *
 * Le azioni sono le stesse degli item, ma qui due di loro non hanno un corrispettivo: le pagine
 * non esistono (un dialogo non ne ha) e "aggiorna" significa riaprire la finestra da capo, perche'
 * una schermata gia' mandata al client non si puo' ridisegnare a pezzi come un inventario.
 */
final class ContestoDialogo implements Contesto {

    private final MagixMenus plugin;
    private final Player giocatore;
    private final Map<String, String> variabili;
    private final MenuDef menu;
    private final MenuAperto provenienza;

    ContestoDialogo(MagixMenus plugin, Player giocatore, Map<String, String> variabili,
                    MenuDef menu, MenuAperto provenienza) {
        this.plugin = plugin;
        this.giocatore = giocatore;
        this.variabili = variabili;
        this.menu = menu;
        this.provenienza = provenienza;
    }

    /** Lo stesso dialogo con delle variabili in piu' (i campi appena compilati). */
    ContestoDialogo con(Map<String, String> altre) {
        return new ContestoDialogo(plugin, giocatore, altre, menu, provenienza);
    }

    MenuDef menu() {
        return menu;
    }

    @Override
    public Player giocatore() {
        return giocatore;
    }

    @Override
    public Map<String, String> variabili() {
        return variabili;
    }

    @Override
    public void chiudi() {
        giocatore.closeDialog();
    }

    @Override
    public void aggiorna() {
        plugin.dialoghi().apri(giocatore, menu, argomenti(), provenienza);
    }

    @Override
    public void pagina(String dove) {
        // Un dialogo non ha pagine: l'azione non fa niente invece di lamentarsi, cosi' lo stesso
        // gruppo di azioni si puo' riusare fra un menu a pagine e un dialogo.
    }

    @Override
    public void indietro() {
        if (provenienza == null) {
            chiudi();
            return;
        }
        giocatore.closeDialog();
        plugin.menu().apri(giocatore, provenienza.definizione(), List.of(), null);
    }

    @Override
    public void apriMenu(String nomeEArgomenti) {
        String[] pezzi = nomeEArgomenti.trim().split("\\s+");
        List<String> args = pezzi.length > 1
                ? List.of(java.util.Arrays.copyOfRange(pezzi, 1, pezzi.length))
                : List.of();
        giocatore.closeDialog();
        plugin.menu().apriPerNome(giocatore, pezzi[0], args, null);
    }

    /** Gli argomenti con cui era stato aperto, ricavati dalle variabili. */
    private List<String> argomenti() {
        List<String> out = new java.util.ArrayList<>();
        for (int i = 1; variabili.containsKey("arg_" + i); i++) {
            out.add(variabili.get("arg_" + i));
        }
        return out;
    }
}
