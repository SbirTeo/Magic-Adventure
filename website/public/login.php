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
<h1 class="page-title">Accedi</h1>

<?php if ($error): ?><div class="alert alert-error"><?= h($error) ?></div><?php endif; ?>

<div class="panel">
  <form method="post" class="stack">
    <?= csrf_field() ?>
    <div>
      <label for="username">Nome utente Minecraft</label>
      <input type="text" id="username" name="username" autocomplete="username" value="<?= h($_POST['username'] ?? '') ?>">
    </div>
    <div>
      <label for="password">Password</label>
      <input type="password" id="password" name="password" autocomplete="current-password">
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
    <button type="submit" class="btn btn-accent">Accedi</button>
  </form>
</div>

<p style="color:var(--text-dim);">
  Non hai ancora un account? Ti basta entrare su <strong>mc.magicadventure.it</strong>:
  l'account nasce li', e queste stesse credenziali valgono qui.<br>
  Hai dimenticato la password? <a href="/password-dimenticata">Reimpostala</a>.
</p>

<?php require __DIR__ . '/../includes/footer.php'; ?>
