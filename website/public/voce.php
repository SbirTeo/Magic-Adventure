<?php
/**
 * /voce — la chat vocale del sito, senza mod: la voce passa dal browser.
 *
 * Si entra con l'account di gioco (stesse credenziali) e si sceglie una stanza: quella di tutta
 * la rete o quella della propria fazione. Le regole (chi entra dove, ban, mute) e il gettone
 * stanno in includes/voice.php; l'audio lo trasporta il server LiveKit del VPS (vedi
 * server-voce/livekit.yaml); qui c'è solo la pagina, il resto lo fa assets/js/voce.js.
 *
 * I testi che legge voce.js stanno nel riquadro nascosto #voceTesti, così li traduce il sito
 * come il resto della pagina (translate_html legge i nodi di testo, non gli script).
 */
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/voice.php';

require_login();
$me = current_user();

$pronta = voice_ready();
$sanzioni = voice_sanctions((string) $me['mc_uuid']);
$stanze = ($pronta && $sanzioni['ban'] === null) ? voice_rooms($me) : [];
$presenti = $stanze ? voice_room_counts() : [];

$page_title = 'Chat vocale';
$page_description = 'La chat vocale di MAGICADVENTURE: parla con la tua fazione e con tutta la rete '
    . 'direttamente dal browser, senza mod.';
$active = 'voce';
require __DIR__ . '/../includes/header.php';
?>
<h1 class="page-title">Chat vocale</h1>

<?php if (!$pronta): ?>
  <div class="alert alert-info">La chat vocale non è ancora attiva. Torna a trovarci tra poco.</div>
<?php elseif ($sanzioni['ban'] !== null): ?>
  <div class="alert alert-error">Hai un ban attivo (<?= h(sanction_expiry($sanzioni['ban'])) ?>): la chat vocale non è disponibile.</div>
<?php else: ?>

<div class="voce" id="voce"
     data-api="/api/voce"
     data-csrf="<?= h(csrf_token()) ?>"
     data-me="<?= h($me['mc_uuid']) ?>">

  <div class="panel voce-intro">
    <p><?= ui_icon('headphones') ?> Parla con gli altri giocatori senza installare niente: <strong>tieni aperta questa
      scheda mentre giochi</strong>. Va bene anche il telefono, usato come auricolare.</p>
    <p class="voce-nota">Consigliate le cuffie: con le casse il browser toglie l'eco da solo, ma le cuffie
      funzionano meglio. Il microfono si chiede solo quando entri in una stanza, e niente viene registrato.</p>
    <?php if ($sanzioni['mute'] !== null): ?>
      <div class="alert alert-info voce-mute">Hai un mute attivo (<?= h(sanction_expiry($sanzioni['mute'])) ?>):
        puoi entrare ad ascoltare, ma non parlare.</div>
    <?php endif; ?>
  </div>

  <div class="voce-stanze">
    <?php foreach ($stanze as $s): ?>
      <div class="voce-stanza" id="<?= h($s['id']) ?>" data-room="<?= h($s['id']) ?>">
        <div class="voce-stanza-testo">
          <strong class="voce-stanza-nome"><?= ui_icon(str_starts_with($s['id'], 'fazione-') ? 'shield' : 'users') ?>
            <?= h($s['name']) ?></strong>
          <span class="voce-stanza-desc"><?= h($s['desc']) ?></span>
          <?php $n = $presenti[$s['id']] ?? 0; ?>
          <?php if ($n > 0): ?>
            <span class="voce-stanza-conta"><?= $n === 1 ? '1 persona dentro adesso' : $n . ' persone dentro adesso' ?></span>
          <?php endif; ?>
        </div>
        <button type="button" class="btn btn-accent voce-entra" data-room="<?= h($s['id']) ?>">Entra</button>
      </div>
    <?php endforeach; ?>
    <?php if (count($stanze) === 1): ?>
      <p class="voce-nota">Quando entri in una fazione, qui compare anche la sua stanza privata.</p>
    <?php endif; ?>
  </div>

  <section class="panel voce-chiamata" id="voceChiamata" hidden>
    <header class="voce-chiamata-testa">
      <div>
        <h2 class="voce-chiamata-nome" id="voceNome"></h2>
        <span class="voce-stato" id="voceStato" role="status"></span>
      </div>
      <div class="voce-comandi">
        <button type="button" class="btn btn-ghost voce-mic" id="voceMic" aria-pressed="false" disabled>
          <span class="voce-mic-acceso"><?= ui_icon('mic') ?> <span>Microfono acceso</span></span>
          <span class="voce-mic-spento"><?= ui_icon('mic-off') ?> <span>Microfono spento</span></span>
        </button>
        <button type="button" class="btn btn-danger voce-esci" id="voceEsci"><?= ui_icon('phone-off') ?> Esci</button>
      </div>
    </header>

    <div class="voce-audio-bloccato" id="voceSblocca" hidden>
      <span>Il browser ha messo in pausa l'audio.</span>
      <button type="button" class="btn btn-accent btn-small" id="voceSbloccaBtn">Attiva l'audio</button>
    </div>
    <div class="alert alert-error" id="voceErrore" hidden></div>

    <label class="voce-dispositivo" id="voceDispositivoRiga" hidden>
      <span>Microfono</span>
      <select id="voceDispositivo"></select>
    </label>

    <ul class="voce-persone" id="vocePersone" aria-live="polite"></ul>
  </section>

  <div id="voceAudio" hidden></div>

  <div id="voceTesti" hidden>
    <span data-k="connecting">Collegamento…</span>
    <span data-k="connected">Collegato</span>
    <span data-k="reconnecting">Connessione persa, riprovo…</span>
    <span data-k="disconnected">Sei uscito dalla stanza.</span>
    <span data-k="duplicate">Sei entrato in questa stanza da un'altra scheda o da un altro dispositivo.</span>
    <span data-k="removed">Sei stato tolto dalla stanza.</span>
    <span data-k="closed">La stanza è stata chiusa.</span>
    <span data-k="failed">Collegamento non riuscito. Controlla la connessione e riprova.</span>
    <span data-k="mic_denied">Il browser non ha dato il permesso per il microfono: puoi ascoltare, ma per parlare consentilo dalle impostazioni del sito (icona del lucchetto accanto all'indirizzo).</span>
    <span data-k="mic_missing">Nessun microfono trovato: puoi ascoltare, ma per parlare collegane uno.</span>
    <span data-k="mic_error">Il microfono non si è acceso. Riprova, o scegline un altro.</span>
    <span data-k="listen_only">Solo ascolto</span>
    <span data-k="you">tu</span>
    <span data-k="alone">Per ora ci sei solo tu. Gli altri ti sentiranno appena entrano.</span>
    <span data-k="volume">Volume</span>
    <span data-k="speaking">sta parlando</span>
    <span data-k="muted">microfono spento</span>
    <span data-k="error">Qualcosa non ha funzionato. Riprova tra poco.</span>
    <span data-k="people_one">1 persona</span>
    <span data-k="people_many">{n} persone</span>
    <span data-k="mic_n">Microfono {n}</span>
  </div>
  <template id="voceIconaMicOff"><?= ui_icon('mic-off') ?></template>
</div>

<script defer src="/assets/js/vendor/livekit-client.umd.js?v=2.22.3"></script>
<script defer src="/assets/js/voce.js?v=<?= @filemtime(__DIR__ . '/assets/js/voce.js') ?: time() ?>"></script>
<?php endif; ?>

<?php require __DIR__ . '/../includes/footer.php'; ?>
