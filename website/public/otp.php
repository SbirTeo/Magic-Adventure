<?php
/**
 * Il secondo passaggio dell'accesso: il codice a sei cifre. Indirizzo: /otp
 *
 * Ci si arriva solo a meta' strada, cioe' con la password gia' verificata e la nota
 * `otp_attesa` in sessione (la mette /login, /set-password o la guardia in auth.php).
 * Finche' il codice non arriva NON esiste nessun accesso valido: in sessione non c'e'
 * user_id, quindi non c'e' niente da "bucare" con un indirizzo indovinato.
 *
 * Tre schermate, una dopo l'altra:
 *   1. attivazione — chi non ha ancora un segreto: QR da inquadrare + conferma col codice;
 *   2. codici di recupero — si vedono UNA volta sola, appena attivata la verifica;
 *   3. verifica — le volte successive: sei cifre, oppure un codice di recupero.
 */
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/otp.php';

// Chi e' gia' dentro non ha niente da fare qui.
if (is_logged_in() && !empty($_SESSION['otp_ok'])) {
    redirect('/');
}

/** L'accesso a meta' strada scade: una pagina lasciata aperta non resta buona per sempre. */
const OTP_ATTESA_MINUTI = 15;

$attesa = $_SESSION['otp_attesa'] ?? null;
if (!$attesa || time() - (int) ($attesa['ora'] ?? 0) > OTP_ATTESA_MINUTI * 60) {
    unset($_SESSION['otp_attesa'], $_SESSION['otp_segreto_nuovo']);
    redirect('/login');
}

$q = db()->prepare('SELECT u.*, r.groups_json FROM users u
                    LEFT JOIN mc_ranks r ON r.mc_uuid = u.mc_uuid COLLATE utf8mb4_unicode_ci
                    WHERE u.id = ?');
$q->execute([(int) $attesa['user_id']]);
$utente = $q->fetch();

if (!$utente) {
    unset($_SESSION['otp_attesa']);
    redirect('/login');
}

// Ruolo tolto nel frattempo: niente verifica, si entra e basta.
if (!otp_serve_per($utente)) {
    accesso_completato($utente, (bool) $attesa['ricordami'], false);
    redirect('/');
}

$errore = null;
$codiciRecupero = $_SESSION['otp_codici_da_mostrare'] ?? null;
$blocco = otp_blocco_residuo($utente);
$attivo = otp_attivo($utente);

// Segreto proposto a chi deve ancora attivare: si tiene in sessione finche' non lo conferma
// col primo codice giusto. Nel database ci finisce solo a conferma avvenuta — se no un
// segreto mai confermato (magari inquadrato male) chiuderebbe fuori l'account.
if (!$attivo && empty($_SESSION['otp_segreto_nuovo'])) {
    $_SESSION['otp_segreto_nuovo'] = otp_nuovo_segreto();
}
$segretoNuovo = $_SESSION['otp_segreto_nuovo'] ?? '';

if ($_SERVER['REQUEST_METHOD'] === 'POST' && !$codiciRecupero) {
    csrf_check();
    $azione = $_POST['azione'] ?? '';
    $codice = trim((string) ($_POST['codice'] ?? ''));

    if ($blocco > 0) {
        $errore = 'Troppi tentativi sbagliati. Riprova fra ' . ceil($blocco / 60) . ' minuti.';

    } elseif ($azione === 'attiva') {
        // ---- Prima attivazione ------------------------------------------------------
        $passo = null;
        if (!otp_chiave_pronta()) {
            $errore = 'Manca la chiave OTP_CHIAVE in config.php: avvisa chi amministra il server.';
        } elseif (otp_verifica($segretoNuovo, $codice, null, $passo)) {
            otp_attiva((int) $utente['id'], $segretoNuovo, (int) $passo);
            unset($_SESSION['otp_segreto_nuovo']);
            // I codici di recupero si mostrano subito dopo, una volta sola.
            $_SESSION['otp_codici_da_mostrare'] = otp_genera_recupero((int) $utente['id']);
            redirect('/otp');
        } else {
            otp_segna_errore((int) $utente['id']);
            $errore = 'Codice non valido. Controlla di aver inquadrato il QR e riprova con il codice mostrato adesso.';
        }

    } elseif ($azione === 'verifica') {
        // ---- Accessi successivi -----------------------------------------------------
        $segreto = otp_decifra($utente['totp_secret']);
        $passo = null;

        if ($segreto === null) {
            // Chiave cambiata o dato rovinato: meglio dirlo che lasciare l'utente a
            // sbattere contro un codice "sempre sbagliato".
            $errore = 'Il segreto salvato non e\' leggibile: usa un codice di recupero, oppure fai azzerare la verifica.';
        } elseif (otp_verifica($segreto, $codice, $utente['totp_last_step'] !== null ? (int) $utente['totp_last_step'] : null, $passo)) {
            db()->prepare('UPDATE users SET totp_last_step = ? WHERE id = ?')
                ->execute([$passo, (int) $utente['id']]);
            otp_azzera_errori((int) $utente['id']);
            accesso_completato($utente, (bool) $attesa['ricordami'], true);
            redirect('/');
        } else {
            otp_segna_errore((int) $utente['id']);
            $errore = 'Codice non valido. Controlla l\'ora del telefono se sbaglia sempre.';
        }

    } elseif ($azione === 'recupero') {
        // ---- Codice di recupero (telefono perso) -------------------------------------
        if (otp_usa_recupero((int) $utente['id'], $codice)) {
            otp_azzera_errori((int) $utente['id']);
            accesso_completato($utente, (bool) $attesa['ricordami'], true);
            // Entrato con un codice di scorta: la cosa giusta da fare subito e' rifare la
            // verifica su un telefono che si ha in mano, quindi lo si porta li'.
            redirect('/profilo?otp=recupero');
        }
        otp_segna_errore((int) $utente['id']);
        $errore = 'Codice di recupero non valido, o gia\' usato.';
    }

    // Dopo uno sbaglio i contatori sono cambiati: si rilegge la riga.
    $q->execute([(int) $attesa['user_id']]);
    $utente = $q->fetch() ?: $utente;
    $blocco = otp_blocco_residuo($utente);
}

// Codici di recupero appena generati: si mostrano una volta e poi spariscono dalla sessione.
if ($codiciRecupero && $_SERVER['REQUEST_METHOD'] === 'POST' && ($_POST['azione'] ?? '') === 'ho-salvato') {
    csrf_check();
    unset($_SESSION['otp_codici_da_mostrare']);
    $q->execute([(int) $attesa['user_id']]);
    $utente = $q->fetch();
    accesso_completato($utente, (bool) $attesa['ricordami'], true);
    redirect('/');
}

$page_title = 'Verifica in due passaggi';
$page_noindex = true;
require __DIR__ . '/../includes/header.php';
?>
<h1 class="page-title">Verifica in due passaggi</h1>

<?php if ($errore): ?><div class="alert alert-error"><?= h($errore) ?></div><?php endif; ?>

<?php if ($codiciRecupero): ?>
  <?php /* ---- SCHERMATA 2: i codici di scorta, visibili una volta sola ---------------- */ ?>
  <div class="alert alert-ok">Verifica attivata per <strong><?= h($utente['mc_username']) ?></strong>.</div>

  <div class="panel">
    <h2>Codici di recupero</h2>
    <p>Se un giorno perdi il telefono, questi codici sono l'unico modo per rientrare da solo.
       <strong>Ognuno vale una volta sola.</strong> Salvali adesso: da questa pagina non si
       rivedono piu&#39;.</p>

    <ul class="otp-codici" data-utente="<?= h($utente['mc_username']) ?>">
      <?php foreach ($codiciRecupero as $c): ?>
        <li><?= h($c) ?></li>
      <?php endforeach; ?>
    </ul>

    <p style="color:var(--text-dim); font-size:13px;">
      Stampali, o mettili nel gestore di password — non nella stessa app che genera i codici:
      se sparisce quella, spariscono insieme la chiave e la copia della chiave.
    </p>

    <form method="post" class="stack">
      <?= csrf_field() ?>
      <input type="hidden" name="azione" value="ho-salvato">
      <label class="campo-check">
        <input type="checkbox" name="conferma" value="1" required>
        <span>Li ho salvati in un posto sicuro</span>
      </label>
      <button type="submit" class="btn btn-accent">Entra nel sito</button>
    </form>
  </div>

<?php elseif (!$attivo): ?>
  <?php /* ---- SCHERMATA 1: attivazione ------------------------------------------------ */ ?>
  <div class="panel">
    <p>L'account <strong class="testo-verde"><?= h($utente['mc_username']) ?></strong> puo&#39; comandare il sito,
       quindi la password da sola non basta: serve anche un codice a sei cifre che cambia
       ogni trenta secondi.</p>
    <p style="color:var(--text-dim); font-size:14px;">
      Ti serve un'app come <strong>Google Authenticator</strong>, <strong>Aegis</strong>,
      <strong>Bitwarden</strong> o <strong>1Password</strong>. Funziona anche senza rete.
      Lo stesso codice varra&#39; anche per entrare in gioco su mc.magicadventure.it.
    </p>
  </div>

  <?php if (!otp_chiave_pronta()): ?>
    <div class="alert alert-error">
      Manca <code>OTP_CHIAVE</code> in <code>config.php</code>: senza, il segreto finirebbe
      nel database in chiaro. Avvisa chi amministra il server prima di continuare.
    </div>
  <?php endif; ?>

  <div class="panel otp-attivazione">
    <div class="otp-qr">
      <?php /* Il QR lo disegna il browser da questo testo: il segreto non passa per nessun
               servizio esterno (le "API per fare QR" si prenderebbero la chiave di casa). */ ?>
      <div id="otpQr" data-uri="<?= h(otp_uri($utente['mc_username'], $segretoNuovo)) ?>"></div>
      <noscript><p style="color:var(--text-dim);">Con JavaScript spento il QR non si disegna: usa la chiave qui accanto.</p></noscript>
    </div>

    <div class="otp-attivazione-testo">
      <ol>
        <li>Apri l'app e scegli <em>aggiungi account</em> &rarr; <em>scansiona QR</em>.</li>
        <li>Inquadra il quadrato qui accanto.</li>
        <li>Scrivi qui sotto il codice a sei cifre che compare.</li>
      </ol>

      <p style="font-size:13px; color:var(--text-dim);">Non riesci a inquadrare? Inserisci la chiave a mano:</p>
      <p class="code-box otp-segreto"><?= h(otp_segreto_leggibile($segretoNuovo)) ?></p>

      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="azione" value="attiva">
        <div>
          <label for="codice">Codice a sei cifre</label>
          <input type="text" id="codice" name="codice" inputmode="numeric" autocomplete="one-time-code"
                 pattern="[0-9]*" maxlength="6" class="otp-input" autofocus
                 <?= $blocco > 0 ? 'disabled' : '' ?>>
        </div>
        <button type="submit" class="btn btn-accent" <?= $blocco > 0 ? 'disabled' : '' ?>>Attiva la verifica</button>
      </form>
    </div>
  </div>

<?php else: ?>
  <?php /* ---- SCHERMATA 3: verifica di tutti i giorni --------------------------------- */ ?>
  <div class="panel">
    <p>Accesso come <strong class="testo-verde"><?= h($utente['mc_username']) ?></strong>. Inserisci il
       codice a sei cifre dell'app.</p>

    <?php if ($blocco > 0): ?>
      <div class="alert alert-error">Troppi tentativi sbagliati: riprova fra <?= ceil($blocco / 60) ?> minuti.</div>
    <?php endif; ?>

    <form method="post" class="stack">
      <?= csrf_field() ?>
      <input type="hidden" name="azione" value="verifica">
      <div>
        <label for="codice">Codice</label>
        <input type="text" id="codice" name="codice" inputmode="numeric" autocomplete="one-time-code"
               pattern="[0-9]*" maxlength="6" class="otp-input" autofocus
               <?= $blocco > 0 ? 'disabled' : '' ?>>
      </div>
      <button type="submit" class="btn btn-accent" <?= $blocco > 0 ? 'disabled' : '' ?>>Entra</button>
    </form>
  </div>

  <details class="panel otp-recupero">
    <summary>Non ho il telefono con me</summary>
    <p style="color:var(--text-dim); font-size:14px;">Usa uno dei codici di recupero che hai
       salvato quando hai attivato la verifica. Ognuno vale una volta sola.</p>
    <form method="post" class="stack">
      <?= csrf_field() ?>
      <input type="hidden" name="azione" value="recupero">
      <div>
        <label for="recupero">Codice di recupero</label>
        <input type="text" id="recupero" name="codice" autocomplete="off" placeholder="ABCDE-FGHIJ"
               <?= $blocco > 0 ? 'disabled' : '' ?>>
      </div>
      <button type="submit" class="btn btn-ghost" <?= $blocco > 0 ? 'disabled' : '' ?>>Entra con il codice di recupero</button>
    </form>
    <p style="color:var(--text-dim); font-size:13px;">Finiti anche quelli? Solo chi ha accesso al
       server puo&#39; azzerare la verifica (vedi la guida per gli amministratori).</p>
  </details>
<?php endif; ?>

<p style="margin-top:16px;"><a href="/login">&larr; Torna all'accesso</a></p>

<?php if (!$attivo && !$codiciRecupero): ?>
<script src="/assets/js/vendor/qrcode.min.js?v=<?= @filemtime(__DIR__ . '/assets/js/vendor/qrcode.min.js') ?: 1 ?>"></script>
<script>
  // Disegna il QR nel riquadro: tutto qui nel browser, il segreto non esce dal sito.
  (function () {
    var box = document.getElementById('otpQr');
    if (!box || typeof QRCode === 'undefined') return;
    new QRCode(box, {
      text: box.dataset.uri,
      width: 200,
      height: 200,
      correctLevel: QRCode.CorrectLevel.M
    });
  })();
</script>
<?php endif; ?>

<?php require __DIR__ . '/../includes/footer.php'; ?>
