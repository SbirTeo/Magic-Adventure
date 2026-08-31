package com.teolo.magixguard.chat;

/**
 * Riporta un messaggio alla sua forma nuda, per poterlo confrontare con un dizionario.
 *
 * <p>Chi vuole aggirare un filtro non scrive la parola: scrive <i>c4zz0</i>, <i>c a z z o</i>,
 * <i>cazzzzo</i>, <i>c.a.z.z.o</i>. Sono quattro trucchi diversi e vanno smontati tutti, ma
 * senza esagerare: piu' si normalizza, piu' si rischia di trasformare una parola innocente in
 * una vietata — ed e' un errore che il giocatore non perdona, perche' lui sa di non aver
 * scritto niente di male.</p>
 *
 * <p>Per questo si producono <b>due</b> forme e si controllano tutte e due:</p>
 * <ul>
 *   <li>la forma <b>a parole</b>, dove le parole restano separate: serve al confronto esatto,
 *       ed e' quella che evita di trovare "culo" dentro "calcolo";</li>
 *   <li>la forma <b>compatta</b>, senza spazi ne' ripetizioni: serve a beccare le spaziature
 *       finte, e si usa solo per le parole abbastanza lunghe da non dare falsi allarmi.</li>
 * </ul>
 */
public final class Normalizer {

    private Normalizer() {
    }

    /** Il messaggio in minuscolo, coi trucchi tipografici sciolti e le parole ancora separate. */
    public static String aParole(String testo) {
        if (testo == null) {
            return "";
        }
        String s = sciogliCodici(testo.toLowerCase());
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                b.append(c);
            } else {
                // qualunque separatore (spazio, punto, trattino) diventa uno spazio solo
                if (b.length() > 0 && b.charAt(b.length() - 1) != ' ') {
                    b.append(' ');
                }
            }
        }
        return comprimiRipetizioni(b.toString().trim());
    }

    /** Lo stesso messaggio senza piu' nessuno spazio: e' la forma che smaschera "s p a m". */
    public static String compatta(String testo) {
        return aParole(testo).replace(" ", "");
    }

    /**
     * I caratteri usati al posto delle lettere. Si tengono solo le sostituzioni davvero comuni:
     * ogni riga in piu' e' un falso positivo in piu'.
     */
    private static String sciogliCodici(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            b.append(switch (c) {
                case '4', '@' -> 'a';
                case '3', '€' -> 'e';
                case '1', '!', '|' -> 'i';
                case '0' -> 'o';
                case '5', '$' -> 's';
                case '7' -> 't';
                default -> c;
            });
        }
        return b.toString();
    }

    /**
     * "cazzzzo" -> "cazzo". Si riducono le lettere ripetute piu' di due volte, non le doppie:
     * in italiano le doppie sono ortografia, non enfasi.
     */
    private static String comprimiRipetizioni(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int uguali = 0;
        char precedente = 0;
        for (char c : s.toCharArray()) {
            uguali = (c == precedente) ? uguali + 1 : 0;
            if (uguali < 2) {
                b.append(c);
            }
            precedente = c;
        }
        return b.toString();
    }

    /**
     * Quanto due messaggi si somigliano, da 0 a 1. Serve a riconoscere chi ripete la stessa
     * cosa cambiando una lettera per non farsi beccare.
     *
     * <p>E' una distanza di Levenshtein normalizzata: sui messaggi di chat (poche decine di
     * caratteri) costa niente, e non serve niente di piu' furbo.</p>
     */
    public static double somiglianza(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        if (a.equals(b)) {
            return 1;
        }
        int massimo = Math.max(a.length(), b.length());
        if (massimo == 0) {
            return 1;
        }
        if (massimo > 200) {
            return a.equals(b) ? 1 : 0;   // messaggi lunghissimi: non vale la pena
        }
        return 1.0 - (double) distanza(a, b) / massimo;
    }

    private static int distanza(String a, String b) {
        int[] precedente = new int[b.length() + 1];
        int[] corrente = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            precedente[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            corrente[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int costo = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                corrente[j] = Math.min(Math.min(corrente[j - 1] + 1, precedente[j] + 1),
                        precedente[j - 1] + costo);
            }
            int[] scambio = precedente;
            precedente = corrente;
            corrente = scambio;
        }
        return precedente[b.length()];
    }

    /** La percentuale di lettere maiuscole, contando solo le lettere. */
    public static int percentualeMaiuscole(String testo) {
        int lettere = 0;
        int maiuscole = 0;
        for (char c : testo.toCharArray()) {
            if (Character.isLetter(c)) {
                lettere++;
                if (Character.isUpperCase(c)) {
                    maiuscole++;
                }
            }
        }
        return lettere == 0 ? 0 : (maiuscole * 100) / lettere;
    }
}
