/* Riordino delle voci del menu col trascinamento (drag & drop del browser, nessuna
   libreria). E' la versione piu' semplice del riordino gia' usato per store e forum: qui
   c'e' un livello solo, quindi basta l'elenco degli id nell'ordine in cui sono rimasti.
   A ogni rilascio si salva subito, senza pulsante "conferma": il gesto E' la conferma. */
(function () {
  var lista = document.getElementById('navSort');
  if (!lista) return;

  var stato = document.getElementById('navSortStato');
  var inCorso = null;

  function toast(text, errore) {
    if (!stato) return;
    stato.textContent = text;
    stato.className = 'forum-sort-stato' + (errore ? ' is-error' : '');
  }

  function start(e) {
    inCorso = this;
    this.classList.add('is-dragging');
    e.dataTransfer.effectAllowed = 'move';
    // Firefox non avvia il trascinamento senza qualcosa dentro dataTransfer
    e.dataTransfer.setData('text/plain', this.dataset.id || '');
  }

  function termina() {
    if (!inCorso) return;
    inCorso.classList.remove('is-dragging');
    inCorso = null;
    save();
  }

  /** L'elemento PRIMA del quale inserire, data la posizione verticale del puntatore. */
  function elementoDopo(y) {
    var candidati = [].slice.call(lista.querySelectorAll('.nav-sort-voce:not(.is-dragging)'));
    var migliore = { distanza: Number.NEGATIVE_INFINITY, elemento: null };
    candidati.forEach(function (el) {
      var r = el.getBoundingClientRect();
      var distanza = y - r.top - r.height / 2;
      if (distanza < 0 && distanza > migliore.distanza) {
        migliore = { distanza: distanza, elemento: el };
      }
    });
    return migliore.elemento;
  }

  lista.addEventListener('dragover', function (e) {
    if (!inCorso) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    var riferimento = elementoDopo(e.clientY);
    if (riferimento) {
      lista.insertBefore(inCorso, riferimento);
    } else {
      lista.appendChild(inCorso);
    }
  });
  lista.addEventListener('drop', function (e) { e.preventDefault(); });

  lista.querySelectorAll('.nav-sort-voce').forEach(function (voce) {
    voce.setAttribute('draggable', 'true');
    voce.addEventListener('dragstart', start);
    voce.addEventListener('dragend', termina);
  });

  var invioInCorso = false;
  function save() {
    if (invioInCorso) return;
    var ordine = [].slice.call(lista.querySelectorAll('.nav-sort-voce')).map(function (v) {
      return v.dataset.id;
    });

    var dati = new FormData();
    dati.append('action', 'nav_reorder');
    dati.append('csrf', lista.dataset.csrf || '');
    dati.append('ordine', JSON.stringify(ordine));

    invioInCorso = true;
    toast('Salvo l’ordine…', false);
    fetch(window.location.pathname + '?section=pages', { method: 'POST', body: dati, credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (r) {
        if (!r || !r.ok) throw new Error('rifiutato');
        toast('Ordine salvato.', false);
        // I numerini "ordine N" sotto ogni voce devono dire la verita' anche senza ricaricare
        [].slice.call(lista.querySelectorAll('.nav-sort-voce')).forEach(function (v, i) {
          var nota = v.querySelector('[data-ordine]');
          if (nota) nota.textContent = String(i + 1);
        });
      })
      .catch(function () {
        toast('Non sono riuscito a salvare l’ordine: ricarica la pagina e riprova.', true);
      })
      .finally(function () { invioInCorso = false; });
  }
})();
