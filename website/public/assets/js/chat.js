// Chat live della home (modulo sopra la scheda giocatore).
// Legge e scrive su /api/chat; il ponte col gioco lo fa il plugin MagixWeb.
// Su tutte le altre pagine il modulo non esiste e questo file esce subito.
(function () {
  var box = document.getElementById('liveChat');
  if (!box) return;

  var lista = document.getElementById('chatMessages');
  var form = document.getElementById('chatForm');
  var input = document.getElementById('chatInput');
  var stato = document.getElementById('chatStato');
  var invio = form ? form.querySelector('.chat-send') : null;

  var csrf = box.getAttribute('data-csrf') || '';
  var puoEliminare = box.getAttribute('data-can-delete') === '1';
  var puoSvuotare = box.getAttribute('data-can-clear') === '1';
  var attesaBase = parseInt(box.getAttribute('data-poll'), 10) || 5000;
  var attesa = attesaBase;
  var ultimoId = 0;
  var vuoto = true;
  var timer = null;
  // Fin dove la chat e' stata svuotata l'ultima volta (lo dice il server a ogni giro).
  // Quando questo numero sale, vuol dire che qualcuno ha premuto il cestino: anche le
  // pagine rimaste aperte devono togliere dallo schermo i messaggi che non esistono piu'.
  var svuotataA = 0;

  // Icone di provenienza, disegnate qui come SVG inline: niente file/immagini esterne da
  // caricare e si adattano da sole alla dimensione del testo.
  // Globo = scritto dal sito, blocco d'erba = scritto in gioco.
  var ICONA_WEB = '<svg viewBox="0 0 16 16" fill="none" stroke="#5aa9e6" stroke-width="1.2"'
    + ' stroke-linecap="round"><circle cx="8" cy="8" r="6.2"/><ellipse cx="8" cy="8" rx="2.9" ry="6.2"/>'
    + '<path d="M1.9 8h12.2M3.1 4.7h9.8M3.1 11.3h9.8"/></svg>';
  var ICONA_GIOCO = '<svg viewBox="0 0 16 16" shape-rendering="crispEdges">'
    + '<rect x="1" y="4" width="14" height="11" fill="#8a5a2b"/>'
    + '<rect x="1" y="1" width="14" height="3" fill="#5fa832"/>'
    + '<rect x="2" y="1" width="3" height="1" fill="#76c93f"/>'
    + '<rect x="8" y="1" width="4" height="1" fill="#76c93f"/>'
    + '<rect x="3" y="6" width="2" height="2" fill="#7a4d22"/>'
    + '<rect x="9" y="9" width="2" height="2" fill="#7a4d22"/>'
    + '<rect x="6" y="12" width="2" height="2" fill="#9a6733"/></svg>';

  function statusMessage(text, errore) {
    stato.textContent = text || '';
    stato.classList.toggle('is-error', !!errore);
  }

  /** True se l'utente sta gia' guardando il fondo: solo allora seguiamo i nuovi messaggi. */
  function inFondo() {
    return lista.scrollHeight - lista.scrollTop - lista.clientHeight < 40;
  }

  function draw(msg) {
    var riga = document.createElement('div');
    riga.className = 'chat-msg ' + (msg.source === 'game' ? 'is-game' : 'is-web');
    riga.setAttribute('data-id', msg.id);

    // Riga unica come in gioco: [Fazione] Nome » messaggio, con icona e ora davanti.
    var icona = document.createElement('span');
    icona.className = 'chat-msg-icona';
    icona.innerHTML = msg.source === 'game' ? ICONA_GIOCO : ICONA_WEB;
    icona.title = msg.source === 'game' ? 'Scritto in gioco' : 'Scritto dal sito';

    var ora = document.createElement('span');
    ora.className = 'chat-msg-ora';
    ora.textContent = msg.time;

    var corpo = document.createElement('span');
    corpo.className = 'chat-msg-corpo';

    var nome = document.createElement('span');
    // name arriva dal server gia' escapato (chat_sender_html): nome e fazione sono passati
    // da h(), il resto sono i tag dei gradi generati da noi.
    nome.innerHTML = msg.name;

    var sep = document.createElement('span');
    sep.className = 'chat-sep';
    // Senza spazi dentro il testo: lo stacco lo danno i margini di .chat-sep, se no si
    // somma al padding del nome cliccabile e a sinistra si apre piu' che a destra.
    sep.textContent = '»';

    var text = document.createElement('span');
    text.className = 'chat-msg-testo';
    text.textContent = msg.text; // MAI innerHTML: il testo lo scrivono i giocatori

    corpo.appendChild(nome);
    corpo.appendChild(sep);
    corpo.appendChild(text);

    // Ora e provenienza non stanno piu' nella riga: vanno in una targhetta che compare
    // SOPRA il messaggio quando ci passi il mouse (o lo tocchi). Cosi' la riga resta pulita
    // come in gioco e l'informazione arriva solo quando la cerchi.
    var info = document.createElement('span');
    info.className = 'chat-msg-info';
    if (msg.faccia) {
      // faccia arriva dal server gia' pronta (immagine + eventuale corona del miglior
      // sostenitore): qui si infila e basta, la regola sta in un posto solo, in PHP.
      var faccia = document.createElement('span');
      faccia.className = 'chat-msg-faccia-box';
      faccia.innerHTML = msg.faccia;
      info.appendChild(faccia);
    }
    var provenienza = document.createElement('span');
    provenienza.className = 'chat-msg-provenienza';
    provenienza.textContent = msg.source === 'game' ? 'scritto in gioco' : 'scritto dal sito';

    // Due righe: la faccia sopra (grande, e' quello che si riconosce a colpo d'occhio),
    // sotto la riga con icona di provenienza, ora e descrizione.
    var rigaInfo = document.createElement('span');
    rigaInfo.className = 'chat-msg-info-riga';
    rigaInfo.appendChild(icona);
    rigaInfo.appendChild(ora);
    rigaInfo.appendChild(provenienza);
    info.appendChild(rigaInfo);

    riga.appendChild(corpo);
    riga.appendChild(info);

    // Col mouse ci pensa il :hover del foglio di stile; col dito il :hover non esiste,
    // quindi toccando il messaggio la targhetta compare (e ritoccandolo sparisce). Il tocco
    // sul pulsante di eliminazione non conta: quello ha il suo lavoro.
    riga.addEventListener('click', function (e) {
      if (e.target.closest('.chat-msg-del')) return;
      var giaAperta = riga.classList.contains('e-aperta');
      // Un solo popup alla volta: chiudi tutti gli altri, cosi' si vede solo la targhetta
      // dell'ultimo messaggio cliccato e non si accavallano.
      var aperti = lista.querySelectorAll('.chat-msg.e-aperta');
      for (var i = 0; i < aperti.length; i++) aperti[i].classList.remove('e-aperta');
      if (!giaAperta) riga.classList.add('e-aperta');
    });

    if (puoEliminare) {
      var del = document.createElement('button');
      del.type = 'button';
      del.className = 'chat-msg-del';
      del.title = 'Elimina messaggio';
      del.setAttribute('aria-label', 'Elimina messaggio');
      del.textContent = '✕';
      del.addEventListener('click', function () { remove(msg.id, riga); });
      riga.appendChild(del);
    }

    return riga;
  }

  function add(messaggi) {
    if (!messaggi.length) return;
    var seguiva = inFondo();
    if (vuoto) {
      lista.innerHTML = '';
      vuoto = false;
    }
    messaggi.forEach(function (m) {
      if (m.id > ultimoId) ultimoId = m.id;
      lista.appendChild(draw(m));
    });
    // Non teniamo in memoria una cronologia infinita: la pagina puo' restare aperta ore.
    while (lista.children.length > 120) lista.removeChild(lista.firstChild);
    if (seguiva) lista.scrollTop = lista.scrollHeight;
  }

  // Click in qualsiasi punto fuori da un messaggio della chat: chiude il popup aperto.
  // Il click SU un messaggio non arriva qui a chiuderlo perche' lo gestisce gia' la riga
  // (toggle), e qui saltiamo i target dentro .chat-msg.
  document.addEventListener('click', function (e) {
    if (e.target.closest('.chat-msg')) return;
    var aperti = lista.querySelectorAll('.chat-msg.e-aperta');
    for (var i = 0; i < aperti.length; i++) aperti[i].classList.remove('e-aperta');
  });

  /** Toglie tutto dall'elenco. L'avviso "ancora nessun messaggio" lo rimette carica(). */
  function ripulisciElenco() {
    lista.innerHTML = '';
    vuoto = true;
  }

  function remove(id, riga) {
    var dati = new URLSearchParams();
    dati.set('action', 'delete');
    dati.set('csrf', csrf);
    dati.set('id', id);
    fetch('/api/chat', { method: 'POST', body: dati, credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        if (d.ok) riga.remove();
        else statusMessage(d.error || 'Impossibile eliminare.', true);
      })
      .catch(function () { statusMessage('Impossibile eliminare.', true); });
  }

  function load() {
    fetch('/api/chat?after=' + ultimoId, { credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        if (!d.ok) throw new Error(d.error || 'errore');
        if (d.enabled === false) {
          box.style.display = 'none';
          return;
        }
        // Prima si butta via il vecchio, poi si aggiunge quello che il server manda adesso:
        // quello che arriva in questa stessa risposta e' successivo allo svuotamento.
        if (typeof d.purge === 'number' && d.purge > svuotataA) {
          svuotataA = d.purge;
          ripulisciElenco();
        }
        add(d.messages || []);
        if (vuoto && !lista.querySelector('.chat-vuoto-pronto')) {
          lista.innerHTML = '<p class="chat-vuoto chat-vuoto-pronto">Ancora nessun messaggio. Scrivi tu il primo!</p>';
        }
        attesa = attesaBase;
        if (stato.classList.contains('is-error')) statusMessage('');
      })
      .catch(function () {
        // Rete o server giu': rallentiamo invece di martellare, fino a 30 secondi.
        attesa = Math.min(attesa * 2, 30000);
        statusMessage('Chat non raggiungibile, riprovo…', true);
      })
      .then(function () { programma(); });
  }

  function programma() {
    clearTimeout(timer);
    timer = setTimeout(function () {
      // A scheda nascosta non ha senso interrogare il server: si riprende al ritorno.
      if (document.hidden) { programma(); return; }
      load();
    }, attesa);
  }

  if (form) {
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var text = input.value.trim();
      if (!text) return;

      invio.disabled = true;
      var dati = new URLSearchParams();
      dati.set('action', 'send');
      dati.set('csrf', csrf);
      dati.set('message', text);

      fetch('/api/chat', { method: 'POST', body: dati, credentials: 'same-origin' })
        .then(function (r) { return r.json(); })
        .then(function (d) {
          if (!d.ok) {
            statusMessage(d.error || 'Messaggio non inviato.', true);
            return;
          }
          input.value = '';
          statusMessage('');
          load(); // il proprio messaggio deve comparire subito, non al giro dopo
        })
        .catch(function () { statusMessage('Messaggio non inviato.', true); })
        .then(function () { invio.disabled = false; input.focus(); });
    });
  }

  // --- Svuota la chat (solo con il permesso chat.clear) ---------------------------------
  // Cancella davvero i messaggi dal database, quindi spariscono per tutti — anche per chi
  // ha la pagina gia' aperta, al giro d'aggiornamento dopo. Per questo si chiede conferma.
  var bottoneSvuota = document.getElementById('chatSvuota');
  if (puoSvuotare && bottoneSvuota) {
    bottoneSvuota.addEventListener('click', function () {
      if (!window.confirm('Svuotare tutta la chat?\n\nI messaggi spariscono per tutti e non si possono recuperare.')) return;

      bottoneSvuota.disabled = true;
      var dati = new URLSearchParams();
      dati.set('action', 'clear');
      dati.set('csrf', csrf);

      fetch('/api/chat', { method: 'POST', body: dati, credentials: 'same-origin' })
        .then(function (r) { return r.json(); })
        .then(function (d) {
          if (!d.ok) {
            statusMessage(d.error || 'Impossibile svuotare la chat.', true);
            return;
          }
          if (typeof d.purge === 'number') svuotataA = d.purge;
          ripulisciElenco();
          statusMessage('Chat svuotata.');
          // L'avviso non e' un errore, quindi il giro d'aggiornamento non lo toglie da solo:
          // se ne va da se' dopo qualche secondo, se nel frattempo non e' cambiato.
          setTimeout(function () {
            if (stato.textContent === 'Chat svuotata.') statusMessage('');
          }, 4000);
          load(); // rimette subito l'elenco a posto (vuoto, o con quello che nasce ora)
        })
        .catch(function () { statusMessage('Impossibile svuotare la chat.', true); })
        .then(function () { bottoneSvuota.disabled = false; });
    });
  }

  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) { attesa = attesaBase; load(); }
  });

  // --- "Ingrandisci": la chat esce dalla colonna e si allarga sopra gli articoli --------
  // Il riquadro NON viene duplicato: lo stesso elemento viene spostato nella riga larga
  // (#chatRiga) e poi rimesso al suo posto, quindi l'aggiornamento dei messaggi, l'invio e
  // l'eliminazione continuano senza accorgersi di nulla.
  // L'animazione e' la tecnica FLIP: si misura dov'era, si sposta, si misura dov'e' finito
  // e si parte dalla vecchia posizione per arrivare alla nuova. Cosi' il riquadro sembra
  // scivolare verso sinistra e crescere, mentre il resto della pagina scende.
  var bottoneGrande = document.getElementById('chatIngrandisci');
  var rigaLarga = document.getElementById('chatRiga');
  var casa = box.parentNode;                       // la colonna di destra
  var segnaposto = box.nextElementSibling;          // per rimetterla esattamente dov'era
  var animazione = null;

  function animaSpostamento(prima) {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches || !box.animate) return;
    var dopo = box.getBoundingClientRect();
    var sx = prima.width / dopo.width;
    var sy = prima.height / dopo.height;
    var dx = prima.left - dopo.left;
    var dy = prima.top - dopo.top;
    // Se si preme di nuovo mentre e' ancora in movimento, la vecchia animazione si annulla
    // e si riparte da dove si trova: nessun blocco del pulsante.
    if (animazione) animazione.cancel();
    animazione = box.animate(
      [
        { transform: 'translate(' + dx + 'px,' + dy + 'px) scale(' + sx + ',' + sy + ')', transformOrigin: 'top left' },
        { transform: 'none', transformOrigin: 'top left' }
      ],
      { duration: 320, easing: 'cubic-bezier(.2,.7,.3,1)' }
    );
  }

  function ingrandisci(apri) {
    if (!bottoneGrande || !rigaLarga) return;
    var prima = box.getBoundingClientRect();

    if (apri) {
      rigaLarga.appendChild(box);
    } else if (segnaposto && segnaposto.parentNode === casa) {
      casa.insertBefore(box, segnaposto);
    } else {
      casa.appendChild(box);
    }
    box.classList.toggle('is-ingrandita', apri);

    bottoneGrande.setAttribute('aria-pressed', apri ? 'true' : 'false');
    bottoneGrande.textContent = apri ? '⤡' : '⤢';
    bottoneGrande.title = apri ? 'Riduci' : 'Ingrandisci';
    bottoneGrande.setAttribute('aria-label', apri ? 'Riduci la chat' : 'Ingrandisci la chat');

    animaSpostamento(prima);
    // In tutti e due i versi cambia l'altezza dell'elenco: si torna in fondo, dove sono
    // i messaggi nuovi.
    lista.scrollTop = lista.scrollHeight;
    if (apri && input) input.focus();
  }

  if (bottoneGrande && rigaLarga) {
    bottoneGrande.addEventListener('click', function () {
      ingrandisci(!box.classList.contains('is-ingrandita'));
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && box.classList.contains('is-ingrandita')) ingrandisci(false);
    });
  }

  load();
})();
