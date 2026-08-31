<?php
/**
 * Dialogo con PayPal (API Orders v2) e consegna di quanto acquistato.
 *
 * Il flusso e' interamente server-to-server per la parte che conta: il sito NON si fida
 * di quello che dice il browser al ritorno dal pagamento, ma richiama PayPal per catturare
 * l'ordine e legge da li' l'esito e l'importo davvero incassato.
 *
 * Credenziali e ambiente si configurano da /manage?section=store#pagamenti.
 */

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/helpers.php';

/** Base delle API: sandbox per le prove, live per i pagamenti veri. */
function paypal_api_base(): string {
    return site_setting('paypal_mode', 'sandbox') === 'live'
        ? 'https://api-m.paypal.com'
        : 'https://api-m.sandbox.paypal.com';
}

/** True se lo store puo' davvero incassare (attivo + credenziali presenti). */
function paypal_ready(): bool {
    return site_setting('paypal_enabled', '0') === '1'
        && trim(site_setting('paypal_client_id', '')) !== ''
        && trim(site_setting('paypal_secret', '')) !== '';
}

/**
 * Chiamata HTTP alle API PayPal. Ritorna [codice_http, corpo_decodificato].
 * Gli errori non vengono mai mostrati al giocatore: finiscono nel log del server.
 */
function paypal_request(string $method, string $path, ?array $body = null, ?string $token = null): array {
    $headers = ['Content-Type: application/json'];
    if ($token !== null) {
        $headers[] = 'Authorization: Bearer ' . $token;
    }

    $ch = curl_init(paypal_api_base() . $path);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CUSTOMREQUEST => $method,
        CURLOPT_HTTPHEADER => $headers,
        CURLOPT_TIMEOUT => 20,
    ]);
    if ($token === null) {
        curl_setopt($ch, CURLOPT_USERPWD, site_setting('paypal_client_id', '') . ':' . site_setting('paypal_secret', ''));
    }
    if ($body !== null) {
        curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($body));
    }

    $risposta = curl_exec($ch);
    $codice = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $erroreRete = curl_error($ch);
    curl_close($ch);

    if ($risposta === false) {
        error_log('PayPal: chiamata fallita (' . $path . '): ' . $erroreRete);
        return [0, null];
    }
    return [$codice, json_decode($risposta, true)];
}

/** Token di accesso OAuth (dura ore, ma qui ne chiediamo uno per operazione: sono poche). */
function paypal_access_token(): ?string {
    $ch = curl_init(paypal_api_base() . '/v1/oauth2/token');
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => 'grant_type=client_credentials',
        CURLOPT_USERPWD => site_setting('paypal_client_id', '') . ':' . site_setting('paypal_secret', ''),
        CURLOPT_HTTPHEADER => ['Content-Type: application/x-www-form-urlencoded'],
        CURLOPT_TIMEOUT => 20,
    ]);
    $risposta = curl_exec($ch);
    $codice = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    if ($codice !== 200 || !$risposta) {
        error_log('PayPal: token non ottenuto (http ' . $codice . ')');
        return null;
    }
    $dati = json_decode($risposta, true);
    return $dati['access_token'] ?? null;
}

/**
 * Crea l'ordine su PayPal e ritorna l'URL a cui mandare il giocatore per approvarlo.
 * $order e' la riga di store_orders gia' salvata come 'pending'.
 */
function paypal_create_order(array $order): ?array {
    $token = paypal_access_token();
    if ($token === null) {
        return null;
    }

    [$codice, $dati] = paypal_request('POST', '/v2/checkout/orders', [
        'intent' => 'CAPTURE',
        'purchase_units' => [[
            // Riferimento nostro: torna identico nella risposta, utile per i controlli
            'reference_id' => (string) $order['id'],
            'description' => mb_substr($order['package_name'], 0, 127),
            'custom_id' => (string) $order['id'],
            'amount' => [
                'currency_code' => $order['currency'],
                'value' => number_format((float) $order['price'], 2, '.', ''),
            ],
        ]],
        'application_context' => [
            'brand_name' => site_setting('site_name', 'MAGICADVENTURE'),
            'locale' => 'it-IT',
            'user_action' => 'PAY_NOW',
            'return_url' => SITE_URL . '/store/return?order=' . (int) $order['id'],
            'cancel_url' => SITE_URL . '/store/cancel?order=' . (int) $order['id'],
        ],
    ], $token);

    if ($codice !== 201 || empty($dati['id'])) {
        error_log('PayPal: creazione ordine fallita (http ' . $codice . '): ' . json_encode($dati));
        return null;
    }

    $approvazione = null;
    foreach ($dati['links'] ?? [] as $link) {
        if (($link['rel'] ?? '') === 'approve') {
            $approvazione = $link['href'];
        }
    }
    if ($approvazione === null) {
        return null;
    }
    return ['id' => $dati['id'], 'approve_url' => $approvazione];
}

/**
 * Cattura il pagamento. Ritorna l'id della cattura se e solo se PayPal conferma
 * COMPLETED e l'importo incassato coincide con quello dell'ordine (niente sorprese
 * se qualcuno prova a manomettere la richiesta).
 */
function paypal_capture_order(array $order): ?string {
    $token = paypal_access_token();
    if ($token === null) {
        return null;
    }

    [$codice, $dati] = paypal_request('POST', '/v2/checkout/orders/' . rawurlencode((string) $order['paypal_order_id']) . '/capture', [], $token);
    if (($codice !== 201 && $codice !== 200) || ($dati['status'] ?? '') !== 'COMPLETED') {
        error_log('PayPal: cattura fallita ordine ' . $order['id'] . ' (http ' . $codice . '): ' . json_encode($dati));
        return null;
    }

    $cattura = $dati['purchase_units'][0]['payments']['captures'][0] ?? null;
    if (!$cattura || ($cattura['status'] ?? '') !== 'COMPLETED') {
        return null;
    }

    $incassato = (float) ($cattura['amount']['value'] ?? 0);
    $valuta = $cattura['amount']['currency_code'] ?? '';
    if (abs($incassato - (float) $order['price']) > 0.001 || $valuta !== $order['currency']) {
        error_log('PayPal: importo diverso dall\'ordine ' . $order['id'] . ' (' . $incassato . ' ' . $valuta . ')');
        return null;
    }

    return (string) $cattura['id'];
}

/**
 * Segna l'ordine come pagato e ACCODA i comandi del pacchetto.
 * Idempotente: se l'ordine risulta gia' pagato non accoda niente una seconda volta
 * (il giocatore potrebbe ricaricare la pagina di ritorno).
 */
function store_completa_ordine(array $order, string $capturaId): bool {
    $aggiorna = db()->prepare("UPDATE store_orders SET status = 'paid', paypal_capture_id = ?, paid_at = NOW() WHERE id = ? AND status = 'pending'");
    $aggiorna->execute([$capturaId, $order['id']]);
    if ($aggiorna->rowCount() === 0) {
        return false; // gia' completato da una richiesta precedente
    }

    $q = db()->prepare('SELECT commands FROM store_packages WHERE id = ?');
    $q->execute([$order['package_id']]);
    $comandi = (string) $q->fetchColumn();

    $ins = db()->prepare('INSERT INTO store_command_queue (order_id, mc_uuid, mc_username, command) VALUES (?, ?, ?, ?)');
    foreach (explode("\n", $comandi) as $riga) {
        $riga = trim($riga);
        if ($riga === '') {
            continue;
        }
        $riga = ltrim($riga, '/');
        $riga = str_replace(['{player}', '{PLAYER}'], $order['mc_username'], $riga);
        $ins->execute([$order['id'], $order['mc_uuid'], $order['mc_username'], mb_substr($riga, 0, 500)]);
    }
    return true;
}
