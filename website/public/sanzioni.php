<?php
/**
 * Elenco pubblico delle sanzioni: /sanzioni
 *
 * Trasparenza piena, per scelta: nick, tipo, categoria, motivo, durata, chi ha deciso,
 * quando, e come e' finito l'eventuale ricorso. Le prove NON stanno qui: quelle le legge
 * l'interessato (e lo staff) nella pagina del singolo provvedimento.
 *
 * L'elenco e' un elenco: righe sobrie con un filo colorato a sinistra per il tipo, come
 * le categorie del forum. Niente tessere, niente riquadri decorativi.
 *
 * Filtri e pagine passano dall'indirizzo (?tipo=&stato=&categoria=&q=&p=): un elenco
 * sanzioni si linka e si cita, quindi ogni vista deve avere un indirizzo suo.
 */
require_once __DIR__ . '/../includes/db.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/sanzioni.php';

$page_title = 'Sanzioni';
$page_description = 'Tutti i provvedimenti presi su MAGICADVENTURE: chi, cosa, quando e con quale esito.';
$active = 'sanzioni';

/** Quante righe per pagina. */
const SANZIONI_PER_PAGINA = 25;

$tipo      = (string) ($_GET['tipo'] ?? '');
$stato     = (string) ($_GET['stato'] ?? '');
$categoria = (string) ($_GET['categoria'] ?? '');
$cerca     = trim((string) ($_GET['q'] ?? ''));
$pagina    = max(1, (int) ($_GET['p'] ?? 1));

if (!isset(SANZIONI_TIPI[$tipo])) {
    $tipo = '';
}
if (!in_array($stato, ['attive', 'scadute', 'revocate'], true)) {
    $stato = '';
}

// ---------------------------------------------------------------------
// La query. Lo stato "attiva/scaduta" NON e' quello scritto in tabella: si calcola
// sulla data, cosi' l'elenco e' vero anche se il server di gioco e' spento.
// ---------------------------------------------------------------------
$dove = [];
$par  = [];

if ($tipo !== '') {
    $dove[] = 's.tipo = ?';
    $par[] = $tipo;
}
if ($categoria !== '') {
    $dove[] = 's.categoria = ?';
    $par[] = $categoria;
}
if ($cerca !== '') {
    $dove[] = 's.mc_username LIKE ?';
    $par[] = '%' . str_replace(['%', '_'], ['\%', '\_'], $cerca) . '%';
}
if ($stato === 'attive') {
    $dove[] = "s.stato = 'attiva' AND (s.fine IS NULL OR s.fine > NOW())";
} elseif ($stato === 'scadute') {
    $dove[] = "s.stato = 'attiva' AND s.fine IS NOT NULL AND s.fine <= NOW()";
} elseif ($stato === 'revocate') {
    $dove[] = "s.stato = 'revocata'";
}

$sqlDove = $dove ? ('WHERE ' . implode(' AND ', $dove)) : '';

$totale = 0;
$righe = [];
$categorieUsate = [];
$conteggi = ['tutte' => 0, 'attive' => 0, 'ban' => 0, 'mute' => 0];

if (sanctions_ready()) {
    $conta = db()->prepare("SELECT COUNT(*) FROM punishments s $sqlDove");
    $conta->execute($par);
    $totale = (int) $conta->fetchColumn();

    $offset = ($pagina - 1) * SANZIONI_PER_PAGINA;
    // L'offset non puo' essere un parametro legato: MariaDB non accetta i placeholder
    // in LIMIT/OFFSET con le prepared statement vere (EMULATE_PREPARES e' spento).
    $q = db()->prepare(
        "SELECT s.*, u.id AS user_id, r.status AS ricorso_stato, r.outcome_public
           FROM punishments s
           LEFT JOIN users u ON u.mc_uuid = s.mc_uuid
           LEFT JOIN punishment_appeals r ON r.punishment_id = s.id
           $sqlDove
          ORDER BY s.created_at DESC, s.id DESC
          LIMIT " . SANZIONI_PER_PAGINA . " OFFSET " . (int) $offset
    );
    $q->execute($par);
    $righe = $q->fetchAll();

    // Le categorie da proporre nel filtro sono solo quelle davvero usate: un menu con
    // dieci voci di cui otto vuote non aiuta nessuno.
    $categorieUsate = db()->query('SELECT DISTINCT category FROM punishments ORDER BY category')->fetchAll(PDO::FETCH_COLUMN);

    $conteggi['tutte']  = (int) db()->query('SELECT COUNT(*) FROM punishments')->fetchColumn();
    $conteggi['attive'] = (int) db()->query("SELECT COUNT(*) FROM punishments WHERE status = 'attiva' AND (ends_at IS NULL OR ends_at > NOW())")->fetchColumn();
    $conteggi['ban']    = (int) db()->query("SELECT COUNT(*) FROM punishments WHERE type = 'ban'")->fetchColumn();
    $conteggi['mute']   = (int) db()->query("SELECT COUNT(*) FROM punishments WHERE type = 'mute'")->fetchColumn();
}

$pagineTotali = max(1, (int) ceil($totale / SANZIONI_PER_PAGINA));

/** Indirizzo di questa stessa pagina con un parametro cambiato (e la pagina azzerata). */
$linkCon = function (array $cambi) use ($tipo, $stato, $categoria, $cerca, $pagina): string {
    $par = array_merge([
        'type' => $tipo, 'status' => $stato, 'category' => $categoria, 'q' => $cerca, 'p' => $pagina,
    ], $cambi);
    if (!isset($cambi['p'])) {
        $par['p'] = 1;
    }
    $par = array_filter($par, fn($v) => $v !== '' && $v !== null && $v !== 1);
    return '/sanzioni' . ($par ? ('?' . http_build_query($par)) : '');
};

require __DIR__ . '/../includes/header.php';
?>
<div class="forum-testata">
  <div>
    <h1 class="page-title">Sanzioni</h1>
    <p class="forum-sottotitolo">
      Ogni provvedimento preso sul server, con il motivo e l'esito del ricorso.
      È pubblico di proposito: le regole valgono se si vede che vengono applicate.
      <a href="/tutorial#regolamento">Leggi il regolamento →</a>
    </p>
  </div>
  <?php if (sanctions_ready() && $conteggi['tutte'] > 0): ?>
    <div class="forum-numeri">
      <span><strong><?= $conteggi['tutte'] ?></strong> in tutto</span>
      <span><strong><?= $conteggi['attive'] ?></strong> in corso</span>
      <span><strong><?= $conteggi['ban'] ?></strong> ban</span>
      <span><strong><?= $conteggi['mute'] ?></strong> mute</span>
    </div>
  <?php endif; ?>
</div>

<form class="sanzioni-filtri panel" method="get" action="/sanzioni">
  <div class="sanzioni-filtri-riga">
    <div class="utenti-cerca">
      <span class="utenti-cerca-lente" aria-hidden="true">
        <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
          <circle cx="8.5" cy="8.5" r="5.5"></circle>
          <path d="M12.8 12.8 17 17"></path>
        </svg>
      </span>
      <input type="search" name="q" value="<?= h($cerca) ?>" placeholder="Cerca un giocatore…"
             autocomplete="off" aria-label="Cerca un giocatore">
    </div>

    <label class="sanzioni-campo">
      <span>Tipo</span>
      <select name="tipo">
        <option value="">Tutti</option>
        <?php foreach (SANZIONI_TIPI as $codice => $t): ?>
          <option value="<?= h($codice) ?>" <?= $tipo === $codice ? 'selected' : '' ?>><?= h($t['etichetta']) ?></option>
        <?php endforeach; ?>
      </select>
    </label>

    <label class="sanzioni-campo">
      <span>Stato</span>
      <select name="stato">
        <option value="">Tutti</option>
        <option value="attive"   <?= $stato === 'attive' ? 'selected' : '' ?>>In corso</option>
        <option value="scadute"  <?= $stato === 'scadute' ? 'selected' : '' ?>>Terminate</option>
        <option value="revocate" <?= $stato === 'revocate' ? 'selected' : '' ?>>Revocate</option>
      </select>
    </label>

    <?php if ($categorieUsate): ?>
      <label class="sanzioni-campo">
        <span>Motivo</span>
        <select name="categoria">
          <option value="">Tutti</option>
          <?php foreach ($categorieUsate as $c): ?>
            <option value="<?= h($c) ?>" <?= $categoria === $c ? 'selected' : '' ?>><?= h(sanction_category($c)) ?></option>
          <?php endforeach; ?>
        </select>
      </label>
    <?php endif; ?>

    <button type="submit" class="btn btn-contrasto">Filtra</button>
    <?php if ($tipo || $stato || $categoria || $cerca !== ''): ?>
      <a href="/sanzioni" class="sanzioni-azzera">Azzera</a>
    <?php endif; ?>
  </div>
</form>

<?php if (!sanctions_ready()): ?>
  <div class="panel">
    <p style="margin:0; color:var(--text-dim);">
      L'archivio delle sanzioni non è ancora attivo su questo sito.
    </p>
  </div>
<?php elseif (!$righe): ?>
  <div class="panel">
    <p style="margin:0; color:var(--text-dim);">
      <?php if ($totale === 0 && $conteggi['tutte'] === 0): ?>
        Nessuna sanzione è mai stata registrata. Buon segno.
      <?php else: ?>
        Nessuna sanzione con questi filtri.
      <?php endif; ?>
    </p>
  </div>
<?php else: ?>

  <div class="sanzioni-elenco">
    <?php foreach ($righe as $i => $s): ?>
      <?php
        $statoVero = sanction_status($s);
        $ricorso = $s['ricorso_stato'] ? ['status' => $s['ricorso_stato']] : null;
      ?>
      <article class="sanzione-riga<?= $statoVero !== 'attiva' ? ' e-conclusa' : '' ?>"
               style="--accento:<?= h(sanction_color($s['type'])) ?>; --i:<?= (int) $i ?>">
        <a class="forum-riga-link" href="/sanzione/<?= (int) $s['id'] ?>">Apri il provvedimento</a>

        <div class="sanzione-chi">
          <?= avatar_top(
                '<img class="forum-faccia" src="' . h(mc_avatar_url($s['mc_uuid'], 40)) . '" alt="" width="34" height="34" loading="lazy">',
                $s['mc_uuid'], 34) ?>
          <div class="sanzione-chi-testo">
            <span class="sanzione-nome"><?= h($s['mc_username']) ?></span>
            <span class="sanzione-quando"><?= h(time_ago((string) $s['created_at'])) ?></span>
          </div>
        </div>

        <div class="sanzione-cosa">
          <div class="sanzione-titolo">
            <span class="sanzione-tipo"><?= h(sanction_type($s['type'])) ?></span>
            <span class="sanzione-categoria"><?= h(sanction_category((string) $s['category'])) ?></span>
          </div>
          <p class="sanzione-motivo"><?= h($s['reason']) ?></p>
          <div class="sanzione-meta">
            <span><?= h(sanction_duration($s)) ?></span>
            <?php if ($sc = sanction_expiry($s)): ?><span><?= h($sc) ?></span><?php endif; ?>
            <span>da <?= h(sanction_author($s)) ?></span>
            <?php if (($s['scope'] ?? 'entrambi') !== 'entrambi'): ?>
              <span><?= h(SANZIONI_AMBITI[$s['scope']] ?? $s['scope']) ?></span>
            <?php endif; ?>
          </div>
        </div>

        <div class="sanzione-stato">
          <span class="sanzione-pallino sanzione-<?= h($statoVero) ?>">
            <?= $statoVero === 'attiva' ? 'In corso' : ($statoVero === 'revocata' ? 'Revocata' : 'Terminata') ?>
          </span>
          <?php if ($ricorso): ?>
            <span class="sanzione-ricorso ricorso-<?= h($s['ricorso_stato']) ?>"><?= h(ricorso_etichetta($ricorso)) ?></span>
          <?php endif; ?>
        </div>
      </article>
    <?php endforeach; ?>
  </div>

  <?php if ($pagineTotali > 1): ?>
    <nav class="sanzioni-pagine" aria-label="Pagine">
      <?php if ($pagina > 1): ?>
        <a href="<?= h($linkCon(['p' => $pagina - 1])) ?>">← Precedenti</a>
      <?php endif; ?>
      <span>Pagina <?= $pagina ?> di <?= $pagineTotali ?></span>
      <?php if ($pagina < $pagineTotali): ?>
        <a href="<?= h($linkCon(['p' => $pagina + 1])) ?>">Successive →</a>
      <?php endif; ?>
    </nav>
  <?php endif; ?>

<?php endif; ?>

<?php require __DIR__ . '/../includes/footer.php'; ?>
