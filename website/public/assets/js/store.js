// Vetrina dello store: i pulsanti in alto sono un filtro a schede. Ogni categoria e' una
// sezione a se'; se ne vede una alla volta e cliccando un pulsante si cambia categoria
// (senza muovere la pagina). Niente pulsante "Tutto".
(function () {
  var barra = document.getElementById('storeFiltri');
  if (!barra) return;

  var blocchi = [].slice.call(document.querySelectorAll('.store-fila-blocco'));

  barra.addEventListener('click', function (e) {
    var pulsante = e.target.closest('.store-filtro');
    if (!pulsante) return;

    barra.querySelectorAll('.store-filtro').forEach(function (b) {
      b.classList.toggle('is-active', b === pulsante);
    });

    var scelta = pulsante.dataset.cat;
    blocchi.forEach(function (b) {
      b.classList.toggle('is-nascosta', b.dataset.cat !== scelta);
    });
  });
})();

// Copertine delle card: forma della card da telefono (vedi sotto) e zoom.
//
// Zoom della copertina (gestionale, 25-100%, uno per telefono e uno per computer): sotto il
// 100% l'immagine si rimpicciolisce per mostrarne di piu'. "cover" non si puo' moltiplicare in
// CSS senza conoscere le misure del file, quindi qui: scala "cover" della card per lo zoom, in
// pixel, con la posizione in percentuale scelta nel gestionale (la stessa formula
// dell'anteprima, assets/js/inquadratura.js). Si rifa' quando la card cambia misura (telefono/
// computer, categoria che compare) e quando cambia il tema (la copertina chiara puo' essere un
// altro file). I bordi scoperti li riempie .store-card-sfondo. Senza JavaScript resta al 100%.
(function () {
  var misure = {};   // indirizzo -> { w, h } (o la lista di chi aspetta che arrivi)

  // Proporzioni delle copertine ospitate altrove: per quelle caricate sul sito --rapporto lo
  // scrive gia' il server (store_cover_style); qui si completano le altre, cosi' anche loro da
  // telefono danno la forma alla card. Va fatto per tema: la copertina chiara puo' essere diversa.
  function shape(card) {
    var strato = card.querySelector('.store-card-media');
    if (!strato) return;
    var chiaro = document.documentElement.getAttribute('data-tema') === 'chiaro';
    var nome = chiaro && card.style.getPropertyValue('--copertina-chiaro') ? '--rapporto-chiaro' : '--rapporto';
    if (card.style.getPropertyValue(nome)) return;
    size(strato, function (img) {
      if (img.w && img.h) card.style.setProperty(nome, String(img.w / img.h));
    });
  }
  var conCopertina = [].slice.call(document.querySelectorAll('.store-card:not(.senza-immagine)'));
  conCopertina.forEach(shape);
  new MutationObserver(function () { conCopertina.forEach(shape); })
    .observe(document.documentElement, { attributes: true, attributeFilter: ['data-tema'] });

  var strati = [].slice.call(document.querySelectorAll('.store-card-sfondo + .store-card-media'));
  if (!strati.length) return;

  function size(strato, cb) {
    var m = /url\(["']?(.*?)["']?\)/.exec(getComputedStyle(strato).backgroundImage || '');
    if (!m) return;
    var src = m[1];
    var nota = misure[src];
    if (nota && nota.w) return cb(nota);
    if (nota) return nota.push(cb);
    misure[src] = [cb];
    var img = new Image();
    img.onload = function () {
      var attesa = misure[src];
      misure[src] = { w: img.naturalWidth, h: img.naturalHeight };
      attesa.forEach(function (f) { f(misure[src]); });
    };
    img.src = src;
  }

  function apply(strato) {
    var z = parseFloat(getComputedStyle(strato).getPropertyValue('--zoom')) || 1;
    var w = strato.clientWidth, h = strato.clientHeight;
    if (z >= 1 || !w || !h) { strato.style.backgroundSize = ''; return; }
    size(strato, function (img) {
      if (!img.w || !img.h) return;
      var s = Math.max(w / img.w, h / img.h) * z;
      strato.style.backgroundSize = (img.w * s) + 'px ' + (img.h * s) + 'px';
    });
  }

  var tutti = function () { strati.forEach(apply); };
  if ('ResizeObserver' in window) {
    var ro = new ResizeObserver(function (voci) { voci.forEach(function (v) { apply(v.target); }); });
    strati.forEach(function (s) { ro.observe(s); });
  } else {
    window.addEventListener('resize', tutti);
  }
  new MutationObserver(tutti).observe(document.documentElement, { attributes: true, attributeFilter: ['data-tema'] });
  tutti();
})();
