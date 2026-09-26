package com.teolo.magixlanguage.translate;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Quando si puo' chiamare davvero MyMemory, condiviso da tutto quello che lo chiama con l'IP del
 * VPS: {@link TranslationSync} (i messaggi dei plugin) e {@code MagixLanguage.translateRawBatch}
 * (il sito, via MagixWeb ogni 30 secondi H24). Due regole, entrambe su disco perche' un riavvio
 * non le azzeri:
 *
 * <ul>
 *   <li><b>pausa dopo un blocco</b>: quando MyMemory rifiuta tre richieste di fila (quota del giorno
 *       finita, HTTP 429) nessuno lo richiama fino alla fine della pausa — la durata che indica
 *       MyMemory stessa nella risposta, se la da', altrimenti quella del config. Finita la pausa si
 *       riprende da soli, senza aspettare un riavvio o il giorno dopo;</li>
 *   <li><b>intervallo fra un giro e l'altro dei plugin</b>: senza blocchi, le chiavi rimaste
 *       mancanti (di solito traduzioni scartate perche' rovinavano un colore o un placeholder) si
 *       riprovano al massimo ogni tanto, non a ogni riavvio: ogni tentativo consuma quota, e il
 *       server si riavvia anche piu' volte al giorno per i deploy.</li>
 * </ul>
 *
 * <p>Sostituisce il vecchio "un tentativo al giorno": se quell'unico tentativo capitava mentre
 * MyMemory era ancora bloccato dal giorno prima (al riavvio notturno, per esempio), la giornata
 * intera andava persa con zero chiavi tradotte — successo davvero per piu' giorni di fila, mentre un
 * /language sync force lanciato a mano nel pomeriggio ne traduceva 844 in dieci minuti.</p>
 */
public final class TranslationPacing {

    private static final String FILE = "translation-pacing.properties";
    private static final String PAUSED_UNTIL = "paused-until";
    private static final String LAST_PLUGIN_ATTEMPT = "last-plugin-attempt";

    /** Un'indicazione di MyMemory oltre questo limite e' quasi certamente letta male: si usa il default. */
    private static final Duration MAX_HINT = Duration.ofHours(26);

    private final File file;
    private final Logger log;

    public TranslationPacing(File dataFolder, Logger log) {
        this.file = new File(dataFolder, FILE);
        this.log = log;
    }

    /** Fine della pausa dopo un blocco, o null se non ce n'e' una in corso. */
    public synchronized Instant pausedUntil() {
        Instant until = read(PAUSED_UNTIL);
        return until != null && Instant.now().isBefore(until) ? until : null;
    }

    /**
     * Quando il sync dei plugin potra' di nuovo chiamare MyMemory, o null se puo' farlo adesso.
     * Se l'ultimo giro si e' fermato per un blocco, conta solo la pausa: finita quella si riprende
     * subito, senza aspettare anche l'intervallo.
     */
    public synchronized Instant nextPluginAttempt(int intervalMinutes) {
        Instant pause = read(PAUSED_UNTIL);
        if (pause != null) {
            return Instant.now().isBefore(pause) ? pause : null;
        }
        Instant last = read(LAST_PLUGIN_ATTEMPT);
        if (last == null) {
            return null;
        }
        Instant next = last.plus(Duration.ofMinutes(intervalMinutes));
        return Instant.now().isBefore(next) ? next : null;
    }

    /** Segnato PRIMA di chiamare MyMemory, cosi' un giro interrotto a meta' conta comunque. */
    public synchronized void markPluginAttempt() {
        Properties p = load();
        p.setProperty(LAST_PLUGIN_ATTEMPT, Instant.now().toString());
        save(p);
    }

    /**
     * Da chiamare a fine giro con il {@link Translator} usato: se MyMemory ha bloccato, apre la
     * pausa; se ha risposto almeno una volta senza bloccare, chiude quella precedente.
     *
     * @return fine della pausa appena aperta, o null
     */
    public synchronized Instant recordOutcome(Translator translator, int defaultPauseMinutes) {
        if (translator.wasBlocked()) {
            Duration hint = translator.retryHint();
            Duration wait = hint != null && !hint.isNegative() && hint.compareTo(MAX_HINT) <= 0
                    ? hint.plusMinutes(1)
                    : Duration.ofMinutes(Math.max(1, defaultPauseMinutes));
            Instant until = Instant.now().plus(wait);
            Properties p = load();
            p.setProperty(PAUSED_UNTIL, until.toString());
            save(p);
            log.warning("MagixLanguage: MyMemory ha bloccato le richieste, pausa di " + formatWait(wait)
                    + (hint != null ? " (indicata da MyMemory)" : "")
                    + ": poi si riprova da soli, o subito con /language sync force.");
            return until;
        }
        if (translator.madeRequests()) {
            Properties p = load();
            if (p.remove(PAUSED_UNTIL) != null) {
                save(p);
            }
        }
        return null;
    }

    /** "/language sync force": via la pausa e l'intervallo, il prossimo giro chiama subito. */
    public synchronized void clear() {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            log.warning("MagixLanguage: impossibile azzerare " + FILE + " (" + e + ").");
        }
    }

    /** "2h 15m", "40m", "meno di 1m": leggibile in qualunque lingua, senza fuso orario. */
    public static String formatWait(Duration d) {
        long minutes = Math.max(0, (d.toSeconds() + 59) / 60);
        if (minutes < 1) {
            return "<1m";
        }
        long h = minutes / 60;
        long m = minutes % 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }

    private Instant read(String key) {
        String raw = load().getProperty(key);
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Properties load() {
        Properties p = new Properties();
        if (file.isFile()) {
            try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                log.warning("MagixLanguage: impossibile leggere " + FILE + " (" + e + ").");
            }
        }
        return p;
    }

    private void save(Properties p) {
        try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            p.store(w, "MagixLanguage: quando si puo' richiamare MyMemory (vedi TranslationPacing). Cancellarlo = /language sync force.");
        } catch (IOException e) {
            log.warning("MagixLanguage: impossibile salvare " + FILE + " (" + e + ").");
        }
    }
}
