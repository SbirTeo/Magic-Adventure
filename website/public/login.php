<?php
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/helpers.php';

if (is_logged_in()) {
    redirect('/');
}

$error = null;

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    csrf_check();
    $username = trim($_POST['username'] ?? '');
    $password = $_POST['password'] ?? '';

    if ($username === '' || $password === '') {
        $error = 'Inserisci nome utente e password.';
    } else {
        $stmt = db()->prepare('SELECT * FROM users WHERE mc_username = ?');
        $stmt->execute([$username]);
        $user = $stmt->fetch();

        if (!$user || !$user['password_hash'] || !password_verify($password, $user['password_hash'])) {
            $error = 'Nome utente o password non corretti.';
        } elseif (otp_serve_per($user)) {
            // Password giusta, ma questo account comanda il sito: serve anche il codice a
            // sei cifre. Fin qui NON si e' collegati — in sessione c'e' solo la nota di chi
            // sta bussando, e /otp la trasforma in accesso vero solo col codice giusto.
            otp_metti_in_attesa((int) $user['id'], !empty($_POST['ricordami']));
            redirect('/otp');
        } else {
            // Id di sessione nuovo: se qualcuno ne avesse imposto uno prima del login,
            // quello resta inutilizzabile (session fixation).
            accesso_completato($user, !empty($_POST['ricordami']), false);
            redirect('/');
        }
    }
}

$page_title = 'Accedi';
require __DIR__ . '/../includes/header.php';
?>
<?php /* Un riquadro stretto e centrato: due campi e un pulsante non hanno bisogno di tutta la
         larghezza della pagina (prima il modulo ne occupava meta' e l'altra meta' restava vuota).
         Sotto, le due domande che chi non riesce ad entrare si fa davvero. */ ?>
<div class="accesso">
  <div class="panel accesso-riquadro">
    <h1 class="accesso-titolo">Accedi</h1>
    <p class="accesso-sottotitolo">Con lo stesso nome e la stessa password che usi in gioco.</p>

    <?php if ($error): ?><div class="alert alert-error"><?= h($error) ?></div><?php endif; ?>

    <form method="post" class="stack accesso-modulo">
      <?= csrf_field() ?>
      <div>
        <label for="username">Nome utente Minecraft</label>
        <input type="text" id="username" name="username" autocomplete="username" required autofocus
               value="<?= h($_POST['username'] ?? '') ?>">
      </div>
      <div>
        <div class="accesso-etichetta-riga">
          <label for="password">Password</label>
          <a href="/password-dimenticata" class="accesso-link-piccolo">Password dimenticata?</a>
        </div>
        <input type="password" id="password" name="password" autocomplete="current-password" required>
      </div>
      <?php
      // Spuntato di serie, e alla prima apertura della pagina (nessun POST ancora) resta
      // spuntato: e' il comportamento chiesto — non doversi ricollegare a ogni riavvio.
      $__ricordami = $_SERVER['REQUEST_METHOD'] !== 'POST' || !empty($_POST['ricordami']);
      ?>
      <label class="campo-check">
        <input type="checkbox" name="ricordami" value="1"<?= $__ricordami ? ' checked' : '' ?>>
        <span>Resta collegato su questo dispositivo</span>
      </label>
      <button type="submit" class="btn btn-accent accesso-invia">Accedi</button>
    </form>
  </div>

  <div class="accesso-aiuto">
    <strong>Non hai ancora un account?</strong>
    <p>
      Entra su <strong>mc.magicadventure.it</strong> e al primo ingresso scrivi
      <code>/register password password</code>: l'account nasce lì, e le stesse credenziali valgono qui.
    </p>
  </div>
</div>

<?php require __DIR__ . '/../includes/footer.php'; ?>
