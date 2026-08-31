<?php
/**
 * Caricamento di un'immagine dal gestionale: risponde al pulsante "Scegli" accanto ai
 * campi "URL immagine". Riceve un file, lo salva in /assets/img/caricate/ e restituisce
 * l'indirizzo da mettere nel campo.
 *
 * Difese, in ordine:
 *  - solo chi ha i permessi (web-admin, o chi puo' scrivere sul blog per le copertine);
 *  - gettone CSRF, come tutti i moduli del gestionale;
 *  - il tipo si decide guardando DENTRO il file (getimagesize), non dal nome ne' dal
 *    tipo dichiarato dal browser: entrambi si falsificano;
 *  - il nome del file lo scegliamo noi (casuale + estensione dedotta), quindi non si puo'
 *    caricare "cosa.php" e farlo eseguire.
 */
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/permissions.php';
require_once __DIR__ . '/../../includes/immagini.php';

header('Content-Type: application/json; charset=utf-8');

/** Risposta di errore in JSON e stop. */
function errore(string $messaggio, int $codice = 400): void {
    http_response_code($codice);
    echo json_encode(['ok' => false, 'errore' => $messaggio], JSON_UNESCAPED_UNICODE);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    errore('Metodo non ammesso.', 405);
}

if (!is_logged_in()) {
    errore('Devi accedere.', 401);
}
if (!is_admin() && !can('blog.create') && !can('blog.edit')) {
    errore('Non hai il permesso di caricare immagini.', 403);
}

// csrf_check() risponderebbe in HTML: qui serve JSON, quindi ripeto il confronto.
if (empty($_SESSION['csrf']) || !hash_equals($_SESSION['csrf'], $_POST['csrf'] ?? '')) {
    errore('Richiesta non valida: ricarica la pagina e riprova.');
}

if (!isset($_FILES['file'])) {
    errore('Nessun file ricevuto.');
}

$file = $_FILES['file'];
if ($file['error'] !== UPLOAD_ERR_OK) {
    // I due errori di dimensione sono i piu' comuni: meritano un messaggio chiaro.
    $messaggi = [
        UPLOAD_ERR_INI_SIZE  => 'Immagine troppo pesante per il server.',
        UPLOAD_ERR_FORM_SIZE => 'Immagine troppo pesante.',
        UPLOAD_ERR_PARTIAL   => 'Caricamento interrotto: riprova.',
        UPLOAD_ERR_NO_FILE   => 'Nessun file selezionato.',
    ];
    errore($messaggi[$file['error']] ?? 'Caricamento non riuscito.');
}

$MAX = 5 * 1024 * 1024;   // 5 MB: piu' che sufficiente per una copertina
if ($file['size'] > $MAX) {
    errore('Immagine troppo pesante: il limite è 5 MB.');
}
if (!is_uploaded_file($file['tmp_name'])) {
    errore('Caricamento non valido.');
}

// Il tipo vero si legge dal contenuto. Se getimagesize fallisce, non e' un'immagine.
$info = @getimagesize($file['tmp_name']);
$estensioni = [
    IMAGETYPE_JPEG => 'jpg',
    IMAGETYPE_PNG  => 'png',
    IMAGETYPE_GIF  => 'gif',
    IMAGETYPE_WEBP => 'webp',
];
if (!$info || !isset($estensioni[$info[2]])) {
    errore('Formato non supportato: usa JPG, PNG, GIF o WEBP.');
}
$estensione = $estensioni[$info[2]];

$cartella = __DIR__ . '/../assets/img/caricate';
if (!is_dir($cartella) && !@mkdir($cartella, 0755, true)) {
    errore('Cartella delle immagini non disponibile.', 500);
}
if (!is_writable($cartella)) {
    errore('Cartella delle immagini non scrivibile.', 500);
}

$nome = date('Ymd') . '-' . bin2hex(random_bytes(6)) . '.' . $estensione;
if (!move_uploaded_file($file['tmp_name'], $cartella . '/' . $nome)) {
    errore('Salvataggio non riuscito.', 500);
}
@chmod($cartella . '/' . $nome, 0644);

// Alleggerimento: rimpicciolisce l'immagine se e' enorme e le affianca la copia WebP che
// nginx servira' ai browser moderni (vedi includes/immagini.php). Se non riesce, pazienza:
// resta l'immagine com'e' arrivata.
immagine_ottimizza($cartella . '/' . $nome);
$misure = @getimagesize($cartella . '/' . $nome) ?: $info;

echo json_encode([
    'ok'        => true,
    'url'       => '/assets/img/caricate/' . $nome,
    'larghezza' => (int) $misure[0],
    'altezza'   => (int) $misure[1],
], JSON_UNESCAPED_UNICODE);
