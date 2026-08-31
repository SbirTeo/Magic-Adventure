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
  }
  toggle.addEventListener('click', function () {
    var isOpen = collapse.classList.toggle('open');
    toggle.classList.toggle('is-active', isOpen);
    toggle.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
  });
  collapse.querySelectorAll('a').forEach(function (a) {
    a.addEventListener('click', close);
  });
})();

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

  function aggiungi() {
    voci.slice(messe, messe + PASSO).forEach(function (v) {
      lista.appendChild(v.cloneNode(true));
    });
    messe = Math.min(messe + PASSO, voci.length);
    altri.hidden = messe >= voci.length;
    altri.textContent = 'Carica altri (' + (voci.length - messe) + ')';
  }

  apri.addEventListener('click', function () {
    if (messe === 0) aggiungi();
    // showModal da' gratis lo sfondo scurito, il fuoco dentro e la chiusura con Esc
    if (typeof finestra.showModal === 'function') {
      finestra.showModal();
    } else {
      finestra.setAttribute('open', '');   // browser vecchi: resta una scatola normale
    }
  });
  altri.addEventListener('click', aggiungi);
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

  var segnala = function () {
    box.classList.toggle('puo-scorrere', box.scrollWidth > box.clientWidth + 1);
  };
  segnala();
  window.addEventListener('resize', segnala);

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

  function applica(scelta) {
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
      applica(ORDINE[(ORDINE.indexOf(attuale) + 1) % ORDINE.length]);
    });
  }

  // Pulsanti "Scuro / Chiaro / Automatico" (pagina del profilo)
  document.addEventListener('click', function (ev) {
    var scelta = ev.target.closest ? ev.target.closest('[data-tema-scelta]') : null;
    if (scelta) applica(scelta.dataset.temaScelta);
  });

  // All'apertura: se in memoria c'e' una scelta, la rimetto e rinnovo il cookie. Cosi' la
  // preferenza non scade mai davvero, nemmeno se il cookie e' stato buttato dal browser.
  var memorizzato = null;
  try { memorizzato = localStorage.getItem('tema'); } catch (e) { /* niente memoria */ }
  if (memorizzato && ORDINE.indexOf(memorizzato) !== -1) {
    applica(memorizzato);
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
  function testo() {
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
    var blob = new Blob([testo()], { type: 'text/plain;charset=utf-8' });
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

  var copia = bottone('⧉ Copia');
  copia.addEventListener('click', function () {
    var testoCodici = codici().join('\n');
    var fatto = function () {
      copia.textContent = '✓ Copiati';
      setTimeout(function () { copia.textContent = '⧉ Copia'; }, 2500);
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
  riga.appendChild(copia);
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

  function avvisa(bottone, messaggio) {
    var prima = bottone.textContent;
    bottone.textContent = messaggio;
    bottone.disabled = true;
    setTimeout(function () { bottone.textContent = prima; bottone.disabled = false; }, 1800);
  }

  function copia(bottone) {
    function ripiego() {
      var area = document.createElement('textarea');
      area.value = dati.url;
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      try { document.execCommand('copy'); avvisa(bottone, '✓ Link copiato'); } catch (e) { /* pazienza */ }
      document.body.removeChild(area);
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(dati.url).then(function () {
        avvisa(bottone, '✓ Link copiato');
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
        copia(principale);
      }
    });
  }
  if (copiaBtn) copiaBtn.addEventListener('click', function () { copia(copiaBtn); });
})();


// Conto alla rovescia dell'apertura: il PORTALE DEL NETHER in home.
// Markup e testi in includes/countdown.php; qui si muovono i numeri e si regola --carica,
// da cui dipendono il vortice, l'alone, le scintille e il punto in cui e' arrivata la
// fiamma della miccia (0 = giorno dell'annuncio, 1 = apertura).
(function () {
  var box = document.querySelector('[data-countdown]');
  if (!box) return;

  var fine = parseInt(box.getAttribute('data-fine'), 10);
  var inizio = parseInt(box.getAttribute('data-inizio'), 10);
  var adessoServer = parseInt(box.getAttribute('data-adesso'), 10);
  if (!fine || !adessoServer) return;

  // Scarto fra l'orologio del server e quello di chi guarda: sui telefoni con la data
  // sbagliata, senza questo, il conto direbbe qualsiasi cosa.
  var scarto = adessoServer - Date.now();

  var gruppoGiorni = box.querySelector('[data-giorni]');
  var celle = {
    g: box.querySelector('[data-g]'),
    h: box.querySelector('[data-h]'),
    m: box.querySelector('[data-m]'),
    s: box.querySelector('[data-s]')
  };
  if (!celle.g || !celle.h || !celle.m || !celle.s) return;

  function due(n) { return n < 10 ? '0' + n : String(n); }

  /*
   * Di quanti pixel deve salire il corridore lungo il percorso.
   *
   * Parte dalla miccia e deve arrivare alla SOGLIA del portale, cioe' sopra la fila di
   * ossidiana in basso: da li' in poi ci cammina dentro. Quanto sia distante dipende da
   * dove cade il portale nella pagina (larghezza della finestra, testo su piu' righe,
   * telefono o schermo grande), quindi si misura qui e non nel foglio di stile.
   *
   * L'altezza di un blocco si ricava dal portale stesso: e' alto cinque blocchi. Leggere
   * --blocco non servirebbe, perche' e' una clamp() e il browser la restituisce com'e'
   * scritta invece che in pixel.
   */
  var portale = box.querySelector('.nether-portale');
  var miccia = box.querySelector('.nether-miccia');

  function misuraSalita() {
    if (!portale || !miccia) return;
    var rp = portale.getBoundingClientRect();
    var rm = miccia.getBoundingClientRect();
    if (!rp.height || !rm.height) return;
    var blocco = rp.height / 5;
    var quotaSoglia = rp.bottom - blocco;    // dove deve arrivare
    var quotaPartenza = rm.bottom - 13;      // dove sta adesso (vedi .corridore nel CSS)
    box.style.setProperty('--salita', Math.max(0, quotaPartenza - quotaSoglia).toFixed(1) + 'px');
  }

  misuraSalita();
  // le immagini che arrivano dopo spostano il portale: si rimisura a pagina caricata
  window.addEventListener('load', misuraSalita);
  var attesaSalita;
  window.addEventListener('resize', function () {
    clearTimeout(attesaSalita);
    attesaSalita = setTimeout(misuraSalita, 150);
  });

  function scrivi(cella, testo) {
    if (cella.textContent !== testo) cella.textContent = testo;
  }

  var timer;

  function passo() {
    var ora = Date.now() + scarto;
    var restano = fine - ora;

    if (restano <= 0) {
      box.classList.add('is-aperto');
      clearInterval(timer);
      return;
    }

    var sec = Math.floor(restano / 1000);
    var giorni = Math.floor(sec / 86400);

    // Sotto il giorno la parola "giorni" sparisce: "0 giorni 04:12:07" e' solo rumore,
    // e nell'ultima giornata l'orologio da solo e' piu' teso.
    if (gruppoGiorni) {
      if (giorni > 0) {
        gruppoGiorni.hidden = false;
        scrivi(celle.g, String(giorni));
      } else {
        gruppoGiorni.hidden = true;
      }
    }
    scrivi(celle.h, due(Math.floor((sec % 86400) / 3600)));
    scrivi(celle.m, due(Math.floor((sec % 3600) / 60)));
    scrivi(celle.s, due(sec % 60));

    // Carica del portale: quanta strada e' stata fatta fra l'annuncio e l'apertura.
    if (inizio && fine > inizio) {
      var quota = (ora - inizio) / (fine - inizio);
      quota = Math.max(0, Math.min(1, quota));
      box.style.setProperty('--carica', quota.toFixed(3));
    }
  }

  passo();
  timer = setInterval(passo, 1000);
})();
