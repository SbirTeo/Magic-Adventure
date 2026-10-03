<?php
/**
 * Pagina di guida e regolamento: /tutorial
 *
 * Guida e regolamento vivono qui, sotto un'unica voce di menu, e si scelgono con due
 * schede in cima (come Top Fazioni / Top Giocatori nelle classifiche): il passaggio
 * avviene lato client, SENZA ricaricare la pagina. La scheda scelta resta nell'hash
 * dell'URL, così /tutorial#regolamento apre direttamente il regolamento; il vecchio
 * indirizzo /regolamento reindirizza qui su quella scheda.
 *
 * La GUIDA non e' scritta qui: e' quella del server, generata da
 * plugins-src/MagixFactions/docs/build_tutorial.py in un unico file autonomo (CSS e GIF
 * incorporati), che il plugin riscrive a ogni avvio e un guardiano sul VPS copia in
 * assets/guida/magixfactions.html. Qui se ne prende solo il CONTENUTO (includes/guide_page.php),
 * tradotto come il resto del sito, e lo si mette nella pagina con lo stile del sito (.guida-doc):
 * a sinistra la ricerca e l'indice fissi, che seguono la lettura (assets/js/guida.js). Fino al
 * 3/10/2026 stava in un iframe, col suo foglio di stile: sembrava un altro sito, e un indice
 * che segue la lettura dentro un iframe non si puo' fare.
 *
 * Il REGOLAMENTO e' testo del gestionale (site_pages) con la tabella delle sanzioni
 * generata dal server al posto del segnaposto [[SANZIONI]] (vedi anche il vecchio
 * regolamento.php, ora un semplice reindirizzamento).
 */
require_once __DIR__ . '/../includes/db.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/auth.php';       // is_admin(): matita di modifica
require_once __DIR__ . '/../includes/sanzioni.php';   // tabella sanzioni del regolamento
require_once __DIR__ . '/../includes/language.php';
require_once __DIR__ . '/../includes/translate.php';  // la guida si traduce come il resto del sito
require_once __DIR__ . '/../includes/guide_page.php';

// --- Guida (riquadro col file del server) --------------------------------------------
$fileGuida = '/assets/guida/magixfactions.html';
$percorso = __DIR__ . $fileGuida;
$esiste = is_file($percorso);
// La data di modifica serve anche come "cache buster": cambia il file, cambia l'indirizzo.
$versione = $esiste ? filemtime($percorso) : 0;
// Il contenuto della guida, tradotto nella lingua del visitatore, e le sue parti e capitoli.
$guida = $esiste
    ? guide_page_parts(translate_html((string) file_get_contents($percorso), site_language()))
    : ['body' => '', 'parts' => []];
$esiste = $esiste && $guida['body'] !== '';

// --- Regolamento (testo dal gestionale + tabella sanzioni dal server) ----------------
$stmtReg = db()->prepare('SELECT title, body FROM site_pages WHERE slug = ?');
$stmtReg->execute(['regolamento']);
$pageReg = $stmtReg->fetch();
$titoloReg = $pageReg['title'] ?? 'Regolamento del server';
$bodyReg = $pageReg['body'] ?? 'Regolamento non ancora disponibile.';

// La sostituzione avviene DOPO corpo_articolo(): se il corpo e' testo semplice quella
// funzione lo passa da htmlspecialchars, e un blocco HTML infilato prima verrebbe stampato
// come codice. Il segnaposto non ha caratteri speciali, quindi arriva intatto.
$corpoReg = corpo_articolo($bodyReg);
$bloccoSanzioni = rulebook_sanctions_block();
if ($bloccoSanzioni) {
    $htmlSanzioni = '<div class="regolamento-sanzioni">' . $bloccoSanzioni['body_html']
        . '<p class="regolamento-sanzioni-fonte">Questa tabella è generata dalla configurazione del server'
        . ($bloccoSanzioni['updated_at'] ? ' e aggiornata ' . h(time_ago((string) $bloccoSanzioni['updated_at'])) : '')
        . '. <a href="/sanzioni">Guarda i provvedimenti presi →</a></p></div>';
} else {
    // Il plugin non l'ha ancora scritta: meglio una riga onesta che un buco nella pagina.
    $htmlSanzioni = '<div class="regolamento-sanzioni"><p>La tabella delle sanzioni non è ancora '
        . 'disponibile. Nel frattempo puoi consultare <a href="/sanzioni">i provvedimenti presi</a>.</p></div>';
}
$corpoReg = str_replace('[[SANZIONI]]', $htmlSanzioni, $corpoReg);

$page_title = 'Guida del server';
$page_description = 'Guida per i nuovi giocatori di MAGICADVENTURE: fazioni, potenza, territori e tutti i comandi, con il regolamento del server.';
$active = 'tutorial';

require __DIR__ . '/../includes/header.php';
?>
<h1 class="page-title" id="tutorialTitolo">Guida del server</h1>

<?php /* Le due schede: guida e regolamento nella stessa pagina, una alla volta. Stesse
         schede delle classifiche (stile in style.css), cambiano solo le icone. */ ?>
<div class="rank-tabs" role="tablist" aria-label="Guida e regolamento">
  <button type="button" class="rank-tab-btn is-active" data-tab="guida" data-titolo="Guida del server"
          role="tab" aria-selected="true" aria-controls="tab-guida"><?= ui_icon('book') ?> Guida</button>
  <button type="button" class="rank-tab-btn" data-tab="regolamento" data-titolo="<?= h($titoloReg) ?>"
          role="tab" aria-selected="false" aria-controls="tab-regolamento"><?= ui_icon('scroll') ?> Regolamento</button>
</div>

<section class="rank-tab-panel" id="tab-guida" role="tabpanel" aria-label="Guida">
  <?php /* Una riga sola di presentazione (si cambia dal gestionale), poi la guida. */ ?>
  <p class="guida-lead">
    <?= h(guide_intro()) ?>
    <?php if (is_admin()): ?>
      <a href="/manage?section=guida_edit" class="guida-lead-modifica" title="Modifica questo testo" aria-label="Modifica questo testo">✎</a>
    <?php endif; ?>
  </p>

  <?php if (!$esiste): ?>
    <div class="alert alert-error">La guida non è al momento disponibile. Riprova più tardi.</div>
  <?php else: ?>
    <div class="guida-layout">
      <?php /* Colonna fissa: ricerca e indice. Da telefono diventa una barra in cima con la
               ricerca e il pulsante "Capitoli" che apre l'indice (vedi assets/js/guida.js). */ ?>
      <aside class="guida-lato" id="guidaLato">
        <div class="guida-cerca" role="search">
          <?= ui_icon('search', 'guida-cerca-lente') ?>
          <input type="search" id="guidaCerca" placeholder="Cerca nella guida…" autocomplete="off"
                 aria-label="Cerca nella guida" aria-controls="guidaRisultati" aria-expanded="false">
          <div class="guida-risultati" id="guidaRisultati" role="listbox" hidden></div>
        </div>
        <button type="button" class="guida-capitoli-btn" id="guidaCapitoliBtn" aria-expanded="false" aria-controls="guidaIndice">
          <?= ui_icon('book') ?> Capitoli
        </button>
        <nav class="guida-indice" id="guidaIndice" aria-label="Capitoli della guida">
          <?php foreach ($guida['parts'] as $parte): ?>
            <?php if ($parte['title'] !== ''): ?>
              <div class="guida-indice-parte"><?= h($parte['title']) ?></div>
            <?php endif; ?>
            <ol>
              <?php foreach ($parte['chapters'] as $cap): ?>
                <li><a href="#<?= h($cap['id']) ?>" data-capitolo="<?= h($cap['id']) ?>"><span class="guida-indice-n"><?= h($cap['number']) ?></span><?= h($cap['title']) ?></a></li>
              <?php endforeach; ?>
            </ol>
          <?php endforeach; ?>
        </nav>
      </aside>

      <?php /* Il contenuto arriva dal file del server: e' il nostro (lo scrive il plugin),
               non testo degli utenti. */ ?>
      <article class="guida-doc" id="guidaDoc">
        <?= $guida['body'] ?>
      </article>
    </div>
  <?php endif; ?>
</section>

<section class="rank-tab-panel" id="tab-regolamento" role="tabpanel" aria-label="Regolamento" hidden>
  <?php /* Il regolamento si legge come la guida: capitoli numerati a schede e indice in cima. Il
           testo resta quello del gestionale; ogni <h2> che lo staff scrive apre un capitolo. */ ?>
  <?php $reg = rulebook_chapters($corpoReg); ?>
  <div class="panel panel-modificabile regolamento">
    <?php /* Stessa matita delle tessere in home: porta dritto al testo di questa pagina. */ ?>
    <?php if (is_admin()): ?>
      <a href="/manage?section=page_edit&slug=regolamento" class="card-edit-btn" title="Modifica il regolamento" aria-label="Modifica il regolamento">✎</a>
    <?php endif; ?>
    <?php if (trim(strip_tags($reg['intro'])) !== ''): ?>
      <div class="blog-body<?= corpo_e_html($bodyReg) ? ' corpo-html' : '' ?> regolamento-intro"><?= $reg['intro'] ?></div>
    <?php endif; ?>
  </div>
  <?php if ($reg['capitoli']): ?>
    <?php /* Stessa impostazione della guida e della guida per lo staff (classi doc-*). */ ?>
    <nav class="doc-toc" id="regolamento-indice" aria-label="Indice del regolamento">
      <div class="doc-toc-h">Indice</div>
      <ol class="doc-toc-list">
        <?php foreach ($reg['capitoli'] as $cap): ?>
          <li><a class="doc-toc-ch" href="#<?= h($cap['id']) ?>"><span class="doc-n doc-n-sm"><?= (int) $cap['numero'] ?></span><?= h($cap['titolo']) ?></a></li>
        <?php endforeach; ?>
      </ol>
    </nav>
  <?php endif; ?>
  <?php foreach ($reg['capitoli'] as $cap): ?>
    <section class="panel doc-chapter regolamento" id="<?= h($cap['id']) ?>">
      <h2 class="doc-chapter-t"><span class="doc-n"><?= (int) $cap['numero'] ?></span><?= h($cap['titolo']) ?></h2>
      <div class="blog-body corpo-html"><?= $cap['corpo'] ?></div>
      <p class="doc-back"><a href="#regolamento-indice">↑ Indice</a></p>
    </section>
  <?php endforeach; ?>
</section>

<script>
// Schede guida/regolamento: mostra una vista alla volta, senza ricaricare. La scelta resta
// nell'hash dell'URL così un link #regolamento apre direttamente quella scheda; il titolo
// in cima cambia insieme alla scheda.
(function () {
  var btns = Array.prototype.slice.call(document.querySelectorAll('.rank-tab-btn'));
  var panels = { guida: document.getElementById('tab-guida'), regolamento: document.getElementById('tab-regolamento') };
  var titolo = document.getElementById('tutorialTitolo');
  if (!btns.length) return;
  function show(tab) {
    if (!panels[tab]) tab = 'guida';
    Object.keys(panels).forEach(function (k) { if (panels[k]) panels[k].hidden = (k !== tab); });
    btns.forEach(function (b) {
      var on = b.dataset.tab === tab;
      b.classList.toggle('is-active', on);
      b.setAttribute('aria-selected', on ? 'true' : 'false');
      if (on && titolo && b.dataset.titolo) titolo.textContent = b.dataset.titolo;
    });
  }
  btns.forEach(function (b) {
    b.addEventListener('click', function () {
      show(b.dataset.tab);
      if (history.replaceState) history.replaceState(null, '', '#' + b.dataset.tab);
      else location.hash = b.dataset.tab;
    });
  });
  var start = (location.hash || '').replace('#', '');
  // Un link a un capitolo del regolamento (#regola-...) apre la scheda del regolamento e ci arriva.
  if (start.indexOf('regola') === 0 && start !== 'regolamento') {
    show('regolamento');
    var cap = document.getElementById(start);
    if (cap) setTimeout(function () { cap.scrollIntoView(); }, 0);
  } else {
    show(panels[start] ? start : 'guida');
  }
})();
</script>

<?php if ($esiste): ?>
  <script src="/assets/js/guida.js?v=<?= @filemtime(__DIR__ . '/assets/js/guida.js') ?: time() ?>"></script>
<?php endif; ?>

<?php require __DIR__ . '/../includes/footer.php'; ?>
