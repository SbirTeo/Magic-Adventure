<?php
/**
 * Cambio password di chi e' gia' collegato.
 *
 * Prima questa pagina non esisteva: il bottone "Cambia password" del profilo mandava a
 * /link, cioe' costringeva a entrare in gioco e farsi dare un codice. Con l'account unico
 * quel giro non ha piu' senso — e su un server non premium era anche pericoloso.
 *
 * La password attuale viene chiesta e non e' una formalita': senza, chiunque passasse
 * davanti a un computer lasciato collegato potrebbe riscrivere la password e prendersi
 * l'account, sul sito e sul server insieme.
 */
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/helpers.php';

require_login();

$utente = current_user();
$error = null;
$fatto = false;

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    csrf_check();
    $attuale = $_POST['attuale'] ?? '';
    $nuova   = $_POST['nuova'] ?? '';
    $nuova2  = $_POST['nuova2'] ?? '';

    if ($attuale === '' || $nuova === '') {
        $error = 'Compila tutti i campi.';
    } elseif (!$utente['password_hash'] || !password_verify($attuale, $utente['password_hash'])) {
        $error = 'La password attuale non e\' corretta.';
    } elseif (strlen($nuova) < 8) {
        $error = 'La password nuova deve essere lunga almeno 8 caratteri.';
    } elseif ($nuova !== $nuova2) {
        $error = 'Le due password non coincidono.';
    } elseif ($nuova === $attuale) {
        $error = 'La password nuova deve essere diversa da quella di adesso.';
    } else {
        // BCRYPT esplicito: la stessa impronta la rilegge MagixAuth in gioco.
        $hash = password_hash($nuova, PASSWORD_BCRYPT);

        // session_epoch + 1 fa cadere ogni altra sessione aperta col vecchio segreto: e'
        // l'unico modo per cacciare davvero un browser che non e' piu' tuo.
        db()->prepare('UPDATE users SET password_hash = ?, session_epoch = session_epoch + 1 WHERE id = ?')
            ->execute([$hash, (int) $utente['id']]);
        remember_forget_all((int) $utente['id']);

        // E anche i dispositivi riconosciuti in gioco: al prossimo ingresso la password
        // torna obbligatoria ovunque, che e' esattamente il senso di averla cambiata.
        try {
            db()->prepare('DELETE FROM auth_sessions WHERE mc_uuid = ?')
                ->execute([$utente['mc_uuid']]);
        } catch (PDOException) {
            // Il plugin non e' ancora installato: nessuna sessione di gioco da chiudere.
        }

        $fresco = db()->prepare('SELECT * FROM users WHERE id = ?');
        $fresco->execute([(int) $utente['id']]);
        $utente = $fresco->fetch();

        // Chi ha appena cambiato la password resta collegato qui: e' l'unico dispositivo
        // di cui sappiamo con certezza che e' suo, visto che conosceva quella di prima.
        accesso_completato($utente, false, true);
        $fatto = true;
    }
}

$page_title = 'Cambia password';
require __DIR__ . '/../includes/header.php';
?>
<h1 class="page-title">Cambia password</h1>

<?php if ($fatto): ?>
  <div class="alert alert-success">Password cambiata. Vale anche in gioco, con lo stesso nome.</div>
  <div class="panel">
    <p style="color:var(--text-dim); font-size:14px;">Gli altri dispositivi sono stati disconnessi, sul sito e in gioco: dovranno usare la password nuova.</p>
    <a href="/profilo" class="btn btn-ghost btn-small">Torna al profilo</a>
  </div>
<?php else: ?>

  <div class="panel">
    <p>Account: <strong class="testo-verde"><?= h($utente['mc_username']) ?></strong></p>
    <p style="color:var(--text-dim); font-size:14px;">La password e' la stessa che usi per entrare sul server: cambiandola qui, cambia anche in gioco.</p>
  </div>

  <?php if ($error): ?><div class="alert alert-error"><?= h($error) ?></div><?php endif; ?>

  <div class="panel">
    <form method="post" class="stack">
      <?= csrf_field() ?>
      <div>
        <label for="attuale">Password attuale</label>
        <input type="password" id="attuale" name="attuale" autocomplete="current-password">
      </div>
      <div>
        <label for="nuova">Password nuova</label>
        <input type="password" id="nuova" name="nuova" autocomplete="new-password" minlength="8">
      </div>
      <div>
        <label for="nuova2">Conferma password nuova</label>
        <input type="password" id="nuova2" name="nuova2" autocomplete="new-password" minlength="8">
      </div>
      <button type="submit" class="btn btn-accent">Salva password</button>
    </form>
  </div>

  <p style="margin-top:16px; color:var(--text-dim);">Non ricordi quella attuale? <a href="/password-dimenticata">Reimpostala da qui</a>.</p>

<?php endif; ?>

<?php require __DIR__ . '/../includes/footer.php'; ?>
