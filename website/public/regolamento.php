<?php
require_once __DIR__ . '/../includes/db.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/auth.php';   // serve per is_admin(): matita di modifica
require_once __DIR__ . '/../includes/sanzioni.php';

$stmt = db()->prepare('SELECT title, body FROM site_pages WHERE slug = ?');
$stmt->execute(['regolamento']);
$page = $stmt->fetch();
$title = $page['title'] ?? 'Regolamento del server';
$body = $page['body'] ?? 'Regolamento non ancora disponibile.';

$page_title = $title;
require_once __DIR__ . '/../includes/seo.php';
$page_description = seo_riassunto($body);
$page_jsonld = seo_briciole(['Home' => '/', $title => '/regolamento']);
$active = 'regolamento';

/**
 * La tabella delle sanzioni NON si scrive a mano: la genera MagixGuard dalla sua
 * configurazione e la deposita in `punishment_rules` a ogni avvio del server.
 * Nel testo della pagina si mette il segnaposto [[SANZIONI]] nel punto in cui la si vuole.
 *
 * La sostituzione avviene DOPO corpo_articolo(): se il corpo e' testo semplice, quella
 * funzione lo passa da htmlspecialchars, e un blocco HTML infilato prima verrebbe stampato
 * come codice. Il segnaposto non ha caratteri speciali, quindi arriva dall'altra parte intatto.
 */
$corpo = corpo_articolo($body);

$blocco = rulebook_sanctions_block();
if ($blocco) {
    $htmlSanzioni = '<div class="regolamento-sanzioni">' . $blocco['body_html']
        . '<p class="regolamento-sanzioni-fonte">Questa tabella è generata dalla configurazione del server'
        . ($blocco['updated_at'] ? ' e aggiornata ' . h(time_ago((string) $blocco['updated_at'])) : '')
        . '. <a href="/sanzioni">Guarda i provvedimenti presi →</a></p></div>';
} else {
    // Il plugin non l'ha ancora scritta: meglio una riga onesta che un buco nella pagina.
    $htmlSanzioni = '<div class="regolamento-sanzioni"><p>La tabella delle sanzioni non è ancora '
        . 'disponibile. Nel frattempo puoi consultare <a href="/sanzioni">i provvedimenti presi</a>.</p></div>';
}
$corpo = str_replace('[[SANZIONI]]', $htmlSanzioni, $corpo);

require __DIR__ . '/../includes/header.php';
?>
<h1 class="page-title"><?= h($title) ?></h1>

<?php /* Stesso interruttore di /tutorial: guida e regolamento condividono la voce di
         menu, qui si torna alla guida. */ ?>
<nav class="guida-switch" aria-label="Guida o regolamento">
  <a href="/tutorial" class="guida-switch-voce">Guida</a>
  <a href="/regolamento" class="guida-switch-voce active" aria-current="page">Regolamento</a>
</nav>

<div class="panel panel-modificabile">
  <?php /* Stessa matita delle tessere in home: porta dritto al testo di questa pagina. */ ?>
  <?php if (is_admin()): ?>
    <a href="/manage?section=page_edit&slug=regolamento" class="card-edit-btn" title="Modifica il regolamento" aria-label="Modifica il regolamento">✎</a>
  <?php endif; ?>
  <div class="blog-body<?= corpo_e_html($body) ? ' corpo-html' : '' ?>"><?= $corpo ?></div>
</div>

<?php require __DIR__ . '/../includes/footer.php'; ?>
