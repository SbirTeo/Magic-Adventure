-- Visite alle discussioni legate all'ACCOUNT, non alla sessione: una riga per
-- (discussione, utente), quindi forum_topics.views = quante persone diverse l'hanno aperta.
-- Prima il conteggio stava nella sessione PHP e ripartiva a ogni browser nuovo.
USE magicadventure_web;

CREATE TABLE IF NOT EXISTS forum_topic_views (
    topic_id INT NOT NULL,
    user_id INT NOT NULL,
    viewed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (topic_id, user_id),
    KEY idx_utente (user_id),
    FOREIGN KEY (topic_id) REFERENCES forum_topics(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
