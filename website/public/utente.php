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
require_once __DIR__ . '/../includes/profile.php';

$nome = trim((string) ($_GET['nome'] ?? ''));

$utente = null;
if ($nome !== '') {
    $q = db()->prepare(
        'SELECT u.id, u.mc_uuid, u.premium_uuid, u.mc_username, u.is_admin, u.created_at, u.last_seen, u.last_login, '
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

// Dati di gioco (MagixFactions): se quel database non risponde la scheda si apre lo stesso.
$stats = profile_game_stats((int) $utente['id']);

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
$ordiniPagati = paid_orders_sql();
$nAcquisti = $ordiniPagati === null ? 0 : $conta("SELECT COUNT(*) FROM {$ordiniPagati} o WHERE user_id = ?", (int) $utente['id']);

$coloreNome = player_name_color($utente);
$tag = player_tag($utente);
$dataIt = fn(?string $d) => $d ? date('d/m/Y', strtotime($d)) : '';
$io = current_user();
$sonoIo = $io && (int) $io['id'] === (int) $utente['id'];

require __DIR__ . '/../includes/header.php';
?>
<?php /* Una scheda sola: a sinistra chi e' (skin, nome, gradi, da quando c'e'), a destra cosa
         fa in gioco e sul sito. Prima il nome compariva due volte (titolo e scheda) e otto
         riquadri uguali mettevano sullo stesso piano la fazione e i "mi piace". */ ?>
<a href="/utenti" class="profilo-indietro">&larr; Tutti gli utenti</a>

<div class="profilo-pagina">
  <aside class="panel profilo-carta">
    <?php /* Figura girabile a 360 gradi col trascinamento (assets/js/profilo-skin.js, che vuole
             la tela e l'indirizzo della skin in data-skin); l'immagine ferma e' il ripiego. */ ?>
    <div class="profilo-avatar" id="avatar3d"
         data-skin="<?= h(mc_skin_url($utente['mc_uuid'], $utente['premium_uuid'] ?? null)) ?>">
      <canvas hidden></canvas>
      <img class="profilo-skin" src="<?= h(mc_body_url($utente['mc_uuid'], 160, $utente['premium_uuid'] ?? null)) ?>" alt="Skin di <?= h($utente['mc_username']) ?>"
           width="90" height="200" loading="lazy">
      <span class="profilo-avatar-nota">Trascina per girarlo</span>
    </div>

    <h1 class="profilo-nome colore-grado"<?= $coloreNome !== null ? ' style="' . rank_color_style($coloreNome) . '"' : '' ?>><?= h($utente['mc_username']) ?></h1>
    <?php if ($tag !== ''): ?>
      <div class="profilo-gradi"><?= $tag ?></div>
    <?php endif; ?>

    <?php $ultimaVolta = $utente['last_seen'] ?: ($utente['last_login'] ?: null); ?>
    <p class="profilo-stato">
      <?php if (is_on_site($utente)): ?>
        <span class="online-dot" aria-hidden="true"></span> Sul sito adesso
      <?php elseif ($ultimaVolta): ?>
        Visto sul sito <?= h(time_ago($ultimaVolta)) ?>
      <?php else: ?>
        Non si &egrave; ancora visto sul sito
      <?php endif; ?>
    </p>

    <dl class="profilo-date">
      <div>
        <dt>In gioco dal</dt>
        <dd><?= !empty($utente['first_join']) ? h($dataIt($utente['first_join'])) : 'mai entrato' ?></dd>
      </div>
      <div>
        <dt>Sul sito dal</dt>
        <dd><?= h($dataIt($utente['created_at'])) ?></dd>
      </div>
    </dl>

    <?php if ($sonoIo): ?>
      <a href="/profilo" class="btn btn-ghost btn-small profilo-carta-azione">Questo sei tu: apri il tuo profilo</a>
    <?php endif; ?>
  </aside>

  <div class="profilo-colonna">
    <?= profile_game_panel($stats, false) ?>
    <?php
      // Quanti acquisti, non quanto ha speso: la cifra e' un fatto suo. Si conta solo se c'e'.
      $numeri = ['Discussioni' => $nTopics, 'Risposte' => $nRisposte, 'Mi piace ricevuti' => $nMiPiace];
      if ($nAcquisti > 0) $numeri['Acquisti'] = $nAcquisti;
    ?>
    <?= profile_site_panel($numeri) ?>
  </div>
</div>

<script defer src="/assets/js/vendor/skinview3d.bundle.js?v=3.4.1"></script>
<script defer src="/assets/js/profilo-skin.js?v=<?= @filemtime(__DIR__ . '/assets/js/profilo-skin.js') ?: time() ?>"></script>

<?php require __DIR__ . '/../includes/footer.php'; ?>
