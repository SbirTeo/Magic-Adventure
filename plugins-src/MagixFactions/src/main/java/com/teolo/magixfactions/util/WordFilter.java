package com.teolo.magixfactions.util;

import java.util.List;
import java.util.Locale;

/**
 * Filtro anti-parolacce/offensivo, riutilizzabile per qualsiasi testo dei giocatori
 * (nomi fazione, descrizioni, ecc.). La lista di parole vietate arriva dal config
 * ({@code forbidden-words}). Il confronto e' case-insensitive, neutralizza il leetspeak
 * e ignora simboli/spazi, con match per sottostringa.
 */
public final class WordFilter {

    private WordFilter() {}

    /** Riduce il testo a sole lettere/cifre minuscole, convertendo il leetspeak piu' comune. */
    private static String normalize(String s) {
        if (s == null) return "";
        s = s.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char m = switch (c) {
                case '0' -> 'o';
                case '1', '!', '|' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '7' -> 't';
                case '8' -> 'b';
                default -> c;
            };
            if (Character.isLetterOrDigit(m)) b.append(m);
        }
        return b.toString();
    }

    /**
     * @return la prima parola vietata contenuta in {@code input} (come indicata nel config),
     *         oppure {@code null} se il testo e' pulito.
     */
    public static String firstForbidden(List<String> forbidden, String input) {
        if (input == null || forbidden == null || forbidden.isEmpty()) return null;
        String norm = normalize(input);
        if (norm.isEmpty()) return null;
        for (String w : forbidden) {
            if (w == null) continue;
            String nw = normalize(w);
            if (!nw.isEmpty() && norm.contains(nw)) return w;
        }
        return null;
    }

    /** @return true se il testo contiene almeno una parola vietata. */
    public static boolean isForbidden(List<String> forbidden, String input) {
        return firstForbidden(forbidden, input) != null;
    }
}
