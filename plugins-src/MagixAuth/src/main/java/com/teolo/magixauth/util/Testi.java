package com.teolo.magixauth.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * I testi che il giocatore legge al cancello di MagixAuth (login, registrazione, OTP, espulsioni).
 * <p>
 * Stanno tutti qui, in un posto solo, perche' sono le prime righe che una persona legge del server e
 * vanno lette come le direbbe un umano: cosa e' successo, e cosa deve fare adesso.
 */
public final class Testi {

    /** Colori in stile "&": la stessa forma usata nei config, esadecimali compresi. */
    private static final LegacyComponentSerializer AMPERSAND =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private Testi() {}

    public static final String BENVENUTO_NUOVO = "&bBenvenuto su MAGICADVENTURE!&r\n&7Scegli una password: ti servira' per rientrare e per accedere\n&7al sito &fmagicadventure.it&7 con lo stesso nome.";
    public static final String BENTORNATO = "&bBentornato!&r &7Accedi con la tua password.";
    public static final String CREDENZIALI_NO = "&cPassword non corretta.&r &7Riprova.";
    public static final String NON_COINCIDONO = "&cLe due password non coincidono.&r &7Ricominciamo.";
    public static final String DENTRO = "&aAccesso effettuato.&r &7Buon gioco!";
    public static final String REGISTRATO = "&aRegistrazione completata.&r\n&7Da adesso puoi entrare anche su &fmagicadventure.it&7 con lo stesso\n&7nome e la stessa password.";
    public static final String OTP_SERVE = "&eVerifica in due passaggi.&r &7Serve il codice della tua app.";
    public static final String OTP_NO = "&cCodice non valido.&r &7Controlla l'app e riprova.";
    public static final String KICK_TEMPO_SCADUTO = "&cTempo scaduto&r\n\n&7Non hai completato l'accesso in tempo.\n&7Rientra pure e riprova.";
    public static final String KICK_DATABASE = "&cAccesso non disponibile&r\n\n&7Non riusciamo a verificare il tuo account in questo momento.\n&7Riprova fra qualche minuto: e' un problema nostro, non tuo.";
    public static final String KICK_NOME_OCCUPATO = "&cNome gia' in uso&r\n\n&7Qualcuno sta gia' giocando con questo nome.";

    /**
     * "Troppi codici sbagliati": con scritto quanto manca davvero.
     *
     * Il tempo va detto e non lasciato a "fra qualche minuto": chi non sa quanto deve
     * aspettare ritenta ogni mezzo minuto, e ogni tentativo e' un altro errore.
     */
    public static String otpBloccato(String quantoManca) {
        return "&cTroppi codici sbagliati.&r &7Riprova fra &f" + quantoManca + "&7.";
    }

    /** Lo stesso, per chi viene respinto al cancello prima ancora di entrare. */
    public static String kickTroppiTentativi(String quantoManca) {
        return "&cTroppi tentativi&r\n\n"
                + "&7Questo indirizzo e' bloccato: riprova fra &f" + quantoManca + "&7.\n"
                + "&7Se hai dimenticato la password, puoi reimpostarla su\n&fmagicadventure.it&7.\n\n"
                + "&8Il blocco vale per la connessione, non per il tuo nome.";
    }

    /** Il testo colorato, pronto da mandare al giocatore. */
    public static Component c(String testo) {
        return AMPERSAND.deserialize(testo);
    }

    /** Prefisso + testo, colorati insieme (i due pezzi si attaccano senza spazi in mezzo). */
    public static Component c(String prefisso, String testo) {
        return AMPERSAND.deserialize(prefisso + testo);
    }
}
