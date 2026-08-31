package com.teolo.magixmenus.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dove va un item dentro il menu.
 *
 * Le caselle si contano da 0, come le conta Minecraft, ma si possono scrivere in tutti i modi in
 * cui verrebbe naturale scriverle:
 *
 * <pre>
 * slot: 13                 una sola
 * slot: 2,3,4              un elenco
 * slot: 2-10               un intervallo (estremi compresi)
 * slot: "0-8,45-53"        elenco e intervalli insieme
 * slot: row:3              tutta la terza riga
 * slot: column:1           tutta la prima colonna
 * slot: border             la cornice esterna
 * slot: all                ogni casella (il riempimento di sfondo)
 *
 * I vecchi nomi italiani (riga:, colonna:, bordo, tutte) restano validi.
 * </pre>
 *
 * Righe e colonne si contano da 1 perche' chi scrive un menu pensa "la terza riga", non "la riga
 * di indice 2"; le caselle restano da 0 perche' quelle si confrontano con i numeri che si leggono
 * ovunque altrove.
 *
 * Fuori misura si scarta e basta: un menu di tre righe con un item alla casella 40 mostra le due
 * righe buone invece di non aprirsi. L'errore lo raccoglie il caricatore, che lo scrive nel log.
 */
public final class Slot {

    private Slot() {
    }

    /**
     * Legge un valore di configurazione (numero, testo o elenco) e ne ricava le caselle.
     *
     * @param valore     quello che c'era scritto sotto "slot"
     * @param larghezza  quante caselle per riga ha questo tipo di menu (9 nel baule, 5 nella tramoggia)
     * @param dimensione quante caselle ha in tutto
     * @param errori     dove finiscono i pezzi che non si sono capiti (mai null)
     */
    public static List<Integer> leggi(Object valore, int larghezza, int dimensione, List<String> errori) {
        Set<Integer> caselle = new LinkedHashSet<>();
        aggiungi(valore, larghezza, dimensione, caselle, errori);
        return new ArrayList<>(caselle);
    }

    private static void aggiungi(Object valore, int larghezza, int dimensione,
                                 Set<Integer> caselle, List<String> errori) {
        if (valore == null) {
            return;
        }
        if (valore instanceof Iterable<?> elenco) {
            for (Object o : elenco) {
                aggiungi(o, larghezza, dimensione, caselle, errori);
            }
            return;
        }
        if (valore instanceof Number n) {
            metti(n.intValue(), dimensione, caselle, errori, String.valueOf(n));
            return;
        }
        for (String pezzo : String.valueOf(valore).split(",")) {
            pezzo(pezzo.trim(), larghezza, dimensione, caselle, errori);
        }
    }

    private static void pezzo(String p, int larghezza, int dimensione,
                              Set<Integer> caselle, List<String> errori) {
        if (p.isEmpty()) {
            return;
        }
        if (larghezza <= 0 || dimensione <= 0) {
            // Un menu senza caselle: le finestre di dialogo. Chi ci scrive dentro degli item si
            // merita una spiegazione, non una divisione per zero.
            errori.add("questo tipo di menu non ha caselle: la chiave slot non ha senso qui");
            return;
        }
        String basso = p.toLowerCase(Locale.ROOT);

        if (basso.equals("all") || basso.equals("tutte") || basso.equals("*")) {
            for (int i = 0; i < dimensione; i++) {
                caselle.add(i);
            }
            return;
        }
        if (basso.equals("border") || basso.equals("bordo")) {
            int righe = (int) Math.ceil(dimensione / (double) larghezza);
            for (int i = 0; i < dimensione; i++) {
                int riga = i / larghezza, colonna = i % larghezza;
                if (riga == 0 || riga == righe - 1 || colonna == 0 || colonna == larghezza - 1) {
                    caselle.add(i);
                }
            }
            return;
        }
        if (basso.startsWith("row:") || basso.startsWith("riga:")) {
            int riga = intero(basso.substring(basso.indexOf(':') + 1), errori, p);
            if (riga < 1) {
                return;
            }
            for (int c = 0; c < larghezza; c++) {
                metti((riga - 1) * larghezza + c, dimensione, caselle, null, p);
            }
            return;
        }
        if (basso.startsWith("column:") || basso.startsWith("colonna:")) {
            int colonna = intero(basso.substring(basso.indexOf(':') + 1), errori, p);
            if (colonna < 1 || colonna > larghezza) {
                if (colonna >= 1) {
                    errori.add("colonna " + colonna + " fuori dal menu (ce ne sono " + larghezza + ")");
                }
                return;
            }
            for (int i = colonna - 1; i < dimensione; i += larghezza) {
                caselle.add(i);
            }
            return;
        }

        int trattino = basso.indexOf('-', 1);   // da 1: un eventuale meno iniziale non e' un intervallo
        if (trattino > 0) {
            int da = intero(basso.substring(0, trattino), errori, p);
            int a = intero(basso.substring(trattino + 1), errori, p);
            if (da < 0 || a < 0) {
                return;
            }
            if (da > a) {
                int t = da;
                da = a;
                a = t;
            }
            for (int i = da; i <= a; i++) {
                metti(i, dimensione, caselle, null, p);
            }
            return;
        }

        metti(intero(basso, errori, p), dimensione, caselle, errori, p);
    }

    private static void metti(int casella, int dimensione, Set<Integer> caselle,
                              List<String> errori, String scritto) {
        if (casella < 0) {
            return;
        }
        if (casella >= dimensione) {
            if (errori != null) {
                errori.add("casella " + scritto + " fuori dal menu (ne ha " + dimensione + ", da 0 a "
                        + (dimensione - 1) + ")");
            }
            return;
        }
        caselle.add(casella);
    }

    private static int intero(String s, List<String> errori, String scritto) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            if (errori != null) {
                errori.add("non ho capito la casella '" + scritto + "'");
            }
            return -1;
        }
    }
}
