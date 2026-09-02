package com.teolo.magixauth.util;

import java.util.Locale;

/**
 * Numeri e durate scritti come li leggerebbe un giocatore italiano ("10 minuti", "1 ora", "48 ore").
 * <p>
 * Sta qui e non dentro un singolo comando perche' gli stessi testi servono in due posti che devono
 * dire la STESSA cosa: le righe di {@code /f power} in chat e i valori sostituiti nel tutorial HTML
 * (vedi {@code MagixFactions.writeTutorial}). Due formattatori separati sarebbero due occasioni di
 * divergere.
 */
public final class DurationText {

    private DurationText() {}

    /** Numero senza decimali inutili: 2.0 -&gt; "2", 1.5 -&gt; "1,5" (virgola, non punto). */
    public static String number(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.ITALIAN, "%.1f", v);
    }

    /** Durata da SECONDI: "45 secondi", "1 minuto", "10 minuti", "1,5 minuti". */
    public static String fromSeconds(long secondi) {
        if (secondi < 60) return secondi + (secondi == 1 ? " secondo" : " secondi");
        double minuti = secondi / 60.0;
        String n = number(minuti);
        return n + (n.equals("1") ? " minuto" : " minuti");
    }

    /**
     * Quanto manca a un momento futuro: "40 secondi", "8 minuti".
     *
     * Mai "0 secondi": se il momento e' gia' passato chi chiama non avrebbe nemmeno
     * dovuto arrivare qui, ma un arrotondamento sfortunato non deve far leggere
     * "riprova fra 0 secondi".
     */
    public static String until(java.time.LocalDateTime fine) {
        long secondi = java.time.Duration.between(java.time.LocalDateTime.now(), fine).getSeconds();
        return fromSeconds(Math.max(1, secondi));
    }

    /** Durata da ORE (anche frazionarie): "30 minuti", "1 ora", "48 ore", "7 giorni". */
    public static String fromHours(double ore) {
        if (ore < 1) return fromSeconds(Math.round(ore * 3600));
        if (ore >= 48 && ore % 24 == 0) {
            long giorni = (long) (ore / 24);
            return giorni + " giorni";
        }
        String n = number(ore);
        return n + (n.equals("1") ? " ora" : " ore");
    }
}
