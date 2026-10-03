<?php
/**
 * Avvio di una ricarica di Magix: crea l'ordine da noi, poi su PayPal, e manda il giocatore a
 * pagare. Dal browser arriva solo la QUANTITA': prezzo e sconto si ricalcolano qui
 * (magix_quote), quindi quello che si paga e' sempre quello che la pagina mostra.
 *
 * Con for=gift e recipient=<nome> la ricarica e' un REGALO: paga chi e' collegato, i Magix
 * vanno al giocatore indicato (cercato di nuovo qui, il browser non decide a chi).
 */
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/paypal.php';
require_once __DIR__ . '/../../includes/magix.php';

require_login();
csrf_check();

$me = current_user();
$quote = magix_quote((int) ($_POST['amount'] ?? 0));
// Se si torna allo store per un errore, il regalo resta impostato com'era (nome compreso).
$perRegalo = ($_POST['for'] ?? 'me') === 'gift' ? '&per=' . rawurlencode(mb_substr(trim((string) ($_POST['recipient'] ?? '')), 0, 16)) : '';

if ($quote === null || !paypal_ready() || $quote['total'] <= 0 || trim((string) $me['mc_uuid']) === '') {
    redirect('/store?err=indisponibile');
}

// Senza la richiesta espressa di consegna immediata (e la rinuncia al recesso) l'acquisto non
// parte: il browser la chiede gia' (required), qui la si ricontrolla.
if (empty($_POST['terms'])) {
    redirect('/store?err=termini&q=' . $quote['amount'] . $perRegalo);
}

magix_ensure_tables();
$valuta = MAGIX_PAY_CURRENCY;

$destinatario = null;
if (($_POST['for'] ?? 'me') === 'gift') {
    $destinatario = magix_find_player((string) ($_POST['recipient'] ?? ''));
    if ($destinatario === null) {
        redirect('/store?err=destinatario&q=' . $quote['amount'] . $perRegalo);
    }
    // Regalarli a se stessi e' una ricarica normale.
    if (strcasecmp($destinatario['uuid'], (string) $me['mc_uuid']) === 0) {
        $destinatario = null;
    }
}

// terms_accepted_at: quando ha spuntato la casella dei termini (prova della rinuncia al recesso).
$ins = db()->prepare('INSERT INTO magix_orders (user_id, mc_uuid, mc_username, recipient_uuid, recipient_name, amount, discount_pct, price, currency, terms_accepted_at)
                      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())');
$ins->execute([$me['id'], $me['mc_uuid'], $me['mc_username'], $destinatario['uuid'] ?? null, $destinatario['name'] ?? null,
               $quote['amount'], $quote['pct'], $quote['total'], $valuta]);
$orderId = (int) db()->lastInsertId();

$q = db()->prepare('SELECT * FROM magix_orders WHERE id = ?');
$q->execute([$orderId]);
$order = $q->fetch();

$paypal = paypal_create_order($order, $quote['amount'] . ' Magix' . ($destinatario ? ' in regalo a ' . $destinatario['name'] : ''));
if ($paypal === null) {
    db()->prepare("UPDATE magix_orders SET status = 'failed' WHERE id = ?")->execute([$orderId]);
    redirect('/store?err=paypal&q=' . $quote['amount'] . $perRegalo);
}

db()->prepare('UPDATE magix_orders SET paypal_order_id = ? WHERE id = ?')->execute([$paypal['id'], $orderId]);

header('Location: ' . $paypal['approve_url']);
exit;
