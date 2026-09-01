<?php
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/helpers.php';

require_login();

$page_title = 'Profilo';
$active = 'profilo';

$me = current_user();

// "Esci dagli altri dispositivi": si fa PRIMA di qualsiasi stampa, perche' finisce con un
// redirect. Il messaggio di conferma torna indietro nell'indirizzo (?usciti=1).
if ($_SERVER['REQUEST_METHOD'] === 'POST' && ($_POST['azione'] ?? '') === 'esci-altri') {
    csrf_check();
    logout_other_devices((int) $me['id']);
    redirect('/profilo?usciti=1');
}
// "Chiudi la sessione di gioco": toglie la fiducia salvata dal server Minecraft. Non serve
// il codice dell'app — e' un'azione che RESTRINGE i permessi, e chiederne uno in piu' vuol
// dire solo che nel momento del sospetto uno non riesce a chiudere la porta.
if ($_SERVER['REQUEST_METHOD'] === 'POST' && ($_POST['azione'] ?? '') === 'otp-esci-gioco') {
    csrf_check();
    otp_close_game_session($me['mc_uuid'], $me['mc_username']);
    redirect('/profilo?gioco=chiusa');
}

// Codici di recupero nuovi: si generano qui e si mostrano una volta sola, subito sotto.
// Si chiede il codice dell'app perche' questa richiesta vale quanto un accesso: chi
// trovasse il computer sbloccato non deve potersi stampare dieci chiavi di scorta.
$codiciNuovi = null;
$erroreOtp = null;
if ($_SERVER['REQUEST_METHOD'] === 'POST' && ($_POST['azione'] ?? '') === 'otp-nuovi-codici') {
    csrf_check();
    $segreto = otp_decifra($me['totp_secret'] ?? null);
    $passo = null;
    if (!otp_enabled($me) || $segreto === null) {
        $erroreOtp = 'La verifica in due passaggi non risulta attiva su questo account.';
    } elseif (otp_blocco_residuo($me) > 0) {
        $erroreOtp = 'Troppi tentativi sbagliati: riprova fra qualche minuto.';
    } elseif (!otp_verifica($segreto, (string) ($_POST['codice'] ?? ''),
                            $me['totp_last_step'] !== null ? (int) $me['totp_last_step'] : null, $passo)) {
        otp_segna_errore((int) $me['id']);
        $erroreOtp = 'Codice non valido.';
    } else {
        db()->prepare('UPDATE users SET totp_last_step = ? WHERE id = ?')->execute([$passo, (int) $me['id']]);
        otp_azzera_errori((int) $me['id']);
        $codiciNuovi = otp_generate_recovery((int) $me['id']);
    }
}

$avviso = isset($_GET['usciti'])
    ? 'Fatto: su tutti gli altri computer e telefoni ora bisogna rifare l\'accesso. Qui resti collegato.'
    : null;
if (($_GET['gioco'] ?? '') === 'chiusa') {
    $avviso = "Sessione di gioco chiusa: al prossimo ingresso su mc.magicadventure.it verra' "
            . "richiesto di nuovo il codice. Se in questo momento qualcuno e' collegato con il "
            . "tuo account, entro pochi secondi si ritrova bloccato e senza codice non prosegue.";
}
if (($_GET['otp'] ?? '') === 'recupero') {
    $avviso = "Sei entrato con un codice di recupero: quel codice ora e' bruciato. "
            . "Se hai cambiato telefono, azzera e riconfigura la verifica qui sotto.";
}

/**
 * Dati di gioco (MagixFactions, database a parte): se quel database non e' raggiungibile
 * il profilo deve comunque aprirsi, quindi si va avanti con i trattini al posto dei numeri.
 */
$stats = null;
try {
    // `rank` fra apici inversi: e' una parola riservata di MariaDB/MySQL (funzione finestra).
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
    $q->execute([$me['id']]);
    $stats = $q->fetch() ?: null;
} catch (PDOException $e) {
    $stats = null;
}

// Nomi dei gradi di fazione come in gioco (config.yml di MagixFactions, sezione `ranks`).
const GRADI_FAZIONE = [
    'recruit' => 'Recluta',
    'member'  => 'Membro',
    'officer' => 'Ufficiale',
    'leader'  => 'Leader',
];

// Attivita' sul sito
$topics = db()->prepare('SELECT COUNT(*) FROM forum_topics WHERE user_id = ?');
$topics->execute([$me['id']]);
$nTopics = (int) $topics->fetchColumn();

$risposte = db()->prepare('SELECT COUNT(*) FROM forum_posts WHERE user_id = ?');
$risposte->execute([$me['id']]);
$nRisposte = (int) $risposte->fetchColumn();

// Mi piace RICEVUTI sui propri messaggi (non quelli messi agli altri).
$nMiPiace = 0;
try {
    $q = db()->prepare('SELECT COUNT(*) FROM forum_likes l JOIN forum_posts p ON p.id = l.post_id
                        WHERE p.user_id = ?');
    $q->execute([$me['id']]);
    $nMiPiace = (int) $q->fetchColumn();
} catch (PDOException $e) {
    $nMiPiace = 0; // tabella non ancora creata
}

// Acquisti pagati (la tabella esiste solo dove lo store e' installato: stesso trattamento
// dei dati di gioco, il profilo non deve rompersi se manca).
$acquisti = [];
try {
    $q = db()->prepare(
        "SELECT package_name, price, currency, paid_at FROM store_orders
         WHERE user_id = ? AND status = 'paid' ORDER BY paid_at DESC, id DESC LIMIT 10"
    );
    $q->execute([$me['id']]);
    $acquisti = $q->fetchAll();
} catch (PDOException $e) {
    $acquisti = [];
}

// Primo accesso al SERVER: lo scrive il plugin MagixWeb in mc_ranks. La colonna puo'
// mancare (installazioni vecchie) e il giocatore puo' non essere ancora passato di li':
// in entrambi i casi si mostra un trattino, senza rompere la pagina.
$primoAccessoServer = null;
try {
    $q = db()->prepare('SELECT first_join FROM mc_ranks WHERE mc_uuid = ? LIMIT 1');
    $q->execute([$me['mc_uuid']]);
    $primoAccessoServer = $q->fetchColumn() ?: null;
} catch (PDOException $e) {
    $primoAccessoServer = null;
}

$coloreNome = player_name_color($me);
$dataIt = fn(?string $d) => $d ? date('d/m/Y H:i', strtotime($d)) : '—';

require __DIR__ . '/../includes/header.php';
?>
<?php /* I comandi del profilo stanno su una riga sola, sopra a tutto: su telefono la riga
         si scorre di lato col dito invece di andare a capo (vedi .profilo-barra). */ ?>
<nav class="profilo-barra" aria-label="Comandi del profilo">
  <?php if (can_manage()): ?>
    <a href="/manage" class="btn btn-ghost btn-small">Gestione</a>
  <?php endif; ?>
  <a href="/cambia-password" class="btn btn-ghost btn-small">Cambia password</a>
  <form method="post"
        onsubmit="return confirm('Vuoi far uscire tutti gli altri dispositivi? Su questo resti collegato.');">
    <?= csrf_field() ?>
    <input type="hidden" name="azione" value="esci-altri">
    <button type="submit" class="btn btn-ghost btn-small">Esci dagli altri dispositivi</button>
  </form>
  <a href="/logout" class="btn btn-ghost btn-small">Esci</a>
</nav>

<h1 class="page-title">Il tuo profilo</h1>

<?php if ($avviso): ?><div class="alert alert-success"><?= h($avviso) ?></div><?php endif; ?>

<div class="profilo-testata panel">
  <?php /* Il personaggio si gira trascinandolo: ci pensa assets/js/profilo-skin.js.
           L'immagine ferma resta come ripiego se il 3D non parte. */ ?>
  <div class="profilo-avatar" id="avatar3d"
       data-skin="<?= h('https://minotar.net/skin/' . rawurlencode(str_replace('-', '', $me['mc_uuid']))) ?>">
    <canvas hidden></canvas>
    <img class="profilo-skin" src="<?= h(mc_body_url($me['mc_uuid'], 160)) ?>" alt=""
         width="90" height="200" loading="lazy">
    <span class="profilo-avatar-nota">Trascina per girarlo</span>
  </div>

  <div class="profilo-testata-testo">
    <div class="profilo-intestazione">
      <div class="profilo-nome colore-grado"<?= $coloreNome !== null ? ' style="' . rank_color_style($coloreNome) . '"' : '' ?>>
        <?= h($me['mc_username']) ?>
      </div>
      <?php $tag = player_tag($me); ?>
      <?php if ($tag !== ''): ?>
        <div class="profilo-gradi"><?= $tag ?></div>
      <?php endif; ?>
    </div>
    <?php if ($tag === ''): ?>
      <p class="profilo-nota">Nessun grado in gioco: entra su <strong>mc.magicadventure.it</strong> per farlo comparire qui.</p>
    <?php endif; ?>

    <?php /* Due colonne di coppie etichetta/valore, ognuna sulla sua riga con un filo di
             separazione: prima erano quattro colonne sparse e sembravano buttate lì. */ ?>
    <dl class="profilo-account">
      <div>
        <dt>Account Minecraft</dt>
        <dd><strong class="testo-verde"><?= h($me['mc_username']) ?></strong></dd>
      </div>
      <div>
        <dt>UUID</dt>
        <?php /* L'UUID e' piu' largo della colonna: invece di spezzarsi su due righe resta
                 su una sola e si scorre trascinandolo (vedi .scorri-trascinando). */ ?>
        <dd><span class="code-box code-box-lungo scorri-trascinando"><?= h($me['mc_uuid']) ?></span></dd>
      </div>
      <div>
        <dt>Primo accesso al server</dt>
        <dd><?= h($dataIt($primoAccessoServer)) ?></dd>
      </div>
      <div>
        <dt>Primo accesso al sito</dt>
        <dd><?= h($dataIt($me['created_at'])) ?></dd>
      </div>
    </dl>
  </div>
</div>

<h2>⚔ In gioco</h2>
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
          ? h(GRADI_FAZIONE[strtolower((string) $r)] ?? ucfirst((string) $r))
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

<?php /* La verifica in due passaggi si vede solo a chi riguarda: per gli altri sarebbe una
         voce in piu' che non possono ne' usare ne' capire. */ ?>
<?php if (otp_serve_per($me) || otp_enabled($me)): ?>
<h2>🔐 Verifica in due passaggi</h2>
<div class="panel">
  <?php if ($erroreOtp): ?><div class="alert alert-error"><?= h($erroreOtp) ?></div><?php endif; ?>

  <p class="otp-stato">
    <span class="otp-pallino<?= otp_enabled($me) ? '' : ' is-spento' ?>"></span>
    <?php if (otp_enabled($me)): ?>
      <span><strong>Attiva</strong> dal <?= $dataIt($me['totp_activated_at']) ?> &middot;
      <?= otp_recupero_rimasti((int) $me['id']) ?> codici di recupero ancora buoni</span>
    <?php else: ?>
      <span><strong>Non attiva</strong> &mdash; obbligatoria su questo account: te la chiedera&#39; al prossimo accesso</span>
    <?php endif; ?>
  </p>

  <p style="color:var(--text-dim); font-size:14px;">
    Lo stesso codice a sei cifre serve per entrare nel gestionale del sito e per entrare
    in gioco su <strong>mc.magicadventure.it</strong>.
  </p>

  <?php if ($codiciNuovi): ?>
    <div class="alert alert-success">Codici nuovi: i precedenti non valgono piu&#39;. Salvali adesso, non si rivedono.</div>
    <ul class="otp-codici" data-utente="<?= h($me['mc_username']) ?>">
      <?php foreach ($codiciNuovi as $c): ?><li><?= h($c) ?></li><?php endforeach; ?>
    </ul>
  <?php elseif (otp_enabled($me)): ?>
    <details class="otp-recupero">
      <summary>Rigenera i codici di recupero</summary>
      <p style="color:var(--text-dim); font-size:14px;">Te ne restituisce dieci nuovi e cancella
         quelli di prima. Fallo se li hai finiti o se pensi che qualcuno li abbia visti.</p>
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="azione" value="otp-nuovi-codici">
        <div>
          <label for="codiceOtp">Codice dell'app (conferma che sei tu)</label>
          <input type="text" id="codiceOtp" name="codice" inputmode="numeric" pattern="[0-9]*"
                 maxlength="6" class="otp-input" autocomplete="one-time-code">
        </div>
        <button type="submit" class="btn btn-ghost">Genera codici nuovi</button>
      </form>
    </details>
  <?php endif; ?>

  <?php
    // Sessione di gioco: si mostra solo a chi la verifica ce l'ha attiva, se no si
    // parlerebbe di una porta che per quell'account non esiste ancora.
    $__sessioniGioco = otp_enabled($me) ? otp_sessioni_gioco($me['mc_uuid']) : [];
  ?>
  <?php if (otp_enabled($me)): ?>
    <div class="otp-sessione">
      <h3>Sessione di gioco</h3>
      <?php if ($__sessioniGioco): ?>
        <p style="font-size:14px; margin:0 0 10px;">
          Su <strong>mc.magicadventure.it</strong> il codice non viene richiesto a ogni
          ingresso: dopo una verifica riuscita il server si fida di te per 12 ore, da quella
          stessa rete. Adesso risulta:
        </p>
        <ul class="otp-sessioni-elenco">
          <?php foreach ($__sessioniGioco as $sg): ?>
            <li>
              <span class="otp-pallino"></span>
              verificata <?= h(time_ago($sg['verified_at'])) ?>
              <span style="color:var(--text-dim);">dalla rete <?= h(otp_ip_mascherato($sg['ip'])) ?></span>
            </li>
          <?php endforeach; ?>
        </ul>
        <form method="post"
              onsubmit="return confirm('Chiudere la sessione di gioco? Al prossimo ingresso ti verra' richiesto il codice, e chi fosse collegato ora col tuo account viene bloccato subito.');">
          <?= csrf_field() ?>
          <input type="hidden" name="azione" value="otp-esci-gioco">
          <button type="submit" class="btn btn-ghost">Chiudi la sessione di gioco</button>
        </form>
        <p style="color:var(--text-dim); font-size:13px; margin:10px 0 0;">
          Fallo se hai giocato dal computer di qualcun altro, da una rete che non e&#39; tua, o se
          sospetti che qualcuno stia usando il tuo account: chi e&#39; in partita in quel momento
          viene bloccato sul posto e senza codice non prosegue.
        </p>
      <?php else: ?>
        <p style="font-size:14px; margin:0; color:var(--text-dim);">
          Nessuna verifica in corso sul server di gioco: al prossimo ingresso su
          <strong>mc.magicadventure.it</strong> ti verra&#39; chiesto il codice.
        </p>
      <?php endif; ?>
    </div>
  <?php endif; ?>

  <p style="color:var(--text-dim); font-size:13px; margin-bottom:0;">
    Cambiato telefono? Entra con un codice di recupero, poi fatti azzerare la verifica da un
    altro web-admin (<em>Gestione &rarr; Sicurezza</em>): al primo accesso dopo la riconfiguri
    sul telefono nuovo.
  </p>
</div>
<?php endif; ?>

<?php /* Il tema e' una scelta di CHI GUARDA, non del sito: sta qui, fra le sue cose, e
         vale solo per lui. Lo stesso interruttore e' anche nella barra in alto (☾). */ ?>
<h2>🎨 Aspetto del sito</h2>
<div class="panel">
  <p style="margin:0; color:var(--text-dim); font-size:14px;">
    Vale solo per te, su questo browser, e <strong>non scade</strong>: resta finché non lo cambi tu.
    <strong>Automatico</strong> segue le impostazioni del tuo telefono o computer.
  </p>
  <div class="tema-scelte">
    <button type="button" class="btn btn-ghost btn-small" data-tema-scelta="scuro">☾ Scuro</button>
    <button type="button" class="btn btn-ghost btn-small" data-tema-scelta="chiaro">☀ Chiaro</button>
    <button type="button" class="btn btn-ghost btn-small" data-tema-scelta="auto">◐ Automatico</button>
  </div>
</div>

<h2>💬 Attività sul sito</h2>
<div class="profilo-griglia">
  <div class="profilo-dato">
    <span>Discussioni aperte</span>
    <strong><?= $nTopics ?></strong>
  </div>
  <div class="profilo-dato">
    <span>Risposte nel forum</span>
    <strong><?= $nRisposte ?></strong>
  </div>
  <div class="profilo-dato">
    <span>Mi piace ricevuti</span>
    <strong><?= $nMiPiace ?></strong>
  </div>
  <?php /* "Iscritto dal" sta ora fra i dati dell'account, in cima. */ ?>
  <div class="profilo-dato">
    <span>Ultimo accesso</span>
    <strong><?= h($dataIt($me['last_login'])) ?></strong>
  </div>
</div>

<?php if ($acquisti): ?>
  <h2>🛒 I tuoi acquisti</h2>
  <div class="panel">
    <div class="tabella-scorrevole">
      <table class="rank">
        <thead>
          <tr><th>Pacchetto</th><th>Prezzo</th><th>Data</th></tr>
        </thead>
        <tbody>
          <?php foreach ($acquisti as $a): ?>
            <tr>
              <td><?= h($a['package_name']) ?></td>
              <td><?= h(number_format((float) $a['price'], 2, ',', '.')) ?> <?= h($a['currency']) ?></td>
              <td><?= h($dataIt($a['paid_at'])) ?></td>
            </tr>
          <?php endforeach; ?>
        </tbody>
      </table>
    </div>
  </div>
<?php endif; ?>

<?php /* Il riquadro "Account" non c'e' piu': nome, UUID e primi accessi stanno in cima,
         accanto alla skin. Resta solo la nota su come si cambia la password. */ ?>
<p class="profilo-nota" style="margin-top:22px;">
  La password e' la stessa che usi per entrare sul server: cambiandola da
  <a href="/cambia-password">Cambia password</a> cambia in tutti e due i posti.
  In gioco puoi farlo con <span class="code-box">/cambiapassword</span>.
</p>

<?php /* Il visualizzatore 3D pesa mezzo mega: si carica SOLO qui, in coda alla pagina e
         senza bloccarla (defer), e solo se il profilo lo mostra davvero. */ ?>
<script defer src="/assets/js/vendor/skinview3d.bundle.js?v=3.4.1"></script>
<script defer src="/assets/js/profilo-skin.js?v=<?= @filemtime(__DIR__ . '/assets/js/profilo-skin.js') ?: time() ?>"></script>

<?php require __DIR__ . '/../includes/footer.php'; ?>
