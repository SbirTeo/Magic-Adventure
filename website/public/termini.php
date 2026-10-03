<?php
/**
 * Termini di vendita dello store. Valgono per gli acquisti fatti dal sito (store -> PayPal ->
 * consegna in gioco con MagixBridge, coda store_command_queue). I dati del venditore vengono
 * dal gestionale (includes/legal.php).
 *
 * Il punto delicato e' il recesso: per un contenuto digitale consegnato subito il consumatore lo
 * perde SOLO se l'ha accettato espressamente prima di pagare (art. 59, lett. o, Codice del
 * Consumo). Per questo la pagina del pacchetto ha la casella obbligatoria e l'ordine registra
 * quando e' stata spuntata (store_orders.terms_accepted_at).
 */
require_once __DIR__ . '/../includes/db.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/legal.php';

$page_title = 'Termini di vendita';
$page_description = 'Le condizioni degli acquisti nello store di Magic Adventure: cosa si compra, consegna, recesso e rimborsi.';

require __DIR__ . '/../includes/header.php';
legal_header('Termini di vendita', 'termini');
?>
<article class="panel legale">
  <p>
    Queste condizioni valgono per tutto quello che acquisti nello <a href="/store">store</a> di
    Magic Adventure. Acquistando dichiari di averle lette e accettate. Le abbiamo scritte in modo
    semplice: se un punto non è chiaro, chiedici prima di comprare.
  </p>

  <h2>1. Chi vende</h2>
  <p>
    Il venditore è <?= legal_value('legal_owner') ?>, <?= legal_value('legal_address') ?><?php if (trim(site_setting('legal_vat', '')) !== ''): ?>,
    <?= legal_value('legal_vat') ?><?php endif; ?>. Contatto per ordini e reclami: <?= legal_value('legal_email') ?>.
  </p>

  <h2>2. Cosa si compra</h2>
  <p>
    Nello store si acquistano <strong>contenuti digitali</strong> da usare sul server Magic Adventure:
    gradi, kit, oggetti, valute di gioco e vantaggi simili. Ognuno è descritto nella sua pagina, con
    il prezzo e la durata. Sono legati all'<strong>account Minecraft</strong> con cui hai fatto
    l'accesso, valgono solo sul nostro server, non si possono trasferire ad altri account e non si
    possono convertire in denaro.
  </p>
  <p>
    Magic Adventure non è affiliato a Mojang Studios né a Microsoft. Minecraft è un marchio di Mojang AB.
  </p>

  <h2>3. Prezzi e pagamento</h2>
  <p>
    I prezzi sono in euro e sono quelli che paghi: il sito li ricalcola al momento dell'ordine,
    sconti compresi. Il pagamento avviene tramite <strong>PayPal</strong> (conto PayPal o carta);
    il contratto è concluso quando PayPal conferma il pagamento. Noi non vediamo né conserviamo i
    dati della tua carta o del tuo conto.
  </p>

  <h2>4. Consegna</h2>
  <p>
    La consegna è automatica: di norma arriva in gioco entro pochi minuti dal pagamento. Se in quel
    momento il server è spento o in manutenzione, arriva appena torna acceso. Se dopo 24 ore non hai
    ancora ricevuto quello che hai comprato, scrivici indicando il nome utente e il numero della
    transazione PayPal: controlliamo e la completiamo, oppure ti rimborsiamo.
  </p>

  <h2>5. Diritto di recesso</h2>
  <p>
    Per gli acquisti online di solito si hanno 14 giorni per ripensarci. Per i contenuti digitali
    consegnati subito, però, la legge prevede che il diritto di recesso si perda se il consumatore
    chiede espressamente la consegna immediata e dichiara di sapere che così rinuncia al recesso
    (art. 59, comma 1, lettera o del Codice del Consumo).
  </p>
  <p>
    Per questo, prima di pagare, ti chiediamo di spuntare una casella con cui chiedi la consegna
    immediata e accetti di perdere il diritto di recesso. Senza quella casella l'acquisto non parte.
    Una volta consegnato il contenuto, quindi, l'acquisto non è rimborsabile per semplice ripensamento.
  </p>

  <h2>6. Se qualcosa non va</h2>
  <p>
    Restano sempre i tuoi diritti se il contenuto <strong>non arriva</strong> o <strong>non
    funziona</strong> come descritto: in quel caso lo sistemiamo, lo sostituiamo o ti rimborsiamo.
    Scrivici entro un tempo ragionevole da quando te ne accorgi.
  </p>

  <h2>7. Durata dei vantaggi</h2>
  <p>
    La durata è quella indicata nella pagina del pacchetto. «Permanente» significa per tutto il tempo
    in cui il server e la modalità per cui l'hai comprato restano attivi: non è un diritto a vita su
    un servizio che potrebbe cambiare o chiudere. Se un vantaggio viene modificato o tolto per
    ragioni di equilibrio del gioco, cerchiamo di sostituirlo con qualcosa di valore simile.
  </p>

  <h2>8. Regolamento e sanzioni</h2>
  <p>
    Un acquisto non mette nessuno al di sopra del <a href="/tutorial#regolamento">regolamento</a>.
    Se vieni sanzionato (anche con un ban) per averlo violato, i contenuti acquistati non vengono
    rimborsati. Contro una sanzione puoi sempre fare ricorso dalla sua pagina.
  </p>

  <h2>9. Contestazioni del pagamento</h2>
  <p>
    Se apri una contestazione o uno storno su PayPal per un acquisto che hai ricevuto, possiamo
    sospendere i vantaggi collegati finché la contestazione non è chiusa. Prima di aprirne una,
    scrivici: quasi sempre si risolve prima e più in fretta.
  </p>

  <h2>10. Minori</h2>
  <p>
    Se sei minorenne puoi acquistare solo con l'autorizzazione di un genitore o di chi ne fa le veci,
    che è responsabile dell'acquisto.
  </p>

  <h2>11. Reclami, legge e foro</h2>
  <p>
    Per qualsiasi reclamo scrivi a <?= legal_value('legal_email') ?>: rispondiamo entro 14 giorni.
    Questi termini sono regolati dalla legge italiana. Se sei un consumatore, per le controversie è
    competente il giudice del luogo in cui risiedi.
  </p>

  <h2>12. Modifiche</h2>
  <p>
    Possiamo aggiornare questi termini: a ogni acquisto valgono quelli pubblicati in quel momento,
    con la data indicata in cima alla pagina.
  </p>
  <p class="legale-nota">Le versioni in altre lingue sono traduzioni automatiche: in caso di differenze vale il testo italiano.</p>
</article>
<?php require __DIR__ . '/../includes/footer.php'; ?>
