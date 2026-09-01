<?php
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/forum_ui.php';

$id = (int)($_GET['id'] ?? 0);
$stmt = db()->prepare("
    SELECT t.*, c.id AS cat_id, c.name AS cat_name, c.slug AS cat_slug,
           m.name AS madre_name, m.slug AS madre_slug
    FROM forum_topics t
    JOIN forum_categories c ON c.id = t.category_id
    LEFT JOIN forum_categories m ON m.id = c.parent_id
    WHERE t.id = ?
");
$stmt->execute([$id]);
$topic = $stmt->fetch();

if (!$topic) {
    http_response_code(404);
    $page_title = 'Discussione non trovata';
    require __DIR__ . '/../../includes/header.php';
    echo '<div class="panel"><p>Discussione non trovata.</p><a href="/forum">← Torna al forum</a></div>';
    require __DIR__ . '/../../includes/footer.php';
    exit;
}

$error = null;

// Mi piace: interruttore su un messaggio di QUESTA discussione, mai sul proprio. Finisce
// con un redirect (schema post-redirect-get), cosi' ricaricare la pagina non lo rimette.
if ($_SERVER['REQUEST_METHOD'] === 'POST' && ($_POST['azione'] ?? '') === 'mi-piace') {
    require_login();
    csrf_check();
    $postId = (int) ($_POST['post_id'] ?? 0);
    $chi = db()->prepare('SELECT user_id FROM forum_posts WHERE id = ? AND topic_id = ?');
    $chi->execute([$postId, $topic['id']]);
    $autore = $chi->fetchColumn();
    if ($autore !== false && (int) $autore !== (int) current_user()['id']) {
        forum_like_toggle($postId, (int) current_user()['id']);
    }
    redirect('/forum/discussione/' . $topic['id'] . '#p' . $postId);
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    require_login();
    csrf_check();
    if ((int)$topic['is_locked'] === 1) {
        $error = 'Questa discussione è chiusa, non puoi rispondere.';
    } else {
        $body = trim($_POST['body'] ?? '');
        if ($body === '') {
            $error = 'Scrivi qualcosa prima di rispondere.';
        } else {
            $ins = db()->prepare('INSERT INTO forum_posts (topic_id, user_id, body) VALUES (?, ?, ?)');
            $ins->execute([$topic['id'], current_user()['id'], $body]);
            $upd = db()->prepare('UPDATE forum_topics SET last_post_at = NOW() WHERE id = ?');
            $upd->execute([$topic['id']]);
            redirect('/forum/discussione/' . $topic['id'] . '#fondo');
        }
    }
}

// ---------------------------------------------------------------------
// Conteggio visite: UNA per ACCOUNT, non per sessione. Prima stava nella sessione, quindi
// bastava riaprire il browser (o cambiare dispositivo) per farla contare di nuovo; ora la
// riga in forum_topic_views e' unica per (discussione, utente) e il numero dice quante
// PERSONE l'hanno aperta.
// Chi legge senza account vede tutto, ma non conta: senza un account non c'e' nessuno da
// contare, e contare i passaggi anonimi gonfierebbe di nuovo il numero.
// ---------------------------------------------------------------------
if (is_logged_in()) {
    $ioId = (int) current_user()['id'];
    try {
        $vista = db()->prepare('INSERT IGNORE INTO forum_topic_views (topic_id, user_id) VALUES (?, ?)');
        $vista->execute([$topic['id'], $ioId]);
        if ($vista->rowCount() > 0) {
            // Prima volta di questo account: il totale sale, e quello mostrato qui sotto e' gia' giusto.
            db()->prepare('UPDATE forum_topics SET views = views + 1 WHERE id = ?')->execute([$topic['id']]);
            $topic['views'] = (int) $topic['views'] + 1;
        } else {
            // Gia' vista: si aggiorna solo quando, senza toccare il totale.
            db()->prepare('UPDATE forum_topic_views SET viewed_at = NOW() WHERE topic_id = ? AND user_id = ?')
                ->execute([$topic['id'], $ioId]);
        }
    } catch (PDOException $e) {
        // tabella non ancora creata: la discussione si apre lo stesso, il numero resta fermo
    }
}

$stmt = db()->prepare("
    SELECT p.*, u.mc_username, u.mc_uuid, u.is_admin, u.last_seen, " . RANK_SELECT_SQL . "
    FROM forum_posts p JOIN forum_topics t ON t.id = p.topic_id
    JOIN users u ON u.id = p.user_id" . rank_join_sql() . "
    WHERE p.topic_id = ?
    ORDER BY p.created_at ASC
");
$stmt->execute([$topic['id']]);
$posts = $stmt->fetchAll();

$me = current_user();

// Mi piace dei messaggi e numeri degli autori: raccolti in blocco prima di disegnare,
// cosi' il ciclo qui sotto non fa nessuna query.
$miPiace = forum_mi_piace(array_column($posts, 'id'), $me ? (int) $me['id'] : null);
$statAutori = forum_user_stats(array_column($posts, 'user_id'));

$page_title = $topic['title'];
$active = 'forum';

// Per Google e per le anteprime social: la descrizione e' l'inizio del primo messaggio,
// la scheda dice che questa e' una discussione (con numero di risposte e data).
require_once __DIR__ . '/../../includes/seo.php';
$__primo = $posts[0] ?? null;
$page_description = $__primo ? seo_riassunto($__primo['body']) : '';
$page_jsonld = [
    [
        '@context' => 'https://schema.org',
        '@type' => 'DiscussionForumPosting',
        'headline' => mb_substr($topic['title'], 0, 110),
        'text' => $page_description,
        'datePublished' => seo_data($topic['created_at']),
        'dateModified' => seo_data($topic['last_post_at']),
        'url' => seo_url('/forum/discussione/' . (int) $topic['id']),
        'inLanguage' => 'it-IT',
        'author' => ['@type' => 'Person', 'name' => $__primo['mc_username'] ?? 'MAGICADVENTURE'],
        'interactionStatistic' => [
            ['@type' => 'InteractionCounter', 'interactionType' => 'https://schema.org/CommentAction',
             'userInteractionCount' => max(0, count($posts) - 1)],
            ['@type' => 'InteractionCounter', 'interactionType' => 'https://schema.org/ViewAction',
             'userInteractionCount' => (int) $topic['views']],
        ],
    ],
    seo_briciole(array_merge(
        ['Home' => '/', 'Forum' => '/forum'],
        // Se la discussione sta in una sezione, in mezzo c'e' la categoria che la contiene
        $topic['madre_slug'] ? [$topic['madre_name'] => '/forum/' . $topic['madre_slug']] : [],
        [
            $topic['cat_name'] => '/forum/' . $topic['cat_slug'],
            $topic['title'] => '/forum/discussione/' . (int) $topic['id'],
        ]
    )),
];

require __DIR__ . '/../../includes/header.php';
?>
<nav class="briciole">
  <a href="/forum">Forum</a> <span aria-hidden="true">/</span>
  <?php /* Se la discussione sta in una sezione, la briciola mostra anche la categoria
           che la contiene: altrimenti non si capisce dove si e' finiti. */ ?>
  <?php if ($topic['madre_slug']): ?>
    <a href="/forum/<?= urlencode($topic['madre_slug']) ?>"><?= h($topic['madre_name']) ?></a> <span aria-hidden="true">/</span>
  <?php endif; ?>
  <a href="/forum/<?= urlencode($topic['cat_slug']) ?>"><?= h($topic['cat_name']) ?></a>
</nav>

<div class="forum-testata">
  <div>
    <h1 class="page-title">
      <?php if ($topic['is_pinned']): ?><span class="forum-segno is-fissata" title="In evidenza">📌</span><?php endif; ?>
      <?php if ($topic['is_locked']): ?><span class="forum-segno is-chiusa" title="Chiusa">🔒</span><?php endif; ?>
      <?= h($topic['title']) ?>
    </h1>
    <p class="forum-sottotitolo">
      <?= max(0, count($posts) - 1) ?> risposte · <?= (int) $topic['views'] ?> visite ·
      aperta <?= h(time_ago($topic['created_at'])) ?>
    </p>
  </div>
</div>

<ol class="forum-messaggi">
  <?php foreach ($posts as $i => $p): ?>
    <li class="forum-msg<?= $i === 0 ? ' is-apertura' : '' ?>" id="m<?= $i + 1 ?>" style="--i:<?= $i ?>">
      <div class="forum-msg-lato">
        <div class="forum-msg-identita">
        <?= forum_faccia($p, 46) ?>
        <?php
          // Gradi sopra, nome sotto, allineati a sinistra accanto alla faccia. I pezzi sono
          // gli stessi di player_name() (tag, pallino di presenza, colore del grado): qui
          // sono solo montati su due righe invece che di seguito.
          $gradi = player_tag($p);
          $colore = player_name_color($p);
        ?>
        <div class="forum-msg-chi">
          <?php if ($gradi !== ''): ?><span class="forum-msg-gradi"><?= $gradi ?></span><?php endif; ?>
          <span class="forum-msg-autore">
            <?= presenza_dot($p, $p['mc_username']) ?>
            <?php if ($colore !== null): ?>
              <span class="player-rank-name colore-grado" style="<?= rank_color_style($colore) ?>"><?= h($p['mc_username']) ?></span>
            <?php else: ?>
              <?= h($p['mc_username']) ?>
            <?php endif; ?>
          </span>
        </div>
        </div>
        <?php /* I numeri di chi scrive: sotto faccia e nome, tutti e tre su una riga.
                 Sono dell'autore, non del messaggio. */ ?>
        <?php $st = $statAutori[(int) $p['user_id']] ?? ['discussioni' => 0, 'messaggi' => 0, 'mi_piace' => 0]; ?>
        <ul class="forum-msg-stat">
          <li><strong><?= $st['discussioni'] ?></strong> <span>discussioni</span></li>
          <li><strong><?= $st['messaggi'] ?></strong> <span>messaggi</span></li>
          <li><strong><?= $st['mi_piace'] ?></strong> <span>mi piace</span></li>
        </ul>
      </div>
      <div class="forum-msg-corpo">
        <div class="forum-msg-testata">
          <?php if ($i === 0): ?><span class="forum-etichetta">Apertura</span><?php endif; ?>
          <time><?= h(time_ago($p['created_at'])) ?></time>
          <a class="forum-msg-numero" href="#m<?= $i + 1 ?>">#<?= $i + 1 ?></a>
        </div>
        <div class="forum-msg-testo"><?= nl2br(h($p['body'])) ?></div>
        <?php
          // Ancora del messaggio per il ritorno dal mi piace (l'id "m<n>" e' gia' preso
          // dalla numerazione visibile, che dipende dalla posizione e non dall'id vero).
          $n = $miPiace['conta'][(int) $p['id']] ?? 0;
          $mio = isset($miPiace['miei'][(int) $p['id']]);
          $eSuo = $me && (int) $p['user_id'] === (int) $me['id'];
        ?>
        <div class="forum-msg-piede">
          <span class="forum-ancora" id="p<?= (int) $p['id'] ?>" aria-hidden="true"></span>
          <?php if ($me && !$eSuo): ?>
            <form method="post" class="forum-mipiace-form">
              <?= csrf_field() ?>
              <input type="hidden" name="azione" value="mi-piace">
              <input type="hidden" name="post_id" value="<?= (int) $p['id'] ?>">
              <button type="submit" class="forum-mipiace<?= $mio ? ' is-attivo' : '' ?>"
                      title="<?= $mio ? 'Togli il mi piace' : 'Mi piace' ?>"
                      aria-pressed="<?= $mio ? 'true' : 'false' ?>">
                <span class="forum-mipiace-icona" aria-hidden="true">👍</span>
                <span class="forum-mipiace-n"><?= $n ?></span>
              </button>
            </form>
          <?php else: ?>
            <span class="forum-mipiace is-fermo"
                  title="<?= $eSuo ? 'Sono gli altri a poter approvare i tuoi messaggi' : 'Accedi per approvare' ?>">
              <span class="forum-mipiace-icona" aria-hidden="true">👍</span>
              <span class="forum-mipiace-n"><?= $n ?></span>
            </span>
          <?php endif; ?>
        </div>
      </div>
    </li>
  <?php endforeach; ?>
</ol>

<div id="fondo"></div>

<?php if ($error): ?><div class="alert alert-error"><?= h($error) ?></div><?php endif; ?>

<?php if ((int)$topic['is_locked'] === 1): ?>
  <div class="alert alert-info">Questa discussione è chiusa: non è più possibile rispondere.</div>
<?php elseif (is_logged_in()): ?>
  <?php /* Stessa impaginazione dei messaggi: a sinistra chi scrive (nella colonna larga
           uguale), a destra il modulo. Cosi' la casella parte esattamente dove partono i
           testi dei messaggi qui sopra. */ ?>
  <div class="forum-risposta">
    <?php
      // Stessa colonna dei messaggi, stessi pezzi: faccia, grado, nome e i propri numeri.
      $gradiMiei = player_tag($me);
      $coloreMio = player_name_color($me);
      $stMie = $statAutori[(int) $me['id']]
          ?? (forum_user_stats([(int) $me['id']])[(int) $me['id']]
              ?? ['discussioni' => 0, 'messaggi' => 0, 'mi_piace' => 0]);
    ?>
    <div class="forum-msg-lato forum-risposta-lato">
      <div class="forum-msg-identita">
        <?= forum_faccia($me, 46) ?>
        <div class="forum-msg-chi">
          <?php if ($gradiMiei !== ''): ?><span class="forum-msg-gradi"><?= $gradiMiei ?></span><?php endif; ?>
          <span class="forum-msg-autore">
            <?php if ($coloreMio !== null): ?>
              <span class="player-rank-name colore-grado" style="<?= rank_color_style($coloreMio) ?>"><?= h($me['mc_username']) ?></span>
            <?php else: ?>
              <?= h($me['mc_username']) ?>
            <?php endif; ?>
          </span>
        </div>
      </div>
      <ul class="forum-msg-stat">
        <li><strong><?= $stMie['discussioni'] ?></strong> <span>discussioni</span></li>
        <li><strong><?= $stMie['messaggi'] ?></strong> <span>messaggi</span></li>
        <li><strong><?= $stMie['mi_piace'] ?></strong> <span>mi piace</span></li>
      </ul>
    </div>
    <form method="post" class="stack">
      <?= csrf_field() ?>
      <div>
        <label for="body">La tua risposta</label>
        <textarea id="body" name="body" rows="6" placeholder="Scrivi qui…"></textarea>
      </div>
      <button type="submit" class="btn btn-accent">Invia risposta</button>
    </form>
  </div>
<?php else: ?>
  <div class="alert alert-info">
    <a href="/login">Accedi</a> per rispondere.
  </div>
<?php endif; ?>

<?php require __DIR__ . '/../../includes/footer.php'; ?>
