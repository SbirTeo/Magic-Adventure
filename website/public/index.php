<?php
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/store_card.php';
require_once __DIR__ . '/../includes/vip_banner.php';
require_once __DIR__ . '/../includes/obiettivo.php';
require_once __DIR__ . '/../includes/countdown.php';
require_once __DIR__ . '/../includes/immagini.php';   // misure vere del logo, vedi piu' sotto

/**
 * Le tessere in home usano la copertina come sfondo RITAGLIATO per riempire un riquadro
 * largo e basso. Va bene per una fotografia sdraiata; una locandina in piedi, invece,
 * verrebbe ingrandita due volte e mezza per coprire la larghezza, e se ne vedrebbe una
 * fetta sfocata in mezzo. Quando l'immagine e' piu' alta che larga si passa quindi a
 * "intera, appoggiata a destra" (vedi .copertina-intera nel foglio di stile).
 */
function copertina_in_piedi(?string $url): bool {
    $m = $url ? immagine_misure($url) : null;
    return $m !== null && $m[1] > 0 && $m[0] / $m[1] < 1.2;
}
$__logoHome = site_setting('logo_url', '/assets/img/logo.png');
$page_title = 'Home';
// Su Google la home si presenta con un titolo suo, scritto dal gestionale (Aspetto):
// "Home — MAGICADVENTURE" non contiene nessuna delle parole che la gente cerca.
$page_title_full = trim(site_setting('meta_title_home', ''))
    ?: 'Magic Adventure — Server Minecraft italiano Hardcore Fazioni';
$active = 'home';

$perPage = max(1, (int) site_setting('blog_per_page', '6'));
$page = max(1, (int) ($_GET['page'] ?? 1));

// "Vai all'articolo numero...": il numero conta dal piu' recente (1 = l'ultimo pubblicato) e
// porta DIRETTAMENTE alla pagina dell'articolo, senza passare per la pagina giusta dell'elenco.
// Se il numero non esiste si resta in home e il modulo lo dice.
$artChiesto = max(0, (int) ($_GET['art'] ?? 0));
$artMancante = false;
if ($artChiesto > 0) {
    $q = db()->prepare('SELECT slug FROM blog_posts WHERE published = 1 AND deleted_at IS NULL ORDER BY created_at DESC LIMIT 1 OFFSET :salta');
    $q->bindValue(':salta', $artChiesto - 1, PDO::PARAM_INT);
    $q->execute();
    $slugChiesto = $q->fetchColumn();
    if ($slugChiesto) {
        redirect('/blog/' . urlencode((string) $slugChiesto));
    }
    $artMancante = true;   // numero fuori dall'elenco: nessun salto, resta la home
}
$totalPosts = (int) db()->query('SELECT COUNT(*) FROM blog_posts WHERE published = 1 AND deleted_at IS NULL')->fetchColumn();
$totalPages = max(1, (int) ceil($totalPosts / $perPage));
$page = min($page, $totalPages);
$offset = ($page - 1) * $perPage;

$stmt = db()->prepare('SELECT b.*, u.mc_username, u.is_admin, u.last_seen, ' . RANK_SELECT_SQL
    . ' FROM blog_posts b LEFT JOIN users u ON u.id = b.author_user_id' . rank_join_sql()
    . ' WHERE b.published = 1 AND b.deleted_at IS NULL ORDER BY b.created_at DESC LIMIT :limit OFFSET :offset');
$stmt->bindValue(':limit', $perPage, PDO::PARAM_INT);
$stmt->bindValue(':offset', $offset, PDO::PARAM_INT);
$stmt->execute();
$posts = $stmt->fetchAll();

$featured = ($page === 1 && $posts) ? array_shift($posts) : null;
$status = mc_server_status();

// Testi della hero, configurabili da Aspetto. Ogni riga va a capo; nel titolo l'ULTIMA
// riga prende il colore primario (classe .accent-word), com'era nella versione fissa.
$heroSlogan = trim(site_setting('hero_slogan', ''));
$heroHeadline = trim(site_setting('hero_headline', ''));
$heroSub = trim(site_setting('hero_sub', ''));

$righeHtml = function (string $testo, bool $accentoUltimaRiga = false): string {
    $righe = array_map('trim', explode("
", $testo));
    $righe = array_values(array_filter($righe, fn($r) => $r !== ''));
    if (!$righe) {
        return '';
    }
    if ($accentoUltimaRiga) {
        $ultima = array_pop($righe);
        $righe[] = '<span class="accent-word">' . h($ultima) . '</span>';
        $righe = array_map(fn($r) => str_starts_with($r, '<span') ? $r : h($r), $righe);
        return implode('<br>', $righe);
    }
    return implode('<br>', array_map('h', $righe));
};

// La colonna di destra (chat, scheda giocatore, "sul sito ora") se la calcola da sola:
// vedi sidebar_colonna() in includes/sidebar.php.

require __DIR__ . '/../includes/header.php';
?>

<div class="cave-zone">
<div class="hero-cave" aria-hidden="true"></div>
<section class="hero-v2">
  <div class="hero-grid">
    <div class="hero-logo-col">
      <?php if (is_admin()): /* fuori dal collegamento al logo: un <a> dentro un altro <a> non e' valido */ ?>
        <a href="/manage?section=theme#logo_url" class="card-edit-btn hero-logo-edit"
           title="Cambia logo e slogan" aria-label="Cambia logo e slogan">&#9998;</a>
      <?php endif; ?>
      <a href="/" class="hero-logo-pulse">
        <span class="logo-particles" aria-hidden="true">
          <i class="particle" style="--x:8%; --y:15%; --delay:0s; --dur:7s; --pcolor:var(--purple);"></i>
          <i class="particle" style="--x:85%; --y:10%; --delay:1.2s; --dur:8.5s; --pcolor:var(--green);"></i>
          <i class="particle" style="--x:15%; --y:70%; --delay:2.4s; --dur:6.5s; --pcolor:#f0c75e;"></i>
          <i class="particle" style="--x:90%; --y:60%; --delay:0.8s; --dur:9s; --pcolor:var(--purple);"></i>
          <i class="particle" style="--x:50%; --y:5%; --delay:3s; --dur:7.5s; --pcolor:var(--green);"></i>
          <i class="particle" style="--x:5%; --y:45%; --delay:1.8s; --dur:8s; --pcolor:#f0c75e;"></i>
          <i class="particle" style="--x:95%; --y:35%; --delay:2.9s; --dur:6.8s; --pcolor:var(--purple);"></i>
          <i class="particle" style="--x:30%; --y:85%; --delay:0.4s; --dur:7.8s; --pcolor:var(--green);"></i>
          <i class="particle" style="--x:70%; --y:88%; --delay:2.1s; --dur:9.4s; --pcolor:#f0c75e;"></i>
          <i class="particle" style="--x:40%; --y:12%; --delay:3.6s; --dur:6.2s; --pcolor:var(--purple);"></i>
          <i class="particle" style="--x:60%; --y:78%; --delay:1.5s; --dur:8.8s; --pcolor:var(--green);"></i>
          <i class="particle" style="--x:20%; --y:30%; --delay:4s; --dur:7.2s; --pcolor:#f0c75e;"></i>
        </span>
        <?php /* E' l'immagine piu' grande della pagina, quella che decide quando la home
                 "sembra" caricata: si chiede al browser di darle la precedenza e si
                 dichiarano le misure, cosi' il posto e' gia' riservato e il testo sotto
                 non salta quando arriva. */ ?>
        <?php $__misureLogo = immagine_misure($__logoHome); ?>
        <img src="<?= h($__logoHome) ?>"
             alt="<?= h(site_setting('site_name', 'MAGICADVENTURE')) ?>" class="hero-logo-v2"
             <?= $__misureLogo ? 'width="' . $__misureLogo[0] . '" height="' . $__misureLogo[1] . '" ' : '' ?>
             fetchpriority="high" decoding="async">
        <?php if ($heroSlogan !== ''): ?>
          <p class="hero-slogan"><?= $righeHtml($heroSlogan) ?></p>
        <?php endif; ?>
      </a>
    </div>
    <div class="hero-copy-col">
      <div class="status-pill <?= $status ? 'is-online' : 'is-unknown' ?>">
        <span class="dot"></span>
        <?php if ($status): ?>
          <?= (int) $status['players_online'] ?> / <?= (int) $status['players_max'] ?> giocatori online
        <?php else: ?>
          Stato server non disponibile
        <?php endif; ?>
      </div>
      <?php if ($heroHeadline !== ''): ?>
        <h1 class="hero-headline"><?= $righeHtml($heroHeadline, true) ?></h1>
      <?php endif; ?>
      <?php if ($heroSub !== ''): ?>
        <p class="hero-sub"><?= $righeHtml($heroSub) ?></p>
      <?php endif; ?>
      <div class="hero-actions">
        <button type="button" class="ip-copy" data-ip="mc.magicadventure.it">
          <span class="ip-copy-label">IP</span>
          <span class="ip-copy-value">mc.magicadventure.it</span>
        </button>
      </div>
    </div>
  </div>
</section>

<div class="section-divider" aria-hidden="true"></div>
</div>

<?php /* Conto alla rovescia per l'apertura: sta fra il logo e gli articoli, a tutta
         larghezza, perche' e' la cosa che deve vedere per prima chi arriva. Markup e
         testi in includes/countdown.php, tutto regolabile da Aspetto. */ ?>
<?php countdown_sezione(); ?>

<div class="content-with-sidebar">
  <?php /* Posto dove la chat va a stare quando la si ingrandisce: e' una riga a tutta
           larghezza SOPRA articoli e colonna di destra, quindi aprendola il resto della
           pagina scende. Finche' e' vuota non occupa niente (vedi .chat-riga:empty). */ ?>
  <div class="chat-riga" id="chatRiga"></div>

  <div class="content-main">
    <?php /* Stesso banner in cima allo store: il markup sta in includes/vip_banner.php,
             cosi' si regola da un punto solo e le due pagine restano allineate.
             Sta DENTRO la colonna degli articoli, non sopra a tutto: cosi' e' largo
             quanto loro e la colonna di destra puo' partire dalla sua stessa altezza. */ ?>
    <?php vip_banner(); ?>

    <?php /* L'obiettivo in home si accende a parte: c'e' chi lo vuole solo nello store. */ ?>
    <?php if (site_setting('goal_home', '0') === '1') { obiettivo_sezione(); } ?>

    <?php /* Scorciatoia per chi scrive sul blog: sta qui, sopra agli articoli, perche' e'
             da qui che si guarda la home per vedere cosa manca. Chi non ha il permesso non
             la vede nemmeno. */ ?>
    <?php if (can('blog.create')): ?>
      <div class="barra-crea">
        <a href="/blog/new" class="btn btn-green btn-small">
          <span class="barra-crea-piu" aria-hidden="true">+</span> Crea nuovo articolo
        </a>
      </div>
    <?php endif; ?>

    <?php if (!$posts && !$featured): ?>
      <div class="panel"><p>Nessun articolo pubblicato ancora. Torna presto!</p></div>
    <?php else: ?>

      <?php /* Ultimo articolo e "altri" stanno nella STESSA griglia, due per riga e tutti
               della stessa misura: l'ultimo si distingue solo per i colori (pillola
               magenta e cornice animata). */ ?>
      <?php /* L'ancora della paginazione sta sulla griglia, non sulla colonna: cosi'
               "pagina 2" porta agli articoli e non al banner qui sopra. */ ?>
      <?php /* Numero d'articolo: 1 e' il piu' recente in assoluto, non il primo di questa
               pagina. E' il numero che si scrive nel modulo qui sotto su telefono, quindi
               deve valere su tutto il blog. */ ?>
      <?php $n = $offset; ?>
      <div class="post-grid" id="articoli">
        <?php if ($featured): ?>
          <div class="card-wrap card-doppia<?= site_setting('featured_border_anim', '1') === '1' ? ' has-border-anim' : '' ?>" id="articolo-<?= ++$n ?>">
            <a href="/blog/<?= urlencode($featured['slug']) ?>"
               class="bento-tile bento-feature <?= empty($featured['cover_image']) ? 'no-image' : '' ?><?= copertina_in_piedi($featured['cover_image'] ?? null) ? ' copertina-intera' : '' ?>"
               <?= !empty($featured['cover_image']) && !copertina_in_piedi($featured['cover_image']) ? 'style="background-image:url(\'' . h($featured['cover_image']) . '\'); --fuoco-telefono:' . h($featured['cover_position'] ?? '50% 50%') . '; --fuoco-pc:' . h($featured['cover_position_pc'] ?? '50% 50%') . '"' : '' ?>>
              <?php /* Copertina in piedi: occupa una colonna sua, da bordo a bordo, e sfuma
                       nel fondo neutro del testo con il gradiente di .tile-poster::after. */ ?>
              <?php if (copertina_in_piedi($featured['cover_image'] ?? null)): ?>
                    <span class="tile-poster" aria-hidden="true" style="--fuoco-telefono:<?= h($featured['cover_position'] ?? '50% 50%') ?>; --fuoco-pc:<?= h($featured['cover_position_pc'] ?? '50% 50%') ?>">
                      <?php /* Il taglio lo decide object-position, che prende il punto scelto
                               nel gestionale dalle variabili --fuoco-* qui sopra. */ ?>
                      <img src="<?= h($featured['cover_image']) ?>" alt="" loading="lazy" decoding="async">
                    </span>
              <?php endif; ?>
              <span class="bento-tag">Ultimo articolo</span>
              <h2><?= h($featured['title']) ?></h2>
              <p class="bento-excerpt"><?= h(mb_strimwidth(strip_tags($featured['body']), 0, 110, '…')) ?></p>
              <div class="meta"><span class="card-numero">#<?= $n ?></span><?= time_ago($featured['created_at']) ?><?= $featured['mc_username'] ? ' · di ' . player_name($featured, $featured['mc_username']) : '' ?></div>
              <span class="card-cta">Leggi tutto →</span>
            </a>
            <?php if (can('blog.edit')): ?>
              <a href="/manage?section=blog_edit&id=<?= $featured['id'] ?>" class="card-edit-btn" title="Modifica" aria-label="Modifica">✎</a>
            <?php endif; ?>
          </div>
        <?php endif; ?>

        <?php if ($posts): ?>
          <?php foreach ($posts as $post): ?>
            <div class="card-wrap" id="articolo-<?= ++$n ?>">
              <a href="/blog/<?= urlencode($post['slug']) ?>"
                 class="post-tile <?= empty($post['cover_image']) ? 'no-image' : '' ?><?= copertina_in_piedi($post['cover_image'] ?? null) ? ' copertina-intera' : '' ?>"
                 <?= !empty($post['cover_image']) && !copertina_in_piedi($post['cover_image']) ? 'style="background-image:url(\'' . h($post['cover_image']) . '\'); --fuoco-telefono:' . h($post['cover_position'] ?? '50% 50%') . '; --fuoco-pc:' . h($post['cover_position_pc'] ?? '50% 50%') . '"' : '' ?>>
                 <?php /* Copertina in piedi: occupa una colonna sua, da bordo a bordo, e sfuma
                          nel fondo neutro del testo con il gradiente di .tile-poster::after. */ ?>
                 <?php if (copertina_in_piedi($post['cover_image'] ?? null)): ?>
                       <span class="tile-poster" aria-hidden="true" style="--fuoco-telefono:<?= h($post['cover_position'] ?? '50% 50%') ?>; --fuoco-pc:<?= h($post['cover_position_pc'] ?? '50% 50%') ?>">
                         <?php /* Il taglio lo decide object-position, che prende il punto scelto
                                  nel gestionale dalle variabili --fuoco-* qui sopra. */ ?>
                         <img src="<?= h($post['cover_image']) ?>" alt="" loading="lazy" decoding="async">
                       </span>
                 <?php endif; ?>
                <h3><?= h($post['title']) ?></h3>
                <p class="post-tile-excerpt"><?= h(mb_strimwidth(strip_tags($post['body']), 0, 110, '…')) ?></p>
                <div class="meta"><span class="card-numero">#<?= $n ?></span><?= time_ago($post['created_at']) ?><?= $post['mc_username'] ? ' · di ' . player_name($post, $post['mc_username']) : '' ?></div>
                <span class="card-cta">Leggi tutto →</span>
              </a>
              <?php if (can('blog.edit')): ?>
                <a href="/manage?section=blog_edit&id=<?= $post['id'] ?>" class="card-edit-btn card-edit-btn-small" title="Modifica" aria-label="Modifica">✎</a>
              <?php endif; ?>
            </div>
          <?php endforeach; ?>
        <?php endif; ?>
      </div>

      <?php /* Scorciatoia per andare a un articolo preciso: il numero conta dal piu' recente
               (#1 = l'ultimo) ed e' scritto sulla riga dei dati di ogni tessera. Vale su
               tutte le larghezze: su telefono prende il posto delle pagine numerate, che li'
               sono nascoste perche' gli articoli si sfogliano col dito. */ ?>
      <?php if ($totalPosts > 1): ?>
        <form class="vai-articolo" method="get" action="/">
          <label for="vaiArticolo">Vai all&rsquo;articolo numero</label>
          <div class="vai-articolo-riga">
            <input type="number" id="vaiArticolo" name="art" inputmode="numeric"
                   min="1" max="<?= (int) $totalPosts ?>" placeholder="1"
                   value="<?= $artChiesto > 0 ? (int) $artChiesto : '' ?>">
            <span class="vai-articolo-tot">di <?= (int) $totalPosts ?> &middot; 1 &egrave; il pi&ugrave; recente</span>
            <button type="submit" class="btn btn-small vai-articolo-btn">Vai</button>
          </div>
          <?php if ($artMancante): ?>
            <p class="vai-articolo-errore">Non c&rsquo;&egrave; nessun articolo numero <?= (int) $artChiesto ?>.</p>
          <?php endif; ?>
        </form>
      <?php endif; ?>

      <?php if ($totalPages > 1): ?>
        <div class="pagination">
          <?php if ($page > 1): ?>
            <a href="/?page=<?= $page - 1 ?>#articoli">‹</a>
          <?php else: ?>
            <span class="disabled">‹</span>
          <?php endif; ?>
          <?php for ($p = 1; $p <= $totalPages; $p++): ?>
            <?php if ($p === $page): ?>
              <span class="active"><?= $p ?></span>
            <?php else: ?>
              <a href="/?page=<?= $p ?>#articoli"><?= $p ?></a>
            <?php endif; ?>
          <?php endfor; ?>
          <?php if ($page < $totalPages): ?>
            <a href="/?page=<?= $page + 1 ?>#articoli">›</a>
          <?php else: ?>
            <span class="disabled">›</span>
          <?php endif; ?>
        </div>
      <?php endif; ?>

    <?php endif; ?>

  </div>

  <?php /* La colonna di destra sta in includes/sidebar.php: la usano anche le altre
           pagine, quando la spunta e' accesa in "Pagine e navigazione". */ ?>
  <?php sidebar_colonna(); ?>
</div>

<?php require __DIR__ . '/../includes/footer.php'; ?>
