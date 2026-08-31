package com.teolo.magixmenus.actions;

import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Quello che un'azione puo' toccare mentre viene eseguita.
 *
 * Le azioni non conoscono i menu: sanno che c'e' un giocatore, delle variabili da sostituire nel
 * testo, e qualcuno a cui chiedere "chiudi", "cambia pagina", "torna indietro". Chi risponde e'
 * di solito il menu aperto, ma non sempre: le azioni di apertura partono quando il menu non c'e'
 * ancora, e quelle di chiusura quando non c'e' piu'.
 *
 * Grazie a questa separazione un'azione scritta per un item funziona identica dentro un dialogo,
 * dove di inventari non ce n'e' nemmeno uno.
 */
public interface Context {

    Player giocatore();

    /** Le variabili del menu: %pagina%, %arg_1%, %voce_nome%... */
    Map<String, String> variabili();

    /** Chiude quello che e' aperto. Fuori da un menu non fa niente. */
    void chiudi();

    /** Ridisegna subito. */
    void aggiorna();

    /** "avanti", "indietro" o un numero di pagina. */
    void pagina(String dove);

    /** Torna al menu precedente, se se ne ricorda uno. */
    void indietro();

    /** Apre un altro menu: nome ed eventuali argomenti separati da spazio. */
    void apriMenu(String nomeEArgomenti);
}
