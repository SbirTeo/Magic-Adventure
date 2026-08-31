-- ============================================================================
--  Nomi di TABELLE e COLONNE in inglese.
--
--  La convenzione del progetto e' l'inglese per tutto cio' che si SCRIVE — chiavi
--  di config, comandi, permessi, identificatori di database — e l'italiano per
--  tutto cio' che si LEGGE: commenti, messaggi, guide, pagine del sito.
--  Il database era rimasto indietro: `sanzioni`, `auth_sessioni`, colonne come
--  `scade_il` o `revocata_da`.
--
--  Si usa RENAME COLUMN (MariaDB 10.5+) invece di CHANGE COLUMN: cosi' il tipo
--  non va riscritto a mano e non lo si cambia per sbaglio ricopiandolo.
--
--  DA APPLICARE CON IL SERVER MINECRAFT FERMO, e nello stesso momento in cui si
--  caricano i plugin e le pagine PHP aggiornate: fra i due passi il codice
--  vecchio cercherebbe nomi che non esistono piu'.
--  Backup preso prima: ~/backup-db-prima-rinomina-2026-08-31.sql.gz
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Le tabelle. Prima queste, perche' i rinomini delle colonne qui sotto
--    parlano gia' con i nomi nuovi.
-- ---------------------------------------------------------------------------
RENAME TABLE auth_posizioni       TO auth_positions;
RENAME TABLE auth_sessioni        TO auth_sessions;
RENAME TABLE auth_tentativi       TO auth_attempts;

-- ---------------------------------------------------------------------------
-- 2. Le colonne dell'autenticazione
-- ---------------------------------------------------------------------------
ALTER TABLE auth_positions RENAME COLUMN mondo      TO world;
ALTER TABLE auth_positions RENAME COLUMN salvata_il TO saved_at;

ALTER TABLE auth_sessions RENAME COLUMN dispositivo    TO device;
ALTER TABLE auth_sessions RENAME COLUMN password_ok_il TO password_ok_at;
ALTER TABLE auth_sessions RENAME COLUMN otp_ok_il      TO otp_ok_at;
ALTER TABLE auth_sessions RENAME COLUMN scade_il       TO expires_at;

ALTER TABLE auth_attempts RENAME COLUMN falliti       TO failures;
ALTER TABLE auth_attempts RENAME COLUMN ultimo_il     TO last_at;
ALTER TABLE auth_attempts RENAME COLUMN bloccato_fino TO locked_until;

-- users: la tabella e' quella del sito e resta com'e'. Si toccano solo le colonne
-- che erano state aggiunte con un nome italiano.
ALTER TABLE users RENAME COLUMN totp_attivato_il       TO totp_activated_at;
ALTER TABLE users RENAME COLUMN totp_ultimo_passo      TO totp_last_step;
ALTER TABLE users RENAME COLUMN totp_tentativi         TO totp_attempts;
ALTER TABLE users RENAME COLUMN totp_bloccato_fino     TO totp_locked_until;
ALTER TABLE users RENAME COLUMN premium_controllato_il TO premium_checked_at;
ALTER TABLE users RENAME COLUMN registrato_in_gioco_il TO registered_in_game_at;

-- la casella della posta fra sito e gioco per le revoche di sessione
ALTER TABLE otp_game_revoke   RENAME COLUMN chiesto_il    TO requested_at;
ALTER TABLE otp_game_revoke   RENAME COLUMN chiesto_da    TO requested_by;
ALTER TABLE otp_game_sessions RENAME COLUMN verificato_il TO verified_at;
