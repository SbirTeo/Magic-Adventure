// Vetrina dello store: filtri per categoria + righe che si trascinano col mouse.
// Le card sono link: dopo un trascinamento il click va soppresso, altrimenti si finirebbe
// sulla pagina del pacchetto ogni volta che si scorre la riga.
(function () {
  var barra = document.getElementById('storeFiltri');

  // ---- filtro per categoria -------------------------------------------------------
  if (barra) {
    var blocchi = [].slice.call(document.querySelectorAll('.store-fila-blocco'));

    barra.addEventListener('click', function (e) {
      var pulsante = e.target.closest('.store-filtro');
      if (!pulsante) return;

      barra.querySelectorAll('.store-filtro').forEach(function (b) {
        b.classList.toggle('is-active', b === pulsante);
      });

      var scelta = pulsante.dataset.cat;

      // One category at a time: show the chosen block, hide the others. There is no "all".
      blocchi.forEach(function (b) {
        b.classList.toggle('is-nascosta', b.dataset.cat !== scelta);
      });

    });
  }

  // ---- trascinamento delle righe --------------------------------------------------
  var SOGLIA = 5; // px oltre i quali il gesto e' un trascinamento e non un click

  document.querySelectorAll('.store-fila').forEach(function (fila) {
    var attivo = false;
    var partenzaX = 0;
    var partenzaScroll = 0;
    var spostamento = 0;

    fila.addEventListener('pointerdown', function (e) {
      if (e.button !== 0) return;
      attivo = true;
      spostamento = 0;
      partenzaX = e.clientX;
      partenzaScroll = fila.scrollLeft;
      fila.classList.add('is-trascino');
    });

    fila.addEventListener('pointermove', function (e) {
      if (!attivo) return;
      var dx = e.clientX - partenzaX;
      if (Math.abs(dx) > spostamento) spostamento = Math.abs(dx);
      fila.scrollLeft = partenzaScroll - dx;
    });

    function fine() {
      if (!attivo) return;
      attivo = false;
      fila.classList.remove('is-trascino');
      // Il click arriva SUBITO dopo il pointerup: azzero un attimo piu' tardi, cosi' il
      // controllo qui sotto fa in tempo a vedere che c'era stato un trascinamento.
      setTimeout(function () { spostamento = 0; }, 0);
    }
    fila.addEventListener('pointerup', fine);
    fila.addEventListener('pointerleave', fine);
    fila.addEventListener('pointercancel', fine);

    // In fase di CATTURA: blocca il click prima che arrivi al link che copre la card.
    fila.addEventListener('click', function (e) {
      if (spostamento > SOGLIA) {
        e.preventDefault();
        e.stopPropagation();
      }
    }, true);

    // ---- barretta di scorrimento --------------------------------------------------
    var blocco = fila.closest('.store-fila-blocco');
    var barra = blocco ? blocco.querySelector('.store-barra') : null;
    var pista = barra ? barra.querySelector('.store-barra-pista') : null;
    var pollice = barra ? barra.querySelector('.store-barra-pollice') : null;

    if (pista && pollice) {
      // Quanto si puo' scorrere in tutto: se e' zero la riga ci sta intera e la barra sparisce.
      function scorribile() { return fila.scrollWidth - fila.clientWidth; }

      function drawBar() {
        var totale = scorribile();
        barra.hidden = totale <= 2;
        if (barra.hidden) return;
        // Il pollice e' largo quanto la parte visibile sul totale, con un minimo dal CSS
        // perche' con tante card resterebbe un puntino.
        var largo = Math.max(fila.clientWidth / fila.scrollWidth, 0.12) * 100;
        pollice.style.width = largo + '%';
        var avanzamento = fila.scrollLeft / totale;       // 0 = inizio, 1 = fine
        pollice.style.left = (avanzamento * (100 - largo)) + '%';
        pista.setAttribute('aria-valuenow', Math.round(avanzamento * 100));
      }

      // Trascinamento: lo spostamento sulla pista si converte in scorrimento della riga
      // usando lo spazio di manovra dei due (pista meno pollice, contro il totale scorribile).
      var trascino = false;
      var xPartenza = 0;
      var scrollPartenza = 0;

      function passoBarra(dx) {
        var corsa = pista.clientWidth - pollice.offsetWidth;
        if (corsa <= 0) return 0;
        return dx * (scorribile() / corsa);
      }

      pollice.addEventListener('pointerdown', function (e) {
        trascino = true;
        xPartenza = e.clientX;
        scrollPartenza = fila.scrollLeft;
        barra.classList.add('is-trascino');
        pista.setPointerCapture(e.pointerId);
        e.preventDefault();
        e.stopPropagation();
      });

      pista.addEventListener('pointermove', function (e) {
        if (!trascino) return;
        fila.scrollLeft = scrollPartenza + passoBarra(e.clientX - xPartenza);
      });

      function fineBarra(e) {
        if (!trascino) return;
        trascino = false;
        barra.classList.remove('is-trascino');
        if (e && e.pointerId !== undefined && pista.hasPointerCapture(e.pointerId)) {
          pista.releasePointerCapture(e.pointerId);
        }
      }
      pista.addEventListener('pointerup', fineBarra);
      pista.addEventListener('pointercancel', fineBarra);

      // Click sulla pista (non sul pollice): ci si sposta li' in mezzo.
      pista.addEventListener('click', function (e) {
        if (e.target === pollice) return;
        var r = pista.getBoundingClientRect();
        var quota = (e.clientX - r.left - pollice.offsetWidth / 2) / (r.width - pollice.offsetWidth);
        fila.scrollTo({ left: Math.max(0, Math.min(1, quota)) * scorribile(), behavior: 'smooth' });
      });

      // Frecce della tastiera quando la barra ha il fuoco: stessa cosa dei pulsanti ‹ ›.
      pista.addEventListener('keydown', function (e) {
        if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
        e.preventDefault();
        var card = fila.querySelector('.store-card');
        var passo = card ? card.getBoundingClientRect().width + 18 : fila.clientWidth / 2;
        fila.scrollBy({ left: e.key === 'ArrowRight' ? passo : -passo, behavior: 'smooth' });
      });

      fila.addEventListener('scroll', drawBar);
      window.addEventListener('resize', drawBar);
      // Le copertine possono arrivare dopo e cambiare la larghezza totale della riga.
      window.addEventListener('load', drawBar);
      drawBar();
    }

    // ---- frecce e sfumatura del bordo ---------------------------------------------
    if (!blocco || !blocco.classList.contains('ha-altri')) return;

    function update() {
      var fine = fila.scrollLeft + fila.clientWidth >= fila.scrollWidth - 2;
      blocco.classList.toggle('a-inizio', fila.scrollLeft <= 2);
      blocco.classList.toggle('a-fine', fine);
    }

    blocco.querySelectorAll('.store-freccia').forEach(function (freccia) {
      freccia.addEventListener('click', function () {
        // un "passo" = una card, cosi' si scorre sempre di una posizione precisa
        var card = fila.querySelector('.store-card');
        var passo = card ? card.getBoundingClientRect().width + 18 : fila.clientWidth / 2;
        fila.scrollBy({ left: freccia.classList.contains('avanti') ? passo : -passo, behavior: 'smooth' });
      });
    });

    fila.addEventListener('scroll', update);
    window.addEventListener('resize', update);
    update();
  });
})();
