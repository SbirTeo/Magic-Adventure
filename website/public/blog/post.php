<?php
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/immagini.php';

$slug = $_GET['slug'] ?? '';
$stmt = db()->prepare("SELECT b.*, u.mc_username, u.is_admin, u.last_seen, " . RANK_SELECT_SQL
    . " FROM blog_posts b LEFT JOIN users u ON u.id = b.author_user_id" . rank_join_sql()
    . " WHERE b.slug = ? AND b.published = 1 AND b.deleted_at IS NULL");
$stmt->execute([$slug]);
$post = $stmt->fetch();

if (!$post) {
    http_response_code(404);
    $page_title = 'Articolo non trovato';
    require __DIR__ . '/../../includes/header.php';
    echo '<div class="panel"><p>Articolo non trovato.</p><a href="/">← Torna al blog</a></div>';
    require __DIR__ . '/../../includes/footer.php';
    exit;
}

$page_title = $post['title'];
$active = 'home';

// Biglietto da visita dell'articolo: descrizione ricavata dal testo, copertina come
// anteprima social e la scheda "Article" per Google (autore, date, immagine).
require_once __DIR__ . '/../../includes/seo.php';
$page_description = seo_riassunto($post['subtitle'] ?: $post['body']);
if (trim((string) ($post['subtitle'] ?? '')) !== '') {
    $page_title_full = $post['title'] . ' — ' . $post['subtitle'];
}
$page_type = 'article';
$page_published = $post['created_at'];
$page_modified = $post['updated_at'] ?: $post['created_at'];
$page_author = $post['mc_username'] ?: null;
if (!empty($post['cover_image'])) $page_image = $post['cover_image'];
$page_jsonld = [
    [
        '@context' => 'https://schema.org',
        '@type' => 'BlogPosting',
        'headline' => mb_substr($post['title'], 0, 110),
        'description' => $page_description,
        'datePublished' => seo_data($post['created_at']),
        'dateModified' => seo_data($post['updated_at'] ?: $post['created_at']),
        'mainEntityOfPage' => seo_url('/blog/' . $post['slug']),
        'inLanguage' => 'it-IT',
        'publisher' => ['@id' => seo_url('/#organizzazione')],
    ] + ($post['mc_username'] ? ['author' => ['@type' => 'Person', 'name' => $post['mc_username']]] : [])
      + (!empty($post['cover_image']) ? ['image' => seo_url($post['cover_image'])] : []),
    seo_briciole(['Home' => '/', $post['title'] => '/blog/' . $post['slug']]),
];

require __DIR__ . '/../../includes/header.php';
?>
<h1 class="page-title titolo-articolo"><?= h($post['title']) ?></h1>
<?php if (trim((string) ($post['subtitle'] ?? '')) !== ''): ?>
  <?php /* Va a capo sotto al titolo: e' una seconda riga, non una coda dopo i due punti. */ ?>
  <p class="sottotitolo-articolo"><?= h($post['subtitle']) ?></p>
<?php endif; ?>
<div class="panel post-panel">
  <?php if (can('blog.edit')): ?>
    <a href="/manage?section=blog_edit&id=<?= $post['id'] ?>" class="card-edit-btn" title="Modifica" aria-label="Modifica">✎</a>
  <?php endif; ?>
  <?php
    // La fascia in cima all'articolo e' larga e bassa: ci sta bene una immagine sdraiata,
    // mentre una locandina in piedi (come quelle del server) verrebbe fuori grande come un
    // francobollo in mezzo a due bande vuote. Quelle verticali quindi NON si mettono qui:
    // restano la copertina della tessera in home e l'anteprima quando si incolla il link,
    // dove il taglio orizzontale funziona.
    $__misureCop = !empty($post['cover_image']) ? immagine_misure($post['cover_image']) : null;
    $__copertinaSdraiata = !empty($post['cover_image'])
        && (!$__misureCop || $__misureCop[1] === 0 || $__misureCop[0] / $__misureCop[1] >= 1.2);
  ?>
  <?php if ($__copertinaSdraiata): ?>
    <?php /* E' l'immagine grande in cima: descritta (per chi non la vede e per Google) e
             segnata come prioritaria, perche' e' quella che decide quando la pagina
             "sembra" caricata. */ ?>
    <img src="<?= h($post['cover_image']) ?>" alt="<?= h($post['title']) ?>" class="post-cover"
         fetchpriority="high" decoding="async">
  <?php endif; ?>
  <div class="meta" style="color:var(--text-dim); margin-bottom:16px;">
    <?= time_ago($post['created_at']) ?><?= $post['mc_username'] ? ' · di ' . player_name($post, $post['mc_username']) : '' ?>
  </div>
  <div class="blog-body<?= corpo_e_html($post['body']) ? ' corpo-html' : '' ?>"><?= corpo_articolo($post['body']) ?></div>
</div>
<?php
  // Indirizzo da spedire in giro: quello ufficiale della pagina (niente ?slug=, niente
  // parametri appiccicati), lo stesso che sta nel <link rel="canonical">.
  $__urlArticolo = seo_url('/blog/' . $post['slug']);
  $__testoCondivisione = trim((string) ($post['subtitle'] ?? '')) !== ''
      ? $post['subtitle']
      : seo_riassunto($post['body'], 120);
?>
<div class="condividi" data-condividi
     data-titolo="<?= h($post['title']) ?>"
     data-testo="<?= h($__testoCondivisione) ?>"
     data-url="<?= h($__urlArticolo) ?>">
  <span class="condividi-etichetta">Condividi</span>
  <div class="condividi-bottoni">
    <?php /* Sul telefono questo apre il menu di sistema (Discord, WhatsApp, quello che
             c'e'); sul computer, dove quel menu non esiste, copia il link. Il copione
             cambia da solo la scritta del pulsante: vedi site.js. */ ?>
    <button type="button" class="btn btn-accent btn-small" data-azione="condividi">Condividi</button>
    <a class="btn btn-ghost btn-small" data-azione="whatsapp"
       href="https://wa.me/?text=<?= rawurlencode($post['title'] . ' — ' . $__urlArticolo) ?>"
       target="_blank" rel="noopener">WhatsApp</a>
    <a class="btn btn-ghost btn-small" data-azione="telegram"
       href="https://t.me/share/url?url=<?= rawurlencode($__urlArticolo) ?>&text=<?= rawurlencode($post['title']) ?>"
       target="_blank" rel="noopener">Telegram</a>
    <a class="btn btn-ghost btn-small" data-azione="x"
       href="https://twitter.com/intent/tweet?url=<?= rawurlencode($__urlArticolo) ?>&text=<?= rawurlencode($post['title']) ?>"
       target="_blank" rel="noopener">X</a>
    <button type="button" class="btn btn-ghost btn-small" data-azione="copia">Copia link</button>
  </div>
</div>

<a href="/">← Tutti gli articoli</a>

<?php require __DIR__ . '/../../includes/footer.php'; ?>
