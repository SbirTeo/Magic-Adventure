<?php
/**
 * Pagina dedicata di un pacchetto dello store: /pacchetto/<slug>
 * Mostra la descrizione estesa, cosa si ottiene e il prezzo, con lo stesso pulsante
 * di acquisto delle card (il prezzo non passa mai dal browser, vedi store/checkout.php).
 */
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/paypal.php';
require_once __DIR__ . '/../includes/store_card.php';

$slug = $_GET['slug'] ?? '';
$q = db()->prepare('SELECT p.*, c.name AS cat_name, c.border_color AS cat_border
                    FROM store_packages p
                    LEFT JOIN store_categories c ON c.id = p.category_id
                    WHERE p.slug = ? AND p.enabled = 1');
$q->execute([$slug]);
$pkg = $q->fetch();

if (!$pkg) {
    http_response_code(404);
    $page_title = 'Pacchetto non trovato';
    require __DIR__ . '/../includes/header.php';
    echo '<div class="panel"><p>Questo pacchetto non esiste o non è più disponibile.</p>'
       . '<a href="/store" class="btn btn-ghost">← Torna allo store</a></div>';
    require __DIR__ . '/../includes/footer.php';
    exit;
}

$valuta = site_setting('store_currency', 'EUR');
$me = current_user();

// Stesse voci "cosa ottieni" delle card: una per riga della descrizione breve.
$voci = array_values(array_filter(array_map('trim', explode("\n", (string) $pkg['description'])), fn($r) => $r !== ''));

$page_title = $pkg['name'];
$page_description = $voci ? mb_substr(implode(' · ', $voci), 0, 160) : 'Pacchetto dello store di MAGICADVENTURE';
$active = 'store';

// Scheda del prodotto per Google: con prezzo e disponibilita' il risultato di ricerca puo'
// mostrare la cifra sotto al titolo. Il prezzo e' quello VERO (sconti compresi) e lo calcola
// store_prezzo(), la stessa funzione che disegna la card: due conti diversi sullo stesso
// pacchetto sono una promessa che poi la cassa non mantiene.
require_once __DIR__ . '/../includes/seo.php';
$__prezzo = store_prezzo($pkg);
if (!empty($pkg['image_url'])) $page_image = $pkg['image_url'];
$page_type = 'product';
$page_jsonld = [
    [
        '@context' => 'https://schema.org',
        '@type' => 'Product',
        'name' => $pkg['name'],
        'description' => seo_riassunto($pkg['long_description'] ?: $pkg['description']),
        'category' => $pkg['cat_name'] ?? 'Store',
        'url' => seo_url('/pacchetto/' . $pkg['slug']),
        'brand' => ['@type' => 'Brand', 'name' => site_setting('site_name', 'MAGICADVENTURE')],
        'offers' => [
            '@type' => 'Offer',
            'price' => number_format($__prezzo['finale'], 2, '.', ''),
            'priceCurrency' => $valuta,
            'availability' => 'https://schema.org/InStock',
            'url' => seo_url('/pacchetto/' . $pkg['slug']),
        ],
    ] + (!empty($pkg['image_url']) ? ['image' => seo_url($pkg['image_url'])] : []),
    seo_briciole(['Home' => '/', 'Store' => '/store', $pkg['name'] => '/pacchetto/' . $pkg['slug']]),
];

require __DIR__ . '/../includes/header.php';
?>
<p class="pkg-torna"><a href="/store">← Tutti i pacchetti</a></p>

<article class="pkg-scheda<?= empty($pkg['image_url']) ? ' senza-immagine' : '' ?>"
         <?php
           $stile = [];
           if (!empty($pkg['cat_border']) && is_valid_hex_color($pkg['cat_border'])) {
               $stile[] = '--store-bordo:' . $pkg['cat_border'];
           }
           if (!empty($pkg['image_url'])) {
               $stile[] = "--copertina:url('" . h($pkg['image_url']) . "')";
           }
           echo $stile ? ' style="' . implode(';', $stile) . '"' : '';
         ?>>
  <div class="pkg-copertina" aria-hidden="true"></div>

  <div class="pkg-corpo">
    <div class="pkg-intestazione">
      <span class="store-card-cat"><?= h($pkg['cat_name'] ?? 'Altro') ?></span>
      <?php if (!empty($pkg['featured'])): ?>
        <span class="pkg-evidenza">★ In promozione</span>
      <?php endif; ?>
    </div>

    <h1><?= h($pkg['name']) ?></h1>

    <?php if (trim((string) $pkg['long_description']) !== ''): ?>
      <div class="pkg-testo"><?= nl2br(h($pkg['long_description'])) ?></div>
    <?php endif; ?>

    <?php if ($voci): ?>
      <h2 class="pkg-sottotitolo">Cosa ottieni</h2>
      <ul class="store-card-voci pkg-voci">
        <?php foreach ($voci as $voce): ?>
          <li><?= h($voce) ?></li>
        <?php endforeach; ?>
      </ul>
    <?php endif; ?>

    <div class="pkg-acquisto">
      <div>
        <?php
          // Stesso calcolo della vetrina (store_card.php): pacchetto -> categoria -> store.
          $prezzo = store_prezzo($pkg);
        ?>
        <span class="pkg-prezzo<?= $prezzo['tipo'] ? ' is-scontato' : '' ?>">
          <?php if ($prezzo['tipo']): ?>
            <s class="store-prezzo-pieno"><?= h(number_format($prezzo['pieno'], 2, ',', '.')) ?></s>
          <?php endif; ?>
          <?= h(number_format($prezzo['finale'], 2, ',', '.')) ?> <small><?= h($valuta) ?></small>
          <?php if ($prezzo['tipo']): ?>
            <span class="store-sconto"><?= h($prezzo['etichetta']) ?></span>
          <?php endif; ?>
        </span>
        <span class="pkg-consegna">
          <?php if ($me): ?>
            Consegna in gioco su <strong><?= h($me['mc_username']) ?></strong>, entro pochi secondi dal pagamento.
          <?php else: ?>
            Accedi con il tuo account Minecraft per acquistare.
          <?php endif; ?>
        </span>
      </div>
      <?php if (!paypal_ready()): ?>
        <button type="button" class="btn btn-gold" disabled>Prossimamente</button>
      <?php elseif (!is_logged_in()): ?>
        <a href="/login" class="btn btn-gold">Accedi</a>
      <?php else: ?>
        <form method="post" action="/store/checkout">
          <?= csrf_field() ?>
          <input type="hidden" name="package" value="<?= h($pkg['slug']) ?>">
          <button type="submit" class="btn btn-gold">Acquista</button>
        </form>
      <?php endif; ?>
    </div>

    <?php if (is_admin()): ?>
      <p class="pkg-admin"><a href="/manage?section=store_pkg_edit&id=<?= (int) $pkg['id'] ?>">Modifica questo pacchetto</a></p>
    <?php endif; ?>
  </div>
</article>

<p class="store-nota">
  I pagamenti sono gestiti da PayPal: il sito non vede né conserva i dati della tua carta.
  Gli acquisti sono legati all'account Minecraft con cui hai fatto l'accesso.
</p>

<?php require __DIR__ . '/../includes/footer.php'; ?>
