<?php
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/forum_ui.php';
$page_title = 'Forum';
$page_description = 'Il forum di MAGICADVENTURE: annunci del server, discussioni fra fazioni, '
    . 'aiuto e supporto per i giocatori del server Minecraft italiano.';
$active = 'forum';

$albero = forum_albero();
$principali = $albero['principali'];
$ultimi = $albero['ultimi'];
$partecipanti = (int) db()->query('SELECT COUNT(DISTINCT user_id) FROM forum_posts')->fetchColumn();
// "Ultime discussioni" si accende dal gestionale (Forum -> Aspetto del forum). Spenta, la
// pagina parte direttamente dalle categorie: e' una scelta di chi gestisce, non del codice.
$ultimeAttive = site_setting('forum_ultime_enabled', '1') === '1';
$ultimeQuante = max(1, min(24, (int) site_setting('forum_ultime_quante', '6')));
$ultime = $ultimeAttive ? forum_ultime_discussioni($ultimeQuante) : [];

require __DIR__ . '/../../includes/header.php';
?>
<?php
  // Dove si puo' scrivere: le categorie senza sezioni e tutte le sezioni (una categoria che
  // ha delle sezioni non ospita discussioni proprie, vedi new_topic.php).
  $doveScrivere = [];
  foreach ($principali as $c) {
      $sez = $albero['sezioni'][(int) $c['id']] ?? [];
      if (!$sez) {
          $doveScrivere[] = ['cat' => $c, 'madre' => null];
      }
      foreach ($sez as $s) {
          $doveScrivere[] = ['cat' => $s, 'madre' => $c['name']];
      }
  }
  $totDiscussioni = (int) $albero['totali']['discussioni'];
?>
<?php /* Intestazione con il pulsante per scrivere sempre in vista: prima per aprire una
         discussione bisognava entrare in una categoria e trovarlo li'. */ ?>
<section class="forum-hero">
  <div class="forum-hero-testo">
    <span class="forum-hero-icona" aria-hidden="true"><?= ui_icon('message') ?></span>
    <div>
      <h1 class="page-title">Forum</h1>
      <p class="forum-sottotitolo">Domande, fazioni in cerca di membri, alleanze, aiuto e tutto il resto. Le discussioni le aprono i giocatori.</p>
    </div>
  </div>
  <div class="forum-hero-azioni">
    <?php if (is_logged_in() && $doveScrivere): ?>
      <details class="forum-nuova">
        <summary class="btn btn-green"><?= ui_icon('plus') ?> Nuova discussione</summary>
        <div class="forum-nuova-menu">
          <span class="forum-etichetta">Dove vuoi scrivere?</span>
          <?php foreach ($doveScrivere as $d): ?>
            <a href="/forum/new_topic?category=<?= urlencode($d['cat']['slug']) ?>">
              <?= ui_icon(forum_category_icon($d['cat'])) ?>
              <span><?= h($d['cat']['name']) ?><?php if ($d['madre']): ?><small><?= h($d['madre']) ?></small><?php endif; ?></span>
            </a>
          <?php endforeach; ?>
        </div>
      </details>
    <?php elseif (!is_logged_in()): ?>
      <a href="/login" class="btn btn-green"><?= ui_icon('user') ?> Accedi per scrivere</a>
    <?php endif; ?>
  </div>
  <div class="forum-hero-numeri">
    <?php if ($totDiscussioni === 0): ?>
      <p class="forum-hero-nuovo"><?= ui_icon('sparkles') ?> Il forum è appena aperto: la prima discussione può essere la tua.</p>
    <?php else: ?>
      <span><?= ui_icon('scroll') ?> <?= forum_conta($totDiscussioni, 'discussione', 'discussioni') ?></span>
      <span><?= ui_icon('message') ?> <?= forum_conta($albero['totali']['messaggi'], 'messaggio', 'messaggi') ?></span>
      <span><?= ui_icon('users') ?> <?= forum_conta($partecipanti, 'partecipante', 'partecipanti') ?></span>
    <?php endif; ?>
  </div>
</section>

<?php if ($ultime): ?>
  <?php /* Prima le cose vive, poi l'archivio: chi arriva vede subito di cosa si parla. */ ?>
  <section class="forum-ultime">
    <h2 class="forum-sezione-titolo">Ultime discussioni</h2>
    <div class="forum-ultime-griglia">
      <?php foreach ($ultime as $u): ?>
        <a class="forum-ultima" href="/forum/discussione/<?= (int) $u['id'] ?>">
          <?= forum_faccia($u, 32) ?>
          <span class="forum-ultima-testo">
            <span class="forum-ultima-titolo"><?= h($u['title']) ?></span>
            <span class="forum-ultima-meta">
              <span class="forum-ultima-dove"><?= h($u['cat_name']) ?></span>
              <?php $risposte = max(0, (int) $u['post_count'] - 1); ?>
              <?= h(time_ago($u['last_post_at'])) ?> ·
              <?= $risposte ?> <?= $risposte === 1 ? 'risposta' : 'risposte' ?>
            </span>
          </span>
        </a>
      <?php endforeach; ?>
    </div>
  </section>
<?php endif; ?>

<?php if (!$principali): ?>
  <div class="panel"><p>Nessuna categoria ancora. Torna presto!</p></div>
<?php else: ?>
  <h2 class="forum-sezione-titolo forum-titolo-staccato">Tutte le categorie</h2>
  <div class="forum-categorie">
    <?php foreach ($principali as $i => $c): ?>
      <?php
        $sezioni = $albero['sezioni'][(int) $c['id']] ?? [];
        $r = forum_riepilogo($c, $sezioni, $ultimi);
        // Una categoria con sezioni non si apre: si sceglie una sezione. Il titolo resta
        // comunque un collegamento, per chi vuole vederla tutta insieme.
      ?>
      <section class="forum-gruppo<?= $sezioni ? ' ha-sezioni' : '' ?>"
               style="<?= h(forum_tinta((int) $c['id'], $i, $c['color'])) ?>">
        <article class="forum-cat">
          <?php /* Tutta la riga porta alla categoria: il collegamento la copre. */ ?>
          <a class="forum-cat-link" href="/forum/<?= urlencode($c['slug']) ?>">Apri <?= h($c['name']) ?></a>
          <span class="forum-cat-icona" aria-hidden="true"><?= ui_icon(forum_category_icon($c)) ?></span>
          <div class="forum-cat-testo">
            <h2><?= h($c['name']) ?></h2>
            <p><?= h($c['description']) ?></p>
            <div class="forum-cat-numeri">
              <span><?= forum_conta($r['discussioni'], 'discussione', 'discussioni') ?></span>
              <span><?= forum_conta($r['messaggi'], 'messaggio', 'messaggi') ?></span>
              <?php if ($sezioni): ?>
                <span><?= forum_conta(count($sezioni), 'sezione', 'sezioni') ?></span>
              <?php endif; ?>
            </div>
          </div>
          <div class="forum-cat-ultimo">
            <?php if ($r['ultimo']): ?>
              <span class="forum-etichetta">Ultimo messaggio</span>
              <div class="forum-ultimo-riga">
                <?= forum_faccia($r['ultimo'], 34) ?>
                <div>
                  <span class="forum-ultimo-titolo"><?= h($r['ultimo']['title']) ?></span>
                  <span class="forum-ultimo-meta">
                    <?= player_name($r['ultimo'], $r['ultimo']['mc_username']) ?> · <?= h(time_ago($r['ultimo']['created_at'])) ?>
                  </span>
                </div>
              </div>
            <?php else: ?>
              <span class="forum-vuoto">Ancora nessuna discussione</span>
            <?php endif; ?>
          </div>
        </article>

        <?php if ($sezioni): ?>
          <?php /* Le sezioni sono righe intere, non targhette: ognuna ha la sua pagina,
                   i suoi numeri e il suo ultimo messaggio, come una categoria in piccolo. */ ?>
          <div class="forum-sezioni">
            <?php foreach ($sezioni as $s): ?>
              <?php forum_riga_sezione($s, $ultimi[(int) $s['id']] ?? null); ?>
            <?php endforeach; ?>
          </div>
        <?php endif; ?>
      </section>
    <?php endforeach; ?>
  </div>
<?php endif; ?>

<?php
  // Scorciatoie in fondo: le tre cose che chi arriva sul forum cerca di solito. Si mostrano
  // solo quelle che esistono davvero (le categorie si rinominano dal gestionale).
  $perSlug = [];
  foreach ($doveScrivere as $d) { $perSlug[$d['cat']['slug']] = $d['cat']; }
  foreach ($principali as $c) { $perSlug[$c['slug']] = $perSlug[$c['slug']] ?? $c; }
  $scorciatoie = [['/tutorial#regolamento', 'scroll', 'Regole del forum', 'Valgono anche qui: rispetto, niente spam, niente pubblicità.']];
  if (isset($perSlug['supporto'])) $scorciatoie[] = ['/forum/supporto', 'help', 'Serve aiuto?', 'Bug, problemi di accesso o domande allo staff.'];
  if (isset($perSlug['cerca-fazione'])) $scorciatoie[] = ['/forum/cerca-fazione', 'search', 'Cerchi una fazione?', 'Presentati: le fazioni che cercano gente passano di lì.'];
?>
<div class="forum-scorciatoie">
  <?php foreach ($scorciatoie as [$url, $icona, $titolo, $testo]): ?>
    <a class="forum-scorciatoia" href="<?= h($url) ?>">
      <span class="forum-scorciatoia-icona" aria-hidden="true"><?= ui_icon($icona) ?></span>
      <span><strong><?= h($titolo) ?></strong><small><?= h($testo) ?></small></span>
    </a>
  <?php endforeach; ?>
</div>

<?php require __DIR__ . '/../../includes/footer.php'; ?>
