-- Chat live del sito divisa per server (MagixBridge 0.14.0): una scheda per modalita'
-- (hub, faction...) nella chat in home.
--
-- Ogni messaggio dice di quale server e': quelli scritti in gioco vengono dal server su cui
-- sono stati scritti, quelli scritti sul sito vanno al server della scheda aperta, e li
-- ripubblica in gioco solo quel server. I messaggi di prima erano tutti del faction.
--
-- Idempotente (IF NOT EXISTS di MariaDB). La stessa colonna la aggiunge da solo anche
-- MagixBridge all'avvio: questa migrazione serve a non aspettare il riavvio del server.
ALTER TABLE web_chat
    ADD COLUMN IF NOT EXISTS server VARCHAR(32) NOT NULL DEFAULT 'faction' AFTER source,
    ADD INDEX IF NOT EXISTS idx_server_consegna (server, delivered, source, id),
    ADD INDEX IF NOT EXISTS idx_server_id (server, id);
