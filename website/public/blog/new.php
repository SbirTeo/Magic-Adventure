<?php
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/campo_immagine.php';   // campo immagine col pulsante Scegli
require_perm('blog.create');

$error = null;

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    csrf_check();
    $title = trim($_POST['title'] ?? '');
    $body = trim($_POST['body'] ?? '');
    $coverImage = trim($_POST['cover_image'] ?? '') ?: null;

    if ($title === '' || $body === '') {
        $error = 'Titolo e testo sono obbligatori.';
    } else {
        $slug = slugify($title);
        // Evita slug duplicati
        $base = $slug;
        $i = 2;
        $check = db()->prepare('SELECT COUNT(*) FROM blog_posts WHERE slug = ?');
        while (true) {
            $check->execute([$slug]);
            if ((int)$check->fetchColumn() === 0) break;
            $slug = $base . '-' . $i++;
        }

        $ins = db()->prepare('INSERT INTO blog_posts (title, slug, cover_image, body, author_user_id) VALUES (?, ?, ?, ?, ?)');
        $ins->execute([$title, $slug, $coverImage, $body, current_user()['id']]);
        redirect('/blog/' . urlencode($slug));
    }
}

$page_title = 'Nuovo articolo';
$active = 'home';
require __DIR__ . '/../../includes/header.php';
?>
<h1 class="page-title">Nuovo articolo</h1>
<?php if ($error): ?><div class="alert alert-error"><?= h($error) ?></div><?php endif; ?>
<div class="panel">
  <form method="post" class="stack">
    <?= csrf_field() ?>
    <div>
      <label for="title">Titolo</label>
      <input type="text" id="title" name="title" value="<?= h($_POST['title'] ?? '') ?>">
    </div>
    <?php campo_immagine('cover_image', 'cover_image', (string) ($_POST['cover_image'] ?? ''),
        'Immagine di presentazione (opzionale)',
        'Incolla un indirizzo oppure carica un file dal computer con <strong>Scegli</strong>.'); ?>
    <div>
      <label for="body">Testo</label>
      <?php require __DIR__ . '/../../includes/editor_toolbar.php'; ?>
      <textarea id="body" name="body" rows="14"><?= h($_POST['body'] ?? '') ?></textarea>
    </div>
    <button type="submit" class="btn btn-accent">Pubblica</button>
  </form>
</div>
<script src="/assets/js/editor.js"></script>
<script src="/assets/js/carica-immagine.js"></script>
<?php require __DIR__ . '/../../includes/footer.php'; ?>
