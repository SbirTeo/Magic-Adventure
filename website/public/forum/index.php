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
<div class="forum-testata">
  <div>
    <h1 class="page-title">Forum</h1>
    <p class="forum-sottotitolo">Domande, guide, costruzioni e tutto il resto. Le discussioni le aprono i giocatori.</p>
  </div>
  <div class="forum-numeri">
    <span><?= forum_conta($albero['totali']['discussioni'], 'discussione', 'discussioni') ?></span>
    <span><?= forum_conta($albero['totali']['messaggi'], 'messaggio', 'messaggi') ?></span>
    <span><?= forum_conta($partecipanti, 'partecipante', 'partecipanti') ?></span>
  </div>
</div>

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
              <span class="forum-vuoto">Ancora nessun messaggio</span>
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

<?php if (!is_logged_in()): ?>
  <div class="alert alert-info" style="margin-top:24px;">
    <a href="/login">Accedi</a> per aprire discussioni e rispondere.
  </div>
<?php endif; ?>

<?php require __DIR__ . '/../../includes/footer.php'; ?>
