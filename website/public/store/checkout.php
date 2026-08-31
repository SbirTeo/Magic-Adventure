<?php
/**
 * Avvio dell'acquisto: crea l'ordine da noi, poi su PayPal, e manda il giocatore a pagare.
 * Il prezzo NON arriva mai dal browser: si rilegge sempre dal database.
 */
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/paypal.php';
require_once __DIR__ . '/../../includes/store_card.php';

require_login();
csrf_check();

$me = current_user();
$slug = trim($_POST['package'] ?? '');

$q = db()->prepare('SELECT * FROM store_packages WHERE slug = ? AND enabled = 1');
$q->execute([$slug]);
$pkg = $q->fetch();

// Il prezzo NON arriva mai dal browser e tiene conto degli sconti (pacchetto, categoria,
// store): quello che si paga e' esattamente quello che il sito mostra.
$prezzo = $pkg ? store_prezzo($pkg)['finale'] : 0;

if (!$pkg || !paypal_ready() || $prezzo <= 0) {
    redirect('/store?err=indisponibile');
}

$valuta = site_setting('store_currency', 'EUR');

$ins = db()->prepare('INSERT INTO store_orders (user_id, package_id, package_name, mc_uuid, mc_username, price, currency) VALUES (?, ?, ?, ?, ?, ?, ?)');
$ins->execute([$me['id'], $pkg['id'], $pkg['name'], $me['mc_uuid'], $me['mc_username'], $prezzo, $valuta]);
$orderId = (int) db()->lastInsertId();

$q = db()->prepare('SELECT * FROM store_orders WHERE id = ?');
$q->execute([$orderId]);
$order = $q->fetch();

$paypal = paypal_create_order($order);
if ($paypal === null) {
    db()->prepare("UPDATE store_orders SET status = 'failed' WHERE id = ?")->execute([$orderId]);
    redirect('/store?err=paypal');
}

db()->prepare('UPDATE store_orders SET paypal_order_id = ? WHERE id = ?')->execute([$paypal['id'], $orderId]);

header('Location: ' . $paypal['approve_url']);
exit;
