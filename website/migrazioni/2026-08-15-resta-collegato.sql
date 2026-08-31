-- "Resta collegato" (cookie a lunga scadenza, uno per browser).
-- Il selettore serve solo a trovare la riga; del validatore si conserva l'hash, cosi' chi
-- leggesse la tabella non potrebbe rifabbricarsi il cookie.
USE magicadventure_web;

CREATE TABLE IF NOT EXISTS remember_tokens (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    selector CHAR(32) NOT NULL UNIQUE,
    validator_hash CHAR(64) NOT NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at DATETIME NULL,
    KEY idx_scadenza (expires_at),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
