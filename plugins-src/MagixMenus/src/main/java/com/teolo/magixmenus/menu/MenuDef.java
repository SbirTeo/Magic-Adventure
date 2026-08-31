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

    private final String nome;
    private final MenuType tipo;
    private final int righe;
    private final String titolo;
    private final int aggiornamentoTick;
    private final List<String> comandi;
    private final String permesso;
    private final List<String> argomenti;
    private final Requirements apriSe;
    private final List<Action> azioniApertura;
    private final List<Action> azioniChiusura;
    private final List<ItemDef> item;
    private final Content contenuto;
    private final MenuDialog dialogo;
    private final boolean chiusuraLibera;
    private final List<String> errori;

    MenuDef(String nome, MenuType tipo, int righe, String titolo, int aggiornamentoTick,
            List<String> comandi, String permesso, List<String> argomenti, Requirements apriSe,
            List<Action> azioniApertura, List<Action> azioniChiusura, List<ItemDef> item,
            Content contenuto, MenuDialog dialogo, boolean chiusuraLibera, List<String> errori) {
        this.nome = nome;
        this.tipo = tipo;
        this.righe = righe;
        this.titolo = titolo;
        this.aggiornamentoTick = aggiornamentoTick;
        this.comandi = List.copyOf(comandi);
        this.permesso = permesso;
        this.argomenti = List.copyOf(argomenti);
        this.apriSe = apriSe;
        this.azioniApertura = List.copyOf(azioniApertura);
        this.azioniChiusura = List.copyOf(azioniChiusura);
        this.item = List.copyOf(item);
        this.contenuto = contenuto;
        this.dialogo = dialogo;
        this.chiusuraLibera = chiusuraLibera;
        this.errori = List.copyOf(errori);
    }

    public String nome() {
        return nome;
    }

    public MenuType tipo() {
        return tipo;
    }

    public int righe() {
        return righe;
    }

    public String titolo() {
        return titolo;
    }

    /** Ogni quanti tick si ridisegna. 0 = mai, si disegna solo all'apertura. */
    public int aggiornamentoTick() {
        return aggiornamentoTick;
    }

    public List<String> comandi() {
        return comandi;
    }

    public String permesso() {
        return permesso;
    }

    /** I nomi degli argomenti del comando: /negozio &lt;categoria&gt; diventa %arg_categoria% e %arg_1%. */
    public List<String> argomenti() {
        return argomenti;
    }

    public Requirements apriSe() {
        return apriSe;
    }

    public List<Action> azioniApertura() {
        return azioniApertura;
    }

    public List<Action> azioniChiusura() {
        return azioniChiusura;
    }

    public List<ItemDef> item() {
        return item;
    }

    public Content contenuto() {
        return contenuto;
    }

    /** La parte da finestra di dialogo: c'e' solo se il tipo e' "dialogo". */
    public MenuDialog dialogo() {
        return dialogo;
    }

    /** Si puo' chiudere con Esc? A falso il menu si riapre da solo: da usare con parsimonia. */
    public boolean chiusuraLibera() {
        return chiusuraLibera;
    }

    public List<String> errori() {
        return errori;
    }

    public int dimensione() {
        return tipo.dimensione(righe);
    }

    /** C'e' qualcosa che cambia da solo, o e' un menu fermo? */
    public boolean dinamico() {
        if (contenuto != null) {
            return true;
        }
        for (ItemDef i : item) {
            if (i.dinamico()) {
                return true;
            }
        }
        return com.teolo.magixmenus.util.Text.dinamico(titolo);
    }

    /** Gli item che possono finire in questa casella, nell'ordine di priorita' (il file). */
    public List<ItemDef> candidatiPer(int casella) {
        List<ItemDef> out = new ArrayList<>(2);
        for (ItemDef i : item) {
            if (i.caselle().contains(casella)) {
                out.add(i);
            }
        }
        return out;
    }
}
