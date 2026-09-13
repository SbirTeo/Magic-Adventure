// Vetrina dello store: le categorie sono sezioni separate una sotto l'altra. I pulsanti in
// alto sono scorciatoie: portano (scorrendo dolcemente) alla sezione della categoria, e si
// evidenzia da solo quello della sezione che si sta guardando.
(function () {
  var barra = document.getElementById('storeFiltri');
  if (!barra) return;

  var bottoni = [].slice.call(barra.querySelectorAll('.store-filtro'));
  var sezioni = {};
  bottoni.forEach(function (b) {
    var s = document.getElementById('store-cat-' + b.dataset.cat);
    if (s) sezioni[b.dataset.cat] = s;
  });

  function attiva(catId) {
    bottoni.forEach(function (b) {
      b.classList.toggle('is-active', b.dataset.cat === catId);
    });
  }

  // Clic sul pulsante: scorri alla sezione (lo scroll-margin-top nel CSS tiene conto della
  // barra in alto incollata) ed evidenzialo subito.
  bottoni.forEach(function (b) {
    b.addEventListener('click', function (e) {
      var s = sezioni[b.dataset.cat];
      if (!s) return;
      e.preventDefault();
      s.scrollIntoView({ behavior: 'smooth', block: 'start' });
      attiva(b.dataset.cat);
      // Aggiorna l'ancora nell'indirizzo senza far saltare la pagina.
      if (window.history && history.replaceState) {
        history.replaceState(null, '', '#store-cat-' + b.dataset.cat);
      }
    });
  });

  // Scroll-spy: evidenzia il pulsante della sezione che attraversa il centro dello schermo.
  if ('IntersectionObserver' in window) {
    var io = new IntersectionObserver(function (voci) {
      voci.forEach(function (v) {
        if (v.isIntersecting) attiva(v.target.dataset.cat);
      });
    }, { rootMargin: '-45% 0px -45% 0px', threshold: 0 });
    Object.keys(sezioni).forEach(function (cat) { io.observe(sezioni[cat]); });
  }
})();
