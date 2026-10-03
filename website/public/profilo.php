<?php
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/profile.php';

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

// Dati di gioco (MagixFactions, database a parte): null se non risponde, e il profilo si apre lo stesso.
$stats = profile_game_stats((int) $me['id']);

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

// Acquisti pagati: le ricariche di Magix e lo storico del vecchio store a pacchetti. Ogni
// tabella puo' mancare (stesso trattamento dei dati di gioco: il profilo non deve rompersi).
$acquisti = [];
foreach ([
    "SELECT CONCAT(amount, ' Magix') AS package_name, price, currency, paid_at FROM magix_orders
     WHERE user_id = ? AND status = 'paid' ORDER BY paid_at DESC, id DESC LIMIT 10",
    "SELECT package_name, price, currency, paid_at FROM store_orders
     WHERE user_id = ? AND status = 'paid' ORDER BY paid_at DESC, id DESC LIMIT 10",
] as $sqlAcquisti) {
    try {
        $q = db()->prepare($sqlAcquisti);
        $q->execute([$me['id']]);
        $acquisti = array_merge($acquisti, $q->fetchAll());
    } catch (PDOException $e) {
        // tabella assente: si va avanti con l'altra
    }
}
usort($acquisti, fn($a, $b) => strcmp((string) $b['paid_at'], (string) $a['paid_at']));
$acquisti = array_slice($acquisti, 0, 10);

// Primo accesso al SERVER: lo scrive il plugin MagixBridge in mc_ranks. La colonna puo'
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
$dataGiorno = fn(?string $d) => $d ? date('d/m/Y', strtotime($d)) : '—';

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

<?php if ($avviso): ?><div class="alert alert-success"><?= h($avviso) ?></div><?php endif; ?>

<?php /* Stessa impaginazione della scheda pubblica (/utente): a sinistra la carta con skin,
         nome, gradi e dati dell'account; a destra "In gioco" e "Sul sito" (con gli acquisti).
         Sotto, a tutta larghezza, sicurezza e aspetto: sono impostazioni, non dati. */ ?>
<div class="profilo-pagina">
  <aside class="panel profilo-carta">
    <?php /* Il personaggio si gira trascinandolo: ci pensa assets/js/profilo-skin.js.
             L'immagine ferma resta come ripiego se il 3D non parte. */ ?>
    <div class="profilo-avatar" id="avatar3d"
         data-skin="<?= h(mc_skin_url($me['mc_uuid'], $me['premium_uuid'] ?? null)) ?>">
      <canvas hidden></canvas>
      <img class="profilo-skin" src="<?= h(mc_body_url($me['mc_uuid'], 160, $me['premium_uuid'] ?? null)) ?>" alt=""
           width="90" height="200" loading="lazy">
      <span class="profilo-avatar-nota">Trascina per girarlo</span>
    </div>

    <h1 class="profilo-nome colore-grado"<?= $coloreNome !== null ? ' style="' . rank_color_style($coloreNome) . '"' : '' ?>><?= h($me['mc_username']) ?></h1>
    <?php $tag = player_tag($me); ?>
    <?php if ($tag !== ''): ?>
      <div class="profilo-gradi"><?= $tag ?></div>
    <?php else: ?>
      <p class="profilo-stato">Nessun grado in gioco: entra su <strong>mc.magicadventure.it</strong> per farlo comparire qui.</p>
    <?php endif; ?>

    <dl class="profilo-date">
      <div>
        <dt>In gioco dal</dt>
        <dd><?= $primoAccessoServer ? h($dataGiorno($primoAccessoServer)) : 'mai entrato' ?></dd>
      </div>
      <div>
        <dt>Sul sito dal</dt>
        <dd><?= h($dataGiorno($me['created_at'])) ?></dd>
      </div>
      <div>
        <dt>Ultimo accesso</dt>
        <dd><?= h($dataIt($me['last_login'])) ?></dd>
      </div>
      <div class="profilo-date-uuid">
        <dt>UUID</dt>
        <?php /* L'UUID e' piu' largo della carta: resta su una riga e si scorre trascinandolo
                 (vedi .scorri-trascinando). */ ?>
        <dd><span class="code-box code-box-lungo scorri-trascinando"><?= h($me['mc_uuid']) ?></span></dd>
      </div>
    </dl>
  </aside>

  <div class="profilo-colonna">
    <?= profile_game_panel($stats, true) ?>
    <?php
      // Gli acquisti (con le cifre) li vede solo il proprietario: stanno qui, nel blocco del sito.
      ob_start();
      if ($acquisti):
    ?>
      <h3 class="profilo-sottotitolo"><?= ui_icon('cart') ?> I tuoi acquisti</h3>
      <div class="tabella-scorrevole">
        <table class="rank profilo-acquisti">
          <thead>
            <tr><th>Acquisto</th><th>Prezzo</th><th>Data</th></tr>
          </thead>
          <tbody>
            <?php foreach ($acquisti as $a): ?>
              <tr>
                <td><?= h($a['package_name']) ?></td>
                <td><?= h(number_format((float) $a['price'], 2, ',', '.')) ?> <?= h($a['currency']) ?></td>
                <td><?= h($dataGiorno($a['paid_at'])) ?></td>
              </tr>
            <?php endforeach; ?>
          </tbody>
        </table>
      </div>
    <?php
      endif;
      $__acquistiHtml = (string) ob_get_clean();
    ?>
    <?= profile_site_panel(['Discussioni' => $nTopics, 'Risposte' => $nRisposte, 'Mi piace ricevuti' => $nMiPiace], $__acquistiHtml) ?>
  </div>
</div>

<?php /* La verifica in due passaggi si vede solo a chi riguarda: per gli altri sarebbe una
         voce in piu' che non possono ne' usare ne' capire. */ ?>
<?php if (otp_serve_per($me) || otp_enabled($me)): ?>
<h2><?= ui_icon('shield-check') ?> Verifica in due passaggi</h2>
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

  <p style="color:var(--text-dim); font-size: var(--fs-base);">
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
      <p style="color:var(--text-dim); font-size: var(--fs-base);">Te ne restituisce dieci nuovi e cancella
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
        <p style="font-size: var(--fs-base); margin:0 0 10px;">
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
        <p style="color:var(--text-dim); font-size: var(--fs-sm); margin:10px 0 0;">
          Fallo se hai giocato dal computer di qualcun altro, da una rete che non e&#39; tua, o se
          sospetti che qualcuno stia usando il tuo account: chi e&#39; in partita in quel momento
          viene bloccato sul posto e senza codice non prosegue.
        </p>
      <?php else: ?>
        <p style="font-size: var(--fs-base); margin:0; color:var(--text-dim);">
          Nessuna verifica in corso sul server di gioco: al prossimo ingresso su
          <strong>mc.magicadventure.it</strong> ti verra&#39; chiesto il codice.
        </p>
      <?php endif; ?>
    </div>
  <?php endif; ?>

  <p style="color:var(--text-dim); font-size: var(--fs-sm); margin-bottom:0;">
    Cambiato telefono? Entra con un codice di recupero, poi fatti azzerare la verifica da un
    altro web-admin (<em>Gestione &rarr; Sicurezza</em>): al primo accesso dopo la riconfiguri
    sul telefono nuovo.
  </p>
</div>
<?php endif; ?>

<?php /* Il tema e' una scelta di CHI GUARDA, non del sito: sta qui, fra le sue cose, e
         vale solo per lui. Lo stesso interruttore e' anche nella barra in alto (☾). */ ?>
<h2><?= ui_icon('palette') ?> Aspetto del sito</h2>
<div class="panel">
  <p style="margin:0; color:var(--text-dim); font-size: var(--fs-base);">
    Vale solo per te, su questo browser, e <strong>non scade</strong>: resta finché non lo cambi tu.
    <strong>Automatico</strong> segue le impostazioni del tuo telefono o computer.
  </p>
  <div class="tema-scelte">
    <button type="button" class="btn btn-ghost btn-small" data-tema-scelta="scuro">☾ Scuro</button>
    <button type="button" class="btn btn-ghost btn-small" data-tema-scelta="chiaro">☀ Chiaro</button>
    <button type="button" class="btn btn-ghost btn-small" data-tema-scelta="auto">◐ Automatico</button>
  </div>
</div>

<?php /* Nome, UUID e primi accessi stanno nella carta in cima. Resta solo la nota su come si
         cambia la password. */ ?>
<p class="profilo-nota" style="margin-top:22px;">
  La password e' la stessa che usi per entrare sul server: cambiandola da
  <a href="/cambia-password">Cambia password</a> cambia in tutti e due i posti.
  In gioco puoi farlo con <span class="code-box">/changepassword</span>.
</p>

<?php /* Il visualizzatore 3D pesa mezzo mega: si carica SOLO qui, in coda alla pagina e
         senza bloccarla (defer), e solo se il profilo lo mostra davvero. */ ?>
<script defer src="/assets/js/vendor/skinview3d.bundle.js?v=3.4.1"></script>
<script defer src="/assets/js/profilo-skin.js?v=<?= @filemtime(__DIR__ . '/assets/js/profilo-skin.js') ?: time() ?>"></script>

<?php require __DIR__ . '/../includes/footer.php'; ?>
