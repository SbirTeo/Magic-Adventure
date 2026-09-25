<?php
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/forum_ui.php';

$slug = $_GET['slug'] ?? '';
$stmt = db()->prepare('SELECT * FROM forum_categories WHERE slug = ?');
$stmt->execute([$slug]);
$cat = $stmt->fetch();

if (!$cat) {
    http_response_code(404);
    $page_title = 'Categoria non trovata';
    require __DIR__ . '/../../includes/header.php';
    echo '<div class="panel"><p>Categoria non trovata.</p><a href="/forum">← Torna al forum</a></div>';
    require __DIR__ . '/../../includes/footer.php';
    exit;
}

/** Discussioni di una o piu' categorie, gia' ordinate come vanno mostrate. */
function forum_discussioni(array $categorieId): array {
    if (!$categorieId) {
        return [];
    }
    $segni = implode(',', array_fill(0, count($categorieId), '?'));
    $stmt = db()->prepare("
        SELECT t.*, u.mc_username, u.mc_uuid, u.premium_uuid, u.is_admin, u.last_seen, " . RANK_SELECT_SQL . ",
               (SELECT COUNT(*) FROM forum_posts p WHERE p.topic_id = t.id) AS post_count
        FROM forum_topics t
        JOIN users u ON u.id = t.user_id" . rank_join_sql() . "
        WHERE t.category_id IN ($segni)
        ORDER BY t.is_pinned DESC, t.last_post_at DESC
    ");
    $stmt->execute($categorieId);

    $per = [];
    foreach ($stmt->fetchAll() as $riga) {
        $per[(int) $riga['category_id']][] = $riga;
    }
    return $per;
}

// L'albero serve sia per le sezioni di questa categoria (con i loro numeri) sia, quando
// siamo dentro una sezione, per la madre e per le sezioni sorelle.
$albero = forum_albero();
$ultimi = $albero['ultimi'];
$sottoCategorie = $albero['sezioni'][(int) $cat['id']] ?? [];
$haSotto = count($sottoCategorie) > 0;

// Se questa e' una sezione, la madre serve per le briciole e per le sorelle: da una
// sezione si passa a quella accanto senza tornare indietro due volte.
$madre = null;
$sorelle = [];
if ($cat['parent_id']) {
    $q = db()->prepare('SELECT * FROM forum_categories WHERE id = ?');
    $q->execute([$cat['parent_id']]);
    $madre = $q->fetch() ?: null;
    if ($madre) {
        $sorelle = $albero['sezioni'][(int) $madre['id']] ?? [];
    }
}

$topics = forum_discussioni([(int) $cat['id']])[(int) $cat['id']] ?? [];

$page_title = $cat['name'];
$active = 'forum';
require_once __DIR__ . '/../../includes/seo.php';
$page_description = seo_riassunto($cat['description'] ?: ('Discussioni della sezione ' . $cat['name']
    . ' sul forum di MAGICADVENTURE, server Minecraft italiano con fazioni.'));
$briciole = ['Home' => '/', 'Forum' => '/forum'];
if ($madre) {
    $briciole[$madre['name']] = '/forum/' . $madre['slug'];
}
$briciole[$cat['name']] = '/forum/' . $cat['slug'];
$page_jsonld = seo_briciole($briciole);
require __DIR__ . '/../../includes/header.php';

/** Una riga dell'elenco discussioni. La tinta e' quella della categoria che la contiene. */
$rigaDiscussione = function (array $t, int $i): void { ?>
  <article class="forum-riga<?= $t['is_pinned'] ? ' is-fissata' : '' ?><?= $t['is_locked'] ? ' is-chiusa' : '' ?>"
           style="--i:<?= $i ?>">
    <a class="forum-riga-link" href="/forum/discussione/<?= (int) $t['id'] ?>">Apri <?= h($t['title']) ?></a>
    <?= forum_faccia($t, 40) ?>
    <div class="forum-riga-testo">
      <div class="forum-riga-titolo">
        <?php if ($t['is_pinned']): ?><span class="forum-segno is-fissata" title="In evidenza">📌</span><?php endif; ?>
        <?php if ($t['is_locked']): ?><span class="forum-segno is-chiusa" title="Chiusa">🔒</span><?php endif; ?>
        <?= h($t['title']) ?>
      </div>
      <div class="forum-riga-meta">
        di <?= player_name($t, $t['mc_username']) ?> · aperta <?= h(time_ago($t['created_at'])) ?>
        <?php if ($t['last_post_at'] && $t['last_post_at'] !== $t['created_at']): ?>
          · ultimo messaggio <?= h(time_ago($t['last_post_at'])) ?>
        <?php endif; ?>
      </div>
    </div>
    <div class="forum-riga-numeri">
      <span><?= forum_conta(max(0, (int) $t['post_count'] - 1), 'risposta', 'risposte') ?></span>
      <span><?= forum_conta((int) $t['views'], 'visita', 'visite') ?></span>
    </div>
  </article>
<?php };
?>
<nav class="briciole">
  <a href="/forum">Forum</a> <span aria-hidden="true">/</span>
  <?php if ($madre): ?>
    <a href="/forum/<?= urlencode($madre['slug']) ?>"><?= h($madre['name']) ?></a> <span aria-hidden="true">/</span>
  <?php endif; ?>
  <strong><?= h($cat['name']) ?></strong>
</nav>

<div class="forum-testata">
  <div>
    <h1 class="page-title"><?= h($cat['name']) ?></h1>
    <p class="forum-sottotitolo"><?= h($cat['description']) ?></p>
  </div>
  <?php /* Con delle sezioni qui non si apre niente: si sceglie in quale sezione scrivere. */ ?>
  <?php if (is_logged_in() && !$haSotto): ?>
    <a href="/forum/new_topic?category=<?= urlencode($cat['slug']) ?>" class="btn btn-green">+ Nuova discussione</a>
  <?php endif; ?>
</div>

<?php if ($madre && count($sorelle) > 1): ?>
  <?php /* Da una sezione si salta a quella accanto: e' il movimento piu' frequente. */ ?>
  <nav class="forum-sorelle" aria-label="Altre sezioni">
    <span class="forum-etichetta">Sezioni di <?= h($madre['name']) ?></span>
    <?php foreach ($sorelle as $s): ?>
      <a href="/forum/<?= urlencode($s['slug']) ?>"
         class="<?= (int) $s['id'] === (int) $cat['id'] ? 'is-qui' : '' ?>"
         <?= (int) $s['id'] === (int) $cat['id'] ? 'aria-current="page"' : '' ?>><?= h($s['name']) ?></a>
    <?php endforeach; ?>
  </nav>
<?php endif; ?>

<?php if ($haSotto): ?>
  <?php /* Le sezioni hanno una pagina propria: qui si sceglie dove entrare, con le stesse
           righe che si vedono in prima pagina. */ ?>
  <h2 class="forum-sezione-titolo">Scegli una sezione</h2>
  <div class="forum-sezioni forum-sezioni-sole">
    <?php foreach ($sottoCategorie as $s): ?>
      <?php forum_riga_sezione($s, $ultimi[(int) $s['id']] ?? null); ?>
    <?php endforeach; ?>
  </div>

  <?php if ($topics): ?>
    <?php /* Discussioni aperte nella categoria PRIMA che avesse delle sezioni: restano
             leggibili, ma qui non se ne aprono di nuove. */ ?>
    <h2 class="forum-sezione-titolo forum-titolo-staccato">Discussioni della categoria</h2>
    <div class="forum-discussioni">
      <?php foreach ($topics as $i => $t) { $rigaDiscussione($t, $i); } ?>
    </div>
  <?php endif; ?>

<?php elseif (!$topics): ?>
  <div class="panel">
    <p>Nessuna discussione ancora in questa <?= $madre ? 'sezione' : 'categoria' ?>.
      <?php if (is_logged_in()): ?>
        Aprila tu: <a href="/forum/new_topic?category=<?= urlencode($cat['slug']) ?>">scrivi la prima</a>.
      <?php else: ?>
        <a href="/login">Accedi</a> per aprire la prima.
      <?php endif; ?>
    </p>
  </div>
<?php else: ?>
  <div class="forum-discussioni">
    <?php foreach ($topics as $i => $t) { $rigaDiscussione($t, $i); } ?>
  </div>
<?php endif; ?>

<?php require __DIR__ . '/../../includes/footer.php'; ?>
