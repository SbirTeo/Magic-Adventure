-- ============================================================================
-- MagixAuth: registrazione e login in gioco, sullo STESSO account del sito.
--
-- Non nasce nessuna anagrafica nuova: la password resta `users.password_hash`,
-- quella che il sito scrive gia' in set-password.php. Chi ha gia' fatto /link e'
-- percio' gia' registrato in gioco, e chi si registra in gioco puo' entrare sul
-- sito da subito. Qui si aggiungono solo le cose che il sito non aveva motivo di
-- sapere: da quale dispositivo si e' entrati, dove si trovava il giocatore, e se
-- il suo nome risulta appartenere a un account premium.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Annotazione premium
-- ---------------------------------------------------------------------------
-- Alla registrazione si chiede a Mojang se quel nome esiste come account premium.
-- L'UUID che risponde viene SOLO annotato: il giocatore continua a giocare col suo
-- UUID offline. Il motivo e' che i nomi brevi e comuni sono quasi tutti account
-- premium di estranei — assegnarne l'UUID vorrebbe dire riempire il database di
-- identita' altrui, e rendere insanabile il conflitto il giorno in cui il vero
-- proprietario si presenta. Quando ci sara' la verifica premium vera, l'annotazione
-- dira' gia' chi va migrato e su quali nomi aspettarsi una contesa.
ALTER TABLE users
    ADD COLUMN premium_uuid CHAR(36) NULL COMMENT 'UUID Mojang del nome, annotato ma NON usato in gioco',
    ADD COLUMN premium_controllato_il DATETIME NULL COMMENT 'Ultima interrogazione all API Mojang',
    ADD COLUMN registrato_in_gioco_il DATETIME NULL;

-- ---------------------------------------------------------------------------
-- 2. Dispositivi riconosciuti
-- ---------------------------------------------------------------------------
-- Superato il login, il giocatore non ridigita la password per la durata configurata
-- (24 ore) se torna dallo STESSO indirizzo.
--
-- Il primo tentativo usava un cookie depositato sul client, che in teoria e' un modo
-- migliore di riconoscere un computer. In pratica no: i cookie di Minecraft vivono nella
-- memoria del client e spariscono appena il giocatore chiude il gioco, quindi ogni ingresso
-- risultava fatto da un dispositivo mai visto e la password veniva chiesta sempre.
-- L'indirizzo cambia ogni tanto — e allora la password si ridigita, che e' giusto — ma fra
-- un ingresso e l'altro nella stessa giornata resta lo stesso.
CREATE TABLE IF NOT EXISTS auth_sessioni (
    mc_uuid CHAR(36) NOT NULL,
    dispositivo VARCHAR(64) NOT NULL COMMENT 'Indirizzo di rete: identifica da dove arriva, non autentica',
    ip VARCHAR(45) NOT NULL,
    password_ok_il DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    otp_ok_il DATETIME NULL COMMENT 'NULL = questo dispositivo non ha ancora passato il secondo fattore',
    scade_il DATETIME NOT NULL,
    PRIMARY KEY (mc_uuid, dispositivo),
    KEY idx_scadenza (scade_il)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 3. Dove si trovava il giocatore
-- ---------------------------------------------------------------------------
-- Chi non ha ancora fatto il login compare al punto di spawn, non dove si trovava:
-- cosi' chi entra col nick di un altro non ne legge le coordinate, e i chunk della
-- posizione vera non vengono nemmeno caricati finche' il login non e' superato.
--
-- La posizione va tenuta QUI e non nel file .dat del giocatore: se si disconnette
-- mentre e' fermo allo spawn, il server salverebbe lo spawn come sua ultima posizione
-- e quella vera sarebbe persa per sempre.
CREATE TABLE IF NOT EXISTS auth_posizioni (
    mc_uuid CHAR(36) NOT NULL PRIMARY KEY,
    mondo VARCHAR(64) NOT NULL,
    x DOUBLE NOT NULL,
    y DOUBLE NOT NULL,
    z DOUBLE NOT NULL,
    yaw FLOAT NOT NULL,
    pitch FLOAT NOT NULL,
    salvata_il DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 4. Tentativi falliti
-- ---------------------------------------------------------------------------
-- Il conteggio sta sull'indirizzo e non sul nome di proposito: bloccare per nome
-- vorrebbe dire che chiunque, sbagliando apposta la password di un altro, puo'
-- chiuderlo fuori dal server sapendone solo il nick.
CREATE TABLE IF NOT EXISTS auth_tentativi (
    ip VARCHAR(45) NOT NULL PRIMARY KEY,
    falliti INT NOT NULL DEFAULT 0,
    ultimo_il DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    bloccato_fino DATETIME NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
