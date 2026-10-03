/**
 * Scelta dell'inquadratura della copertina (gestionale, scheda di un articolo).
 *
 * Nelle tessere della home l'immagine viene RITAGLIATA: sul telefono in una fascia larga e
 * bassa, sul computer in una colonna stretta e alta. Di una locandina in piedi, quindi, se
 * ne vede solo un pezzo — e finora quel pezzo era sempre il centro dell'immagine, che non
 * e' detto sia la parte interessante.
 *
 * Qui si vedono le due inquadrature vere, affiancate, e si trascina l'immagine col mouse
 * (o col dito) per decidere cosa resta in vista. Il punto scelto viene scritto nel campo
 * nascosto "cover_position" come due percentuali, cioe' esattamente quello che serve al
 * foglio di stile: background-position.
 *
 * Se il pannello ha anche i cursori dello zoom (store: input[data-zoom="telefono"|"computer"])
 * l'anteprima ingrandisce l'immagine attorno al punto scelto, come fa la card (transform con
 * origine nel punto dell'inquadratura). Senza cursori lo zoom resta al 100%.
 */
(function () {
  var pannello = document.querySelector('[data-inquadratura]');
  if (!pannello) return;

  // Un campo nascosto per ciascuna anteprima: telefono e computer ritagliano l'immagine
  // in modo diverso, quindi il punto buono per uno non e' quasi mai quello buono per
  // l'altro e le due inquadrature restano separate.
  function campoDi(tela) {
    return pannello.querySelector('input[name="' + tela.getAttribute('data-campo') + '"]');
  }
  // Campo con l'indirizzo dell'immagine da seguire: l'articolo usa #cover_image, lo store
  // #pkg_image. Il pannello lo dichiara in data-src-campo; senza, si resta su #cover_image.
  var campoImmagine = document.querySelector(pannello.getAttribute('data-src-campo') || '#cover_image');
  var tele = [].slice.call(pannello.querySelectorAll('[data-tela]'));
  var bottoneCentra = pannello.querySelector('[data-centra]');

  var misureImmagine = null;   // { w, h } del file, servono a trascinare con la mano giusta

  function read(text) {
    var m = /^(\d{1,3})%\s+(\d{1,3})%$/.exec(String(text || ''));
    return m ? { x: +m[1], y: +m[2] } : { x: 50, y: 50 };
  }

  // Cursore dello zoom di quell'anteprima (o null) e il suo valore come fattore: 1 = 100%.
  function cursoreZoom(tela) {
    return pannello.querySelector('input[data-zoom="' + tela.getAttribute('data-tela') + '"]');
  }
  function zoomDi(tela) {
    var c = cursoreZoom(tela);
    var z = c ? parseFloat(c.value) / 100 : 1;
    return z > 0 ? z : 1;
  }

  // Scala dell'immagine nell'anteprima: "cover" (il lato piu' corto riempie il riquadro) per
  // lo zoom. Null finche' non si conoscono le misure del file o il riquadro e' nascosto.
  function scalaDi(tela) {
    var r = tela.getBoundingClientRect();
    if (!misureImmagine || !r.width || !r.height) return null;
    return { r: r, s: Math.max(r.width / misureImmagine.w, r.height / misureImmagine.h) * zoomDi(tela) };
  }

  function write(tela, pos) {
    var campo = campoDi(tela);
    var valore = Math.round(pos.x) + '% ' + Math.round(pos.y) + '%';
    if (campo) campo.value = valore;
    tela.style.backgroundPosition = valore;
    // Immagine "cover" ingrandita dello zoom, con la stessa posizione in percentuale: il punto
    // scelto resta fermo, esattamente come lo scale() con transform-origin della card.
    var sc = zoomDi(tela) !== 1 ? scalaDi(tela) : null;
    tela.style.backgroundSize = sc
      ? (misureImmagine.w * sc.s) + 'px ' + (misureImmagine.h * sc.s) + 'px'
      : 'cover';
    var c = cursoreZoom(tela);
    var etichetta = c && pannello.querySelector('[data-zoom-valore="' + tela.getAttribute('data-tela') + '"]');
    if (etichetta) etichetta.textContent = Math.round(zoomDi(tela) * 100) + '%';
  }

  function posDi(tela) {
    var campo = campoDi(tela);
    return read(campo ? campo.value : '');
  }

  function show(src) {
    pannello.hidden = !src;
    if (!src) return;
    tele.forEach(function (t) {
      t.style.backgroundImage = 'url("' + src + '")';
      write(t, posDi(t));
    });
    var img = new Image();
    img.onload = function () {
      misureImmagine = { w: img.naturalWidth, h: img.naturalHeight };
      // Stessa soglia di copertina_in_piedi() in index.php: sotto 1,2 e' una locandina in
      // piedi (colonna al 50%), sopra riempie tutta la tessera.
      pannello.classList.toggle('e-orizzontale', misureImmagine.h > 0 && misureImmagine.w / misureImmagine.h >= 1.2);
      // Con le misure vere lo zoom dell'anteprima si puo' calcolare.
      tele.forEach(function (t) { write(t, posDi(t)); });
    };
    img.src = src;
  }

  /**
   * Da spostamento del mouse (in pixel) a spostamento dell'inquadratura (in percentuale).
   *
   * Non e' una proporzione qualsiasi: dipende da QUANTA immagine avanza fuori dal riquadro.
   * Se l'immagine sborda di 300 px, trascinare di 300 px deve percorrere tutto il 100%;
   * se sborda di 30, gli stessi 300 px non devono far altro che arrivare a fondo corsa.
   * Senza questo conto il trascinamento sarebbe lentissimo su un lato e nervoso sull'altro.
   */
  function passo(tela, dx, dy) {
    // L'immagine e' scalata come "cover" (il fattore piu' grande fra i due lati), per lo zoom.
    var sc = scalaDi(tela);
    if (!sc) return { x: 0, y: 0 };
    var r = sc.r;
    var eccessoX = misureImmagine.w * sc.s - r.width;
    var eccessoY = misureImmagine.h * sc.s - r.height;
    return {
      x: eccessoX > 1 ? (dx / eccessoX) * 100 : 0,
      y: eccessoY > 1 ? (dy / eccessoY) * 100 : 0
    };
  }

  tele.forEach(function (tela) {
    var trascino = null;

    tela.addEventListener('pointerdown', function (e) {
      trascino = { x: e.clientX, y: e.clientY };
      tela.setPointerCapture(e.pointerId);
      tela.classList.add('in-movimento');
      e.preventDefault();
    });

    tela.addEventListener('pointermove', function (e) {
      if (!trascino) return;
      // Si trascina l'IMMAGINE, non la finestra: spostando il mouse a destra si scopre la
      // parte sinistra, quindi il segno e' invertito.
      var d = passo(tela, trascino.x - e.clientX, trascino.y - e.clientY);
      var pos = posDi(tela);
      pos.x = Math.max(0, Math.min(100, pos.x + d.x));
      pos.y = Math.max(0, Math.min(100, pos.y + d.y));
      trascino = { x: e.clientX, y: e.clientY };
      write(tela, pos);
    });

    function fine(e) {
      if (!trascino) return;
      trascino = null;
      tela.classList.remove('in-movimento');
      if (e && e.pointerId != null && tela.hasPointerCapture(e.pointerId)) {
        tela.releasePointerCapture(e.pointerId);
      }
    }
    tela.addEventListener('pointerup', fine);
    tela.addEventListener('pointercancel', fine);
    tela.addEventListener('lostpointercapture', fine);
  });

  if (bottoneCentra) {
    // Rimette al centro tutte e due: e' il pulsante "ricomincio da capo".
    // Toglie anche lo zoom, se c'e'.
    bottoneCentra.addEventListener('click', function () {
      tele.forEach(function (t) {
        var c = cursoreZoom(t);
        if (c) c.value = 100;
        write(t, { x: 50, y: 50 });
      });
    });
  }

  // Il cursore dello zoom ridisegna subito la sua anteprima.
  tele.forEach(function (t) {
    var c = cursoreZoom(t);
    if (c) c.addEventListener('input', function () { write(t, posDi(t)); });
  });
  // Lo zoom e' in pixel: se il riquadro cambia larghezza (finestra stretta) va rifatto.
  window.addEventListener('resize', function () {
    tele.forEach(function (t) { write(t, posDi(t)); });
  });

  // Se si cambia immagine (a mano o col pulsante Scegli) l'anteprima segue subito.
  if (campoImmagine) {
    ['change', 'input'].forEach(function (evento) {
      campoImmagine.addEventListener(evento, function () { show(campoImmagine.value.trim()); });
    });
  }

  show(pannello.getAttribute('data-src') || (campoImmagine ? campoImmagine.value.trim() : ''));
})();
