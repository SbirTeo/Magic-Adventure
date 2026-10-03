<?php
/**
 * Portafoglio Magix del giocatore collegato, per la pagina dello store: la pagina lo
 * richiede ogni pochi secondi, cosi' il saldo resta quello vero anche mentre il giocatore
 * spende o riceve Magix in gioco (o un regalo dal sito).
 *
 *   GET              ->  {ok, balance, orders: [{id, kind, amount, label, meta}]}
 *   GET ?player=Nome ->  {ok, found, name, avatar, self}  (a chi si sta per regalare)
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
    if (isset($_GET['player'])) {
        $p = magix_find_player((string) $_GET['player']);
        echo json_encode($p === null ? ['ok' => true, 'found' => false] : [
            'ok' => true,
            'found' => true,
            'name' => $p['name'],
            'avatar' => mc_avatar_url($p['uuid'], 64, $p['premium_uuid']),
            'self' => strcasecmp($p['uuid'], (string) $me['mc_uuid']) === 0,
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }
    echo json_encode([
        'ok' => true,
        'balance' => magix_balance((string) $me['mc_uuid']),
        'orders' => magix_recent_orders((int) $me['id'], (string) $me['mc_uuid']),
    ], JSON_UNESCAPED_UNICODE);
} catch (Throwable $e) {
    error_log('api/magix: ' . $e->getMessage());
    http_response_code(503);
    echo json_encode(['ok' => false]);
}
