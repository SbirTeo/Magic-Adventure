package com.teolo.magixguard.sanzioni;

/**
 * Le durate come le scrive una persona: {@code 30m}, {@code 6h}, {@code 3d}, {@code 2w},
 * {@code permanente}. Vanno e vengono da millisecondi.
 *
 * <p>Il permanente e' {@link #PERMANENTE} (-1), non zero: zero e' una durata legittima
 * (un provvedimento gia' scaduto) e confonderli vorrebbe dire bandire per sempre qualcuno
 * per un errore di battitura.</p>
 */
public final class Durata {

    /** Valore convenzionale del provvedimento che non scade. */
    public static final long PERMANENTE = -1L;

    private static final long SECONDO = 1000L;
    private static final long MINUTO = 60 * SECONDO;
    private static final long ORA = 60 * MINUTO;
    private static final long GIORNO = 24 * ORA;
    private static final long SETTIMANA = 7 * GIORNO;

    private Durata() {
    }

    /**
     * Legge una durata scritta a mano. Ritorna {@link #PERMANENTE} per "permanente"/"perm",
     * oppure {@code 0} se la stringa non si capisce: chi chiama decide cosa farne, ma non
     * si inventa mai una durata al posto di chi ha scritto.
     */
    public static long leggi(String testo) {
        if (testo == null) {
            return 0L;
        }
        String s = testo.trim().toLowerCase();
        if (s.isEmpty()) {
            return 0L;
        }
        if (s.equals("permanente") || s.equals("perm") || s.equals("-1") || s.equals("per sempre")) {
            return PERMANENTE;
        }
        if (s.equals("0")) {
            return 0L;
        }

        long totale = 0L;
        long numero = 0L;
        boolean visto = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                numero = numero * 10 + (c - '0');
                visto = true;
                continue;
            }
            long unita = switch (c) {
                case 's' -> SECONDO;
                case 'm' -> MINUTO;
                case 'h', 'o' -> ORA;       // h di hour, o di ore: le scrivono in tutti e due i modi
                case 'd', 'g' -> GIORNO;    // d di day, g di giorni
                case 'w' -> SETTIMANA;
                default -> 0L;
            };
            if (unita == 0L || !visto) {
                return 0L;   // carattere che non c'entra: meglio non capire che indovinare
            }
            totale += numero * unita;
            numero = 0L;
            visto = false;
        }
        // Un numero senza unita' finale (es. "30") si intende in minuti: e' la lettura
        // piu' prudente, ed e' quella che uno staff si aspetta scrivendo /mute Tizio 30.
        if (visto) {
            totale += numero * MINUTO;
        }
        return totale;
    }

    /** Come si racconta a un essere umano: "3 giorni", "30 minuti", "permanente". */
    public static String scrivi(long millis) {
        if (millis == PERMANENTE) {
            return "permanente";
        }
        if (millis <= 0) {
            return "istantanea";
        }
        if (millis >= GIORNO) {
            long g = millis / GIORNO;
            return g + (g == 1 ? " giorno" : " giorni");
        }
        if (millis >= ORA) {
            long o = millis / ORA;
            return o + (o == 1 ? " ora" : " ore");
        }
        if (millis >= MINUTO) {
            long m = millis / MINUTO;
            return m + (m == 1 ? " minuto" : " minuti");
        }
        long s = Math.max(1, millis / SECONDO);
        return s + (s == 1 ? " secondo" : " secondi");
    }

    /** Quanto manca da adesso a quel momento, detto in italiano. */
    public static String mancante(long fineMillis) {
        long resta = fineMillis - System.currentTimeMillis();
        return resta <= 0 ? "poco" : scrivi(resta);
    }
}
