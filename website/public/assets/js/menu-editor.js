// Scheda "Menu di gioco": si disegna un menu di MagixMenus come lo vedrà chi gioca.
// Parla solo con /api/menu; i file li scrive magix-console sulla macchina.
// Su tutte le altre pagine questo file esce subito.
//
// COME E' FATTO
//   - i menu li LEGGE il plugin, non il sito: qui arriva menus.json, cioe' quello che il
//     server ha davvero capito dei file. Se un menu ha un errore, l'editor lo mostra uguale
//     al server, perche' e' la stessa lettura.
//   - il modello in memoria ha la stessa forma di menus.json: si modifica quello, e al
//     salvataggio il PHP lo riscrive in .yml. Nessuna traduzione a meta' strada.
//   - le tendine (tipi di menu, requisiti, azioni, item, incantesimi, suoni) vengono dal
//     catalogo che pubblica il plugin: non possono offrire qualcosa che il plugin non sa fare.
(function () {
  var radice = document.getElementById('menuEditor');
  if (!radice) return;

  var csrf = radice.getAttribute('data-csrf') || '';
  var app = document.getElementById('menuApp');
  var avvisi = document.getElementById('menuAvvisi');
  var statoTesto = document.getElementById('menuStato');

  var dati = null;          // { menu: {...}, catalogo: {...} }
  var menu = null;          // il menu in modifica (copia di lavoro)
  var nomeMenu = '';
  var sceltoItem = -1;      // quale item e' selezionato, come indice dell'elenco
  var schedaAperta = 'aspetto';
  var modificato = false;
  var atlante = null;       // quali item hanno una texture (vedi piu' sotto)
  var fileAperto = false;   // il pannello che mostra il .yml com'e' scritto sul server
  var testoFile = null;     // il contenuto, appena letto

  // ------------------------------------------------------------------
  //  Utilita'
  // ------------------------------------------------------------------
  function el(tag, cls, testo) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (testo !== undefined && testo !== null) e.textContent = testo;
    return e;
  }

  function svuota(nodo) {
    while (nodo.firstChild) nodo.removeChild(nodo.firstChild);
    return nodo;
  }

  function copia(x) {
    return JSON.parse(JSON.stringify(x));
  }

  function avviso(testo, errore) {
    svuota(avvisi);
    if (!testo) return;
    var d = el('div', 'alert alert-' + (errore ? 'error' : 'success'), testo);
    avvisi.appendChild(d);
    if (!errore) setTimeout(function () { if (d.parentNode) d.parentNode.removeChild(d); }, 6000);
    else avvisi.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }

  function api(azione, corpo) {
    var opzioni;
    if (corpo) {
      var f = new FormData();
      f.append('csrf', csrf);
      f.append('azione', azione);
      Object.keys(corpo).forEach(function (k) { f.append(k, corpo[k]); });
      opzioni = { method: 'POST', body: f, credentials: 'same-origin' };
      return fetch('/api/menu', opzioni).then(rispondi);
    }
    return fetch('/api/menu?' + azione, { credentials: 'same-origin' }).then(rispondi);
  }

  function rispondi(r) {
    return r.json().catch(function () {
      throw new Error('Il server ha risposto in un modo che non capisco.');
    }).then(function (d) {
      if (!d || !d.ok) throw new Error((d && d.error) || 'Operazione non riuscita.');
      return d;
    });
  }

  function segnaModificato() {
    modificato = true;
    var b = document.getElementById('meSalva');
    if (b) b.classList.add('btn-accent');
    if (statoTesto) statoTesto.textContent = 'modifiche non salvate';
  }

  window.addEventListener('beforeunload', function (e) {
    if (!modificato) return;
    e.preventDefault();
    e.returnValue = '';
  });

  // ------------------------------------------------------------------
  //  I colori di Minecraft, come si vedranno in gioco
  // ------------------------------------------------------------------
  //  Non e' un vezzo: un nome scritto "&#C046E8&lSpada" sulla pagina e' illeggibile, e chi
  //  costruisce il menu non ha modo di sapere che aspetto avra' finche' non entra in gioco.
  var TAVOLOZZA = {
    '0': '#000000', '1': '#0000AA', '2': '#00AA00', '3': '#00AAAA',
    '4': '#AA0000', '5': '#AA00AA', '6': '#FFAA00', '7': '#AAAAAA',
    '8': '#555555', '9': '#5555FF', 'a': '#55FF55', 'b': '#55FFFF',
    'c': '#FF5555', 'd': '#FF55FF', 'e': '#FFFF55', 'f': '#FFFFFF'
  };

  function coloraInHtml(testo) {
    testo = String(testo === undefined || testo === null ? '' : testo);
    var out = '', colore = '#DDDDDD', grassetto = false, corsivo = false, barrato = false, sottolineato = false;
    var pezzo = '';
    function chiudi() {
      if (!pezzo) return;
      var stile = 'color:' + colore;
      if (grassetto) stile += ';font-weight:700';
      if (corsivo) stile += ';font-style:italic';
      if (barrato || sottolineato) {
        stile += ';text-decoration:' + (barrato ? 'line-through' : '') + (sottolineato ? ' underline' : '');
      }
      var span = document.createElement('span');
      span.setAttribute('style', stile);
      span.textContent = pezzo;
      out += span.outerHTML;
      pezzo = '';
    }
    for (var i = 0; i < testo.length; i++) {
      var c = testo[i];
      if (c === '&' && i + 1 < testo.length) {
        var p = testo[i + 1];
        if (p === '#' && /^[0-9a-fA-F]{6}$/.test(testo.substr(i + 2, 6))) {
          chiudi(); colore = '#' + testo.substr(i + 2, 6); i += 7; continue;
        }
        var basso = p.toLowerCase();
        if (TAVOLOZZA[basso]) { chiudi(); colore = TAVOLOZZA[basso]; grassetto = corsivo = barrato = sottolineato = false; i++; continue; }
        if (basso === 'l') { chiudi(); grassetto = true; i++; continue; }
        if (basso === 'o') { chiudi(); corsivo = true; i++; continue; }
        if (basso === 'm') { chiudi(); barrato = true; i++; continue; }
        if (basso === 'n') { chiudi(); sottolineato = true; i++; continue; }
        if (basso === 'r') { chiudi(); colore = '#DDDDDD'; grassetto = corsivo = barrato = sottolineato = false; i++; continue; }
      }
      pezzo += c;
    }
    chiudi();
    return out || '<span style="color:#DDDDDD"></span>';
  }

  /**
   * Spezza un testo coi codici in TRATTI: {testo, colore, grassetto, corsivo, barrato,
   * sottolineato, illeggibile}.
   *
   * E' la stessa lettura che fa Minecraft: un codice vale da dove sta in poi, e un codice di
   * COLORE azzera anche i formati (per questo, riapplicandolo, i formati vanno riscritti).
   */
  function inTratti(testo) {
    testo = String(testo === undefined || testo === null ? '' : testo);
    var tratti = [];
    var stato = { colore: null, grassetto: false, corsivo: false, barrato: false, sottolineato: false, illeggibile: false };
    var pezzo = '';

    function chiudi() {
      if (pezzo === '') return;
      tratti.push({
        testo: pezzo, colore: stato.colore, grassetto: stato.grassetto, corsivo: stato.corsivo,
        barrato: stato.barrato, sottolineato: stato.sottolineato, illeggibile: stato.illeggibile
      });
      pezzo = '';
    }

    for (var i = 0; i < testo.length; i++) {
      var c = testo[i];
      if (c === '&' && i + 1 < testo.length) {
        var pr = testo[i + 1];
        if (pr === '#' && /^[0-9a-fA-F]{6}$/.test(testo.substr(i + 2, 6))) {
          chiudi();
          stato = { colore: '&#' + testo.substr(i + 2, 6).toUpperCase(), grassetto: false, corsivo: false, barrato: false, sottolineato: false, illeggibile: false };
          i += 7; continue;
        }
        var b = pr.toLowerCase();
        if (TAVOLOZZA[b]) {
          chiudi();
          stato = { colore: '&' + b, grassetto: false, corsivo: false, barrato: false, sottolineato: false, illeggibile: false };
          i++; continue;
        }
        if (b === 'l') { chiudi(); stato.grassetto = true; i++; continue; }
        if (b === 'o') { chiudi(); stato.corsivo = true; i++; continue; }
        if (b === 'm') { chiudi(); stato.barrato = true; i++; continue; }
        if (b === 'n') { chiudi(); stato.sottolineato = true; i++; continue; }
        if (b === 'k') { chiudi(); stato.illeggibile = true; i++; continue; }
        if (b === 'r') {
          chiudi();
          stato = { colore: null, grassetto: false, corsivo: false, barrato: false, sottolineato: false, illeggibile: false };
          i++; continue;
        }
      }
      pezzo += c;
    }
    chiudi();
    return tratti;
  }

  var FORMATI_TRATTO = [['grassetto', '&l'], ['corsivo', '&o'], ['barrato', '&m'],
    ['sottolineato', '&n'], ['illeggibile', '&k']];

  /** Dai tratti al testo coi codici, scrivendo solo i codici che servono davvero. */
  function daTratti(tratti) {
    var out = '';
    var prima = { colore: null, grassetto: false, corsivo: false, barrato: false, sottolineato: false, illeggibile: false };
    tratti.forEach(function (t) {
      if (t.testo === '') return;
      var perde = FORMATI_TRATTO.some(function (f) { return prima[f[0]] && !t[f[0]]; });
      var coloreDiverso = (t.colore || null) !== (prima.colore || null);
      // L'&r si scrive solo quando serve davvero: un codice di COLORE azzera gia' i formati
      // per conto suo, quindi davanti a un colore nuovo l'&r sarebbe rumore nel file.
      var serveAzzerare = (perde && !(coloreDiverso && t.colore)) || (coloreDiverso && !t.colore);
      if (serveAzzerare) {
        out += '&r';
        prima = { colore: null, grassetto: false, corsivo: false, barrato: false, sottolineato: false, illeggibile: false };
        coloreDiverso = (t.colore || null) !== null;
      }
      if (coloreDiverso && t.colore) {
        out += t.colore;
        // Un codice di colore azzera i formati: quelli che restano vanno riscritti dopo.
        prima = { colore: t.colore, grassetto: false, corsivo: false, barrato: false, sottolineato: false, illeggibile: false };
      }
      FORMATI_TRATTO.forEach(function (f) {
        if (t[f[0]] && !prima[f[0]]) { out += f[1]; prima[f[0]] = true; }
      });
      out += t.testo;
    });
    return out;
  }

  function stileTratto(t) {
    var st = 'color:' + (t.colore ? (t.colore.charAt(1) === '#' ? '#' + t.colore.slice(2) : TAVOLOZZA[t.colore.charAt(1)]) : '#DDDDDD');
    if (t.grassetto) st += ';font-weight:700';
    if (t.corsivo) st += ';font-style:italic';
    if (t.barrato || t.sottolineato) {
      st += ';text-decoration:' + (t.barrato ? 'line-through' : '') + (t.sottolineato ? ' underline' : '');
    }
    if (t.illeggibile) st += ';opacity:0.65;letter-spacing:1px';
    return st;
  }

  function senzaColori(testo) {
    return String(testo || '').replace(/&#[0-9a-fA-F]{6}|&[0-9a-fk-orA-FK-OR]/g, '');
  }

  // ------------------------------------------------------------------
  //  La barra degli strumenti del testo
  // ------------------------------------------------------------------
  //  Compare sotto il campo su cui si sta scrivendo e sparisce appena si va altrove: una
  //  barra fissa sotto ognuno dei venti campi di questa pagina sarebbe piu' ingombrante di
  //  quello che si sta scrivendo.
  //
  //  I codici di Minecraft non sono tag che si aprono e si chiudono: valgono da dove si
  //  scrivono in poi. Quindi con del testo SELEZIONATO il colore lo si mette prima e si chiude
  //  con &r (che e' quello che uno si aspetta da una barra del genere); senza selezione lo si
  //  infila dove sta il cursore e vale fino alla fine della riga.

  /** I colori del marchio, quelli di STILE-MAGIX: sono i primi perche' sono quelli giusti. */
  var COLORI_MARCHIO = [
    ['&#C046E8', '#C046E8', 'viola del logo — chi parla, i titoli'],
    ['&#A8DC2C', '#A8DC2C', 'verde del logo — quello che si può fare'],
    ['&#9A8CA8', '#9A8CA8', 'grigio — le spiegazioni'],
    ['&#5A5068', '#5A5068', 'tenue — cornici e note spente'],
    ['&#FF6B6B', '#FF6B6B', 'rosso — non ha funzionato'],
    ['&#FFD166', '#FFD166', 'giallo — attenzione']
  ];

  /** I sedici colori storici di Minecraft: piu' brutti ma li capiscono tutti i client. */
  var COLORI_CLASSICI = '0123456789abcdef'.split('').map(function (c) {
    return ['&' + c, TAVOLOZZA[c], 'colore classico &' + c];
  });

  var FORMATI = [
    ['&l', 'G', 'grassetto', 'font-weight:700'],
    ['&o', 'C', 'corsivo', 'font-style:italic'],
    ['&n', 'S', 'sottolineato', 'text-decoration:underline'],
    ['&m', 'B', 'barrato', 'text-decoration:line-through'],
    ['&k', '?', 'illeggibile (le lettere ballano)', 'letter-spacing:1px'],
    ['&r', '↺', 'da qui in poi torna normale', 'opacity:0.8']
  ];

  function inserisciCodice(campo, codice) {
    if (campo.getAttribute && campo.getAttribute('data-scritto')) {
      applicaVisuale(campo, codice);
      return;
    }
    var inizio = campo.selectionStart, fine = campo.selectionEnd, v = campo.value;
    var chiude = codice !== '&r' && fine > inizio;
    campo.value = v.slice(0, inizio) + codice + v.slice(inizio, fine) + (chiude ? '&r' : '') + v.slice(fine);
    var dopo = inizio + codice.length + (fine - inizio) + (chiude ? 2 : 0);
    campo.focus();
    campo.setSelectionRange(dopo, dopo);
    // L'evento va lanciato a mano: il modello si aggiorna ascoltando "input", e scrivere
    // dentro .value da codice non ne fa scattare nessuno.
    campo.dispatchEvent(new Event('input', { bubbles: true }));
  }

  function pastiglia(campo, codice, colore, titolo) {
    var b = el('button', 'me-pastiglia');
    b.type = 'button';
    b.title = titolo;
    b.style.background = colore;
    // mousedown + preventDefault: senza, il campo perde il fuoco (e la selezione) prima
    // ancora che il clic arrivi, e il codice finirebbe chissa' dove.
    b.addEventListener('mousedown', function (e) {
      e.preventDefault();
      inserisciCodice(campo, codice);
    });
    return b;
  }

  function costruisciBarra(campo) {
    var barra = el('div', 'me-barra-testo');

    var gruppoMarchio = el('div', 'me-gruppo');
    COLORI_MARCHIO.forEach(function (c) {
      gruppoMarchio.appendChild(pastiglia(campo, c[0], c[1], c[2]));
    });
    barra.appendChild(gruppoMarchio);

    var gruppoClassici = el('div', 'me-gruppo');
    COLORI_CLASSICI.forEach(function (c) {
      gruppoClassici.appendChild(pastiglia(campo, c[0], c[1], c[2]));
    });
    barra.appendChild(gruppoClassici);

    // Il selettore vero del browser, per un colore che non e' fra quelli proposti.
    var scelta = el('label', 'me-gruppo me-scelta-colore');
    var pennello = el('input');
    pennello.type = 'color';
    pennello.value = '#C046E8';
    pennello.title = 'Un colore qualsiasi';
    pennello.addEventListener('mousedown', function () {
      // Il selettore di colore ruba il fuoco per forza: ci si segna dov'era il cursore.
      campo.dataset.cursore = campo.selectionStart + ':' + campo.selectionEnd;
    });
    pennello.addEventListener('input', function () {
      var segnato = (campo.dataset.cursore || '').split(':');
      if (segnato.length === 2) {
        campo.setSelectionRange(parseInt(segnato[0], 10), parseInt(segnato[1], 10));
      }
      inserisciCodice(campo, '&' + pennello.value.toUpperCase());
    });
    scelta.appendChild(pennello);
    barra.appendChild(scelta);

    var gruppoFormati = el('div', 'me-gruppo');
    FORMATI.forEach(function (f) {
      var b = el('button', 'me-formato', f[1]);
      b.type = 'button';
      b.title = f[2];
      b.setAttribute('style', f[3]);
      b.addEventListener('mousedown', function (e) {
        e.preventDefault();
        inserisciCodice(campo, f[0]);
      });
      gruppoFormati.appendChild(b);
    });

    var pulisci = el('button', 'me-formato', 'Aa');
    pulisci.type = 'button';
    pulisci.title = 'Togli tutti i colori e i formati da questo campo';
    pulisci.addEventListener('mousedown', function (e) {
      e.preventDefault();
      if (campo.getAttribute && campo.getAttribute('data-scritto')) {
        disegnaTratti(campo, senzaColori(leggiTratti(campo)));
        campo.dispatchEvent(new Event('input', { bubbles: true }));
        return;
      }
      campo.value = senzaColori(campo.value);
      campo.dispatchEvent(new Event('input', { bubbles: true }));
    });
    gruppoFormati.appendChild(pulisci);
    barra.appendChild(gruppoFormati);

    return barra;
  }

  /**
   * Il campo che mostra il testo GIA' colorato, al posto dei codici.
   *
   * Torna l'elemento da mettere nel modulo. Chi lo usa non vede mai un "&#A8DC2C": scrive, e
   * per colorare o mettere in grassetto usa la barra qui sopra. I codici restano raggiungibili
   * con l'interruttore, perche' nascondere una cosa non deve volere dire renderla irraggiungibile.
   */
  function campoScritto(valore, cambia, multiriga) {
    var scatola = el('div', 'me-scritto');

    var visuale = el('div', 'me-inp me-visuale' + (multiriga ? ' me-visuale-alta' : ''));
    visuale.contentEditable = 'true';
    visuale.spellcheck = false;
    visuale.setAttribute('data-scritto', '1');
    // Un contenteditable non ha il "placeholder": il suggerimento lo disegna il CSS quando
    // l'elemento e' vuoto (vedi .me-visuale:empty::before).
    disegnaTratti(visuale, valore);

    var grezzo = el('textarea', 'me-inp me-area me-grezzo');
    grezzo.rows = multiriga ? 4 : 2;
    grezzo.value = valore || '';
    grezzo.style.display = 'none';

    // --- si scrive nel visuale: si rilegge, senza toccare il DOM (il cursore non deve saltare)
    visuale.addEventListener('input', function () {
      var v = leggiTratti(visuale);
      grezzo.value = v;
      cambia(v);
    });
    // A capo dentro un campo a riga sola: non ha senso e sporcherebbe il testo.
    visuale.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !multiriga) e.preventDefault();
    });
    // Incollare porta dentro il formato di provenienza: si tiene solo il testo.
    visuale.addEventListener('paste', function (e) {
      e.preventDefault();
      var t = (e.clipboardData || window.clipboardData).getData('text');
      document.execCommand('insertText', false, String(t).replace(/\r?\n/g, multiriga ? '\n' : ' '));
    });

    grezzo.addEventListener('input', function () {
      cambia(grezzo.value);
    });

    scatola.appendChild(visuale);
    scatola.appendChild(grezzo);

    var interruttore = el('button', 'me-interruttore', '⟨⟩ codici');
    interruttore.type = 'button';
    interruttore.title = 'Mostra i codici scritti per esteso (&#A8DC2C, &l…)';
    interruttore.addEventListener('click', function () {
      var siVede = grezzo.style.display !== 'none';
      if (siVede) {
        disegnaTratti(visuale, grezzo.value);   // tornando al visuale si rilegge cio' che c'e' scritto
        grezzo.style.display = 'none';
        visuale.style.display = '';
        interruttore.textContent = '⟨⟩ codici';
      } else {
        grezzo.value = leggiTratti(visuale);
        visuale.style.display = 'none';
        grezzo.style.display = '';
        interruttore.textContent = '✎ testo';
      }
    });
    scatola.appendChild(interruttore);

    scatola.campo = visuale;
    scatola.grezzo = grezzo;
    return scatola;
  }

  /** Disegna il testo coi codici come tratti colorati dentro il campo. */
  function disegnaTratti(host, valore) {
    svuota(host);
    var tratti = inTratti(valore);
    if (!tratti.length) return;
    tratti.forEach(function (t) {
      var sp = document.createElement('span');
      sp.setAttribute('style', stileTratto(t));
      // Lo stato viaggia con il pezzo di testo: cosi' scrivendoci dentro non si perde.
      sp.setAttribute('data-t', JSON.stringify({
        c: t.colore, g: t.grassetto ? 1 : 0, i: t.corsivo ? 1 : 0,
        b: t.barrato ? 1 : 0, s: t.sottolineato ? 1 : 0, k: t.illeggibile ? 1 : 0
      }));
      sp.textContent = t.testo;
      host.appendChild(sp);
    });
  }

  /** Rilegge dal campo il testo coi codici. */
  function leggiTratti(host) {
    var tratti = [];
    Array.prototype.forEach.call(host.childNodes, function (n) {
      if (n.nodeType === 3) {
        // Testo scritto fuori da ogni tratto (capita in fondo o all'inizio): nessun formato.
        tratti.push({ testo: n.nodeValue, colore: null, grassetto: false, corsivo: false, barrato: false, sottolineato: false, illeggibile: false });
        return;
      }
      if (n.nodeType !== 1) return;
      if (n.tagName === 'BR') { tratti.push({ testo: '\n', colore: null }); return; }
      var d = {};
      try { d = JSON.parse(n.getAttribute('data-t') || '{}'); } catch (x) { d = {}; }
      tratti.push({
        testo: n.textContent, colore: d.c || null, grassetto: !!d.g, corsivo: !!d.i,
        barrato: !!d.b, sottolineato: !!d.s, illeggibile: !!d.k
      });
    });
    return daTratti(tratti);
  }

  /**
   * Applica un codice al testo scelto dentro un campo visuale.
   *
   * Senza selezione vale dal cursore in avanti: e' come si comportano davvero i codici di
   * Minecraft, e quasi sempre e' quello che si vuole ("da qui in poi, verde").
   */
  function applicaVisuale(host, codice) {
    var raw = leggiTratti(host);
    var tratti = inTratti(raw);
    var sel = window.getSelection();
    var da = 0, a = testoIntero(tratti).length;

    if (sel && sel.rangeCount && host.contains(sel.anchorNode)) {
      var r = sel.getRangeAt(0);
      da = posizioneIn(host, r.startContainer, r.startOffset);
      a = r.collapsed ? testoIntero(tratti).length : posizioneIn(host, r.endContainer, r.endOffset);
    }
    if (a <= da) a = testoIntero(tratti).length;

    var nuovi = [];
    var scorre = 0;
    tratti.forEach(function (t) {
      var inizio = scorre, fine = scorre + t.testo.length;
      scorre = fine;
      // Il tratto va spezzato dove comincia e dove finisce la parte scelta.
      [[inizio, Math.min(fine, Math.max(inizio, da))], [Math.max(inizio, Math.min(fine, da)), Math.min(fine, Math.max(inizio, a))], [Math.max(inizio, Math.min(fine, a)), fine]]
        .forEach(function (p, quale) {
          if (p[1] <= p[0]) return;
          var pezzo = Object.assign({}, t, { testo: t.testo.slice(p[0] - inizio, p[1] - inizio) });
          if (quale === 1) applicaCodiceA(pezzo, codice);
          nuovi.push(pezzo);
        });
    });
    if (!tratti.length) {
      var vuoto = { testo: '', colore: null, grassetto: false, corsivo: false, barrato: false, sottolineato: false, illeggibile: false };
      applicaCodiceA(vuoto, codice);
      nuovi.push(vuoto);
    }
    disegnaTratti(host, daTratti(nuovi));
    host.dispatchEvent(new Event('input', { bubbles: true }));
    host.focus();
  }

  function testoIntero(tratti) {
    return tratti.map(function (t) { return t.testo; }).join('');
  }

  /** Da (nodo, offset) alla posizione dentro il testo intero del campo. */
  function posizioneIn(host, nodo, offset) {
    var n = 0, fatto = false;
    (function scendi(x) {
      if (fatto) return;
      if (x === nodo && x.nodeType === 3) { n += offset; fatto = true; return; }
      if (x.nodeType === 3) { n += x.nodeValue.length; return; }
      for (var i = 0; i < x.childNodes.length; i++) {
        if (!fatto && x === nodo && i === offset) { fatto = true; return; }
        scendi(x.childNodes[i]);
        if (fatto) return;
      }
      if (!fatto && x === nodo) fatto = true;
    })(host);
    return n;
  }

  function applicaCodiceA(t, codice) {
    if (codice === '&r') {
      t.colore = null; t.grassetto = t.corsivo = t.barrato = t.sottolineato = t.illeggibile = false;
      return;
    }
    var b = codice.charAt(1).toLowerCase();
    if (codice.charAt(1) === '#' || TAVOLOZZA[b]) { t.colore = codice.charAt(1) === '#' ? codice.toUpperCase() : '&' + b; return; }
    if (b === 'l') t.grassetto = true;
    else if (b === 'o') t.corsivo = true;
    else if (b === 'm') t.barrato = true;
    else if (b === 'n') t.sottolineato = true;
    else if (b === 'k') t.illeggibile = true;
  }

  /**
   * Rende "colorabile" un campo: barra sotto quando ci si scrive, anteprima sempre.
   *
   * @param dentro il contenitore del campo, dove infilare barra e anteprima
   */
  function abilitaColori(campo, dentro) {
    // Il campo visuale mostra gia' il testo com'e': una riga di anteprima sotto direbbe la
    // stessa cosa due volte. Resta solo per i campi che mostrano i codici per esteso.
    if (!(campo.getAttribute && campo.getAttribute('data-scritto'))) {
      var anteprima = el('div', 'me-riga-colorata');
      var aggiorna = function () {
        anteprima.innerHTML = campo.value ? coloraInHtml(campo.value.split('\n')[0]) : '';
        anteprima.style.display = campo.value ? '' : 'none';
      };
      aggiorna();
      campo.addEventListener('input', aggiorna);
      dentro.appendChild(anteprima);
    }

    var barra = null;
    campo.addEventListener('focus', function () {
      if (barra) return;
      barra = costruisciBarra(campo);
      dentro.appendChild(barra);
    });
    campo.addEventListener('blur', function () {
      // Un attimo di respiro: il clic su una pastiglia toglie il fuoco per un istante, e
      // togliendo la barra subito il clic finirebbe nel vuoto.
      setTimeout(function () {
        if (barra && document.activeElement !== campo && !barra.contains(document.activeElement)) {
          barra.remove();
          barra = null;
        }
      }, 150);
    });
  }

  // ------------------------------------------------------------------
  //  Le icone degli item
  // ------------------------------------------------------------------
  //  Le texture vere stanno in /assets/img/item/, una per item, estratte dal client con
  //  vps/icone-item.py. L'elenco di quelle che ESISTONO si legge una volta sola: senza,
  //  ogni item senza icona lascerebbe nella console del browser un errore 404 per uno.
  //
  //  Chi non ce l'ha (bandiere, letti, qualche blocco con un modello suo) prende una
  //  piastrella col nome: brutta ma leggibile. L'editor funziona comunque — le icone sono una
  //  comodita', non un pezzo del meccanismo.
  fetch('/assets/img/item/elenco.json', { credentials: 'same-origin' })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (a) {
      if (!a || !a.icone) return;
      atlante = {};
      for (var i = 0; i < a.icone.length; i++) atlante[a.icone[i]] = true;
      ridisegnaIcone();
    })
    .catch(function () { /* niente icone: si resta alle piastrelle */ });

  function ridisegnaIcone() {
    if (menu) disegnaEditor();
    else if (dati) disegnaElenco();
  }

  function tinta(id) {
    var n = 0;
    for (var i = 0; i < id.length; i++) n = (n * 31 + id.charCodeAt(i)) % 360;
    return 'hsl(' + n + ' 45% 32%)';
  }

  function icona(id, misura) {
    id = String(id || 'STONE').toUpperCase().replace(/^MINECRAFT:/, '');
    misura = misura || 32;
    if (atlante && atlante[id]) {
      var img = el('img', 'me-icona');
      img.src = '/assets/img/item/' + encodeURIComponent(id) + '.png';
      img.width = img.height = misura;
      img.alt = '';
      img.title = id;
      img.loading = 'lazy';
      return img;
    }
    var d = el('span', 'me-icona me-icona-testo');
    d.style.width = d.style.height = misura + 'px';
    d.style.background = tinta(id);
    d.textContent = id.slice(0, 2);
    d.title = id;
    return d;
  }

  // ------------------------------------------------------------------
  //  Avvio
  // ------------------------------------------------------------------
  carica();

  function carica() {
    api('azione=elenco').then(function (d) {
      dati = d;
      disegnaElenco();
    }).catch(function (e) {
      svuota(app);
      app.appendChild(el('div', 'alert alert-error', e.message));
      app.appendChild(el('p', 'muted', 'I menu si leggono dal server: se è spento, o se MagixMenus non è mai partito, qui non c’è ancora niente da mostrare.'));
    });
  }

  // ------------------------------------------------------------------
  //  L'elenco dei menu
  // ------------------------------------------------------------------
  function disegnaElenco() {
    menu = null;
    modificato = false;
    if (statoTesto) statoTesto.textContent = '';
    svuota(app);

    var testata = el('div', 'me-testata');
    var sinistra = el('div', 'me-testata-sx');
    sinistra.appendChild(el('h3', null, 'I menu del server'));
    var quanti = Object.keys(dati.menu).length;
    sinistra.appendChild(el('span', 'muted', quanti + (quanti === 1 ? ' menu' : ' menu') + ' · aggiornato dal server ' + (dati.generato || '').replace('T', ' ').slice(0, 16)));
    testata.appendChild(sinistra);

    var destra = el('div', 'me-testata-dx');
    var nuovo = el('button', 'btn btn-accent btn-small', '+ Nuovo menu');
    nuovo.type = 'button';
    nuovo.addEventListener('click', creaNuovo);
    destra.appendChild(nuovo);
    var applica = el('button', 'btn btn-ghost btn-small', 'Applica al server');
    applica.type = 'button';
    applica.addEventListener('click', function () { applicaAlServer(applica); });
    destra.appendChild(applica);
    testata.appendChild(destra);
    app.appendChild(testata);

    var elenco = el('div', 'me-elenco');
    Object.keys(dati.menu).sort().forEach(function (nome) {
      var m = dati.menu[nome];
      var card = el('div', 'me-card' + (m.errori && m.errori.length ? ' me-card-errore' : ''));

      var cima = el('div', 'me-card-cima');
      cima.appendChild(icona(primoItemId(m), 28));
      var titolo = el('div', 'me-card-titolo');
      titolo.appendChild(el('strong', null, nome));
      var sotto = el('span', 'muted');
      sotto.innerHTML = coloraInHtml(m.titolo || '');
      titolo.appendChild(sotto);
      cima.appendChild(titolo);
      card.appendChild(cima);

      var righe = el('div', 'me-card-righe');
      righe.appendChild(el('span', 'me-tag', m.tipo + (m.tipo === 'chest' ? ' · ' + m.righe + ' righe' : '')));
      righe.appendChild(el('span', 'me-tag', (m.item ? m.item.length : 0) + ' item'));
      if (m.comandi && m.comandi.length) righe.appendChild(el('span', 'me-tag', '/' + m.comandi.join(' /')));
      if (m.permesso) righe.appendChild(el('span', 'me-tag', m.permesso));
      if (m.aggiornamento) righe.appendChild(el('span', 'me-tag', 'ogni ' + m.aggiornamento + ' tick'));
      card.appendChild(righe);

      if (m.errori && m.errori.length) {
        var err = el('div', 'me-card-problemi');
        err.appendChild(el('strong', null, m.errori.length + (m.errori.length === 1 ? ' problema' : ' problemi')));
        m.errori.slice(0, 3).forEach(function (e) { err.appendChild(el('div', null, '• ' + e)); });
        if (m.errori.length > 3) err.appendChild(el('div', 'muted', '…e altri ' + (m.errori.length - 3)));
        card.appendChild(err);
      }

      var azioni = el('div', 'me-card-azioni');
      var modifica = el('button', 'btn btn-small', 'Modifica');
      modifica.type = 'button';
      modifica.addEventListener('click', function () { apri(nome); });
      azioni.appendChild(modifica);

      var duplica = el('button', 'btn btn-ghost btn-small', 'Duplica');
      duplica.type = 'button';
      duplica.addEventListener('click', function () { duplicaMenu(nome); });
      azioni.appendChild(duplica);

      var elimina = el('button', 'btn btn-ghost btn-small', 'Elimina');
      elimina.type = 'button';
      elimina.addEventListener('click', function () { eliminaMenu(nome); });
      azioni.appendChild(elimina);
      card.appendChild(azioni);

      elenco.appendChild(card);
    });
    app.appendChild(elenco);

    var nota = el('div', 'panel me-nota');
    nota.innerHTML = '<p><strong>Come funziona.</strong> Qui si disegna il menu; il server lo legge dal suo file. '
      + '<em>Salva</em> scrive il file, <em>Applica</em> dice al server di rileggerlo — servono tutti e due.</p>'
      + '<p class="muted">Un file salvato da qui viene riscritto per intero: i commenti che ci fossero scritti a mano non sopravvivono. '
      + 'La copia di prima resta sul server come <code>.bak</code>.</p>';
    app.appendChild(nota);
  }

  function primoItemId(m) {
    if (!m.item || !m.item.length) return 'BARRIER';
    for (var i = 0; i < m.item.length; i++) {
      if (m.item[i].slot && m.item[i].slot.length === 1) return m.item[i].id;
    }
    return m.item[0].id;
  }

  function creaNuovo() {
    var nome = (prompt('Nome del nuovo menu (diventa il nome del file e non si vede in gioco):', '') || '').trim().toLowerCase();
    if (!nome) return;
    if (!/^[a-z0-9_-]{1,40}$/.test(nome)) { avviso('Nel nome vanno solo lettere minuscole, numeri, - e _.', true); return; }
    if (dati.menu[nome]) { avviso('Un menu con questo nome esiste già.', true); return; }
    dati.menu[nome] = {
      nome: nome, tipo: 'chest', righe: 3, caselle: 27, larghezza: 9,
      titolo: '&#C046E8' + nome, aggiornamento: 0, comandi: [nome], permesso: null,
      argomenti: [], chiusura_libera: true,
      apri_se: vuotoRequisiti(), azioni_apertura: [], azioni_chiusura: [],
      item: [], errori: []
    };
    apri(nome);
    segnaModificato();
  }

  function duplicaMenu(nome) {
    var nuovo = (prompt('Nome della copia:', nome + '-copia') || '').trim().toLowerCase();
    if (!nuovo) return;
    if (!/^[a-z0-9_-]{1,40}$/.test(nuovo)) { avviso('Nel nome vanno solo lettere minuscole, numeri, - e _.', true); return; }
    if (dati.menu[nuovo]) { avviso('Un menu con questo nome esiste già.', true); return; }
    var c = copia(dati.menu[nome]);
    c.nome = nuovo;
    // I comandi NON si copiano: due menu sullo stesso comando finirebbero per litigarselo,
    // e il primo caricato vincerebbe in modo imprevedibile.
    c.comandi = [];
    dati.menu[nuovo] = c;
    apri(nuovo);
    segnaModificato();
  }

  function eliminaMenu(nome) {
    if (!confirm('Elimino il menu "' + nome + '"?\n\nIl file resta sul server rinominato in .eliminato, ma il menu sparisce dal gioco al prossimo Applica.')) return;
    api('elimina', { nome: nome }).then(function (d) {
      delete dati.menu[nome];
      avviso(d.messaggio);
      disegnaElenco();
    }).catch(function (e) { avviso(e.message, true); });
  }

  function applicaAlServer(bottone) {
    if (bottone) { bottone.disabled = true; bottone.textContent = 'Applico…'; }
    api('applica', {}).then(function (d) {
      avviso(d.messaggio + ' Rileggo com’è andata…');
      return api('azione=elenco');
    }).then(function (d) {
      dati = d;
      var problemi = 0;
      Object.keys(dati.menu).forEach(function (n) { if (dati.menu[n].errori.length) problemi++; });
      if (problemi) avviso('Applicato, ma ' + problemi + (problemi === 1 ? ' menu ha' : ' menu hanno') + ' dei problemi: guarda le schede rosse.', true);
      if (menu) { var n = nomeMenu; aggiornaDaServer(n); } else disegnaElenco();
    }).catch(function (e) {
      avviso(e.message, true);
    }).then(function () {
      if (bottone) { bottone.disabled = false; bottone.textContent = 'Applica al server'; }
    });
  }

  function aggiornaDaServer(nome) {
    if (dati.menu[nome]) {
      var errori = dati.menu[nome].errori || [];
      menu.errori = errori;
      disegnaEditor();
    }
  }

  // ------------------------------------------------------------------
  //  L'editor di un menu
  // ------------------------------------------------------------------
  function apri(nome) {
    nomeMenu = nome;
    testoFile = null;
    menu = copia(dati.menu[nome]);
    if (!menu.item) menu.item = [];
    sceltoItem = -1;
    modificato = false;
    disegnaEditor();
    // Se il pannello del file era aperto resta aperto, ma ora guarda un altro menu: senza
    // questa riga resterebbe fermo su "Leggo il file…" per sempre.
    if (fileAperto) caricaFile();
  }

  function caselleTotali() {
    if (menu.tipo === 'chest') return Math.max(1, Math.min(6, menu.righe || 3)) * 9;
    return menu.caselle || 0;
  }

  function larghezza() {
    return menu.larghezza || 9;
  }

  function disegnaEditor() {
    svuota(app);
    app.appendChild(barraMenu());

    if (menu.errori && menu.errori.length) {
      var box = el('div', 'me-problemi');
      box.appendChild(el('strong', null, 'Il server ha trovato ' + menu.errori.length + (menu.errori.length === 1 ? ' problema' : ' problemi') + ' in questo menu:'));
      menu.errori.forEach(function (e) { box.appendChild(el('div', null, '• ' + e)); });
      box.appendChild(el('div', 'muted', 'Sono quelli dell’ultima volta che il server ha letto il file: si aggiornano dopo Salva + Applica.'));
      app.appendChild(box);
    }

    if (fileAperto) {
      app.appendChild(pannelloFile());
    }

    var corpo = el('div', 'me-corpo');
    var sinistra = el('div', 'me-sinistra');
    if (menu.tipo === 'dialog') {
      sinistra.appendChild(pannelloDialogo());
    } else {
      sinistra.appendChild(griglia());
      sinistra.appendChild(catalogo());
    }
    corpo.appendChild(sinistra);
    corpo.appendChild(pannelloDestro());
    app.appendChild(corpo);
  }

  // --- la barra in alto: le proprieta' del menu -----------------------
  function barraMenu() {
    var barra = el('div', 'me-testata');

    var sx = el('div', 'me-testata-sx');
    var indietro = el('button', 'btn btn-ghost btn-small', '‹ Tutti i menu');
    indietro.type = 'button';
    indietro.addEventListener('click', function () {
      if (modificato && !confirm('Hai modifiche non salvate: le perdo?')) return;
      disegnaElenco();
    });
    sx.appendChild(indietro);
    sx.appendChild(el('h3', null, nomeMenu));
    barra.appendChild(sx);

    var dx = el('div', 'me-testata-dx');
    var salva = el('button', 'btn btn-accent btn-small', 'Salva');
    salva.type = 'button';
    salva.id = 'meSalva';
    salva.addEventListener('click', function () { salvaMenu(salva); });
    dx.appendChild(salva);

    var applica = el('button', 'btn btn-ghost btn-small', 'Applica al server');
    applica.type = 'button';
    applica.addEventListener('click', function () { applicaAlServer(applica); });
    dx.appendChild(applica);

    // "Vedi il file": l'editor disegna, ma quello che conta e' il .yml che finisce sul server.
    // Poterlo leggere senza aprire una sessione SSH e' il modo piu' rapido di capire cosa e'
    // stato salvato davvero — ed e' anche dove si vede che le chiavi sono in inglese.
    var vediFile = el('button', 'btn btn-ghost btn-small', fileAperto ? 'Nascondi il file' : 'Vedi il file');
    vediFile.type = 'button';
    vediFile.addEventListener('click', function () {
      fileAperto = !fileAperto;
      disegnaEditor();
      if (fileAperto) caricaFile();
    });
    dx.appendChild(vediFile);
    barra.appendChild(dx);

    var campi = el('div', 'me-campi-menu');
    campi.appendChild(campoScelta('Tipo', menu.tipo, dati.catalogo.tipi_menu, function (v) {
      menu.tipo = v;
      // Cambiando tipo cambiano le caselle: quelle rimaste fuori si perderebbero senza dirlo.
      if (v !== 'chest') menu.righe = 0;
      else if (!menu.righe) menu.righe = 3;
      segnaModificato();
      disegnaEditor();
    }, 'Che finestra si apre. Il baule è quello che serve quasi sempre.', 'type'));

    if (menu.tipo === 'chest') {
      campi.appendChild(campoScelta('Righe', String(menu.righe || 3), ['1', '2', '3', '4', '5', '6'], function (v) {
        menu.righe = parseInt(v, 10);
        menu.caselle = menu.righe * 9;
        segnaModificato();
        disegnaEditor();
      }, 'Nove caselle per riga.', 'rows'));
    }

    campi.appendChild(campoTesto('Titolo', menu.titolo || '', function (v) { menu.titolo = v; segnaModificato(); anteprimaTitolo(); },
      'Quello che si legge in cima alla finestra. Accetta colori e placeholder.', true, 'title'));
    campi.appendChild(campoNumero('Aggiornamento', menu.aggiornamento || 0, function (v) { menu.aggiornamento = v; segnaModificato(); },
      'Ogni quanti tick si ridisegna (20 = un secondo). 0 = mai.', 'update'));
    campi.appendChild(campoTesto('Comandi', (menu.comandi || []).join(', '), function (v) {
      menu.comandi = v.split(',').map(function (s) { return s.trim().replace(/^\//, '').toLowerCase(); }).filter(Boolean);
      segnaModificato();
    }, 'Separati da virgola, senza la barra. Il primo è il nome, gli altri sono scorciatoie.', false, 'commands'));
    campi.appendChild(campoTesto('Permesso', menu.permesso || '', function (v) { menu.permesso = v || null; segnaModificato(); },
      'Chi non ce l’ha non può aprirlo. Vuoto = aperto a tutti.', false, 'permission'));

    barra.appendChild(campi);

    var anteprima = el('div', 'me-anteprima-titolo');
    anteprima.id = 'meAnteprimaTitolo';
    anteprima.innerHTML = coloraInHtml(menu.titolo || '');
    barra.appendChild(anteprima);
    return barra;
  }

  function anteprimaTitolo() {
    var n = document.getElementById('meAnteprimaTitolo');
    if (n) n.innerHTML = coloraInHtml(menu.titolo || '');
  }

  /**
   * Il file .yml come sta sul server.
   *
   * E' quello SALVATO, non quello che hai davanti: finche' non premi Salva, le modifiche
   * dell'editor non ci sono. Sta scritto nel pannello, perche' e' la domanda che verrebbe
   * subito dopo.
   */
  function pannelloFile() {
    var box = el('div', 'panel me-file');
    var t = el('div', 'me-sotto-titolo');
    t.appendChild(el('strong', null, nomeMenu + '.yml'));
    t.appendChild(el('span', 'muted', 'com’è scritto adesso sul server'));
    box.appendChild(t);

    var pre = el('pre', 'me-file-testo');
    pre.id = 'meFileTesto';
    pre.textContent = testoFile === null ? 'Leggo il file…' : testoFile;
    box.appendChild(pre);

    var nota = el('p', 'muted');
    nota.textContent = modificato
      ? 'Hai modifiche non salvate: qui sotto c’è ancora la versione precedente. Premi Salva per riscriverlo.'
      : 'Le chiavi sono in inglese (type, rows, display_name, lore, show_requirements, actions…). I vecchi nomi italiani restano accettati, quindi i menu scritti a mano continuano a funzionare.';
    box.appendChild(nota);
    return box;
  }

  function caricaFile() {
    testoFile = null;
    api('azione=yaml&nome=' + encodeURIComponent(nomeMenu)).then(function (d) {
      testoFile = d.testo;
      var pre = document.getElementById('meFileTesto');
      if (pre) pre.textContent = testoFile;
    }).catch(function (e) {
      testoFile = 'Non riesco a leggere il file: ' + e.message;
      var pre = document.getElementById('meFileTesto');
      if (pre) pre.textContent = testoFile;
    });
  }

  // --- la griglia -----------------------------------------------------
  function griglia() {
    var box = el('div', 'panel me-griglia-box');
    var t = el('div', 'me-sotto-titolo');
    t.appendChild(el('strong', null, 'La finestra'));
    t.appendChild(el('span', 'muted', 'trascina un item dal catalogo, o spostane uno da una casella all’altra'));
    box.appendChild(t);

    var g = el('div', 'me-griglia');
    g.style.gridTemplateColumns = 'repeat(' + larghezza() + ', var(--me-cella))';
    var totale = caselleTotali();

    for (var c = 0; c < totale; c++) {
      (function (casella) {
        var cella = el('div', 'me-cella');
        cella.setAttribute('data-casella', String(casella));
        var indici = itemNellaCasella(casella);
        var vincente = indici.length ? indici[0] : -1;

        if (vincente >= 0) {
          var it = menu.item[vincente];
          cella.appendChild(icona(it.id, 32));
          cella.draggable = true;
          cella.title = senzaColori(it.titolo || it.nome);
          // Il numerino si mette solo dove la contesa e' VERA, cioe' dove almeno uno dei
          // pretendenti ha delle condizioni e quindi la casella puo' cambiare faccia. Uno
          // sfondo che occupa tutte le caselle perde sempre contro tutti: segnarlo ovunque
          // riempirebbe la griglia di numeri che non dicono niente.
          if (indici.length > 1 && contesaVera(indici)) {
            cella.appendChild(el('span', 'me-badge', String(indici.length)));
          }
          if (it.azioni && contaAzioni(it)) cella.classList.add('me-cella-cliccabile');
          if (sceltoItem === vincente) cella.classList.add('me-cella-scelta');
          else if (sceltoItem >= 0 && menu.item[sceltoItem] && menu.item[sceltoItem].slot.indexOf(casella) >= 0) {
            cella.classList.add('me-cella-sorella');
          }
          cella.addEventListener('dragstart', function (e) {
            e.dataTransfer.setData('text/plain', JSON.stringify({ da: casella }));
            e.dataTransfer.effectAllowed = 'move';
          });
        } else {
          cella.classList.add('me-cella-vuota');
        }

        cella.addEventListener('click', function () {
          if (vincente >= 0) scegliItem(vincente);
          else aggiungiItem('STONE', casella);
        });
        cella.addEventListener('dragover', function (e) { e.preventDefault(); cella.classList.add('me-cella-mira'); });
        cella.addEventListener('dragleave', function () { cella.classList.remove('me-cella-mira'); });
        cella.addEventListener('drop', function (e) {
          e.preventDefault();
          cella.classList.remove('me-cella-mira');
          var carico;
          try { carico = JSON.parse(e.dataTransfer.getData('text/plain')); } catch (x) { return; }
          if (carico.nuovo) aggiungiItem(carico.nuovo, casella);
          else if (carico.da !== undefined) spostaCasella(carico.da, casella);
        });
        g.appendChild(cella);
      })(c);
    }
    // La griglia di un baule e' larga oltre quattrocento pixel: su un telefono non ci sta.
    // Va dentro un contenitore che scorre — col dito lo fa il browser da solo, col mouse lo
    // fa trascinaPer() qui sotto.
    var scorri = el('div', 'me-griglia-scorri');
    scorri.appendChild(g);
    trascinaPer(scorri);
    box.appendChild(scorri);

    var legenda = el('div', 'me-legenda');
    legenda.appendChild(el('span', 'muted', 'Il bordo verde segna gli item che fanno qualcosa al clic. Il numerino dice quanti item si contendono quella casella: la prende il primo dell’elenco che ha i suoi show_requirements soddisfatti.'));
    box.appendChild(legenda);
    return box;
  }

  /**
   * Fa scorrere un contenitore trascinandolo col mouse.
   *
   * Col dito non serve: il browser lo fa gia'. Serve col mouse, dove un contenitore che scorre
   * di lato si muove solo con la rotellina + shift, che nessuno sa.
   *
   * Due accortezze, senza le quali rompe piu' di quanto aggiusta:
   *  - se si parte da una casella che si puo' TRASCINARE (un item), non si scorre: quel gesto
   *    e' gia' preso, serve a spostare l'item;
   *  - lo scorrimento comincia solo dopo qualche pixel di movimento, se no ogni clic su una
   *    casella diventerebbe un mezzo trascinamento e il clic andrebbe perso.
   */
  function trascinaPer(contenitore) {
    var attivo = false, parte = false, dax = 0, daScorrimento = 0;

    contenitore.addEventListener('mousedown', function (e) {
      if (e.button !== 0) return;
      var cella = e.target.closest ? e.target.closest('[draggable="true"]') : null;
      if (cella) return;               // quel gesto sposta un item, non la griglia
      attivo = true;
      parte = false;
      dax = e.clientX;
      daScorrimento = contenitore.scrollLeft;
    });

    window.addEventListener('mousemove', function (e) {
      if (!attivo) return;
      var quanto = e.clientX - dax;
      if (!parte && Math.abs(quanto) < 4) return;   // ancora un clic, non un trascinamento
      parte = true;
      contenitore.classList.add('sto-trascinando');
      contenitore.scrollLeft = daScorrimento - quanto;
      e.preventDefault();
    });

    window.addEventListener('mouseup', function () {
      if (parte) {
        // Il clic che chiude il trascinamento non deve arrivare alla casella sotto.
        contenitore.addEventListener('click', function fermaUnaVolta(ev) {
          ev.stopPropagation();
          contenitore.removeEventListener('click', fermaUnaVolta, true);
        }, true);
      }
      attivo = false;
      parte = false;
      contenitore.classList.remove('sto-trascinando');
    });
  }

  function contaAzioni(it) {
    var n = 0;
    Object.keys(it.azioni || {}).forEach(function (k) { n += (it.azioni[k] || []).length; });
    return n;
  }

  /** La casella puo' davvero cambiare item, o c'e' solo dello sfondo sotto? */
  function contesaVera(indici) {
    for (var i = 0; i < indici.length; i++) {
      var r = menu.item[indici[i]].mostra_se;
      if (r && r.requisiti && r.requisiti.length) return true;
    }
    return false;
  }

  function itemNellaCasella(casella) {
    var out = [];
    menu.item.forEach(function (it, i) {
      if (it.slot && it.slot.indexOf(casella) >= 0) out.push(i);
    });
    return out;
  }

  function scegliItem(indice) {
    sceltoItem = indice;
    schedaAperta = schedaAperta || 'aspetto';
    disegnaEditor();
  }

  function nomeLibero(base) {
    var n = base || 'item';
    var usati = {};
    menu.item.forEach(function (i) { usati[i.nome] = true; });
    if (!usati[n]) return n;
    var k = 2;
    while (usati[n + k]) k++;
    return n + k;
  }

  function vuotoRequisiti() {
    return { minimo: 0, requisiti: [], azioni_negate: [] };
  }

  function aggiungiItem(id, casella) {
    var it = {
      nome: nomeLibero(String(id).toLowerCase()),
      slot: [casella],
      id: id,
      quantita: '1',
      titolo: null,
      descrizione: [],
      incantesimi: [],
      luccica: false, indistruttibile: false, nascondi_dettagli: false,
      modello_custom: null, modello_item: null, colore: null, testa: null, avanzate: null,
      attesa_fra_clic: 0,
      mostra_se: vuotoRequisiti(),
      azioni: {}, click_se: {}
    };
    // In cima all'elenco: il primo che chiede una casella se la prende, e un item appena
    // messo deve vedersi subito invece di finire sotto lo sfondo.
    menu.item.unshift(it);
    sceltoItem = 0;
    segnaModificato();
    disegnaEditor();
  }

  function spostaCasella(da, a) {
    if (da === a) return;
    var indici = itemNellaCasella(da);
    if (!indici.length) return;
    var it = menu.item[indici[0]];
    it.slot = it.slot.filter(function (c) { return c !== da; });
    if (it.slot.indexOf(a) < 0) it.slot.push(a);
    it.slot.sort(function (x, y) { return x - y; });
    sceltoItem = indici[0];
    segnaModificato();
    disegnaEditor();
  }

  // --- il catalogo degli item ----------------------------------------
  function catalogo() {
    var box = el('div', 'panel me-catalogo');
    var t = el('div', 'me-sotto-titolo');
    t.appendChild(el('strong', null, 'Catalogo'));
    t.appendChild(el('span', 'muted', dati.catalogo.item.length + ' item del gioco'));
    box.appendChild(t);

    var cerca = el('input', 'me-cerca');
    cerca.type = 'search';
    cerca.placeholder = 'Cerca un item… (spada, vetro, testa)';
    box.appendChild(cerca);

    var lista = el('div', 'me-catalogo-lista');
    box.appendChild(lista);

    function riempi(filtro) {
      svuota(lista);
      var f = (filtro || '').trim().toUpperCase().replace(/ /g, '_');
      var mostrati = 0;
      for (var i = 0; i < dati.catalogo.item.length && mostrati < 120; i++) {
        var id = dati.catalogo.item[i];
        if (f && id.indexOf(f) < 0) continue;
        mostrati++;
        (function (id) {
          var v = el('div', 'me-catalogo-voce');
          v.draggable = true;
          v.appendChild(icona(id, 24));
          v.appendChild(el('span', null, id.toLowerCase().replace(/_/g, ' ')));
          v.addEventListener('dragstart', function (e) {
            e.dataTransfer.setData('text/plain', JSON.stringify({ nuovo: id }));
            e.dataTransfer.effectAllowed = 'copy';
          });
          v.addEventListener('click', function () {
            var libera = primaCasellaLibera();
            if (libera < 0) { avviso('Non c’è più una casella libera: ingrandisci il menu o togli qualcosa.', true); return; }
            aggiungiItem(id, libera);
          });
          lista.appendChild(v);
        })(id);
      }
      if (!mostrati) lista.appendChild(el('p', 'muted', 'Nessun item con questo nome.'));
      else if (mostrati === 120) lista.appendChild(el('p', 'muted', 'Ce ne sono altri: restringi la ricerca.'));
    }
    cerca.addEventListener('input', function () { riempi(cerca.value); });
    riempi('');
    return box;
  }

  function primaCasellaLibera() {
    var totale = caselleTotali();
    for (var c = 0; c < totale; c++) {
      if (!itemNellaCasella(c).length) return c;
    }
    return -1;
  }

  // ------------------------------------------------------------------
  //  Il pannello di destra
  // ------------------------------------------------------------------
  function pannelloDestro() {
    var box = el('div', 'me-destra');
    if (menu.tipo === 'dialog') {
      box.appendChild(pannelloMenuAvanzato());
      return box;
    }
    if (sceltoItem < 0 || !menu.item[sceltoItem]) {
      var vuoto = el('div', 'panel me-vuoto');
      vuoto.appendChild(el('p', null, 'Nessun item selezionato.'));
      vuoto.appendChild(el('p', 'muted', 'Clicca una casella per modificare quello che c’è dentro, o una casella vuota per metterci qualcosa.'));
      box.appendChild(vuoto);
      box.appendChild(pannelloMenuAvanzato());
      return box;
    }

    var it = menu.item[sceltoItem];
    var p = el('div', 'panel me-pannello');

    var cima = el('div', 'me-pannello-cima');
    cima.appendChild(icona(it.id, 32));
    var nomeCampo = el('input', 'me-nome-item');
    nomeCampo.value = it.nome;
    nomeCampo.title = 'Il nome interno dell’item: non si vede in gioco, serve a te per ritrovarlo.';
    nomeCampo.addEventListener('change', function () {
      it.nome = nomeCampo.value.trim().toLowerCase().replace(/[^a-z0-9_]/g, '_') || 'item';
      segnaModificato();
    });
    cima.appendChild(nomeCampo);

    var su = el('button', 'btn btn-ghost btn-small', '↑');
    su.type = 'button';
    su.title = 'Più in alto nell’elenco: vince sugli altri che chiedono la stessa casella';
    su.addEventListener('click', function () { spostaPriorita(-1); });
    cima.appendChild(su);
    var giu = el('button', 'btn btn-ghost btn-small', '↓');
    giu.type = 'button';
    giu.title = 'Più in basso: gli altri item della stessa casella lo coprono';
    giu.addEventListener('click', function () { spostaPriorita(1); });
    cima.appendChild(giu);

    var togli = el('button', 'btn btn-ghost btn-small', 'Elimina');
    togli.type = 'button';
    togli.addEventListener('click', function () {
      if (!confirm('Tolgo l’item "' + it.nome + '" dal menu?')) return;
      menu.item.splice(sceltoItem, 1);
      sceltoItem = -1;
      segnaModificato();
      disegnaEditor();
    });
    cima.appendChild(togli);
    p.appendChild(cima);

    p.appendChild(schede([
      ['aspetto', 'Aspetto'],
      ['caselle', 'Caselle'],
      ['negozio', 'Negozio'],
      ['condizioni', 'Condizioni'],
      ['azioni', 'Azioni'],
      ['avanzate', 'Avanzate']
    ]));

    var corpo = el('div', 'me-scheda-corpo');
    if (schedaAperta === 'aspetto') corpo.appendChild(schedaAspetto(it));
    else if (schedaAperta === 'caselle') corpo.appendChild(schedaCaselle(it));
    else if (schedaAperta === 'negozio') corpo.appendChild(schedaNegozio(it));
    else if (schedaAperta === 'condizioni') corpo.appendChild(schedaCondizioni(it));
    else if (schedaAperta === 'azioni') corpo.appendChild(schedaAzioni(it));
    else corpo.appendChild(schedaAvanzate(it));
    p.appendChild(corpo);

    box.appendChild(p);
    box.appendChild(anteprimaItem(it));
    return box;
  }

  function spostaPriorita(verso) {
    var a = sceltoItem, b = sceltoItem + verso;
    if (b < 0 || b >= menu.item.length) return;
    var t = menu.item[a];
    menu.item[a] = menu.item[b];
    menu.item[b] = t;
    sceltoItem = b;
    segnaModificato();
    disegnaEditor();
  }

  function schede(voci) {
    var barra = el('div', 'me-schede');
    voci.forEach(function (v) {
      var b = el('button', 'me-scheda' + (schedaAperta === v[0] ? ' attiva' : ''), v[1]);
      b.type = 'button';
      b.addEventListener('click', function () { schedaAperta = v[0]; disegnaEditor(); });
      barra.appendChild(b);
    });
    return barra;
  }

  // --- scheda: aspetto ------------------------------------------------
  function schedaAspetto(it) {
    var f = el('div', 'me-form');
    f.appendChild(campoRicerca('Item', it.id, dati.catalogo.item, function (v) { it.id = v; segnaModificato(); disegnaEditor(); },
      'Quale oggetto del gioco si vede nella casella.', 'id'));
    f.appendChild(campoTesto('Quantità', it.quantita || '1', function (v) { it.quantita = v || '1'; segnaModificato(); },
      'Il numerino in basso a destra. Può essere un placeholder.', false, 'amount'));
    f.appendChild(campoTesto('Nome', it.titolo || '', function (v) { it.titolo = v || null; segnaModificato(); aggiornaAnteprima(it); },
      'Il nome che si legge. Vuoto = quello normale dell’oggetto.', true, 'display_name'));
    f.appendChild(campoTestoLungo('Descrizione', (it.descrizione || []).join('\n'), function (v) {
      it.descrizione = v === '' ? [] : v.split('\n');
      segnaModificato();
      aggiornaAnteprima(it);
    }, 'Una riga per riga. È il posto dove spiegare cosa fa il bottone.', true, 'lore'));

    f.appendChild(campoTesto('Testa', it.testa || '', function (v) { it.testa = v || null; segnaModificato(); },
      'Nome di un giocatore (anche %player_name%), indirizzo di una texture o valore base64.', false, 'head'));

    var spunte = el('div', 'me-spunte');
    spunte.appendChild(campoSpunta('Luccica', it.luccica, function (v) { it.luccica = v; segnaModificato(); }, 'glow'));
    spunte.appendChild(campoSpunta('Indistruttibile', it.indistruttibile, function (v) { it.indistruttibile = v; segnaModificato(); }, 'unbreakable'));
    spunte.appendChild(campoSpunta('Nascondi dettagli', it.nascondi_dettagli, function (v) { it.nascondi_dettagli = v; segnaModificato(); }, 'hide_details'));
    f.appendChild(spunte);

    f.appendChild(listaTesti('Incantesimi', it.incantesimi || [], function (v) { it.incantesimi = v; segnaModificato(); },
      'Uno per riga, come SHARPNESS,5', dati.catalogo.incantesimi, 'enchantments'));
    return f;
  }

  // --- scheda: caselle ------------------------------------------------
  function schedaCaselle(it) {
    var f = el('div', 'me-form');
    f.appendChild(el('p', 'muted', 'Lo stesso item può stare in più caselle: è una definizione sola disegnata più volte. Si scrivono come 2,3,4 oppure 2-10, e valgono anche row:3, column:1, border e all.'));

    var campo = campoTesto('Caselle', compattaSlot(it.slot || []), function (v) {
      var nuove = espandiSlot(v, caselleTotali(), larghezza());
      if (!nuove.length) { avviso('Nessuna casella valida: l’item sparirebbe.', true); return; }
      it.slot = nuove;
      segnaModificato();
      disegnaEditor();
    }, 'Premi Invio per applicare.', false, 'slot');
    f.appendChild(campo);

    var rapidi = el('div', 'me-rapidi');
    [['Tutte', 'all'], ['Bordo', 'border'], ['Prima riga', 'row:1'], ['Ultima riga', 'row:' + Math.max(1, menu.righe || 1)]].forEach(function (r) {
      var b = el('button', 'btn btn-ghost btn-small', r[0]);
      b.type = 'button';
      b.addEventListener('click', function () {
        it.slot = espandiSlot(r[1], caselleTotali(), larghezza());
        segnaModificato();
        disegnaEditor();
      });
      rapidi.appendChild(b);
    });
    f.appendChild(rapidi);
    f.appendChild(anteprimaCaselle(it));
    f.appendChild(el('p', 'muted', 'In questo momento occupa ' + (it.slot || []).length
      + ((it.slot || []).length === 1 ? ' casella.' : ' caselle.')));
    return f;
  }

  /** Dove finisce l'item, disegnato: una griglia piccola con le caselle accese. */
  function anteprimaCaselle(it) {
    var box = el('div', 'me-mini-griglia');
    box.style.gridTemplateColumns = 'repeat(' + larghezza() + ', 14px)';
    var occupate = {};
    (it.slot || []).forEach(function (c) { occupate[c] = true; });
    for (var i = 0; i < caselleTotali(); i++) {
      var c = el('span', 'me-mini-cella' + (occupate[i] ? ' accesa' : ''));
      c.title = 'casella ' + i;
      box.appendChild(c);
    }
    return box;
  }

  function compattaSlot(caselle) {
    if (!caselle || !caselle.length) return '';
    var c = caselle.slice().sort(function (a, b) { return a - b; });
    var pezzi = [], inizio = c[0], prec = c[0];
    for (var i = 1; i <= c.length; i++) {
      var ora = i < c.length ? c[i] : null;
      if (ora !== null && ora === prec + 1) { prec = ora; continue; }
      pezzi.push(inizio === prec ? String(inizio) : (prec - inizio === 1 ? inizio + ',' + prec : inizio + '-' + prec));
      inizio = prec = ora;
    }
    return pezzi.join(',');
  }

  // Le stesse regole del plugin (util/Slot.java): se qui si scrivesse una regola diversa,
  // l'editor mostrerebbe caselle che in gioco non esistono.
  function espandiSlot(testo, totale, larga) {
    var fuori = {}, out = [];
    function metti(n) { if (n >= 0 && n < totale && !fuori[n]) { fuori[n] = true; out.push(n); } }
    String(testo || '').split(',').forEach(function (pezzo) {
      var p = pezzo.trim().toLowerCase();
      if (!p) return;
      if (p === 'all' || p === 'tutte' || p === '*') { for (var i = 0; i < totale; i++) metti(i); return; }
      if (p === 'border' || p === 'bordo') {
        var righe = Math.ceil(totale / larga);
        for (var j = 0; j < totale; j++) {
          var r = Math.floor(j / larga), c = j % larga;
          if (r === 0 || r === righe - 1 || c === 0 || c === larga - 1) metti(j);
        }
        return;
      }
      if (p.indexOf('row:') === 0 || p.indexOf('riga:') === 0) {
        var riga = parseInt(p.slice(p.indexOf(':') + 1), 10);
        if (riga >= 1) for (var k = 0; k < larga; k++) metti((riga - 1) * larga + k);
        return;
      }
      if (p.indexOf('column:') === 0 || p.indexOf('colonna:') === 0) {
        var col = parseInt(p.slice(p.indexOf(':') + 1), 10);
        if (col >= 1 && col <= larga) for (var m = col - 1; m < totale; m += larga) metti(m);
        return;
      }
      var trattino = p.indexOf('-', 1);
      if (trattino > 0) {
        var da = parseInt(p.slice(0, trattino), 10), a = parseInt(p.slice(trattino + 1), 10);
        if (!isNaN(da) && !isNaN(a)) {
          if (da > a) { var t = da; da = a; a = t; }
          for (var n = da; n <= a; n++) metti(n);
        }
        return;
      }
      var uno = parseInt(p, 10);
      if (!isNaN(uno)) metti(uno);
    });
    out.sort(function (a, b) { return a - b; });
    return out;
  }

  // --- scheda: negozio -------------------------------------------------
  //  Tre campi al posto di un blocco di condizioni. Il plugin fa da solo i controlli sui soldi
  //  e sul posto in inventario, e scrive il prezzo nella descrizione: qui non si scrive nessuna
  //  equazione, ed e' il punto.
  function schedaNegozio(it) {
    var f = el('div', 'me-form');
    f.appendChild(el('p', 'muted', 'Metti un prezzo e questo item diventa un articolo: chi clicca paga e riceve. Non serve nessuna condizione — i controlli sui soldi e sul posto in inventario li fa il plugin, e il prezzo compare da solo nella descrizione (quindi non riscriverlo lì).'));

    f.appendChild(campoTesto('Prezzo', it.prezzo || '', function (v) {
      it.prezzo = v.trim() === '' ? null : v.trim();
      segnaModificato();
      disegnaEditor();
    }, 'Quanto costa, col clic sinistro. Vuoto = non è in vendita. Può essere un placeholder.', false, 'price'));

    if (it.prezzo || it.vendi) {
      f.appendChild(campoTesto('Cosa riceve', it.dai || '', function (v) {
        it.dai = v.trim() === '' ? null : v.trim();
        segnaModificato();
      }, 'Es. DIAMOND 4. Scrivi self per dare una copia dell’item che si vede (descrizione compresa). Vuoto = non riceve oggetti: usalo se a dare qualcosa ci pensano le azioni (un permesso, un grado).', false, 'give'));

      f.appendChild(campoTesto('Lo ricompra a', it.vendi || '', function (v) {
        it.vendi = v.trim() === '' ? null : v.trim();
        segnaModificato();
        disegnaEditor();
      }, 'Quanto paga il server se il giocatore glielo rivende col clic destro. Vuoto = non lo ricompra.', false, 'sell'));

      var nota = el('div', 'me-blocco');
      nota.appendChild(el('strong', null, 'Come si comporterà'));
      var righe = [];
      if (it.prezzo) righe.push('Clic sinistro: paga ' + it.prezzo + (it.dai ? ' e riceve ' + it.dai : ''));
      if (it.vendi) righe.push('Clic destro: consegna ' + (it.dai || '?') + ' e incassa ' + it.vendi);
      if (it.vendi && !it.dai) righe.push('⚠ Manca "cosa riceve": senza, la rivendita non sa cosa togliere.');
      var conAzioni = contaAzioni(it);
      if (conAzioni) righe.push(conAzioni === 1 ? 'L’azione scritta parte dopo il pagamento riuscito.'
        : 'Le ' + conAzioni + ' azioni scritte partono dopo il pagamento riuscito.');
      righe.forEach(function (r) { nota.appendChild(el('div', 'muted', '• ' + r)); });
      f.appendChild(nota);
    }
    return f;
  }

  // --- scheda: condizioni ---------------------------------------------
  function schedaCondizioni(it) {
    var f = el('div', 'me-form');
    f.appendChild(el('p', 'muted', 'Due cose diverse: “si vede” nasconde l’item, “si può cliccare” lo lascia visibile e spiega perché non funziona. Quasi sempre serve la seconda.'));

    f.appendChild(bloccoRequisiti('Si vede se…', it.mostra_se, function (r) { it.mostra_se = r; segnaModificato(); }, false, 'show_requirements'));
    if (!it.click_se) it.click_se = {};
    if (!it.click_se.click_requirements) it.click_se.click_requirements = vuotoRequisiti();
    f.appendChild(bloccoRequisiti('Si può cliccare se…', it.click_se.click_requirements, function (r) { it.click_se.click_requirements = r; segnaModificato(); }, true, 'click_requirements'));
    return f;
  }

  function bloccoRequisiti(titolo, r, salva, conNegate, chiave) {
    if (!r) r = vuotoRequisiti();
    var box = el('div', 'me-blocco');
    var t = el('div', 'me-sotto-titolo');
    var intestazione = el('strong', null, titolo);
    if (chiave) intestazione.appendChild(el('code', 'me-chiave', chiave));
    t.appendChild(intestazione);
    box.appendChild(t);

    (r.requisiti || []).forEach(function (uno, i) {
      box.appendChild(rigaRequisito(uno, function () { salva(r); }, function () {
        r.requisiti.splice(i, 1);
        salva(r);
        disegnaEditor();
      }));
    });

    var aggiungi = el('button', 'btn btn-ghost btn-small', '+ condizione');
    aggiungi.type = 'button';
    aggiungi.addEventListener('click', function () {
      r.requisiti.push({ tipo: 'PERMISSION', chiave: '', valore: '', quantita: 1, uguale: true });
      salva(r);
      disegnaEditor();
    });
    box.appendChild(aggiungi);

    if ((r.requisiti || []).length > 1) {
      box.appendChild(campoNumero('Ne bastano', r.minimo || 0, function (v) { r.minimo = v; salva(r); },
        '0 = servono tutte. 2 = ne bastano due qualsiasi.', 'minimum'));
    }
    if ((r.requisiti || []).length) {
      box.appendChild(anteprimaRequisiti(titolo, r));
    }
    // "Se non si può" ha senso solo se c'e' qualcosa che puo' non potersi: senza nessuna
    // condizione quel riquadro prometteva un caso che non poteva mai capitare.
    if (conNegate && (r.requisiti || []).length) {
      box.appendChild(bloccoAzioni('Se non si può, allora…', r.azioni_negate || [], function (a) {
        r.azioni_negate = a;
        salva(r);
      }, 'deny_actions'));
    } else if (conNegate && (r.azioni_negate || []).length) {
      var orfane = el('div', 'me-avviso-riga');
      orfane.textContent = 'Qui sotto ci sono ' + r.azioni_negate.length
        + ' azioni per il caso "non si può", ma non c’è nessuna condizione: non partiranno mai.';
      box.appendChild(orfane);
      box.appendChild(bloccoAzioni('Se non si può, allora…', r.azioni_negate, function (a) {
        r.azioni_negate = a;
        salva(r);
      }, 'deny_actions'));
    }
    return box;
  }

  /** Il blocco di condizioni riscritto come lo direbbe una persona. */
  function anteprimaRequisiti(titolo, r) {
    var box = el('div', 'me-anteprima-riga');
    var quante = (r.requisiti || []).length;
    var minimo = parseInt(r.minimo, 10) || 0;
    var capo = quante === 1 ? ''
      : (minimo > 0 && minimo < quante ? 'Ne bastano ' + minimo + ' su ' + quante + ': '
        : 'Servono tutte e ' + quante + ': ');
    var frasi = (r.requisiti || []).map(fraseRequisito);
    box.textContent = '→ ' + capo + frasi.join(minimo > 0 && minimo < quante ? ' oppure ' : ' e ');
    return box;
  }

  /** Una condizione singola in italiano: "ha il permesso magixmenus.admin". */
  function fraseRequisito(u) {
    var k = u.chiave || '…';
    var v = u.valore || '…';
    var n = u.quantita || 1;
    var frasi = {
      PERMISSION: 'ha il permesso ' + k,
      EQUATION: 'è vero che ' + k,
      STRING_EQUALS: k + ' è uguale a ' + v,
      STRING_CONTAINS: k + ' contiene ' + v,
      REGEX: k + ' corrisponde a ' + v,
      HAS_ITEM: 'ha ' + n + ' ' + k,
      HAS_MONEY: 'ha almeno ' + n + ' monete',
      HAS_LEVEL: 'ha almeno ' + n + ' livelli',
      WORLD: 'si trova in ' + k,
      EMPTY_SLOTS: 'ha ' + n + ' caselle libere in inventario'
    };
    var f = frasi[u.tipo] || u.tipo;
    return u.uguale === false ? 'NON ' + f : f;
  }

  function rigaRequisito(uno, cambiato, togli) {
    var riga = el('div', 'me-riga-cond');

    var tipo = el('select', 'me-sel');
    dati.catalogo.tipi_requisito.forEach(function (t) {
      var o = el('option', null, t.toLowerCase().replace(/_/g, ' '));
      o.value = t;
      if (t === uno.tipo) o.selected = true;
      tipo.appendChild(o);
    });
    tipo.addEventListener('change', function () { uno.tipo = tipo.value; cambiato(); disegnaEditor(); });
    riga.appendChild(tipo);

    var descrizioni = {
      PERMISSION: ['il permesso', 'es. magixmenus.admin', false],
      EQUATION: ['la condizione', 'es. %vault_eco_balance% >= 1000', false],
      STRING_EQUALS: ['questo testo', '%player_world%', true],
      STRING_CONTAINS: ['questo testo', '%player_name%', true],
      REGEX: ['questo testo', '%player_name%', true],
      HAS_ITEM: ['l’item', 'DIAMOND', false],
      HAS_MONEY: ['', '', false],
      HAS_LEVEL: ['', '', false],
      WORLD: ['i mondi', 'world, world_nether', false],
      EMPTY_SLOTS: ['', '', false]
    };
    var d = descrizioni[uno.tipo] || ['la chiave', '', false];

    if (d[0]) {
      var chiave = el('input', 'me-inp');
      chiave.value = uno.chiave || '';
      chiave.placeholder = d[1];
      chiave.addEventListener('input', function () { uno.chiave = chiave.value; cambiato(); });
      riga.appendChild(chiave);
    }
    if (d[2]) {
      var valore = el('input', 'me-inp');
      valore.value = uno.valore || '';
      valore.placeholder = 'a cui confrontarlo';
      valore.addEventListener('input', function () { uno.valore = valore.value; cambiato(); });
      riga.appendChild(valore);
    }
    if (uno.tipo === 'HAS_ITEM' || uno.tipo === 'HAS_MONEY' || uno.tipo === 'HAS_LEVEL' || uno.tipo === 'EMPTY_SLOTS') {
      var quanti = el('input', 'me-inp me-inp-corto');
      quanti.type = 'number';
      quanti.value = uno.quantita || 1;
      quanti.title = 'quanti';
      quanti.addEventListener('input', function () { uno.quantita = parseInt(quanti.value, 10) || 1; cambiato(); });
      riga.appendChild(quanti);
    }

    var negato = el('button', 'btn btn-ghost btn-small' + (uno.uguale === false ? ' me-negato' : ''), uno.uguale === false ? 'NON' : 'è così');
    negato.type = 'button';
    negato.title = 'Ribalta la condizione';
    negato.addEventListener('click', function () { uno.uguale = uno.uguale === false; cambiato(); disegnaEditor(); });
    riga.appendChild(negato);

    var x = el('button', 'btn btn-ghost btn-small', '×');
    x.type = 'button';
    x.addEventListener('click', togli);
    riga.appendChild(x);
    return riga;
  }

  // --- scheda: azioni --------------------------------------------------
  // Le chiavi sono quelle che finiscono nel file: il plugin le pubblica gia' cosi', e qui
  // non c'e' nessuna tabella di traduzione che possa restare indietro.
  var TASTI = [
    ['actions', 'Qualunque tasto'],
    ['left_click_actions', 'Tasto sinistro'],
    ['right_click_actions', 'Tasto destro'],
    ['shift_left_click_actions', 'Shift + sinistro'],
    ['shift_right_click_actions', 'Shift + destro'],
    ['middle_click_actions', 'Rotellina'],
    ['number_key_actions', 'Tasti 1-9'],
    ['double_click_actions', 'Doppio clic']
  ];

  function schedaAzioni(it) {
    var f = el('div', 'me-form');
    f.appendChild(el('p', 'muted', 'Un clic fa scattare prima le azioni del suo tasto, poi quelle di “qualunque tasto”. Le cose comuni si scrivono una volta sola lì.'));
    if (!it.azioni) it.azioni = {};

    TASTI.forEach(function (t) {
      var elenco = it.azioni[t[0]] || [];
      if (t[0] !== 'actions' && !elenco.length) return;   // i tasti inutilizzati non ingombrano
      f.appendChild(bloccoAzioni(t[1], elenco, function (a) { it.azioni[t[0]] = a; segnaModificato(); }, t[0]));
    });

    var altri = TASTI.filter(function (t) { return !(it.azioni[t[0]] || []).length; });
    if (altri.length) {
      var sel = el('select', 'me-sel');
      var vuoto = el('option', null, '+ azioni per un altro tasto…');
      vuoto.value = '';
      sel.appendChild(vuoto);
      altri.forEach(function (t) {
        var o = el('option', null, t[1]);
        o.value = t[0];
        sel.appendChild(o);
      });
      sel.addEventListener('change', function () {
        if (!sel.value) return;
        it.azioni[sel.value] = [{ tipo: 'message', argomento: '' }];
        segnaModificato();
        disegnaEditor();
      });
      f.appendChild(sel);
    }
    return f;
  }

  function bloccoAzioni(titolo, azioni, salva, chiave) {
    if (!azioni) azioni = [];
    var box = el('div', 'me-blocco');
    var t = el('div', 'me-sotto-titolo');
    var intestazione = el('strong', null, titolo);
    if (chiave) intestazione.appendChild(el('code', 'me-chiave', chiave));
    t.appendChild(intestazione);
    box.appendChild(t);

    azioni.forEach(function (a, i) {
      box.appendChild(rigaAzione(a, i, azioni, salva));
    });

    var barra = el('div', 'me-rapidi');
    var piu = el('button', 'btn btn-ghost btn-small', '+ azione');
    piu.type = 'button';
    piu.addEventListener('click', function () {
      azioni.push({ tipo: 'message', argomento: '' });
      salva(azioni);
      disegnaEditor();
    });
    barra.appendChild(piu);

    var seBlocco = el('button', 'btn btn-ghost btn-small', '+ se… allora…');
    seBlocco.type = 'button';
    seBlocco.title = 'Un bivio: metti una condizione, e scegli cosa fare se è vera e cosa se non lo è '
      + '(per esempio: se ha i soldi glielo vendi, altrimenti glielo dici)';
    seBlocco.addEventListener('click', function () {
      azioni.push({
        tipo: 'if',
        condizione: { minimo: 0, requisiti: [{ tipo: 'EQUATION', chiave: '', valore: '', quantita: 1, uguale: true }], azioni_negate: [] },
        allora: [], altrimenti: []
      });
      salva(azioni);
      disegnaEditor();
    });
    barra.appendChild(seBlocco);
    box.appendChild(barra);
    if (azioni.length) {
      box.appendChild(anteprimaAzioni(azioni));
    }
    return box;
  }

  /**
   * Quello che succede, nell'ordine, in italiano.
   *
   * Le attese si accumulano e vengono segnate accanto ai passi che le seguono: senza, una
   * catena con due "wait" in mezzo e' impossibile da leggere a occhio, e ci si accorge del
   * ritardo sbagliato solo provandolo in gioco.
   */
  function anteprimaAzioni(azioni) {
    var box = el('div', 'me-anteprima-passi');
    box.appendChild(el('div', 'me-passi-titolo', 'Cosa succede, nell’ordine'));
    var ritardo = 0;
    var n = 0;
    azioni.forEach(function (a) {
      if (a.tipo === 'wait') {
        ritardo += (parseInt(a.argomento, 10) || 0);
        return;
      }
      n++;
      var riga = el('div', 'me-passo');
      riga.appendChild(el('span', 'me-passo-n', String(n)));
      if (azioneIncompleta(a)) {
        // Meglio dire "manca qualcosa" che leggere "il giocatore esegue /…": un'azione a meta'
        // e' un errore da vedere, non una frase da indovinare.
        var manca = el('span', 'me-passo-manca', fraseMancante(a));
        riga.appendChild(manca);
      } else {
        riga.appendChild(el('span', null, fraseAzione(a)));
      }
      if (ritardo > 0) {
        riga.appendChild(el('span', 'me-passo-quando', 'dopo ' + secondi(ritardo)));
      }
      box.appendChild(riga);
    });
    if (!n) {
      box.appendChild(el('div', 'muted', 'Solo attese: non succede niente.'));
    }
    return box;
  }

  /** Un'azione che vuole un argomento e non ce l'ha: e' a meta'. */
  function azioneIncompleta(a) {
    if (a.tipo === 'if') return false;
    var senzaArgomento = ['back', 'close', 'refresh'];
    if (senzaArgomento.indexOf(a.tipo) >= 0) return false;
    return !String(a.argomento || '').trim();
  }

  /** Cosa manca a un'azione lasciata a meta', detto per intero. */
  function fraseMancante(a) {
    var f = {
      command: 'manca il comando che esegue il giocatore',
      console: 'manca il comando che esegue il server',
      op_command: 'manca il comando da eseguire con l’op',
      message: 'manca il messaggio da mandare in chat',
      broadcast: 'manca l’annuncio da mandare a tutti',
      title: 'manca il testo del titolo a schermo',
      actionbar: 'manca il testo da scrivere sopra la barra',
      sound: 'manca quale suono fare',
      menu: 'manca quale menu aprire',
      page: 'manca quale pagina (next, prev o un numero)',
      give_money: 'mancano quante monete dare',
      take_money: 'mancano quante monete togliere',
      wait: 'manca quanto aspettare, in tick'
    };
    return f[a.tipo] || 'manca cosa deve fare';
  }

  function secondi(tick) {
    var s = tick / 20;
    return (s === Math.round(s) ? s : s.toFixed(1)) + ' s';
  }

  /** Un'azione detta a parole. */
  function fraseAzione(a) {
    var x = a.argomento || '…';
    var breve = function (t) {
      var pulito = senzaColori(t).trim();
      return pulito.length > 42 ? '«' + pulito.slice(0, 42) + '…»' : '«' + pulito + '»';
    };
    if (a.tipo === 'if') {
      var quante = ((a.condizione || {}).requisiti || []).length;
      var allora = (a.allora || []).length;
      var altrimenti = (a.altrimenti || []).length;
      return 'se ' + (quante ? (a.condizione.requisiti || []).map(fraseRequisito).join(' e ') : '…')
        + ' → ' + allora + (allora === 1 ? ' azione' : ' azioni')
        + (altrimenti ? ', altrimenti ' + altrimenti : '');
    }
    var frasi = {
      command: 'il giocatore esegue /' + x,
      console: 'il server esegue /' + x,
      op_command: 'il giocatore esegue /' + x + ' con l’op',
      message: 'manda in chat ' + breve(x),
      broadcast: 'annuncia a tutti ' + breve(x),
      title: 'titolo a schermo ' + breve(x.split('|')[0]),
      actionbar: 'scrive sopra la barra ' + breve(x),
      sound: 'fa il suono ' + x.split('|')[0],
      menu: 'apre il menu ' + x,
      back: 'torna al menu precedente',
      close: 'chiude il menu',
      refresh: 'ridisegna il menu',
      page: x === 'next' ? 'va alla pagina successiva'
        : (x === 'prev' ? 'torna alla pagina precedente' : 'va alla pagina ' + x),
      give_money: 'dà ' + x + ' monete',
      take_money: 'toglie ' + x + ' monete (se non bastano si ferma qui)'
    };
    return frasi[a.tipo] || (a.tipo + ': ' + x);
  }

  function rigaAzione(a, i, azioni, salva) {
    if (a.tipo === 'if') {
      var blocco = el('div', 'me-azione-se');
      var cima = el('div', 'me-sotto-titolo');
      cima.appendChild(el('strong', null, 'Un bivio: se…'));
      var xx = el('button', 'btn btn-ghost btn-small', '×');
      xx.type = 'button';
      xx.addEventListener('click', function () { azioni.splice(i, 1); salva(azioni); disegnaEditor(); });
      cima.appendChild(xx);
      blocco.appendChild(cima);

      blocco.appendChild(bloccoRequisiti('La condizione', a.condizione, function (r) { a.condizione = r; salva(azioni); }, false));
      blocco.appendChild(bloccoAzioni('allora', a.allora || [], function (x) { a.allora = x; salva(azioni); }));
      blocco.appendChild(bloccoAzioni('altrimenti', a.altrimenti || [], function (x) { a.altrimenti = x; salva(azioni); }));
      return blocco;
    }

    var riga = el('div', 'me-riga-azione');

    var su = el('button', 'btn btn-ghost btn-small', '↑');
    su.type = 'button';
    su.addEventListener('click', function () {
      if (i === 0) return;
      var t = azioni[i - 1]; azioni[i - 1] = azioni[i]; azioni[i] = t;
      salva(azioni); disegnaEditor();
    });
    riga.appendChild(su);

    var tipo = el('select', 'me-sel');
    dati.catalogo.tipi_azione.forEach(function (t) {
      var o = el('option', null, t.replace(/_/g, ' '));
      o.value = t;
      if (t === a.tipo) o.selected = true;
      tipo.appendChild(o);
    });
    tipo.addEventListener('change', function () { a.tipo = tipo.value; salva(azioni); disegnaEditor(); });
    riga.appendChild(tipo);

    var senzaArgomento = ['back', 'close', 'refresh'];
    if (senzaArgomento.indexOf(a.tipo) < 0) {
      if (AZIONI_CON_TESTO.indexOf(a.tipo) >= 0) {
        // Un messaggio, un titolo, una riga sopra la barra: e' testo che il giocatore LEGGE,
        // quindi si scrive gia' colorato come tutti gli altri testi del menu. Il campo va sotto
        // tutta la riga; i pulsanti restano in ALTO accanto alla tendina, se no finiscono su una
        // riga per conto loro e non si capisce a cosa appartengano.
        riga.appendChild(togliAzione(i, azioni, salva));
        var scritto = campoScritto(a.argomento || '', function (v) {
          a.argomento = v;
          salva(azioni);
        }, false);
        scritto.classList.add('me-arg-colorato');
        scritto.campo.setAttribute('data-vuoto', suggerimentoAzione(a.tipo));
        riga.appendChild(scritto);
        abilitaColori(scritto.campo, scritto);
        return riga;
      }
      var arg = el('input', 'me-inp');
      arg.value = a.argomento || '';
      arg.placeholder = suggerimentoAzione(a.tipo);
      arg.addEventListener('input', function () { a.argomento = arg.value; salva(azioni); });
      riga.appendChild(arg);
    }

    riga.appendChild(togliAzione(i, azioni, salva));
    return riga;
  }

  function togliAzione(i, azioni, salva) {
    var x = el('button', 'btn btn-ghost btn-small', '×');
    x.type = 'button';
    x.title = 'Togli questa azione';
    x.addEventListener('click', function () { azioni.splice(i, 1); salva(azioni); disegnaEditor(); });
    return x;
  }

  /** Le azioni il cui argomento finisce scritto a schermo: solo li' i colori hanno senso. */
  var AZIONI_CON_TESTO = ['message', 'broadcast', 'title', 'actionbar'];

  function suggerimentoAzione(tipo) {
    var s = {
      command: 'spawn        (lo esegue il giocatore)',
      console: 'give %player_name% diamond 1',
      op_command: 'un comando che vuole l’op (di norma spento)',
      message: '&aCiao %player_name%',
      broadcast: '&e%player_name% ha vinto!',
      title: 'Grande|piccolo',
      actionbar: 'la riga sopra la barra',
      sound: 'BLOCK_NOTE_BLOCK_PLING|1|1.4',
      menu: 'nome-del-menu',
      page: 'next, prev o un numero',
      give_money: '100',
      take_money: '100',
      wait: '20   (tick: 20 = un secondo)'
    };
    return s[tipo] || '';
  }

  // --- scheda: avanzate ------------------------------------------------
  function schedaAvanzate(it) {
    var f = el('div', 'me-form');
    f.appendChild(el('p', 'muted', 'Roba che serve di rado. Se stai qui dentro spesso, forse manca un campo comodo: dimmelo.'));
    f.appendChild(campoNumero('Attesa fra due clic (secondi)', it.attesa_fra_clic || 0, function (v) { it.attesa_fra_clic = v; segnaModificato(); },
      'Contro chi tempesta di clic un bottone che dà qualcosa.', 'cooldown'));
    f.appendChild(campoTesto('Modello del pacchetto (item_model)', it.modello_item || '', function (v) { it.modello_item = v || null; segnaModificato(); },
      'es. magicadventure:moneta', false, 'item_model'));
    f.appendChild(campoTesto('Custom model data', it.modello_custom || '', function (v) { it.modello_custom = v || null; segnaModificato(); },
      'Il vecchio modo di puntare a un modello del pacchetto.', false, 'custom_model_data'));
    f.appendChild(campoTesto('Colore', it.colore || '', function (v) { it.colore = v || null; segnaModificato(); },
      '#RRGGBB — vale per la pelle e per le pozioni.', false, 'color'));
    f.appendChild(campoTestoLungo('Componenti grezzi', it.avanzate || '', function (v) { it.avanzate = v || null; segnaModificato(); },
      'Per quello che qui non è previsto, nella forma dei comandi /give: [minecraft:rarity=epic]. Si applica per primo, i campi normali gli vanno sopra.', false, 'components'));
    return f;
  }

  // --- il menu in generale (apertura, chiusura, contenuto) --------------
  function pannelloMenuAvanzato() {
    var p = el('div', 'panel me-pannello');
    p.appendChild(el('div', 'me-sotto-titolo')).appendChild(el('strong', null, 'Il menu nell’insieme'));
    p.appendChild(campoTesto('Argomenti del comando', (menu.argomenti || []).join(', '), function (v) {
      menu.argomenti = v.split(',').map(function (s) { return s.trim(); }).filter(Boolean);
      segnaModificato();
    }, 'Separati da virgola. /negozio armi con argomento "categoria" dà %arg_categoria%.', false, 'arguments'));

    p.appendChild(campoSpunta('Si può chiudere con Esc', menu.chiusura_libera !== false, function (v) {
      menu.chiusura_libera = v;
      segnaModificato();
    }, 'closeable'));

    p.appendChild(bloccoRequisiti('Si può aprire se…', menu.apri_se, function (r) { menu.apri_se = r; segnaModificato(); }, true, 'open_requirements'));
    p.appendChild(bloccoAzioni('Quando si apre', menu.azioni_apertura || [], function (a) { menu.azioni_apertura = a; segnaModificato(); }, 'open_actions'));
    p.appendChild(bloccoAzioni('Quando si chiude', menu.azioni_chiusura || [], function (a) { menu.azioni_chiusura = a; segnaModificato(); }, 'close_actions'));
    return p;
  }

  function pannelloDialogo() {
    var p = el('div', 'panel me-pannello');
    var t = el('div', 'me-sotto-titolo');
    t.appendChild(el('strong', null, 'La finestra di dialogo'));
    p.appendChild(t);
    p.appendChild(el('p', 'muted', 'Un dialogo non ha caselle: ha un testo, dei campi da riempire e dei bottoni.'));

    if (!menu.dialogo) menu.dialogo = { corpo: [], campi: [], bottoni: [], pausa: false };
    var d = menu.dialogo;

    p.appendChild(campoTestoLungo('Testo', (d.corpo || []).join('\n'), function (v) {
      d.corpo = v === '' ? [] : v.split('\n');
      segnaModificato();
    }, 'Una riga per paragrafo.', true, 'body'));

    // I campi
    var boxCampi = el('div', 'me-blocco');
    boxCampi.appendChild(el('div', 'me-sotto-titolo')).appendChild(el('strong', null, 'Campi da riempire'));
    (d.campi || []).forEach(function (c, i) {
      var riga = el('div', 'me-riga-azione');
      var chiave = el('input', 'me-inp me-inp-corto');
      chiave.value = c.chiave || '';
      chiave.placeholder = 'nome';
      chiave.title = 'Il valore arriva alle azioni come %field_' + (c.chiave || 'nome') + '%';
      chiave.addEventListener('input', function () { c.chiave = chiave.value.trim().toLowerCase().replace(/[^a-z0-9_]/g, '_'); segnaModificato(); });
      riga.appendChild(chiave);

      var tipo = el('select', 'me-sel');
      ['text', 'boolean', 'number', 'option'].forEach(function (x) {
        var o = el('option', null, x);
        o.value = x;
        if (x === c.tipo) o.selected = true;
        tipo.appendChild(o);
      });
      tipo.addEventListener('change', function () { c.tipo = tipo.value; segnaModificato(); disegnaEditor(); });
      riga.appendChild(tipo);

      var scrittaEtichetta = campoScritto(c.etichetta || '', function (v) {
        c.etichetta = v;
        segnaModificato();
      }, false);
      scrittaEtichetta.classList.add('me-arg-colorato');
      scrittaEtichetta.campo.setAttribute('data-vuoto', 'come si chiama a schermo');
      riga.appendChild(scrittaEtichetta);
      abilitaColori(scrittaEtichetta.campo, scrittaEtichetta);

      if (c.tipo === 'option') {
        var opzioni = el('input', 'me-inp');
        opzioni.value = (c.opzioni || []).join(', ');
        opzioni.placeholder = 'le scelte, separate da virgola';
        opzioni.addEventListener('input', function () {
          c.opzioni = opzioni.value.split(',').map(function (s) { return s.trim(); }).filter(Boolean);
          segnaModificato();
        });
        riga.appendChild(opzioni);
      }

      var x = el('button', 'btn btn-ghost btn-small', '×');
      x.type = 'button';
      x.addEventListener('click', function () { d.campi.splice(i, 1); segnaModificato(); disegnaEditor(); });
      riga.appendChild(x);
      boxCampi.appendChild(riga);
    });
    var piuCampo = el('button', 'btn btn-ghost btn-small', '+ campo');
    piuCampo.type = 'button';
    piuCampo.addEventListener('click', function () {
      d.campi.push({ chiave: 'campo' + ((d.campi || []).length + 1), tipo: 'text', etichetta: '', iniziale: '', lunghezza: 100, larghezza: 300, da: 0, a: 100, passo: 1, opzioni: [] });
      segnaModificato();
      disegnaEditor();
    });
    boxCampi.appendChild(piuCampo);
    p.appendChild(boxCampi);

    // I bottoni
    (d.bottoni || []).forEach(function (b, i) {
      var box = el('div', 'me-blocco');
      var cima = el('div', 'me-sotto-titolo');
      cima.appendChild(el('strong', null, 'Bottone'));
      var x = el('button', 'btn btn-ghost btn-small', '×');
      x.type = 'button';
      x.addEventListener('click', function () { d.bottoni.splice(i, 1); segnaModificato(); disegnaEditor(); });
      cima.appendChild(x);
      box.appendChild(cima);
      box.appendChild(campoTesto('Etichetta', b.etichetta || '', function (v) { b.etichetta = v; segnaModificato(); }, 'Quello che c’è scritto sopra.', true, 'label'));
      box.appendChild(campoTesto('Suggerimento', b.suggerimento || '', function (v) { b.suggerimento = v || null; segnaModificato(); }, 'Compare passandoci sopra.', true, 'tooltip'));
      box.appendChild(bloccoRequisiti('Si vede se…', b.mostra_se, function (r) { b.mostra_se = r; segnaModificato(); }, false, 'show_requirements'));
      box.appendChild(bloccoAzioni('Quando lo premono', b.azioni || [], function (a) { b.azioni = a; segnaModificato(); }, 'actions'));
      p.appendChild(box);
    });
    var piuBottone = el('button', 'btn btn-ghost btn-small', '+ bottone');
    piuBottone.type = 'button';
    piuBottone.addEventListener('click', function () {
      d.bottoni.push({ etichetta: 'Conferma', suggerimento: null, larghezza: 150, mostra_se: vuotoRequisiti(), azioni: [] });
      segnaModificato();
      disegnaEditor();
    });
    p.appendChild(piuBottone);
    p.appendChild(anteprimaDialogo(d));
    return p;
  }

  /**
   * La finestra come la vedra' chi gioca: titolo, testo, campi e bottoni.
   *
   * Un dialogo, a differenza di un baule, non si puo' immaginare guardando dei campi di modulo:
   * o lo si disegna, o si scopre com'e' venuto entrando in gioco.
   */
  function anteprimaDialogo(d) {
    var box = el('div', 'me-anteprima-dialogo');
    box.appendChild(el('div', 'me-sotto-titolo')).appendChild(el('strong', null, 'Come si vedrà'));

    var finestra = el('div', 'me-finestra');
    var titolo = el('div', 'me-finestra-titolo');
    titolo.innerHTML = coloraInHtml(menu.titolo || '');
    finestra.appendChild(titolo);

    (d.corpo || []).forEach(function (r) {
      var riga = el('div', 'me-finestra-testo');
      riga.innerHTML = coloraInHtml(r) || '&nbsp;';
      finestra.appendChild(riga);
    });

    (d.campi || []).forEach(function (c) {
      var riga = el('div', 'me-finestra-campo');
      var et = el('div', 'me-finestra-etichetta');
      et.innerHTML = coloraInHtml(c.etichetta || c.chiave || '');
      riga.appendChild(et);
      var finto = el('div', 'me-finestra-input');
      if (c.tipo === 'boolean') {
        finto.classList.add('spunta');
        finto.textContent = (String(c.iniziale) === 'true' ? '☑' : '☐');
      } else if (c.tipo === 'option') {
        finto.textContent = '‹ ' + ((c.opzioni || [])[0] || '…') + ' ›';
      } else if (c.tipo === 'number') {
        finto.textContent = '—•——  ' + (c.iniziale || c.da || 0);
      } else {
        finto.textContent = c.iniziale || '';
      }
      riga.appendChild(finto);
      finestra.appendChild(riga);
    });

    var bottoni = el('div', 'me-finestra-bottoni');
    (d.bottoni || []).forEach(function (b) {
      var x = el('span', 'me-finestra-bottone');
      x.innerHTML = coloraInHtml(b.etichetta || '');
      if ((b.mostra_se || {}).requisiti && b.mostra_se.requisiti.length) {
        x.classList.add('condizionato');
        x.title = 'Si vede solo se: ' + b.mostra_se.requisiti.map(fraseRequisito).join(' e ');
      }
      bottoni.appendChild(x);
    });
    if (!(d.bottoni || []).length) {
      bottoni.appendChild(el('span', 'muted', 'nessun bottone: si potrà solo leggere e chiudere'));
    }
    finestra.appendChild(bottoni);

    box.appendChild(finestra);
    return box;
  }

  // --- l'anteprima dell'item -------------------------------------------
  function anteprimaItem(it) {
    var box = el('div', 'panel me-anteprima');
    box.appendChild(el('div', 'me-sotto-titolo')).appendChild(el('strong', null, 'Come si vedrà'));
    var scatola = el('div', 'me-tooltip-scena');
    scatola.id = 'meTooltip';
    disegnaTooltip(scatola, it);
    box.appendChild(scatola);
    box.appendChild(el('p', 'muted', 'I placeholder (%player_name%) qui restano scritti così: il loro valore lo conosce solo il server, e cambia da giocatore a giocatore.'));
    return box;
  }

  /**
   * La targhetta dell'item come la vedrebbe chi gioca: l'icona con la sua quantita', il nome,
   * la descrizione, e i segni di cio' che non si legge (riflessi, indistruttibile).
   *
   * E' l'unico modo di accorgersi PRIMA che un nome e' illeggibile o che una descrizione e'
   * larga il doppio dello schermo.
   */
  function disegnaTooltip(scatola, it) {
    svuota(scatola);

    var fianco = el('div', 'me-tooltip-icona');
    fianco.appendChild(icona(it.id, 44));
    var q = String(it.quantita || '1');
    if (q !== '1') {
      fianco.appendChild(el('span', 'me-tooltip-quantita', q));
    }
    scatola.appendChild(fianco);

    var targhetta = el('div', 'me-tooltip');
    var nome = el('div', 'me-tooltip-nome');
    nome.innerHTML = coloraInHtml(it.titolo || '&7' + it.id.toLowerCase().replace(/_/g, ' '));
    targhetta.appendChild(nome);
    (it.descrizione || []).forEach(function (r) {
      var d = el('div', 'me-tooltip-riga');
      d.innerHTML = coloraInHtml(r) || '&nbsp;';
      targhetta.appendChild(d);
    });

    // Le righe del prezzo le aggiunge il server (il testo sta in messages.yml): qui si segna
    // che ci saranno, senza ricopiarle — ricopiate, prima o poi direbbero un'altra cosa.
    if (it.prezzo || it.vendi) {
      targhetta.appendChild(el('div', 'me-tooltip-riga me-tooltip-auto',
        '＋ le righe del prezzo, aggiunte dal server'));
    }
    scatola.appendChild(targhetta);

    var segni = [];
    if (it.luccica) segni.push('riflessi');
    if (it.indistruttibile) segni.push('indistruttibile');
    if (it.nascondi_dettagli) segni.push('dettagli nascosti');
    if ((it.incantesimi || []).length) segni.push((it.incantesimi || []).length + ' incantesimi');
    if (it.testa) segni.push('testa di ' + it.testa);
    if (segni.length) {
      var sotto = el('div', 'me-tooltip-segni');
      segni.forEach(function (x) { sotto.appendChild(el('span', 'me-tag', x)); });
      scatola.appendChild(sotto);
    }
  }

  function aggiornaAnteprima(it) {
    var s = document.getElementById('meTooltip');
    if (s) disegnaTooltip(s, it);
  }

  // ------------------------------------------------------------------
  //  Mattoncini di modulo
  // ------------------------------------------------------------------
  /**
   * @param colorabile il campo dentro cui si scrive testo che i giocatori vedranno: gli si
   *        attacca la barra degli strumenti. Quando c'e', il contenitore e' un <div> e non un
   *        <label>: dentro un'etichetta, il clic su un pulsante della barra verrebbe girato al
   *        campo e la barra non funzionerebbe.
   */
  function etichettato(testo, dentro, aiuto, colorabile, chiave) {
    var l = el(colorabile ? 'div' : 'label', 'me-campo');
    var e = el('span', 'me-etichetta', testo);
    if (chiave) {
      // La chiave com'e' scritta nel file, accanto al nome in italiano: l'interfaccia si
      // legge, il file si scrive, e cosi' si vede subito quale riga si sta toccando.
      e.appendChild(el('code', 'me-chiave', chiave));
    }
    l.appendChild(e);
    l.appendChild(dentro);
    if (aiuto) l.appendChild(el('span', 'me-aiuto', aiuto));
    if (colorabile) abilitaColori(colorabile, l);
    return l;
  }

  function campoTesto(nome, valore, cambia, aiuto, colorato, chiave) {
    if (colorato) {
      var sc = campoScritto(valore, cambia, false);
      return etichettato(nome, sc, aiuto, sc.campo, chiave);
    }
    var i = el('input', 'me-inp');
    i.value = valore || '';
    i.addEventListener('input', function () { cambia(i.value); });
    return etichettato(nome, i, aiuto, null, chiave);
  }

  function campoTestoLungo(nome, valore, cambia, aiuto, colorato, chiave) {
    if (colorato) {
      var sc = campoScritto(valore, cambia, true);
      return etichettato(nome, sc, aiuto, sc.campo, chiave);
    }
    var t = el('textarea', 'me-inp me-area');
    t.value = valore || '';
    t.rows = 4;
    t.addEventListener('input', function () { cambia(t.value); });
    return etichettato(nome, t, aiuto, null, chiave);
  }

  function campoNumero(nome, valore, cambia, aiuto, chiave) {
    var i = el('input', 'me-inp me-inp-corto');
    i.type = 'number';
    i.value = valore || 0;
    i.addEventListener('input', function () { cambia(parseInt(i.value, 10) || 0); });
    return etichettato(nome, i, aiuto, null, chiave);
  }

  function campoSpunta(nome, valore, cambia, chiave) {
    var l = el('label', 'me-spunta');
    var i = el('input');
    i.type = 'checkbox';
    i.checked = !!valore;
    i.addEventListener('change', function () { cambia(i.checked); });
    l.appendChild(i);
    var testo = el('span', null, nome);
    if (chiave) testo.appendChild(el('code', 'me-chiave', chiave));
    l.appendChild(testo);
    return l;
  }

  function campoScelta(nome, valore, voci, cambia, aiuto, chiave) {
    var s = el('select', 'me-sel');
    voci.forEach(function (v) {
      var o = el('option', null, String(v).replace(/_/g, ' '));
      o.value = v;
      if (String(v) === String(valore)) o.selected = true;
      s.appendChild(o);
    });
    s.addEventListener('change', function () { cambia(s.value); });
    return etichettato(nome, s, aiuto, null, chiave);
  }

  /** Come campoScelta, ma con migliaia di voci: si scrive e si filtra (datalist del browser). */
  function campoRicerca(nome, valore, voci, cambia, aiuto, chiave) {
    var i = el('input', 'me-inp');
    i.value = valore || '';
    var idLista = 'meLista' + nome.replace(/[^A-Za-z]/g, '');
    i.setAttribute('list', idLista);
    var lista = el('datalist');
    lista.id = idLista;
    // Solo le prime: un datalist con millecinquecento voci fa arrancare il browser a ogni
    // tasto premuto, e chi cerca "diamond" ne scrive comunque qualche lettera.
    voci.slice(0, 400).forEach(function (v) {
      var o = el('option');
      o.value = v;
      lista.appendChild(o);
    });
    i.addEventListener('change', function () {
      var v = i.value.trim().toUpperCase().replace(/ /g, '_');
      if (voci.indexOf(v) < 0) {
        avviso('“' + i.value + '” non è un item del gioco: in gioco compare una barriera rossa.', true);
      }
      cambia(v);
    });
    var contenitore = el('div');
    contenitore.appendChild(i);
    contenitore.appendChild(lista);
    return etichettato(nome, contenitore, aiuto, null, chiave);
  }

  function listaTesti(nome, valori, cambia, aiuto, suggerimenti, chiave) {
    var t = el('textarea', 'me-inp me-area');
    t.value = (valori || []).join('\n');
    t.rows = 3;
    t.addEventListener('input', function () {
      cambia(t.value === '' ? [] : t.value.split('\n').filter(function (r) { return r.trim() !== ''; }));
    });
    return etichettato(nome, t, aiuto + (suggerimenti && suggerimenti.length ? ' (' + suggerimenti.length + ' disponibili)' : ''), null, chiave);
  }

  // ------------------------------------------------------------------
  //  Salvataggio
  // ------------------------------------------------------------------
  function salvaMenu(bottone) {
    var problemi = controlla();
    if (problemi.length) {
      avviso('Non salvo: ' + problemi[0], true);
      return;
    }
    if (bottone) { bottone.disabled = true; bottone.textContent = 'Salvo…'; }
    api('salva', { nome: nomeMenu, menu: JSON.stringify(menu) }).then(function (d) {
      modificato = false;
      dati.menu[nomeMenu] = copia(menu);
      if (statoTesto) statoTesto.textContent = 'salvato';
      var b = document.getElementById('meSalva');
      if (b) b.classList.remove('btn-accent');
      if (d.testo) {
        // Il PHP restituisce il file che ha appena scritto: cosi' il pannello non deve
        // rileggerlo dal server per essere aggiornato.
        testoFile = d.testo;
        var pre = document.getElementById('meFileTesto');
        if (pre) pre.textContent = testoFile;
      }
      avviso(d.messaggio + ' Ora premi “Applica al server” per vederlo in gioco.');
    }).catch(function (e) {
      avviso(e.message, true);
    }).then(function () {
      if (bottone) { bottone.disabled = false; bottone.textContent = 'Salva'; }
    });
  }

  /** I controlli che si possono fare senza il server: quelli veri li fa lui al reload. */
  function controlla() {
    var problemi = [];
    if (menu.tipo !== 'dialog' && !menu.item.length) {
      problemi.push('questo menu non ha nessun item, si aprirebbe vuoto.');
    }
    menu.item.forEach(function (i) {
      if (!i.slot || !i.slot.length) problemi.push('l’item “' + i.nome + '” non sta in nessuna casella.');
      if (!i.id) problemi.push('l’item “' + i.nome + '” non dice che oggetto è.');
    });
    (menu.comandi || []).forEach(function (c) {
      Object.keys(dati.menu).forEach(function (altro) {
        if (altro === nomeMenu) return;
        if ((dati.menu[altro].comandi || []).indexOf(c) >= 0) {
          problemi.push('il comando /' + c + ' è già del menu “' + altro + '”: lo prenderebbe uno solo dei due.');
        }
      });
    });
    return problemi;
  }
})();
