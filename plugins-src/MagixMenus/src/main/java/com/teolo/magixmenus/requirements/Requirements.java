package com.teolo.magixmenus.requirements;

import com.teolo.magixmenus.actions.Action;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * Un gruppo di condizioni, con la regola che dice quante ne servono.
 *
 * Di norma servono tutte. Con {@code minimo: 2} ne bastano due qualsiasi su quante ne sono
 * scritte: e' la differenza fra "devi avere il grado E i soldi" e "ti basta uno dei tre modi per
 * entrare", e senza questa chiave il secondo caso costringerebbe a scrivere la stessa voce tre
 * volte.
 *
 * {@code azioni_negate} sono le azioni di quando la risposta e' no. Su un {@code click_se} sono
 * quasi obbligatorie: un bottone che non fa niente e non dice niente sembra rotto, e chi gioca
 * apre una segnalazione. Su un {@code mostra_se} non hanno senso — l'item semplicemente non c'e'
 * — e infatti li' non vengono nemmeno lette.
 */
public record Requirements(List<Requirement> elenco, int minimo, List<Action> azioniNegate) {

    /** Nessuna condizione: sempre soddisfatto. E' il valore di chi non scrive niente nel file. */
    public static final Requirements NESSUNO = new Requirements(List.of(), 0, List.of());

    public boolean vuoto() {
        return elenco.isEmpty();
    }

    /** Quante condizioni servono davvero: quelle scritte, o il "minimo" se e' stato indicato. */
    public int quanteServono() {
        return minimo > 0 ? Math.min(minimo, elenco.size()) : elenco.size();
    }

    public boolean soddisfatti(Player p, Map<String, String> variabili) {
        if (elenco.isEmpty()) {
            return true;
        }
        int servono = quanteServono();
        int ok = 0;
        int mancanti = elenco.size();
        for (Requirement r : elenco) {
            mancanti--;
            if (r.soddisfatto(p, variabili)) {
                ok++;
                if (ok >= servono) {
                    return true;
                }
            } else if (ok + mancanti < servono) {
                // Non ne restano abbastanza per arrivare al minimo: inutile valutare il resto,
                // e ogni condizione in meno e' un placeholder in meno da risolvere.
                return false;
            }
        }
        return ok >= servono;
    }
}
