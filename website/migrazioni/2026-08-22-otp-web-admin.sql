-- ============================================================================
-- Verifica in due passaggi (OTP) per gli account che comandano il sito.
-- Vale per i web-admin sempre; per lo staff del gestionale solo se si accende
-- l'interruttore in Gestione > Sicurezza (otp_staff_obbligatorio).
-- ============================================================================

-- Il segreto TOTP sta CIFRATO (AES-256-GCM con OTP_CHIAVE, in config.php): chi leggesse
-- il database, o entrasse da phpMyAdmin, non potrebbe generare codici.
ALTER TABLE users
    ADD COLUMN totp_secret VARCHAR(255) NULL COMMENT 'Segreto TOTP cifrato (vedi includes/otp.php)',
    ADD COLUMN totp_attivato_il DATETIME NULL,
    -- Ultimo intervallo di 30 secondi gia' speso: impedisce di riusare un codice appena
    -- visto (sul sito e in gioco, che leggono la stessa riga).
    ADD COLUMN totp_ultimo_passo BIGINT NULL,
    ADD COLUMN totp_tentativi INT NOT NULL DEFAULT 0,
    ADD COLUMN totp_bloccato_fino DATETIME NULL;

-- Codici di recupero: monouso, nel database solo come hash (come le password).
CREATE TABLE IF NOT EXISTS otp_recovery_codes (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    used_at DATETIME NULL,
    KEY idx_utente (user_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Verifica superata IN GIOCO: entro la finestra configurata nel plugin il giocatore
-- rientra senza ridigitare il codice a ogni join (l'indirizzo fa parte della chiave,
-- quindi da un'altra rete il codice si rifa').
CREATE TABLE IF NOT EXISTS otp_game_sessions (
    mc_uuid CHAR(36) NOT NULL,
    ip VARCHAR(45) NOT NULL,
    verificato_il DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (mc_uuid, ip)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- "Resta collegato": il cookie vale come dispositivo fidato SOLO se e' nato dopo una
-- verifica OTP riuscita. I cookie gia' in giro valgono 0 e faranno ripassare dal codice.
ALTER TABLE remember_tokens
    ADD COLUMN otp_ok TINYINT(1) NOT NULL DEFAULT 0;

INSERT IGNORE INTO site_settings (setting_key, setting_value) VALUES
    ('otp_staff_obbligatorio', '0');

-- Le sessioni aperte adesso dagli account soggetti a OTP devono cadere: se no chi e' gia'
-- collegato continuerebbe a girare senza aver mai visto un codice.
UPDATE users SET session_epoch = session_epoch + 1 WHERE is_admin = 1;
DELETE FROM remember_tokens WHERE user_id IN (SELECT id FROM users WHERE is_admin = 1);
