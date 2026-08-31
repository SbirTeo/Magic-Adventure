<?php
/**
 * API dell'editor dei menu (Gestione sito -> scheda "Menu").
 *
 * Il browser la chiama come /api/menu. Risponde sempre in JSON.
 *
 *   GET  ?azione=elenco              tutti i menu come li ha capiti il plugin (menus.json)
 *   GET  ?azione=yaml&nome=<n>       il file .yml com'e' scritto, per chi vuole vederlo
 *   POST azione=salva                + nome + menu (JSON)   scrive il .yml
 *   POST azione=applica              manda "magixmenus reload" alla console
 *   POST azione=elimina              + nome                 mette il file da parte
 *
 * <h3>Chi legge e chi scrive</h3>
 * Il sito non interpreta gli .yml: quelli li legge il plugin, che pubblica il risultato in
 * menus.json. Qui si SCRIVE soltanto (vedi includes/menu_yaml.php per il perche').
 *
 * <h3>Come si arriva ai file</h3>
 * Mai direttamente: sempre attraverso magix-console, lo stesso programma di sistema che serve
 * la console dei server. E' l'unico punto in cui e' scritto cosa il sito puo' fare alla macchina,
 * e i menu sono una cartella sola dentro quel poco.
 *
 * Tutto e' riservato al web-admin, come la scheda Server.
 */
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/console.php';
require_once __DIR__ . '/../../includes/menu_yaml.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

/**
 * L'istanza su cui vivono i menu.
 *
 * E' l'id scritto in /etc/magicadventure/istanze.conf. Il giorno in cui i menu dovranno vivere
 * anche su una lobby o su un mondo eventi, questa diventa una scelta nell'interfaccia: finche'
 * il server e' uno solo, chiederlo a ogni salvataggio sarebbe una domanda senza scelta.
 */
const MENU_ISTANZA = 'mc';

/** Un menu piu' grande di cosi' non e' un menu (lo stesso limite del ponte). */
const MENU_MAX_BYTE = 262144;

function menu_json(array $dati, int $codice = 200): void
{
    http_response_code($codice);
    echo json_encode($dati, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function menu_errore(string $messaggio, int $codice = 400): void
{
    menu_json(['ok' => false, 'error' => $messaggio], $codice);
}

/** Il nome del menu diventa un nome di file: si accetta solo cio' che accetta anche il plugin. */
function menu_nome(string $n): string
{
    $n = strtolower(trim($n));
    if (!preg_match('/^[a-z0-9_-]{1,40}$/', $n)) {
        menu_errore('Nome non valido: usa solo lettere minuscole, numeri, trattino e trattino basso (max 40).');
    }
    return $n;
}

$me = current_user();
if (!$me || (int) $me['is_admin'] !== 1) {
    menu_errore('Sezione riservata al web-admin.', 403);
}
$attore = (string) ($me['mc_username'] ?? '?');

$csrfSessione = $_SESSION['csrf'] ?? '';
session_write_close();

// ---------------------------------------------------------------------
// Lettura
// ---------------------------------------------------------------------
if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    $azione = $_GET['azione'] ?? 'elenco';

    if ($azione === 'elenco') {
        $r = console_esegui('menu-json', [MENU_ISTANZA], 20);
        if (!$r['ok']) {
            menu_errore($r['err'] ?: 'Non riesco a leggere i menu dal server.');
        }
        $dati = json_decode($r['out'], true);
        if (!is_array($dati)) {
            menu_errore('Il file dei menu del server non è leggibile (JSON rovinato).');
        }
        // Chi sta modificando: serve all'editor per disegnare la faccia vera sulle teste
        // scritte come %player_name%. Sta qui e non in un attributo della pagina perche'
        // manage.php e' un file lungo e conteso: una chiave in piu' in questa risposta non
        // pesta i piedi a nessuno.
        menu_json(['ok' => true, 'io' => $attore] + $dati);
    }

    if ($azione === 'yaml') {
        $nome = menu_nome((string) ($_GET['nome'] ?? ''));
        $r = console_esegui('menu-leggi', [MENU_ISTANZA, $nome], 20);
        if (!$r['ok']) {
            menu_errore($r['err'] ?: 'Menu non leggibile.');
        }
        menu_json(['ok' => true, 'nome' => $nome, 'testo' => $r['out']]);
    }

    menu_errore('Richiesta non riconosciuta.');
}

// ---------------------------------------------------------------------
// Scrittura
// ---------------------------------------------------------------------
if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    menu_errore('Metodo non ammesso.', 405);
}
if ($csrfSessione === '' || !hash_equals($csrfSessione, (string) ($_POST['csrf'] ?? ''))) {
    menu_errore('Richiesta non valida (CSRF). Ricarica la pagina.', 400);
}

switch ((string) ($_POST['azione'] ?? '')) {

    case 'salva': {
        $nome = menu_nome((string) ($_POST['nome'] ?? ''));
        $modello = json_decode((string) ($_POST['menu'] ?? ''), true);
        if (!is_array($modello)) {
            menu_errore('Il menu inviato non è leggibile.');
        }
        $modello['nome'] = $nome;

        $yaml = menu_yaml_da_modello($modello, $attore);
        if (strlen($yaml) > MENU_MAX_BYTE) {
            menu_errore('Menu troppo grande: alleggeriscilo (limite ' . round(MENU_MAX_BYTE / 1024) . ' KB).');
        }

        $r = console_esegui('menu-scrivi', [$attore, MENU_ISTANZA, $nome], 20, $yaml);
        if (!$r['ok']) {
            menu_errore($r['err'] ?: 'Salvataggio non riuscito.');
        }
        // Si restituisce anche il testo scritto: l'editor lo mostra nella scheda "File", cosi'
        // chi vuole vedere cosa e' finito su disco non deve fidarsi sulla parola.
        menu_json(['ok' => true, 'messaggio' => 'Salvato in ' . $nome . '.yml', 'testo' => $yaml]);
    }

    case 'applica': {
        $r = console_esegui('cmd', [$attore, MENU_ISTANZA, 'magixmenus reload'], 20);
        if (!$r['ok']) {
            menu_errore($r['err'] ?: 'Il server non ha accettato il comando (è acceso?).');
        }
        menu_json(['ok' => true, 'messaggio' => 'Menu ricaricati sul server.']);
    }

    case 'elimina': {
        $nome = menu_nome((string) ($_POST['nome'] ?? ''));
        $r = console_esegui('menu-elimina', [$attore, MENU_ISTANZA, $nome], 20);
        if (!$r['ok']) {
            menu_errore($r['err'] ?: 'Eliminazione non riuscita.');
        }
        menu_json(['ok' => true, 'messaggio' => 'Menu ' . $nome . ' messo da parte (resta come .eliminato sul server).']);
    }

    default:
        menu_errore('Azione non riconosciuta.');
}
