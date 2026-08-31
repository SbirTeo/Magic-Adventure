package com.teolo.magixguard.sanzioni;

/**
 * I quattro provvedimenti possibili. I nomi in minuscolo sono quelli scritti nel database
 * e letti dal sito: non vanno cambiati senza cambiare anche la tabella `sanzioni`.
 */
public enum Tipo {

    /** Un avviso: non impedisce niente, ma resta agli atti e porta punti. */
    WARN("warn", "Richiamo"),
    /** Non puo' scrivere in chat. */
    MUTE("mute", "Silenziato"),
    /** Buttato fuori adesso; puo' rientrare subito. */
    KICK("kick", "Espulso"),
    /** Non puo' entrare (e, secondo l'ambito, nemmeno usare il sito). */
    BAN("ban", "Bandito");

    private final String codice;
    private final String etichetta;

    Tipo(String codice, String etichetta) {
        this.codice = codice;
        this.etichetta = etichetta;
    }

    /** Come si chiama nel database e sul sito. */
    public String codice() {
        return codice;
    }

    /** Come si dice a un essere umano. */
    public String etichetta() {
        return etichetta;
    }

    /** Ha una durata? Warn e kick no: valgono nell'istante in cui vengono dati. */
    public boolean haDurata() {
        return this == MUTE || this == BAN;
    }

    public static Tipo da(String s) {
        if (s == null) {
            return null;
        }
        for (Tipo t : values()) {
            if (t.codice.equalsIgnoreCase(s.trim())) {
                return t;
            }
        }
        return null;
    }
}
