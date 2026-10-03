<?php
/** Il giocatore ha annullato su PayPal: l'ordine resta senza accredito e si torna allo store. */
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/magix.php';

require_login();
magix_ensure_tables();

$me = current_user();
$orderId = (int) ($_GET['order'] ?? 0);
db()->prepare("UPDATE magix_orders SET status = 'cancelled' WHERE id = ? AND user_id = ? AND status = 'pending'")
    ->execute([$orderId, $me['id']]);

$q = db()->prepare('SELECT amount FROM magix_orders WHERE id = ? AND user_id = ?');
$q->execute([$orderId, $me['id']]);
$amount = (int) ($q->fetchColumn() ?: 0);

redirect('/store?err=annullato' . ($amount > 0 ? '&q=' . $amount : ''));
