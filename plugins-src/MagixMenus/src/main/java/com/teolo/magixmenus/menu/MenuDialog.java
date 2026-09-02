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
public record MenuDialog(List<String> body, List<Field> fields, List<Bottone> bottoni,
                      boolean pauseUpdates) {

    /** Che cosa si chiede al giocatore. */
    public enum FieldType {
        /** Una riga (o piu') di testo libero. */
        TEXT,
        /** Una spunta: si' o no. */
        BOOLEANO,
        /** Un numero da scegliere con un cursore, fra un minimo e un massimo. */
        NUMERO,
        /** Una scelta fra valori decisi da chi ha scritto il menu. */
        CHOICE;

        /** Il nome con cui questo campo si scrive nei file. */
        public String fileName() {
            return switch (this) {
                case TEXT -> "text";
                case BOOLEANO -> "boolean";
                case NUMERO -> "number";
                case CHOICE -> "option";
            };
        }

        public static FieldType read(String s) {
            if (s == null) {
                return TEXT;
            }
            return switch (s.trim().toUpperCase(Locale.ROOT)) {
                case "TESTO", "TEXT", "STRINGA" -> TEXT;
                case "BOOLEANO", "BOOLEAN", "SPUNTA", "SI_NO" -> BOOLEANO;
                case "NUMERO", "NUMBER", "RANGE" -> NUMERO;
                case "SCELTA", "OPZIONE", "OPTION", "SINGLE_OPTION" -> CHOICE;
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
    public record Field(String key, FieldType type, String label, String iniziale,
                        float minimum, float maximum, float step, int lunghezza, int larghezza,
                        boolean multiLine, List<String> opzioni) {
    }

    /**
     * Un bottone.
     *
     * A differenza di un item, un bottone che non si puo' premere e' meglio non mostrarlo affatto:
     * in un dialogo non c'e' spazio per spiegare accanto, e i bottoni stanno in fila uno accanto
     * all'altro. Per questo c'e' {@code mostra_se} ma non un {@code click_se}.
     */
    public record Bottone(String label, String suggestion, int larghezza,
                          Requirements showIf, List<Action> actions) {
    }
}
