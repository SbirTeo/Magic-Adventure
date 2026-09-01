<?php
/**
 * Password dimenticata.
 *
 * Prende il posto del vecchio giro con /link, che su un server non premium era diventato
 * una falla: bastava entrare in gioco col nome di un altro per generarsi un codice e
 * prendersi il suo account anche qui.
 *
 * Chi ha la verifica in due passaggi si rimette in piedi da solo, dimostrando di essere se
 * stesso con l'app (o con un codice di recupero). Chi non ce l'ha deve passare dallo staff:
 * senza un secondo fattore non esiste niente che possa dimostrare chi sia, e una domanda
 * di sicurezza sarebbe solo un modo piu' lento di farsi rubare l'account.
 */
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/otp.php';

if (is_logged_in()) {
    redirect('/cambia-password');
}

$error = null;

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    csrf_check();
    $username = trim($_POST['username'] ?? '');
    $codice   = trim($_POST['codice'] ?? '');

    if ($username === '' || $codice === '') {
        $error = 'Inserisci il tuo nome e il codice di verifica.';
    } else {
        $stmt = db()->prepare('SELECT * FROM users WHERE mc_username = ?');
        $stmt->execute([$username]);
        $utente = $stmt->fetch();

        // Un solo messaggio per tutti i motivi: nome inesistente, verifica non attiva o
        // codice sbagliato dicono la stessa cosa. Distinguerli farebbe di questa pagina un
        // modo comodo per sapere quali nomi esistono e quali account sono protetti.
        $generico = 'Non e\' stato possibile verificare la richiesta. Se non hai la verifica '
                  . 'in due passaggi attiva, scrivi a un amministratore in gioco.';

        if (!$utente || !otp_enabled($utente)) {
            $error = $generico;
        } elseif (otp_blocco_residuo($utente) > 0) {
            $error = 'Troppi tentativi. Riprova fra qualche minuto.';
        } else {
            $segreto = otp_decifra($utente['totp_secret']);
            $passo = null;
            $ultimo = $utente['totp_last_step'] !== null ? (int) $utente['totp_last_step'] : null;

            if ($segreto && otp_verifica($segreto, $codice, $ultimo, $passo)) {
                // Il passo va bruciato subito: e' cio' che impedisce di riusare lo stesso
                // codice, qui, sul login del sito e in gioco, che leggono la stessa riga.
                db()->prepare('UPDATE users SET totp_last_step = ? WHERE id = ?')
                    ->execute([$passo, (int) $utente['id']]);
                otp_azzera_errori((int) $utente['id']);
                $_SESSION['reset_user_id'] = (int) $utente['id'];
                redirect('/set-password');
            } elseif (otp_usa_recupero((int) $utente['id'], $codice)) {
                otp_azzera_errori((int) $utente['id']);
                $_SESSION['reset_user_id'] = (int) $utente['id'];
                redirect('/set-password');
            } else {
                otp_segna_errore((int) $utente['id']);
                $error = $generico;
            }
        }
    }
}

$page_title = 'Password dimenticata';
require __DIR__ . '/../includes/header.php';
?>
<h1 class="page-title">Password dimenticata</h1>

<div class="panel">
  <p>Se hai attivato la <strong>verifica in due passaggi</strong>, puoi reimpostare la password da solo: inserisci il tuo nome e il codice a sei cifre della tua app di autenticazione.</p>
  <p style="color:var(--text-dim); font-size:14px;">Al posto del codice puoi usare uno dei <strong>codici di recupero</strong> che hai salvato quando l'hai attivata.</p>
</div>

<?php if ($error): ?><div class="alert alert-error"><?= h($error) ?></div><?php endif; ?>

<div class="panel">
  <form method="post" class="stack">
    <?= csrf_field() ?>
    <div>
      <label for="username">Nome del giocatore</label>
      <input type="text" id="username" name="username" maxlength="32" autocomplete="username">
    </div>
    <div>
      <label for="codice">Codice di verifica</label>
      <input type="text" id="codice" name="codice" autocomplete="one-time-code" placeholder="Es. 123456">
    </div>
    <button type="submit" class="btn btn-accent">Continua</button>
  </form>
</div>

<div class="panel">
  <p style="color:var(--text-dim); font-size:14px;">
    <strong>Non hai la verifica in due passaggi?</strong><br>
    Allora non c'e' modo di dimostrare da qui che l'account e' tuo. Chiedi a un amministratore
    in gioco: puo' azzerare la password, e al tuo prossimo ingresso ne sceglierai una nuova.
  </p>
</div>

<p style="margin-top:16px; color:var(--text-dim);">Ti e' tornata in mente? <a href="/login">Accedi qui</a>.</p>

<?php require __DIR__ . '/../includes/footer.php'; ?>
