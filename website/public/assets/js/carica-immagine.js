/* Pulsante "Scegli" accanto ai campi con l'indirizzo di un'immagine.
   Prende il file, lo manda a /api/carica-immagine e scrive l'indirizzo nel campo.
   Vale per tutti i campi presenti nella pagina, senza doverli elencare. */
(function () {
  var GETTONE = document.querySelector('input[name="csrf"]');

  function stato(campo, text, tipo) {
    var p = campo.querySelector('[data-stato]');
    if (!p) return;
    p.textContent = text || '';
    p.hidden = !text;
    p.className = 'campo-immagine-stato' + (tipo ? ' is-' + tipo : '');
  }

  function anteprima(campo, indirizzo) {
    var box = campo.querySelector('[data-anteprima]');
    var img = campo.querySelector('[data-img]');
    if (!box || !img) return;
    if (indirizzo) {
      img.src = indirizzo;
      box.hidden = false;
    } else {
      box.hidden = true;
    }
  }

  function load(campo, file) {
    if (!file) return;
    if (!GETTONE) {
      stato(campo, 'Ricarica la pagina e riprova.', 'errore');
      return;
    }
    // Controllo anche qui la dimensione: cosi' non si aspetta un caricamento inutile.
    if (file.size > 5 * 1024 * 1024) {
      stato(campo, 'Immagine troppo pesante: il limite è 5 MB.', 'errore');
      return;
    }

    var dati = new FormData();
    dati.append('csrf', GETTONE.value);
    dati.append('file', file);

    var bottone = campo.querySelector('[data-scegli]');
    if (bottone) bottone.disabled = true;
    stato(campo, 'Carico…');

    fetch('/api/carica-immagine', { method: 'POST', body: dati, credentials: 'same-origin' })
      .then(function (r) { return r.json().catch(function () { return { ok: false, errore: 'Risposta non valida dal server.' }; }); })
      .then(function (risposta) {
        if (!risposta.ok) {
          stato(campo, risposta.errore || 'Caricamento non riuscito.', 'errore');
          return;
        }
        var input = campo.querySelector('[data-indirizzo]');
        input.value = risposta.url;
        input.dispatchEvent(new Event('change', { bubbles: true }));
        anteprima(campo, risposta.url);
        stato(campo, 'Caricata (' + risposta.larghezza + '×' + risposta.altezza + '). Ricordati di salvare.', 'ok');
      })
      .catch(function () {
        stato(campo, 'Caricamento non riuscito: controlla la connessione.', 'errore');
      })
      .then(function () {
        if (bottone) bottone.disabled = false;
      });
  }

  document.addEventListener('click', function (ev) {
    var bottone = ev.target.closest ? ev.target.closest('[data-scegli]') : null;
    if (!bottone) return;
    var campo = bottone.closest('[data-campo-immagine]');
    campo.querySelector('[data-file]').click();
  });

  document.addEventListener('change', function (ev) {
    var input = ev.target;
    if (input.matches('[data-file]')) {
      load(input.closest('[data-campo-immagine]'), input.files[0]);
      input.value = '';   // così si può ricaricare lo stesso file dopo un errore
    } else if (input.matches('[data-indirizzo]')) {
      anteprima(input.closest('[data-campo-immagine]'), input.value.trim());
    }
  });
})();
