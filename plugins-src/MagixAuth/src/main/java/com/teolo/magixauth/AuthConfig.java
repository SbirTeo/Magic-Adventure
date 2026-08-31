package com.teolo.magixauth;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * La configurazione, letta una volta e tenuta in campi finali.
 *
 * Rileggere il file a ogni join sarebbe un accesso a disco sul percorso critico del login;
 * qui si legge all'avvio e a /mauth reload, e basta.
 */
public final class AuthConfig {

    public final String dbHost;
    public final int dbPorta;
    public final String dbNome;
    public final String dbUtente;
    public final String dbPassword;
    public final String chiaveOtpBase64;

    public final int secondiMassimi;
    public final int tentativiMassimi;
    public final int bloccoMinuti;
    public final int passwordMinima;
    public final int oreSessione;
    public final boolean otpSegueLaSessione;
    public final boolean cookieDispositivo;
    public final long cookieAttesaMillis;

    public final String trackStaff;
    public final boolean otpFacoltativoPerGiocatori;
    public final boolean passwordPrima;
    public final int controlloRevocheSecondi;

    public final boolean spawnAlPostoDellaPosizione;
    public final boolean invisibileAgliAltri;
    public final boolean nascondiGliAltri;
    public final boolean nascondiChat;
    public final boolean ritardaMessaggioIngresso;

    public final boolean skinDaMojang;
    public final int skinMinutiCache;
    public final boolean annotaUuidPremium;
    public final int premiumTimeoutMillis;

    public final String prefisso;

    public AuthConfig(FileConfiguration c) {
        this.dbHost = c.getString("database.host", "localhost");
        this.dbPorta = c.getInt("database.port", 3306);
        this.dbNome = c.getString("database.name", "magicadventure_web");
        this.dbUtente = c.getString("database.user", "magicadventure");
        this.dbPassword = c.getString("database.password", "");
        this.chiaveOtpBase64 = c.getString("database.otp_key_base64", "");

        this.secondiMassimi = c.getInt("login.max_seconds", 120);
        this.tentativiMassimi = c.getInt("login.max_attempts", 5);
        this.bloccoMinuti = c.getInt("login.lockout_minutes", 10);
        this.passwordMinima = c.getInt("login.min_password_length", 8);
        this.oreSessione = c.getInt("login.session_hours", 24);
        this.otpSegueLaSessione = c.getBoolean("login.otp_follows_session", true);
        this.cookieDispositivo = c.getBoolean("login.device_cookie", true);
        this.cookieAttesaMillis = c.getLong("login.cookie_wait_millis", 1500L);

        this.trackStaff = c.getString("otp.staff_track", "staff");
        this.otpFacoltativoPerGiocatori = c.getBoolean("otp.optional_for_players", true);
        // Volutamente non un enum: il valore ha senso solo in due modi, e chiunque apra il
        // file deve capire dal nome che invertirlo e' una scelta, non una preferenza.
        this.passwordPrima = !"otp-first".equalsIgnoreCase(c.getString("otp.order", "password-first"));
        this.controlloRevocheSecondi = Math.max(2, c.getInt("otp.revocation_check_seconds", 5));

        this.spawnAlPostoDellaPosizione = c.getBoolean("gate.spawn_instead_of_position", true);
        this.invisibileAgliAltri = c.getBoolean("gate.hide_from_others", true);
        this.nascondiGliAltri = c.getBoolean("gate.hide_others", true);
        this.nascondiChat = c.getBoolean("gate.hide_chat", true);
        this.ritardaMessaggioIngresso = c.getBoolean("gate.delay_join_message", true);

        this.skinDaMojang = c.getBoolean("premium.skin_from_mojang", true);
        this.skinMinutiCache = c.getInt("premium.skin_cache_minutes", 30);
        this.annotaUuidPremium = c.getBoolean("premium.note_uuid", true);
        this.premiumTimeoutMillis = c.getInt("premium.timeout_millis", 3000);

        this.prefisso = c.getString("messages.prefix", "&#C046E8&lMagixAuth &8» &r");
    }

    /** Senza questa il segreto OTP resta nella sua busta e nessun codice si puo' verificare. */
    public boolean chiaveOtpPronta() {
        try {
            return chiaveOtpBase64 != null
                    && java.util.Base64.getDecoder().decode(chiaveOtpBase64.trim()).length == 32;
        } catch (Exception e) {
            return false;
        }
    }
}
