/**
 * Guida dei giocatori (/tutorial): ricerca, indice che segue la lettura, "Capitoli" da telefono.
 *
 * La guida sta nella pagina (#guidaDoc, vedi includes/guide_page.php): la ricerca lavora sul suo
 * testo, qui nel browser, senza chiedere niente al server. Ogni capitolo e ogni paragrafo con un
 * titolo (h3) e' una voce; si cerca senza badare a maiuscole e accenti, si saltano le parole che
 * non dicono niente ("come", "si", "una"...) e si accettano desinenze diverse (perdo / perdi,
 * territorio / territori). I risultati sono i paragrafi, coi titoli che pesano piu' del testo, e
 * sotto ognuno la riga in cui c'e' la parola cercata.
 */
(function () {
  'use strict';

  var doc = document.getElementById('guidaDoc');
  var lato = document.getElementById('guidaLato');
  var campo = document.getElementById('guidaCerca');
  var box = document.getElementById('guidaRisultati');
  var indice = document.getElementById('guidaIndice');
  var btnCapitoli = document.getElementById('guidaCapitoliBtn');
  if (!doc || !lato) return;

  // ---------------------------------------------------------------- altezza della testata
  // La barra del sito e' fissa e non e' alta uguale ovunque (due righe su schermo largo, una da
  // telefono): la colonna fissa e i salti ai capitoli devono partire SOTTO di lei.
  var testata = document.querySelector('.site-header');
  function measureHeader() {
    var h = testata ? testata.getBoundingClientRect().height : 64;
    document.documentElement.style.setProperty('--testata-h', Math.round(h) + 'px');
  }
  measureHeader();
  window.addEventListener('resize', measureHeader);
  if (window.ResizeObserver && testata) new ResizeObserver(measureHeader).observe(testata);

  // ---------------------------------------------------------------- testo normalizzato
  /** Carattere per carattere (stessa lunghezza dell'originale): minuscolo e senza accenti. */
  function normalize(testo) {
    var out = '';
    for (var i = 0; i < testo.length; i++) {
      var c = testo[i];
      var n = c.normalize ? c.normalize('NFD')[0] : c;
      out += (n || c).toLowerCase();
    }
    return out;
  }

  var VUOTE = ' a ad al alla alle allo ai agli anche c che chi ci come con cosa cose da dal dalla dei del della delle dello di do e ed fa fare gli ho i il in io la le lo ma mi ne nel nella non o per piu puo qual quale quando quanto quanta quanti quante se si sono su sul sulla ti tu un una uno vuol ';
  /** La radice con cui si cerca una parola: abbastanza corta da coprire le desinenze. */
  function stem(parola) {
    if (parola.length > 6) return parola.slice(0, 5);
    if (parola.length > 4) return parola.slice(0, parola.length - 1);
    return parola;
  }
  function tokenize(q) {
    return normalize(q).split(/[^a-z0-9/]+/).filter(function (p) {
      return p.length >= 2 && VUOTE.indexOf(' ' + p + ' ') < 0;
    }).map(stem);
  }

  // ---------------------------------------------------------------- indice di ricerca
  var voci = [];
  Array.prototype.forEach.call(doc.querySelectorAll('section[id]'), function (sez) {
    var h2 = sez.querySelector('h2');
    var numero = h2 && h2.querySelector('.n') ? h2.querySelector('.n').textContent.trim() : '';
    var capitolo = h2 ? h2.textContent.replace(numero, '').trim() : '';
    var corrente = { id: sez.id, titolo: capitolo, sotto: '', testo: '' };
    voci.push(corrente);
    Array.prototype.forEach.call(sez.children, function (el) {
      if (el.tagName === 'H2' || (el.matches && el.matches('nav.chips'))) return;
      if (el.tagName === 'H3' && el.id) {
        corrente = { id: el.id, titolo: capitolo, sotto: el.textContent.trim(), testo: '' };
        voci.push(corrente);
        return;
      }
      corrente.testo += ' ' + el.textContent.replace(/\s+/g, ' ').trim();
    });
  });
  voci.forEach(function (v) {
    v.testo = v.testo.trim();
    v.nTitolo = normalize(v.titolo + ' ' + v.sotto);
    v.nTesto = normalize(v.testo);
  });

  function esc(s) {
    return s.replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; });
  }

  /** La riga di testo intorno alla prima parola trovata, con le parole cercate in grassetto. */
  function snippet(voce, radici) {
    var primo = -1;
    radici.forEach(function (r) {
      var i = voce.nTesto.indexOf(r);
      if (i >= 0 && (primo < 0 || i < primo)) primo = i;
    });
    if (primo < 0) return esc(voce.testo.slice(0, 120)) + (voce.testo.length > 120 ? '…' : '');
    var da = Math.max(0, primo - 45), a = Math.min(voce.testo.length, primo + 115);
    // Si comincia e si finisce su una parola intera.
    if (da > 0) { var sp = voce.testo.indexOf(' ', da); if (sp > 0 && sp < primo) da = sp + 1; }
    if (a < voce.testo.length) { var sp2 = voce.testo.lastIndexOf(' ', a); if (sp2 > primo) a = sp2; }
    var pezzo = voce.testo.slice(da, a), nPezzo = voce.nTesto.slice(da, a);
    // Si segna in grassetto ogni parola che comincia con una delle radici.
    var segni = new Array(pezzo.length + 1).join('0').split('');
    radici.forEach(function (r) {
      var i = 0;
      while ((i = nPezzo.indexOf(r, i)) >= 0) {
        var fine = i;
        while (fine < nPezzo.length && /[a-z0-9]/.test(nPezzo[fine])) fine++;
        for (var k = i; k < fine; k++) segni[k] = '1';
        i = fine || i + 1;
      }
    });
    var html = '', dentro = false;
    for (var j = 0; j < pezzo.length; j++) {
      if (segni[j] === '1' && !dentro) { html += '<strong>'; dentro = true; }
      if (segni[j] !== '1' && dentro) { html += '</strong>'; dentro = false; }
      html += esc(pezzo[j]);
    }
    if (dentro) html += '</strong>';
    return (da > 0 ? '…' : '') + html + (a < voce.testo.length ? '…' : '');
  }

  function search(q) {
    var radici = tokenize(q);
    if (!radici.length) return [];
    var minimo = Math.max(1, Math.ceil(radici.length / 2));
    var trovati = [];
    voci.forEach(function (v, ordine) {
      var punti = 0, prese = 0;
      radici.forEach(function (r) {
        var t = v.nTitolo.indexOf(r) >= 0, x = v.nTesto.indexOf(r) >= 0;
        if (t || x) prese++;
        if (t) punti += 4;
        if (x) punti += 1;
      });
      if (prese >= minimo) trovati.push({ voce: v, punti: punti + prese * 3, ordine: ordine });
    });
    trovati.sort(function (a, b) { return b.punti - a.punti || a.ordine - b.ordine; });
    return trovati.slice(0, 7).map(function (t) { return { voce: t.voce, html: snippet(t.voce, radici) }; });
  }

  // ---------------------------------------------------------------- risultati
  var attivo = -1;
  function closeResults() {
    if (!box) return;
    box.hidden = true;
    box.innerHTML = '';
    attivo = -1;
    if (campo) campo.setAttribute('aria-expanded', 'false');
  }
  function highlight(i) {
    var righe = box.querySelectorAll('.guida-risultato');
    Array.prototype.forEach.call(righe, function (r, k) { r.classList.toggle('is-attivo', k === i); });
    attivo = i;
    if (righe[i]) righe[i].scrollIntoView({ block: 'nearest' });
  }
  function showResults(q) {
    if (!box) return;
    if (!q.trim()) { closeResults(); return; }
    var lista = search(q);
    if (!lista.length) {
      box.innerHTML = '<p class="guida-risultati-vuoto">Nessun risultato per «' + esc(q.trim()) + '». Prova con un\'altra parola.</p>';
    } else {
      box.innerHTML = lista.map(function (r) {
        var v = r.voce;
        return '<a class="guida-risultato" role="option" href="#' + esc(v.id) + '" data-vai="' + esc(v.id) + '">'
          + '<span class="guida-risultato-t">' + esc(v.titolo) + (v.sotto ? ' <span class="guida-risultato-sotto">› ' + esc(v.sotto) + '</span>' : '') + '</span>'
          + '<span class="guida-risultato-s">' + r.html + '</span></a>';
      }).join('');
    }
    box.hidden = false;
    campo.setAttribute('aria-expanded', 'true');
    attivo = -1;
  }

  // ---------------------------------------------------------------- andare a un punto
  function goTo(id) {
    var dove = document.getElementById(id);
    if (!dove) return;
    closeResults();
    lato.classList.remove('is-aperto');
    if (btnCapitoli) btnCapitoli.setAttribute('aria-expanded', 'false');
    dove.scrollIntoView({ behavior: 'smooth', block: 'start' });
    if (history.replaceState) history.replaceState(null, '', '#' + id);
    // Un lampo sul punto raggiunto: in mezzo a tanti riquadri simili si capisce dov'e'.
    dove.classList.remove('guida-evidenzia');
    void dove.offsetWidth;
    dove.classList.add('guida-evidenzia');
    setTimeout(function () { dove.classList.remove('guida-evidenzia'); }, 1800);
  }

  if (campo && box) {
    var timer;
    campo.addEventListener('input', function () {
      clearTimeout(timer);
      timer = setTimeout(function () { showResults(campo.value); }, 120);
    });
    campo.addEventListener('focus', function () { if (campo.value.trim()) showResults(campo.value); });
    campo.addEventListener('keydown', function (e) {
      var righe = box.querySelectorAll('.guida-risultato');
      if (e.key === 'ArrowDown' && righe.length) { e.preventDefault(); highlight(Math.min(righe.length - 1, attivo + 1)); }
      else if (e.key === 'ArrowUp' && righe.length) { e.preventDefault(); highlight(Math.max(0, attivo - 1)); }
      else if (e.key === 'Enter') {
        e.preventDefault();
        var scelta = righe[attivo >= 0 ? attivo : 0];
        if (scelta) { goTo(scelta.getAttribute('data-vai')); campo.blur(); }
      } else if (e.key === 'Escape') { closeResults(); campo.blur(); }
    });
    box.addEventListener('click', function (e) {
      var r = e.target.closest('.guida-risultato');
      if (!r) return;
      e.preventDefault();
      goTo(r.getAttribute('data-vai'));
    });
    document.addEventListener('click', function (e) {
      if (!e.target.closest('.guida-cerca')) closeResults();
    });
  }

  // ---------------------------------------------------------------- indice
  if (indice) {
    indice.addEventListener('click', function (e) {
      var a = e.target.closest('a[data-capitolo]');
      if (!a) return;
      e.preventDefault();
      goTo(a.getAttribute('data-capitolo'));
    });
  }
  // Anche i collegamenti dentro la guida ("Come si fonda →", "In questo capitolo") passano da qui.
  doc.addEventListener('click', function (e) {
    var a = e.target.closest('a[href^="#"]');
    if (!a) return;
    var id = a.getAttribute('href').slice(1);
    if (!id || !document.getElementById(id)) return;
    e.preventDefault();
    goTo(id);
  });

  if (btnCapitoli) {
    btnCapitoli.addEventListener('click', function () {
      var aperto = lato.classList.toggle('is-aperto');
      btnCapitoli.setAttribute('aria-expanded', aperto ? 'true' : 'false');
    });
  }

  // Il capitolo che si sta leggendo si accende nell'indice.
  var links = {};
  if (indice) {
    Array.prototype.forEach.call(indice.querySelectorAll('a[data-capitolo]'), function (a) {
      links[a.getAttribute('data-capitolo')] = a;
    });
  }
  function updateActive() {
    var sezioni = doc.querySelectorAll('section[id]');
    var scelto = null;
    // Attivo e' il capitolo che occupa il terzo alto dello schermo, non solo quello sotto la testata.
    var soglia = (testata ? testata.getBoundingClientRect().height : 64) + window.innerHeight * 0.25;
    Array.prototype.forEach.call(sezioni, function (s) {
      if (s.getBoundingClientRect().top - soglia <= 0) scelto = s.id;
    });
    if (!scelto && sezioni.length) scelto = sezioni[0].id;
    Object.keys(links).forEach(function (id) {
      var on = id === scelto;
      links[id].classList.toggle('is-attivo', on);
      if (on && window.innerWidth > 1000) {
        var r = links[id].getBoundingClientRect(), l = indice.getBoundingClientRect();
        if (r.top < l.top || r.bottom > l.bottom) links[id].scrollIntoView({ block: 'nearest' });
      }
    });
  }
  var inCoda = false;
  window.addEventListener('scroll', function () {
    if (inCoda) return;
    inCoda = true;
    requestAnimationFrame(function () { inCoda = false; updateActive(); });
  }, { passive: true });
  updateActive();
})();
