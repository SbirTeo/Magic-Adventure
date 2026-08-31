-- "Chiudi la sessione di gioco": il sito cancella la fiducia salvata per quell'account e
-- lascia qui un biglietto. Il plugin lo legge entro pochi secondi e, se il giocatore e' in
-- partita in quel momento, lo ricongela chiedendogli di nuovo il codice.
--
-- E' una tabella e non una colonna su `users` perche' e' un MESSAGGIO, non uno stato: nasce
-- quando si preme il pulsante e sparisce appena il server l'ha raccolto.
CREATE TABLE IF NOT EXISTS otp_game_revoke (
    mc_uuid CHAR(36) NOT NULL PRIMARY KEY,
    chiesto_il DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    chiesto_da VARCHAR(32) NULL COMMENT 'chi ha premuto il pulsante (per il log del server)'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
