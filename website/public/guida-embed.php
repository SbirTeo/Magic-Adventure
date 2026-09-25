<?php
/**
 * Passa dalla guida statica del server (plugins-src/MagixFactions/docs/build_tutorial.py,
 * copiata in assets/guida/) alla stessa traduzione automatica del resto del sito: nginx la
 * serve anche direttamente (percorso originale, sempre in italiano), qui invece si legge il
 * file, si traduce con lo stesso includes/translate.php e si manda al browser. /tutorial usa
 * questo indirizzo nell'iframe della guida.
 */
require_once __DIR__ . '/../includes/language.php';
require_once __DIR__ . '/../includes/translate.php';

// Whitelist esplicita: mai un percorso a piacere nella query string (niente attraversamento
// di cartelle), solo i file di guida che conosciamo davvero.
$file = $_GET['file'] ?? 'magixfactions';
$percorsi = [
    'magixfactions' => __DIR__ . '/assets/guida/magixfactions.html',
];
if (!isset($percorsi[$file]) || !is_file($percorsi[$file])) {
    http_response_code(404);
    exit;
}

$html = file_get_contents($percorsi[$file]);
header('Content-Type: text/html; charset=utf-8');
echo translate_html($html, site_language());
