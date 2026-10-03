<?php
/**
 * Portafoglio Magix del giocatore collegato, per la pagina dello store: la pagina lo
 * richiede ogni pochi secondi, cosi' il saldo resta quello vero anche mentre il giocatore
 * spende o riceve Magix in gioco.
 *
 *   GET  ->  {ok, balance, orders: [{amount, price, paid_at, ago}]}
 *
 * Risponde sempre in JSON; senza accesso al sito: 401.
 */
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/magix.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

$me = current_user();
if (!$me || trim((string) $me['mc_uuid']) === '') {
    http_response_code(401);
    echo json_encode(['ok' => false]);
    exit;
}

try {
    $orders = array_map(fn($o) => [
        'id' => (int) $o['id'],
        'amount' => (int) $o['amount'],
        'price' => magix_euro((float) $o['price']),
        'ago' => $o['paid_at'] ? time_ago($o['paid_at']) : '',
    ], magix_recent_orders((int) $me['id']));
    echo json_encode(['ok' => true, 'balance' => magix_balance((string) $me['mc_uuid']), 'orders' => $orders], JSON_UNESCAPED_UNICODE);
} catch (Throwable $e) {
    error_log('api/magix: ' . $e->getMessage());
    http_response_code(503);
    echo json_encode(['ok' => false]);
}
