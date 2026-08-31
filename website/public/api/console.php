<?php
/**
 * API della scheda "Server" del gestionale (console e screen del VPS).
 *
 * Il browser la chiama come /api/console (senza .php): nginx manda i .php chiesti in
 * GET al loro indirizzo pulito con un 301, e ogni giro di aggiornamento costerebbe
 * un viaggio in piu.
 *
 * Risponde SEMPRE in JSON: la scheda si aggiorna da sola, senza ricaricare la pagina,
 * perche' accendere o spegnere un server richiede decine di secondi e nel frattempo si
 * deve continuare a leggere la console.
 *
 *   GET  ?azione=stato                       istanze + screen, con giocatori online
 *   GET  ?azione=log&id=<id>&righe=<n>       ultime righe di console dell'istanza
 *   GET  ?azione=screen-log&sessione=<s>     copia di una screen non configurata
 *   POST azione=avvia|ferma|riavvia|forza    + id
 *   POST azione=cmd                          + id + comando
 *   POST azione=screen-cmd                   + sessione + comando
 *   POST azione=screen-chiudi                + sessione
 *
 * Tutto e' riservato al web-admin: e' l'unico posto del sito da cui si comanda la macchina.
 */
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/console.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

/** Un comando di console piu' lungo di cosi' non e' un comando. */
const CONSOLE_MAX_COMANDO = 400;

function console_json(array $dati, int $codice = 200): void {
    http_response_code($codice);
    echo json_encode($dati, JSON_UNESCAPED_UNICODE);
    exit;
}

function console_errore(string $messaggio, int $codice = 400): void {
    console_json(['ok' => false, 'error' => $messaggio], $codice);
}

$me = current_user();
if (!$me || (int) $me['is_admin'] !== 1) {
    console_errore('Sezione riservata al web-admin.', 403);
}
$attore = (string) ($me['mc_username'] ?? '?');

// Come nella chat: la sessione serve solo per sapere chi sei e per il token, e va chiusa
// subito, altrimenti il suo lock metterebbe in fila le richieste di aggiornamento.
$csrfSessione = $_SESSION['csrf'] ?? '';
session_write_close();

// ---------------------------------------------------------------------
// Lettura
// ---------------------------------------------------------------------
if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    $azione = $_GET['azione'] ?? 'stato';

    if ($azione === 'stato') {
        $stato = console_stato();
        // Giocatori collegati: si chiede al server stesso (Server List Ping) e solo se e'
        // acceso, cosi' non si aspetta un timeout inutile a ogni aggiornamento.
        foreach ($stato['istanze'] as &$ist) {
            $ist['giocatori'] = null;
            $ist['giocatori_max'] = null;
            if ($ist['accesa'] && $ist['porta']) {
                $ping = mc_server_status('127.0.0.1', $ist['porta'], 0.8);
                if ($ping) {
                    $ist['giocatori'] = $ping['players_online'];
                    $ist['giocatori_max'] = $ping['players_max'];
                }
            }
            $ist['risponde'] = $ist['giocatori'] !== null;
        }
        unset($ist);
        console_json($stato);
    }

    if ($azione === 'log' || $azione === 'screen-log') {
        $righe = max(50, min(1000, (int) ($_GET['righe'] ?? 300)));
        if ($azione === 'log') {
            $id = (string) ($_GET['id'] ?? '');
            if ($id === '') {
                console_errore('Manca il server da leggere.');
            }
            $r = console_esegui('log', [$id, $righe], 20);
        } else {
            $sessione = (string) ($_GET['sessione'] ?? '');
            if ($sessione === '') {
                console_errore('Manca la screen da leggere.');
            }
            $r = console_esegui('screen-log', [$sessione, $righe], 20);
        }
        if (!$r['ok']) {
            console_errore($r['err'] ?: 'Console non leggibile.');
        }
        $testo = rtrim($r['out'], "\n");
        // La firma serve al browser per non ridisegnare (e non far saltare lo scorrimento)
        // quando dall'ultimo giro non e' cambiato niente.
        console_json(['ok' => true, 'testo' => $testo, 'firma' => md5($testo)]);
    }

    console_errore('Richiesta non riconosciuta.');
}

// ---------------------------------------------------------------------
// Azioni
// ---------------------------------------------------------------------
if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    console_errore('Metodo non ammesso.', 405);
}
if ($csrfSessione === '' || !hash_equals($csrfSessione, (string) ($_POST['csrf'] ?? ''))) {
    console_errore('Richiesta non valida (CSRF). Ricarica la pagina.', 400);
}

$azione = (string) ($_POST['azione'] ?? '');
$id = (string) ($_POST['id'] ?? '');
$sessione = (string) ($_POST['sessione'] ?? '');
$comando = trim((string) ($_POST['comando'] ?? ''));

switch ($azione) {
    case 'avvia':
    case 'ferma':
    case 'riavvia':
    case 'forza': {
        if ($id === '') {
            console_errore('Manca il server.');
        }
        $r = console_esegui($azione, [$attore, $id], 30);
        break;
    }

    case 'cmd': {
        if ($id === '' || $comando === '') {
            console_errore('Comando vuoto.');
        }
        if (mb_strlen($comando) > CONSOLE_MAX_COMANDO) {
            console_errore('Comando troppo lungo.');
        }
        // Lo "stop" scritto a mano nella console fa ripartire il server da solo (start.sh
        // ha il ciclo di riavvio): chi vuole spegnerlo davvero deve usare il pulsante.
        $r = console_esegui('cmd', [$attore, $id, $comando], 20);
        break;
    }

    case 'screen-cmd': {
        if ($sessione === '' || $comando === '') {
            console_errore('Comando vuoto.');
        }
        if (mb_strlen($comando) > CONSOLE_MAX_COMANDO) {
            console_errore('Comando troppo lungo.');
        }
        $r = console_esegui('screen-cmd', [$attore, $sessione, $comando], 20);
        break;
    }

    case 'screen-chiudi': {
        if ($sessione === '') {
            console_errore('Manca la screen.');
        }
        $r = console_esegui('screen-chiudi', [$attore, $sessione], 20);
        break;
    }

    default:
        console_errore('Azione non riconosciuta.');
}

if (!$r['ok']) {
    console_errore($r['err'] ?: 'Operazione non riuscita.');
}
console_json(['ok' => true, 'messaggio' => trim($r['out']) ?: 'Fatto.']);
