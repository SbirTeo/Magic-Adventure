<?php
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/helpers.php';

$slug = $_GET['slug'] ?? '';
$stmt = db()->prepare('SELECT title, body FROM site_pages WHERE slug = ?');
$stmt->execute([$slug]);
$page = $stmt->fetch();

if (!$page) {
    http_response_code(404);
    $page_title = 'Pagina non trovata';
    require __DIR__ . '/../includes/header.php';
    echo '<div class="panel"><p>Pagina non trovata.</p><a href="/">← Torna alla home</a></div>';
    require __DIR__ . '/../includes/footer.php';
    exit;
}

$page_title = $page['title'];
require_once __DIR__ . '/../includes/seo.php';
$page_description = seo_riassunto($page['body']);
$page_jsonld = seo_briciole(['Home' => '/', $page['title'] => '/pagina/' . $slug]);
require __DIR__ . '/../includes/header.php';
?>
<h1 class="page-title"><?= h($page['title']) ?></h1>
<div class="panel">
  <div class="blog-body<?= corpo_e_html($page['body']) ? ' corpo-html' : '' ?>"><?= corpo_articolo($page['body']) ?></div>
</div>

<?php require __DIR__ . '/../includes/footer.php'; ?>
