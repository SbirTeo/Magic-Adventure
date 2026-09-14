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
