<?php
/**
 * API della chat vocale (/voice): dà al browser il gettone per entrare in UNA stanza.
 *
 *   POST action=token room=<id> csrf=<token>
 *     -> {ok:true, url, token, room, name, canSpeak}  oppure  {ok:false, error}
 *   POST action=rooms csrf=<token>
 *     -> {ok:true, rooms:[{id, name}]}: le stanze di adesso. La pagina la chiede ogni tanto per
 *        seguire il giocatore quando cambia modalità (la stanza di prossimità è near-<server>).
 *
 * Le regole (chi entra dove, ban, mute) stanno in includes/voice.php. Qui solo: accesso fatto,
 * CSRF, stanza fra quelle permesse al giocatore adesso.
 */
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/voice.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

function voice_api_reply(array $dati, int $codice = 200): void {
    http_response_code($codice);
    echo json_encode($dati, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function voice_api_error(string $messaggio, int $codice = 400): void {
    voice_api_reply(['ok' => false, 'error' => $messaggio], $codice);
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    voice_api_error('Richiesta non valida.', 405);
}

$me = current_user();
$csrfSessione = $_SESSION['csrf'] ?? '';
session_write_close();

if (!$me) {
    voice_api_error('Accedi con il tuo account di gioco per usare la chat vocale.', 401);
}
if ($csrfSessione === '' || !hash_equals($csrfSessione, (string) ($_POST['csrf'] ?? ''))) {
    voice_api_error('Sessione scaduta: ricarica la pagina.', 400);
}
if (!voice_ready()) {
    voice_api_error('La chat vocale non è ancora attiva.', 503);
}
$azione = (string) ($_POST['action'] ?? '');
if ($azione !== 'token' && $azione !== 'rooms') {
    voice_api_error('Richiesta non valida.');
}

$sanzioni = voice_sanctions((string) $me['mc_uuid']);
if ($sanzioni['ban'] !== null) {
    voice_api_error('Hai un ban attivo: la chat vocale non è disponibile.', 403);
}

if ($azione === 'rooms') {
    voice_api_reply([
        'ok'    => true,
        'rooms' => array_map(fn ($s) => ['id' => $s['id'], 'name' => $s['name']], voice_rooms($me)),
    ]);
}

$richiesta = (string) ($_POST['room'] ?? '');
$stanza = null;
foreach (voice_rooms($me) as $s) {
    if ($s['id'] === $richiesta) {
        $stanza = $s;
        break;
    }
}
if ($stanza === null) {
    voice_api_error('Non puoi entrare in questa stanza.', 403);
}

$puoParlare = $sanzioni['mute'] === null;
$extra = ['avatar' => mc_avatar_url($me['mc_uuid'], 64, $me['premium_uuid'] ?? null)];

voice_api_reply([
    'ok'       => true,
    'url'      => voice_url(),
    'token'    => voice_token($me, $stanza['id'], $puoParlare, $extra),
    'room'     => $stanza['id'],
    'name'     => $stanza['name'],
    'canSpeak' => $puoParlare,
]);
