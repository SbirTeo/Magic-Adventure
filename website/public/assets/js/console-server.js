// Scheda "Server" del gestionale: schede dei server, console dal vivo e screen del VPS.
// Parla solo con /api/console; il lavoro vero lo fa magix-console sulla macchina.
// Su tutte le altre pagine questo file esce subito.
(function () {
  var radice = document.getElementById('consoleServer');
  if (!radice) return;

  var csrf = radice.getAttribute('data-csrf') || '';
  var modoScreen = radice.getAttribute('data-modo') || 'lettura';

  var boxIstanze = document.getElementById('consoleIstanze');
  var boxSchede = document.getElementById('consoleSchede');
  var boxScreens = document.getElementById('consoleScreens');
  var schermo = document.getElementById('consoleSchermo');
  var avvisi = document.getElementById('consoleAvvisi');
  var aggiornato = document.getElementById('consoleAggiornato');
  var form = document.getElementById('consoleForm');
  var campo = document.getElementById('consoleComando');
  var selRighe = document.getElementById('consoleRighe');
  var spuntaAuto = document.getElementById('consoleAuto');

  // Cosa si sta guardando in console: un server configurato o una screen qualsiasi.
  var mira = null;           // { tipo: 'istanza'|'screen', id: '...' }
  var ultimaFirma = null;
  var statoCorrente = { istanze: [], screens: [], modo_screen: modoScreen };
  var timerStato = null;
  var timerLog = null;
  var storico = [];          // comandi gia' mandati, si ripescano con le frecce
  var postoStorico = -1;

  // ------------------------------------------------------------------
  //  Utilita'
  // ------------------------------------------------------------------
  function avviso(text, errore) {
    avvisi.innerHTML = '';
    if (!text) return;
    var d = document.createElement('div');
    d.className = 'alert alert-' + (errore ? 'error' : 'success');
    d.textContent = text;
    avvisi.appendChild(d);
    if (!errore) {
      setTimeout(function () { if (d.parentNode) d.parentNode.removeChild(d); }, 6000);
    }
  }

  function duration(secondi) {
    if (secondi === null || secondi === undefined || secondi < 0) return '—';
    if (secondi < 60) return secondi + ' s';
    var min = Math.floor(secondi / 60);
    if (min < 60) return min + ' min';
    var ore = Math.floor(min / 60);
    min = min % 60;
    if (ore < 24) return ore + ' h' + (min ? ' ' + min + ' min' : '');
    var giorni = Math.floor(ore / 24);
    ore = ore % 24;
    return giorni + ' g' + (ore ? ' ' + ore + ' h' : '');
  }

  function chiedi(dati) {
    var corpo = new URLSearchParams();
    corpo.append('csrf', csrf);
    Object.keys(dati).forEach(function (k) { corpo.append(k, dati[k]); });
    return fetch('/api/console', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: corpo.toString()
    }).then(function (r) { return r.json().catch(function () { return { ok: false, error: 'Risposta non valida dal sito.' }; }); });
  }

  function read(parametri) {
    return fetch('/api/console?' + new URLSearchParams(parametri).toString(), {
      credentials: 'same-origin'
    }).then(function (r) { return r.json().catch(function () { return { ok: false, error: 'Risposta non valida dal sito.' }; }); });
  }

  // ------------------------------------------------------------------
  //  Schede dei server
  // ------------------------------------------------------------------
  var ETICHETTA_LAVORO = { start: 'avvio in corso…', ferma: 'arresto in corso…', riavvia: 'riavvio in corso…', forza: 'chiusura in corso…' };

  function pulsante(text, classe, azione, ist) {
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'btn btn-small ' + classe;
    b.textContent = text;
    b.addEventListener('click', function () { runAction(azione, ist, b); });
    return b;
  }

  function drawInstances() {
    boxIstanze.innerHTML = '';
    if (!statoCorrente.istanze.length) {
      var vuoto = document.createElement('div');
      vuoto.className = 'panel';
      vuoto.textContent = statoCorrente.errore
        ? statoCorrente.errore
        : 'Nessun server configurato in /etc/magicadventure/istanze.conf.';
      boxIstanze.appendChild(vuoto);
      return;
    }

    statoCorrente.istanze.forEach(function (ist) {
      var card = document.createElement('div');
      card.className = 'console-card' + (ist.accesa ? ' e-accesa' : '');

      var testa = document.createElement('div');
      testa.className = 'console-card-testa';
      var titolo = document.createElement('h3');
      titolo.textContent = ist.nome;
      var pallino = document.createElement('span');
      pallino.className = 'console-pill ' + (ist.lavoro ? 'e-lavoro' : (ist.accesa ? 'e-su' : 'e-giu'));
      pallino.textContent = ist.lavoro ? (ETICHETTA_LAVORO[ist.lavoro] || 'in corso…') : (ist.accesa ? 'acceso' : 'spento');
      testa.appendChild(titolo);
      testa.appendChild(pallino);
      card.appendChild(testa);

      var righe = [];
      if (ist.accesa) {
        righe.push('acceso da ' + duration(ist.da));
        if (ist.processo === 'assente') {
          righe.push('⚠ la screen è aperta ma il processo del server non c’è');
        }
        if (ist.giocatori !== null && ist.giocatori !== undefined) {
          righe.push(ist.giocatori + (ist.giocatori_max ? '/' + ist.giocatori_max : '') + ' giocatori collegati');
        } else if (ist.risponde === false && ist.porta) {
          righe.push('non risponde ancora sulla porta ' + ist.porta + ' (sta caricando?)');
        }
      } else {
        righe.push('spento');
      }
      var dett = document.createElement('div');
      dett.className = 'sub console-card-dati';
      dett.textContent = righe.join(' · ');
      card.appendChild(dett);

      var tecnico = document.createElement('div');
      tecnico.className = 'sub console-card-tecnico';
      tecnico.textContent = 'screen ' + ist.screen + (ist.servizio ? ' · ' + ist.servizio : '') + (ist.porta ? ' · porta ' + ist.porta : '');
      card.appendChild(tecnico);

      var azioni = document.createElement('div');
      azioni.className = 'console-azioni';
      if (ist.accesa) {
        azioni.appendChild(pulsante('Ferma', 'btn-ghost', 'ferma', ist));
        if (ist.servizio) azioni.appendChild(pulsante('Riavvia', 'btn-accent', 'riavvia', ist));
        azioni.appendChild(pulsante('Forza chiusura', 'btn-danger', 'forza', ist));
      } else {
        azioni.appendChild(pulsante('Avvia', 'btn-green', 'avvia', ist));
      }
      var guarda = document.createElement('button');
      guarda.type = 'button';
      guarda.className = 'btn btn-ghost btn-small';
      guarda.textContent = 'Console';
      guarda.addEventListener('click', function () { selectTarget('istanza', ist.id); });
      azioni.appendChild(guarda);

      if (ist.lavoro) {
        Array.prototype.forEach.call(azioni.querySelectorAll('button'), function (b) {
          if (b !== guarda) b.disabled = true;
        });
      }
      card.appendChild(azioni);
      boxIstanze.appendChild(card);
    });
  }

  var CONFERME = {
    ferma: 'Fermare «%s»? I mondi vengono salvati e chi sta giocando viene disconnesso.',
    riavvia: 'Riavviare «%s»? I mondi vengono salvati; il server torna su fra un minuto circa.',
    forza: 'CHIUSURA FORZATA di «%s».\n\nIl processo viene ucciso all’istante: quello che non è ancora stato salvato SI PERDE.\nUsala solo se il server è bloccato e «Ferma» non funziona.\n\nProcedere?'
  };

  function runAction(azione, ist, bottone) {
    if (CONFERME[azione] && !window.confirm(CONFERME[azione].replace('%s', ist.nome))) return;
    bottone.disabled = true;
    chiedi({ azione: azione, id: ist.id }).then(function (r) {
      if (!r.ok) {
        avviso(r.error || 'Operazione non riuscita.', true);
        bottone.disabled = false;
        return;
      }
      avviso(r.messaggio || 'Fatto.', false);
      updateStatus();
    }).catch(function () {
      avviso('Il sito non risponde.', true);
      bottone.disabled = false;
    });
  }

  // ------------------------------------------------------------------
  //  Altre screen
  // ------------------------------------------------------------------
  function drawScreens() {
    boxScreens.innerHTML = '';
    if (!statoCorrente.screens || !statoCorrente.screens.length) {
      var p = document.createElement('p');
      p.className = 'sub';
      p.style.margin = '0';
      p.textContent = 'Nessuna altra screen aperta.';
      boxScreens.appendChild(p);
      return;
    }
    statoCorrente.screens.forEach(function (s) {
      var riga = document.createElement('div');
      riga.className = 'manage-row';

      var sin = document.createElement('div');
      var t = document.createElement('div');
      t.className = 'title';
      t.textContent = s.sessione;
      var d = document.createElement('div');
      d.className = 'sub';
      d.textContent = 'utente ' + s.utente + ' · pid ' + s.pid + ' · aperta da ' + duration(s.da);
      sin.appendChild(t);
      sin.appendChild(d);
      riga.appendChild(sin);

      var azioni = document.createElement('div');
      azioni.className = 'actions';
      var guarda = document.createElement('button');
      guarda.type = 'button';
      guarda.className = 'btn btn-ghost btn-small';
      guarda.textContent = 'Guarda';
      guarda.addEventListener('click', function () { selectTarget('screen', s.sessione); });
      azioni.appendChild(guarda);

      if (statoCorrente.modo_screen === 'pieno') {
        var chiudi = document.createElement('button');
        chiudi.type = 'button';
        chiudi.className = 'btn btn-danger btn-small';
        chiudi.textContent = 'Chiudi';
        chiudi.addEventListener('click', function () {
          if (!window.confirm('Chiudere la screen «' + s.sessione + '»? Quello che ci gira dentro viene terminato.')) return;
          chiudi.disabled = true;
          chiedi({ azione: 'screen-chiudi', sessione: s.sessione }).then(function (r) {
            avviso(r.ok ? (r.messaggio || 'Screen chiusa.') : (r.error || 'Non riuscito.'), !r.ok);
            updateStatus();
          });
        });
        azioni.appendChild(chiudi);
      }
      riga.appendChild(azioni);
      boxScreens.appendChild(riga);
    });
  }

  // ------------------------------------------------------------------
  //  Console
  // ------------------------------------------------------------------
  function drawTabs() {
    boxSchede.innerHTML = '';
    var voci = statoCorrente.istanze.map(function (i) {
      return { tipo: 'istanza', id: i.id, nome: i.nome, viva: i.accesa };
    });
    (statoCorrente.screens || []).forEach(function (s) {
      voci.push({ tipo: 'screen', id: s.sessione, nome: 'screen ' + s.sessione, viva: true });
    });
    if (!voci.length) return;

    // Se non si e' ancora scelto niente (o quello che si guardava non c'e' piu'),
    // si apre il primo della lista.
    var valida = mira && voci.some(function (v) { return v.tipo === mira.tipo && v.id === mira.id; });
    if (!valida) {
      mira = { tipo: voci[0].tipo, id: voci[0].id };
      ultimaFirma = null;
    }

    voci.forEach(function (v) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'console-scheda' + (mira.tipo === v.tipo && mira.id === v.id ? ' e-attiva' : '') + (v.viva ? '' : ' e-spenta');
      b.textContent = v.nome;
      b.addEventListener('click', function () { selectTarget(v.tipo, v.id); });
      boxSchede.appendChild(b);
    });

    // La riga di comando ha senso solo dove si puo' davvero scrivere.
    var scrivibile = mira.tipo === 'istanza'
      ? statoCorrente.istanze.some(function (i) { return i.id === mira.id && i.accesa; })
      : statoCorrente.modo_screen === 'pieno';
    form.classList.toggle('e-bloccata', !scrivibile);
    campo.disabled = !scrivibile;
    campo.placeholder = scrivibile
      ? 'comando da mandare alla console (senza / davanti)'
      : (mira.tipo === 'screen' ? 'sola lettura: vedi screen_esterni in istanze.conf' : 'il server è spento');
  }

  function selectTarget(tipo, id) {
    mira = { tipo: tipo, id: id };
    ultimaFirma = null;
    schermo.textContent = 'Caricamento…';
    drawTabs();
    updateLog();
    if (!campo.disabled) campo.focus();
  }

  function inFondo() {
    return schermo.scrollHeight - schermo.scrollTop - schermo.clientHeight < 60;
  }

  function drawLog(text) {
    var seguire = inFondo();
    var prevTop = schermo.scrollTop;   // per non saltare in cima quando arriva roba nuova mentre leggi
    var frammento = document.createDocumentFragment();
    text.split('\n').forEach(function (riga) {
      var span = document.createElement('span');
      span.className = 'console-riga';
      if (/\/(ERROR|FATAL)\]|Exception|Caused by:/i.test(riga)) span.classList.add('e-errore');
      else if (/\/WARN\]/.test(riga)) span.classList.add('e-avviso');
      else if (/\/(INFO)\]:? \[?(MagixWeb|MagixFactions|MagixTime|MagixEntities|MagixGuard)/.test(riga)) span.classList.add('e-nostro');
      span.textContent = riga;
      frammento.appendChild(span);
      frammento.appendChild(document.createTextNode('\n'));
    });
    schermo.innerHTML = '';
    schermo.appendChild(frammento);
    // Se stai seguendo il fondo, resti al fondo; se stavi leggendo più su, ti ci lascio invece di
    // ributtarti in cima (il rebuild azzera lo scroll da solo).
    schermo.scrollTop = seguire ? schermo.scrollHeight : prevTop;
  }

  function updateLog() {
    if (!mira) return;
    var parametri = mira.tipo === 'istanza'
      ? { azione: 'log', id: mira.id, righe: selRighe.value }
      : { azione: 'screen-log', sessione: mira.id, righe: selRighe.value };
    return read(parametri).then(function (r) {
      if (!r.ok) {
        schermo.textContent = r.error || 'Console non leggibile.';
        ultimaFirma = null;
        return;
      }
      if (r.firma === ultimaFirma) return;   // niente di nuovo: non si ridisegna
      ultimaFirma = r.firma;
      drawLog(r.testo);
    }).catch(function () { /* rete ballerina: si riprova al giro dopo */ });
  }

  function updateStatus() {
    return read({ azione: 'stato' }).then(function (r) {
      if (!r || r.ok === false) {
        avviso((r && r.error) || 'Stato non leggibile.', true);
        return;
      }
      statoCorrente = r;
      drawInstances();
      drawTabs();
      drawScreens();
      var ora = new Date();
      aggiornato.textContent = 'aggiornato alle ' + ora.toLocaleTimeString('it-IT');
      programmaStato();
    }).catch(function () { programmaStato(); });
  }

  // Mentre un server sta partendo o si sta fermando conviene guardare piu' spesso.
  function programmaStato() {
    if (timerStato) clearTimeout(timerStato);
    var lavoroInCorso = (statoCorrente.istanze || []).some(function (i) { return i.lavoro; });
    timerStato = setTimeout(updateStatus, lavoroInCorso ? 2000 : 6000);
  }

  function giroLog() {
    if (timerLog) clearTimeout(timerLog);
    var prosegui = function () { timerLog = setTimeout(giroLog, 1000); };
    if (document.hidden || !spuntaAuto.checked) {
      prosegui();
      return;
    }
    var p = updateLog();
    if (p && p.then) p.then(prosegui, prosegui); else prosegui();
  }

  // ------------------------------------------------------------------
  //  Riga di comando
  // ------------------------------------------------------------------
  function sendCommand(text) {
    if (!text || !mira) return;
    var dati = mira.tipo === 'istanza'
      ? { azione: 'cmd', id: mira.id, comando: text }
      : { azione: 'screen-cmd', sessione: mira.id, comando: text };
    chiedi(dati).then(function (r) {
      if (!r.ok) {
        avviso(r.error || 'Comando non riuscito.', true);
        return;
      }
      storico.push(text);
      postoStorico = -1;
      ultimaFirma = null;          // la risposta arriva subito nel log
      setTimeout(updateLog, 400);
    }).catch(function () { avviso('Il sito non risponde.', true); });
  }

  form.addEventListener('submit', function (e) {
    e.preventDefault();
    var text = campo.value.trim();
    if (!text) return;
    // La barra davanti non serve in console: se c'e', la si toglie invece di far
    // fallire il comando.
    if (text.charAt(0) === '/') text = text.slice(1);
    campo.value = '';
    sendCommand(text);
  });

  // Frecce su/giu': si ripescano i comandi gia' mandati, come in un terminale.
  campo.addEventListener('keydown', function (e) {
    if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return;
    if (!storico.length) return;
    e.preventDefault();
    if (e.key === 'ArrowUp') {
      postoStorico = postoStorico < 0 ? storico.length - 1 : Math.max(0, postoStorico - 1);
    } else {
      postoStorico = postoStorico < 0 ? -1 : Math.min(storico.length - 1, postoStorico + 1);
    }
    campo.value = postoStorico < 0 ? '' : storico[postoStorico];
  });

  document.getElementById('consoleRapidi').addEventListener('click', function (e) {
    var b = e.target.closest('button');
    if (!b || campo.disabled) return;
    if (b.hasAttribute('data-riempi')) {
      campo.value = b.getAttribute('data-riempi');
      campo.focus();
      return;
    }
    if (b.hasAttribute('data-comando')) sendCommand(b.getAttribute('data-comando'));
  });

  document.getElementById('consoleGiu').addEventListener('click', function () {
    schermo.scrollTop = schermo.scrollHeight;
  });

  selRighe.addEventListener('change', function () { ultimaFirma = null; updateLog(); });
  spuntaAuto.addEventListener('change', function () { if (spuntaAuto.checked) updateLog(); });
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) { updateLog(); updateStatus(); }
  });

  // ------------------------------------------------------------------
  //  Avvio
  // ------------------------------------------------------------------
  try {
    var iniziale = document.getElementById('consoleStatoIniziale');
    if (iniziale) statoCorrente = JSON.parse(iniziale.textContent);
  } catch (e) { /* si riparte comunque dallo stato chiesto qui sotto */ }

  drawInstances();
  drawTabs();
  drawScreens();
  updateLog();
  updateStatus();
  giroLog();
})();
