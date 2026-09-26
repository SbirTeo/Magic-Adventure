package com.teolo.magixlanguage.translate;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.logging.Logger;

/**
 * Un solo tentativo di traduzione VERA al giorno, condiviso da tutto quello che chiama MyMemory
 * con lo stesso indirizzo (IP del VPS): sia {@link TranslationSync} (il sync dei plugin, a ogni
 * riavvio o {@code /language sync}) sia {@code MagixLanguage.translateRawBatch} (la traduzione del
 * sito, richiamata da MagixWeb ogni 30 secondi H24).
 *
 * <p>Prima che questa classe esistesse i due path avevano ciascuno la propria disciplina — anzi,
 * la traduzione del sito non ne aveva affatto: creava un {@link Translator} nuovo a ogni giro,
 * senza memoria dei fallimenti precedenti, e ripeteva la richiesta ogni 30 secondi tutto il
 * giorno. Era quasi certamente la causa principale per cui MyMemory restava bloccato (HTTP 429)
 * anche nelle ore in cui il sync dei plugin non aveva ancora consumato il proprio tentativo
 * giornaliero: la stessa quota per IP viene condivisa da MyMemory fra qualunque chiamante, quindi
 * un chiamante senza freno vanificava la disciplina dell'altro.</p>
 *
 * <p>Il file su disco (nella cartella dati di MagixLanguage) tiene solo la data dell'ultimo
 * tentativo: cancellarlo forza un nuovo tentativo prima di mezzanotte, per verificare a mano se un
 * blocco si e' gia' liberato (vedi anche "/language sync force").</p>
 */
public final class DailyThrottle {

    private final File file;
    private final Logger log;

    public DailyThrottle(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "last-translation-attempt.txt");
        this.log = log;
    }

    /** Se oggi si e' gia' tentata una traduzione vera (anche se fallita subito). */
    public boolean alreadyUsedToday() {
        if (!file.isFile()) {
            return false;
        }
        try {
            String saved = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
            return LocalDate.now().toString().equals(saved);
        } catch (IOException e) {
            return false;
        }
    }

    /** Segna il tentativo di oggi come usato, PRIMA di sapere se andra' a buon fine: e' il punto,
     *  altrimenti un tentativo fallito subito (HTTP 429) non risparmierebbe i giri successivi. */
    public void markUsedToday() {
        try {
            Files.writeString(file.toPath(), LocalDate.now().toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("MagixLanguage: impossibile salvare la data dell'ultimo tentativo di traduzione (" + e + ").");
        }
    }

    /** Cancella il segna-tentativo di oggi: il prossimo tentativo prova MyMemory anche se oggi
     *  e' gia' stato tentato. Usato da "/language sync force". */
    public void reset() {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            log.warning("MagixLanguage: impossibile azzerare il tentativo di oggi (" + e + ").");
        }
    }
}
