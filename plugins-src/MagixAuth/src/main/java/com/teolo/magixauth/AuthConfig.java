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
    public final int dbPort;
    public final String dbName;
    public final String dbUser;
    public final String dbPassword;
    public final String otpKeyBase64;

    public final int maxSeconds;
    public final int maxAttempts;
    public final int lockoutMinutes;
    public final int minPasswordLength;
    public final int sessionHours;
    public final boolean otpFollowsSession;
    public final boolean deviceCookie;
    public final long cookieWaitMillis;

    public final String staffTrack;
    public final boolean otpOptionalForPlayers;
    public final boolean passwordFirst;
    public final int revocationCheckSeconds;

    public final boolean spawnInsteadOfPosition;
    public final boolean hideFromOthers;
    public final boolean hideOthers;
    public final boolean hideChat;
    public final boolean delayJoinMessage;

    public final boolean skinFromMojang;
    public final int skinCacheMinutes;
    public final boolean noteUuidPremium;
    public final int premiumTimeoutMillis;

    public AuthConfig(FileConfiguration c) {
        this.dbHost = c.getString("database.host", "localhost");
        this.dbPort = c.getInt("database.port", 3306);
        this.dbName = c.getString("database.name", "magicadventure_web");
        this.dbUser = c.getString("database.user", "magicadventure");
        this.dbPassword = c.getString("database.password", "");
        this.otpKeyBase64 = c.getString("database.otp_key_base64", "");

        this.maxSeconds = c.getInt("login.max_seconds", 120);
        this.maxAttempts = c.getInt("login.max_attempts", 5);
        this.lockoutMinutes = c.getInt("login.lockout_minutes", 10);
        this.minPasswordLength = c.getInt("login.min_password_length", 8);
        this.sessionHours = c.getInt("login.session_hours", 24);
        this.otpFollowsSession = c.getBoolean("login.otp_follows_session", true);
        this.deviceCookie = c.getBoolean("login.device_cookie", true);
        this.cookieWaitMillis = c.getLong("login.cookie_wait_millis", 1500L);

        this.staffTrack = c.getString("otp.staff_track", "staff");
        this.otpOptionalForPlayers = c.getBoolean("otp.optional_for_players", true);
        // Volutamente non un enum: il valore ha senso solo in due modi, e chiunque apra il
        // file deve capire dal nome che invertirlo e' una scelta, non una preferenza.
        this.passwordFirst = !"otp-first".equalsIgnoreCase(c.getString("otp.order", "password-first"));
        this.revocationCheckSeconds = Math.max(2, c.getInt("otp.revocation_check_seconds", 5));

        this.spawnInsteadOfPosition = c.getBoolean("gate.spawn_instead_of_position", true);
        this.hideFromOthers = c.getBoolean("gate.hide_from_others", true);
        this.hideOthers = c.getBoolean("gate.hide_others", true);
        this.hideChat = c.getBoolean("gate.hide_chat", true);
        this.delayJoinMessage = c.getBoolean("gate.delay_join_message", true);

        this.skinFromMojang = c.getBoolean("premium.skin_from_mojang", true);
        this.skinCacheMinutes = c.getInt("premium.skin_cache_minutes", 30);
        this.noteUuidPremium = c.getBoolean("premium.note_uuid", true);
        this.premiumTimeoutMillis = c.getInt("premium.timeout_millis", 3000);
    }

    /** Senza questa il segreto OTP resta nella sua busta e nessun codice si puo' verificare. */
    public boolean otpKeyReady() {
        try {
            return otpKeyBase64 != null
                    && java.util.Base64.getDecoder().decode(otpKeyBase64.trim()).length == 32;
        } catch (Exception e) {
            return false;
        }
    }
}
