-- Store dei Magix: una ricarica si puo' regalare a un altro giocatore. Chi paga resta in
-- user_id/mc_uuid; i Magix vanno a recipient_uuid (UUID di gioco), il nome resta per l'elenco.
-- Vuoti = ricarica per se'. includes/magix.php aggiunge le stesse colonne da solo se mancano.
--
-- Idempotente (IF NOT EXISTS di MariaDB).
ALTER TABLE magix_orders
    ADD COLUMN IF NOT EXISTS recipient_uuid VARCHAR(36) NULL DEFAULT NULL AFTER mc_username,
    ADD COLUMN IF NOT EXISTS recipient_name VARCHAR(32) NULL DEFAULT NULL AFTER recipient_uuid,
    ADD INDEX IF NOT EXISTS idx_magix_orders_recipient (recipient_uuid, status);
