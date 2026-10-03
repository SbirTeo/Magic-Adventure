-- Comandi dello store divisi per server (modalita' della rete: faction, hub...).
--
-- store_packages.server_commands: JSON {"faction": "comando\ncomando", "hub": "..."}, una chiave
-- per ogni server di GAME_SERVERS (includes/helpers.php). I pacchetti che c'erano gia' prendono
-- i loro comandi di sempre come comandi del faction, l'unico server che li eseguiva. La vecchia
-- colonna `commands` resta, aggiornata dal gestionale coi comandi del faction.
--
-- store_command_queue.server: il server che esegue quella riga (network.server-name di
-- MagixBridge). Le righe vecchie erano tutte del faction. La stessa colonna la aggiunge anche
-- MagixBridge 0.15.0 all'avvio.
--
-- Idempotente (IF NOT EXISTS di MariaDB, e l'UPDATE tocca solo i pacchetti non ancora convertiti).
ALTER TABLE store_packages
    ADD COLUMN IF NOT EXISTS server_commands TEXT NULL AFTER commands;

UPDATE store_packages
   SET server_commands = JSON_OBJECT('faction', commands)
 WHERE server_commands IS NULL AND commands IS NOT NULL AND TRIM(commands) <> '';

ALTER TABLE store_command_queue
    ADD COLUMN IF NOT EXISTS server VARCHAR(32) NOT NULL DEFAULT 'faction' AFTER mc_username,
    ADD INDEX IF NOT EXISTS idx_server_pending (server, executed_at, id);
