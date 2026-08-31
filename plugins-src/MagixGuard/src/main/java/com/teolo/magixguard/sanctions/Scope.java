package com.teolo.magixguard.sanctions;

/**
 * Dove vale un provvedimento.
 *
 * <p>Il gioco fa valere {@link #GIOCO} ed {@link #ENTRAMBI}; il sito fa valere {@link #SITO}
 * ed {@link #ENTRAMBI}. Un ban di solo gioco non chiude il sito — ed e' voluto: il ricorso si
 * fa da li'.</p>
 */
public enum Scope {

    GIOCO("gioco", "Solo in gioco"),
    SITO("sito", "Solo sul sito"),
    ENTRAMBI("entrambi", "Gioco e sito");

    private final String codice;
    private final String etichetta;

    Scope(String codice, String etichetta) {
        this.codice = codice;
        this.etichetta = etichetta;
    }

    public String codice() {
        return codice;
    }

    public String etichetta() {
        return etichetta;
    }

    /** Vale in partita? */
    public boolean tocca(boolean inGioco) {
        if (this == ENTRAMBI) {
            return true;
        }
        return inGioco ? this == GIOCO : this == SITO;
    }

    public static Scope da(String s) {
        if (s == null) {
            return ENTRAMBI;
        }
        for (Scope a : values()) {
            if (a.codice.equalsIgnoreCase(s.trim())) {
                return a;
            }
        }
        return ENTRAMBI;
    }
}
