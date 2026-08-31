-- =====================================================================
--  Sanzioni + Guida per amministratori
--  Disegno: plugins-src/MagixGuard/PROGETTO-SANZIONI.md e plugins-src/GUIDA-STAFF.md
--
--  Il SITO legge e mostra; a scrivere le sanzioni e' MagixGuard, unica penna.
--  Le uniche righe che nascono qui sul sito sono i RICORSI e le decisioni dello staff.
-- =====================================================================

-- ------------------------------------------------------------------
-- Le sanzioni. Una riga per provvedimento, storico compreso: quelle
-- scadute o revocate NON si cancellano, cambiano stato.
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sanzioni (
    id INT AUTO_INCREMENT PRIMARY KEY,
    mc_uuid CHAR(36) NOT NULL,
    mc_username VARCHAR(32) NOT NULL,
    tipo ENUM('warn','mute','ban','kick') NOT NULL,
    -- Categoria del disegno: chat.spam, chat.insulti, chat.pubblicita,
    -- chat.dati-personali, cheat.movimento, cheat.combat, cheat.xray,
    -- afk.elusione, report.confermato, manuale
    categoria VARCHAR(48) NOT NULL DEFAULT 'manuale',
    motivo VARCHAR(255) NOT NULL,
    -- Dove vale il provvedimento. 'entrambi' e' il predefinito: un ban e' un ban.
    ambito ENUM('gioco','sito','entrambi') NOT NULL DEFAULT 'entrambi',
    -- Punti accreditati da questa sanzione (registro a decadimento del plugin)
    punti INT NOT NULL DEFAULT 0,
    inizio DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- NULL = permanente
    fine DATETIME NULL DEFAULT NULL,
    -- NULL su staff_uuid + automatica=1 => l'ha decisa il plugin
    staff_uuid CHAR(36) NULL DEFAULT NULL,
    staff_nome VARCHAR(32) NULL DEFAULT NULL,
    automatica TINYINT(1) NOT NULL DEFAULT 0,
    stato ENUM('attiva','scaduta','revocata') NOT NULL DEFAULT 'attiva',
    revocata_da VARCHAR(32) NULL DEFAULT NULL,
    revocata_il DATETIME NULL DEFAULT NULL,
    revoca_motivo VARCHAR(255) NULL DEFAULT NULL,
    -- Una revoca decisa dal gestionale deve ancora arrivare al gioco: il plugin toglie il
    -- ban/mute e mette questo a 1. Finche' e' 0 con stato 'revocata', il lavoro e' a meta'
    -- (e il gestionale lo dice, invece di far credere che sia gia' fatto).
    revoca_applicata TINYINT(1) NOT NULL DEFAULT 1,
    -- Impronta SHA-256 del rapporto firmato: dimostra che il documento esisteva
    -- gia' in quella forma PRIMA della decisione (vedi /mg verify).
    rapporto_hash CHAR(64) NULL DEFAULT NULL,
    -- Versione PUBBLICA del rapporto (IP mascherati): e' quella che il sanzionato
    -- puo' leggere. Quella interna non esce mai dal plugin.
    rapporto_pubblico MEDIUMTEXT NULL DEFAULT NULL,
    creata_il DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_uuid (mc_uuid),
    KEY idx_stato (stato),
    KEY idx_tipo (tipo),
    KEY idx_creata (creata_il),
    KEY idx_fine (fine)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------------
-- I ricorsi. Uno per sanzione: la discussione continua dentro lo stesso,
-- non si aprono dieci ricorsi sullo stesso provvedimento.
-- Si svolge in privato; a decisione presa, `esito_pubblico` e' l'unica
-- parte che compare nell'elenco pubblico.
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sanzioni_ricorsi (
    id INT AUTO_INCREMENT PRIMARY KEY,
    sanzione_id INT NOT NULL,
    user_id INT NULL DEFAULT NULL,
    testo TEXT NOT NULL,
    stato ENUM('aperto','accolto','respinto') NOT NULL DEFAULT 'aperto',
    risposta TEXT NULL DEFAULT NULL,
    esito_pubblico VARCHAR(255) NULL DEFAULT NULL,
    staff_nome VARCHAR(32) NULL DEFAULT NULL,
    aperto_il DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deciso_il DATETIME NULL DEFAULT NULL,
    UNIQUE KEY uq_sanzione (sanzione_id),
    KEY idx_stato (stato)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------------
-- La coda di revisione: proposte che aspettano una conferma umana.
-- Ci finisce tutto cio' che l'automatismo non puo' decidere da solo
-- (modo 'misto') e tutto cio' che supera il tetto del grado di chi lo chiede.
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sanzioni_coda (
    id INT AUTO_INCREMENT PRIMARY KEY,
    mc_uuid CHAR(36) NOT NULL,
    mc_username VARCHAR(32) NOT NULL,
    tipo ENUM('warn','mute','ban','kick') NOT NULL,
    categoria VARCHAR(48) NOT NULL,
    motivo VARCHAR(255) NOT NULL,
    ambito ENUM('gioco','sito','entrambi') NOT NULL DEFAULT 'entrambi',
    -- NULL = permanente
    durata_secondi INT NULL DEFAULT NULL,
    punti INT NOT NULL DEFAULT 0,
    -- Chi l'ha proposta: grim, chat, xray, afk, report, staff
    fonte VARCHAR(48) NOT NULL DEFAULT 'staff',
    proposta_da VARCHAR(32) NULL DEFAULT NULL,
    -- Riassunto leggibile delle prove, gia' pronto per lo staff
    dettaglio MEDIUMTEXT NULL DEFAULT NULL,
    rapporto_hash CHAR(64) NULL DEFAULT NULL,
    stato ENUM('attesa','confermata','respinta') NOT NULL DEFAULT 'attesa',
    decisa_da VARCHAR(32) NULL DEFAULT NULL,
    decisa_il DATETIME NULL DEFAULT NULL,
    -- Riempito quando la conferma genera davvero la sanzione
    sanzione_id INT NULL DEFAULT NULL,
    creata_il DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_stato (stato),
    KEY idx_uuid (mc_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------------
-- Il blocco del regolamento generato dalla configurazione del plugin.
-- Una riga sola: la verita' e' sempre l'ultima. La pagina /regolamento
-- lo innesta al posto del segnaposto [[SANZIONI]].
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS regolamento_sanzioni (
    id TINYINT NOT NULL PRIMARY KEY DEFAULT 1,
    corpo_html MEDIUMTEXT NOT NULL,
    versione VARCHAR(32) NOT NULL DEFAULT '',
    aggiornato_il DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------------
-- Guida per amministratori: un capitolo per plugin, riscritto dal plugin
-- stesso a ogni avvio. Niente storico: se il server e' acceso, e' aggiornata.
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS guide_staff (
    plugin VARCHAR(64) NOT NULL PRIMARY KEY,
    titolo VARCHAR(160) NOT NULL,
    versione VARCHAR(32) NOT NULL DEFAULT '',
    ordine INT NOT NULL DEFAULT 100,
    corpo_html MEDIUMTEXT NOT NULL,
    aggiornata_il DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ------------------------------------------------------------------
-- Voce di menu per l'elenco pubblico. Si aggiunge in fondo e solo se non
-- c'e' gia': la migrazione si puo' rilanciare senza duplicare niente.
-- Se non la vuoi nel menu, basta spegnerla da "Pagine e menu".
-- ------------------------------------------------------------------
INSERT INTO nav_items (label, url, sort_order, enabled, show_sidebar)
SELECT 'Sanzioni', '/sanzioni', COALESCE(MAX(sort_order), 0) + 1, 1, 1
FROM nav_items
WHERE NOT EXISTS (SELECT 1 FROM (SELECT url FROM nav_items) AS n WHERE n.url = '/sanzioni');
