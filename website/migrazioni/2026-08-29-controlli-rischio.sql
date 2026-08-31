-- =====================================================================
--  Chi e' stato gia' controllato, e quando.
--
--  Serve alla classifica di rischio del gestionale: senza, la lista mostra
--  sempre gli stessi nomi in cima e non si capisce piu' chi manca da guardare.
--  Una riga per ogni controllo fatto: e' anche lo storico di chi ha guardato cosa.
-- =====================================================================
CREATE TABLE IF NOT EXISTS sanzioni_controlli (
    id INT AUTO_INCREMENT PRIMARY KEY,
    mc_uuid CHAR(36) NOT NULL,
    mc_username VARCHAR(32) NOT NULL,
    staff_nome VARCHAR(32) NOT NULL,
    -- Cosa ha visto chi e' andato a guardare. Facoltativa ma preziosa: fra un mese
    -- "gia' controllato" senza una riga di spiegazione non dice niente a nessuno.
    nota VARCHAR(500) NULL DEFAULT NULL,
    -- 'pulito' = guardato e non c'e' niente; 'sospetto' = da riguardare presto
    esito ENUM('pulito','sospetto') NOT NULL DEFAULT 'pulito',
    controllato_il DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_uuid (mc_uuid, controllato_il)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
