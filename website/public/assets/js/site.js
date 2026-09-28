// Larghezza della barra di scorrimento verticale -> --sbw, usata dalle fasce a tutta
// larghezza per non sforare (100vw include la barra e farebbe comparire lo scroll orizzontale).
(function () {
  var ultima = null;
  function misura() {
    var sbw = window.innerWidth - document.documentElement.clientWidth;
    var valore = (sbw > 0 ? sbw : 0) + 'px';
    // Scrivere solo quando cambia: la proprieta' modifica la larghezza delle fasce, e con
    // ResizeObserver una scrittura a ogni notifica innescherebbe un giro infinito.
    if (valore === ultima) return;
    ultima = valore;
    document.documentElement.style.setProperty('--sbw', valore);
  }
  misura();
  window.addEventListener('resize', misura);
  window.addEventListener('load', misura);
  // La barra di scorrimento verticale compare solo quando la pagina diventa piu' alta della
  // finestra: al primo caricamento (immagini e font non ancora arrivati) spesso non c'e'
  // ancora, la misura da' 0 e le fasce a 100vw restano piu' larghe del contenuto (~8px di
  // scorrimento orizzontale) finche' non si ridimensiona la finestra. Rimisurando a ogni
  // cambio di altezza del documento il valore si corregge da solo appena la barra compare.
  if (window.ResizeObserver) {
    new ResizeObserver(misura).observe(document.documentElement);
  }
})();

// Altezza vera della barra di navigazione -> --h-testata. Ci si appoggia tutto cio' che
// deve fermarsi SOTTO la barra mentre si scorre (la colonna laterale, l'indice della guida).
// Va misurata e non scritta a mano: cambia col logo, col tema e quando le voci del menu
// vanno a capo su finestre strette.
(function () {
  var testata = document.querySelector('.site-header');
  if (!testata) return;
  var ultima = null;
  function misura() {
    var valore = Math.round(testata.getBoundingClientRect().height) + 'px';
    if (valore === ultima) return;
    ultima = valore;
    document.documentElement.style.setProperty('--h-testata', valore);
  }
  misura();
  window.addEventListener('resize', misura);
  window.addEventListener('load', misura);
  // I caratteri arrivano dopo il primo disegno e cambiano l'altezza della barra.
  if (window.ResizeObserver) new ResizeObserver(misura).observe(testata);
})();

(function () {
  var toggle = document.getElementById('navToggle');
  var collapse = document.getElementById('navCollapse');
  if (!toggle || !collapse) return;
  function close() {
    collapse.classList.remove('open');
    toggle.classList.remove('is-active');
    toggle.setAttribute('aria-expanded', 'false');
    // Su telefono il menu copre tutto lo schermo: senza questo la pagina sotto restava
    // scorribile col dito, invisibile ma viva, e si scorreva "alla cieca" col menu aperto.
    document.body.classList.remove('menu-aperto');
  }
  toggle.addEventListener('click', function () {
    var isOpen = collapse.classList.toggle('open');
    toggle.classList.toggle('is-active', isOpen);
    toggle.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
    document.body.classList.toggle('menu-aperto', isOpen);
  });
  collapse.querySelectorAll('a').forEach(function (a) {
    a.addEventListener('click', close);
  });
})();

// Selettore di lingua (<details> nativo): da solo un <details> resta aperto finche' non si
// clicca di nuovo sul suo <summary>, un clic altrove sulla pagina non lo chiude. Qui si chiude
// anche cosi', come ci si aspetta da un menu a tendina qualsiasi.
document.querySelectorAll('.cambia-lingua').forEach(function (dettagli) {
  document.addEventListener('click', function (ev) {
    // Il clic che il browser genera al rilascio di un trascinamento (vedi sotto) non e' un
    // "clic altrove" da gestire qui.
    if (saltaClic) {
      saltaClic = false;
      return;
    }
    if (dettagli.hasAttribute('open') && !dettagli.contains(ev.target)) {
      dettagli.removeAttribute('open');
    }
  });

  // Trascinamento: si preme sulla bandiera e si tira giu'. La tendina NON si apre: le bandiere
  // scorrono come un rullo dentro il pulsante (dall'alto scende quella della lingua dopo), sotto
  // compare il nome della lingua che si sta per scegliere e rilasciando ci si va. Il tocco
  // semplice resta quello del <details>, che apre la tendina.
  var summary = dettagli.querySelector('summary');
  var menu = dettagli.querySelector('.cambia-lingua-menu');
  var bandiera = summary ? summary.querySelector('.bandiera-lingua') : null;
  if (!summary || !menu || !bandiera) return;
  // Solo le lingue vere, non "Automatica" (che non ha data-lang).
  var voci = Array.prototype.slice.call(menu.querySelectorAll('a[data-lang]'));
  var attuale = voci.findIndex(function (a) { return a.classList.contains('active'); });
  if (voci.length < 2 || attuale < 0) return;
  var SOGLIA = 10; // px di discesa prima che conti come trascinamento e non come tocco
  var DITO = 40;   // px di dito per passare alla lingua dopo
  var PASSO = 20;  // altezza di una bandiera (14px) + lo spazio fra una e l'altra nel rullo
  var ultima = voci.length - 1;
  var inizioY = null;
  var trascina = false;
  var saltaClic = false;
  var scelta = 0;

  // Il rullo: dall'alto in basso le lingue che vengono dopo quella attuale (la piu' vicina
  // subito sopra), e in fondo la bandiera di adesso, l'unica visibile a riposo.
  var rullo = document.createElement('span');
  rullo.className = 'bandiera-rullo';
  var striscia = document.createElement('span');
  striscia.className = 'bandiera-striscia';
  for (var k = ultima; k >= 1; k--) {
    var copia = voci[(attuale + k) % voci.length].querySelector('.bandiera-lingua');
    if (copia) striscia.appendChild(copia.cloneNode(true));
  }
  bandiera.parentNode.insertBefore(rullo, bandiera);
  striscia.appendChild(bandiera);
  rullo.appendChild(striscia);
  var etichetta = document.createElement('span');
  etichetta.className = 'cambia-lingua-etichetta';
  etichetta.setAttribute('aria-hidden', 'true');
  // Dentro il <summary>, non accanto: il resto di un <details> chiuso non si vede proprio.
  summary.appendChild(etichetta);

  function moveStrip(passi) {
    striscia.style.transform = 'translateY(' + ((passi - ultima) * PASSO) + 'px)';
  }
  moveStrip(0);
  function showLabel(indice) {
    etichetta.textContent = voci[(attuale + indice) % voci.length].textContent.trim();
    etichetta.classList.toggle('uguale', indice === 0);
    etichetta.classList.add('visibile');
  }
  // Come far scoprire il gesto: le prime volte, da telefono, il rullo scende da solo di mezza
  // bandiera e torna su come una molla (vedi playHint). Smette per sempre la prima volta che il
  // visitatore trascina davvero; da quel momento sparisce anche il suggerimento nella tendina.
  var INVITI = 3;
  var memoria = { visite: 0, usato: false };
  try {
    var salvata = JSON.parse(localStorage.getItem('lingua-rullo') || 'null');
    if (salvata) memoria = salvata;
  } catch (e) { /* niente memoria: si comporta come alla prima visita */ }
  function remember() {
    try { localStorage.setItem('lingua-rullo', JSON.stringify(memoria)); } catch (e) { /* navigazione privata */ }
  }
  if (memoria.usato) dettagli.classList.add('rullo-usato');
  function playHint() {
    if (inizioY !== null || dettagli.hasAttribute('open')) return;
    striscia.classList.remove('torna');
    striscia.classList.add('invito');
    moveStrip(0.6);
    setTimeout(function () {
      if (inizioY === null) moveStrip(0);
      setTimeout(function () { striscia.classList.remove('invito'); }, 700);
    }, 450);
  }
  var tocco = window.matchMedia && window.matchMedia('(pointer: coarse)').matches;
  var calma = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (tocco && !calma && !memoria.usato && (memoria.visite || 0) < INVITI) {
    memoria.visite = (memoria.visite || 0) + 1;
    remember();
    setTimeout(playHint, 1800);
  }

  function reset() {
    inizioY = null;
    trascina = false;
    scelta = 0;
    striscia.classList.add('torna');
    moveStrip(0);
    etichetta.classList.remove('visibile');
  }

  summary.addEventListener('pointerdown', function (ev) {
    if (ev.button !== 0) return;
    inizioY = ev.clientY;
    trascina = false;
    // Subito, non a soglia superata: se il puntatore esce dalla bandiera prima dei 10px
    // (col mouse succede, col dito no) la bandiera non ne vedrebbe piu' i movimenti.
    try { summary.setPointerCapture(ev.pointerId); } catch (e) { /* puntatore gia' sparito */ }
  });
  summary.addEventListener('pointermove', function (ev) {
    if (inizioY === null) return;
    var discesa = ev.clientY - inizioY;
    if (!trascina) {
      if (discesa < SOGLIA) return;
      trascina = true;
      dettagli.removeAttribute('open');
      striscia.classList.remove('torna', 'invito');
      if (!memoria.usato) {
        memoria.usato = true;
        remember();
        dettagli.classList.add('rullo-usato');
      }
    }
    ev.preventDefault();
    // Segue il dito passo passo; oltre l'ultima lingua (o sopra la prima) fa resistenza.
    var passi = Math.max(0, discesa - SOGLIA) / DITO;
    if (passi > ultima) passi = ultima + (passi - ultima) * 0.25;
    moveStrip(passi);
    var ora = Math.min(ultima, Math.round(passi));
    if (ora !== scelta || !etichetta.classList.contains('visibile')) {
      scelta = ora;
      showLabel(scelta);
    }
  });
  summary.addEventListener('pointerup', function () {
    if (inizioY === null) return;
    if (!trascina) {
      inizioY = null;
      return;
    }
    // Il clic che segue il rilascio aprirebbe la tendina: qui non deve.
    saltaClic = true;
    setTimeout(function () { saltaClic = false; }, 400);
    if (scelta === 0) {
      reset();
      return;
    }
    var voce = voci[(attuale + scelta) % voci.length];
    inizioY = null;
    trascina = false;
    striscia.classList.add('torna');
    moveStrip(scelta);
    rullo.classList.add('scelta');
    setTimeout(function () { window.location.href = voce.href; }, 260);
  });
  summary.addEventListener('pointercancel', reset);
  summary.addEventListener('click', function (ev) {
    if (saltaClic) {
      ev.preventDefault();
      saltaClic = false;
    }
  }, true);
});

document.querySelectorAll('.ip-copy').forEach(function (btn) {
  btn.addEventListener('click', function () {
    var ip = btn.getAttribute('data-ip');
    var valueEl = btn.querySelector('.ip-copy-value');
    var restore = valueEl.textContent;
    var done = function () {
      btn.classList.add('copied');
      valueEl.textContent = 'Copiato!';
      setTimeout(function () {
        btn.classList.remove('copied');
        valueEl.textContent = restore;
      }, 1500);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(ip).then(done).catch(done);
    } else {
      done();
    }
  });
});

/* "Sul sito ora": il pulsante "e altri N" scopre le voci oltre le prime — sono gia' nella
   pagina, solo nascoste, quindi non serve nessuna richiesta al server. */
/* "e altri N…" nel riquadro "Sul sito ora": apre una finestrella con l'elenco completo,
   venti nomi per volta. Le voci sono gia' nella pagina dentro un <template>, quindi aprirla
   non chiede niente al server. */
(function () {
  var apri = document.getElementById('apriOnline');
  var finestra = document.getElementById('finestraOnline');
  var dati = document.getElementById('datiOnline');
  var lista = document.getElementById('listaOnline');
  var altri = document.getElementById('altriOnline');
  var chiudi = document.getElementById('chiudiOnline');
  if (!apri || !finestra || !dati || !lista || !altri) return;

  var PASSO = 20;
  var voci = [].slice.call(dati.content.querySelectorAll('.online-voce'));
  var messe = 0;

  function add() {
    voci.slice(messe, messe + PASSO).forEach(function (v) {
      lista.appendChild(v.cloneNode(true));
    });
    messe = Math.min(messe + PASSO, voci.length);
    altri.hidden = messe >= voci.length;
    altri.textContent = 'Carica altri (' + (voci.length - messe) + ')';
  }

  apri.addEventListener('click', function () {
    if (messe === 0) add();
    // showModal da' gratis lo sfondo scurito, il fuoco dentro e la chiusura con Esc
    if (typeof finestra.showModal === 'function') {
      finestra.showModal();
    } else {
      finestra.setAttribute('open', '');   // browser vecchi: resta una scatola normale
    }
  });
  altri.addEventListener('click', add);
  if (chiudi) {
    chiudi.addEventListener('click', function () {
      if (typeof finestra.close === 'function') finestra.close();
      else finestra.removeAttribute('open');
    });
  }
  // Clic fuori dal contenuto = chiudi (il <dialog> riceve il clic sullo sfondo scurito)
  finestra.addEventListener('click', function (e) {
    if (e.target === finestra && typeof finestra.close === 'function') finestra.close();
  });
})();

/* Trascinamento per scorrere di lato (UUID e simili: .scorri-trascinando).
   Col dito il browser lo fa gia' da solo; qui si aggiunge il mouse. Il trascinamento parte
   solo dopo qualche pixel, cosi' un clic secco continua a selezionare il testo per copiarlo. */
document.querySelectorAll('.scorri-trascinando').forEach(function (box) {
  var SOGLIA = 4;
  var giu = false, trascina = false, xIniziale = 0, scorrIniziale = 0;

  var report = function () {
    box.classList.toggle('puo-scorrere', box.scrollWidth > box.clientWidth + 1);
  };
  report();
  window.addEventListener('resize', report);

  box.addEventListener('pointerdown', function (e) {
    if (e.pointerType !== 'mouse' || e.button !== 0) return;
    giu = true;
    trascina = false;
    xIniziale = e.clientX;
    scorrIniziale = box.scrollLeft;
  });

  box.addEventListener('pointermove', function (e) {
    if (!giu) return;
    var spostamento = e.clientX - xIniziale;
    if (!trascina && Math.abs(spostamento) < SOGLIA) return;
    if (!trascina) {
      trascina = true;
      box.classList.add('sta-trascinando');
      box.setPointerCapture(e.pointerId);
    }
    box.scrollLeft = scorrIniziale - spostamento;
    e.preventDefault();
  });

  var fine = function (e) {
    if (trascina && e.pointerId !== undefined && box.hasPointerCapture(e.pointerId)) {
      box.releasePointerCapture(e.pointerId);
    }
    giu = false;
    trascina = false;
    box.classList.remove('sta-trascinando');
  };
  box.addEventListener('pointerup', fine);
  box.addEventListener('pointercancel', fine);
});

/* ---- Interruttore del tema: scuro → chiaro → automatico ----------------------------
   La scelta finisce in un cookie (un anno), cosi' il server puo' gia' scrivere il tema
   giusto nell'HTML della pagina dopo e non si vede nessun lampo di colore sbagliato. */
(function () {
  var ORDINE = ['scuro', 'chiaro', 'auto'];
  var ICONE = { scuro: '☾', chiaro: '☀', auto: '◐' };
  var NOMI = { scuro: 'Tema scuro', chiaro: 'Tema chiaro', auto: 'Tema automatico' };
  var radice = document.documentElement;
  var chiaroDiSistema = window.matchMedia('(prefers-color-scheme: light)');
  var bottone = document.getElementById('cambiaTema');

  // La scelta NON scade. Il cookie da solo non basta: i browser tagliano qualunque
  // scadenza a 400 giorni (Chrome e Firefox lo impongono, non e' una scelta nostra).
  // Quindi il valore vero sta in localStorage, che non scade, e il cookie — che serve
  // solo al server per scrivere il tema giusto gia' nell'HTML — viene riscritto a ogni
  // visita col massimo consentito. Finche' non si cancellano i dati del sito, resta.
  var GIORNI_MAX = 60 * 60 * 24 * 400;

  function ricorda(scelta) {
    try { localStorage.setItem('tema', scelta); } catch (e) { /* navigazione privata */ }
    document.cookie = 'tema=' + scelta + ';path=/;max-age=' + GIORNI_MAX + ';samesite=lax';
  }

  function apply(scelta) {
    if (ORDINE.indexOf(scelta) === -1) return;
    radice.dataset.temaScelto = scelta;
    radice.dataset.tema = scelta === 'auto'
      ? (chiaroDiSistema.matches ? 'chiaro' : 'scuro')
      : scelta;
    ricorda(scelta);

    if (bottone) {
      bottone.querySelector('.cambia-tema-icona').textContent = ICONE[scelta];
      var etichetta = bottone.querySelector('.cambia-tema-testo');
      if (etichetta) etichetta.textContent = NOMI[scelta].replace('Tema ', '');
      bottone.title = NOMI[scelta] + ' — clicca per cambiare';
      bottone.setAttribute('aria-label', NOMI[scelta]);
    }
    // Scelta esplicita nel profilo: segno quale delle tre e' attiva
    document.querySelectorAll('[data-tema-scelta]').forEach(function (b) {
      b.classList.toggle('is-attivo', b.dataset.temaScelta === scelta);
      b.setAttribute('aria-pressed', b.dataset.temaScelta === scelta ? 'true' : 'false');
    });
  }

  if (bottone) {
    bottone.addEventListener('click', function () {
      var attuale = radice.dataset.temaScelto || 'scuro';
      apply(ORDINE[(ORDINE.indexOf(attuale) + 1) % ORDINE.length]);
    });
  }

  // Pulsanti "Scuro / Chiaro / Automatico" (pagina del profilo)
  document.addEventListener('click', function (ev) {
    var scelta = ev.target.closest ? ev.target.closest('[data-tema-scelta]') : null;
    if (scelta) apply(scelta.dataset.temaScelta);
  });

  // All'apertura: se in memoria c'e' una scelta, la rimetto e rinnovo il cookie. Cosi' la
  // preferenza non scade mai davvero, nemmeno se il cookie e' stato buttato dal browser.
  var memorizzato = null;
  try { memorizzato = localStorage.getItem('tema'); } catch (e) { /* niente memoria */ }
  if (memorizzato && ORDINE.indexOf(memorizzato) !== -1) {
    apply(memorizzato);
  }
  // Se non ha mai scelto non salvo niente: cosi' continua a valere il tema di partenza
  // deciso nel gestionale, e cambiandolo lo vedono anche i visitatori vecchi.

  // Allineo lo stato iniziale dei pulsanti del profilo
  document.querySelectorAll('[data-tema-scelta]').forEach(function (b) {
    var attivo = b.dataset.temaScelta === (radice.dataset.temaScelto || 'scuro');
    b.classList.toggle('is-attivo', attivo);
    b.setAttribute('aria-pressed', attivo ? 'true' : 'false');
  });
})();

/* ---- La colonna di destra parte alla stessa altezza del primo riquadro --------------
   Il blocco del titolo cambia altezza da pagina a pagina (il forum ha anche sottotitolo e
   numeri), quindi un margine fisso sbaglia sempre da qualche parte: qui si misura e basta.
   Il valore nel CSS resta come ripiego per chi non ha JavaScript. */
(function () {
  var lato = document.querySelector('.side-col');
  var principale = document.querySelector('.content-main');
  if (!lato || !principale) return;

  // Primo elemento "di contenuto" della colonna: si saltano SOLO i blocchi del titolo
  // (titolo, briciole, intestazione del forum). Elencare invece i contenuti da agganciare
  // era sbagliato: in home, davanti alla griglia degli articoli, ci sono il banner e
  // l'obiettivo, e la colonna finiva molto piu' in basso di dove deve stare.
  var SALTA = 'h1, h2, .page-title, .briciole, .forum-testata, .profilo-barra, .solo-lettori';
  var primo = null;
  for (var i = 0; i < principale.children.length; i++) {
    var el = principale.children[i];
    if (el.matches(SALTA) || el.getBoundingClientRect().height === 0) continue;
    primo = el;
    break;
  }
  if (!primo) return;

  function allinea() {
    // Sotto i 900px la colonna va sotto al contenuto: nessuno stacco da recuperare
    if (window.matchMedia('(max-width: 900px)').matches) {
      lato.style.marginTop = '0px';
      return;
    }
    // Si CORREGGE l'errore residuo invece di ricalcolare da zero: si guarda di quanto la
    // colonna e' ancora disallineata rispetto al primo riquadro e si aggiusta il margine
    // di quel tanto. Cosi' non serve sapere come e' fatta la griglia (righe, spaziature,
    // elementi nascosti): qualunque sia la geometria, il conto converge da solo.
    var attuale = parseFloat(getComputedStyle(lato).marginTop) || 0;
    var scarto = primo.getBoundingClientRect().top - lato.getBoundingClientRect().top;
    if (Math.abs(scarto) < 1) return;   // gia' allineata: non tocco niente
    lato.style.marginTop = Math.max(0, Math.round(attuale + scarto)) + 'px';
  }

  allinea();
  window.addEventListener('resize', allinea);
  // I caratteri web cambiano l'altezza del titolo quando finiscono di caricare
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(allinea);
  }
  // E qualunque altra cosa cambi l'altezza del blocco del titolo (immagini, avvisi che
  // compaiono, riquadri che si aprono): si rimisura invece di indovinare il momento.
  // Non c'e' rischio di giro infinito: lo stacco si applica alla colonna di DESTRA, che
  // non e' quella osservata.
  if (window.ResizeObserver) {
    new ResizeObserver(allinea).observe(principale);
  }
})();

/* ---- CODICI DI RECUPERO: SCARICA E COPIA -------------------------------------------
   I dieci codici di scorta della verifica in due passaggi si vedono UNA volta sola, e
   ricopiarli a mano dallo schermo e' il modo migliore per sbagliare una lettera e
   scoprirlo il giorno in cui servono davvero. Qui si prendono dalla pagina e si
   trasformano in un file di testo (o negli appunti), senza passare dal server: sono
   gia' nella pagina, e un giro in piu' sulla rete per un segreto non serve a nessuno. */
(function () {
  var elenco = document.querySelector('.otp-codici');
  if (!elenco) return;

  function codici() {
    return Array.prototype.map.call(elenco.querySelectorAll('li'), function (li) {
      return li.textContent.trim();
    });
  }

  /** Il file che si scarica: i codici, ma anche di che sito sono e come si usano. */
  function text() {
    var oggi = new Date().toLocaleDateString('it-IT');
    return [
      'MAGICADVENTURE — codici di recupero',
      'Account: ' + (elenco.dataset.utente || '—'),
      'Generati il ' + oggi,
      '',
      'Servono per entrare sul sito (magicadventure.it) e in gioco (/otp <codice>)',
      'quando non hai con te il telefono con l\'app.',
      'OGNI CODICE VALE UNA VOLTA SOLA. Tienili lontano dall\'app che genera i codici.',
      '',
    ].concat(codici()).join('\r\n') + '\r\n';
  }

  function bottone(etichetta) {
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'btn btn-ghost btn-small';
    b.textContent = etichetta;
    return b;
  }

  var riga = document.createElement('div');
  riga.className = 'otp-azioni';

  var scarica = bottone('⤓ Scarica i codici');
  scarica.addEventListener('click', function () {
    var blob = new Blob([text()], { type: 'text/plain;charset=utf-8' });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = 'magicadventure-codici-recupero.txt';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    // L'indirizzo temporaneo si libera subito dopo: il file e' gia' partito.
    setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    scarica.textContent = '✓ Scaricato';
    setTimeout(function () { scarica.textContent = '⤓ Scarica i codici'; }, 2500);
  });

  var clone = bottone('⧉ Copia');
  clone.addEventListener('click', function () {
    var testoCodici = codici().join('\n');
    var fatto = function () {
      clone.textContent = '✓ Copiati';
      setTimeout(function () { clone.textContent = '⧉ Copia'; }, 2500);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(testoCodici).then(fatto, ripiego);
    } else {
      ripiego();
    }
    // Senza permesso per gli appunti (o su http) si passa dalla vecchia scorciatoia.
    function ripiego() {
      var area = document.createElement('textarea');
      area.value = testoCodici;
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      try { document.execCommand('copy'); fatto(); } catch (e) { /* pazienza */ }
      document.body.removeChild(area);
    }
  });

  var stampa = bottone('🖨 Stampa');
  stampa.addEventListener('click', function () { window.print(); });

  riga.appendChild(scarica);
  riga.appendChild(clone);
  riga.appendChild(stampa);
  elenco.parentNode.insertBefore(riga, elenco.nextSibling);
})();


/* ---- Condividi un articolo -----------------------------------------------------------
   Un pulsante solo che fa la cosa giusta sul posto giusto: sul telefono apre il menu di
   condivisione del sistema (dove ci sono Discord, WhatsApp, le note...), sul computer —
   dove quel menu non esiste — copia il link negli appunti e lo dice. Accanto restano i
   collegamenti diretti, che funzionano anche senza JavaScript perche' sono <a href> veri:
   qui non si tocca niente di loro. */
(function () {
  var barra = document.querySelector('[data-condividi]');
  if (!barra) return;

  var dati = {
    title: barra.getAttribute('data-titolo') || document.title,
    text: barra.getAttribute('data-testo') || '',
    url: barra.getAttribute('data-url') || location.href
  };
  var principale = barra.querySelector('[data-azione="condividi"]');
  var copiaBtn = barra.querySelector('[data-azione="copia"]');
  var haMenuSistema = typeof navigator.share === 'function';

  if (!haMenuSistema && principale) {
    // Senza menu di sistema il pulsante grande diventa "Copia link", e quello piccolo
    // accanto sparisce: due pulsanti che fanno la stessa cosa sono solo confusione.
    principale.textContent = 'Copia link';
    if (copiaBtn) copiaBtn.remove();
  }

  function notify(bottone, toast) {
    var prima = bottone.textContent;
    bottone.textContent = toast;
    bottone.disabled = true;
    setTimeout(function () { bottone.textContent = prima; bottone.disabled = false; }, 1800);
  }

  function clone(bottone) {
    function ripiego() {
      var area = document.createElement('textarea');
      area.value = dati.url;
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      try { document.execCommand('copy'); notify(bottone, '✓ Link copiato'); } catch (e) { /* pazienza */ }
      document.body.removeChild(area);
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(dati.url).then(function () {
        notify(bottone, '✓ Link copiato');
      }, ripiego);
    } else {
      ripiego();
    }
  }

  if (principale) {
    principale.addEventListener('click', function () {
      if (haMenuSistema) {
        // Se l'utente chiude il menu senza scegliere, navigator.share rifiuta la promessa:
        // non e' un errore da segnalare, e' semplicemente un ripensamento.
        navigator.share(dati).catch(function () { /* ha cambiato idea */ });
      } else {
        clone(principale);
      }
    });
  }
  if (copiaBtn) copiaBtn.addEventListener('click', function () { clone(copiaBtn); });
})();
