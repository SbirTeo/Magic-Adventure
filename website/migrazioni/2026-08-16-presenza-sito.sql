-- Presenza sul sito: quando l'utente ha aperto l'ultima pagina.
-- La scrive current_user() (includes/auth.php) al massimo una volta al minuto; la legge
-- utenti_sul_sito() per il riquadro "Sul sito ora" nella colonna della home.
USE magicadventure_web;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS last_seen DATETIME NULL AFTER last_login,
    ADD INDEX IF NOT EXISTS idx_last_seen (last_seen);
