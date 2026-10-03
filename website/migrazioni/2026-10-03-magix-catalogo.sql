-- Store dei Magix: "Cosa puoi comprare con N Magix". Le voci (nome, costo in Magix, nota,
-- colore dell'etichetta) le scrive lo staff in Gestione -> Store; lo store le mostra sotto il
-- cursore. includes/magix.php crea la stessa tabella da solo se manca.
--
-- Idempotente (IF NOT EXISTS).
CREATE TABLE IF NOT EXISTS magix_catalog (
    id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(40) NOT NULL,
    cost INT UNSIGNED NOT NULL,
    note VARCHAR(80) NOT NULL DEFAULT '',
    color CHAR(7) NOT NULL DEFAULT '#c04ff0',
    sort_order INT NOT NULL DEFAULT 0,
    enabled TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
