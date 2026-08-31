package com.teolo.magixauth.model;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Una riga di `users` vista con gli occhi del gioco.
 *
 * E' la stessa riga che il sito usa per far entrare la gente su magicadventure.it: qui non
 * esiste un'anagrafica separata dei giocatori, di proposito. Un account solo, una password
 * sola, un posto solo in cui cambiarla.
 */
public final class Account {

    /** Chiave del sito (`users.id`): serve per le tabelle che vi si agganciano. */
    public final int idSito;
    public final UUID uuid;
    public final String nome;

    /** bcrypt, oppure null: null significa "conosciuto ma mai registrato". */
    public final String passwordHash;

    /** Segreto TOTP ancora nella busta cifrata del sito (vedi OtpCodici#decifraSegreto). */
    public final String totpSecretCifrato;
    public final Long totpUltimoPasso;
    public final int totpTentativi;
    public final LocalDateTime totpBloccatoFino;

    /** Amministratore del sito: per lui la verifica in due passaggi non e' facoltativa. */
    public final boolean webAdmin;

    public Account(int idSito, UUID uuid, String nome, String passwordHash,
                   String totpSecretCifrato, Long totpUltimoPasso, int totpTentativi,
                   LocalDateTime totpBloccatoFino, boolean webAdmin) {
        this.idSito = idSito;
        this.uuid = uuid;
        this.nome = nome;
        this.passwordHash = passwordHash;
        this.totpSecretCifrato = totpSecretCifrato;
        this.totpUltimoPasso = totpUltimoPasso;
        this.totpTentativi = totpTentativi;
        this.totpBloccatoFino = totpBloccatoFino;
        this.webAdmin = webAdmin;
    }

    /**
     * Ha gia' una password?
     *
     * Distingue i due ingressi possibili: chi ha gia' usato /link e impostato la password
     * dal sito e' gia' registrato e deve solo entrare; chi non l'ha mai fatto ha la colonna
     * vuota e va portato alla registrazione. Nessuno dei due deve fare migrazioni.
     */
    public boolean registrato() {
        return passwordHash != null && !passwordHash.isEmpty();
    }

    /** Ha attivato la verifica in due passaggi (dal sito)? */
    public boolean haOtp() {
        return totpSecretCifrato != null && !totpSecretCifrato.isEmpty();
    }

    /** Il codice e' bloccato per troppi tentativi sbagliati? */
    public boolean otpBloccato() {
        return totpBloccatoFino != null && totpBloccatoFino.isAfter(LocalDateTime.now());
    }
}
