package com.teolo.magixguard.sanctions;

/**
 * I quattro provvedimenti possibili. I nomi in minuscolo sono quelli scritti nel database
 * e letti dal sito: non vanno cambiati senza cambiare anche la tabella `sanzioni`.
 */
public enum Type {

    /** Un avviso: non impedisce niente, ma resta agli atti e porta punti. */
    WARN("warn", "Richiamo"),
    /** Non puo' scrivere in chat. */
    MUTE("mute", "Silenziato"),
    /** Buttato fuori adesso; puo' rientrare subito. */
    KICK("kick", "Espulso"),
    /** Non puo' entrare (e, secondo l'ambito, nemmeno usare il sito). */
    BAN("ban", "Bandito");

    private final String code;
    private final String label;

    Type(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** Come si chiama nel database e sul sito. */
    public String code() {
        return code;
    }

    /** Come si dice a un essere umano. */
    public String label() {
        return label;
    }

    /** Ha una durata? Warn e kick no: valgono nell'istante in cui vengono dati. */
    public boolean hasDuration() {
        return this == MUTE || this == BAN;
    }

    public static Type da(String s) {
        if (s == null) {
            return null;
        }
        for (Type t : values()) {
            if (t.code.equalsIgnoreCase(s.trim())) {
                return t;
            }
        }
        return null;
    }
}
