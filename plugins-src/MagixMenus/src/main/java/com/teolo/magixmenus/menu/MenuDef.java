package com.teolo.magixmenus.menu;

import com.teolo.magixmenus.actions.Action;
import com.teolo.magixmenus.requirements.Requirements;

import java.util.ArrayList;
import java.util.List;

/**
 * Un menu come sta scritto nel suo file, gia' letto e controllato.
 *
 * E' immutabile e non sa niente di chi lo guarda: un solo MenuDef serve tutti i giocatori che
 * hanno quel menu aperto, e quello che cambia da persona a persona vive in {@link OpenMenu}.
 * Cosi' un {@code reload} sostituisce la definizione senza toccare chi sta guardando, e cento
 * giocatori sullo stesso menu non sono cento copie della configurazione.
 *
 * <h2>Gli errori</h2>
 * Un file con degli sbagli produce lo stesso un MenuDef, con gli errori raccolti in
 * {@link #errori()}. Un menu che non si apre perche' una lettera e' storta e' peggio di un menu
 * con un buco: il buco si vede e si aggiusta, il menu che non si apre manda a cercare nel log.
 */
public final class MenuDef {

    private final String name;
    private final MenuType type;
    private final int rows;
    private final String title;
    private final int aggiornamentoTick;
    private final List<String> commands;
    private final String permesso;
    private final List<String> arguments;
    private final Requirements openIf;
    private final List<Action> openActions;
    private final List<Action> closeActions;
    private final List<ItemDef> item;
    private final Content content;
    private final MenuDialog dialog;
    private final boolean freeClose;
    private final List<String> errori;

    MenuDef(String name, MenuType type, int rows, String title, int aggiornamentoTick,
            List<String> commands, String permesso, List<String> arguments, Requirements openIf,
            List<Action> openActions, List<Action> closeActions, List<ItemDef> item,
            Content content, MenuDialog dialog, boolean freeClose, List<String> errori) {
        this.name = name;
        this.type = type;
        this.rows = rows;
        this.title = title;
        this.aggiornamentoTick = aggiornamentoTick;
        this.commands = List.copyOf(commands);
        this.permesso = permesso;
        this.arguments = List.copyOf(arguments);
        this.openIf = openIf;
        this.openActions = List.copyOf(openActions);
        this.closeActions = List.copyOf(closeActions);
        this.item = List.copyOf(item);
        this.content = content;
        this.dialog = dialog;
        this.freeClose = freeClose;
        this.errori = List.copyOf(errori);
    }

    public String name() {
        return name;
    }

    public MenuType type() {
        return type;
    }

    public int rows() {
        return rows;
    }

    public String title() {
        return title;
    }

    /** Ogni quanti tick si ridisegna. 0 = mai, si disegna solo all'apertura. */
    public int aggiornamentoTick() {
        return aggiornamentoTick;
    }

    public List<String> commands() {
        return commands;
    }

    public String permesso() {
        return permesso;
    }

    /** I nomi degli argomenti del comando: /negozio &lt;categoria&gt; diventa %arg_categoria% e %arg_1%. */
    public List<String> arguments() {
        return arguments;
    }

    public Requirements openIf() {
        return openIf;
    }

    public List<Action> openActions() {
        return openActions;
    }

    public List<Action> closeActions() {
        return closeActions;
    }

    public List<ItemDef> item() {
        return item;
    }

    public Content content() {
        return content;
    }

    /** La parte da finestra di dialogo: c'e' solo se il tipo e' "dialogo". */
    public MenuDialog dialog() {
        return dialog;
    }

    /** Si puo' chiudere con Esc? A falso il menu si riapre da solo: da usare con parsimonia. */
    public boolean freeClose() {
        return freeClose;
    }

    public List<String> errori() {
        return errori;
    }

    public int dimensione() {
        return type.dimensione(rows);
    }

    /** C'e' qualcosa che cambia da solo, o e' un menu fermo? */
    public boolean dinamico() {
        if (content != null) {
            return true;
        }
        for (ItemDef i : item) {
            if (i.dinamico()) {
                return true;
            }
        }
        return com.teolo.magixmenus.util.Text.dinamico(title);
    }

    /** Gli item che possono finire in questa casella, nell'ordine di priorita' (il file). */
    public List<ItemDef> candidatiPer(int slot) {
        List<ItemDef> out = new ArrayList<>(2);
        for (ItemDef i : item) {
            if (i.slots().contains(slot)) {
                out.add(i);
            }
        }
        return out;
    }
}
