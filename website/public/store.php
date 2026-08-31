<?php
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/paypal.php';
require_once __DIR__ . '/../includes/store_card.php';
require_once __DIR__ . '/../includes/vip_banner.php';   // la promozione in cima, uguale alla home
require_once __DIR__ . '/../includes/sidebar.php';      // colonna del sito, opzionale anche qui
require_once __DIR__ . '/../includes/obiettivo.php';    // barra dell'obiettivo di raccolta

$page_title = 'Store';
$page_description = 'Gradi VIP, kit e vantaggi per il server Minecraft MAGICADVENTURE: '
    . 'acquisto sicuro con PayPal e consegna automatica in gioco.';
$active = 'store';
require_once __DIR__ . '/../includes/seo.php';
$page_jsonld = seo_briciole(['Home' => '/', 'Store' => '/store']);

// Categorie e pacchetti arrivano dal gestionale (/manage?section=store).
// I pacchetti senza categoria finiscono in un gruppo finale, cosi' non spariscono mai.
$cats = db()->query('SELECT * FROM store_categories WHERE enabled = 1 ORDER BY sort_order, name')->fetchAll();
$pkgs = db()->query('SELECT * FROM store_packages WHERE enabled = 1 ORDER BY sort_order, name')->fetchAll();
$valuta = site_setting('store_currency', 'EUR');

// Ordino i pacchetti come sono ordinate le categorie, cosi' la griglia segue il gestionale
$posizione = [];
foreach ($cats as $i => $c) {
    $posizione[(int) $c['id']] = $i;
}
usort($pkgs, function ($a, $b) use ($posizione) {
    $pa = $posizione[(int) $a['category_id']] ?? 999;
    $pb = $posizione[(int) $b['category_id']] ?? 999;
    return $pa <=> $pb ?: ((int) $a['sort_order'] <=> (int) $b['sort_order']);
});

// Il pacchetto in evidenza non ha piu' una card sua qui: lo promuove il banner in cima
// (includes/vip_banner.php), che se lo prende da solo. Nell'elenco resta una card come
// tutte le altre, nella riga della sua categoria.

// ---------------------------------------------------------------------
// Sidebar: ultimi acquisti e miglior sostenitore. Tutto configurabile da
// /manage?section=store (pannello "Sidebar dello store").
// ---------------------------------------------------------------------
$sidebarAttiva = site_setting('store_sidebar_enabled', '1') === '1';
// Colonna del sito (chat, scheda giocatore, "sul sito ora"): si accende dalla voce di menu
// Store in "Pagine e menu". Qui non apre una colonna sua: i suoi riquadri vanno SOTTO a
// quelli dello store, nella stessa colonna.
$sidebarSito = pagina_con_sidebar('/store');
$colonnaDestra = $sidebarAttiva || $sidebarSito;
$quantiRecenti = max(0, min(20, (int) site_setting('store_sidebar_recent_count', '5')));
$mostraImporto = site_setting('store_sidebar_show_amount', '1') === '1';
$mostraPacchetto = site_setting('store_sidebar_show_package', '1') === '1';
$mostraData = site_setting('store_sidebar_show_date', '1') === '1';
$mostraNome = site_setting('store_sidebar_show_name', '1') === '1';
$mostraRank = site_setting('store_sidebar_show_rank', '1') === '1';

/** Nome del giocatore come va mostrato nella colonna: coi tag del grado, senza, o niente. */
$nomeGiocatore = function (array $riga) use ($mostraNome, $mostraRank): string {
    if (!$mostraNome) {
        return '';
    }
    return $mostraRank
        ? player_name($riga, $riga['mc_username'])
        : h((string) $riga['mc_username']);
};
$topAttivo = site_setting('store_sidebar_top_enabled', '1') === '1';
$giorniTop = max(0, min(3650, (int) site_setting('store_sidebar_top_days', '0'))); // 0 = da sempre
$contaManuali = site_setting('store_sidebar_include_manual', '0') === '1';

$ultimiAcquisti = [];
$topDonatore = null;

if ($sidebarAttiva) {
    // Le consegne manuali del gestionale non sono soldi incassati: di default restano fuori
    // (vedi l'interruttore nel pannello), altrimenti falserebbero la classifica.
    $soloVeri = $contaManuali ? '' : " AND (o.paypal_capture_id IS NULL OR o.paypal_capture_id NOT LIKE 'MANUALE-%') ";

    if ($quantiRecenti > 0) {
        $q = db()->prepare(
            'SELECT o.mc_uuid, o.mc_username, o.package_name, o.price, o.currency, o.paid_at, us.last_seen, '
            . RANK_SELECT_SQL
            . ' FROM store_orders o LEFT JOIN mc_ranks r ON r.mc_uuid = o.mc_uuid COLLATE utf8mb4_unicode_ci'
            . ' LEFT JOIN users us ON us.mc_uuid = o.mc_uuid COLLATE utf8mb4_unicode_ci'
            . " WHERE o.status = 'paid' " . $soloVeri
            . ' ORDER BY o.paid_at DESC, o.id DESC LIMIT :n'
        );
        $q->bindValue(':n', $quantiRecenti, PDO::PARAM_INT);
        $q->execute();
        $ultimiAcquisti = $q->fetchAll();
    }

    if ($topAttivo) {
        // Il periodo e' un intero gia' validato: entra nella query perche' MariaDB non accetta
        // un parametro dentro INTERVAL.
        $periodo = $giorniTop > 0 ? " AND o.paid_at >= DATE_SUB(NOW(), INTERVAL {$giorniTop} DAY) " : '';
        $q = db()->query(
            // I campi del grado sono uno solo per giocatore (mc_ranks ha l'uuid come chiave):
            // il MAX() serve solo a soddisfare il GROUP BY, non sceglie davvero fra piu' valori.
            'SELECT o.mc_uuid, MAX(o.mc_username) AS mc_username, SUM(o.price) AS totale,
                    COUNT(*) AS acquisti, MAX(o.currency) AS currency,
                    MAX(r.group_name) AS group_name, MAX(r.group_display) AS group_display,
                    MAX(r.tag_text) AS tag_text, MAX(r.tag_color) AS tag_color,
                    MAX(r.tags_json) AS tags_json, MAX(r.name_color) AS name_color,
                    MAX(us.last_seen) AS last_seen'
            . ' FROM store_orders o LEFT JOIN mc_ranks r ON r.mc_uuid = o.mc_uuid COLLATE utf8mb4_unicode_ci'
            . ' LEFT JOIN users us ON us.mc_uuid = o.mc_uuid COLLATE utf8mb4_unicode_ci'
            . " WHERE o.status = 'paid' " . $soloVeri . $periodo
            . ' GROUP BY o.mc_uuid ORDER BY totale DESC LIMIT 1'
        );
        $topDonatore = $q->fetch() ?: null;
    }
}

require __DIR__ . '/../includes/header.php';
?>
<?php /* In cima c'e' la stessa promozione della home: e' lo stesso pezzo di codice
         (includes/vip_banner.php), quindi si regola dal gestionale una volta sola e
         cambia in tutte e due le pagine. Il titolo resta per chi legge con lo schermo
         vocale e per i motori di ricerca, ma non si vede: lo dice gia' il banner. */ ?>
<h1 class="solo-lettori">Store</h1>
<?php vip_banner(true); ?>

<?php /* Obiettivo di raccolta: sotto la promozione, prima dei filtri. Non stampa nulla
         se e' spento o senza cifra impostata. */ ?>
<?php obiettivo_sezione(); ?>

<?php /* Niente riga di targhette qui: il nome su cui arriva la consegna si legge nella
         pagina del pacchetto, al momento che conta (quello dell'acquisto), e lo store si
         gestisce dalla matita sulle card. */ ?>

<?php if (is_admin() && !paypal_ready()):
  // Solo l'admin vede perche' i pulsanti sono spenti: agli altri basta "Prossimamente".
  $mancano = [];
  if (site_setting('paypal_enabled', '0') !== '1')      $mancano[] = 'la spunta <strong>PayPal attivo</strong>';
  if (trim(site_setting('paypal_client_id', '')) === '') $mancano[] = 'il <strong>Client ID</strong>';
  if (trim(site_setting('paypal_secret', '')) === '')    $mancano[] = 'il <strong>Secret</strong>';
?>
  <div class="alert alert-info">
    <strong>Gli acquisti sono spenti:</strong> manca <?= implode(', ', $mancano) ?>.
    Si completa in <a href="/manage?section=store#pagamenti">Gestione → Store → Pagamenti</a>; con l'ambiente
    su <em>Sandbox</em> puoi provare tutto il giro d'acquisto senza soldi veri.
  </div>
<?php endif; ?>

<?php if (isset($_GET['err'])): ?>
  <div class="alert alert-error">
    <?= $_GET['err'] === 'paypal'
        ? 'Il pagamento non è partito: riprova tra poco. Se il problema resta, avvisa lo staff.'
        : 'Questo pacchetto non è al momento acquistabile.' ?>
  </div>
<?php endif; ?>

<?php if (!$pkgs): ?>
  <div class="panel">
    <p>Il negozio è in preparazione: presto potrai acquistare rank, kit e vantaggi per sostenere il server.</p>
  </div>
<?php else: ?>

  <div class="<?= $colonnaDestra ? 'content-with-sidebar' : '' ?>">
  <div class="<?= $colonnaDestra ? 'content-main' : '' ?>">

  <?php if (count($cats) > 1): ?>
    <div class="store-filtri" id="storeFiltri">
      <button type="button" class="store-filtro is-active" data-cat="tutti">Tutto</button>
      <?php foreach ($cats as $c): ?>
        <?php
          // Colori propri del filtro: sovrascrivono le variabili globali solo su questo pulsante
          $stileFiltro = '';
          if (!empty($c['filter_active_color']) && is_valid_hex_color($c['filter_active_color'])) {
              $stileFiltro .= '--store-filtro-attivo:' . $c['filter_active_color']
                  . ';--store-filtro-attivo-testo:' . text_on_color($c['filter_active_color']);
          }
          if (!empty($c['filter_idle_color']) && is_valid_hex_color($c['filter_idle_color'])) {
              $testo = text_on_color($c['filter_idle_color']);
              $stileFiltro .= ';--store-filtro-riposo:' . $c['filter_idle_color']
                  . ';--store-filtro-riposo-testo:' . $testo
                  . ';--store-filtro-bordo:' . hex_to_rgba($testo, 0.18);
          }
        ?>
        <button type="button" class="store-filtro" data-cat="<?= (int) $c['id'] ?>"
                <?= $stileFiltro !== '' ? 'style="' . ltrim($stileFiltro, ';') . '"' : '' ?>><?= h($c['name']) ?></button>
      <?php endforeach; ?>
    </div>
  <?php endif; ?>

  <?php /* Niente card "pacchetto in evidenza" qui: la promozione e' il banner in cima,
           uguale a quello della home. Il pacchetto resta comunque nella riga della sua
           categoria, come tutti gli altri. */ ?>

  <?php
  // Una riga per categoria: si vedono due pacchetti alla volta e gli altri si raggiungono
  // trascinando la riga col mouse (o scorrendo, da telefono). $pkgs e' gia' ordinato per
  // categoria, quindi il raggruppamento mantiene l'ordine del gestionale.
  $perCategoria = [];
  foreach ($pkgs as $item) {
      $perCategoria[(int) $item['category_id']][] = $item;
  }
  ?>
  <div class="store-griglia" id="storeGriglia">
    <?php foreach ($perCategoria as $catId => $items): ?>
      <?php $altri = count($items) > 2; ?>
      <section class="store-fila-blocco<?= $altri ? ' ha-altri' : '' ?>" data-cat="<?= (int) $catId ?>">
        <?php if ($altri): ?>
          <?php /* Frecce e sfumatura esistono solo dove c'e' davvero altro da vedere: senza,
                   il terzo pacchetto della riga non si sospetta nemmeno. */ ?>
          <button type="button" class="store-freccia indietro" aria-label="Pacchetti precedenti">‹</button>
          <button type="button" class="store-freccia avanti" aria-label="Altri pacchetti">›</button>
        <?php endif; ?>
        <div class="store-fila<?= $altri ? ' ha-altri' : '' ?>">
          <?php foreach ($items as $item) {
              store_card($item);
          } ?>
        </div>
        <?php /* Barretta di scorrimento della riga: dice a che punto si e' e si puo' trascinare.
                 Sta nell'HTML sempre; e' store.js a nasconderla quando la riga ci sta tutta
                 nello schermo e non c'e' niente da scorrere. */ ?>
        <div class="store-barra" hidden>
          <div class="store-barra-pista" role="scrollbar" aria-orientation="horizontal"
               aria-label="Scorri i pacchetti di questa categoria" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0"
               tabindex="0">
            <span class="store-barra-pollice"></span>
          </div>
        </div>
      </section>
    <?php endforeach; ?>
  </div>

  <p class="store-nota">
    I pagamenti sono gestiti da PayPal: il sito non vede né conserva i dati della tua carta.
    Gli acquisti sono legati all'account Minecraft con cui hai fatto l'accesso.
  </p>

  </div><?php /* fine colonna principale */ ?>

  <?php if ($colonnaDestra): ?>
    <aside class="side-col">
      <?php if ($sidebarAttiva && $topAttivo): ?>
        <section class="store-lato">
          <h3><?= h(site_setting('store_sidebar_top_title', 'Miglior sostenitore')) ?></h3>
          <?php if ($topDonatore): ?>
            <div class="store-top">
              <?php /* La corona del miglior sostenitore la mette avatar_top(): stessa regola
                       in tutto il sito, ovunque compaia la faccia di qualcuno. */ ?>
              <?= avatar_top(
                    '<img class="store-skin store-skin-grande" src="'
                    . h(mc_avatar_url($topDonatore['mc_uuid'], 72)) . '" alt="">',
                    $topDonatore['mc_uuid'], 56) ?>

              <div class="store-top-dati">
                <?php if ($mostraNome): ?>
                  <div class="store-lato-nome"><?= $nomeGiocatore($topDonatore) ?></div>
                <?php endif; ?>
                <?php if ($mostraImporto): ?>
                  <div class="store-top-cifra">
                    <?= h(number_format((float) $topDonatore['totale'], 2, ',', '.')) ?>
                    <small><?= h($topDonatore['currency'] ?: $valuta) ?></small>
                  </div>
                <?php endif; ?>
                <div class="store-lato-meta">
                  <?= (int) $topDonatore['acquisti'] ?> acquist<?= (int) $topDonatore['acquisti'] === 1 ? 'o' : 'i' ?>
                  <?= $giorniTop > 0 ? ' · ultimi ' . $giorniTop . ' giorni' : '' ?>
                </div>
              </div>
            </div>
          <?php else: ?>
            <p class="store-lato-vuoto">Ancora nessun sostenitore: potresti essere il primo.</p>
          <?php endif; ?>
        </section>
      <?php endif; ?>

      <?php if ($sidebarAttiva && $quantiRecenti > 0): ?>
        <section class="store-lato">
          <h3><?= h(site_setting('store_sidebar_recent_title', 'Ultimi acquisti')) ?></h3>
          <?php if ($ultimiAcquisti): ?>
            <ul class="store-acquisti">
              <?php foreach ($ultimiAcquisti as $a): ?>
                <li>
                  <?= avatar_top(
                        '<img class="store-skin" src="' . h(mc_avatar_url($a['mc_uuid'], 32)) . '" alt=""'
                        . ' width="32" height="32" loading="lazy" decoding="async">',
                        $a['mc_uuid'], 32) ?>
                  <div class="store-acquisto-dati">
                    <?php if ($mostraNome): ?>
                      <div class="store-lato-nome"><?= $nomeGiocatore($a) ?></div>
                    <?php endif; ?>
                    <?php
                      // Riga sotto il nome: pacchetto e data sono indipendenti, il punto di
                      // separazione compare solo quando ci sono davvero entrambi.
                      $pezzi = [];
                      if ($mostraPacchetto) { $pezzi[] = h($a['package_name']); }
                      if ($mostraData && $a['paid_at']) { $pezzi[] = time_ago($a['paid_at']); }
                    ?>
                    <?php if ($pezzi): ?>
                      <div class="store-lato-meta"><?= implode(' · ', $pezzi) ?></div>
                    <?php endif; ?>
                  </div>
                  <?php if ($mostraImporto): ?>
                    <span class="store-acquisto-cifra">
                      <?= h(number_format((float) $a['price'], 2, ',', '.')) ?>
                      <small><?= h($a['currency'] ?: $valuta) ?></small>
                    </span>
                  <?php endif; ?>
                </li>
              <?php endforeach; ?>
            </ul>
          <?php else: ?>
            <p class="store-lato-vuoto">Nessun acquisto ancora.</p>
          <?php endif; ?>
        </section>
      <?php endif; ?>

      <?php if ($sidebarAttiva && is_admin()): ?>
        <a class="store-lato-modifica" href="/manage?section=store#sidebar">✎ Configura questa colonna</a>
      <?php endif; ?>

      <?php /* I riquadri del sito, sotto a quelli dello store e dentro la stessa colonna */ ?>
      <?php if ($sidebarSito) { sidebar_colonna(false); } ?>
    </aside>
  <?php endif; ?>
  </div><?php /* fine layout con sidebar */ ?>
<?php endif; ?>

<?php /* stesso cache-busting di CSS e altri script: senza, dopo un deploy il browser
         continua a usare la copia vecchia — gia' successo. */ ?>
<script src="/assets/js/store.js?v=<?= @filemtime(__DIR__ . '/assets/js/store.js') ?: time() ?>"></script>
<?php require __DIR__ . '/../includes/footer.php'; ?>
