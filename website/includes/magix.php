<?php
/**
 * I Magix: la valuta di rete del server, in vendita sul sito.
 *
 * Lo store vende UNA cosa sola: una quantita' di Magix scelta col cursore (da MAGIX_MIN a
 * MAGIX_MAX), a MAGIX_PRICE l'uno, con uno sconto che cresce con la quantita' (MAGIX_TIERS).
 * I pacchetti VIP non si comprano piu' qui: si comprano in gioco, spendendo i Magix.
 *
 * Il saldo e' quello del gioco: la valuta "magix" di MagixEssentials e' condivisa (shared: true
 * in currencies.yml), quindi vive nella tabella me_currency_balances di questo stesso database,
 * un saldo per giocatore (UUID di gioco = users.mc_uuid). Il plugin la rilegge a ogni
 * operazione, senza copie in memoria: un accredito scritto qui vale subito su tutta la rete.
 *
 * Una ricarica si puo' anche REGALARE: chi paga e' sempre l'utente collegato (user_id), i Magix
 * vanno al giocatore indicato (recipient_uuid/recipient_name, vuoti = a se stesso).
 *
 * Prezzi e sconti stanno SOLO qui: la pagina li riceve da magix_config_js(), e il checkout
 * ricalcola il totale da magix_quote() senza mai fidarsi di quello che arriva dal browser.
 */

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/helpers.php';

const MAGIX_CURRENCY_ID = 'magix';
/** Valuta dei pagamenti: i prezzi qui sotto sono in euro. */
const MAGIX_PAY_CURRENCY = 'EUR';
const MAGIX_MIN = 10;
const MAGIX_MAX = 1000;
/** Prezzo pieno di un Magix, in euro. */
const MAGIX_PRICE = 0.10;
/** Livelli di sconto: da quanti Magix scatta (from) e quanto vale in percentuale (pct). */
const MAGIX_TIERS = [
    ['from' => MAGIX_MIN, 'pct' => 0],
    ['from' => 100,  'pct' => 5],
    ['from' => 250,  'pct' => 10],
    ['from' => 500,  'pct' => 15],
    ['from' => 750,  'pct' => 20],
    ['from' => 1000, 'pct' => 25],
];

/** Sconto (in percentuale) per una quantita'. */
function magix_discount(int $amount): int {
    $pct = 0;
    foreach (MAGIX_TIERS as $tier) {
        if ($amount >= $tier['from']) {
            $pct = $tier['pct'];
        }
    }
    return $pct;
}

/**
 * Preventivo per una quantita': prezzo pieno, sconto e totale da pagare (arrotondato al
 * centesimo). Null se la quantita' e' fuori dai limiti.
 */
function magix_quote(int $amount): ?array {
    if ($amount < MAGIX_MIN || $amount > MAGIX_MAX) {
        return null;
    }
    $pct = magix_discount($amount);
    // Conti in centesimi interi, arrotondati al centesimo piu' vicino: store-magix.js fa
    // esattamente la stessa divisione, cosi' pagina e cassa non differiscono mai di un centesimo.
    $fullCents = (int) round($amount * MAGIX_PRICE * 100);
    $totalCents = intdiv($fullCents * (100 - $pct) + 50, 100);
    return ['amount' => $amount, 'pct' => $pct, 'full' => $fullCents / 100, 'total' => $totalCents / 100];
}

/** Quello che serve al copione della pagina per fare gli stessi conti del server. */
function magix_config_js(): array {
    return [
        'min' => MAGIX_MIN,
        'max' => MAGIX_MAX,
        'price' => MAGIX_PRICE,
        'tiers' => MAGIX_TIERS,
        'currency' => MAGIX_PAY_CURRENCY,
    ];
}

/** Importo in euro come lo legge un italiano: 22,50 €. */
function magix_euro(float $value): string {
    return number_format($value, 2, ',', '.') . ' €';
}

/**
 * Tabelle che servono allo store. Create qui (IF NOT EXISTS) oltre che dalla migrazione
 * 2026-10-03-store-magix.sql, cosi' la pagina funziona anche se la migrazione non e' ancora
 * stata lanciata. me_currency_balances ha la stessa forma di quella che crea MagixEssentials
 * (currency/db/Database.java): se il plugin l'ha gia' creata non cambia niente.
 */
function magix_ensure_tables(): void {
    static $done = false;
    if ($done) {
        return;
    }
    $done = true;
    db()->exec('CREATE TABLE IF NOT EXISTS magix_orders (
        id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
        user_id INT UNSIGNED NOT NULL,
        mc_uuid VARCHAR(36) NOT NULL,
        mc_username VARCHAR(32) NOT NULL,
        amount INT UNSIGNED NOT NULL,
        discount_pct TINYINT UNSIGNED NOT NULL DEFAULT 0,
        price DECIMAL(10,2) NOT NULL,
        currency CHAR(3) NOT NULL DEFAULT \'EUR\',
        status ENUM(\'pending\',\'paid\',\'failed\',\'cancelled\') NOT NULL DEFAULT \'pending\',
        paypal_order_id VARCHAR(64) NULL DEFAULT NULL,
        paypal_capture_id VARCHAR(64) NULL DEFAULT NULL,
        terms_accepted_at DATETIME NULL DEFAULT NULL,
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        paid_at DATETIME NULL DEFAULT NULL,
        KEY idx_magix_orders_user (user_id, status),
        KEY idx_magix_orders_paid (status, paid_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci');
    // I regali sono arrivati dopo la tabella (migrazione 2026-10-03-magix-regali.sql): le colonne
    // si aggiungono qui se mancano, controllando prima, cosi' non si tocca la tabella a ogni pagina.
    if (!db()->query("SHOW COLUMNS FROM magix_orders LIKE 'recipient_uuid'")->fetch()) {
        db()->exec('ALTER TABLE magix_orders
            ADD COLUMN IF NOT EXISTS recipient_uuid VARCHAR(36) NULL DEFAULT NULL AFTER mc_username,
            ADD COLUMN IF NOT EXISTS recipient_name VARCHAR(32) NULL DEFAULT NULL AFTER recipient_uuid,
            ADD INDEX IF NOT EXISTS idx_magix_orders_recipient (recipient_uuid, status)');
    }
    // Cosa si compra in gioco con i Magix: lo elenco lo scrive lo staff (Gestione -> Store) e lo
    // store lo mostra sotto il cursore ("Cosa puoi comprare con N Magix").
    db()->exec('CREATE TABLE IF NOT EXISTS magix_catalog (
        id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
        name VARCHAR(40) NOT NULL,
        cost INT UNSIGNED NOT NULL,
        note VARCHAR(80) NOT NULL DEFAULT \'\',
        color CHAR(7) NOT NULL DEFAULT \'#c04ff0\',
        sort_order INT NOT NULL DEFAULT 0,
        enabled TINYINT(1) NOT NULL DEFAULT 1
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci');
    db()->exec('CREATE TABLE IF NOT EXISTS me_currency_balances (
        currency_id VARCHAR(32) NOT NULL,
        player_uuid VARCHAR(36) NOT NULL,
        balance BIGINT NOT NULL DEFAULT 0,
        PRIMARY KEY (currency_id, player_uuid)
    )');
}

/**
 * Le voci del catalogo (cosa si compra in gioco con i Magix), nell'ordine scelto dallo staff
 * (frecce in Gestione -> Store; a parita', dalla meno cara). Con $all anche
 * quelle spente (per il gestionale).
 */
function magix_catalog(bool $all = false): array {
    magix_ensure_tables();
    return db()->query('SELECT id, name, cost, note, color, sort_order, enabled FROM magix_catalog'
        . ($all ? '' : ' WHERE enabled = 1') . ' ORDER BY sort_order, cost, id')->fetchAll();
}

/** Saldo Magix di un giocatore (0 se non ne ha mai avuti). */
function magix_balance(string $mcUuid): int {
    magix_ensure_tables();
    $q = db()->prepare('SELECT balance FROM me_currency_balances WHERE currency_id = ? AND player_uuid = ?');
    $q->execute([MAGIX_CURRENCY_ID, $mcUuid]);
    $value = $q->fetchColumn();
    return $value === false ? 0 : (int) $value;
}

/**
 * Un giocatore a cui si possono regalare Magix, cercato per nome (quello che usa in gioco,
 * maiuscole e minuscole non contano): ['uuid', 'name', 'premium_uuid'] oppure null.
 *
 * Prima fra gli account del sito, poi fra chi e' entrato in gioco (mc_ranks, lo scrive
 * MagixBridge a ogni ingresso): l'UUID e' sempre quello di gioco, lo stesso del saldo.
 */
function magix_find_player(string $name): ?array {
    $name = trim($name);
    if (!preg_match('/^[A-Za-z0-9_]{2,16}$/', $name)) {
        return null;
    }
    $q = db()->prepare('SELECT mc_uuid, mc_username, premium_uuid FROM users WHERE mc_username = ? AND mc_uuid <> \'\' LIMIT 1');
    $q->execute([$name]);
    $row = $q->fetch();
    if (!$row) {
        try {
            $q = db()->prepare('SELECT mc_uuid, mc_username, NULL AS premium_uuid FROM mc_ranks WHERE mc_username = ? LIMIT 1');
            $q->execute([$name]);
            $row = $q->fetch();
        } catch (PDOException $e) {
            $row = false;
        }
    }
    return $row ? ['uuid' => (string) $row['mc_uuid'], 'name' => (string) $row['mc_username'],
                   'premium_uuid' => $row['premium_uuid'] ?? null] : null;
}

/**
 * Gli ultimi movimenti del sito di un giocatore, il piu' recente per primo: le sue ricariche,
 * i regali che ha fatto e quelli che ha ricevuto. Ogni riga e' gia' pronta da mostrare
 * (magix_order_row), uguale nella pagina e in /api/magix.
 */
function magix_recent_orders(int $userId, string $mcUuid, int $limit = 5): array {
    magix_ensure_tables();
    $q = db()->prepare("SELECT id, user_id, mc_username, recipient_uuid, recipient_name, amount, price, paid_at
                        FROM magix_orders
                        WHERE status = 'paid' AND (user_id = ? OR recipient_uuid = ?)
                        ORDER BY paid_at DESC, id DESC LIMIT " . max(1, min(20, $limit)));
    $q->execute([$userId, $mcUuid]);
    return array_map(fn($o) => magix_order_row($o, $userId), $q->fetchAll());
}

/**
 * Una riga del portafoglio: kind = 'self' (ricarica per se'), 'sent' (regalo fatto, i Magix
 * sono andati a un altro) o 'received' (regalo ricevuto: il prezzo non si mostra, e' un fatto
 * di chi ha pagato).
 */
function magix_order_row(array $o, int $userId): array {
    $mine = (int) $o['user_id'] === $userId;
    $gift = trim((string) ($o['recipient_uuid'] ?? '')) !== '';
    $kind = !$gift ? 'self' : ($mine ? 'sent' : 'received');
    $ago = $o['paid_at'] ? time_ago((string) $o['paid_at']) : '';
    $labels = [
        'self' => 'Ricarica sul sito',
        'sent' => 'Regalo a ' . $o['recipient_name'],
        'received' => 'Regalo da ' . $o['mc_username'],
    ];
    $meta = $kind === 'received' ? $ago : magix_euro((float) $o['price']) . ($ago !== '' ? ' · ' . $ago : '');
    return ['id' => (int) $o['id'], 'kind' => $kind, 'amount' => (int) $o['amount'],
            'label' => $labels[$kind], 'meta' => $meta];
}

/**
 * Segna l'ordine come pagato e accredita i Magix, nella stessa transazione: o succedono
 * tutte e due le cose o nessuna. Idempotente: un ordine gia' pagato non accredita una seconda
 * volta (il giocatore puo' ricaricare la pagina di ritorno da PayPal).
 *
 * L'accredito e' un'unica istruzione (balance = balance + N), quindi non si perde nemmeno se
 * nello stesso istante il plugin sta scrivendo il saldo dello stesso giocatore.
 */
function magix_complete_order(array $order, string $captureId): bool {
    magix_ensure_tables();
    $pdo = db();
    $pdo->beginTransaction();
    try {
        $upd = $pdo->prepare("UPDATE magix_orders SET status = 'paid', paypal_capture_id = ?, paid_at = NOW() WHERE id = ? AND status = 'pending'");
        $upd->execute([$captureId, $order['id']]);
        if ($upd->rowCount() === 0) {
            $pdo->rollBack();
            return false;
        }
        $credit = $pdo->prepare('INSERT INTO me_currency_balances (currency_id, player_uuid, balance) VALUES (?, ?, ?)
                                 ON DUPLICATE KEY UPDATE balance = balance + VALUES(balance)');
        // Un regalo va al destinatario; altrimenti a chi ha pagato.
        $to = trim((string) ($order['recipient_uuid'] ?? '')) !== '' ? $order['recipient_uuid'] : $order['mc_uuid'];
        $credit->execute([MAGIX_CURRENCY_ID, $to, (int) $order['amount']]);
        $pdo->commit();
        return true;
    } catch (Throwable $e) {
        $pdo->rollBack();
        error_log('Store Magix: accredito fallito per l\'ordine ' . $order['id'] . ': ' . $e->getMessage());
        throw $e;
    }
}
