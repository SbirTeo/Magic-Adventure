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
    SITE("sito", "Solo sul sito"),
    ENTRAMBI("entrambi", "Gioco e sito");

    private final String code;
    private final String label;

    Scope(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** Vale in partita? */
    public boolean tocca(boolean inGioco) {
        if (this == ENTRAMBI) {
            return true;
        }
        return inGioco ? this == GIOCO : this == SITE;
    }

    public static Scope da(String s) {
        if (s == null) {
            return ENTRAMBI;
        }
        for (Scope a : values()) {
            if (a.code.equalsIgnoreCase(s.trim())) {
                return a;
            }
        }
        return ENTRAMBI;
    }
}
