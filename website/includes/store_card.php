<?php
/**
 * La card di un pacchetto dello store, in un posto solo.
 *
 * La usano la vetrina dello store (`/store`) e la promozione in home, che mostra la card
 * del pacchetto in evidenza: prima il markup viveva dentro store.php e la home aveva un
 * banner tutto suo, con due aspetti da tenere allineati a mano.
 */

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/helpers.php';
require_once __DIR__ . '/auth.php';   // is_admin(): matita di modifica sulla card

/**
 * Stili per categoria (velo della copertina, barretta laterale, colori del filtro) come
 * dichiarazioni CSS pronte da mettere sulla card. Calcolati una volta per richiesta.
 *
 * @return array{nomi: array<int,string>, velo: array<int,string>, bordo: array<int,string>, filtro: array<int,string>}
 */
function store_category_styles(): array {
    static $stili = null;
    if ($stili !== null) {
        return $stili;
    }

    $stili = ['nomi' => [0 => 'Altro'], 'velo' => [], 'bordo' => [], 'filtro' => [], 'scelte' => []];

    foreach (db()->query('SELECT * FROM store_categories')->fetchAll() as $c) {
        $id = (int) $c['id'];
        $stili['nomi'][$id] = $c['name'];

        // Barretta laterale della card + alone dell'hover, nella stessa tinta.
        //
        // NB: qui NON si scrive direttamente --store-bordo. Questi valori finiscono inline
        // sulla card, e uno stile inline vince su qualsiasi regola del foglio: scrivendo
        // subito la variabile finale, il tema chiaro non avrebbe piu' modo di cambiarla.
        // Si passano quindi le due tinte grezze (--cat-bordo / --cat-bordo-chiaro) e a
        // scegliere e' il foglio di stile, come si fa gia' per i colori dei gradi.
        if (!empty($c['border_color']) && is_valid_hex_color($c['border_color'])) {
            $bordoChiaro = !empty($c['border_color_light']) && is_valid_hex_color($c['border_color_light'])
                ? $c['border_color_light']
                : $c['border_color'];
            $stili['bordo'][$id] = '--cat-bordo:' . $c['border_color']
                . ';--cat-bordo-glow:' . hex_to_rgba($c['border_color'], 0.5)
                . ';--cat-bordo-chiaro:' . $bordoChiaro
                . ';--cat-bordo-glow-chiaro:' . hex_to_rgba($bordoChiaro, 0.5);
        }

        // Scelte fatte a mano sulla categoria: testo sopra il velo, prezzo e targhetta dello
        // sconto. Colonna vuota = eredita (scelta generale dello store, e in mancanza il
        // colore automatico calcolato dal velo). Anche qui si passano tinte con un nome
        // proprio: lo stile inline vince sul foglio, quindi non si puo' scrivere subito la
        // variabile finale o il tema chiaro non potrebbe piu' cambiarla.
        $scelte = [];
        foreach ([
            'text_color' => '--cat-testo-scelto',
            'text_color_light' => '--cat-testo-scelto-chiaro',
            'price_color' => '--cat-prezzo',
            'price_color_light' => '--cat-prezzo-chiaro',
        ] as $colonna => $variabile) {
            if (!empty($c[$colonna]) && is_valid_hex_color($c[$colonna])) {
                $scelte[] = $variabile . ':' . $c[$colonna];
            }
        }
        // La targhetta dello sconto porta con se' anche il colore del testo, calcolato per
        // contrasto come si fa per quella generale.
        if (!empty($c['discount_color']) && is_valid_hex_color($c['discount_color'])) {
            $scelte[] = '--sconto-fondo:' . $c['discount_color'];
            $scelte[] = '--sconto-testo:' . text_on_color($c['discount_color']);
        }
        if ($scelte) {
            $stili['scelte'][$id] = implode(';', $scelte);
        }

        // Colori del filtro della categoria: li riusa la targhetta sulla card.
        if (!empty($c['filter_active_color']) && is_valid_hex_color($c['filter_active_color'])) {
            $stili['filtro'][$id] = '--store-filtro-attivo:' . $c['filter_active_color']
                . ';--store-filtro-attivo-testo:' . text_on_color($c['filter_active_color']);
        }

        // Velo sulla copertina: se la categoria ne ha uno proprio, sovrascrive quello generale.
        // Due versioni, tema scuro e tema chiaro (le colonne *_chiaro vuote valgono "come il
        // tema scuro"); la direzione invece e' una sola, non dipende dal tema.
        if (!empty($c['overlay_color']) && is_valid_hex_color($c['overlay_color'])) {
            $alpha = max(0, min(100, (int) ($c['overlay_intensity'] ?? 92))) / 100;
            $stop = max(20, min(100, (int) ($c['overlay_stop'] ?? 55)));
            $dir = ($c['overlay_direction'] ?? '') === 'orizzontale' ? '270deg' : '180deg';

            $coloreChiaro = !empty($c['overlay_color_light']) && is_valid_hex_color($c['overlay_color_light'])
                ? $c['overlay_color_light']
                : $c['overlay_color'];
            $alphaChiaro = $c['overlay_intensity_light'] !== null && $c['overlay_intensity_light'] !== ''
                ? max(0, min(100, (int) $c['overlay_intensity_light'])) / 100
                : $alpha;
            $stopChiaro = $c['overlay_stop_light'] !== null && $c['overlay_stop_light'] !== ''
                ? max(20, min(100, (int) $c['overlay_stop_light']))
                : $stop;

            $stili['velo'][$id] = '--cat-velo:' . hex_to_rgba($c['overlay_color'], $alpha)
                . ';--cat-velo-stop:' . $stop . '%'
                . ';--cat-velo-chiaro:' . hex_to_rgba($coloreChiaro, $alphaChiaro)
                . ';--cat-velo-stop-chiaro:' . $stopChiaro . '%'
                // Testo che finisce SOPRA il velo (titolo, voci, prezzo): nero o bianco
                // secondo quanto e' chiaro il velo stesso, come si fa gia' per i pulsanti
                // dei filtri. Un velo giallo con sopra il bianco non si legge.
                . ';--cat-testo:' . text_on_color($c['overlay_color'])
                . ';--cat-testo-chiaro:' . text_on_color($coloreChiaro)
                . ';--store-overlay-dir:' . $dir
                . ';--store-featured-overlay-dir:' . $dir;
        }
    }

    return $stili;
}

/**
 * Sconti. Si possono impostare su tre livelli e vince SEMPRE il piu' specifico:
 * pacchetto -> categoria -> tutto lo store. Il tipo e' 'percentuale' (0-100) oppure
 * 'importo' (nella valuta dello store); valore 0 significa "nessuno sconto".
 *
 * @return array{pieno: float, finale: float, tipo: ?string, valore: float, etichetta: ?string}
 */
function store_prezzo(array $item): array {
    $pieno = round((float) ($item['price'] ?? 0), 2);
    $sconto = null;

    if (!empty($item['discount_type']) && (float) $item['discount_value'] > 0) {
        $sconto = ['tipo' => $item['discount_type'], 'valore' => (float) $item['discount_value']];
    } else {
        $sconto = store_category_discount((int) ($item['category_id'] ?? 0)) ?? store_sconto_globale();
    }

    if (!$sconto) {
        return ['pieno' => $pieno, 'finale' => $pieno, 'tipo' => null, 'valore' => 0, 'etichetta' => null];
    }

    $finale = $sconto['tipo'] === 'percentuale'
        ? $pieno * (1 - $sconto['valore'] / 100)
        : $pieno - $sconto['valore'];
    $finale = max(0, round($finale, 2));

    // Uno sconto che non cambia il prezzo (es. 0,00 su un pacchetto gratis) non va mostrato.
    if (abs($finale - $pieno) < 0.005) {
        return ['pieno' => $pieno, 'finale' => $pieno, 'tipo' => null, 'valore' => 0, 'etichetta' => null];
    }

    $etichetta = $sconto['tipo'] === 'percentuale'
        ? '-' . rtrim(rtrim(number_format($sconto['valore'], 2, ',', '.'), '0'), ',') . '%'
        : '-' . number_format($sconto['valore'], 2, ',', '.') . ' ' . site_setting('store_currency', 'EUR');

    return [
        'pieno' => $pieno,
        'finale' => $finale,
        'tipo' => $sconto['tipo'],
        'valore' => $sconto['valore'],
        'etichetta' => $etichetta,
    ];
}

/** Sconto della categoria, se ne ha uno. */
function store_category_discount(int $catId): ?array {
    static $sconti = null;
    if ($sconti === null) {
        $sconti = [];
        foreach (db()->query('SELECT id, discount_type, discount_value FROM store_categories')->fetchAll() as $c) {
            if (!empty($c['discount_type']) && (float) $c['discount_value'] > 0) {
                $sconti[(int) $c['id']] = ['tipo' => $c['discount_type'], 'valore' => (float) $c['discount_value']];
            }
        }
    }
    return $sconti[$catId] ?? null;
}

/** Sconto valido su tutto lo store, se impostato. */
function store_sconto_globale(): ?array {
    $valore = (float) site_setting('store_discount_value', '0');
    if ($valore <= 0) {
        return null;
    }
    $tipo = site_setting('store_discount_type', 'percentuale') === 'importo' ? 'importo' : 'percentuale';
    return ['tipo' => $tipo, 'valore' => $valore];
}

/**
 * Price ready to print: struck-through full price + discounted price when a discount
 * applies, otherwise the plain price. The discount label itself is NOT printed here: on
 * the card it is shown by the angled corner ribbon (see store_card()).
 */
function store_prezzo_html(array $item): string {
    $p = store_prezzo($item);
    $valuta = h(site_setting('store_currency', 'EUR'));
    $cifra = fn(float $v): string => h(number_format($v, 2, ',', '.'));

    if (!$p['tipo']) {
        return '<span class="store-prezzo">' . $cifra($p['finale']) . ' <small>' . $valuta . '</small></span>';
    }
    return '<span class="store-prezzo is-scontato">'
        . '<s class="store-prezzo-pieno">' . $cifra($p['pieno']) . '</s> '
        . $cifra($p['finale']) . ' <small>' . $valuta . '</small>'
        . '</span>';
}

/** Print one package card. */
function store_card(array $item): void {
    $stili = store_category_styles();
    $catId = (int) $item['category_id'];
    $prezzo = store_prezzo($item);
    // Featured package: the one flagged in the manager (store_packages.featured = 1).
    $featured = !empty($item['featured']);

    $stile = $stili['velo'][$catId] ?? '';
    foreach (['bordo', 'filtro', 'scelte'] as $parte) {
        if (!empty($stili[$parte][$catId])) {
            $stile .= ';' . $stili[$parte][$catId];
        }
    }
    if (!empty($item['image_url'])) {
        $stile .= ";--copertina:url('" . h($item['image_url']) . "')";
    }

    $classi = 'store-card'
        . (empty($item['image_url']) ? ' senza-immagine' : '')
        . ($featured ? ' is-featured' : '');
    // The cell wraps the card AND the price below it: it is the flex item of the row, so
    // filtering hides card + price as one unit and the price stays out of the card.
    ?>
    <div class="store-card-cella<?= $featured ? ' is-featured' : '' ?>" data-cat="<?= $catId ?>">
      <article class="<?= $classi ?>"
               id="<?= h($item['slug']) ?>"
               data-cat="<?= $catId ?>"
               <?= $stile !== '' ? ' style="' . ltrim($stile, ';') . '"' : '' ?>>
        <div class="store-card-media" aria-hidden="true"></div>
        <?php if ($featured): /* Gold border comes from the CSS; here goes the badge. */ ?>
          <span class="store-card-consigliato">Consigliato</span>
        <?php endif; ?>
        <?php if ($prezzo['etichetta']): /* Discount as an angled corner ribbon, top-right. */ ?>
          <span class="store-card-sconto"><?= h($prezzo['etichetta']) ?></span>
        <?php endif; ?>
        <?php if (is_admin()): /* web-admin only: the store is managed from there */ ?>
          <a href="/manage?section=store_pkg_edit&id=<?= (int) $item['id'] ?>"
             class="card-edit-btn store-card-modifica" title="Modifica questo pacchetto"
             aria-label="Modifica <?= h($item['name']) ?>">✎</a>
        <?php endif; ?>
        <?php /* The whole cover links to the package page: here you look, you don't buy. */ ?>
        <a class="store-card-link" href="/pacchetto/<?= h(rawurlencode($item['slug'])) ?>">Vedi <?= h($item['name']) ?></a>
      </article>
      <?php /* Name and price sit BELOW the card, on the page background: the cover stays clean
               (no category label, no title over the art) and the name/price read at a glance. */ ?>
      <div class="store-card-info">
        <a class="store-card-nome" href="/pacchetto/<?= h(rawurlencode($item['slug'])) ?>"><?= h($item['name']) ?></a>
        <div class="store-card-prezzo-est"><?= store_prezzo_html($item) ?></div>
      </div>
    </div>
    <?php
}
