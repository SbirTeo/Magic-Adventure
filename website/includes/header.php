<?php
require_once __DIR__ . '/auth.php';
require_once __DIR__ . '/helpers.php';
require_once __DIR__ . '/sidebar.php';
require_once __DIR__ . '/seo.php';
require_once __DIR__ . '/immagini.php';   // misure del logo nella barra
require_once __DIR__ . '/language.php';
require_once __DIR__ . '/translate.php';

// Da qui in poi TUTTO l'output della pagina (fino al footer) viene bufferizzato: footer.php lo
// traduce in un colpo solo prima di mandarlo al browser (vedi translate_html). Bufferizzare
// prima di qualunque HTML e' anche cio' che permette a header()/setcookie() di funzionare
// sempre, anche dopo che una pagina ha gia' scritto qualcosa: niente viene davvero inviato
// finche' il buffer non si svuota.
ob_start();
$GLOBALS['__siteLang'] = site_language();

$__siteName = site_setting('site_name', 'MAGICADVENTURE');
$__logo = site_setting('logo_url', '/assets/img/logo.png');
// Logo ridotto (quadrato): barra in alto e anteprime dei link. Se non e' stato caricato
// si ripiega su quello grande, cosi' il sito funziona comunque come prima.
$__logoPiccolo = trim(site_setting('logo_small_url', '')) ?: $__logo;
$__favicon = trim(site_setting('favicon_url', '')) ?: $__logoPiccolo;
$__colorBg = site_setting('color_bg', '#0b0c0e');
$__colorPurple = site_setting('color_purple', '#c04ff0');
$__colorGreen = site_setting('color_green', '#a3e635');
$__colorGold = site_setting('vip_banner_color', '#f0c75e');
$__goldOverlayPct = (int) site_setting('vip_banner_overlay_intensity', '85');
$__goldOverlayPct = max(0, min(100, $__goldOverlayPct));
$__goldOverlayAlpha = $__goldOverlayPct / 100;
$__colorFeaturedOverlay = site_setting('featured_overlay_color', '#c04ff0');
$__featuredOverlayPct = max(0, min(100, (int) site_setting('featured_overlay_intensity', '35')));
$__featuredOverlayAlpha = $__featuredOverlayPct / 100;
$__colorGridOverlay = site_setting('grid_overlay_color', '#9a9aa0');
$__gridOverlayPct = max(0, min(100, (int) site_setting('grid_overlay_intensity', '18')));
$__gridOverlayAlpha = $__gridOverlayPct / 100;
if (!is_valid_hex_color($__colorBg)) $__colorBg = '#0b0c0e';
if (!is_valid_hex_color($__colorPurple)) $__colorPurple = '#c04ff0';
if (!is_valid_hex_color($__colorGreen)) $__colorGreen = '#a3e635';
if (!is_valid_hex_color($__colorGold)) $__colorGold = '#f0c75e';
if (!is_valid_hex_color($__colorFeaturedOverlay)) $__colorFeaturedOverlay = '#c04ff0';
if (!is_valid_hex_color($__colorGridOverlay)) $__colorGridOverlay = '#9a9aa0';

// Punto in cui il velo raggiunge la piena intensita': piu' e' basso, piu' la fascia scura
// e' alta e larga (100% = la sfumatura arriva a fondo tessera, com'era prima)
$__featuredOverlayStop = max(20, min(100, (int) site_setting('featured_overlay_stop', '100')));

// Velo delle card dello store (colore, intensita' e altezza, regolabili dal gestionale)
$__storeOverlayColor = site_setting('store_overlay_color', '#0a0804');
if (!is_valid_hex_color($__storeOverlayColor)) $__storeOverlayColor = '#0a0804';
$__storeOverlayAlpha = max(0, min(100, (int) site_setting('store_overlay_intensity', '92'))) / 100;
$__storeOverlayStop = max(20, min(100, (int) site_setting('store_overlay_stop', '55')));

// Colori dei filtri per categoria nello store (il testo si calcola da solo, vedi text_on_color)
$__filtroAttivo = site_setting('store_filter_active_color', '#f0c75e');
if (!is_valid_hex_color($__filtroAttivo)) $__filtroAttivo = '#f0c75e';
$__filtroRiposo = site_setting('store_filter_idle_color', '#17181b');
if (!is_valid_hex_color($__filtroRiposo)) $__filtroRiposo = '#17181b';

// Barretta laterale delle card: articolo in evidenza, altri articoli, card dello store
$__bordoEvidenza = site_setting('featured_border_color', '#c04ff0');
if (!is_valid_hex_color($__bordoEvidenza)) $__bordoEvidenza = '#c04ff0';
$__bordoGriglia = site_setting('grid_border_color', '#c04ff0');
if (!is_valid_hex_color($__bordoGriglia)) $__bordoGriglia = '#c04ff0';
$__bordoStore = site_setting('store_border_color', '#f0c75e');
if (!is_valid_hex_color($__bordoStore)) $__bordoStore = '#f0c75e';

/** 'orizzontale' = velo da sinistra, qualsiasi altra cosa = velo dal basso. */
$__direzioneVelo = fn(string $chiave, string $default): string =>
    site_setting($chiave, $default) === 'orizzontale' ? '270deg' : '180deg';
$__gridOverlayStop = max(20, min(100, (int) site_setting('grid_overlay_stop', '100')));

// ---- VELI E TESTO DELLE TESSERE NEL TEMA CHIARO ------------------------------------
// Il velo scuro sulle copertine e' pensato per il fondo nero: sul tema chiaro puo' servire
// un colore o un'intensita' diversi (e il testo bianco sopra puo' non bastare piu'). Sono
// impostazioni A PARTE, con la loro riga nel gestionale; se non sono mai state toccate
// valgono quelle del tema scuro, quindi chi non entra a cambiarle non vede alcuna
// differenza rispetto a prima.
$__colorOrOwn = function (string $chiave, string $ripiego): string {
    $v = site_setting($chiave, $ripiego);
    return is_valid_hex_color($v) ? $v : $ripiego;
};
$__pctOSua = fn(string $chiave, int $ripiego): int =>
    max(0, min(100, (int) site_setting($chiave, (string) $ripiego)));

$__colorFeaturedOverlayChiaro = $__colorOrOwn('featured_overlay_color_chiaro', $__colorFeaturedOverlay);
$__featuredOverlayAlphaChiaro = $__pctOSua('featured_overlay_intensity_chiaro', $__featuredOverlayPct) / 100;
$__featuredOverlayStopChiaro = max(20, $__pctOSua('featured_overlay_stop_chiaro', $__featuredOverlayStop));
$__colorGridOverlayChiaro = $__colorOrOwn('grid_overlay_color_chiaro', $__colorGridOverlay);
$__gridOverlayAlphaChiaro = $__pctOSua('grid_overlay_intensity_chiaro', $__gridOverlayPct) / 100;
$__gridOverlayStopChiaro = max(20, $__pctOSua('grid_overlay_stop_chiaro', $__gridOverlayStop));

// Testo sopra le copertine (titolo, riassunto, riga dei dati, "Leggi tutto"): un colore
// solo per tema, le tre sfumature piu' tenui si ricavano da quello.
// Testo sopra le copertine degli ARTICOLI. Funziona esattamente come quello delle card
// dello store: vuoto = automatico, cioe' nero o bianco secondo quanto e' chiaro il velo di
// quella tessera; un colore scelto nel gestionale vince su tutto. Le due famiglie di card
// devono comportarsi allo stesso modo, se no sulla stessa pagina una si legge e l'altra no.
$__tileTesto = site_setting('tile_text_color', '');
$__tileTestoChiaro = site_setting('tile_text_color_chiaro', '');
$__tileTesto = is_valid_hex_color($__tileTesto) ? $__tileTesto : '';
$__tileTestoChiaro = is_valid_hex_color($__tileTestoChiaro) ? $__tileTestoChiaro : '';

// I due veli degli articoli hanno colori diversi, quindi anche il testo automatico e' due:
// uno per l'articolo in evidenza e uno per le altre tessere.
$__tileAutoEvidenza = text_on_color($__colorFeaturedOverlay);
$__tileAutoGriglia = text_on_color($__colorGridOverlay);
$__tileAutoEvidenzaChiaro = text_on_color($__colorFeaturedOverlayChiaro);
$__tileAutoGrigliaChiaro = text_on_color($__colorGridOverlayChiaro);

// Barrette laterali delle tessere: anche loro un colore per tema (le variabili del tema
// scuro sono piu' avanti, $__bordoEvidenza / $__bordoGriglia).
$__bordoEvidenzaChiaro = $__colorOrOwn('featured_border_color_chiaro', site_setting('featured_border_color', '#c04ff0'));
$__bordoGrigliaChiaro = $__colorOrOwn('grid_border_color_chiaro', site_setting('grid_border_color', '#c04ff0'));

// Stessa storia per le card dello store: velo e barretta hanno una versione per tema.
$__storeOverlayColorChiaro = $__colorOrOwn('store_overlay_color_chiaro', site_setting('store_overlay_color', '#0a0804'));
$__storeOverlayAlphaChiaro = $__pctOSua('store_overlay_intensity_chiaro', (int) site_setting('store_overlay_intensity', '92')) / 100;
$__storeOverlayStopChiaro = max(20, $__pctOSua('store_overlay_stop_chiaro', (int) site_setting('store_overlay_stop', '55')));
$__bordoStoreChiaro = $__colorOrOwn('store_border_color_chiaro', site_setting('store_border_color', '#f0c75e'));

// Testo sopra le card dello store. Vuoto = automatico: lo decide il velo (nero sui veli
// chiari, bianco su quelli scuri). Se invece l'admin sceglie un colore, quello vince su
// tutto, anche sulle categorie che hanno un velo proprio.
$__storeTesto = site_setting('store_text_color', '');
$__storeTestoChiaro = site_setting('store_text_color_chiaro', '');
$__storeTesto = is_valid_hex_color($__storeTesto) ? $__storeTesto : '';
$__storeTestoChiaro = is_valid_hex_color($__storeTestoChiaro) ? $__storeTestoChiaro : '';

// Il prezzo puo' avere un colore tutto suo (vuoto = come il resto del testo della card).
$__storePrezzo = site_setting('store_price_color', '');
$__storePrezzoChiaro = site_setting('store_price_color_chiaro', '');
$__storePrezzo = is_valid_hex_color($__storePrezzo) ? $__storePrezzo : '';
$__storePrezzoChiaro = is_valid_hex_color($__storePrezzoChiaro) ? $__storePrezzoChiaro : '';

// Targhetta dello sconto (-5%): una sola tinta per tutto il sito — card dello store, pagina
// del pacchetto e banner VIP usano la stessa classe. Il testo sopra (nero o bianco) si
// calcola da solo, quindi un colore solo funziona su entrambi i temi.
$__scontoColore = site_setting('store_sconto_color', '');
$__scontoColore = is_valid_hex_color($__scontoColore) ? $__scontoColore : $__colorGreen;

// Titolo della scheda del browser e riga cliccabile su Google. Di norma e'
// "<pagina> — <sito>"; una pagina puo' pero' dettarlo per intero ($page_title_full), come
// fa la home, dove "Home — MAGICADVENTURE" non direbbe a nessuno di che sito si tratta.
$__title = !empty($page_title_full)
    ? $page_title_full
    : (isset($page_title) ? $page_title . ' — ' . $__siteName : $__siteName);
$__description = $page_description ?? site_setting('meta_description', '');

// ---- MOTORI DI RICERCA E ANTEPRIME SOCIAL (vedi includes/seo.php) -------------------
$__canonical = seo_canonical($page_canonical ?? null);
$__noindex = seo_da_nascondere(!empty($page_noindex));
$__ogType = $page_type ?? 'website';
$__ogImage = seo_url($page_image ?? (trim(site_setting('og_image', '')) ?: $__logoPiccolo));
$__descrizioneSito = site_setting('meta_description', '');
$__u = current_user();
// Stile dei pulsanti principali (.btn-accent, quelli "pieni" di tutto il sito):
//   'contrasto' = bianco su tema scuro e nero su tema chiaro, come l'invio della chat
//   'accento'   = la sfumatura col colore primario del sito (com'era prima)
// Il valore finisce in due variabili, e il foglio di stile non deve sapere altro.
$__stileBottoni = site_setting('btn_stile', 'contrasto') === 'accento' ? 'accento' : 'contrasto';

// Presenza degli ospiti: chi guarda senza accesso non ha una riga in `users`, quindi la
// sua visita si segna a parte (una riga per sessione, vedi ospiti_registra). Serve al
// riquadro "Sul sito ora", che altrimenti direbbe "nessuno" con dieci persone sulla pagina.
ospiti_registra();
align_mc_names();

$__navItems = db()->query('SELECT * FROM nav_items WHERE enabled = 1 ORDER BY sort_order, id')->fetchAll();
$__currentPath = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
?><!DOCTYPE html>
<html lang="<?= h($GLOBALS['__siteLang']) ?>" data-tema="<?= h(tema_scelto()) ?>">
<head>
<meta charset="UTF-8">
<script>
// Tema "auto": qui si traduce nella preferenza vera del sistema, PRIMA che la pagina venga
// disegnata — cosi' non si vede il lampo scuro prima di diventare chiara. Senza JavaScript
// "auto" resta scuro, che e' il tema di casa del sito.
(function () {
  var r = document.documentElement;
  function risolvi() {
    if (r.dataset.temaScelto !== 'auto') return;
    r.dataset.tema = window.matchMedia('(prefers-color-scheme: light)').matches ? 'chiaro' : 'scuro';
  }
  r.dataset.temaScelto = r.dataset.tema;
  risolvi();
  // Se cambia il tema del sistema mentre la pagina e' aperta, la seguo.
  window.matchMedia('(prefers-color-scheme: light)').addEventListener('change', risolvi);
})();
</script>
<?php /* Zoom bloccato su richiesta: niente pizzico a due dita, niente doppio tocco.
         NOTA: Safari su iPhone ignora user-scalable dal 2016 per non togliere l'ingrandimento a
         chi ci vede poco, quindi li' il pizzico resta possibile. Quello che su iPhone si evita
         davvero e' l'ingrandimento AUTOMATICO quando si tocca un campo, ed e' un'altra cosa:
         si ottiene tenendo i campi a 16px (vedi style.css, blocco @media (pointer: coarse)). */ ?>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<title><?= h($__title) ?></title>
<?php if ($__description !== ''): ?>
<meta name="description" content="<?= h($__description) ?>">
<?php endif; ?>
<?php /* Indirizzo ufficiale della pagina: senza, le versioni con www/.php/?utm_ si fanno
         concorrenza fra loro su Google e nessuna guadagna posizioni. */ ?>
<link rel="canonical" href="<?= h($__canonical) ?>">
<?php
// Codice di verifica di Google Search Console: e' Google che lo assegna, e serve solo a
// dimostrargli che il sito e' nostro. Sta nelle impostazioni (Aspetto) e non nel codice,
// cosi' se un domani si rifa' la verifica non serve rimettere mano ai file.
$__verificaGoogle = trim(site_setting('google_site_verification', ''));
?>
<?php if ($__verificaGoogle !== ''): ?>
<meta name="google-site-verification" content="<?= h($__verificaGoogle) ?>">
<?php endif; ?>
<?php if ($__noindex): ?>
<meta name="robots" content="noindex, follow">
<?php else: ?>
<meta name="robots" content="index, follow, max-image-preview:large, max-snippet:-1">
<?php endif; ?>
<meta name="theme-color" content="<?= h($__colorBg) ?>">
<?php /* Anteprima quando il link viene incollato su Discord, WhatsApp, Telegram, X... */ ?>
<meta property="og:type" content="<?= h($__ogType) ?>">
<meta property="og:site_name" content="<?= h($__siteName) ?>">
<meta property="og:locale" content="<?= h(og_locale($GLOBALS['__siteLang'])) ?>">
<meta property="og:title" content="<?= h($__title) ?>">
<?php if ($__description !== ''): ?>
<meta property="og:description" content="<?= h($__description) ?>">
<?php endif; ?>
<meta property="og:url" content="<?= h($__canonical) ?>">
<meta property="og:image" content="<?= h($__ogImage) ?>">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="<?= h($__title) ?>">
<?php if ($__description !== ''): ?>
<meta name="twitter:description" content="<?= h($__description) ?>">
<?php endif; ?>
<meta name="twitter:image" content="<?= h($__ogImage) ?>">
<?php if ($__ogType === 'article'): ?>
<?php if (!empty($page_published)): ?>
<meta property="article:published_time" content="<?= h((string) seo_data($page_published)) ?>">
<?php endif; ?>
<?php if (!empty($page_modified)): ?>
<meta property="article:modified_time" content="<?= h((string) seo_data($page_modified)) ?>">
<?php endif; ?>
<?php if (!empty($page_author)): ?>
<meta property="article:author" content="<?= h($page_author) ?>">
<?php endif; ?>
<?php endif; ?>
<?php
// I due caratteri del sito stanno sul nostro server, non piu' su fonts.googleapis.com:
// niente due connessioni verso un altro dominio prima di poter disegnare il testo, e
// nessun indirizzo IP di visitatore spedito a Google senza motivo. Le due dichiarazioni
// (che sono quattro righe di CSS) vanno DENTRO la pagina: un file a parte costerebbe un
// altro viaggio al server proprio nel momento peggiore, quando c'e' solo da disegnare.
$__caratteri = __DIR__ . '/../public/assets/css/caratteri.css';
?>
<?php /* I due file di partenza si chiedono subito, in parallelo al foglio di stile: sono
         quelli del testo normale e dei titoli, servono comunque entro il primo istante. */ ?>
<link rel="preload" href="/assets/fonts/inter-latin.woff2" as="font" type="font/woff2" crossorigin>
<link rel="preload" href="/assets/fonts/space-grotesk-latin.woff2" as="font" type="font/woff2" crossorigin>
<style><?= @file_get_contents($__caratteri) ?: '' ?></style>
<?php
// La versione e' la data di modifica del file: cambia a ogni deploy, cosi' il browser
// non puo' servire il foglio di stile vecchio dalla cache (problema visto piu' volte).
$__cssVer = @filemtime(__DIR__ . '/../public/assets/css/style.css') ?: time();
?>
<link rel="stylesheet" href="/assets/css/style.css?v=<?= $__cssVer ?>">
<link rel="icon" href="<?= h($__favicon) ?>">
<?php /* Icona per "aggiungi a schermata Home" su iPhone e iPad: usa la stessa immagine. */ ?>
<link rel="apple-touch-icon" href="<?= h($__favicon) ?>">
<style>
:root {
  --purple: <?= h($__colorPurple) ?>;
  --purple-glow: <?= hex_to_rgba($__colorPurple, 0.35) ?>;
  --purple-light: <?= h(hex_shade($__colorPurple, 0.45)) ?>;
  --purple-dark: <?= h(hex_shade($__colorPurple, -0.35)) ?>;
  --green: <?= h($__colorGreen) ?>;
  --green-glow: <?= hex_to_rgba($__colorGreen, 0.35) ?>;
  /* Il verde e' acceso apposta (fondo scuro), ma come TESTO su un pannello bianco non si
     legge: questa e' la stessa tinta scurita finche' il contrasto non basta. La usa il tema
     chiaro dove il verde fa da testo, non da fondo. */
  --verde-leggibile: <?= h(readable_color($__colorGreen, '#ffffff')) ?>;
  --gold: <?= h($__colorGold) ?>;
  --gold-light: <?= h(hex_shade($__colorGold, 0.55)) ?>;
  --gold-dark: <?= h(hex_shade($__colorGold, -0.35)) ?>;
  --gold-glow: <?= hex_to_rgba($__colorGold, 0.5) ?>;
  --gold-border: <?= hex_to_rgba($__colorGold, 0.45) ?>;
  --gold-bg-1: <?= h(hex_shade($__colorGold, -0.85)) ?>;
  --gold-bg-2: <?= h(hex_shade($__colorGold, -0.93)) ?>;
  --gold-overlay-1: <?= hex_to_rgba(hex_shade($__colorGold, -0.85), $__goldOverlayAlpha) ?>;
  --gold-overlay-2: <?= hex_to_rgba(hex_shade($__colorGold, -0.93), min(1, $__goldOverlayAlpha + 0.06)) ?>;
  /* Stessi fondi in versione CHIARA: il banner della promozione e' quasi nero (l'oro
     scurito del 85-93%), e su una pagina chiara sembrava una lastra di catrame. */
  --gold-bg-chiaro-1: <?= h(hex_shade($__colorGold, 0.78)) ?>;
  --gold-bg-chiaro-2: <?= h(hex_shade($__colorGold, 0.92)) ?>;
  --gold-overlay-chiaro-1: <?= hex_to_rgba(hex_shade($__colorGold, 0.78), $__goldOverlayAlpha) ?>;
  --gold-overlay-chiaro-2: <?= hex_to_rgba(hex_shade($__colorGold, 0.92), min(1, $__goldOverlayAlpha + 0.06)) ?>;
  --oro-scuro-leggibile: <?= h(readable_color($__colorGold, hex_shade($__colorGold, 0.85))) ?>;
  --featured-overlay: <?= hex_to_rgba($__colorFeaturedOverlay, $__featuredOverlayAlpha) ?>;
  --grid-overlay: <?= hex_to_rgba($__colorGridOverlay, $__gridOverlayAlpha) ?>;
  --featured-overlay-stop: <?= $__featuredOverlayStop ?>%;
  --grid-overlay-stop: <?= $__gridOverlayStop ?>%;
  --store-overlay: <?= hex_to_rgba($__storeOverlayColor, $__storeOverlayAlpha) ?>;
  --store-overlay-stop: <?= $__storeOverlayStop ?>%;
  /* Gli stessi due valori con un nome che le card possono usare come RIPIEGO quando la
     categoria non ha un velo suo (vedi .store-card nel foglio di stile). */
  --store-velo: <?= hex_to_rgba($__storeOverlayColor, $__storeOverlayAlpha) ?>;
  --store-velo-stop: <?= $__storeOverlayStop ?>%;
  --store-testo: <?= text_on_color($__storeOverlayColor) ?>;
  --sconto-fondo: <?= h($__scontoColore) ?>;
  --sconto-testo: <?= text_on_color($__scontoColore) ?>;
<?php if ($__storeTesto !== ''): ?>
  --store-testo-scelto: <?= h($__storeTesto) ?>;
<?php endif; ?>
<?php if ($__storePrezzo !== ''): ?>
  --store-prezzo-scelto: <?= h($__storePrezzo) ?>;
<?php endif; ?>
  --store-overlay-dir: <?= $__direzioneVelo('store_overlay_direction', 'verticale') ?>;
  --store-featured-overlay-dir: <?= $__direzioneVelo('store_featured_overlay_direction', 'orizzontale') ?>;
  --store-filtro-attivo: <?= h($__filtroAttivo) ?>;
  --store-filtro-attivo-testo: <?= text_on_color($__filtroAttivo) ?>;
  --store-filtro-riposo: <?= h($__filtroRiposo) ?>;
  --store-filtro-riposo-testo: <?= text_on_color($__filtroRiposo) ?>;
  --store-filtro-bordo: <?= hex_to_rgba(text_on_color($__filtroRiposo), 0.18) ?>;
  --bordo-evidenza: <?= h($__bordoEvidenza) ?>;
  --bordo-griglia: <?= h($__bordoGriglia) ?>;
  --store-bordo: <?= h($__bordoStore) ?>;
  --store-bordo-glow: <?= hex_to_rgba($__bordoStore, 0.5) ?>;
  --store-bordo-base: <?= h($__bordoStore) ?>;
  --store-bordo-glow-base: <?= hex_to_rgba($__bordoStore, 0.5) ?>;
  /* Testo sopra le copertine: il colore pieno per i titoli, tre velature per il resto. */
  --tile-auto-evidenza: <?= h($__tileAutoEvidenza) ?>;
  --tile-auto-griglia: <?= h($__tileAutoGriglia) ?>;
<?php if ($__tileTesto !== ''): ?>
  --tile-testo-scelto: <?= h($__tileTesto) ?>;
<?php endif; ?>
  --featured-overlay-dir: <?= $__direzioneVelo('featured_overlay_direction', 'verticale') ?>;
  --grid-overlay-dir: <?= $__direzioneVelo('grid_overlay_direction', 'verticale') ?>;

  /* Testo sopra i colori d'accento: nero o bianco, scelto in base al contrasto reale
     (vedi text_on_color). Cambiando i colori del sito dal gestionale si aggiusta da solo. */
  --testo-su-magenta: <?= text_on_color($__colorPurple) ?>;


  /* Pulsanti principali: fondo e testo cambiano tutti insieme da qui (Aspetto). */
<?php if ($__stileBottoni === 'accento'): ?>
  --btn-fondo: linear-gradient(135deg, var(--purple-light, var(--purple)), var(--purple), var(--purple-dark, var(--purple)));
  --btn-testo: var(--testo-su-magenta, #1a0426);
  --btn-alone: 0 4px 20px -6px var(--purple-glow);
<?php else: ?>
  --btn-fondo: var(--contrasto);
  --btn-testo: var(--contrasto-testo);
  --btn-alone: none;
<?php endif; ?>
  --testo-su-verde: <?= text_on_color($__colorGreen) ?>;
  --testo-su-oro: <?= text_on_color($__colorGold) ?>;
}

<?php
// ---- TAVOLOZZE DEI DUE TEMI --------------------------------------------------------
// Ogni tema si regola dal gestionale (Aspetto) con TRE colori: fondo della pagina, fondo
// dei pannelli, colore del testo. Tutto il resto lo ricava tavolozza_tema(), che spinge
// anche i testi tenui oltre la soglia di contrasto (vedi colore_leggibile).
$__themeColor = function (string $chiave, string $default): string {
    $v = site_setting($chiave, $default);
    return is_valid_hex_color($v) ? $v : $default;
};

$__scuroBg = is_valid_hex_color($__colorBg) ? $__colorBg : '#0b0c0e';
$__scuroPannello = $__themeColor('dark_panel', '#17181b');
$__scuroTesto = $__themeColor('dark_text', '#f0f0ee');

$__chiaroBg = $__themeColor('light_bg', '#f2f3f6');
$__chiaroPannello = $__themeColor('light_panel', '#ffffff');
$__chiaroTesto = $__themeColor('light_text', '#14161a');
?>
/* Tema scuro: vale anche per "auto" finche' il copione non decide (e senza JavaScript) */
:root:not([data-tema="chiaro"]) {
  <?= tavolozza_tema($__scuroBg, $__scuroPannello, $__scuroTesto) ?>

  color-scheme: dark;
}
[data-tema="chiaro"] {
  <?= tavolozza_tema($__chiaroBg, $__chiaroPannello, $__chiaroTesto) ?>

  /* Veli delle copertine e testo sopra: valori tutti loro (vedi Aspetto degli articoli). */
  --featured-overlay: <?= hex_to_rgba($__colorFeaturedOverlayChiaro, $__featuredOverlayAlphaChiaro) ?>;
  --featured-overlay-stop: <?= $__featuredOverlayStopChiaro ?>%;
  --grid-overlay: <?= hex_to_rgba($__colorGridOverlayChiaro, $__gridOverlayAlphaChiaro) ?>;
  --grid-overlay-stop: <?= $__gridOverlayStopChiaro ?>%;
  --tile-auto-evidenza: <?= h($__tileAutoEvidenzaChiaro) ?>;
  --tile-auto-griglia: <?= h($__tileAutoGrigliaChiaro) ?>;
<?php if ($__tileTestoChiaro !== ''): ?>
  --tile-testo-scelto: <?= h($__tileTestoChiaro) ?>;
<?php else: ?>
  /* "initial" spegne davvero la variabile e fa scattare il ripiego di var(): lasciarla
     vuota non basta, e senza questa riga il colore scelto per il tema scuro resterebbe
     anche qui (le variabili si ereditano). */
  --tile-testo-scelto: initial;
<?php endif; ?>
  --bordo-evidenza: <?= h($__bordoEvidenzaChiaro) ?>;
  --bordo-griglia: <?= h($__bordoGrigliaChiaro) ?>;
  --store-overlay: <?= hex_to_rgba($__storeOverlayColorChiaro, $__storeOverlayAlphaChiaro) ?>;
  --store-overlay-stop: <?= $__storeOverlayStopChiaro ?>%;
  --store-velo: <?= hex_to_rgba($__storeOverlayColorChiaro, $__storeOverlayAlphaChiaro) ?>;
  --store-velo-stop: <?= $__storeOverlayStopChiaro ?>%;
  --store-testo: <?= text_on_color($__storeOverlayColorChiaro) ?>;
<?php if ($__storeTestoChiaro !== ''): ?>
  --store-testo-scelto: <?= h($__storeTestoChiaro) ?>;
<?php else: ?>
  /* Niente scelta a mano su questo tema: la variabile va spenta in modo ESPLICITO, se no
     resterebbe quella del tema scuro (le variabili si ereditano). "initial" su una variabile
     la riporta al valore invalido-di-garanzia, cioe' fa scattare il ripiego scritto dentro
     var() — che qui e' il colore automatico. Lasciarla vuota NON basta: una variabile vuota
     e' comunque definita e il ripiego non scatterebbe. */
  --store-testo-scelto: initial;
<?php endif; ?>
<?php if ($__storePrezzoChiaro !== ''): ?>
  --store-prezzo-scelto: <?= h($__storePrezzoChiaro) ?>;
<?php else: ?>
  --store-prezzo-scelto: initial;
<?php endif; ?>
  --store-bordo: <?= h($__bordoStoreChiaro) ?>;
  --store-bordo-glow: <?= hex_to_rgba($__bordoStoreChiaro, 0.5) ?>;
  --store-bordo-base: <?= h($__bordoStoreChiaro) ?>;
  --store-bordo-glow-base: <?= hex_to_rgba($__bordoStoreChiaro, 0.5) ?>;

  color-scheme: light;
}
/* La "nuvola" dietro il logo segue i colori del tema invece di essere fissa */
[data-tema="chiaro"] .hero-cave::after {
  background:
    radial-gradient(circle at 22% 30%, var(--purple-glow) 0%, transparent 34%),
    radial-gradient(circle at 78% 24%, var(--green-glow) 0%, transparent 32%),
    linear-gradient(165deg, <?= h($__chiaroPannello) ?> 0%, <?= h($__chiaroBg) ?> 60%, <?= h(hex_mix($__chiaroTesto, $__chiaroBg, 0.06)) ?> 100%);
}
</style>
<?php
// Dati strutturati: la scheda del sito c'e' sempre, quella della pagina (articolo,
// pacchetto, discussione, briciole di pane) solo se la pagina l'ha preparata.
echo seo_jsonld(seo_site_card($__siteName, $__logo, $__descrizioneSito)), "\n";
if (!empty($page_jsonld)) {
    $__schede = isset($page_jsonld['@type']) || isset($page_jsonld['@graph']) ? [$page_jsonld] : $page_jsonld;
    foreach ($__schede as $__scheda) {
        if (is_array($__scheda)) echo seo_jsonld($__scheda), "\n";
    }
}
?>
</head>
<?php
// La pagina attiva finisce come classe sul body: serve al CSS per cambiare accento
// (nello store il magenta diventa oro). Ripulita, perche' finisce dentro l'HTML.
$__classeBody = preg_replace('/[^a-z0-9_-]/', '', strtolower((string) ($active ?? '')));
// Veli spenti dal gestionale: lo dice una classe sul <body>, cosi' il foglio di stile puo'
// toglierli dovunque siano. Sono due interruttori separati perche' i due posti hanno
// esigenze diverse: nello store il velo tiene leggibile il prezzo, negli articoli spesso
// una copertina pulita rende di piu'. Ognuno si spegne dal modulo del velo della sua
// sezione (Blog, Store).
$__senzaVeloBlog = site_setting('card_overlay_blog', '1') !== '1';
$__senzaVeloStore = site_setting('card_overlay_store', '1') !== '1';
?>
<?php
$__classiBody = [];
if ($__classeBody !== '') $__classiBody[] = 'pagina-' . $__classeBody;
if ($__senzaVeloBlog) $__classiBody[] = 'senza-veli-blog';
if ($__senzaVeloStore) $__classiBody[] = 'senza-veli-store';
?>
<body<?= $__classiBody ? ' class="' . implode(' ', $__classiBody) . '"' : '' ?>>
<div class="top-accent"></div>
<header class="site-header">
  <div class="wrap header-inner">
    <button type="button" class="nav-toggle" id="navToggle" aria-expanded="false" aria-controls="navCollapse" aria-label="Menu">
      <span></span><span></span><span></span>
    </button>
    <div class="nav-collapse" id="navCollapse">
      <nav class="main-nav">
        <?php /* Il logo in piccolo apre la home: e' il segnaposto del sito, sta prima di
                 tutte le voci ed e' cliccabile come un normale collegamento. Si accende e
                 si spegne dal gestionale (Aspetto), perche' su una barra con molte voci
                 puo' diventare un ingombro. */ ?>
        <?php if (site_setting('nav_logo_enabled', '1') === '1'): ?>
          <?php
            // Altezza scelta nel gestionale; la larghezza si ricava dalle proporzioni vere
            // del file, cosi' un logo disteso occupa quello che gli serve e uno quadrato
            // resta quadrato. Dichiararle entrambe evita che la barra sobbalzi mentre
            // l'immagine arriva.
            $__hLogoNav = max(20, min(64, (int) site_setting('nav_logo_size', '44')));
            $__misureLogoNav = immagine_misure($__logoPiccolo);
            $__wLogoNav = $__misureLogoNav && $__misureLogoNav[1] > 0
                ? min(160, (int) round($__hLogoNav * $__misureLogoNav[0] / $__misureLogoNav[1]))
                : $__hLogoNav;
          ?>
          <a href="/" class="nav-logo" aria-label="<?= h($__siteName) ?> — vai alla home"
             style="--nav-logo-h:<?= $__hLogoNav ?>px">
            <img src="<?= h($__logoPiccolo) ?>" alt=""
                 width="<?= $__wLogoNav ?>" height="<?= $__hLogoNav ?>" decoding="async">
          </a>
        <?php endif; ?>
        <?php foreach ($__navItems as $item): ?>
          <?php
          // La voce dello Store non e' piu' fissa nel codice: sta in nav_items come le altre
          // (cosi' si rinomina, si sposta e si disattiva dal gestionale), ma si riconosce
          // dall'URL e resta disegnata come pulsante oro.
          $__classi = [];
          if ($item['url'] === '/store' || $item['url'] === '/store.php') {
              $__classi[] = 'btn btn-gold btn-nav';
              if (site_setting('store_btn_border_anim', '1') === '1') $__classi[] = 'has-border-anim';
          }
          if ($__currentPath === $item['url']) $__classi[] = 'active';
          ?>
          <a href="<?= h($item['url']) ?>" class="<?= h(implode(' ', $__classi)) ?>"><?= h($item['label']) ?></a>
        <?php endforeach; ?>
      </nav>
      <?php
        // Selettore di lingua: un link per lingua verso la stessa pagina con ?lingua=xx, che
        // language.php legge e rende sticky (sessione + cookie). Niente JavaScript necessario:
        // funziona anche con JS disattivato, come il resto della navigazione del sito.
        // La bandiera sta per la lingua (convenzione comune: UK per l'inglese, non gli USA), come
        // SVG e non come emoji: Windows non ha i disegni delle bandiere e mostrerebbe di nuovo
        // due lettere al loro posto (vedi language_flag_svg). title/aria-label portano il nome
        // vero per chi non la riconosce o usa uno screen reader.
        // Ripetuto qui (riga semplice, sempre visibile) SOLO per il menu ad hamburger: sotto
        // l'hamburger .auth-box non ha piu' spazio per il pulsante di sotto (vedi CSS), quindi
        // qui c'e' l'unica copia raggiungibile da telefono.
        $__nomeLingua = ['it' => 'Italiano', 'en' => 'English', 'es' => 'Español', 'de' => 'Deutsch'];
      ?>
      <div class="lingua-mobile">
        <?php foreach (SITE_LANGUAGES as $__lang): ?>
          <a href="<?= h(language_switch_url($__lang)) ?>" title="<?= h($__nomeLingua[$__lang]) ?>"
             aria-label="<?= h($__nomeLingua[$__lang]) ?>"<?= $__lang === $GLOBALS['__siteLang'] ? ' class="active"' : '' ?>>
            <?= language_flag_svg($__lang) ?>
          </a>
        <?php endforeach; ?>
      </div>
    </div>
    <div class="auth-box">
      <?php
        // Interruttore del tema: gira fra scuro, chiaro e automatico. L'etichetta di partenza
        // la scrive il server (niente sfarfallio); da li' in poi la cambia assets/js/site.js,
        // che salva la scelta in un cookie valido un anno.
        $__temaOra = tema_scelto();
        $__iconaTema = ['scuro' => '☾', 'chiaro' => '☀', 'auto' => '◐'];
        $__nomeTema = ['scuro' => 'Tema scuro', 'chiaro' => 'Tema chiaro', 'auto' => 'Tema automatico'];
      ?>
      <button type="button" class="btn btn-ghost cambia-tema" id="cambiaTema"
              title="<?= h($__nomeTema[$__temaOra]) ?> — clicca per cambiare"
              aria-label="<?= h($__nomeTema[$__temaOra]) ?>">
        <span class="cambia-tema-icona"><?= $__iconaTema[$__temaOra] ?></span>
        <?php /* La parola sparisce su schermo stretto: resta la sola icona. */ ?>
        <span class="cambia-tema-testo"><?= h(str_replace('Tema ', '', $__nomeTema[$__temaOra])) ?></span>
      </button>
      <details class="cambia-lingua">
        <summary class="btn btn-ghost" title="Cambia lingua" aria-label="Cambia lingua">
          <?= language_flag_svg($GLOBALS['__siteLang']) ?>
        </summary>
        <div class="cambia-lingua-menu">
          <?php foreach (SITE_LANGUAGES as $__lang): ?>
            <a href="<?= h(language_switch_url($__lang)) ?>"<?= $__lang === $GLOBALS['__siteLang'] ? ' class="active"' : '' ?>>
              <?= language_flag_svg($__lang) ?>
              <?= h($__nomeLingua[$__lang]) ?>
            </a>
          <?php endforeach; ?>
        </div>
      </details>
      <?php if ($__u): ?>
        <?php
        // Qui i tag dei gradi NON si mostrano (la barra deve restare pulita): del grado resta
        // solo il colore del piu' importante, addosso al nome. L'avatar porta al profilo.
        $__coloreNome = player_name_color($__u);
        ?>
        <a href="/profilo" class="btn btn-ghost who-link<?= $__currentPath === '/profilo' ? ' active' : '' ?>" title="Il tuo profilo">
          <?php /* Corona anche qui, se e' il miglior sostenitore: senza cuoricini, nella
                   barra sarebbero rumore in mezzo ai pulsanti. */ ?>
          <?= avatar_top('<img class="who-avatar" src="' . h(mc_avatar_url($__u['mc_uuid'], 64, $__u['premium_uuid'] ?? null)) . '" alt="" width="28" height="28">', $__u['mc_uuid'], 28) ?>
          <span class="who colore-grado"<?= $__coloreNome !== null ? ' style="' . rank_color_style($__coloreNome) . '"' : '' ?>><?= h($__u['mc_username']) ?></span>
        </a>
        <?php if (can_manage()): ?>
          <a href="/manage" class="btn btn-ghost">Gestione</a>
        <?php endif; ?>
        <a href="/logout" class="btn btn-ghost">Esci</a>
      <?php else: ?>
        <a href="/login" class="btn btn-accent">Accedi</a>
      <?php endif; ?>
    </div>
  </div>
</header>
<main class="wrap main-content">
<?php
// Colonna di destra automatica: la pagina scrive il suo contenuto e basta, l'impalcatura
// (griglia + colonna) la mette qui l'intestazione e la chiude il pie' di pagina. Chi ce
// l'ha o no si decide dal gestionale, voce per voce (Pagine e navigazione).
// Home e store no: quelle due se la posizionano da sole, dentro la pagina.
$__conSidebar = !in_array($active ?? '', ['home', 'store'], true)
    && pagina_con_sidebar((string) $__currentPath);
if ($__conSidebar):
?>
<div class="content-with-sidebar">
  <?php /* Posto dove va la chat quando la si ingrandisce: vedi chat.js */ ?>
  <div class="chat-riga" id="chatRiga"></div>
  <div class="content-main">
<?php endif; ?>
