<?php
/** Il giocatore ha annullato su PayPal: l'ordine resta senza consegna. */
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';

require_login();

$me = current_user();
$orderId = (int) ($_GET['order'] ?? 0);
db()->prepare("UPDATE store_orders SET status = 'cancelled' WHERE id = ? AND user_id = ? AND status = 'pending'")
    ->execute([$orderId, $me['id']]);

$page_title = 'Acquisto annullato';
$active = 'store';
require __DIR__ . '/../../includes/header.php';
?>
<h1 class="page-title">Acquisto annullato</h1>
<div class="panel">
  <p>Non è stato addebitato nulla. Puoi riprovare quando vuoi.</p>
  <a href="/store" class="btn btn-ghost">Torna allo store</a>
</div>
<?php require __DIR__ . '/../../includes/footer.php'; ?>
