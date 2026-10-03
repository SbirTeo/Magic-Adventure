-- Store dei Magix: le ricariche comprate sul sito (store.php -> PayPal -> accredito nel saldo).
-- Una riga per ricarica: quanti Magix, sconto, prezzo pagato, esito del pagamento. L'accredito
-- va nella tabella delle valute di MagixEssentials (me_currency_balances, la crea il plugin).
-- includes/magix.php crea la stessa tabella da solo se manca: questa migrazione serve a
-- prepararla prima della prima visita.
--
-- Idempotente (IF NOT EXISTS).
CREATE TABLE IF NOT EXISTS magix_orders (
    id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id INT UNSIGNED NOT NULL,
    mc_uuid VARCHAR(36) NOT NULL,
    mc_username VARCHAR(32) NOT NULL,
    amount INT UNSIGNED NOT NULL,
    discount_pct TINYINT UNSIGNED NOT NULL DEFAULT 0,
    price DECIMAL(10,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'EUR',
    status ENUM('pending','paid','failed','cancelled') NOT NULL DEFAULT 'pending',
    paypal_order_id VARCHAR(64) NULL DEFAULT NULL,
    paypal_capture_id VARCHAR(64) NULL DEFAULT NULL,
    terms_accepted_at DATETIME NULL DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at DATETIME NULL DEFAULT NULL,
    KEY idx_magix_orders_user (user_id, status),
    KEY idx_magix_orders_paid (status, paid_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
