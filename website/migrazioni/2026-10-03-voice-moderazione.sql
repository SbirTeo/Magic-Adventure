-- Voice, fase 3: chi sta parlando nelle stanze di prossimità (lo scrive il browser tramite
-- api/voice.php, con una scadenza di pochi secondi; MagixBridge lo legge per le note sopra la
-- testa in gioco) e i mute di sola voce dati dallo staff dal gestionale (scheda Voice).
-- Le stesse tabelle le crea MagixBridge all'avvio (db/Database.java): questa migrazione serve
-- solo a non aspettare il primo riavvio. Idempotente.
CREATE TABLE IF NOT EXISTS voice_speaking (
    mc_uuid CHAR(36) NOT NULL PRIMARY KEY,
    room VARCHAR(64) NOT NULL,
    until_at DATETIME(3) NOT NULL,
    KEY idx_room_until (room, until_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS voice_mutes (
    id INT AUTO_INCREMENT PRIMARY KEY,
    mc_uuid CHAR(36) NOT NULL,
    mc_username VARCHAR(32) NOT NULL,
    ends_at DATETIME NOT NULL,
    staff VARCHAR(32) NOT NULL,
    reason VARCHAR(200) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lifted_at DATETIME NULL,
    lifted_by VARCHAR(32) NULL,
    KEY idx_uuid_end (mc_uuid, ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
