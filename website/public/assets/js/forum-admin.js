/* Riordino delle categorie del forum col trascinamento (drag & drop del browser, nessuna
   libreria). Stessa idea dello store (assets/js/store-admin.js), ma qui i due livelli sono
   la stessa cosa — una categoria — e trascinandone una DENTRO un'altra diventa una sua
   sotto-categoria; tirandola fuori torna principale.
   A ogni rilascio si manda al server l'albero completo, che riscrive posizione e padre. */
(function () {
  var lista = document.getElementById('forumSort');
  if (!lista) return;

  var stato = document.getElementById('forumSortStato');
  var inCorso = null;

  function messaggio(testo, errore) {
    if (!stato) return;
    stato.textContent = testo;
    stato.className = 'forum-sort-stato' + (errore ? ' is-error' : '');
  }

  function avvia(e) {
    // Senza questo, trascinando una figlia si muoverebbe il gruppo che la contiene
    if (e.target !== this) return;
    inCorso = this;
    this.classList.add('is-dragging');
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', this.dataset.id || '');
  }

  function termina(e) {
    if (e && e.target !== this) return;
    if (inCorso) inCorso.classList.remove('is-dragging');
    inCorso = null;
    lista.querySelectorAll('.is-drop-target').forEach(function (el) {
      el.classList.remove('is-drop-target');
    });
    salva();
  }

  /** Dentro un contenitore, l'elemento PRIMA del quale inserire, data la Y del puntatore. */
  function elementoDopo(contenitore, y, selettore) {
    var candidati = [].slice.call(contenitore.querySelectorAll(selettore + ':not(.is-dragging)'));
    var migliore = { distanza: Number.NEGATIVE_INFINITY, elemento: null };
    candidati.forEach(function (el) {
      if (el.parentElement !== contenitore) return;
      var r = el.getBoundingClientRect();
      var distanza = y - r.top - r.height / 2;
      if (distanza < 0 && distanza > migliore.distanza) {
        migliore = { distanza: distanza, elemento: el };
      }
    });
    return migliore.elemento;
  }

  // UN SOLO gestore, sulla lista. Metterlo anche sui singoli pezzi non funziona: l'evento
  // risale dalla zona delle figlie al gruppo che la contiene, e il secondo gestore disfa
  // quello che aveva appena fatto il primo (l'elemento tornava sempre di primo livello).
  function sopra(e) {
    if (!inCorso) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';

    // Una categoria principale (che ha figlie) non puo' finire dentro un'altra: la
    // trascinerebbe con se' e le sue sparirebbero dall'elenco.
    var haFiglie = inCorso.querySelectorAll('.forum-sort-figlia').length > 0;
    var sotto = e.target && e.target.closest ? e.target : null;
    var zonaFiglie = sotto ? sotto.closest('.forum-sort-figlie') : null;
    // Trascinando sopra un gruppo (non dentro la sua zona figlie) non si annida
    if (zonaFiglie && inCorso.contains(zonaFiglie)) {
      zonaFiglie = null;
    }

    lista.querySelectorAll('.is-drop-target').forEach(function (el) {
      if (el !== zonaFiglie) el.classList.remove('is-drop-target');
    });

    var contenitore, selettore;
    if (zonaFiglie && !haFiglie) {
      contenitore = zonaFiglie;
      selettore = '.forum-sort-figlia';
      contenitore.classList.add('is-drop-target');
    } else {
      contenitore = lista;
      selettore = '.forum-sort-gruppo';
    }

    // Chi cambia livello cambia anche vestito: una figlia trascinata fuori torna gruppo.
    var eraFiglia = inCorso.classList.contains('forum-sort-figlia');
    var diventaFiglia = contenitore !== lista;
    if (eraFiglia !== diventaFiglia) {
      inCorso.classList.toggle('forum-sort-figlia', diventaFiglia);
      inCorso.classList.toggle('forum-sort-gruppo', !diventaFiglia);
      inCorso.dataset.tipo = diventaFiglia ? 'figlia' : 'categoria';
      if (!diventaFiglia && !inCorso.querySelector('.forum-sort-figlie')) {
        // Tornando principale deve riavere la sua zona di rilascio, altrimenti non
        // potrebbe piu' ricevere sotto-categorie
        var zona = document.createElement('div');
        zona.className = 'forum-sort-figlie';
        inCorso.appendChild(zona);
        collega();
      }
    }

    var riferimento = elementoDopo(contenitore, e.clientY, selettore);
    if (riferimento) {
      contenitore.insertBefore(inCorso, riferimento);
    } else {
      contenitore.appendChild(inCorso);
    }
  }

  function collega() {
    lista.querySelectorAll('.forum-sort-gruppo, .forum-sort-figlia').forEach(function (el) {
      if (el.dataset.collegato) return;
      el.dataset.collegato = '1';
      el.setAttribute('draggable', 'true');
      el.addEventListener('dragstart', avvia);
      el.addEventListener('dragend', termina);
    });
    // Il sorvolo si ascolta in un posto solo (vedi il commento su sopra)
    if (!lista.dataset.collegato) {
      lista.dataset.collegato = '1';
      lista.addEventListener('dragover', sopra);
      lista.addEventListener('drop', function (e) { e.preventDefault(); });
    }
  }

  function fotografia() {
    var principali = [];
    var figlie = [];
    lista.querySelectorAll(':scope > .forum-sort-gruppo').forEach(function (gruppo) {
      principali.push(gruppo.dataset.id);
      gruppo.querySelectorAll('.forum-sort-figlia').forEach(function (f, indice) {
        figlie.push({ id: f.dataset.id, padre: gruppo.dataset.id, ordine: indice });
      });
    });
    return { principali: principali, figlie: figlie };
  }

  function salva() {
    messaggio('Salvataggio…', false);
    var corpo = new FormData();
    corpo.append('action', 'forum_reorder');
    corpo.append('csrf', lista.dataset.csrf);
    corpo.append('ordine', JSON.stringify(fotografia()));

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
