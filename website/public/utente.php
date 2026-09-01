<?php
/**
 * Scheda pubblica di un giocatore: /utente?nome=DorinoJ
 *
 * E' la stessa pagina del profilo personale (/profilo) vista da fuori: skin, gradi, dati
 * di gioco e attivita' sul sito. Qui pero' non si tocca niente — nessun comando, nessun
 * dato privato (indirizzo, sessioni, cifre spese): quello resta al proprietario.
 */
require_once __DIR__ . '/../includes/db.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/auth.php';

$nome = trim((string) ($_GET['nome'] ?? ''));

$utente = null;
if ($nome !== '') {
    $q = db()->prepare(
        'SELECT u.id, u.mc_uuid, u.mc_username, u.is_admin, u.created_at, u.last_seen, u.last_login, '
        . RANK_SELECT_SQL . ', r.weight, r.first_join'
        . ' FROM users u' . rank_join_sql()
        . ' WHERE u.mc_username = ? LIMIT 1'
    );
    $q->execute([$nome]);
    $utente = $q->fetch() ?: null;
}

if (!$utente) {
    http_response_code(404);
    $page_title = 'Giocatore non trovato';
    $active = 'utenti';
    require __DIR__ . '/../includes/header.php';
    ?>
    <h1 class="page-title">Giocatore non trovato</h1>
    <div class="panel">
      <p>Nessun account con questo nome. Puo' essere scritto in modo diverso, oppure il giocatore
         non ha ancora collegato il suo account al sito.</p>
      <p style="margin-bottom:0;"><a class="btn btn-accent btn-small" href="/utenti">Torna all&rsquo;elenco</a></p>
    </div>
    <?php
    require __DIR__ . '/../includes/footer.php';
    return;
}

$page_title = $utente['mc_username'];
$page_description = 'Scheda di ' . $utente['mc_username'] . ' su MAGICADVENTURE: gradi, fazione e attivita\'.';
$active = 'utenti';

// Dati di gioco: stessa query del profilo personale. Se il database di MagixFactions non
// risponde la scheda si apre lo stesso, con i trattini al posto dei numeri.
$stats = null;
try {
    $q = db()->prepare("
        SELECT p.power, p.max_power, f.name AS faction_name, f.tag AS faction_tag,
               fm.`rank` AS faction_rank,
               (SELECT COUNT(*) FROM factions_magixfactions.claims c WHERE c.faction_id = f.id) AS territories
        FROM users u
        LEFT JOIN factions_magixfactions.players p ON p.uuid = u.mc_uuid COLLATE utf8mb4_unicode_ci
        LEFT JOIN factions_magixfactions.faction_members fm ON fm.uuid = u.mc_uuid COLLATE utf8mb4_unicode_ci
        LEFT JOIN factions_magixfactions.factions f ON f.id = fm.faction_id
        WHERE u.id = ?
    ");
    $q->execute([$utente['id']]);
    $stats = $q->fetch() ?: null;
} catch (PDOException $e) {
    $stats = null;
}

const GRADI_FAZIONE_PUBBLICI = [
    'recruit' => 'Recluta',
    'member'  => 'Membro',
    'officer' => 'Ufficiale',
    'leader'  => 'Leader',
];

$conta = function (string $sql, int $id): int {
    try {
        $q = db()->prepare($sql);
        $q->execute([$id]);
        return (int) $q->fetchColumn();
    } catch (PDOException $e) {
        return 0;
    }
};
$nTopics = $conta('SELECT COUNT(*) FROM forum_topics WHERE user_id = ?', (int) $utente['id']);
$nRisposte = $conta('SELECT COUNT(*) FROM forum_posts WHERE user_id = ?', (int) $utente['id']);
$nMiPiace = $conta('SELECT COUNT(*) FROM forum_likes l JOIN forum_posts p ON p.id = l.post_id WHERE p.user_id = ?', (int) $utente['id']);
// Quanti acquisti, non quanto ha speso: la cifra e' un fatto suo.
$nAcquisti = $conta("SELECT COUNT(*) FROM store_orders WHERE user_id = ? AND status = 'paid'", (int) $utente['id']);

$coloreNome = player_name_color($utente);
$tag = player_tag($utente);
$dataIt = fn(?string $d) => $d ? date('d/m/Y', strtotime($d)) : '—';
$io = current_user();
$sonoIo = $io && (int) $io['id'] === (int) $utente['id'];

require __DIR__ . '/../includes/header.php';
?>
<nav class="profilo-barra" aria-label="Comandi della scheda">
  <a href="/utenti" class="btn btn-ghost btn-small">← Tutti gli utenti</a>
  <?php if ($sonoIo): ?>
    <a href="/profilo" class="btn btn-accent btn-small">Questo sei tu: apri il tuo profilo</a>
  <?php endif; ?>
</nav>

<h1 class="page-title"><?= h($utente['mc_username']) ?></h1>

<div class="profilo-testata panel">
  <?php /* Stessa figura del profilo personale, girabile a 360 gradi col trascinamento: ci
           pensa assets/js/profilo-skin.js, che pero' ha bisogno di DUE cose che qui prima
           mancavano — la tela su cui disegnare e l'indirizzo della skin in data-skin.
           L'immagine ferma resta come ripiego se il 3D non parte (niente WebGL, script
           bloccato, ecc.). */ ?>
  <div class="profilo-avatar" id="avatar3d"
       data-skin="<?= h('https://minotar.net/skin/' . rawurlencode(str_replace('-', '', (string) $utente['mc_uuid']))) ?>">
    <canvas hidden></canvas>
    <img class="profilo-skin" src="<?= h(mc_body_url($utente['mc_uuid'], 160)) ?>" alt="Skin di <?= h($utente['mc_username']) ?>"
         width="90" height="200" loading="lazy">
    <span class="profilo-avatar-nota">Trascina per girarlo</span>
  </div>

  <div class="profilo-testata-testo">
    <div class="profilo-intestazione">
      <div class="profilo-nome colore-grado"<?= $coloreNome !== null ? ' style="' . rank_color_style($coloreNome) . '"' : '' ?>>
        <?= h($utente['mc_username']) ?>
      </div>
      <?php if ($tag !== ''): ?>
        <div class="profilo-gradi"><?= $tag ?></div>
      <?php endif; ?>
    </div>

    <?php $ultimaVolta = $utente['last_seen'] ?: ($utente['last_login'] ?: null); ?>
    <?php if (is_on_site($utente)): ?>
      <p class="profilo-nota"><span class="online-dot" aria-hidden="true"></span> Sul sito in questo momento.</p>
    <?php elseif ($ultimaVolta): ?>
      <p class="profilo-nota">Ultima volta sul sito <?= h(time_ago($ultimaVolta)) ?>.</p>
    <?php else: ?>
      <p class="profilo-nota">Non si &egrave; ancora visto sul sito.</p>
    <?php endif; ?>

    <dl class="profilo-account">
      <div>
        <dt>Sul server dal</dt>
        <dd><?= !empty($utente['first_join']) ? h($dataIt($utente['first_join'])) : 'mai entrato in gioco' ?></dd>
      </div>
      <div>
        <dt>Account del sito dal</dt>
        <dd><?= h($dataIt($utente['created_at'])) ?></dd>
      </div>
    </dl>
  </div>
</div>

<div class="profilo-griglia">
  <div class="profilo-dato">
    <span>Fazione</span>
    <strong><?= $stats && $stats['faction_name'] ? h($stats['faction_name']) : '—' ?></strong>
  </div>
  <div class="profilo-dato">
    <span>Grado nella fazione</span>
    <strong><?php
      $r = $stats['faction_rank'] ?? null;
      echo $stats && $stats['faction_name'] && $r
          ? h(GRADI_FAZIONE_PUBBLICI[strtolower((string) $r)] ?? ucfirst((string) $r))
          : '—';
    ?></strong>
  </div>
  <div class="profilo-dato">
    <span>Potenza</span>
    <strong><?= $stats && $stats['power'] !== null ? (int) $stats['power'] . ' / ' . (int) $stats['max_power'] : '—' ?></strong>
  </div>
  <div class="profilo-dato">
    <span>Territori della fazione</span>
    <strong><?= $stats && $stats['territories'] !== null ? (int) $stats['territories'] : '0' ?></strong>
  </div>
</div>

<?php /* Attivita' pubblica: quello che chiunque puo' gia' vedere girando per il forum e lo
         store, messo insieme. Le CIFRE spese non ci sono: quelle restano al proprietario. */ ?>
<div class="profilo-griglia">
  <div class="profilo-dato">
    <span>Discussioni aperte</span>
    <strong><?= (int) $nTopics ?></strong>
  </div>
  <div class="profilo-dato">
    <span>Risposte sul forum</span>
    <strong><?= (int) $nRisposte ?></strong>
  </div>
  <div class="profilo-dato">
    <span>Mi piace ricevuti</span>
    <strong><?= (int) $nMiPiace ?></strong>
  </div>
  <div class="profilo-dato">
    <span>Pacchetti presi dallo store</span>
    <strong><?= (int) $nAcquisti ?></strong>
  </div>
</div>

<script defer src="/assets/js/vendor/skinview3d.bundle.js?v=3.4.1"></script>
<script defer src="/assets/js/profilo-skin.js?v=<?= @filemtime(__DIR__ . '/assets/js/profilo-skin.js') ?: time() ?>"></script>

<?php require __DIR__ . '/../includes/footer.php'; ?>
