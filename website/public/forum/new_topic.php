<?php
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_login();

$slug = $_GET['category'] ?? ($_POST['category'] ?? '');
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

// Una categoria che contiene delle sezioni non ospita discussioni proprie: si scrive
// dentro una delle sue. Il controllo sta qui, non solo nei pulsanti, perche' l'indirizzo
// si puo' scrivere a mano.
$conSezioni = db()->prepare('SELECT COUNT(*) FROM forum_categories WHERE parent_id = ?');
$conSezioni->execute([$cat['id']]);
if ((int) $conSezioni->fetchColumn() > 0) {
    redirect('/forum/' . urlencode($cat['slug']));
}

$error = null;

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    csrf_check();
    $title = trim($_POST['title'] ?? '');
    $body = trim($_POST['body'] ?? '');

    if ($title === '' || $body === '') {
        $error = 'Titolo e testo sono obbligatori.';
    } else {
        $pdo = db();
        $pdo->beginTransaction();
        $ins = $pdo->prepare('INSERT INTO forum_topics (category_id, user_id, title) VALUES (?, ?, ?)');
        $ins->execute([$cat['id'], current_user()['id'], $title]);
        $topicId = (int)$pdo->lastInsertId();
        $insPost = $pdo->prepare('INSERT INTO forum_posts (topic_id, user_id, body) VALUES (?, ?, ?)');
        $insPost->execute([$topicId, current_user()['id'], $body]);
        $pdo->commit();
        redirect('/forum/discussione/' . $topicId);
    }
}

// Chi scrive deve vedere dove sta scrivendo: se questa e' una sezione, il percorso mostra
// anche la categoria che la contiene.
$madre = null;
if ($cat['parent_id']) {
    $q = db()->prepare('SELECT name, slug FROM forum_categories WHERE id = ?');
    $q->execute([$cat['parent_id']]);
    $madre = $q->fetch() ?: null;
}

$page_title = 'Nuova discussione';
$active = 'forum';
require __DIR__ . '/../../includes/header.php';
?>
<nav class="briciole">
  <a href="/forum">Forum</a> <span aria-hidden="true">/</span>
  <?php if ($madre): ?>
    <a href="/forum/<?= urlencode($madre['slug']) ?>"><?= h($madre['name']) ?></a> <span aria-hidden="true">/</span>
  <?php endif; ?>
  <a href="/forum/<?= urlencode($cat['slug']) ?>"><?= h($cat['name']) ?></a> <span aria-hidden="true">/</span>
  <strong>Nuova discussione</strong>
</nav>
<h1 class="page-title">Nuova discussione</h1>
<p class="forum-sottotitolo" style="margin-bottom:18px;">
  La stai aprendo in <strong><?= h($cat['name']) ?></strong><?= $madre ? ', dentro ' . h($madre['name']) : '' ?>.
</p>
<?php if ($error): ?><div class="alert alert-error"><?= h($error) ?></div><?php endif; ?>
<div class="panel">
  <form method="post" class="stack">
    <?= csrf_field() ?>
    <input type="hidden" name="category" value="<?= h($cat['slug']) ?>">
    <div>
      <label for="title">Titolo</label>
      <input type="text" id="title" name="title" value="<?= h($_POST['title'] ?? '') ?>">
    </div>
    <div>
      <label for="body">Messaggio</label>
      <textarea id="body" name="body" rows="10"><?= h($_POST['body'] ?? '') ?></textarea>
    </div>
    <button type="submit" class="btn btn-accent">Pubblica discussione</button>
  </form>
</div>
<?php require __DIR__ . '/../../includes/footer.php'; ?>
