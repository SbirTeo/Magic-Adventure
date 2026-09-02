package com.teolo.magixmenus.actions;

import com.teolo.magixmenus.requirements.Requirements;

import java.util.List;
import java.util.Locale;

/**
 * Una cosa che succede: un comando, un messaggio, un suono, un altro menu che si apre.
 *
 * Nel file si scrivono come righe {@code "tipo: quello che serve"}, in ordine, e in
 * quell'ordine vengono eseguite:
 *
 * <pre>
 * azioni:
 *   - "messaggio: &amp;aTi teletrasporto..."
 *   - "suono: ENTITY_ENDERMAN_TELEPORT"
 *   - "attesa: 20"
 *   - "console: tp %player_name% 0 100 0"
 *   - "chiudi"
 * </pre>
 *
 * Una riga puo' anche essere un blocco con una condizione, per i casi in cui l'esito dipende da
 * qualcosa che si sa solo al momento del clic:
 *
 * <pre>
 *   - se: "%vault_eco_balance% &gt;= 500"
 *     allora:
 *       - "togli_soldi: 500"
 *       - "console: give %player_name% diamond 1"
 *     altrimenti:
 *       - "messaggio: &amp;cTi mancano %vault_eco_balance% monete"
 * </pre>
 *
 * <h2>Perche' "attesa" e' un'azione e non un campo</h2>
 * Sarebbe stato possibile dare a ogni azione il suo ritardo. Ma quello che si vuole quasi sempre
 * e' "aspetta qui, poi continua", e con i ritardi per azione bisogna ricalcolarli tutti ogni volta
 * che se ne aggiunge una in mezzo. Cosi' invece l'ordine di lettura e' l'ordine dei fatti, che e'
 * anche l'unico modo in cui l'editor sul sito puo' mostrarli come un elenco che si trascina.
 */
public record Action(Type type, String argomento, Requirements condizione,
                     List<Action> allora, List<Action> altrimenti) {

    /** Action semplice: tipo e argomento, senza condizioni. */
    public static Action di(Type type, String argomento) {
        return new Action(type, argomento, Requirements.NESSUNO, List.of(), List.of());
    }

    public enum Type {
        /** Il comando viene eseguito DAL GIOCATORE, con i suoi permessi. */
        COMMAND(true),
        /** Il comando viene eseguito dalla console: serve per quello che il giocatore non potrebbe fare. */
        CONSOLE(true),
        /**
         * Il comando viene eseguito dal giocatore con l'op acceso per il tempo di quel comando.
         * Da usare il meno possibile: quasi sempre CONSOLE fa la stessa cosa senza dare niente a
         * nessuno. Si puo' spegnere del tutto dal config.
         */
        COMMAND_OP(true),
        /** Un messaggio in chat solo a chi ha cliccato. */
        MESSAGE(true),
        /** Un messaggio in chat a tutto il server. */
        ANNUNCIO(true),
        /** Titolo a schermo: {@code "titolo: Grande|piccolo"}. */
        TITLE(true),
        /** La riga sopra la barra degli oggetti. */
        ACTIONBAR(true),
        /** Un suono: {@code "suono: BLOCK_NOTE_BLOCK_PLING"}, o {@code "...|volume|tono"}. */
        SUONO(true),
        /** Apre un altro menu: {@code "menu: negozio"}, con eventuali argomenti dopo il nome. */
        OPEN_MENU(true),
        /** Torna al menu da cui si e' arrivati. */
        BACK(false),
        /** Chiude il menu. */
        CLOSE(false),
        /** Ridisegna subito il menu, senza aspettare il prossimo aggiornamento. */
        REFRESH(false),
        /** Cambia pagina: {@code "pagina: avanti"}, {@code "pagina: indietro"}, {@code "pagina: 3"}. */
        PAGE(true),
        /** Aggiunge monete (serve Vault). */
        DAI_SOLDI(true),
        /** Toglie monete (serve Vault). Se non bastano, l'azione fallisce e la catena si ferma. */
        TAKE_MONEY(true),
        /** Ferma la catena per tanti tick, poi riprende da dove era. */
        ATTESA(true),
        /** Blocco condizionale: vedi l'esempio in cima. */
        SE(false);

        private final boolean vuoleArgomento;

        Type(boolean vuoleArgomento) {
            this.vuoleArgomento = vuoleArgomento;
        }

        public boolean vuoleArgomento() {
            return vuoleArgomento;
        }

        /** Il nome con cui questa azione si scrive nei file (inglese, come tutte le chiavi). */
        public String fileName() {
            return switch (this) {
                case COMMAND -> "command";
                case CONSOLE -> "console";
                case COMMAND_OP -> "op_command";
                case MESSAGE -> "message";
                case ANNUNCIO -> "broadcast";
                case TITLE -> "title";
                case ACTIONBAR -> "actionbar";
                case SUONO -> "sound";
                case OPEN_MENU -> "menu";
                case BACK -> "back";
                case CLOSE -> "close";
                case REFRESH -> "refresh";
                case PAGE -> "page";
                case DAI_SOLDI -> "give_money";
                case TAKE_MONEY -> "take_money";
                case ATTESA -> "wait";
                case SE -> "if";
            };
        }

        /** Il nome scritto nel file: quello inglese, o il vecchio nome italiano. */
        public static Type read(String s) {
            if (s == null) {
                return null;
            }
            String n = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            return switch (n) {
                case "COMANDO", "COMMAND", "GIOCATORE", "PLAYER", "PLAYER_COMMAND" -> COMMAND;
                case "CONSOLE", "CONSOLE_COMMAND" -> CONSOLE;
                case "COMANDO_OP", "OP_COMMAND", "OP", "PLAYER_COMMAND_OP" -> COMMAND_OP;
                case "MESSAGGIO", "MSG", "MESSAGE", "CHAT" -> MESSAGE;
                case "ANNUNCIO", "BROADCAST" -> ANNUNCIO;
                case "TITOLO", "TITLE" -> TITLE;
                case "ACTIONBAR", "BARRA" -> ACTIONBAR;
                case "SUONO", "SOUND" -> SUONO;
                case "APRI_MENU", "MENU", "APRI", "OPEN_MENU", "OPEN" -> OPEN_MENU;
                case "INDIETRO", "BACK" -> BACK;
                case "CHIUDI", "CLOSE" -> CLOSE;
                case "AGGIORNA", "REFRESH", "UPDATE" -> REFRESH;
                case "PAGINA", "PAGE" -> PAGE;
                case "DAI_SOLDI", "GIVE_MONEY", "DEPOSIT" -> DAI_SOLDI;
                case "TOGLI_SOLDI", "TAKE_MONEY", "WITHDRAW" -> TAKE_MONEY;
                case "ATTESA", "ASPETTA", "DELAY", "WAIT" -> ATTESA;
                default -> null;
            };
        }
    }

    /** Elenco dei tipi scrivibili in un file, per i messaggi d'errore e per l'editor sul sito. */
    public static List<String> availableTypes() {
        List<String> out = new java.util.ArrayList<>();
        for (Type t : Type.values()) {
            if (t != Type.SE) {
                out.add(t.fileName());   // il blocco "if" non si sceglie da una tendina: ha una forma sua
            }
        }
        return out;
    }
}
