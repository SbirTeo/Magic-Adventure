-- Presenza degli OSPITI: chi sta guardando il sito senza aver fatto l'accesso.
--
-- Una riga per sessione del browser, con l'ultima volta che ha aperto una pagina. La chiave
-- e' l'hash della sessione PHP, non la sessione stessa: se qualcuno leggesse la tabella non
-- ci troverebbe niente con cui impersonare nessuno. Nessun indirizzo IP, nessun dato
-- personale — serve solo a contare quante persone stanno guardando.
--
-- Le righe vecchie si buttano da sole (vedi ospiti_registra() in includes/helpers.php).
USE magicadventure_web;

CREATE TABLE IF NOT EXISTS ospiti_online (
    chiave    CHAR(64) NOT NULL PRIMARY KEY COMMENT 'hash della sessione: non identifica nessuno',
    last_seen DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_visto (last_seen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
