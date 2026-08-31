-- "Mi piace" sui messaggi del forum: una riga per (messaggio, utente), quindi uno ciascuno
-- e togliere il proprio e' una DELETE. I conteggi si fanno al volo, senza contatori da
-- tenere allineati.
USE magicadventure_web;

CREATE TABLE IF NOT EXISTS forum_likes (
    post_id INT NOT NULL,
    user_id INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (post_id, user_id),
    KEY idx_utente (user_id),
    FOREIGN KEY (post_id) REFERENCES forum_posts(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
