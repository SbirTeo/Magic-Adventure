package com.teolo.magixmenus.menu;

import org.bukkit.event.inventory.ClickType;

import java.util.Locale;

/**
 * In quanti modi si puo' cliccare un item, e quale gruppo di azioni tocca a ciascuno.
 *
 * Le azioni sotto {@code azioni:} valgono per qualunque clic; quelle sotto {@code azioni_destro:}
 * solo per il tasto destro, e cosi' via. Un clic fa scattare PRIMA le azioni del suo tasto e POI
 * quelle generiche, cosi' le cose comuni a tutti i tasti (il suono, la chiusura) si scrivono una
 * volta sola invece di ripeterle in ogni gruppo.
 */
public enum Clic {

    QUALSIASI("actions", "azioni"),
    SINISTRO("left_click_actions", "azioni_sinistro"),
    DESTRO("right_click_actions", "azioni_destro"),
    SHIFT_SINISTRO("shift_left_click_actions", "azioni_shift_sinistro"),
    SHIFT_DESTRO("shift_right_click_actions", "azioni_shift_destro"),
    CENTRALE("middle_click_actions", "azioni_centrale"),
    /** I tasti da 1 a 9, quelli che nell'inventario scambiano l'item con la barra rapida. */
    NUMERO("number_key_actions", "azioni_numero"),
    DOPPIO("double_click_actions", "azioni_doppio");

    private final String chiaveAzioni;
    private final String chiaveAzioniIt;

    Clic(String chiaveAzioni, String chiaveAzioniIt) {
        this.chiaveAzioni = chiaveAzioni;
        this.chiaveAzioniIt = chiaveAzioniIt;
    }

    /** La chiave delle azioni nel file: "right_click_actions". */
    public String chiaveAzioni() {
        return chiaveAzioni;
    }

    /**
     * Il vecchio nome italiano della stessa chiave ("azioni_destro").
     *
     * Le chiavi dei file si scrivono in inglese (vedi la regola comune ai plugin Magix), ma i
     * menu gia' scritti in italiano devono continuare ad aprirsi: il nome vecchio resta valido
     * come sinonimo e basta.
     */
    public String chiaveAzioniItaliana() {
        return chiaveAzioniIt;
    }

    /** La chiave dei requisiti nel file: "right_click_requirements". */
    public String chiaveRequisiti() {
        return this == QUALSIASI ? "click_requirements"
                : chiaveAzioni.substring(0, chiaveAzioni.length() - "actions".length()) + "requirements";
    }

    /** Il vecchio nome italiano dei requisiti ("click_se_destro"). */
    public String chiaveRequisitiItaliana() {
        return this == QUALSIASI ? "click_se" : "click_se" + chiaveAzioniIt.substring("azioni".length());
    }

    /** Il modo in cui Minecraft dice che e' stato cliccato, tradotto nel nostro. */
    public static Clic da(ClickType t) {
        return switch (t) {
            case LEFT -> SINISTRO;
            case RIGHT -> DESTRO;
            case SHIFT_LEFT -> SHIFT_SINISTRO;
            case SHIFT_RIGHT -> SHIFT_DESTRO;
            case MIDDLE, CREATIVE -> CENTRALE;
            case NUMBER_KEY, SWAP_OFFHAND -> NUMERO;
            case DOUBLE_CLICK -> DOPPIO;
            default -> QUALSIASI;
        };
    }

    /** Il nome scritto nel file, per i messaggi d'errore. */
    public static Clic leggi(String s) {
        if (s == null) {
            return null;
        }
        String n = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (n) {
            case "QUALSIASI", "ANY", "TUTTI" -> QUALSIASI;
            case "SINISTRO", "LEFT" -> SINISTRO;
            case "DESTRO", "RIGHT" -> DESTRO;
            case "SHIFT_SINISTRO", "SHIFT_LEFT" -> SHIFT_SINISTRO;
            case "SHIFT_DESTRO", "SHIFT_RIGHT" -> SHIFT_DESTRO;
            case "CENTRALE", "MIDDLE" -> CENTRALE;
            case "NUMERO", "NUMBER_KEY" -> NUMERO;
            case "DOPPIO", "DOUBLE_CLICK" -> DOPPIO;
            default -> null;
        };
    }
}
