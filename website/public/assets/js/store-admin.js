// Riordino dello store col trascinamento (nessuna libreria: drag & drop nativo del browser).
// - le categorie si riordinano fra loro
// - i pacchetti si riordinano dentro una categoria E si spostano da una categoria all'altra
// A ogni rilascio si invia l'ordine completo al server, che riscrive sort_order e category_id.
(function () {
  var lista = document.getElementById('storeSort');
  if (!lista) return;

  var stato = document.getElementById('storeSortStato');
  var inCorso = null;

  function messaggio(testo, errore) {
    if (!stato) return;
    stato.textContent = testo;
    stato.className = 'store-sort-stato' + (errore ? ' is-error' : '');
  }

  // --- trascinamento ---------------------------------------------------
  function avvia(e) {
    // dragstart risale al gruppo che contiene il pacchetto: senza questo controllo il gruppo
    // "ruberebbe" il trascinamento e si sposterebbe l'intera categoria invece del pacchetto
    if (e.target !== this) return;
    inCorso = this;
    this.classList.add('is-dragging');
    e.dataTransfer.effectAllowed = 'move';
    // Firefox non avvia il drag senza dati impostati
    e.dataTransfer.setData('text/plain', this.dataset.id || '');
  }

  function termina(e) {
    if (e && e.target !== this) return;
    if (inCorso) inCorso.classList.remove('is-dragging');
    inCorso = null;
    document.querySelectorAll('.is-drop-target').forEach(function (el) {
      el.classList.remove('is-drop-target');
    });
    salva();
  }

  // Dato un contenitore e la Y del puntatore, trova l'elemento prima del quale inserire
  function elementoDopo(contenitore, y, selettore) {
    var candidati = [].slice.call(contenitore.querySelectorAll(selettore + ':not(.is-dragging)'));
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

  function sopra(e) {
    if (!inCorso) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';

    var tipo = inCorso.dataset.tipo;
    var contenitore;
    if (tipo === 'categoria') {
      contenitore = lista;
    } else {
      contenitore = this.closest('.store-sort-pacchetti');
      if (!contenitore) return;
      contenitore.classList.add('is-drop-target');
    }

    var selettore = tipo === 'categoria' ? '.store-sort-gruppo' : '.store-sort-pacchetto';
    var riferimento = elementoDopo(contenitore, e.clientY, selettore);
    if (riferimento) {
      contenitore.insertBefore(inCorso, riferimento);
    } else {
      contenitore.appendChild(inCorso);
    }
  }

  function collega() {
    lista.querySelectorAll('.store-sort-gruppo, .store-sort-pacchetto').forEach(function (el) {
      el.setAttribute('draggable', 'true');
      el.addEventListener('dragstart', avvia);
      el.addEventListener('dragend', termina);
      el.addEventListener('dragover', sopra);
    });
    // anche le liste vuote devono poter ricevere un pacchetto
    lista.querySelectorAll('.store-sort-pacchetti').forEach(function (el) {
      el.addEventListener('dragover', sopra);
      el.addEventListener('dragleave', function () { el.classList.remove('is-drop-target'); });
    });
  }

  // --- salvataggio -----------------------------------------------------
  function fotografia() {
    var categorie = [];
    var pacchetti = [];
    lista.querySelectorAll('.store-sort-gruppo').forEach(function (gruppo, indiceCat) {
      var catId = gruppo.dataset.id;
      categorie.push(catId);
      gruppo.querySelectorAll('.store-sort-pacchetto').forEach(function (p, indice) {
        pacchetti.push({ id: p.dataset.id, categoria: catId, ordine: indice });
      });
      gruppo.dataset.ordine = indiceCat;
    });
    return { categorie: categorie, pacchetti: pacchetti };
  }

  function salva() {
    var dati = fotografia();
    messaggio('Salvataggio…', false);

    var corpo = new FormData();
    corpo.append('action', 'store_reorder');
    corpo.append('csrf', lista.dataset.csrf);
    corpo.append('ordine', JSON.stringify(dati));

    fetch('/manage', { method: 'POST', body: corpo, credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (r) {
        if (r && r.ok) {
          messaggio('Ordine salvato', false);
          setTimeout(function () { messaggio('', false); }, 1800);
        } else {
          messaggio('Salvataggio non riuscito: ricarica la pagina', true);
        }
      })
      .catch(function () {
        messaggio('Salvataggio non riuscito: ricarica la pagina', true);
      });
  }

  collega();
})();
