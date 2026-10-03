-- Modifica di una sanzione dal sito (durata e motivo), con lo storico di chi ha cambiato cosa.
--
-- La modifica si scrive direttamente sulla riga di `punishments` (ends_at, reason): MagixGuard
-- legge le sanzioni dal database ogni volta che servono (il ban all'ingresso, i mute a ogni giro
-- di NetworkSync), quindi la nuova durata vale in gioco da sola, senza toccare il plugin.
-- Qui resta la traccia: prima e dopo, chi e perche'. La pagina /sanzione/<id> la mostra a tutti,
-- come la revoca: un provvedimento cambiato in silenzio non sarebbe trasparente.
--
-- Idempotente (IF NOT EXISTS).
CREATE TABLE IF NOT EXISTS punishment_edits (
    id INT AUTO_INCREMENT PRIMARY KEY,
    punishment_id INT NOT NULL,
    staff_name VARCHAR(32) NOT NULL,
    old_ends_at DATETIME NULL DEFAULT NULL,
    new_ends_at DATETIME NULL DEFAULT NULL,
    old_reason VARCHAR(255) NOT NULL,
    new_reason VARCHAR(255) NOT NULL,
    -- 0 = la durata non e' cambiata (old_ends_at e new_ends_at sono solo la copia)
    duration_changed TINYINT(1) NOT NULL DEFAULT 0,
    note VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_punishment (punishment_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
