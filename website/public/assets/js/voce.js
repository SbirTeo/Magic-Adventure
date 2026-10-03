/*
 * Chat vocale del sito (/voce). La pagina la disegna voce.php; qui:
 *  - "Entra" chiede al sito il gettone di quella stanza (api/voce.php) e si collega al server
 *    della voce (LiveKit, attraverso nginx su /voce-rtc);
 *  - accende il microfono (se il giocatore può parlare) e fa sentire gli altri;
 *  - tiene l'elenco di chi c'è, chi sta parlando, chi ha il microfono spento, con un volume
 *    per persona (ricordato in questo browser).
 *
 * I testi stanno nella pagina (#voceTesti), così li traduce il sito come tutto il resto.
 * Libreria: assets/js/vendor/livekit-client.umd.js (copia locale, globale LivekitClient).
 */
(function () {
  'use strict';

  var box = document.getElementById('voce');
  if (!box || !window.LivekitClient) {
    return;
  }
  var LK = window.LivekitClient;
  var api = box.getAttribute('data-api');
  var csrf = box.getAttribute('data-csrf') || '';

  var el = {
    chiamata: document.getElementById('voceChiamata'),
    nome: document.getElementById('voceNome'),
    stato: document.getElementById('voceStato'),
    mic: document.getElementById('voceMic'),
    esci: document.getElementById('voceEsci'),
    sblocca: document.getElementById('voceSblocca'),
    sbloccaBtn: document.getElementById('voceSbloccaBtn'),
    errore: document.getElementById('voceErrore'),
    dispRiga: document.getElementById('voceDispositivoRiga'),
    disp: document.getElementById('voceDispositivo'),
    persone: document.getElementById('vocePersone'),
    audio: document.getElementById('voceAudio'),
    iconaMicOff: document.getElementById('voceIconaMicOff')
  };

  var testi = {};
  Array.prototype.forEach.call(document.querySelectorAll('#voceTesti [data-k]'), function (s) {
    testi[s.getAttribute('data-k')] = s.textContent.trim();
  });
  function t(k) { return testi[k] || k; }

  // Preferenze di questo browser: microfono scelto e volume di ogni persona. Mai indispensabili.
  function readPref(chiave, ripiego) {
    try { var v = localStorage.getItem('voce.' + chiave); return v === null ? ripiego : v; } catch (e) { return ripiego; }
  }
  function writePref(chiave, valore) {
    try { localStorage.setItem('voce.' + chiave, valore); } catch (e) { /* niente */ }
  }

  var room = null;          // la stanza collegata (o in collegamento)
  var stanzaId = null;
  var puoParlare = false;
  var parlano = {};         // identita' -> true mentre parla
  var uscitaVoluta = false;

  // ------------------------------------------------------------------ stato e messaggi

  function showStatus(testo) { el.stato.textContent = testo || ''; }

  function showError(testo) {
    el.errore.textContent = testo || '';
    el.errore.hidden = !testo;
  }

  function markButtons() {
    Array.prototype.forEach.call(box.querySelectorAll('.voce-entra'), function (b) {
      var qui = b.getAttribute('data-room') === stanzaId && room !== null;
      b.disabled = qui;
      var card = b.closest('.voce-stanza');
      card.classList.toggle('is-attiva', qui);
      if (qui) { card.classList.remove('is-evidenza'); }
    });
  }

  function updateMicButton() {
    var lp = room && room.localParticipant;
    var acceso = !!(lp && lp.isMicrophoneEnabled);
    el.mic.disabled = !room || !puoParlare;
    el.mic.setAttribute('aria-pressed', acceso ? 'true' : 'false');
    el.mic.classList.toggle('is-acceso', acceso);
    el.mic.title = puoParlare ? '' : t('listen_only');
  }

  // ------------------------------------------------------------------ elenco persone

  function metadataOf(p) {
    try { return p.metadata ? JSON.parse(p.metadata) : {}; } catch (e) { return {}; }
  }

  function avatarOf(p) {
    var a = metadataOf(p).avatar;
    // Solo le teste di minotar, come nel resto del sito: i metadati li firma il sito, ma meglio
    // non fidarsi di un indirizzo qualunque dentro un <img>.
    return (typeof a === 'string' && a.indexOf('https://minotar.net/') === 0) ? a : '';
  }

  function isMicMuted(p) {
    var pub = p.getTrackPublication(LK.Track.Source.Microphone);
    return !pub || pub.isMuted;
  }

  // Una riga per persona, creata una volta sola e poi solo aggiornata: chi parla cambia due
  // volte al secondo, e ridisegnare tutto farebbe saltare il cursore del volume sotto il dito.
  var righe = {};           // identita' -> { li, muto, persona }

  function createRow(p, locale) {
    var li = document.createElement('li');
    li.className = 'voce-persona' + (locale ? ' is-tu' : '');

    var img = document.createElement('img');
    img.className = 'voce-persona-faccia';
    img.alt = '';
    img.width = 36;
    img.height = 36;
    img.loading = 'lazy';
    var src = avatarOf(p);
    if (src) { img.src = src; }
    li.appendChild(img);

    var nome = document.createElement('span');
    nome.className = 'voce-persona-nome';
    nome.textContent = p.name || p.identity;
    if (locale) {
      var tu = document.createElement('small');
      tu.textContent = ' (' + t('you') + ')';
      nome.appendChild(tu);
    }
    li.appendChild(nome);

    var muto = document.createElement('span');
    muto.className = 'voce-persona-muto';
    muto.title = t('muted');
    muto.appendChild(el.iconaMicOff.content.cloneNode(true));
    li.appendChild(muto);

    if (!locale) {
      var vol = document.createElement('input');
      vol.type = 'range';
      vol.min = '0';
      vol.max = '100';
      vol.step = '5';
      vol.className = 'voce-persona-volume';
      vol.title = t('volume');
      vol.setAttribute('aria-label', t('volume') + ' — ' + (p.name || p.identity));
      vol.value = readPref('vol.' + p.identity, '100');
      vol.addEventListener('input', function () {
        p.setVolume(Number(vol.value) / 100);
        writePref('vol.' + p.identity, vol.value);
      });
      li.appendChild(vol);
    }
    return { li: li, muto: muto, persona: p };
  }

  function updateRow(r) {
    var p = r.persona;
    var parla = !!parlano[p.identity];
    r.li.classList.toggle('is-parla', parla);
    r.muto.hidden = !isMicMuted(p);
    if (parla) {
      r.li.setAttribute('aria-label', (p.name || p.identity) + ', ' + t('speaking'));
    } else {
      r.li.removeAttribute('aria-label');
    }
  }

  /** Solo stato (chi parla, microfoni): nessuna riga creata o spostata. */
  function updatePeople() {
    Object.keys(righe).forEach(function (id) { updateRow(righe[id]); });
  }

  /** Elenco completo: righe nuove per chi e' entrato, via chi e' uscito, ordine per nome. */
  function renderPeople() {
    if (!room) { el.persone.textContent = ''; righe = {}; return; }
    var remoti = Array.from(room.remoteParticipants.values());
    remoti.sort(function (a, b) { return (a.name || '').localeCompare(b.name || ''); });
    var tutti = [room.localParticipant].concat(remoti);
    var nuove = {};
    tutti.forEach(function (p, i) {
      nuove[p.identity] = righe[p.identity] && righe[p.identity].persona === p
        ? righe[p.identity] : createRow(p, i === 0);
    });
    righe = nuove;
    el.persone.textContent = '';
    tutti.forEach(function (p) {
      updateRow(righe[p.identity]);
      el.persone.appendChild(righe[p.identity].li);
    });
    if (remoti.length === 0) {
      var vuoto = document.createElement('li');
      vuoto.className = 'voce-nota';
      vuoto.textContent = t('alone');
      el.persone.appendChild(vuoto);
    }
    if (room.state === LK.ConnectionState.Connected) {
      var n = tutti.length;
      showStatus(t('connected') + ' · ' + (n === 1 ? t('people_one') : t('people_many').replace('{n}', n)));
    }
  }

  function applyVolume(p) {
    var v = Number(readPref('vol.' + p.identity, '100'));
    if (!isNaN(v) && v !== 100) { p.setVolume(v / 100); }
  }

  // ------------------------------------------------------------------ microfoni del dispositivo

  function listMicrophones() {
    if (!room || !puoParlare) { el.dispRiga.hidden = true; return; }
    LK.Room.getLocalDevices('audioinput', false).then(function (lista) {
      el.disp.textContent = '';
      var attivo = room.getActiveDevice('audioinput');
      lista.forEach(function (d, i) {
        var o = document.createElement('option');
        o.value = d.deviceId;
        o.textContent = d.label || t('mic_n').replace('{n}', i + 1);
        if (d.deviceId === attivo) { o.selected = true; }
        el.disp.appendChild(o);
      });
      el.dispRiga.hidden = lista.length < 2;
    }).catch(function () { el.dispRiga.hidden = true; });
  }

  el.disp.addEventListener('change', function () {
    if (!room) { return; }
    var id = el.disp.value;
    room.switchActiveDevice('audioinput', id).then(function () {
      writePref('mic', id);
      showError('');
    }).catch(function () { showError(t('mic_error')); });
  });

  // ------------------------------------------------------------------ collegamento

  function requestToken(id) {
    var dati = new FormData();
    dati.set('action', 'token');
    dati.set('room', id);
    dati.set('csrf', csrf);
    return fetch(api, { method: 'POST', body: dati, credentials: 'same-origin' })
      .then(function (r) {
        return r.json().catch(function () { return { ok: false, error: t('error') }; });
      });
  }

  function detachAudio() {
    el.audio.textContent = '';
  }

  function enableMicrophone() {
    var preferito = readPref('mic', '');
    var opzioni = preferito ? { deviceId: preferito } : undefined;
    return room.localParticipant.setMicrophoneEnabled(true, opzioni).catch(function (e) {
      // Il microfono preferito non c'e' piu' (cuffie staccate): si riprova con quello di serie.
      if (preferito && e && (e.name === 'OverconstrainedError' || e.name === 'NotFoundError')) {
        writePref('mic', '');
        return room.localParticipant.setMicrophoneEnabled(true);
      }
      throw e;
    }).catch(function (e) {
      var nome = e && e.name;
      if (nome === 'NotAllowedError' || nome === 'SecurityError') {
        showError(t('mic_denied'));
      } else if (nome === 'NotFoundError' || nome === 'OverconstrainedError') {
        showError(t('mic_missing'));
      } else {
        showError(t('mic_error'));
      }
    });
  }

  function bindRoomEvents(r) {
    var E = LK.RoomEvent;
    r.on(E.ParticipantConnected, function (p) { applyVolume(p); renderPeople(); })
      .on(E.ParticipantDisconnected, function (p) { delete parlano[p.identity]; renderPeople(); })
      .on(E.TrackSubscribed, function (track, pub, p) {
        if (track.kind !== 'audio') { return; }
        // Lo stesso audio annunciato due volte (succede dopo una riconnessione): un secondo
        // elemento farebbe sentire quella voce doppia, con l'eco.
        if (track.attachedElements.length > 0) { return; }
        var a = track.attach();
        a.setAttribute('data-identita', p.identity);
        el.audio.appendChild(a);
        applyVolume(p);
        updatePeople();
      })
      .on(E.TrackUnsubscribed, function (track) {
        track.detach().forEach(function (a) { a.remove(); });
        updatePeople();
      })
      .on(E.TrackMuted, updatePeople)
      .on(E.TrackUnmuted, updatePeople)
      .on(E.TrackPublished, updatePeople)
      .on(E.TrackUnpublished, updatePeople)
      .on(E.LocalTrackPublished, function () { updateMicButton(); updatePeople(); listMicrophones(); })
      .on(E.LocalTrackUnpublished, function () { updateMicButton(); updatePeople(); })
      .on(E.ActiveSpeakersChanged, function (chi) {
        parlano = {};
        chi.forEach(function (p) { parlano[p.identity] = true; });
        updatePeople();
      })
      .on(E.AudioPlaybackStatusChanged, function () { el.sblocca.hidden = r.canPlaybackAudio; })
      .on(E.MediaDevicesChanged, listMicrophones)
      .on(E.Reconnecting, function () { showStatus(t('reconnecting')); })
      .on(E.Reconnected, function () { showError(''); renderPeople(); })
      .on(E.Disconnected, function (motivo) {
        if (r !== room) { return; }   // una stanza lasciata per entrare in un'altra
        var R = LK.DisconnectReason;
        var testo = '';
        if (!uscitaVoluta) {
          if (motivo === R.DUPLICATE_IDENTITY) { testo = t('duplicate'); }
          else if (motivo === R.PARTICIPANT_REMOVED) { testo = t('removed'); }
          else if (motivo === R.ROOM_DELETED || motivo === R.ROOM_CLOSED) { testo = t('closed'); }
          else { testo = t('disconnected'); }
        }
        resetCall(testo);
      });
  }

  /** Torna allo stato "nessuna stanza", con un messaggio facoltativo nel riquadro. */
  function resetCall(messaggio) {
    room = null;
    stanzaId = null;
    parlano = {};
    righe = {};
    detachAudio();
    el.persone.textContent = '';
    el.dispRiga.hidden = true;
    el.sblocca.hidden = true;
    updateMicButton();
    markButtons();
    if (messaggio) {
      showStatus('');
      showError(messaggio);
      el.chiamata.hidden = false;
    } else {
      el.chiamata.hidden = true;
    }
  }

  function leaveRoom() {
    var r = room;
    if (!r) { return Promise.resolve(); }
    uscitaVoluta = true;
    room = null;
    detachAudio();   // le voci della stanza lasciata: non devono restare negli elementi audio
    return r.disconnect().catch(function () {}).then(function () { uscitaVoluta = false; });
  }

  function joinRoom(id, nomeStanza) {
    if (room && stanzaId === id) { return; }
    showError('');
    leaveRoom().then(function () {
      stanzaId = id;
      el.nome.textContent = nomeStanza;
      el.chiamata.hidden = false;
      showStatus(t('connecting'));
      markButtons();
      return requestToken(id);
    }).then(function (g) {
      if (!g || !g.ok) {
        // Il motivo lo scrive il sito (ban, stanza non tua...): si mostra cosi' com'e'.
        var no = new Error((g && g.error) || t('error'));
        no.dalSito = true;
        throw no;
      }
      if (stanzaId !== id) { return; }   // nel frattempo ha cliccato altro
      puoParlare = !!g.canSpeak;
      el.nome.textContent = g.name || nomeStanza;
      var r = new LK.Room({
        adaptiveStream: false,
        dynacast: false,
        audioCaptureDefaults: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
        publishDefaults: { dtx: true, red: true }
      });
      room = r;
      bindRoomEvents(r);
      return r.connect(g.url, g.token, { autoSubscribe: true }).then(function () {
        if (room !== r) { return; }
        r.remoteParticipants.forEach(applyVolume);
        renderPeople();
        updateMicButton();
        markButtons();
        // Siamo ancora nel clic: e' il momento in cui il browser lascia partire l'audio.
        r.startAudio().catch(function () {}).then(function () { el.sblocca.hidden = r.canPlaybackAudio; });
        if (puoParlare) {
          return enableMicrophone().then(function () { updateMicButton(); updatePeople(); listMicrophones(); });
        }
      });
    }).catch(function (e) {
      var r = room;
      room = null;
      if (r) { r.disconnect().catch(function () {}); }
      resetCall(e && e.dalSito ? e.message : t('failed'));
    });
  }

  // ------------------------------------------------------------------ pulsanti

  Array.prototype.forEach.call(box.querySelectorAll('.voce-entra'), function (b) {
    b.addEventListener('click', function () {
      var card = b.closest('.voce-stanza');
      var nome = card ? card.querySelector('.voce-stanza-nome').textContent.trim() : '';
      joinRoom(b.getAttribute('data-room'), nome);
    });
  });

  el.mic.addEventListener('click', function () {
    if (!room || !puoParlare) { return; }
    var lp = room.localParticipant;
    var accendi = !lp.isMicrophoneEnabled;
    (accendi ? enableMicrophone() : lp.setMicrophoneEnabled(false)).then(function () {
      updateMicButton();
      updatePeople();
    });
  });

  el.esci.addEventListener('click', function () {
    leaveRoom().then(function () { resetCall(''); });
  });

  el.sbloccaBtn.addEventListener('click', function () {
    if (room) { room.startAudio().then(function () { el.sblocca.hidden = room.canPlaybackAudio; }); }
  });

  // Arrivando da /voce#<stanza> (colonna laterale): la scheda di quella stanza si fa notare.
  // Entrare resta un clic: il browser fa partire microfono e audio solo dopo un gesto.
  if (location.hash.length > 1) {
    var scelta = document.getElementById(decodeURIComponent(location.hash.slice(1)));
    if (scelta && scelta.classList.contains('voce-stanza')) {
      scelta.classList.add('is-evidenza');
      scelta.scrollIntoView({ block: 'center' });
    }
  }

  // Chiudendo la scheda si esce subito dalla stanza (gli altri non aspettano il timeout).
  window.addEventListener('pagehide', function () {
    if (room) { uscitaVoluta = true; room.disconnect(); }
  });
})();
