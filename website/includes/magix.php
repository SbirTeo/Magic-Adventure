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
 * Prezzi e sconti stanno SOLO qui: la pagina li riceve da magix_config_js(), e il checkout
 * ricalcola il totale da magix_quote() senza mai fidarsi di quello che arriva dal browser.
 */

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/helpers.php';

const MAGIX_CURRENCY_ID = 'magix';
/** Valuta dei pagamenti: i prezzi qui sotto sono in euro. */
const MAGIX_PAY_CURRENCY = 'EUR';
const MAGIX_MIN = 1;
const MAGIX_MAX = 1000;
/** Prezzo pieno di un Magix, in euro. */
const MAGIX_PRICE = 0.10;
/** Livelli di sconto: da quanti Magix scatta (from) e quanto vale in percentuale (pct). */
const MAGIX_TIERS = [
    ['from' => 1,    'pct' => 0],
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
    db()->exec('CREATE TABLE IF NOT EXISTS me_currency_balances (
        currency_id VARCHAR(32) NOT NULL,
        player_uuid VARCHAR(36) NOT NULL,
        balance BIGINT NOT NULL DEFAULT 0,
        PRIMARY KEY (currency_id, player_uuid)
    )');
}

/** Saldo Magix di un giocatore (0 se non ne ha mai avuti). */
function magix_balance(string $mcUuid): int {
    magix_ensure_tables();
    $q = db()->prepare('SELECT balance FROM me_currency_balances WHERE currency_id = ? AND player_uuid = ?');
    $q->execute([MAGIX_CURRENCY_ID, $mcUuid]);
    $value = $q->fetchColumn();
    return $value === false ? 0 : (int) $value;
}

/** Le ultime ricariche pagate di un utente, la piu' recente per prima. */
function magix_recent_orders(int $userId, int $limit = 5): array {
    magix_ensure_tables();
    $q = db()->prepare("SELECT id, amount, price, currency, paid_at, paypal_capture_id FROM magix_orders
                        WHERE user_id = ? AND status = 'paid' ORDER BY paid_at DESC, id DESC LIMIT " . max(1, min(20, $limit)));
    $q->execute([$userId]);
    return $q->fetchAll();
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
        $credit->execute([MAGIX_CURRENCY_ID, $order['mc_uuid'], (int) $order['amount']]);
        $pdo->commit();
        return true;
    } catch (Throwable $e) {
        $pdo->rollBack();
        error_log('Store Magix: accredito fallito per l\'ordine ' . $order['id'] . ': ' . $e->getMessage());
        throw $e;
    }
}
