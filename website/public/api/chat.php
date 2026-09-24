<?php
/**
 * API della chat live mostrata in home (modulo sopra la scheda giocatore).
 *
 * E' un PONTE col gioco: i messaggi scritti qui finiscono in `web_chat` con source='web'
 * e il plugin MagixWeb li ripubblica nella chat del server; la chat pubblica del server
 * viene specchiata dallo stesso plugin con source='game' e la leggiamo qui.
 *
 * Risponde SEMPRE in JSON (anche sugli errori): il modulo in home e' interamente JS.
 *
 *   GET  ?after=<id>   messaggi nuovi (senza after: la coda piu' recente)
 *   POST action=send   invia un messaggio (login + CSRF + slowmode)
 *   POST action=delete elimina un messaggio (permesso chat.moderate)
 *   POST action=clear  svuota tutta la chat (permesso chat.clear)
 */
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

/** Stesso limite della colonna `message` lato DB, contato in caratteri (non byte). */
const CHAT_MAX_LEN = 200;
/** Tetto anti-flood indipendente dallo slowmode: messaggi al minuto per giocatore. */
const CHAT_MAX_PER_MINUTE = 12;

function chat_json(array $data, int $code = 200): void {
    http_response_code($code);
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}

function chat_errore(string $messaggio, int $code = 400): void {
    chat_json(['ok' => false, 'error' => $messaggio], $code);
}

$chatAttiva = site_setting('chat_enabled', '1') === '1';
$mostraGioco = site_setting('chat_show_game', '1') === '1';
$storico = max(5, min(100, (int) site_setting('chat_history', '40')));
$slowmode = max(0, min(120, (int) site_setting('chat_slowmode', '3')));

$me = current_user();
$puoModerare = can('chat.moderate');
$puoSvuotare = can('chat.clear');
// Segna il punto dell'ultimo svuotamento: e' l'id piu' alto che c'era in tabella quando
// qualcuno ha premuto "svuota". Serve alle pagine GIA' APERTE: la cancellazione dal
// database da sola non toglierebbe dallo schermo i messaggi che hanno gia' disegnato,
// e resterebbero li' finche' non ricaricano. Vedendo questo numero salire, il browser
// butta via quello che ha in elenco.
$svuotataA = (int) site_setting('chat_purge_id', '0');
$metodo = $_SERVER['REQUEST_METHOD'];

// Battito di presenza degli ospiti: chi guarda la home resta li' senza aprire altre pagine,
// e senza questo verrebbe contato una volta sola e poi sparirebbe. La chat chiama qui ogni
// 5 secondi portandosi dietro il cookie, quindi e' il posto giusto per dire "ci sono ancora"
// (chi ha fatto l'accesso e' gia' a posto: la sua presenza la scrive current_user() in
// auth.php, e ospiti_registra() salta da sola chi ha l'accesso fatto).
// Va PRIMA di chiudere la sessione, perche' si appoggia a $_SESSION per non scrivere
// sul database piu' di una volta al minuto.
ospiti_registra();

// La sessione serve solo per sapere chi sei e per il token CSRF: chiusa subito, altrimenti
// il lock del file di sessione serializzerebbe il polling della chat con le altre richieste.
$csrfSessione = $_SESSION['csrf'] ?? '';
session_write_close();

// ---------------------------------------------------------------------
// Lettura
// ---------------------------------------------------------------------
if ($metodo === 'GET') {
    if (!$chatAttiva) {
        chat_json(['ok' => true, 'enabled' => false, 'messages' => []]);
    }

    $after = max(0, (int) ($_GET['after'] ?? 0));
    $filtroSorgente = $mostraGioco ? '' : " AND c.source = 'web' ";

    // La chat si vede come in gioco: il tag della fazione e il nome prendono il colore della
    // relazione di CHI LEGGE verso il mittente, quindi servono la fazione di chi guarda la
    // pagina e le sue alleanze (una query sola, non una per messaggio).
    $miaFazione = null;
    if ($me) {
        $fq = db()->prepare('SELECT faction_id FROM factions_magixfactions.faction_members
                             WHERE uuid COLLATE utf8mb4_unicode_ci = ?');
        $fq->execute([$me['mc_uuid']]);
        $trovata = $fq->fetchColumn();
        $miaFazione = $trovata !== false && $trovata !== null ? (int) $trovata : null;
    }
    $alleati = faction_allies($miaFazione);

    // La JOIN con users serve solo alla presenza sul sito (pallino accanto al nome): chi
    // scrive dal gioco puo' non avere un account sul sito, e allora last_seen resta NULL.
    $sql = 'SELECT c.id, c.source, c.mc_uuid, us.premium_uuid, c.mc_username, c.message, c.created_at, '
        . RANK_SELECT_SQL . ', f.id AS faction_id, f.name AS faction_name, fm.rank AS faction_rank, us.last_seen'
        . ' FROM web_chat c'
        . ' LEFT JOIN users us ON us.mc_uuid = c.mc_uuid COLLATE utf8mb4_unicode_ci'
        . ' LEFT JOIN mc_ranks r ON r.mc_uuid = c.mc_uuid COLLATE utf8mb4_unicode_ci'
        . ' LEFT JOIN factions_magixfactions.faction_members fm ON fm.uuid = c.mc_uuid COLLATE utf8mb4_unicode_ci'
        . ' LEFT JOIN factions_magixfactions.factions f ON f.id = fm.faction_id'
        . ' WHERE c.id > :after' . $filtroSorgente
        . ' ORDER BY c.id DESC LIMIT :lim';

    $stmt = db()->prepare($sql);
    $stmt->bindValue(':after', $after, PDO::PARAM_INT);
    // Con un `after` chiediamo solo il delta, ma teniamo comunque un tetto per non
    // rispondere con mille righe se la pagina e' rimasta aperta a lungo.
    $stmt->bindValue(':lim', $after > 0 ? 100 : $storico, PDO::PARAM_INT);
    $stmt->execute();

    $righe = array_reverse($stmt->fetchAll());
    $out = [];
    foreach ($righe as $r) {
        $relazione = faction_relation(
            $miaFazione,
            $r['faction_id'] !== null ? (int) $r['faction_id'] : null,
            $alleati
        );
        $out[] = [
            'id'     => (int) $r['id'],
            'source' => $r['source'],
            // HTML gia' sicuro: nome e fazione sono escapati, il resto sono i tag dei gradi.
            'name'   => chat_sender_html($r, $relazione),
            // Testo grezzo: lo inserisce il JS con textContent, mai come HTML.
            'text'   => $r['message'],
            'time'   => date('H:i', strtotime($r['created_at'])),
            // Faccia del mittente per la targhetta che compare passando il mouse sul
            // messaggio. Arriva come HTML GIA' COMPOSTO, non come semplice indirizzo: cosi'
            // passa da avatar_top(), che e' l'unico posto in cui si decide se sopra la testa
            // va la corona del miglior sostenitore (vedi la regola in helpers.php). Se la
            // costruisse il browser, quella regola andrebbe copiata anche in JavaScript e
            // prima o poi le due copie direbbero cose diverse.
            'faccia' => avatar_top(
                '<img class="chat-msg-faccia" src="' . h(mc_avatar_url($r['mc_uuid'], 64, $r['premium_uuid'] ?? null))
                . '" alt="" width="34" height="34" loading="lazy">',
                $r['mc_uuid'], 34
            ),
            'mine'   => $me && $r['source'] === 'web'
                        && strcasecmp((string) $r['mc_uuid'], (string) $me['mc_uuid']) === 0,
        ];
    }

    chat_json([
        'ok'        => true,
        'enabled'   => true,
        'canDelete' => $puoModerare,
        'canClear'  => $puoSvuotare,
        'purge'     => $svuotataA,
        'messages'  => $out,
    ]);
}

if ($metodo !== 'POST') {
    chat_errore('Metodo non consentito.', 405);
}

// ---------------------------------------------------------------------
// Scrittura
// ---------------------------------------------------------------------
// CSRF fatto qui a mano invece che con csrf_check(): quello risponde in HTML, qui serve JSON.
$inviato = $_POST['csrf'] ?? '';
if ($csrfSessione === '' || !hash_equals((string) $csrfSessione, (string) $inviato)) {
    chat_errore('Sessione scaduta, ricarica la pagina.', 400);
}

$azione = $_POST['action'] ?? '';

if ($azione === 'delete') {
    if (!$puoModerare) {
        chat_errore('Non hai il permesso di moderare la chat.', 403);
    }
    $id = (int) ($_POST['id'] ?? 0);
    if ($id <= 0) {
        chat_errore('Messaggio non valido.');
    }
    db()->prepare('DELETE FROM web_chat WHERE id = ?')->execute([$id]);
    chat_json(['ok' => true, 'deleted' => $id]);
}

if ($azione === 'clear') {
    if (!$puoSvuotare) {
        chat_errore('Non hai il permesso di svuotare la chat.', 403);
    }
    // Si segna PRIMA fin dove si arriva e poi si cancella solo fino a li': un messaggio
    // arrivato nel frattempo (dal gioco il ponte scrive di continuo) non deve sparire senza
    // essere mai stato letto da nessuno.
    $fino = (int) db()->query('SELECT COALESCE(MAX(id), 0) FROM web_chat')->fetchColumn();
    // Spariscono anche gli eventuali messaggi del sito non ancora ripubblicati in gioco:
    // svuotare vuol dire che quella roba non si vuole piu' vedere, ne' qui ne' in partita.
    db()->prepare('DELETE FROM web_chat WHERE id <= ?')->execute([$fino]);
    db()->prepare('INSERT INTO site_settings (setting_key, setting_value) VALUES (?, ?)
                   ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)')
        ->execute(['chat_purge_id', (string) $fino]);
    chat_json(['ok' => true, 'purge' => $fino]);
}

if ($azione !== 'send') {
    chat_errore('Azione sconosciuta.');
}

if (!$chatAttiva) {
    chat_errore('La chat è disattivata.', 403);
}
if (!$me) {
    chat_errore('Devi accedere per scrivere in chat.', 401);
}

$testo = (string) ($_POST['message'] ?? '');
if (!mb_check_encoding($testo, 'UTF-8')) {
    // Senza questo controllo le preg_replace in modalita' /u qui sotto tornerebbero null
    // (byte non validi) e il messaggio diventerebbe vuoto senza spiegazione.
    chat_errore('Messaggio non valido.');
}
// Via i caratteri di controllo (a capo compresi: e' una riga sola) e il simbolo dei codici
// colore di Minecraft, cosi' un messaggio del sito non puo' colorare la chat di gioco.
$testo = preg_replace('/[\x00-\x1F\x7F]/u', ' ', $testo);
$testo = str_replace("\u{00A7}", '', $testo);
$testo = trim(preg_replace('/\s{2,}/u', ' ', $testo));

if ($testo === '') {
    chat_errore('Scrivi qualcosa prima di inviare.');
}
if (mb_strlen($testo) > CHAT_MAX_LEN) {
    $testo = mb_substr($testo, 0, CHAT_MAX_LEN);
}

// Slowmode e tetto al minuto: contati sui messaggi gia' in tabella, cosi' valgono anche
// se il giocatore apre il sito in piu' schede.
$conta = db()->prepare(
    'SELECT
        SUM(created_at > DATE_SUB(NOW(), INTERVAL ' . $slowmode . ' SECOND)) AS recenti,
        SUM(created_at > DATE_SUB(NOW(), INTERVAL 1 MINUTE)) AS ultimo_minuto
     FROM web_chat WHERE source = \'web\' AND mc_uuid = ?'
);
$conta->execute([$me['mc_uuid']]);
$limiti = $conta->fetch();

if ($slowmode > 0 && (int) ($limiti['recenti'] ?? 0) > 0) {
    chat_errore('Aspetta ' . $slowmode . ' secondi tra un messaggio e l\'altro.', 429);
}
if ((int) ($limiti['ultimo_minuto'] ?? 0) >= CHAT_MAX_PER_MINUTE) {
    chat_errore('Stai scrivendo troppo in fretta. Riprova tra un minuto.', 429);
}

$ins = db()->prepare(
    "INSERT INTO web_chat (source, mc_uuid, mc_username, message, delivered)
     VALUES ('web', ?, ?, ?, 0)"
);
$ins->execute([$me['mc_uuid'], $me['mc_username'], $testo]);

chat_json(['ok' => true, 'id' => (int) db()->lastInsertId()]);
