package com.teolo.magixfactions.util;

import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Traduzione colori: codici &, e esadecimali &#RRGGBB. */
public final class Colors {

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    /** Codici colore/formato &: esadecimali &#RRGGBB e singoli &0-&f/&k-&o/&r. */
    private static final Pattern CODES = Pattern.compile("&#[0-9a-fA-F]{6}|&[0-9a-fk-orA-FK-OR]");

    private Colors() {}

    /**
     * Toglie tutti i codici colore/formato {@code &} (e {@code &#RRGGBB}) lasciando solo il testo.
     * Usato per i tag dei gradi quando il colore lo deve dare la RELAZIONE, non il tag stesso.
     */
    public static String stripCodes(String s) {
        return s == null ? "" : CODES.matcher(s).replaceAll("");
    }

    public static String translate(String s) {
        if (s == null) return "";
        // \n scritto nel file (due caratteri: backslash + n) diventa un vero a capo: senza
        // virgolette doppie perfette YAML lo lascerebbe letterale, e chi scrive messages.yml
        // scrive quasi sempre senza virgolette o con quelle singole (che NON interpretano gli
        // escape). Cosi' un a capo funziona sempre, qualunque sia lo stile della stringa.
        s = s.replace("\\n", "\n");
        Matcher m = HEX.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) rep.append('§').append(c);
            m.appendReplacement(sb, rep.toString());
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }
}
