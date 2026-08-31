<?php
/**
 * API della ricerca nella guida: una domanda in italiano, la risposta presa dalla guida.
 *
 *   GET ?q=come si conquista un territorio            → guida dei giocatori (/tutorial)
 *   GET ?q=...&ambito=staff                           → guida per amministratori (gestionale)
 *
 * Il motore sta in includes/guida_ricerca.php e non chiama nessun servizio esterno: qui c'e'
 * solo il controllo di chi puo' chiedere cosa, il freno anti-martellamento e il JSON.
 *
 * L'ambito 'staff' e' riservato a chi entra nel gestionale: la guida dei plugin racconta
 * comandi e permessi dello staff, non e' roba da pagina pubblica.
 */
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/guida_ricerca.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

/** Domande al minuto per sessione: una persona che scrive non ci arriva mai, un copione si'. */
const GUIDA_IA_MAX_AL_MINUTO = 30;

function guida_api_json(array $dati, int $codice = 200): void {
    http_response_code($codice);
    echo json_encode($dati, JSON_UNESCAPED_UNICODE);
    exit;
}

$ambito = ($_GET['ambito'] ?? 'pubblica') === 'staff' ? 'staff' : 'pubblica';
$domanda = trim((string) ($_GET['q'] ?? ''));

if ($ambito === 'staff') {
    // Stesso cancello del gestionale: login piu' almeno un permesso web. Niente redirect —
    // qui si risponde in JSON, e chi chiama e' un copione, non un browser che naviga.
    if (!current_user() || !can_manage()) {
        guida_api_json(['ok' => false, 'nota' => 'Riservata allo staff.'], 403);
    }
}

// Il freno sta in sessione: basta per fermare la macchina impazzita senza dare fastidio a
// nessuno. La sessione si chiude subito dopo, se no il suo lucchetto metterebbe in fila le
// richieste della pagina con quelle della ricerca.
$adesso = time();
$recenti = array_values(array_filter((array) ($_SESSION['guida_ia'] ?? []), fn($t) => $t > $adesso - 60));
$troppe = count($recenti) >= GUIDA_IA_MAX_AL_MINUTO;
if (!$troppe) {
    $recenti[] = $adesso;
}
$_SESSION['guida_ia'] = $recenti;
session_write_close();

if ($troppe) {
    guida_api_json(['ok' => false, 'nota' => 'Troppe domande di fila. Riprova tra un minuto.'], 429);
}

if ($domanda === '') {
    guida_api_json(['ok' => false, 'nota' => 'Scrivi una domanda.'], 400);
}

guida_api_json(guida_ia_cerca($domanda, $ambito));
