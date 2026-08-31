package com.teolo.magixmenus.menu;

import com.teolo.magixmenus.actions.Action;
import com.teolo.magixmenus.requirements.Requirements;

import java.util.List;
import java.util.Locale;

/**
 * La parte di un menu che vale solo se e' una finestra di dialogo.
 *
 * Un dialogo non e' un inventario travestito: e' una schermata vera di Minecraft, con un testo, dei
 * campi da riempire e dei bottoni. Niente caselle, niente item da cliccare, niente item che il
 * giocatore possa provare a portarsi via — e quindi niente di tutto il lavoro che un menu-baule
 * deve fare per impedirglielo.
 *
 * <pre>
 * menu:
 *   tipo: dialogo
 *   titolo: "Assistenza"
 *   corpo:
 *     - "Scrivi cosa non va e lo staff ti risponde."
 *   campi:
 *     testo:
 *       tipo: testo
 *       etichetta: "Il problema"
 *       lunghezza: 200
 *   bottoni:
 *     - etichetta: "&amp;aInvia"
 *       azioni:
 *         - "console: segnalazione %player_name% %campo_testo%"
 * </pre>
 *
 * Quello che il giocatore scrive nei campi arriva alle azioni come {@code %campo_<nome>%}.
 */
public record MenuDialog(List<String> corpo, List<Campo> campi, List<Bottone> bottoni,
                      boolean mettiInPausa) {

    /** Che cosa si chiede al giocatore. */
    public enum TipoCampo {
        /** Una riga (o piu') di testo libero. */
        TESTO,
        /** Una spunta: si' o no. */
        BOOLEANO,
        /** Un numero da scegliere con un cursore, fra un minimo e un massimo. */
        NUMERO,
        /** Una scelta fra valori decisi da chi ha scritto il menu. */
        SCELTA;

        /** Il nome con cui questo campo si scrive nei file. */
        public String nomeFile() {
            return switch (this) {
                case TESTO -> "text";
                case BOOLEANO -> "boolean";
                case NUMERO -> "number";
                case SCELTA -> "option";
            };
        }

        public static TipoCampo leggi(String s) {
            if (s == null) {
                return TESTO;
            }
            return switch (s.trim().toUpperCase(Locale.ROOT)) {
                case "TESTO", "TEXT", "STRINGA" -> TESTO;
                case "BOOLEANO", "BOOLEAN", "SPUNTA", "SI_NO" -> BOOLEANO;
                case "NUMERO", "NUMBER", "RANGE" -> NUMERO;
                case "SCELTA", "OPZIONE", "OPTION", "SINGLE_OPTION" -> SCELTA;
                default -> null;
            };
        }
    }

    /**
     * Un campo da riempire.
     *
     * @param chiave    il nome con cui il valore arriva alle azioni: %campo_chiave%
     * @param opzioni   solo per SCELTA: i valori possibili
     */
    public record Campo(String chiave, TipoCampo tipo, String etichetta, String iniziale,
                        float minimo, float massimo, float passo, int lunghezza, int larghezza,
                        boolean piuRighe, List<String> opzioni) {
    }

    /**
     * Un bottone.
     *
     * A differenza di un item, un bottone che non si puo' premere e' meglio non mostrarlo affatto:
     * in un dialogo non c'e' spazio per spiegare accanto, e i bottoni stanno in fila uno accanto
     * all'altro. Per questo c'e' {@code mostra_se} ma non un {@code click_se}.
     */
    public record Bottone(String etichetta, String suggerimento, int larghezza,
                          Requirements mostraSe, List<Action> azioni) {
    }
}
