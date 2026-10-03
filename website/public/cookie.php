<?php
/**
 * Informativa sui cookie. Il sito usa SOLO cookie tecnici (niente statistiche di terzi, niente
 * pubblicita'), quindi non serve il banner di consenso: basta dire quali sono. Se si aggiunge un
 * cookie o un servizio che ne mette (es. un'analitica), va scritto qui E va valutato il banner.
 *
 * Elenco allineato al codice: PHPSESSID (includes/auth.php, sessione), ma_resta_collegato
 * (auth.php, REMEMBER_COOKIE), ma_lingua (includes/language.php), tema (assets/js/site.js),
 * e nel localStorage tema e chatServer (site.js, chat.js).
 */
require_once __DIR__ . '/../includes/db.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/legal.php';

$page_title = 'Cookie';
$page_description = 'I cookie di Magic Adventure: solo tecnici, nessuna pubblicità e nessun tracciamento.';

require __DIR__ . '/../includes/header.php';
legal_header('Cookie', 'cookie');
?>
<article class="panel legale">
  <p>
    Il sito usa <strong>solo cookie tecnici</strong>: servono a farlo funzionare (restare collegati,
    ricordare lingua e tema). Non usiamo cookie di profilazione o di pubblicità, né strumenti di
    statistica di terze parti. Per questo non ti chiediamo un consenso: per i cookie tecnici la legge
    non lo prevede, chiede solo di dirti quali sono.
  </p>

  <h2>Cookie del sito</h2>
  <div class="legale-tabella">
    <table>
      <thead>
        <tr><th>Nome</th><th>A cosa serve</th><th>Quanto dura</th></tr>
      </thead>
      <tbody>
        <tr><td><code>PHPSESSID</code></td><td>Tiene aperta la sessione mentre navighi: senza, a ogni pagina usciresti dall'account.</td><td>Fino alla chiusura del browser</td></tr>
        <tr><td><code>ma_resta_collegato</code></td><td>Solo se spunti «Resta collegato» all'accesso: ti riconosce quando torni, senza rifare il login.</td><td>Fino a 1 anno, o finché esci</td></tr>
        <tr><td><code>ma_lingua</code></td><td>Ricorda la lingua che hai scelto dal selettore in alto.</td><td>1 anno</td></tr>
        <tr><td><code>tema</code></td><td>Ricorda se preferisci il tema scuro, chiaro o automatico.</td><td>Circa 13 mesi</td></tr>
      </tbody>
    </table>
  </div>
  <p>
    Nella memoria del browser (localStorage) il sito salva anche il <strong>tema</strong> scelto e la
    <strong>scheda della chat</strong> che avevi aperto. Restano solo sul tuo dispositivo e non
    vengono mandati a nessuno.
  </p>

  <h2>Servizi esterni</h2>
  <ul>
    <li><strong>Minotar</strong> (minotar.net): le facce dei giocatori sono immagini caricate da lì.
      Il tuo browser le scarica direttamente, quindi quel servizio vede il tuo indirizzo IP, come per
      qualsiasi immagine esterna.</li>
    <li><strong>PayPal</strong>: solo se fai un acquisto, quando vai sulla pagina di pagamento. I cookie
      di quella pagina sono di PayPal e seguono la <a href="https://www.paypal.com/it/legalhub/privacy-full" target="_blank" rel="noopener">sua informativa</a>.</li>
    <li><strong>minecraft-italia.net</strong>: solo se clicchi «Vota», che ti porta sul loro sito.</li>
  </ul>

  <h2>Come cancellarli</h2>
  <p>
    Puoi cancellare i cookie in qualsiasi momento dalle impostazioni del browser. Il sito continua a
    funzionare: dovrai solo rifare l'accesso e scegliere di nuovo lingua e tema.
  </p>
  <p>
    Per qualsiasi domanda: <?= legal_value('legal_email') ?>. Per sapere come trattiamo i tuoi dati
    leggi l'<a href="/privacy">informativa sulla privacy</a>.
  </p>
  <p class="legale-nota">Le versioni in altre lingue sono traduzioni automatiche: in caso di differenze vale il testo italiano.</p>
</article>
<?php require __DIR__ . '/../includes/footer.php'; ?>
