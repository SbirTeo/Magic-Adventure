<?php
/**
 * Rientro da PayPal dopo l'approvazione. Qui NON si crede al browser: si richiama PayPal per
 * catturare davvero il pagamento, e solo se risulta incassato l'importo giusto l'ordine
 * diventa "paid" e i Magix finiscono nel saldo (magix_complete_order, una transazione sola).
 *
 * L'id della cattura si salva PRIMA dell'accredito: se l'accredito non riesce (database giu'
 * per un attimo), ricaricando questa pagina si riprova solo l'accredito, senza richiedere a
 * PayPal una cattura che e' gia' avvenuta.
 *
 * Alla fine si torna allo store, che mostra l'esito e fa arrivare i Magix nel portafoglio.
 */
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/paypal.php';
require_once __DIR__ . '/../../includes/magix.php';

require_login();
magix_ensure_tables();

$me = current_user();
$orderId = (int) ($_GET['order'] ?? 0);

$load = function () use ($orderId, $me) {
    $q = db()->prepare('SELECT * FROM magix_orders WHERE id = ? AND user_id = ?');
    $q->execute([$orderId, $me['id']]);
    return $q->fetch() ?: null;
};
$order = $load();

$outcome = 'errore';
if ($order && $order['status'] === 'paid') {
    $outcome = 'ok';                      // pagina ricaricata: niente doppio accredito
} elseif ($order && $order['status'] === 'pending') {
    $capture = $order['paypal_capture_id'] ?: null;
    if ($capture === null && $order['paypal_order_id']) {
        $capture = paypal_capture_order($order);
        if ($capture !== null) {
            db()->prepare("UPDATE magix_orders SET paypal_capture_id = ? WHERE id = ? AND status = 'pending'")
                ->execute([$capture, $order['id']]);
        }
    }
    if ($capture !== null) {
        try {
            magix_complete_order($order, $capture);
            $order = $load();
            $outcome = ($order && $order['status'] === 'paid') ? 'ok' : 'accredito';
        } catch (Throwable $e) {
            $outcome = 'accredito';       // pagato ma non ancora accreditato: si riprova ricaricando
        }
    } else {
        db()->prepare("UPDATE magix_orders SET status = 'failed' WHERE id = ? AND status = 'pending'")->execute([$order['id']]);
    }
}

redirect('/store?ricarica=' . $orderId . '&esito=' . $outcome);
