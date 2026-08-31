<?php
/**
 * Pagina della guida: /tutorial
 *
 * Il testo NON sta qui. La guida vera e' quella del server, generata da
 * plugins-src/MagixFactions/docs/build_tutorial.py in un unico file autonomo
 * (CSS e GIF incorporati). Qui viene solo mostrata dentro il sito.
 *
 * Per aggiornarla dopo una modifica al plugin:
 *   1. python plugins-src/MagixFactions/docs/build_tutorial.py
 *   2. copia docs/tutorial.html in website/public/assets/guida/magixfactions.html
 *   3. carica il file sul VPS
 * (lo fa in un colpo solo lo script website/aggiorna-tutorial.ps1)
 *
 * La guida sta in un riquadro isolato e non "inline" perche' porta con se' il proprio
 * foglio di stile, con regole su body/h2/p/table: mescolarlo a quello del sito
 * romperebbe intestazione e piede di pagina.
 */
require_once __DIR__ . '/../includes/db.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/auth.php';   // serve per is_admin(): matita di modifica

$fileGuida = '/assets/guida/magixfactions.html';
$percorso = __DIR__ . $fileGuida;
$esiste = is_file($percorso);
// La data di modifica serve anche come "cache buster": cambia il file, cambia l'indirizzo.
$versione = $esiste ? filemtime($percorso) : 0;

$page_title = 'Guida del server';
$page_description = 'Guida per i nuovi giocatori di MAGICADVENTURE: fazioni, potenza, territori e tutti i comandi.';
$active = 'tutorial';

require __DIR__ . '/../includes/header.php';
?>
<h1 class="page-title">Guida del server</h1>

<div class="panel panel-modificabile">
  <?php /* Il testo si cambia dal gestionale; la guida sotto arriva invece dal server. */ ?>
  <?php if (is_admin()): ?>
    <a href="/manage?section=guida_edit" class="card-edit-btn" title="Modifica questo testo" aria-label="Modifica questo testo">✎</a>
  <?php endif; ?>
  <p class="guida-intro">
    <?= nl2br(h(guida_intro())) ?>
    <?php if ($esiste): ?>
      <a href="<?= h($fileGuida) ?>?v=<?= $versione ?>" target="_blank" rel="noopener">Aprila a schermo intero →</a>
    <?php endif; ?>
  </p>
</div>

<?php if (!$esiste): ?>
  <div class="alert alert-error">La guida non è al momento disponibile. Riprova più tardi.</div>
<?php else: ?>
  <?php /* La ricerca sta QUI e non dentro la guida: la guida e' un file del server (lo
           riscrive il plugin a ogni avvio), e mettercela dentro vorrebbe dire riscriverla
           a mano ogni volta. Vedi assets/js/guida-ia.js e includes/guida_ricerca.php. */ ?>
  <div class="guida-ia" data-guida-ia data-ambito="pubblica" data-modo="chat">
    <div class="guida-ia-testa">
      <h2>Chiedi alla guida</h2>
      <span class="guida-ia-sub">risponde con le parole della guida, e ti porta al capitolo</span>
    </div>

    <div class="guida-ia-risposte" data-guida-ia-risposte aria-live="polite"></div>

    <form class="guida-ia-form" autocomplete="off">
      <input type="text" name="q" maxlength="200" placeholder="Scrivi la tua domanda, per esempio: come si conquista un territorio?"
             aria-label="La tua domanda sulla guida">
      <button type="submit" class="guida-ia-invia">Chiedi</button>
    </form>

    <p class="guida-ia-esempi">
      <span>Prova con:</span>
      <button type="button" data-guida-ia-esempio>Come si crea una fazione?</button>
      <button type="button" data-guida-ia-esempio>Quanta Potenza perdo se muoio?</button>
      <button type="button" data-guida-ia-esempio>Come torno alla casa della fazione?</button>
    </p>

    <p class="guida-ia-nota">
      Non è un'intelligenza che si inventa le risposte: cerca dentro questa guida, ti riporta
      quello che c'è scritto e ti manda al capitolo giusto. Se una cosa nella guida non c'è, te lo dice.
    </p>
  </div>
<?php endif; ?>

<?php if ($esiste): ?>
  <div class="guida-riquadro">
    <?php /* Niente loading="lazy": e' il contenuto della pagina, e caricandosi in ritardo
             l'aggancio del copione poteva arrivare dopo l'evento di caricamento. */ ?>
    <iframe id="guidaFrame" src="<?= h($fileGuida) ?>?v=<?= $versione ?>"
            title="Guida per i nuovi giocatori"></iframe>
  </div>

  <script>
  // La guida sta in un riquadro a parte (foglio di stile suo), ma deve leggersi come una
  // pagina sola: niente barra di scorrimento interna e i link dell'indice muovono la
  // pagina del sito, non il riquadro.
  (function () {
    var frame = document.getElementById('guidaFrame');
    if (!frame) return;

    function adatta() {
      try {
        var doc = frame.contentDocument;
        if (!doc || !doc.body) return;
        // Misuro il CORPO, non documentElement: quest'ultimo non scende mai sotto
        // l'altezza del riquadro, quindi allargando la finestra il riquadro sarebbe
        // rimasto alto come quando era stretto, con un buco vuoto in fondo.
        var altezza = doc.body.scrollHeight || doc.documentElement.scrollHeight;
        frame.style.height = altezza + 'px';
      } catch (e) {
        // File di un'altra origine: lascio l'altezza di ripiego del CSS.
      }
    }

    // La guida e' un file a se' (si apre anche da sola), quindi ha fondo e larghezza suoi.
    // Dentro il sito quelli darebbero un riquadro dentro il riquadro: qui gli passo i colori
    // del tema e gli tolgo il contenitore, cosi' i capitoli sembrano pannelli del sito.
    function integraNelSito() {
      try {
        var doc = frame.contentDocument;
        if (!doc || !doc.head) return;
        var tema = getComputedStyle(document.body);
        var v = function (nome) { return tema.getPropertyValue(nome).trim(); };
        // Si riscrive (non si duplica): al cambio di tema i colori vanno rifatti.
        var stile = doc.getElementById('stileSito');
        if (!stile) {
          stile = doc.createElement('style');
          stile.id = 'stileSito';
          doc.head.appendChild(stile);
        }
        // Chrome dipinge di BIANCO il fondo di un iframe il cui schema di colori non
        // combacia con quello della pagina che lo ospita, e il fondo trasparente qui sotto
        // non basta: senza questa riga, con il sistema in chiaro, dietro ai capitoli della
        // guida si vedeva una fascia bianca.
        var schema = document.documentElement.getAttribute('data-tema') === 'chiaro' ? 'light' : 'dark';
        stile.textContent =
          ':root{' +
          'color-scheme:' + schema + ';' +
          '--bg:transparent;' +
          '--card:' + (v('--bg-panel') || '#17181b') + ';' +
          '--card2:' + (v('--bg-elevated') || '#1f2024') + ';' +
          '--line:' + (v('--border') || '#26272b') + ';' +
          '--txt:' + (v('--text') || '#f0f0ee') + ';' +
          '--sub:' + (v('--text-dim') || '#a9adbd') + '}' +
          'html,body{background:transparent}' +
          '.wrap{max-width:none;padding:0 0 8px}' +
          'header{padding-top:0;border-bottom:0}' +
          // Su telefono il sito e' a fasce a tutta larghezza (vedi "IL SITO A FASCE" nel
          // foglio di stile): i capitoli della guida devono seguirlo, se no restano gli
          // unici riquadri con la cornice in mezzo a tutto il resto.
          '@media(max-width:700px){' +
          'section,.toc{border-radius:0;border-left:0;border-right:0;' +
          'padding-left:15px;padding-right:15px;margin:12px 0}' +
          '.tip,.warn{border-radius:0}' +
          '}';
      } catch (e) { /* altra origine: la guida resta com'e' */ }
    }

    // Il tema si cambia senza ricaricare la pagina: la guida deve seguirlo, altrimenti
    // resta coi colori di prima (e con lo schema sbagliato torna la fascia bianca).
    if (window.MutationObserver) {
      new MutationObserver(integraNelSito).observe(document.documentElement,
        { attributes: true, attributeFilter: ['data-tema'] });
    }

    // I link dell'indice devono muovere la pagina del sito: il riquadro e' alto quanto il
    // suo contenuto, quindi al suo interno non c'e' niente da scorrere e un salto normale
    // non farebbe muovere nulla.
    // Lo scorrimento morbido non c'e' dappertutto: certi browser incorporati (e chi ha
    // spento le animazioni) lo ignorano SENZA dire niente, e il link non muoveva nulla.
    // Si prova con le buone e, se dopo un attimo non ci si e' mossi, si salta e basta.
    function scorriA(y) {
      var partenza = window.pageYOffset;
      window.scrollTo({ top: y, behavior: 'smooth' });
      setTimeout(function () {
        if (Math.abs(window.pageYOffset - partenza) < 2 && Math.abs(y - partenza) > 2) {
          window.scrollTo(0, y);
        }
      }, 350);
    }

    function vaiA(id) {
      var doc;
      try { doc = frame.contentDocument; } catch (e) { return; }
      if (!doc) return;
      var meta = id ? doc.getElementById(id) : null;
      var y = frame.getBoundingClientRect().top + window.pageYOffset
            + (meta ? meta.getBoundingClientRect().top + doc.documentElement.scrollTop : 0)
            - 80;   // spazio per l'intestazione fissa del sito
      scorriA(Math.max(0, y));
      // Un lampo sul capitolo appena raggiunto: senza, in mezzo a dodici riquadri uguali non
      // si capisce quale fosse quello giusto. Il colore arriva dal tema del sito.
      if (meta) {
        var tema = getComputedStyle(document.body).getPropertyValue('--purple').trim() || '#c04ff0';
        meta.style.transition = 'box-shadow .35s ease-out';
        meta.style.boxShadow = '0 0 0 2px ' + tema;
        setTimeout(function () { meta.style.boxShadow = ''; }, 1600);
      }
    }

    // La ricerca sopra la guida vive nella pagina del sito, ma i capitoli stanno qui dentro:
    // e' l'unico modo che ha per portarci il lettore (vedi assets/js/guida-ia.js).
    window.guidaVaiA = vaiA;

    var linkAgganciati = false;
    function agganciaIndice() {
      if (linkAgganciati) return;
      try {
        var doc = frame.contentDocument;
        doc.addEventListener('click', function (ev) {
          var link = ev.target.closest ? ev.target.closest('a[href^="#"]') : null;
          if (!link) return;
          ev.preventDefault();
          vaiA(link.getAttribute('href').slice(1));
        });
        linkAgganciati = true;
      } catch (e) { /* niente aggancio dei link: restano quelli del riquadro */ }
    }

    // Non mi affido al solo evento di caricamento: se la guida e' gia' pronta quando parte
    // questo copione, quell'evento e' gia' passato e il riquadro resterebbe all'altezza di
    // ripiego, con la barra di scorrimento dentro.
    function sistema() {
      var doc;
      try { doc = frame.contentDocument; } catch (e) { return true; }
      if (!doc || doc.readyState !== 'complete' || !doc.body) return false;
      integraNelSito();
      agganciaIndice();
      adatta();
      return true;
    }

    frame.addEventListener('load', function () {
      sistema();
      setTimeout(adatta, 300);   // le immagini incorporate possono allungare la pagina
    });

    if (!sistema()) {
      var attesa = setInterval(function () { if (sistema()) clearInterval(attesa); }, 150);
      setTimeout(function () { clearInterval(attesa); }, 10000);
    }

    // Cambi di larghezza (rotazione del telefono, finestra ridimensionata) rimandano il
    // testo a capo e cambiano l'altezza.
    window.addEventListener('resize', adatta);
    if (window.ResizeObserver) {
      frame.addEventListener('load', function () {
        try { new ResizeObserver(adatta).observe(frame.contentDocument.body); } catch (e) {}
      });
    }
  })();
  </script>

  <script src="/assets/js/guida-ia.js?v=<?= @filemtime(__DIR__ . '/assets/js/guida-ia.js') ?: time() ?>"></script>
<?php endif; ?>

<?php require __DIR__ . '/../includes/footer.php'; ?>
