<?php
/**
 * Mappa del sito per i motori di ricerca: /sitemap.xml (nginx la manda qui).
 *
 * Si costruisce da sola leggendo il database, quindi un articolo nuovo o una discussione
 * nuova ci finiscono dentro senza che nessuno debba ricordarsi di aggiornare un elenco.
 * Restano fuori le pagine private (accesso, gestionale, acquisto) — quelle non hanno
 * niente da fare su Google, vedi SEO_PERCORSI_PRIVATI in includes/seo.php.
 */
require_once __DIR__ . '/../includes/db.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/seo.php';

header('Content-Type: application/xml; charset=UTF-8');
// Mezz'ora di cache: la sitemap la leggono i robot, non serve rifarla a ogni passaggio.
header('Cache-Control: public, max-age=1800');

/** @var array<string,array{0:string,1:?string,2:string}> percorso => percorso, ultima modifica, importanza */
$voci = [];
$add = function (string $percorso, ?string $modifica = null, string $priorita = '0.5') use (&$voci) {
    // Lo stesso indirizzo puo' arrivare da due parti (le pagine fisse qui sotto e le voci
    // del menu, che spesso puntano proprio a quelle): nella mappa deve comparire UNA volta,
    // se no si dichiara a Google un doppione che poi lui deve andare a scartare.
    $percorso = $percorso !== '/' ? rtrim($percorso, '/') : '/';
    if (isset($voci[$percorso])) return;
    $voci[$percorso] = [$percorso, $modifica, $priorita];
};

// --- Pagine fisse -------------------------------------------------------------------
$add('/', null, '1.0');
$add('/forum', null, '0.8');
$add('/store', null, '0.8');
$add('/tutorial', null, '0.7');
$add('/classifiche', null, '0.6');
$add('/utenti', null, '0.5');

// Le voci del menu possono puntare a pagine create dal gestionale: quelle interne entrano.
try {
    foreach (db()->query('SELECT url FROM nav_items WHERE enabled = 1')->fetchAll() as $v) {
        $u = (string) $v['url'];
        if (str_starts_with($u, '/') && !str_contains($u, '?')) $add($u, null, '0.6');
    }
} catch (PDOException) { /* menu assente: pazienza, le fisse ci sono gia' */ }

// --- Pagine di testo (regolamento e le altre create dal gestionale) ------------------
foreach (db()->query('SELECT slug, updated_at FROM site_pages')->fetchAll() as $p) {
    $percorso = $p['slug'] === 'regolamento' ? '/regolamento' : '/pagina/' . $p['slug'];
    $add($percorso, $p['updated_at'], '0.6');
}

// --- Articoli del blog --------------------------------------------------------------
foreach (db()->query('SELECT slug, created_at, updated_at FROM blog_posts
                      WHERE published = 1 AND deleted_at IS NULL
                      ORDER BY created_at DESC')->fetchAll() as $b) {
    $add('/blog/' . $b['slug'], $b['updated_at'] ?: $b['created_at'], '0.7');
}

// --- Forum: categorie e discussioni --------------------------------------------------
foreach (db()->query('SELECT slug FROM forum_categories ORDER BY sort_order, id')->fetchAll() as $c) {
    $add('/forum/' . $c['slug'], null, '0.6');
}
foreach (db()->query('SELECT id, last_post_at FROM forum_topics
                      ORDER BY last_post_at DESC LIMIT 2000')->fetchAll() as $t) {
    $add('/forum/discussione/' . (int) $t['id'], $t['last_post_at'], '0.5');
}

// --- Store: la pagina dedicata di ogni pacchetto in vendita --------------------------
foreach (db()->query('SELECT slug, created_at, updated_at FROM store_packages
                      WHERE enabled = 1 ORDER BY sort_order, id')->fetchAll() as $s) {
    $add('/pacchetto/' . $s['slug'], $s['updated_at'] ?: $s['created_at'], '0.6');
}

echo '<?xml version="1.0" encoding="UTF-8"?>', "\n";
echo '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">', "\n";
?>
<?php foreach ($voci as [$percorso, $modifica, $priorita]): ?>
  <url>
    <loc><?= h(seo_url($percorso)) ?></loc>
<?php if ($modifica): ?>
    <lastmod><?= h((string) seo_data($modifica)) ?></lastmod>
<?php endif; ?>
    <priority><?= $priorita ?></priority>
  </url>
<?php endforeach; ?>
</urlset>
