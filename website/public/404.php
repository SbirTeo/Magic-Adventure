<?php
/**
 * Pagina "non trovata", con la grafica del sito.
 *
 * Ci arriva nginx (error_page 404) per ogni indirizzo che non esiste: prima si vedeva la pagina
 * bianca di nginx, con tanto di versione del server. Il codice 404 lo manda questa pagina,
 * anche se la si apre direttamente: per i motori di ricerca deve restare un "non esiste".
 */
require_once __DIR__ . '/../includes/db.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/auth.php';

http_response_code(404);
$page_title = 'Pagina non trovata';
$page_noindex = true;

require __DIR__ . '/../includes/header.php';
?>
<section class="pagina-errore">
  <p class="pagina-errore-codice" aria-hidden="true">404</p>
  <h1 class="pagina-errore-titolo">Questa pagina non esiste</h1>
  <p class="pagina-errore-testo">
    Forse il collegamento è vecchio, o l'indirizzo ha un errore di battitura.
    Da qui puoi tornare dove serve.
  </p>
  <div class="pagina-errore-azioni">
    <a href="/" class="btn btn-accent">Torna alla home</a>
    <a href="/tutorial" class="btn btn-ghost">Guida del server</a>
    <a href="/forum" class="btn btn-ghost">Forum</a>
  </div>
</section>
<?php require __DIR__ . '/../includes/footer.php'; ?>
