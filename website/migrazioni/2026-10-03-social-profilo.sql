-- Contatti social del giocatore: li sceglie lui nel suo profilo (/profilo) e si vedono sulla sua
-- scheda pubblica (/utente). Una riga per social; si salva solo il nome utente gia' ripulito, mai
-- un indirizzo: il collegamento lo costruisce il sito (includes/socials.php), solo verso i domini
-- noti di ogni social. Cancellando l'utente spariscono anche i suoi contatti.
--
-- Idempotente (IF NOT EXISTS).
CREATE TABLE IF NOT EXISTS user_socials (
    user_id INT NOT NULL,
    network VARCHAR(20) NOT NULL,
    handle VARCHAR(100) NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, network),
    CONSTRAINT fk_user_socials_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
