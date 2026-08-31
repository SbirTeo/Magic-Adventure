<?php
/**
 * Rientro da PayPal dopo l'approvazione. Qui NON si crede al browser: si richiama PayPal
 * per catturare davvero il pagamento, e solo se risulta incassato l'importo giusto
 * l'ordine diventa "paid" e i comandi vengono accodati per il server di gioco.
 */
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/paypal.php';

require_login();

$me = current_user();
$orderId = (int) ($_GET['order'] ?? 0);

$q = db()->prepare('SELECT * FROM store_orders WHERE id = ? AND user_id = ?');
$q->execute([$orderId, $me['id']]);
$order = $q->fetch();

$esito = 'errore';
if ($order && $order['status'] === 'paid') {
    $esito = 'gia_pagato';           // pagina ricaricata: niente doppia consegna
} elseif ($order && $order['status'] === 'pending' && $order['paypal_order_id']) {
    $cattura = paypal_capture_order($order);
    if ($cattura !== null && store_completa_ordine($order, $cattura)) {
        $esito = 'ok';
    } else {
        db()->prepare("UPDATE store_orders SET status = 'failed' WHERE id = ? AND status = 'pending'")->execute([$order['id']]);
    }
}

$page_title = 'Acquisto';
$active = 'store';
require __DIR__ . '/../../includes/header.php';
?>
<h1 class="page-title">Acquisto</h1>

<?php if ($esito === 'ok' || $esito === 'gia_pagato'): ?>
  <div class="alert alert-success">
    Pagamento completato. Grazie per il sostegno!
  </div>
  <div class="panel">
    <p><strong><?= h($order['package_name']) ?></strong> — <?= h(number_format((float) $order['price'], 2, ',', '.')) ?> <?= h($order['currency']) ?></p>
    <p style="color:var(--text-dim);">
      I vantaggi vengono consegnati in gioco entro pochi secondi, sull'account
      <strong><?= h($order['mc_username']) ?></strong>. Se in questo momento sei offline, entra pure:
      i comandi restano in coda finché non vengono eseguiti.
    </p>
    <a href="/store" class="btn btn-ghost">Torna allo store</a>
  </div>
<?php else: ?>
  <div class="alert alert-error">
    Non è stato possibile completare il pagamento. Se l'importo ti è stato addebitato,
    scrivi allo staff indicando l'orario: nessun ordine viene consegnato senza conferma di PayPal.
  </div>
  <a href="/store" class="btn btn-ghost">Torna allo store</a>
<?php endif; ?>

<?php require __DIR__ . '/../../includes/footer.php'; ?>
