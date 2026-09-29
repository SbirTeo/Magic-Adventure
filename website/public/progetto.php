<?php
/**
 * /progetto — lo spazio di lavoro condiviso fra gli amministratori del sito: panoramica con
 * l'avanzamento, bacheca delle attivita', obiettivi, calendario, chat e registro. E' una pagina
 * a se' (non una scheda del gestionale): nel gestionale c'e' solo il collegamento, col numero
 * dei messaggi non letti.
 *
 * Solo web-admin (is_admin()), come le schede di configurazione del sito: non si delega ai
 * gruppi. Tutto il contenuto lo disegna assets/js/project.js leggendo /api/project.
 */
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/helpers.php';

require_login();
if (!is_admin()) {
    http_response_code(403);
    die('Pagina riservata agli amministratori del sito.');
}
// Appunti di lavoro interni: niente motori di ricerca.
header('X-Robots-Tag: noindex, nofollow');

$me = current_user();
$page_title = 'Progetto';
require __DIR__ . '/../includes/header.php';
?>
<link rel="stylesheet" href="/assets/css/project.css?v=<?= @filemtime(__DIR__ . '/assets/css/project.css') ?: time() ?>">

<h1 class="page-title">Progetto</h1>

<?php /* data-no-tr: sono appunti di lavoro fra amministratori, non testo del sito da passare
         al traduttore automatico. */ ?>
<div id="project" class="pj" data-no-tr
     data-csrf="<?= h(csrf_token()) ?>" data-me="<?= (int) $me['id'] ?>">
  <div class="pj-loading">Caricamento dello spazio di lavoro…</div>
</div>

<script src="/assets/js/project.js?v=<?= @filemtime(__DIR__ . '/assets/js/project.js') ?: time() ?>"></script>

<?php require __DIR__ . '/../includes/footer.php'; ?>
