-- Scheda "Progetto" del gestionale (solo web-admin): bacheca delle attivita', obiettivi,
-- calendario degli appuntamenti, chat fra amministratori e registro di chi ha fatto cosa.
-- Vedi public/api/project.php e public/assets/js/project.js. Rilanciarla non fa niente.

CREATE TABLE IF NOT EXISTS project_goals (
    id INT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    description TEXT NULL,
    -- Colore della barra dell'obiettivo e delle schede collegate (#RRGGBB).
    color CHAR(7) NOT NULL DEFAULT '#a3e635',
    due_date DATE NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_by INT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS project_tasks (
    id INT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(160) NOT NULL,
    notes TEXT NULL,
    -- Le colonne della bacheca: idea -> da fare -> in corso -> fatto.
    status ENUM('idea','todo','doing','done') NOT NULL DEFAULT 'todo',
    priority ENUM('low','normal','high') NOT NULL DEFAULT 'normal',
    assignee_id INT NULL,
    goal_id INT NULL,
    due_date DATE NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_by INT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- Quando e' finita in "fatto": alimenta il grafico delle attivita' chiuse per settimana.
    completed_at DATETIME NULL,
    KEY idx_status (status, sort_order),
    KEY idx_goal (goal_id),
    KEY idx_completed (completed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS project_events (
    id INT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(160) NOT NULL,
    notes TEXT NULL,
    starts_at DATETIME NOT NULL,
    ends_at DATETIME NULL,
    all_day TINYINT(1) NOT NULL DEFAULT 0,
    color CHAR(7) NOT NULL DEFAULT '#c04ff0',
    created_by INT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_starts (starts_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS project_messages (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    body TEXT NOT NULL,
    image_url VARCHAR(255) NULL,
    reply_to BIGINT UNSIGNED NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Una modifica o una cancellazione cambia updated_at: la pagina gia' aperta dell'altro la
    -- ripesca senza ricaricare (vedi "changed since" nell'API).
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    edited_at DATETIME NULL,
    deleted_at DATETIME NULL,
    pinned TINYINT(1) NOT NULL DEFAULT 0,
    KEY idx_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS project_message_reactions (
    message_id BIGINT UNSIGNED NOT NULL,
    user_id INT NOT NULL,
    emoji VARCHAR(16) NOT NULL,
    PRIMARY KEY (message_id, user_id, emoji)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Per ogni amministratore: fin dove ha letto la chat (le "spunte" e il numero sulla scheda),
-- fin dove ha visto il registro, e fino a quando sta scrivendo ("sta scrivendo...").
CREATE TABLE IF NOT EXISTS project_reads (
    user_id INT PRIMARY KEY,
    last_message_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    last_activity_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    typing_until DATETIME NULL,
    seen_at DATETIME NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS project_activity (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id INT NULL,
    text VARCHAR(255) NOT NULL,
    task_id INT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
