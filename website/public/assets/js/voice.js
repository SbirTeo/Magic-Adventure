/*
 * Chat vocale del sito (/voice). La pagina la disegna voice.php; qui:
 *  - "Entra" chiede al sito il gettone di quella stanza (api/voice.php) e si collega al server
 *    della voce (LiveKit, attraverso nginx su /voice-rtc);
 *  - accende il microfono (se il giocatore può parlare) e fa sentire gli altri;
 *  - tiene l'elenco di chi c'è, chi sta parlando, chi ha il microfono spento, con un volume
 *    per persona (ricordato in questo browser).
 *
 * I testi stanno nella pagina (#voiceTexts), così li traduce il sito come tutto il resto.
 * Libreria: assets/js/vendor/livekit-client.umd.js (copia locale, globale LivekitClient).
 */
(function () {
  'use strict';

  var box = document.getElementById('voice');
  if (!box || !window.LivekitClient) {
    return;
  }
  var LK = window.LivekitClient;
  var api = box.getAttribute('data-api');
  var csrf = box.getAttribute('data-csrf') || '';

  var el = {
    chiamata: document.getElementById('voiceChiamata'),
    nome: document.getElementById('voiceNome'),
    stato: document.getElementById('voiceStato'),
    mic: document.getElementById('voiceMic'),
    esci: document.getElementById('voiceEsci'),
    sblocca: document.getElementById('voiceSblocca'),
    sbloccaBtn: document.getElementById('voiceSbloccaBtn'),
    errore: document.getElementById('voiceErrore'),
    dispRiga: document.getElementById('voiceDispositivoRiga'),
    disp: document.getElementById('voiceDispositivo'),
    persone: document.getElementById('voicePersone'),
    audio: document.getElementById('voiceAudio'),
    iconaMicOff: document.getElementById('voiceIconaMicOff'),
    avviso: document.getElementById('voiceAvviso')
  };

  var testi = {};
  Array.prototype.forEach.call(document.querySelectorAll('#voiceTexts [data-k]'), function (s) {
    testi[s.getAttribute('data-k')] = s.textContent.trim();
  });
  function t(k) { return testi[k] || k; }

  // Preferenze di questo browser: microfono scelto e volume di ogni persona. Mai indispensabili.
  function readPref(chiave, ripiego) {
    try { var v = localStorage.getItem('voice.' + chiave); return v === null ? ripiego : v; } catch (e) { return ripiego; }
  }
  function writePref(chiave, valore) {
    try { localStorage.setItem('voice.' + chiave, valore); } catch (e) { /* niente */ }
  }

  var room = null;          // la stanza collegata (o in collegamento)
  var stanzaId = null;
  var puoParlare = false;
  var parlano = {};         // identita' -> true mentre parla
  var uscitaVoluta = false;
  var micVoluto = true;     // false dopo che il giocatore ha spento il microfono col pulsante
  // Stanza di prossimita' (near-<server>): volume e lato di ogni vicino li manda il server di
  // gioco (MagixBridge) qualche volta al secondo, solo a noi. null nelle altre stanze.
  var vicinanza = null;     // { gains: {id: 0-1}, pans: {id: -1..1}, permessi: 'id,id', ultimo: ms }
  var panners = {};         // identita' -> StereoPannerNode (sinistra/destra)

  function isProximityRoom(id) { return typeof id === 'string' && id.indexOf('near-') === 0; }

  // Chi parla si vede anche in gioco (note sopra la testa, MagixBridge): la pagina dice al sito
  // quando il NOSTRO microfono parla, nella stanza dei vicini, e lo ripete ogni secondo finche' dura
  // (il sito lo tiene per pochi secondi: se la pagina si chiude di colpo, le note spariscono da sole).
  var parloIo = false;
  var ultimoAvviso = 0;
  var avvisoParla = false;   // l'ultimo stato mandato al sito

  function reportSpeaking(parla) {
    if (!room || !isProximityRoom(stanzaId)) { return; }
    ultimoAvviso = Date.now();
    avvisoParla = parla;
    var dati = new FormData();
    dati.set('action', 'speaking');
    dati.set('room', stanzaId);
    dati.set('state', parla ? '1' : '0');
    dati.set('csrf', csrf);
    fetch(api, { method: 'POST', body: dati, credentials: 'same-origin', keepalive: !parla })
      .catch(function () { /* il prossimo avviso rimette a posto */ });
  }

  /**
   * Una pausa fra due frasi non manda niente: la riga del sito scade da sola dopo un paio di
   * secondi (cosi' le note in gioco non lampeggiano a ogni respiro). Lo stop esplicito (smetti=true)
   * parte solo quando si spegne il microfono, si esce o arriva un mute.
   */
  function setSpeaking(parla, smetti) {
    parloIo = parla;
    if (parla && (!avvisoParla || Date.now() - ultimoAvviso >= 1000)) {
      reportSpeaking(true);
    } else if (!parla && smetti && avvisoParla) {
      reportSpeaking(false);
    }
  }

  // ------------------------------------------------------------------ stato e messaggi

  function showStatus(testo) { el.stato.textContent = testo || ''; }

  function showError(testo) {
    el.errore.textContent = testo || '';
    el.errore.hidden = !testo;
  }

  function markButtons() {
    Array.prototype.forEach.call(box.querySelectorAll('.voice-entra'), function (b) {
      var qui = b.getAttribute('data-room') === stanzaId && room !== null;
      b.disabled = qui;
      var card = b.closest('.voice-stanza');
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
    li.className = 'voice-persona' + (locale ? ' is-tu' : '');

    var img = document.createElement('img');
    img.className = 'voice-persona-faccia';
    img.alt = '';
    img.width = 36;
    img.height = 36;
    img.loading = 'lazy';
    var src = avatarOf(p);
    if (src) { img.src = src; }
    li.appendChild(img);

    var nome = document.createElement('span');
    nome.className = 'voice-persona-nome';
    nome.textContent = p.name || p.identity;
    if (locale) {
      var tu = document.createElement('small');
      tu.textContent = ' (' + t('you') + ')';
      nome.appendChild(tu);
    }
    li.appendChild(nome);

    var muto = document.createElement('span');
    muto.className = 'voice-persona-muto';
    muto.title = t('muted');
    muto.appendChild(el.iconaMicOff.content.cloneNode(true));
    li.appendChild(muto);

    if (!locale) {
      var vol = document.createElement('input');
      vol.type = 'range';
      vol.min = '0';
      vol.max = '100';
      vol.step = '5';
      vol.className = 'voice-persona-volume';
      vol.title = t('volume');
      vol.setAttribute('aria-label', t('volume') + ' — ' + (p.name || p.identity));
      vol.value = readPref('vol.' + p.identity, '100');
      vol.addEventListener('input', function () {
        writePref('vol.' + p.identity, vol.value);
        applyVolume(p);
      });
      li.appendChild(vol);
    }
    return { li: li, muto: muto, persona: p };
  }

  function updateRow(r) {
    var p = r.persona;
    var parla = !!parlano[p.identity];
    r.li.classList.toggle('is-parla', parla);
    // In prossimita' chi non e' vicino resta in elenco, ma spento: lo si sente solo avvicinandosi.
    var lontano = !!vicinanza && !!room && p !== room.localParticipant && !(p.identity in vicinanza.gains);
    r.li.classList.toggle('is-lontano', lontano);
    r.li.title = lontano ? t('far') : '';
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
      vuoto.className = 'voice-nota';
      vuoto.textContent = t('alone');
      el.persone.appendChild(vuoto);
    }
    if (room.state === LK.ConnectionState.Connected) {
      var n = tutti.length;
      showStatus(t('connected') + ' · ' + (n === 1 ? t('people_one') : t('people_many').replace('{n}', n)));
    }
  }

  /** Volume scelto per quella persona, per il volume della distanza se siamo in prossimita'. */
  function applyVolume(p) {
    var v = Number(readPref('vol.' + p.identity, '100'));
    if (isNaN(v)) { v = 100; }
    var distanza = vicinanza ? (vicinanza.gains[p.identity] || 0) : 1;
    p.setVolume((v / 100) * distanza);
  }

  /**
   * Un pacchetto del server di gioco: [[identita', volume 0-1, lato -1..1], ...] per i vicini.
   * Chi e' nell'elenco puo' anche sentire il NOSTRO microfono: il server della voce lo fa
   * rispettare, quindi chi e' lontano non ci sente nemmeno con una pagina modificata.
   */
  function applyProximity(elenco) {
    if (!vicinanza || !room) { return; }
    var gains = {}, pans = {}, ids = [];
    elenco.forEach(function (v) {
      if (!Array.isArray(v) || typeof v[0] !== 'string') { return; }
      ids.push(v[0]);
      gains[v[0]] = Math.max(0, Math.min(1, Number(v[1]) || 0));
      pans[v[0]] = Math.max(-1, Math.min(1, Number(v[2]) || 0));
    });
    vicinanza.gains = gains;
    vicinanza.pans = pans;
    vicinanza.ultimo = Date.now();
    ids.sort();
    var chiave = ids.join(',');
    if (chiave !== vicinanza.permessi) {
      vicinanza.permessi = chiave;
      room.localParticipant.setTrackSubscriptionPermissions(false, ids.map(function (id) {
        return { participantIdentity: id, allowAll: true };
      }));
    }
    room.remoteParticipants.forEach(function (p) {
      applyVolume(p);
      var pn = panners[p.identity];
      if (pn) { pn.pan.setTargetAtTime(pans[p.identity] || 0, pn.context.currentTime, 0.1); }
    });
    el.avviso.hidden = true;
    updatePeople();
  }

  /** Il lato (sinistra/destra) di una voce appena arrivata, nella stanza di prossimita'. */
  function attachPanner(track, identita) {
    var ctx = track.audioContext;
    if (!vicinanza || !ctx || typeof ctx.createStereoPanner !== 'function') { return; }
    var pn = ctx.createStereoPanner();
    pn.pan.value = vicinanza.pans[identita] || 0;
    track.setWebAudioPlugins([pn]);
    panners[identita] = pn;
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
        // Una voce per persona: un elemento rimasto da un abbonamento precedente se ne va.
        Array.prototype.forEach.call(el.audio.querySelectorAll('audio'), function (vecchio) {
          if (vecchio.getAttribute('data-identita') === p.identity) { vecchio.remove(); }
        });
        var a = track.attach();
        a.setAttribute('data-identita', p.identity);
        el.audio.appendChild(a);
        attachPanner(track, p.identity);
        applyVolume(p);
        updatePeople();
      })
      .on(E.TrackUnsubscribed, function (track, pub, p) {
        track.detach().forEach(function (a) { a.remove(); });
        if (p) { delete panners[p.identity]; }
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
        setSpeaking(!!parlano[r.localParticipant.identity] && r.localParticipant.isMicrophoneEnabled, false);
        updatePeople();
      })
      .on(E.ParticipantPermissionsChanged, function (prima, p) {
        // Il permesso di parlare cambiato a stanza in corso: mute dato o tolto dallo staff, o
        // scaduto. Tolto: il server ha gia' spento il microfono; ridato: si riaccende da solo se
        // il giocatore non l'aveva spento lui.
        if (p && p !== r.localParticipant) { return; }
        var lp = r.localParticipant;
        var ora = !!(lp.permissions && lp.permissions.canPublish);
        if (ora === puoParlare) { return; }
        puoParlare = ora;
        showError(ora ? '' : t('silenced'));
        if (!ora) { setSpeaking(false, true); }
        updateMicButton();
        if (ora && micVoluto) {
          enableMicrophone().then(function () { updateMicButton(); updatePeople(); listMicrophones(); });
        }
      })
      .on(E.AudioPlaybackStatusChanged, function () { el.sblocca.hidden = r.canPlaybackAudio; })
      .on(E.MediaDevicesChanged, listMicrophones)
      .on(E.DataReceived, function (dati, mittente, tipo, argomento) {
        // Solo i pacchetti del server (nessun mittente): un altro browser non puo' mandarli.
        if (mittente || argomento !== 'proximity' || r !== room) { return; }
        try {
          applyProximity(JSON.parse(new TextDecoder().decode(dati)).n || []);
        } catch (e) { /* pacchetto rovinato: arriva il prossimo */ }
      })
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
    setSpeaking(false, true);
    room = null;
    stanzaId = null;
    parlano = {};
    righe = {};
    vicinanza = null;
    panners = {};
    el.avviso.hidden = true;
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
    setSpeaking(false, true);
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
      vicinanza = isProximityRoom(id) ? { gains: {}, pans: {}, permessi: '', ultimo: 0 } : null;
      panners = {};
      var r = new LK.Room({
        adaptiveStream: false,
        dynacast: false,
        // In prossimita' l'audio passa dal mixer del browser: serve per il lato sinistra/destra.
        webAudioMix: !!vicinanza,
        singlePeerConnection: false,
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
        var permessi = Promise.resolve();
        if (vicinanza) {
          // Prima di accendere il microfono: nessuno ci sente finche' il server di gioco non dice
          // chi ci sta vicino.
          // (Se un pacchetto e' gia' arrivato durante il collegamento, si tiene il suo elenco.)
          permessi = r.localParticipant.setTrackSubscriptionPermissions(false,
            vicinanza.permessi.split(',').filter(Boolean).map(function (id) {
              return { participantIdentity: id, allowAll: true };
            }));
        }
        if (puoParlare) {
          return Promise.resolve(permessi).then(enableMicrophone)
            .then(function () {
              updateMicButton(); updatePeople(); listMicrophones();
              // Raro, ma visto: la negoziazione WebRTC fallisce e il microfono non esce. Un
              // secondo tentativo dopo qualche secondo, solo se nel frattempo non l'ha spento lui.
              setTimeout(function () {
                if (room === r && puoParlare && micVoluto
                    && !r.localParticipant.getTrackPublication(LK.Track.Source.Microphone)) {
                  enableMicrophone().then(function () { updateMicButton(); updatePeople(); });
                }
              }, 4000);
            });
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

  Array.prototype.forEach.call(box.querySelectorAll('.voice-entra'), function (b) {
    b.addEventListener('click', function () {
      var card = b.closest('.voice-stanza');
      var nome = card ? card.querySelector('.voice-stanza-nome').textContent.trim() : '';
      joinRoom(b.getAttribute('data-room'), nome);
    });
  });

  el.mic.addEventListener('click', function () {
    if (!room || !puoParlare) { return; }
    var lp = room.localParticipant;
    var accendi = !lp.isMicrophoneEnabled;
    micVoluto = accendi;
    if (!accendi) { setSpeaking(false, true); }
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

  // Arrivando da /voice#<stanza> (colonna laterale): la scheda di quella stanza si fa notare.
  // Entrare resta un clic: il browser fa partire microfono e audio solo dopo un gesto.
  if (location.hash.length > 1) {
    var scelta = document.getElementById(decodeURIComponent(location.hash.slice(1)));
    if (scelta && scelta.classList.contains('voice-stanza')) {
      scelta.classList.add('is-evidenza');
      scelta.scrollIntoView({ block: 'center' });
    }
  }

  // Prossimita': ogni pochi secondi si controlla che i pacchetti del server di gioco arrivino
  // (se no il giocatore non e' in gioco) e, ogni tanto, in che modalita' e' adesso: se e' passato
  // da faction a hub, la pagina lo sposta da sola nella stanza dei vicini della modalita' nuova.
  var ultimoGiro = 0;
  setInterval(function () {
    if (!room || !vicinanza || room.state !== LK.ConnectionState.Connected) { return; }
    var muto = Date.now() - Math.max(vicinanza.ultimo, vicinanza.inizio || 0) > 6000;
    if (!vicinanza.inizio) { vicinanza.inizio = Date.now(); muto = false; }
    el.avviso.hidden = !muto;
    if (Date.now() - ultimoGiro < 15000) { return; }
    ultimoGiro = Date.now();
    var dati = new FormData();
    dati.set('action', 'rooms');
    dati.set('csrf', csrf);
    fetch(api, { method: 'POST', body: dati, credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (risposta) {
        if (!risposta || !risposta.ok || !room || !isProximityRoom(stanzaId)) { return; }
        var qui = (risposta.rooms || []).filter(function (s) { return isProximityRoom(s.id); })[0];
        if (qui && qui.id !== stanzaId) {
          joinRoom(qui.id, qui.name);
        }
      })
      .catch(function () { /* riprova al prossimo giro */ });
  }, 2000);

  // Finche' si parla, l'avviso si rinnova ogni secondo (il sito lo tiene vivo 2 secondi).
  setInterval(function () {
    if (parloIo && Date.now() - ultimoAvviso >= 1000) { reportSpeaking(true); }
    // Pausa lunga: lo stato mandato resta "parla" ma la riga del sito e' gia' scaduta da sola.
    if (!parloIo && avvisoParla && Date.now() - ultimoAvviso >= 2000) { avvisoParla = false; }
  }, 500);

  // Chiudendo la scheda si esce subito dalla stanza (gli altri non aspettano il timeout).
  window.addEventListener('pagehide', function () {
    if (room) { uscitaVoluta = true; room.disconnect(); }
  });
})();
