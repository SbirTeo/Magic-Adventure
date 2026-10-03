<?php
/**
 * Informativa privacy (art. 13 GDPR). Descrive quello che il sito e il server fanno davvero:
 * se cambia un trattamento (un dato nuovo, un servizio esterno, un tempo di conservazione)
 * si cambia anche qui. I dati del titolare vengono dal gestionale (includes/legal.php).
 */
require_once __DIR__ . '/../includes/db.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/legal.php';

$page_title = 'Privacy';
$page_description = 'Come Magic Adventure tratta i dati di chi gioca e di chi usa il sito: quali, perché, per quanto tempo e con quali diritti.';

require __DIR__ . '/../includes/header.php';
legal_header('Informativa sulla privacy', 'privacy');
?>
<article class="panel legale">
  <p>
    Questa pagina spiega quali dati personali trattiamo quando giochi su <strong>mc.magicadventure.it</strong>
    o usi <strong>magicadventure.it</strong>, perché lo facciamo, per quanto tempo li teniamo e quali
    diritti hai. È scritta per essere letta: se qualcosa non è chiaro, scrivici.
  </p>

  <h2>1. Chi è il titolare</h2>
  <p>
    Il titolare del trattamento è <?= legal_value('legal_owner') ?>,
    <?= legal_value('legal_address') ?><?php if (trim(site_setting('legal_vat', '')) !== ''): ?>,
    <?= legal_value('legal_vat') ?><?php endif; ?>.
    Per qualsiasi domanda sui tuoi dati, o per esercitare i tuoi diritti: <?= legal_value('legal_email') ?>.
  </p>

  <h2>2. Quali dati trattiamo</h2>
  <h3>Account di gioco e del sito</h3>
  <p>
    L'account nasce quando entri per la prima volta sul server. Trattiamo il <strong>nome utente
    Minecraft</strong>, il suo <strong>identificativo (UUID)</strong>, la <strong>password</strong>
    (conservata solo in forma cifrata con un algoritmo a senso unico: nessuno, nemmeno lo staff, può
    leggerla), le date di iscrizione e di ultimo accesso, il grado e, se la attivi, la chiave della
    verifica in due passaggi. Non chiediamo né conserviamo email, nome reale, età o numero di telefono.
  </p>
  <h3>Gioco e statistiche</h3>
  <p>
    Quello che succede in partita e serve al gioco: fazione, potenza, territori, uccisioni, tempo di
    gioco, saldo delle valute del server. Una parte è <strong>pubblica</strong> per natura del gioco
    (classifiche, elenco utenti, scheda del giocatore).
  </p>
  <h3>Contenuti che pubblichi</h3>
  <p>
    Messaggi nella chat di gioco e del sito, discussioni e risposte sul forum, ricorsi contro una
    sanzione. Chat e forum sono visibili a tutti; il testo di un ricorso lo leggono solo l'interessato
    e lo staff.
  </p>
  <h3>Sicurezza, anti-cheat e sanzioni</h3>
  <p>
    Per proteggere il server da cheat, account multipli usati per aggirare un ban e attacchi, il
    server registra l'<strong>indirizzo IP</strong> di ogni accesso al gioco, insieme a ora e server
    della rete. Dopo 180 giorni l'indirizzo leggibile viene cancellato e ne resta solo
    un'impronta cifrata (non riconducibile all'IP senza una chiave segreta che resta sul server).
    Le sanzioni (tipo, motivo, durata, chi l'ha decisa) sono pubblicate nella pagina
    <a href="/sanzioni">Sanzioni</a>: è una scelta di trasparenza, perché le regole valgono se si vede
    come vengono applicate. Le prove raccolte le vedono solo l'interessato e lo staff.
  </p>
  <h3>Acquisti</h3>
  <p>
    Se acquisti qualcosa nello <a href="/store">store</a> conserviamo l'ordine: pacchetto, prezzo,
    data, account a cui è stato consegnato e il numero della transazione PayPal. I dati di pagamento
    (carta, conto, indirizzo email PayPal) li tratta <strong>PayPal</strong>: noi non li vediamo.
  </p>
  <h3>Dati tecnici del sito</h3>
  <p>
    Come ogni sito, il server web registra per qualche giorno le richieste ricevute (indirizzo IP,
    pagina, data, browser) per motivi di sicurezza e per risolvere i guasti. Il sito usa solo cookie
    tecnici: li trovi elencati nella pagina <a href="/cookie">Cookie</a>.
  </p>

  <h2>3. Perché li trattiamo (e su quale base)</h2>
  <ul>
    <li><strong>Farti giocare e usare il sito</strong> — account, accesso, gioco, chat, forum,
      consegna degli acquisti: è il servizio che ci chiedi (art. 6.1.b GDPR).</li>
    <li><strong>Sicurezza e rispetto del regolamento</strong> — IP, anti-cheat, sanzioni e loro
      pubblicazione: legittimo interesse nostro e di tutti i giocatori a un server pulito e sicuro
      (art. 6.1.f GDPR).</li>
    <li><strong>Obblighi di legge</strong> — conservazione degli ordini per finalità fiscali e
      contabili, risposte alle autorità (art. 6.1.c GDPR).</li>
  </ul>
  <p>Non usiamo i tuoi dati per pubblicità, non li vendiamo e non facciamo profilazione commerciale.</p>

  <h2>4. Chi li vede</h2>
  <ul>
    <li><strong>Lo staff</strong> del server, solo per quello che serve al suo ruolo (moderazione,
      supporto, sanzioni).</li>
    <li><strong>OVH</strong>, che ospita il server e il sito in data center nell'Unione Europea, come
      fornitore (responsabile del trattamento).</li>
    <li><strong>PayPal</strong>, per i pagamenti, come titolare autonomo con la sua informativa.</li>
    <li><strong>Minotar</strong> (minotar.net): le facce dei giocatori che vedi nel sito sono immagini
      caricate da lì, quindi il tuo browser contatta quel servizio e gli comunica il suo indirizzo IP,
      come per qualsiasi immagine esterna.</li>
  </ul>
  <p>Non trasferiamo dati fuori dall'Unione Europea, salvo quanto fanno PayPal e Minotar per i loro servizi.</p>

  <h2>5. Per quanto tempo</h2>
  <ul>
    <li><strong>Account e statistiche</strong>: finché l'account esiste. Puoi chiederne la cancellazione.</li>
    <li><strong>IP di accesso al gioco</strong>: in chiaro per 180 giorni, poi solo l'impronta cifrata.</li>
    <li><strong>Sanzioni e prove</strong>: finché servono a far rispettare il regolamento (un
      provvedimento conta per le soglie successive, e i suoi punti si dimezzano ogni 90 giorni).</li>
    <li><strong>Ordini</strong>: 10 anni, come richiesto dalla legge per i documenti contabili.</li>
    <li><strong>Registri del server web</strong>: pochi giorni, poi vengono cancellati in automatico.</li>
  </ul>

  <h2>6. I tuoi diritti</h2>
  <p>
    Puoi chiederci in qualsiasi momento di vedere i dati che abbiamo su di te, di correggerli, di
    cancellarli, di limitarne l'uso, di riceverli in un formato leggibile da un computer, e puoi
    opporti ai trattamenti basati sul legittimo interesse (articoli 15-21 del GDPR). Basta scrivere a
    <?= legal_value('legal_email') ?> indicando il tuo nome utente Minecraft: per sicurezza potremmo
    chiederti di confermare la richiesta dal gioco. Rispondiamo entro un mese.
  </p>
  <p>
    Se ritieni che trattiamo i tuoi dati in modo scorretto puoi presentare reclamo al
    <a href="https://www.garanteprivacy.it" target="_blank" rel="noopener">Garante per la protezione dei dati personali</a>.
  </p>

  <h2>7. Minori</h2>
  <p>
    Il server è aperto anche ai più giovani. Se hai meno di 14 anni, per usare il sito ti serve il
    permesso di un genitore; per qualsiasi acquisto serve sempre l'autorizzazione di un genitore o di
    chi ne fa le veci. Un genitore può scriverci in qualsiasi momento per chiedere di vedere o
    cancellare i dati del figlio.
  </p>

  <h2>8. Modifiche</h2>
  <p>
    Se cambiamo qualcosa in questa informativa aggiorniamo la data in cima alla pagina; se il
    cambiamento è importante lo diciamo anche sul sito e in gioco.
  </p>
  <p class="legale-nota">Le versioni in altre lingue sono traduzioni automatiche: in caso di differenze vale il testo italiano.</p>
</article>
<?php require __DIR__ . '/../includes/footer.php'; ?>
