<?php
/**
 * Lo store: si comprano i Magix, la valuta di rete del server.
 *
 * Una pagina sola: il giocatore sceglie quanti Magix vuole spostando il cursore (da MAGIX_MIN
 * a MAGIX_MAX, sconto crescente con la quantita'), paga con PayPal e i Magix arrivano nel suo
 * saldo di gioco, oppure li REGALA a un altro giocatore scrivendone il nome. I pacchetti VIP si
 * comprano poi DENTRO il gioco, spendendo i Magix.
 * Prezzi e sconti: includes/magix.php. Il copione della pagina: assets/js/store-magix.js.
 *
 * Nella colonna di destra il portafoglio del giocatore (saldo e ultime ricariche), che la
 * pagina tiene aggiornato da sola leggendo /api/magix; sotto, se accesi dal gestionale, i
 * riquadri della colonna del sito (chat, scheda giocatore...).
 */
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/paypal.php';
require_once __DIR__ . '/../includes/magix.php';
require_once __DIR__ . '/../includes/sidebar.php';

$page_title = 'Store';
$page_description = 'Ricarica i Magix, la valuta di MAGICADVENTURE: più ne prendi, più risparmi. '
    . 'Pagamento sicuro con PayPal e Magix subito nel tuo portafoglio, da spendere in gioco per i pacchetti VIP.';
$active = 'store';
require_once __DIR__ . '/../includes/seo.php';
$page_jsonld = seo_briciole(['Home' => '/', 'Store' => '/store']);

$me = current_user();
$conGiocatore = $me && trim((string) $me['mc_uuid']) !== '';
$pronto = paypal_ready();

// Saldo e ricariche: se il database non risponde la pagina si apre lo stesso, col portafoglio
// in attesa (lo riprova il copione).
$saldo = null;
$ricariche = [];
$ricarica = null;
if ($conGiocatore) {
    try {
        $saldo = magix_balance((string) $me['mc_uuid']);
        $ricariche = magix_recent_orders((int) $me['id'], (string) $me['mc_uuid']);
        // Rientro da PayPal (store/return.php): l'ordine appena pagato, per festeggiarlo.
        if (isset($_GET['ricarica'])) {
            $q = db()->prepare('SELECT * FROM magix_orders WHERE id = ? AND user_id = ?');
            $q->execute([(int) $_GET['ricarica'], (int) $me['id']]);
            $ricarica = $q->fetch() ?: null;
        }
    } catch (Throwable $e) {
        error_log('Store Magix: ' . $e->getMessage());
    }
}
$esito = (string) ($_GET['esito'] ?? '');
$festa = $ricarica && $ricarica['status'] === 'paid' && $esito === 'ok';
// Al ritorno da un regalo i Magix non sono nel MIO portafoglio: niente gemme che volano.
$regaloFatto = $festa && trim((string) ($ricarica['recipient_uuid'] ?? '')) !== '';

// Quantita' con cui si apre il cursore: quella dell'ultimo tentativo (errore, annullato),
// altrimenti il minimo, col sacco vuoto che si riempie man mano.
$iniziale = max(MAGIX_MIN, min(MAGIX_MAX, (int) ($_GET['q'] ?? ($ricarica['amount'] ?? MAGIX_MIN))));
$preventivo = magix_quote($iniziale);

// Cosa si compra in gioco con i Magix (Gestione -> Store): la sezione sotto al cursore.
try {
    $catalogo = magix_catalog();
} catch (Throwable $e) {
    $catalogo = [];
}

// Regalo gia' impostato (rientro da un errore o da PayPal annullato): si riapre com'era.
$perRegalo = isset($_GET['per']) ? mb_substr(trim((string) $_GET['per']), 0, 16) : '';

$sidebarSito = pagina_con_sidebar('/store');
$gemma = '/assets/img/magix.svg';

require __DIR__ . '/../includes/header.php';
?>
<link rel="stylesheet" href="/assets/css/store-magix.css?v=<?= @filemtime(__DIR__ . '/assets/css/store-magix.css') ?: time() ?>">

<h1 class="page-title magix-titolo">Ricarica i tuoi <span class="magix-testo">Magix</span></h1>
<p class="magix-intro">
  Scegli quanti Magix vuoi trascinando il cursore: più ne prendi, più risparmi.
  Poi in gioco li spendi per i pacchetti VIP.
</p>

<?php if (is_admin() && !$pronto):
  // Solo l'admin vede perche' il pulsante e' spento: agli altri basta "Prossimamente".
  $mancano = [];
  if (site_setting('paypal_enabled', '0') !== '1')      $mancano[] = 'la spunta <strong>PayPal attivo</strong>';
  if (trim(site_setting('paypal_client_id', '')) === '') $mancano[] = 'il <strong>Client ID</strong>';
  if (trim(site_setting('paypal_secret', '')) === '')    $mancano[] = 'il <strong>Secret</strong>';
?>
  <div class="alert alert-info">
    <strong>Gli acquisti sono spenti:</strong> manca <?= implode(', ', $mancano) ?>.
    Si completa in <a href="/manage?section=store">Gestione → Store</a>; con l'ambiente su
    <em>Sandbox</em> puoi provare tutto il giro d'acquisto senza soldi veri.
  </div>
<?php endif; ?>

<?php
  $errori = [
      'paypal' => 'Il pagamento non è partito: riprova tra poco. Se il problema resta, avvisa lo staff.',
      'termini' => 'Per acquistare devi spuntare la casella dei termini di vendita, accanto al pulsante.',
      'annullato' => 'Hai annullato il pagamento su PayPal: non ti è stato addebitato nulla.',
      'indisponibile' => 'Al momento non è possibile acquistare: riprova tra poco.',
      'destinatario' => 'Non abbiamo trovato il giocatore a cui vuoi regalare i Magix: controlla il nome, quello che usa in gioco.',
  ];
  $err = (string) ($_GET['err'] ?? '');
?>
<?php if (isset($errori[$err])): ?>
  <div class="alert <?= $err === 'annullato' ? 'alert-info' : 'alert-error' ?>"><?= h($errori[$err]) ?></div>
<?php endif; ?>
<?php if ($ricarica && $esito === 'accredito'): ?>
  <div class="alert alert-error">
    Il pagamento è arrivato, ma i Magix non sono ancora nel portafoglio.
    <a href="/store/return?order=<?= (int) $ricarica['id'] ?>">Riprova l'accredito</a>; se il problema resta
    scrivi allo staff indicando la ricarica n. <?= (int) $ricarica['id'] ?>.
  </div>
<?php elseif (isset($_GET['ricarica']) && $esito === 'errore'): ?>
  <div class="alert alert-error">
    Non è stato possibile completare il pagamento. Se l'importo ti è stato addebitato,
    scrivi allo staff indicando l'orario: nessuna ricarica viene accreditata senza conferma di PayPal.
  </div>
<?php endif; ?>

<div class="content-with-sidebar magix-layout">
  <div class="content-main">

    <section class="panel magix-scelta" id="magixScelta"
             data-config="<?= h(json_encode(magix_config_js())) ?>"
             data-initial="<?= $iniziale ?>">
      <div class="magix-vetrina">
        <div class="magix-sacco-box" aria-hidden="true">
          <div class="magix-raggi"></div>
          <div class="magix-alone" id="magixAlone"></div>
          <div class="magix-sacco" id="magixSacco">
            <img class="magix-sacco-vuoto" src="/assets/img/magix-sacco.png" alt="" width="256" height="256">
            <img class="magix-sacco-pieno" src="/assets/img/magix-sacco.png" alt="" width="256" height="256">
            <div class="magix-sacco-linea" id="magixLinea"></div>
          </div>
        </div>
        <div class="magix-quanti-box">
          <div class="magix-etichetta">Stai acquistando</div>
          <div class="magix-quanti">
            <span class="magix-num" id="magixNum"><?= $iniziale ?></span>
            <span class="magix-unita">Magix</span>
            <span class="magix-pill<?= $preventivo['pct'] ? '' : ' spento' ?>" id="magixPill">-<?= $preventivo['pct'] ?>%</span>
          </div>
          <div class="magix-unitario">
            Prezzo base <b><?= h(number_format(MAGIX_PRICE, 2, ',', '.')) ?> €</b> per Magix ·
            ora ti costa <b id="magixUnit"><?= h(number_format($preventivo['total'] / $iniziale, 3, ',', '.')) ?> €</b>
          </div>
        </div>
      </div>

      <div class="magix-cursore-zona">
        <div class="magix-binario" id="magixBinario">
          <div class="magix-riempito" id="magixRiempito"></div>
          <div class="magix-maniglia" id="magixManiglia" role="slider" tabindex="0" aria-label="Quantità di Magix"
               aria-valuemin="<?= MAGIX_MIN ?>" aria-valuemax="<?= MAGIX_MAX ?>" aria-valuenow="<?= $iniziale ?>">
            <span class="magix-suggerimento">Trascina →</span>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <g class="magix-freccia"><path d="M5 12h13"/><path d="M13 6l6 6-6 6"/></g>
            </svg>
          </div>
        </div>
        <div class="magix-etichette" id="magixEtichette"></div>
      </div>

      <div class="magix-passi">
        <button type="button" class="magix-tondo" id="magixMeno" aria-label="Un Magix in meno">−</button>
        <button type="button" class="magix-tondo" id="magixPiu" aria-label="Un Magix in più">+</button>
      </div>

      <?php if ($pronto && $conGiocatore): ?>
        <?php /* Per chi sono i Magix: per se' o in regalo. I campi stanno fuori dal modulo di
                 pagamento ma ne fanno parte (attributo form). Il nome si controlla mentre lo si
                 scrive (/api/magix?player=) e di nuovo nella cassa. */ ?>
        <div class="magix-per-chi" id="magixPerChi">
          <div class="magix-etichetta">Per chi sono</div>
          <div class="magix-scelte-chi" role="radiogroup" aria-label="Per chi sono i Magix">
            <label class="magix-chip">
              <input type="radio" name="for" value="me" form="magixCompra"<?= $perRegalo === '' ? ' checked' : '' ?>>
              <img src="<?= h(mc_avatar_url($me['mc_uuid'], 32, $me['premium_uuid'] ?? null)) ?>" alt="" width="22" height="22">
              <span>Per me</span>
            </label>
            <label class="magix-chip">
              <input type="radio" name="for" value="gift" form="magixCompra"<?= $perRegalo !== '' ? ' checked' : '' ?>>
              <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="8" width="18" height="4" rx="1"/><path d="M12 8v13M19 12v7a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-7"/><path d="M7.5 8a2.5 2.5 0 0 1 0-5C11 3 12 8 12 8s1-5 4.5-5a2.5 2.5 0 0 1 0 5"/></svg>
              <span>Regala a un altro giocatore</span>
            </label>
          </div>
          <div class="magix-regalo" id="magixRegalo"<?= $perRegalo === '' ? ' hidden' : '' ?>>
            <div class="magix-regalo-campo">
              <img class="magix-regalo-avatar" id="magixRegaloAvatar" src="/assets/img/magix.svg" alt="" width="40" height="40">
              <input type="text" name="recipient" id="magixDestinatario" form="magixCompra" maxlength="16" value="<?= h($perRegalo) ?>"
                     placeholder="Nome del giocatore, quello che usa in gioco" autocomplete="off" spellcheck="false"
                     aria-describedby="magixRegaloEsito">
            </div>
            <p class="magix-regalo-esito" id="magixRegaloEsito" aria-live="polite">I Magix arriveranno nel suo portafoglio, su tutta la rete.</p>
          </div>
        </div>
      <?php endif; ?>

      <div class="magix-riepilogo">
        <div class="magix-righe">
          <div class="magix-barrato" id="magixBarrato"><span>Prezzo pieno</span><b id="magixPieno"><?= h(magix_euro($preventivo['full'])) ?></b></div>
          <div><span>Sconto quantità</span><b id="magixSconto"><?= $preventivo['pct'] ? $preventivo['pct'] . '%' : '—' ?></b></div>
          <div class="magix-risparmio"><span>Risparmi</span><b id="magixRisparmio"><?= h(magix_euro($preventivo['full'] - $preventivo['total'])) ?></b></div>
          <p class="magix-prossimo" id="magixProssimo"></p>
        </div>
        <div class="magix-cassa">
          <div class="magix-totale-lbl">Totale</div>
          <div class="magix-totale" id="magixTotale"><?= h(magix_euro($preventivo['total'])) ?></div>
          <?php if (!$pronto): ?>
            <button type="button" class="btn btn-gold magix-paga" disabled>Prossimamente</button>
          <?php elseif (!$me): ?>
            <a href="/login" class="btn btn-gold magix-paga">Accedi per acquistare</a>
          <?php elseif (!$conGiocatore): ?>
            <a href="/profilo" class="btn btn-gold magix-paga">Collega il tuo account di gioco</a>
          <?php else: ?>
            <?php /* La casella e' obbligatoria per legge: per un contenuto digitale consegnato subito il
                     recesso si perde solo con una richiesta ESPRESSA fatta prima di pagare (art. 59,
                     lett. o, Codice del Consumo). store/checkout.php la ricontrolla e la registra. */ ?>
            <form method="post" action="/store/checkout" class="magix-compra" id="magixCompra">
              <?= csrf_field() ?>
              <input type="hidden" name="amount" id="magixAmount" value="<?= $iniziale ?>">
              <label class="magix-termini">
                <input type="checkbox" name="terms" value="1" required>
                <span>Accetto i <a href="/termini" target="_blank">termini di vendita</a> e chiedo la consegna
                immediata: so che, una volta consegnati i Magix, perdo il diritto di recesso.</span>
              </label>
              <button type="submit" class="btn btn-gold magix-paga">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7 21H3.6l2.7-17h6.4c3.6 0 5.6 1.9 5 5.3-.6 3.6-3.2 5.4-6.6 5.4H9l-1 6.3zM9.5 12h1.6c1.9 0 3.1-.8 3.4-2.6.3-1.6-.6-2.4-2.3-2.4H10.4z"/></svg>
                <span id="magixPagaTesto">Paga con PayPal</span>
              </button>
            </form>
          <?php endif; ?>
          <p class="magix-sicuro">Pagamento sicuro con PayPal · i Magix arrivano subito nel portafoglio</p>
        </div>
      </div>
    </section>

    <?php if ($catalogo || is_admin()): ?>
      <?php /* Cosa si compra con la quantita' scelta: si aggiorna mentre si sposta il cursore
               (store-magix.js). Toccare una voce che non basta porta il cursore al suo costo. */ ?>
      <section class="panel magix-catalogo-box">
        <h2>Cosa puoi comprare con <span class="magix-catalogo-n" id="magixCatalogoN"><?= number_format($iniziale, 0, ',', '.') ?></span> Magix</h2>
        <?php if ($catalogo): ?>
          <p class="magix-catalogo-sub">I pacchetti si comprano in gioco, spendendo i Magix. Tocca un pacchetto per scegliere esattamente quello che costa.</p>
          <div class="magix-catalogo" id="magixCatalogo">
            <?php foreach ($catalogo as $voce):
              $costo = (int) $voce['cost'];
              $colore = is_valid_hex_color((string) $voce['color']) ? (string) $voce['color'] : '#c04ff0';
              $basta = $costo <= $iniziale;
            ?>
              <button type="button" class="magix-voce<?= $basta ? ' basta' : '' ?>" data-cost="<?= $costo ?>">
                <span class="magix-voce-testa">
                  <span class="magix-voce-tag" style="background:<?= h($colore) ?>;color:<?= h(text_on_color($colore)) ?>"><?= h($voce['name']) ?></span>
                  <span class="magix-voce-costo"><img src="<?= $gemma ?>" alt="" width="16" height="16"><?= number_format($costo, 0, ',', '.') ?></span>
                </span>
                <?php if (trim((string) $voce['note']) !== ''): ?>
                  <span class="magix-voce-nota"><?= h($voce['note']) ?></span>
                <?php endif; ?>
                <span class="magix-voce-barra"><i style="width:<?= min(100, round($iniziale / max(1, $costo) * 100)) ?>%"></i></span>
                <span class="magix-voce-stato"><?= $basta ? '✓ Ti bastano' : 'Ti mancano ' . number_format($costo - $iniziale, 0, ',', '.') . ' Magix' ?></span>
              </button>
            <?php endforeach; ?>
          </div>
        <?php else: ?>
          <p class="magix-catalogo-sub">
            <strong>Solo lo staff vede questo riquadro:</strong> l'elenco dei pacchetti è vuoto. Si compila in
            <a href="/manage?section=store#catalogo">Gestione → Store → Cosa si compra con i Magix</a>.
          </p>
        <?php endif; ?>
      </section>
    <?php endif; ?>

    <section class="panel magix-livelli-box">
      <h2>Livelli di sconto</h2>
      <div class="magix-livelli" id="magixLivelli">
        <?php foreach (MAGIX_TIERS as $i => $livello): ?>
          <button type="button" class="magix-livello" data-i="<?= $i ?>" data-from="<?= $livello['from'] ?>">
            <span class="magix-gemme"><?= str_repeat('<img src="' . $gemma . '" alt="" width="22" height="22">', $i + 1) ?></span>
            <span class="magix-da"><?= $livello['from'] ?></span>
            <span class="magix-pc"><?= $livello['pct'] ? '-' . $livello['pct'] . '%' : 'prezzo pieno' ?></span>
          </button>
        <?php endforeach; ?>
      </div>
    </section>

    <p class="store-nota">
      I pagamenti sono gestiti da PayPal: il sito non vede né conserva i dati della tua carta.
      I Magix finiscono sull'account Minecraft con cui hai fatto l'accesso e valgono su tutta la rete.
    </p>
  </div>

  <aside class="side-col">
    <section class="magix-portafoglio" id="magixPortafoglio" data-live="<?= $conGiocatore ? '1' : '0' ?>">
      <?php if ($conGiocatore): ?>
        <div class="magix-chi">
          <?= avatar_top('<img class="magix-avatar" src="' . h(mc_avatar_url($me['mc_uuid'], 64, $me['premium_uuid'] ?? null)) . '" alt="" width="44" height="44">', $me['mc_uuid'], 44) ?>
          <div class="magix-chi-dati">
            <div class="magix-chi-nome"><?= player_name($me, (string) ($me['nome_gioco'] ?: $me['mc_username'])) ?></div>
            <div class="magix-vivo"><i></i> Portafoglio aggiornato in tempo reale</div>
          </div>
        </div>

        <div class="magix-saldo-box" id="magixSaldoBox">
          <div class="magix-etichetta">Il tuo saldo</div>
          <div class="magix-saldo">
            <img src="<?= $gemma ?>" alt="" width="54" height="54">
            <span class="magix-saldo-v"><span id="magixSaldo"><?= $saldo === null ? '—' : number_format($saldo, 0, ',', '.') ?></span><small>Magix</small></span>
          </div>
          <div class="magix-saldo-nota">Uguale su tutta la rete: lo stesso saldo in ogni modalità</div>
          <div class="magix-piu-arrivo" id="magixPiuArrivo"></div>
        </div>

        <div class="magix-sez">
          <h3>Spendili in gioco</h3>
          <p class="magix-spendi">
            I pacchetti VIP si comprano dentro il server, con i Magix. Il saldo lo vedi anche in gioco con
            <code>/magix</code>.
          </p>
        </div>

        <div class="magix-sez">
          <h3>I tuoi movimenti</h3>
          <ul class="magix-movimenti" id="magixMovimenti">
            <?php foreach ($ricariche as $r): ?>
              <li class="is-<?= h($r['kind']) ?>">
                <span class="magix-mov-ico"><?= $r['kind'] === 'sent' ? '&#8599;' : '+' ?></span>
                <span class="magix-mov-cosa"><b><?= h($r['label']) ?></b><span><?= h($r['meta']) ?></span></span>
                <span class="magix-mov-q"><?= $r['kind'] === 'sent' ? '' : '+' ?><?= number_format($r['amount'], 0, ',', '.') ?></span>
              </li>
            <?php endforeach; ?>
          </ul>
          <p class="magix-vuoto" id="magixNessuna"<?= $ricariche ? ' hidden' : '' ?>>Ancora nessun movimento.</p>
        </div>
      <?php else: ?>
        <div class="magix-saldo-box is-ospite">
          <div class="magix-saldo">
            <img src="<?= $gemma ?>" alt="" width="54" height="54">
            <span class="magix-saldo-v">Il tuo portafoglio</span>
          </div>
          <div class="magix-saldo-nota">
            <?= $me ? 'Collega il tuo account di gioco per vedere i tuoi Magix.' : 'Accedi con il tuo account Minecraft per vedere i tuoi Magix.' ?>
          </div>
        </div>
        <a href="<?= $me ? '/profilo' : '/login' ?>" class="btn btn-ghost magix-accedi"><?= $me ? 'Vai al profilo' : 'Accedi' ?></a>
      <?php endif; ?>
    </section>

    <?php /* I riquadri della colonna del sito, sotto al portafoglio e nella stessa colonna:
             si accendono dalla voce di menu Store in "Pagine e menu". */ ?>
    <?php if ($sidebarSito) { sidebar_colonna(false); } ?>
  </aside>
</div>

<?php if ($festa): ?>
  <div class="magix-velo on" id="magixVelo" role="dialog" aria-modal="true" aria-labelledby="magixFestaTit"
       data-amount="<?= $regaloFatto ? 0 : (int) $ricarica['amount'] ?>">
    <div class="magix-modale">
      <img src="/assets/img/magix-sacco.png" alt="" width="120" height="120">
      <?php if ($regaloFatto): ?>
        <h3 id="magixFestaTit">Regalo inviato!</h3>
        <p><b><?= number_format((int) $ricarica['amount'], 0, ',', '.') ?> Magix</b> sono nel portafoglio di
           <b><?= h($ricarica['recipient_name']) ?></b>.<br>Li trova già su tutta la rete.</p>
      <?php else: ?>
        <h3 id="magixFestaTit">Ricarica completata!</h3>
        <p><b><?= number_format((int) $ricarica['amount'], 0, ',', '.') ?> Magix</b> sono nel tuo portafoglio.<br>
           Entra in gioco e spendili per i pacchetti VIP.</p>
      <?php endif; ?>
      <button type="button" class="btn btn-gold" id="magixChiudi">Fantastico</button>
    </div>
  </div>
<?php endif; ?>

<script src="/assets/js/store-magix.js?v=<?= @filemtime(__DIR__ . '/assets/js/store-magix.js') ?: time() ?>"></script>
<?php require __DIR__ . '/../includes/footer.php'; ?>
