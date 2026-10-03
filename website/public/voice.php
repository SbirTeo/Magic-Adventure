<?php
/**
 * /voice — la chat vocale del sito, senza mod: la voce passa dal browser.
 *
 * Si entra con l'account di gioco (stesse credenziali) e si sceglie una stanza: quella di tutta
 * la rete o quella della propria fazione. Le regole (chi entra dove, ban, mute) e il gettone
 * stanno in includes/voice.php; l'audio lo trasporta il server LiveKit del VPS (vedi
 * server-voce/livekit.yaml); qui c'è solo la pagina, il resto lo fa assets/js/voice.js.
 *
 * I testi che legge voice.js stanno nel riquadro nascosto #voiceTexts, così li traduce il sito
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

$page_title = 'Voice';
$page_description = 'La chat vocale di MAGICADVENTURE: parla con la tua fazione e con tutta la rete '
    . 'direttamente dal browser, senza mod.';
$active = 'voice';
require __DIR__ . '/../includes/header.php';
?>
<h1 class="page-title">Voice <span class="voice-sottotitolo">chat vocale</span></h1>

<?php if (!$pronta): ?>
  <div class="alert alert-info">La chat vocale non è ancora attiva. Torna a trovarci tra poco.</div>
<?php elseif ($sanzioni['ban'] !== null): ?>
  <div class="alert alert-error">Hai un ban attivo (<?= h(sanction_expiry($sanzioni['ban'])) ?>): la chat vocale non è disponibile.</div>
<?php else: ?>

<div class="voice" id="voice"
     data-api="/api/voice"
     data-csrf="<?= h(csrf_token()) ?>"
     data-me="<?= h($me['mc_uuid']) ?>">

  <div class="panel voice-intro">
    <p><?= ui_icon('headphones') ?> Parla con gli altri giocatori senza installare niente: <strong>tieni aperta questa
      scheda mentre giochi</strong>. Va bene anche il telefono, usato come auricolare.</p>
    <p class="voice-nota">Consigliate le cuffie: con le casse il browser toglie l'eco da solo, ma le cuffie
      funzionano meglio. Il microfono si chiede solo quando entri in una stanza, e niente viene registrato.</p>
    <?php if ($sanzioni['mute'] !== null): ?>
      <div class="alert alert-info voice-mute">Hai un mute attivo (<?= h(sanction_expiry($sanzioni['mute'])) ?>):
        puoi entrare ad ascoltare, ma non parlare.</div>
    <?php endif; ?>
  </div>

  <div class="voice-stanze">
    <?php foreach ($stanze as $s): ?>
      <div class="voice-stanza" id="<?= h($s['id']) ?>" data-room="<?= h($s['id']) ?>">
        <div class="voice-stanza-testo">
          <strong class="voice-stanza-nome"><?= ui_icon(voice_room_icon($s['id'])) ?>
            <?= h($s['name']) ?></strong>
          <span class="voice-stanza-desc"><?= h($s['desc']) ?></span>
          <?php $n = $presenti[$s['id']] ?? 0; ?>
          <?php if ($n > 0): ?>
            <span class="voice-stanza-conta"><?= $n === 1 ? '1 persona dentro adesso' : $n . ' persone dentro adesso' ?></span>
          <?php endif; ?>
        </div>
        <button type="button" class="btn btn-accent voice-entra" data-room="<?= h($s['id']) ?>">Entra</button>
      </div>
    <?php endforeach; ?>
    <?php if (count($stanze) === 1): ?>
      <p class="voice-nota">Quando entri in una fazione, qui compare anche la sua stanza privata.</p>
    <?php endif; ?>
  </div>

  <section class="panel voice-chiamata" id="voiceChiamata" hidden>
    <header class="voice-chiamata-testa">
      <div>
        <h2 class="voice-chiamata-nome" id="voiceNome"></h2>
        <span class="voice-stato" id="voiceStato" role="status"></span>
      </div>
      <div class="voice-comandi">
        <button type="button" class="btn btn-ghost voice-mic" id="voiceMic" aria-pressed="false" disabled>
          <span class="voice-mic-acceso"><?= ui_icon('mic') ?> <span>Microfono acceso</span></span>
          <span class="voice-mic-spento"><?= ui_icon('mic-off') ?> <span>Microfono spento</span></span>
        </button>
        <button type="button" class="btn btn-danger voice-esci" id="voiceEsci"><?= ui_icon('phone-off') ?> Esci</button>
      </div>
    </header>

    <div class="voice-audio-bloccato" id="voiceSblocca" hidden>
      <span>Il browser ha messo in pausa l'audio.</span>
      <button type="button" class="btn btn-accent btn-small" id="voiceSbloccaBtn">Attiva l'audio</button>
    </div>
    <div class="alert alert-error" id="voiceErrore" hidden></div>
    <div class="alert alert-info" id="voiceAvviso" hidden>Non ti troviamo in gioco in questa modalità: entra
      nel server e le voci di chi ti sta vicino arrivano da sole.</div>

    <label class="voice-dispositivo" id="voiceDispositivoRiga" hidden>
      <span>Microfono</span>
      <select id="voiceDispositivo"></select>
    </label>

    <ul class="voice-persone" id="voicePersone" aria-live="polite"></ul>
  </section>

  <div id="voiceAudio" hidden></div>

  <div id="voiceTexts" hidden>
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
    <span data-k="far">Lontano: lo senti quando ti avvicini</span>
    <span data-k="silenced">Ti è stata tolta la parola (mute): puoi continuare ad ascoltare.</span>
  </div>
  <template id="voiceIconaMicOff"><?= ui_icon('mic-off') ?></template>
</div>

<script defer src="/assets/js/vendor/livekit-client.umd.js?v=2.22.3"></script>
<script defer src="/assets/js/voice.js?v=<?= @filemtime(__DIR__ . '/assets/js/voice.js') ?: time() ?>"></script>
<?php endif; ?>

<?php require __DIR__ . '/../includes/footer.php'; ?>
