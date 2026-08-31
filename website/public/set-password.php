<?php
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/helpers.php';

// Ci si arriva solo dopo aver dimostrato di essere se stessi in /password-dimenticata,
// col codice della verifica in due passaggi. Il vecchio ingresso da /link non esiste piu':
// su un server non premium bastava entrare col nome di un altro per arrivare fin qui.
if (empty($_SESSION['reset_user_id'])) {
    redirect('/password-dimenticata');
}

$stmt = db()->prepare('SELECT * FROM users WHERE id = ?');
$stmt->execute([$_SESSION['reset_user_id']]);
$pendingUser = $stmt->fetch();

if (!$pendingUser) {
    unset($_SESSION['reset_user_id']);
    redirect('/password-dimenticata');
}

$error = null;

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    csrf_check();
    $pass = $_POST['password'] ?? '';
    $pass2 = $_POST['password2'] ?? '';

    if (strlen($pass) < 8) {
        $error = 'La password deve essere lunga almeno 8 caratteri.';
    } elseif ($pass !== $pass2) {
        $error = 'Le due password non coincidono.';
    } else {
        // BCRYPT esplicito, non PASSWORD_DEFAULT: questa impronta la rilegge anche
        // MagixAuth in gioco, e PASSWORD_DEFAULT e' un valore che PHP si riserva di
        // cambiare nelle versioni future. Il giorno in cui cambiasse, il sito comincerebbe
        // a scrivere un formato che il server non sa verificare, e chi cambia la password
        // qui si ritroverebbe chiuso fuori dal gioco.
        $hash = password_hash($pass, PASSWORD_BCRYPT);
        // session_epoch + 1 = tutte le sessioni aperte con la vecchia password decadono
        $upd = db()->prepare('UPDATE users SET password_hash = ?, session_epoch = session_epoch + 1, last_login = NOW() WHERE id = ?');
        $upd->execute([$hash, $pendingUser['id']]);

        $nuovaEpoca = db()->prepare('SELECT session_epoch FROM users WHERE id = ?');
        $nuovaEpoca->execute([$pendingUser['id']]);

        // La password nuova vale anche qui: i "resta collegato" rilasciati prima non devono
        // sopravvivere al cambio (e' l'unico modo per cacciare un browser non piu' tuo).
        remember_forget_all((int) $pendingUser['id']);

        unset($_SESSION['reset_user_id']);
        $pendingUser['session_epoch'] = (int) $nuovaEpoca->fetchColumn();

        // Su un account che comanda il sito la password nuova da sola non basta: anche da
        // qui si ripassa da /otp. Il codice appena speso per arrivare fin qui dimostra chi
        // sei, non ti collega.
        if (otp_serve_per($pendingUser)) {
            otp_metti_in_attesa((int) $pendingUser['id'], !empty($_POST['ricordami']));
            redirect('/otp');
        }

        // ...tranne questa: chi ha appena cambiato la password resta collegato
        accesso_completato($pendingUser, !empty($_POST['ricordami']), false);
        redirect('/');
    }
}

$page_title = 'Imposta password';
require __DIR__ . '/../includes/header.php';
?>
<h1 class="page-title">Imposta una nuova password</h1>

<div class="panel">
  <p>Account: <strong class="testo-verde"><?= h($pendingUser['mc_username']) ?></strong></p>
  <p style="color:var(--text-dim); font-size:14px;">Questa password vale sia sul sito sia in gioco: userai lo stesso nome e la stessa password in tutti e due i posti.</p>
</div>

<?php if ($error): ?><div class="alert alert-error"><?= h($error) ?></div><?php endif; ?>

<div class="panel">
  <form method="post" class="stack">
    <?= csrf_field() ?>
    <div>
      <label for="password">Nuova password</label>
      <input type="password" id="password" name="password" autocomplete="new-password" minlength="8">
    </div>
    <div>
      <label for="password2">Conferma password</label>
      <input type="password" id="password2" name="password2" autocomplete="new-password" minlength="8">
    </div>
    <?php $__ricordami = $_SERVER['REQUEST_METHOD'] !== 'POST' || !empty($_POST['ricordami']); ?>
    <label class="campo-check">
      <input type="checkbox" name="ricordami" value="1"<?= $__ricordami ? ' checked' : '' ?>>
      <span>Resta collegato su questo dispositivo</span>
    </label>
    <button type="submit" class="btn btn-accent">Salva password</button>
  </form>
</div>

<?php require __DIR__ . '/../includes/footer.php'; ?>
