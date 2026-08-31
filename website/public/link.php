<?php
/**
 * Il vecchio "collega account Minecraft".
 *
 * Non collega piu' niente, e non e' una semplificazione: il giro che c'era prima — un codice
 * generato in gioco con /link, da incollare qui per impostare la password — su un server non
 * premium sarebbe diventato una falla. Chiunque avesse scritto il nome di un altro nel
 * launcher avrebbe potuto generarsi il codice e prendersi il suo account sul sito, store e
 * pannello di amministrazione compresi.
 *
 * Adesso l'account nasce in gioco al primo ingresso, e le stesse credenziali aprono il sito.
 * La pagina resta in piedi solo per non lasciare un errore a chi arriva da un vecchio link.
 */
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/helpers.php';

if (is_logged_in()) {
    redirect('/');
}

$page_title = 'Accedi con Minecraft';
require __DIR__ . '/../includes/header.php';
?>
<h1 class="page-title">Non serve piu' collegare nulla</h1>

<div class="panel">
  <p>Il tuo account del sito e quello di gioco sono <strong>lo stesso account</strong>. Non c'e' piu' nessun codice da copiare.</p>
  <ol>
    <li>Entra su <strong>mc.magicadventure.it</strong></li>
    <li>Al primo ingresso scegli una password sul cartello che ti compare davanti</li>
    <li>Da quel momento accedi qui con lo <strong>stesso nome</strong> e la <strong>stessa password</strong></li>
  </ol>
</div>

<div class="panel">
  <a href="/login" class="btn btn-accent">Vai all'accesso</a>
  <a href="/password-dimenticata" class="btn btn-ghost btn-small">Ho dimenticato la password</a>
</div>

<?php require __DIR__ . '/../includes/footer.php'; ?>
