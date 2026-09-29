-- Pagina /progetto: le "modalita'" (Factions, Hub, ...). Ogni attivita' appartiene a una
-- modalita' (o a nessuna, "Generale"): la bacheca si guarda una modalita' alla volta o tutte
-- insieme, e la panoramica mostra l'avanzamento di ciascuna oltre al totale del progetto.
-- Rilanciarla non fa niente.

CREATE TABLE IF NOT EXISTS project_boards (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(60) NOT NULL,
    color CHAR(7) NOT NULL DEFAULT '#a3e635',
    sort_order INT NOT NULL DEFAULT 0,
    created_by INT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE project_tasks ADD COLUMN IF NOT EXISTS board_id INT NULL AFTER goal_id;
ALTER TABLE project_tasks ADD INDEX IF NOT EXISTS idx_board (board_id);
