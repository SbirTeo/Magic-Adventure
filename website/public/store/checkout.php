<?php
/**
 * Avvio di una ricarica di Magix: crea l'ordine da noi, poi su PayPal, e manda il giocatore a
 * pagare. Dal browser arriva solo la QUANTITA': prezzo e sconto si ricalcolano qui
 * (magix_quote), quindi quello che si paga e' sempre quello che la pagina mostra.
 */
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/paypal.php';
require_once __DIR__ . '/../../includes/magix.php';

require_login();
csrf_check();

$me = current_user();
$quote = magix_quote((int) ($_POST['amount'] ?? 0));

if ($quote === null || !paypal_ready() || $quote['total'] <= 0 || trim((string) $me['mc_uuid']) === '') {
    redirect('/store?err=indisponibile');
}

// Senza la richiesta espressa di consegna immediata (e la rinuncia al recesso) l'acquisto non
// parte: il browser la chiede gia' (required), qui la si ricontrolla.
if (empty($_POST['terms'])) {
    redirect('/store?err=termini&q=' . $quote['amount']);
}

magix_ensure_tables();
$valuta = MAGIX_PAY_CURRENCY;

// terms_accepted_at: quando ha spuntato la casella dei termini (prova della rinuncia al recesso).
$ins = db()->prepare('INSERT INTO magix_orders (user_id, mc_uuid, mc_username, amount, discount_pct, price, currency, terms_accepted_at)
                      VALUES (?, ?, ?, ?, ?, ?, ?, NOW())');
$ins->execute([$me['id'], $me['mc_uuid'], $me['mc_username'], $quote['amount'], $quote['pct'], $quote['total'], $valuta]);
$orderId = (int) db()->lastInsertId();

$q = db()->prepare('SELECT * FROM magix_orders WHERE id = ?');
$q->execute([$orderId]);
$order = $q->fetch();

$paypal = paypal_create_order($order, $quote['amount'] . ' Magix');
if ($paypal === null) {
    db()->prepare("UPDATE magix_orders SET status = 'failed' WHERE id = ?")->execute([$orderId]);
    redirect('/store?err=paypal&q=' . $quote['amount']);
}

db()->prepare('UPDATE magix_orders SET paypal_order_id = ? WHERE id = ?')->execute([$paypal['id'], $orderId]);

header('Location: ' . $paypal['approve_url']);
exit;
