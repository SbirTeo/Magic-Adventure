/**
 * La ricerca nella guida, lato pagina.
 *
 * Serve due posti diversi con lo stesso codice:
 *   - /tutorial          → si comporta come la chat del server: la domanda e' un messaggio
 *                          tuo, la risposta un messaggio della Guida (modo "chat");
 *   - gestionale, Guida  → una barra di ricerca e una risposta sola, che si sostituisce a
 *                          quella di prima (modo "singola"): li' si lavora, non si chiacchiera.
 *
 * A rispondere e' /api/guida-cerca, che cerca dentro la guida e restituisce dei pezzi di
 * guida gia' ripuliti (restano solo grassetto, corsivo, <code>, <br> e le parole evidenziate):
 * per questo si possono mettere nella pagina come HTML.
 *
 * Il link "Vai al capitolo":
 *   - in /tutorial la guida sta dentro un riquadro isolato, quindi si chiama guidaVaiA(),
 *     che la pagina espone apposta (vedi tutorial.php);
 *   - nel gestionale i capitoli sono nella pagina stessa: basta l'ancora.
 */
(function () {
  'use strict';

  var moduli = document.querySelectorAll('[data-guida-ia]');
  if (!moduli.length) return;

  Array.prototype.forEach.call(moduli, function (modulo) {
    var ambito = modulo.getAttribute('data-ambito') === 'staff' ? 'staff' : 'pubblica';
    var modo = modulo.getAttribute('data-modo') === 'singola' ? 'singola' : 'chat';
    var form = modulo.querySelector('form');
    var campo = modulo.querySelector('input[type="text"], input[type="search"]');
    var elenco = modulo.querySelector('[data-guida-ia-risposte]');
    var invia = modulo.querySelector('button[type="submit"]');
    if (!form || !campo || !elenco) return;

    var inCorso = false;

    /**
     * Quante domande restano scritte, con le loro risposte. Il riquadro scorre da solo (ci
     * pensa il CSS), ma senza un tetto la pagina si riempirebbe comunque di roba vecchia che
     * nessuno rilegge: dopo qualche domanda le prime si sfilano da sole.
     */
    var MAX_SCAMBI = 4;

    /** Un messaggio nell'elenco. In modo "singola" c'e' sempre e solo l'ultimo. */
    function add(classe) {
      if (modo === 'singola') elenco.innerHTML = '';
      var riga = document.createElement('div');
      riga.className = 'guida-ia-msg ' + classe;
      elenco.appendChild(riga);
      return riga;
    }

    /** Via le domande piu' vecchie, con la risposta attaccata. */
    function sfoltisci() {
      if (modo === 'singola') return;
      var domande = elenco.querySelectorAll('.guida-ia-msg.e-tua');
      for (var i = 0; i < domande.length - MAX_SCAMBI; i++) {
        var nodo = domande[i];
        // La domanda e tutto quello che la segue fino alla domanda dopo: sono una cosa sola.
        while (nodo && !(nodo !== domande[i] && nodo.classList.contains('e-tua'))) {
          var prossimo = nodo.nextElementSibling;
          nodo.remove();
          nodo = prossimo;
        }
      }
    }

    /** Porta in vista l'ultima riga SENZA muovere la pagina: si scorre solo il riquadro. */
    function inFondo() {
      elenco.scrollTop = elenco.scrollHeight;
    }

    function text(nodo, valore) {
      nodo.appendChild(document.createTextNode(valore));
    }

    /** L'intestazione del messaggio della Guida: "Guida »", come una riga di chat del server. */
    function firma(riga) {
      var nome = document.createElement('span');
      nome.className = 'guida-ia-nome';
      nome.textContent = 'Guida';
      var sep = document.createElement('span');
      sep.className = 'guida-ia-sep';
      sep.textContent = '»';
      riga.appendChild(nome);
      riga.appendChild(sep);
      return riga;
    }

    /** Porta il lettore al capitolo da cui viene la risposta. */
    function vaiAlCapitolo(ancora) {
      if (typeof window.guidaVaiA === 'function') {
        window.guidaVaiA(ancora);
        return;
      }
      var meta = document.getElementById(ancora);
      if (meta) {
        // Non scrollIntoView: l'intestazione del sito e' fissa e coprirebbe il titolo del
        // capitolo appena raggiunto. Gli stessi 80px che usa la guida in /tutorial.
        var y = Math.max(0, meta.getBoundingClientRect().top + window.pageYOffset - 80);
        var partenza = window.pageYOffset;
        window.scrollTo({ top: y, behavior: 'smooth' });
        // Lo scorrimento morbido lo ignorano in silenzio certi browser incorporati e chi ha
        // spento le animazioni: se dopo un attimo siamo ancora fermi, si salta e basta.
        setTimeout(function () {
          if (Math.abs(window.pageYOffset - partenza) < 2 && Math.abs(y - partenza) > 2) {
            window.scrollTo(0, y);
          }
        }, 350);
        // Un lampo sul capitolo: senza, dopo lo scorrimento non si capisce quale sia.
        meta.classList.add('guida-ia-evidenziato');
        setTimeout(function () { meta.classList.remove('guida-ia-evidenziato'); }, 1600);
      } else {
        window.location.hash = ancora;
      }
    }

    function linkCapitolo(fonte, etichetta) {
      var a = document.createElement('a');
      a.className = 'guida-ia-vai';
      a.href = '#' + fonte.ancora;
      a.textContent = etichetta || ('Vai al capitolo: ' + fonte.titolo);
      a.addEventListener('click', function (ev) {
        ev.preventDefault();
        vaiAlCapitolo(fonte.ancora);
      });
      return a;
    }

    /** Disegna la risposta arrivata dal server. */
    function show(dati) {
      var riga = add('e-guida');
      if (modo === 'chat') firma(riga);
      var corpo = document.createElement('div');
      corpo.className = 'guida-ia-corpo';
      riga.appendChild(corpo);

      if (dati.ok) {
        var apertura = document.createElement('p');
        apertura.className = 'guida-ia-apertura';
        apertura.innerHTML = dati.apertura;   // ripulito dal server: 6 tag e nient'altro
        corpo.appendChild(apertura);

        (dati.passaggi || []).forEach(function (p) {
          var blocco = document.createElement('blockquote');
          blocco.className = 'guida-ia-passo';
          if (p.sotto) {
            var sotto = document.createElement('span');
            sotto.className = 'guida-ia-sotto';
            sotto.textContent = p.sotto;
            blocco.appendChild(sotto);
          }
          var testoP = document.createElement('span');
          testoP.innerHTML = p.html;
          blocco.appendChild(testoP);
          corpo.appendChild(blocco);
        });

        if (dati.fonte) {
          corpo.appendChild(linkCapitolo(dati.fonte, 'Leggi il capitolo «' + dati.fonte.titolo + '» →'));
        }
      } else {
        var nota = document.createElement('p');
        nota.className = 'guida-ia-nulla';
        nota.textContent = dati.nota || 'Non ho trovato la risposta nella guida.';
        corpo.appendChild(nota);
      }

      if ((dati.correlati || []).length) {
        var altri = document.createElement('p');
        altri.className = 'guida-ia-altri';
        text(altri, dati.ok ? 'Ne parlano anche: ' : '');
        dati.correlati.forEach(function (c, i) {
          if (i) text(altri, ' · ');
          altri.appendChild(linkCapitolo(c, c.titolo));
        });
        corpo.appendChild(altri);
      }
      return riga;
    }

    function chiedi(domanda) {
      if (inCorso || !domanda) return;
      inCorso = true;
      if (invia) invia.disabled = true;

      if (modo === 'chat') {
        var mia = add('e-tua');
        var nome = document.createElement('span');
        nome.className = 'guida-ia-nome';
        nome.textContent = 'Tu';
        var sep = document.createElement('span');
        sep.className = 'guida-ia-sep';
        sep.textContent = '»';
        mia.appendChild(nome);
        mia.appendChild(sep);
        var corpo = document.createElement('span');
        corpo.className = 'guida-ia-corpo';
        corpo.textContent = domanda;
        mia.appendChild(corpo);
      }

      var attesa = add('e-guida e-attesa');
      if (modo === 'chat') firma(attesa);
      var puntini = document.createElement('span');
      puntini.className = 'guida-ia-corpo';
      puntini.textContent = 'cerco nella guida…';
      attesa.appendChild(puntini);
      sfoltisci();
      inFondo();

      fetch('/api/guida-cerca?ambito=' + ambito + '&q=' + encodeURIComponent(domanda), {
        credentials: 'same-origin'
      })
        .then(function (r) { return r.json(); })
        .then(function (dati) {
          attesa.remove();
          var riga = show(dati);
          // Ci si mette in cima alla RISPOSTA, non in fondo: quello che conta e' la prima
          // riga ("dalla guida, capitolo…"), non l'ultima citazione.
          elenco.scrollTop = Math.max(0, riga.offsetTop - elenco.offsetTop - 8);
        })
        .catch(function () {
          attesa.remove();
          show({ ok: false, nota: 'La ricerca non risponde. Riprova tra poco.' });
          inFondo();
        })
        .then(function () {
          inCorso = false;
          if (invia) invia.disabled = false;
          campo.focus();
        });
    }

    form.addEventListener('submit', function (ev) {
      ev.preventDefault();
      var domanda = campo.value.trim();
      if (!domanda) return;
      campo.value = '';
      chiedi(domanda);
    });

    // Gli esempi sotto al campo: si cliccano e partono da soli. Servono a far capire in un
    // colpo d'occhio che qui si scrive una domanda intera, non una parola chiave.
    Array.prototype.forEach.call(modulo.querySelectorAll('[data-guida-ia-esempio]'), function (chip) {
      chip.addEventListener('click', function (ev) {
        ev.preventDefault();
        chiedi(chip.textContent.trim());
      });
    });
  });
})();
