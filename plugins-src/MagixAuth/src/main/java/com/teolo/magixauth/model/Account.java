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
    public final int siteId;
    public final UUID uuid;
    public final String name;

    /** bcrypt, oppure null: null significa "conosciuto ma mai registrato". */
    public final String passwordHash;

    /** Segreto TOTP ancora nella busta cifrata del sito (vedi OtpCodici#decifraSegreto). */
    public final String totpSecretCifrato;
    public final Long totpLastStep;
    public final int totpAttempts;
    public final LocalDateTime totpLockedUntil;

    /** Amministratore del sito: per lui la verifica in due passaggi non e' facoltativa. */
    public final boolean webAdmin;

    public Account(int siteId, UUID uuid, String name, String passwordHash,
                   String totpSecretCifrato, Long totpLastStep, int totpAttempts,
                   LocalDateTime totpLockedUntil, boolean webAdmin) {
        this.siteId = siteId;
        this.uuid = uuid;
        this.name = name;
        this.passwordHash = passwordHash;
        this.totpSecretCifrato = totpSecretCifrato;
        this.totpLastStep = totpLastStep;
        this.totpAttempts = totpAttempts;
        this.totpLockedUntil = totpLockedUntil;
        this.webAdmin = webAdmin;
    }

    /**
     * Ha gia' una password?
     *
     * Distingue i due ingressi possibili: chi ha gia' usato /link e impostato la password
     * dal sito e' gia' registrato e deve solo entrare; chi non l'ha mai fatto ha la colonna
     * vuota e va portato alla registrazione. Nessuno dei due deve fare migrazioni.
     */
    public boolean registered() {
        return passwordHash != null && !passwordHash.isEmpty();
    }

    /** Ha attivato la verifica in due passaggi (dal sito)? */
    public boolean haOtp() {
        return totpSecretCifrato != null && !totpSecretCifrato.isEmpty();
    }

    /** Il codice e' bloccato per troppi tentativi sbagliati? */
    public boolean otpLocked() {
        return totpLockedUntil != null && totpLockedUntil.isAfter(LocalDateTime.now());
    }
}
