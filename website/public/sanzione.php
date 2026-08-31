<?php
/**
 * Il singolo provvedimento: /sanzione/<id>
 *
 * Tre livelli di lettura, di proposito diversi:
 *   - chiunque      -> chi, cosa, quando, durata, stato, esito pubblico del ricorso
 *   - l'interessato -> in piu' il rapporto con le prove, e il pulsante per fare ricorso
 *   - lo staff      -> come l'interessato, piu' la parte riservata del ricorso
 *
 * Il ricorso e' sempre raggiungibile, anche da chi e' bandito dal sito: una sanzione a cui
 * non si puo' rispondere non e' una sanzione, e' un muro.
 */
require_once __DIR__ . '/../includes/db.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/permissions.php';
require_once __DIR__ . '/../includes/sanzioni.php';

$id = (int) ($_GET['id'] ?? 0);
if ($id <= 0 || !sanzioni_pronte()) {
    http_response_code(404);
    $page_title = 'Provvedimento non trovato';
    require __DIR__ . '/../includes/header.php';
    echo '<h1 class="page-title">Provvedimento non trovato</h1>'
       . '<div class="panel"><p style="margin:0;">Questo provvedimento non esiste. '
       . '<a href="/sanzioni">Torna all\'elenco</a>.</p></div>';
    require __DIR__ . '/../includes/footer.php';
    return;
}

$stmt = db()->prepare('SELECT * FROM punishments WHERE id = ?');
$stmt->execute([$id]);
$s = $stmt->fetch();

if (!$s) {
    http_response_code(404);
    $page_title = 'Provvedimento non trovato';
    require __DIR__ . '/../includes/header.php';
    echo '<h1 class="page-title">Provvedimento non trovato</h1>'
       . '<div class="panel"><p style="margin:0;">Questo provvedimento non esiste o è stato cancellato. '
       . '<a href="/sanzioni">Torna all\'elenco</a>.</p></div>';
    require __DIR__ . '/../includes/footer.php';
    return;
}

$me = current_user();
$sonoIo = $me !== null && strcasecmp((string) $me['mc_uuid'], (string) $s['mc_uuid']) === 0;
$sonoStaff = can('sanzioni.view');
$vedoLeProve = $sonoIo || $sonoStaff;

$ricorso = sanzione_ricorso($id);
$errore = null;

// ---------------------------------------------------------------------
// Apertura del ricorso. Lo puo' fare solo l'interessato, una volta sola.
// ---------------------------------------------------------------------
if ($_SERVER['REQUEST_METHOD'] === 'POST' && ($_POST['action'] ?? '') === 'ricorso_apri') {
    csrf_check();
    if (!$sonoIo) {
        http_response_code(403);
        die('Solo il giocatore sanzionato può fare ricorso su questo provvedimento.');
    }
    $testo = trim((string) ($_POST['testo'] ?? ''));
    if (mb_strlen($testo) < 20) {
        $errore = 'Scrivi almeno una ventina di caratteri: spiega cosa è successo dal tuo punto di vista.';
    } elseif ($ricorso) {
        $errore = 'Hai già un ricorso aperto su questo provvedimento.';
    } else {
        $ins = db()->prepare(
            'INSERT INTO punishment_appeals (punishment_id, user_id, text) VALUES (?, ?, ?)'
        );
        $ins->execute([$id, (int) $me['id'], mb_substr($testo, 0, 5000)]);
        redirect('/sanzione/' . $id . '?ricorso=inviato');
    }
}

$statoVero = sanzione_stato($s);
$page_title = sanzione_tipo($s['type']) . ' — ' . $s['mc_username'];
$page_description = 'Provvedimento su ' . $s['mc_username'] . ': ' . sanzione_categoria((string) $s['category']) . '.';
$active = 'sanzioni';

require __DIR__ . '/../includes/header.php';
?>
<p class="briciole"><a href="/sanzioni">Sanzioni</a><span>›</span>Provvedimento n. <?= (int) $s['id'] ?></p>

<div class="panel sanzione-scheda" style="--accento:<?= h(sanzione_colore($s['type'])) ?>">
  <div class="sanzione-scheda-testa">
    <?= avatar_top(
          '<img src="' . h(mc_avatar_url($s['mc_uuid'], 64)) . '" alt="" width="48" height="48" class="forum-faccia">',
          $s['mc_uuid'], 48) ?>
    <div>
      <h1 class="page-title" style="margin:0 0 4px;">
        <?= h(sanzione_tipo($s['type'])) ?>: <?= h($s['mc_username']) ?>
      </h1>
      <p class="sanzione-sottotitolo">
        <?= h(sanzione_categoria((string) $s['category'])) ?> ·
        <?= h(sanzione_durata($s)) ?> ·
        <span class="sanzione-pallino sanzione-<?= h($statoVero) ?>">
          <?= $statoVero === 'attiva' ? 'In corso' : ($statoVero === 'revocata' ? 'Revocata' : 'Terminata') ?>
        </span>
      </p>
    </div>
  </div>

  <dl class="sanzione-dati">
    <div><dt>Motivo</dt><dd><?= h($s['reason']) ?></dd></div>
    <div><dt>Dove vale</dt><dd><?= h(SANZIONI_AMBITI[$s['scope']] ?? $s['scope']) ?></dd></div>
    <div><dt>Inizio</dt><dd><?= h(date('d/m/Y H:i', strtotime((string) $s['starts_at']))) ?></dd></div>
    <div>
      <dt>Fine</dt>
      <dd><?= $s['ends_at'] ? h(date('d/m/Y H:i', strtotime((string) $s['ends_at']))) : 'nessuna: è permanente' ?></dd>
    </div>
    <div><dt>Deciso da</dt><dd><?= h(sanzione_autore($s)) ?></dd></div>
    <?php if ((int) $s['points'] > 0): ?>
      <div><dt>Punti</dt><dd><?= (int) $s['points'] ?> <span class="sanzione-nota">(dimezzano ogni 90 giorni)</span></dd></div>
    <?php endif; ?>
    <?php if ($statoVero === 'revocata'): ?>
      <div>
        <dt>Revoca</dt>
        <dd>
          <?= h($s['revoke_reason'] ?: 'nessuna motivazione indicata') ?>
          <?php if ($s['revoked_by']): ?><span class="sanzione-nota">— <?= h($s['revoked_by']) ?></span><?php endif; ?>
        </dd>
      </div>
    <?php endif; ?>
  </dl>

  <?php if ($s['report_hash']): ?>
    <p class="sanzione-impronta" title="Impronta SHA-256 del rapporto firmato">
      Rapporto firmato · <code><?= h(substr((string) $s['report_hash'], 0, 16)) ?>…</code>
      <span class="sanzione-nota">l'impronta prova che il documento non è stato modificato dopo la decisione</span>
    </p>
  <?php endif; ?>
</div>

<?php if ($vedoLeProve && $s['report_public']): ?>
  <div class="panel">
    <h2 class="forum-sezione-titolo">Il rapporto</h2>
    <div class="sanzione-rapporto"><?= corpo_articolo((string) $s['report_public']) ?></div>
  </div>
<?php elseif ($vedoLeProve): ?>
  <div class="panel">
    <h2 class="forum-sezione-titolo">Il rapporto</h2>
    <p style="margin:0; color:var(--text-dim);">
      Per questo provvedimento non è stato allegato un rapporto: è una decisione presa a mano dallo staff.
    </p>
  </div>
<?php endif; ?>

<?php /* ---------------- Ricorso ---------------- */ ?>
<div class="panel">
  <h2 class="forum-sezione-titolo">Ricorso</h2>

  <?php if (isset($_GET['ricorso']) && $_GET['ricorso'] === 'inviato'): ?>
    <div class="alert alert-success">Ricorso inviato. Lo staff ti risponderà qui.</div>
  <?php endif; ?>
  <?php if ($errore): ?>
    <div class="alert alert-error"><?= h($errore) ?></div>
  <?php endif; ?>

  <?php if ($ricorso): ?>
    <p class="sanzione-ricorso-stato ricorso-<?= h($ricorso['status']) ?>">
      <?= h(ricorso_etichetta($ricorso)) ?>
      · aperto il <?= h(date('d/m/Y', strtotime((string) $ricorso['opened_at']))) ?>
      <?php if ($ricorso['decided_at']): ?>
        · deciso il <?= h(date('d/m/Y', strtotime((string) $ricorso['decided_at']))) ?>
      <?php endif; ?>
    </p>

    <?php if ($ricorso['outcome_public']): ?>
      <p class="sanzione-esito"><?= h($ricorso['outcome_public']) ?></p>
    <?php endif; ?>

    <?php if ($vedoLeProve): ?>
      <div class="sanzione-ricorso-testo">
        <h3>Quello che ha scritto <?= h($s['mc_username']) ?></h3>
        <p><?= nl2br(h($ricorso['text'])) ?></p>
        <?php if ($ricorso['reply']): ?>
          <h3>Risposta dello staff<?= $ricorso['staff_name'] ? ' (' . h($ricorso['staff_name']) . ')' : '' ?></h3>
          <p><?= nl2br(h($ricorso['reply'])) ?></p>
        <?php endif; ?>
      </div>
    <?php else: ?>
      <p style="margin:0; color:var(--text-dim); font-size:14px;">
        Il contenuto del ricorso è privato: lo vedono l'interessato e lo staff.
      </p>
    <?php endif; ?>

  <?php elseif ($sonoIo && $statoVero !== 'revocata'): ?>
    <p style="color:var(--text-dim); font-size:14px; margin-top:0;">
      Se ritieni che questo provvedimento sia sbagliato, scrivi qui la tua versione.
      Il ricorso resta privato fra te e lo staff; nell'elenco pubblico comparirà solo l'esito.
    </p>
    <?php /* Il modulo occupa tutta la scheda: qui si scrive la propria versione dei fatti,
             e un campo stretto invita a rispondere con una riga. */ ?>
    <form method="post" class="stack ricorso-modulo">
      <?= csrf_field() ?>
      <input type="hidden" name="action" value="ricorso_apri">
      <textarea name="testo" rows="9" maxlength="5000" required
                placeholder="Racconta cosa è successo dal tuo punto di vista…"></textarea>
      <button type="submit" class="btn btn-accent">Invia il ricorso</button>
    </form>

  <?php elseif ($sonoIo): ?>
    <p style="margin:0; color:var(--text-dim);">
      Questo provvedimento è già stato revocato: non c'è nulla contro cui fare ricorso.
    </p>
  <?php elseif (!is_logged_in()): ?>
    <p style="margin:0; color:var(--text-dim);">
      Nessun ricorso su questo provvedimento. Se sei tu il giocatore sanzionato,
      <a href="/login">accedi</a> per aprirne uno.
    </p>
  <?php else: ?>
    <p style="margin:0; color:var(--text-dim);">Nessun ricorso su questo provvedimento.</p>
  <?php endif; ?>
</div>

<?php require __DIR__ . '/../includes/footer.php'; ?>
