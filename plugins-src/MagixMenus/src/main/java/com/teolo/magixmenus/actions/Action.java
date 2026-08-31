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
public record Action(Tipo tipo, String argomento, Requirements condizione,
                     List<Action> allora, List<Action> altrimenti) {

    /** Action semplice: tipo e argomento, senza condizioni. */
    public static Action di(Tipo tipo, String argomento) {
        return new Action(tipo, argomento, Requirements.NESSUNO, List.of(), List.of());
    }

    public enum Tipo {
        /** Il comando viene eseguito DAL GIOCATORE, con i suoi permessi. */
        COMANDO(true),
        /** Il comando viene eseguito dalla console: serve per quello che il giocatore non potrebbe fare. */
        CONSOLE(true),
        /**
         * Il comando viene eseguito dal giocatore con l'op acceso per il tempo di quel comando.
         * Da usare il meno possibile: quasi sempre CONSOLE fa la stessa cosa senza dare niente a
         * nessuno. Si puo' spegnere del tutto dal config.
         */
        COMANDO_OP(true),
        /** Un messaggio in chat solo a chi ha cliccato. */
        MESSAGGIO(true),
        /** Un messaggio in chat a tutto il server. */
        ANNUNCIO(true),
        /** Titolo a schermo: {@code "titolo: Grande|piccolo"}. */
        TITOLO(true),
        /** La riga sopra la barra degli oggetti. */
        ACTIONBAR(true),
        /** Un suono: {@code "suono: BLOCK_NOTE_BLOCK_PLING"}, o {@code "...|volume|tono"}. */
        SUONO(true),
        /** Apre un altro menu: {@code "menu: negozio"}, con eventuali argomenti dopo il nome. */
        APRI_MENU(true),
        /** Torna al menu da cui si e' arrivati. */
        INDIETRO(false),
        /** Chiude il menu. */
        CHIUDI(false),
        /** Ridisegna subito il menu, senza aspettare il prossimo aggiornamento. */
        AGGIORNA(false),
        /** Cambia pagina: {@code "pagina: avanti"}, {@code "pagina: indietro"}, {@code "pagina: 3"}. */
        PAGINA(true),
        /** Aggiunge monete (serve Vault). */
        DAI_SOLDI(true),
        /** Toglie monete (serve Vault). Se non bastano, l'azione fallisce e la catena si ferma. */
        TOGLI_SOLDI(true),
        /** Ferma la catena per tanti tick, poi riprende da dove era. */
        ATTESA(true),
        /** Blocco condizionale: vedi l'esempio in cima. */
        SE(false);

        private final boolean vuoleArgomento;

        Tipo(boolean vuoleArgomento) {
            this.vuoleArgomento = vuoleArgomento;
        }

        public boolean vuoleArgomento() {
            return vuoleArgomento;
        }

        /** Il nome con cui questa azione si scrive nei file (inglese, come tutte le chiavi). */
        public String nomeFile() {
            return switch (this) {
                case COMANDO -> "command";
                case CONSOLE -> "console";
                case COMANDO_OP -> "op_command";
                case MESSAGGIO -> "message";
                case ANNUNCIO -> "broadcast";
                case TITOLO -> "title";
                case ACTIONBAR -> "actionbar";
                case SUONO -> "sound";
                case APRI_MENU -> "menu";
                case INDIETRO -> "back";
                case CHIUDI -> "close";
                case AGGIORNA -> "refresh";
                case PAGINA -> "page";
                case DAI_SOLDI -> "give_money";
                case TOGLI_SOLDI -> "take_money";
                case ATTESA -> "wait";
                case SE -> "if";
            };
        }

        /** Il nome scritto nel file: quello inglese, o il vecchio nome italiano. */
        public static Tipo leggi(String s) {
            if (s == null) {
                return null;
            }
            String n = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            return switch (n) {
                case "COMANDO", "COMMAND", "GIOCATORE", "PLAYER", "PLAYER_COMMAND" -> COMANDO;
                case "CONSOLE", "CONSOLE_COMMAND" -> CONSOLE;
                case "COMANDO_OP", "OP_COMMAND", "OP", "PLAYER_COMMAND_OP" -> COMANDO_OP;
                case "MESSAGGIO", "MSG", "MESSAGE", "CHAT" -> MESSAGGIO;
                case "ANNUNCIO", "BROADCAST" -> ANNUNCIO;
                case "TITOLO", "TITLE" -> TITOLO;
                case "ACTIONBAR", "BARRA" -> ACTIONBAR;
                case "SUONO", "SOUND" -> SUONO;
                case "APRI_MENU", "MENU", "APRI", "OPEN_MENU", "OPEN" -> APRI_MENU;
                case "INDIETRO", "BACK" -> INDIETRO;
                case "CHIUDI", "CLOSE" -> CHIUDI;
                case "AGGIORNA", "REFRESH", "UPDATE" -> AGGIORNA;
                case "PAGINA", "PAGE" -> PAGINA;
                case "DAI_SOLDI", "GIVE_MONEY", "DEPOSIT" -> DAI_SOLDI;
                case "TOGLI_SOLDI", "TAKE_MONEY", "WITHDRAW" -> TOGLI_SOLDI;
                case "ATTESA", "ASPETTA", "DELAY", "WAIT" -> ATTESA;
                default -> null;
            };
        }
    }

    /** Elenco dei tipi scrivibili in un file, per i messaggi d'errore e per l'editor sul sito. */
    public static List<String> tipiDisponibili() {
        List<String> out = new java.util.ArrayList<>();
        for (Tipo t : Tipo.values()) {
            if (t != Tipo.SE) {
                out.add(t.nomeFile());   // il blocco "if" non si sceglie da una tendina: ha una forma sua
            }
        }
        return out;
    }
}
