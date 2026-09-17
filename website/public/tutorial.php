<?php
/**
 * Pagina di guida e regolamento: /tutorial
 *
 * Guida e regolamento vivono qui, sotto un'unica voce di menu, e si scelgono con due
 * schede in cima (come Top Fazioni / Top Giocatori nelle classifiche): il passaggio
 * avviene lato client, SENZA ricaricare la pagina. La scheda scelta resta nell'hash
 * dell'URL, così /tutorial#regolamento apre direttamente il regolamento; il vecchio
 * indirizzo /regolamento reindirizza qui su quella scheda.
 *
 * La GUIDA non e' scritta qui: e' quella del server, generata da
 * plugins-src/MagixFactions/docs/build_tutorial.py in un unico file autonomo (CSS e GIF
 * incorporati) e mostrata dentro un riquadro. Per aggiornarla dopo una modifica al plugin:
 *   1. python plugins-src/MagixFactions/docs/build_tutorial.py
 *   2. copia docs/tutorial.html in website/public/assets/guida/magixfactions.html
 *   3. carica il file sul VPS
 * (lo fa in un colpo solo lo script website/aggiorna-tutorial.ps1)
 * Sta in un riquadro isolato e non "inline" perche' porta con se' il proprio foglio di
 * stile (body/h2/p/table): mescolarlo a quello del sito romperebbe testa e piede di pagina.
 *
 * Il REGOLAMENTO e' testo del gestionale (site_pages) con la tabella delle sanzioni
 * generata dal server al posto del segnaposto [[SANZIONI]] (vedi anche il vecchio
 * regolamento.php, ora un semplice reindirizzamento).
 */
require_once __DIR__ . '/../includes/db.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/auth.php';       // is_admin(): matita di modifica
require_once __DIR__ . '/../includes/sanzioni.php';   // tabella sanzioni del regolamento

// --- Guida (riquadro col file del server) --------------------------------------------
$fileGuida = '/assets/guida/magixfactions.html';
$percorso = __DIR__ . $fileGuida;
$esiste = is_file($percorso);
// La data di modifica serve anche come "cache buster": cambia il file, cambia l'indirizzo.
$versione = $esiste ? filemtime($percorso) : 0;

// --- Regolamento (testo dal gestionale + tabella sanzioni dal server) ----------------
$stmtReg = db()->prepare('SELECT title, body FROM site_pages WHERE slug = ?');
$stmtReg->execute(['regolamento']);
$pageReg = $stmtReg->fetch();
$titoloReg = $pageReg['title'] ?? 'Regolamento del server';
$bodyReg = $pageReg['body'] ?? 'Regolamento non ancora disponibile.';

// La sostituzione avviene DOPO corpo_articolo(): se il corpo e' testo semplice quella
// funzione lo passa da htmlspecialchars, e un blocco HTML infilato prima verrebbe stampato
// come codice. Il segnaposto non ha caratteri speciali, quindi arriva intatto.
$corpoReg = corpo_articolo($bodyReg);
$bloccoSanzioni = rulebook_sanctions_block();
if ($bloccoSanzioni) {
    $htmlSanzioni = '<div class="regolamento-sanzioni">' . $bloccoSanzioni['body_html']
        . '<p class="regolamento-sanzioni-fonte">Questa tabella è generata dalla configurazione del server'
        . ($bloccoSanzioni['updated_at'] ? ' e aggiornata ' . h(time_ago((string) $bloccoSanzioni['updated_at'])) : '')
        . '. <a href="/sanzioni">Guarda i provvedimenti presi →</a></p></div>';
} else {
    // Il plugin non l'ha ancora scritta: meglio una riga onesta che un buco nella pagina.
    $htmlSanzioni = '<div class="regolamento-sanzioni"><p>La tabella delle sanzioni non è ancora '
        . 'disponibile. Nel frattempo puoi consultare <a href="/sanzioni">i provvedimenti presi</a>.</p></div>';
}
$corpoReg = str_replace('[[SANZIONI]]', $htmlSanzioni, $corpoReg);

$page_title = 'Guida del server';
$page_description = 'Guida per i nuovi giocatori di MAGICADVENTURE: fazioni, potenza, territori e tutti i comandi, con il regolamento del server.';
$active = 'tutorial';

require __DIR__ . '/../includes/header.php';
?>
<h1 class="page-title" id="tutorialTitolo">Guida del server</h1>

<?php /* Le due schede: guida e regolamento nella stessa pagina, una alla volta. Stesse
         schede delle classifiche (stile in style.css), cambiano solo le icone. */ ?>
<div class="rank-tabs" role="tablist" aria-label="Guida e regolamento">
  <button type="button" class="rank-tab-btn is-active" data-tab="guida" data-titolo="Guida del server"
          role="tab" aria-selected="true" aria-controls="tab-guida">📖 Guida</button>
  <button type="button" class="rank-tab-btn" data-tab="regolamento" data-titolo="<?= h($titoloReg) ?>"
          role="tab" aria-selected="false" aria-controls="tab-regolamento">📜 Regolamento</button>
</div>

<section class="rank-tab-panel" id="tab-guida" role="tabpanel" aria-label="Guida">
  <div class="panel panel-modificabile">
    <?php /* Il testo si cambia dal gestionale; la guida sotto arriva invece dal server. */ ?>
    <?php if (is_admin()): ?>
      <a href="/manage?section=guida_edit" class="card-edit-btn" title="Modifica questo testo" aria-label="Modifica questo testo">✎</a>
    <?php endif; ?>
    <p class="guida-intro">
      <?= nl2br(h(guide_intro())) ?>
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

    <div class="guida-riquadro">
      <?php /* Niente loading="lazy": e' il contenuto della pagina, e caricandosi in ritardo
               l'aggancio del copione poteva arrivare dopo l'evento di caricamento. */ ?>
      <iframe id="guidaFrame" src="<?= h($fileGuida) ?>?v=<?= $versione ?>"
              title="Guida per i nuovi giocatori"></iframe>
    </div>
  <?php endif; ?>
</section>

<section class="rank-tab-panel" id="tab-regolamento" role="tabpanel" aria-label="Regolamento" hidden>
  <div class="panel panel-modificabile">
    <?php /* Stessa matita delle tessere in home: porta dritto al testo di questa pagina. */ ?>
    <?php if (is_admin()): ?>
      <a href="/manage?section=page_edit&slug=regolamento" class="card-edit-btn" title="Modifica il regolamento" aria-label="Modifica il regolamento">✎</a>
    <?php endif; ?>
    <div class="blog-body<?= corpo_e_html($bodyReg) ? ' corpo-html' : '' ?>"><?= $corpoReg ?></div>
  </div>
</section>

<script>
// Schede guida/regolamento: mostra una vista alla volta, senza ricaricare. La scelta resta
// nell'hash dell'URL così un link #regolamento apre direttamente quella scheda; il titolo
// in cima cambia insieme alla scheda.
(function () {
  var btns = Array.prototype.slice.call(document.querySelectorAll('.rank-tab-btn'));
  var panels = { guida: document.getElementById('tab-guida'), regolamento: document.getElementById('tab-regolamento') };
  var titolo = document.getElementById('tutorialTitolo');
  if (!btns.length) return;
  function show(tab) {
    if (!panels[tab]) tab = 'guida';
    Object.keys(panels).forEach(function (k) { if (panels[k]) panels[k].hidden = (k !== tab); });
    btns.forEach(function (b) {
      var on = b.dataset.tab === tab;
      b.classList.toggle('is-active', on);
      b.setAttribute('aria-selected', on ? 'true' : 'false');
      if (on && titolo && b.dataset.titolo) titolo.textContent = b.dataset.titolo;
    });
    // Il riquadro della guida si misura da solo, ma se era nascosto (aperti sul regolamento)
    // resta all'altezza di ripiego: quando torna visibile lo si fa rimisurare.
    if (tab === 'guida') window.dispatchEvent(new Event('resize'));
  }
  btns.forEach(function (b) {
    b.addEventListener('click', function () {
      show(b.dataset.tab);
      if (history.replaceState) history.replaceState(null, '', '#' + b.dataset.tab);
      else location.hash = b.dataset.tab;
    });
  });
  var start = (location.hash || '').replace('#', '');
  show(panels[start] ? start : 'guida');
})();
</script>

<?php if ($esiste): ?>
  <script>
  // Se il browser ripristina questa pagina dalla cache avanti/indietro (bfcache) — tipico
  // tornando alla guida dal menu o col tasto Indietro — il codice che gira e' quello di
  // ALLORA, non l'ultimo caricato dal server: si restava su uno script vecchio (es. con
  // l'offset di scorrimento sbagliato, che nascondeva il titolo del capitolo sotto
  // l'intestazione). La pagina e' no-store lato server, quindi basta rileggerla: se e' un
  // ripristino da bfcache, forziamo un ricaricamento pulito.
  window.addEventListener('pageshow', function (e) { if (e.persisted) window.location.reload(); });

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
    function integrateIntoSite() {
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
      new MutationObserver(integrateIntoSite).observe(document.documentElement,
        { attributes: true, attributeFilter: ['data-tema'] });
    }

    // I link dell'indice devono muovere la pagina del sito: il riquadro e' alto quanto il
    // suo contenuto, quindi al suo interno non c'e' niente da scorrere e un salto normale
    // non farebbe muovere nulla.
    // Lo scorrimento morbido non c'e' dappertutto: certi browser incorporati (e chi ha
    // spento le animazioni) lo ignorano SENZA dire niente, e il link non muoveva nulla.
    // Si prova con le buone e, se dopo un attimo non ci si e' mossi, si salta e basta.
    function scorriA(y, dopoFermo) {
      var partenza = window.pageYOffset;
      window.scrollTo({ top: y, behavior: 'smooth' });
      setTimeout(function () {
        if (Math.abs(window.pageYOffset - partenza) < 2 && Math.abs(y - partenza) > 2) {
          window.scrollTo(0, y);
        }
        // Un salto lungo (dall'inizio pagina a un capitolo in fondo) puo' impiegare piu' di
        // 350ms ad ANIMARE: se un correttore leggesse la posizione ora, la leggerebbe a meta'
        // corsa e correggerebbe sul valore SBAGLIATO, interrompendo l'animazione a meta' (visto
        // succedere: il capitolo finiva sotto la barra invece che sistemato). Si aspetta che lo
        // scroll sia davvero FERMO (nessun movimento per due controlli di fila) prima di dire
        // che e' finito, con un tetto di 1.5s per non restare in attesa all'infinito.
        if (dopoFermo) attendiFermo(dopoFermo);
      }, 350);
    }

    function attendiFermo(cb) {
      var precedente = window.pageYOffset, fermi = 0;
      var iv = setInterval(function () {
        var ora = window.pageYOffset;
        if (Math.abs(ora - precedente) < 0.5) {
          fermi++;
          if (fermi >= 2) { clearInterval(iv); cb(); }
        } else {
          fermi = 0;
        }
        precedente = ora;
      }, 80);
      setTimeout(function () { clearInterval(iv); cb(); }, 1500);
    }

    // L'intestazione del sito e' fissa (sticky) e la sua altezza NON e' costante: su finestre
    // strette il menu va a capo e l'intestazione diventa piu' alta (qui misurata a 98px, ma a
    // schermo largo e' ~65px). Un numero fisso (prima era 80) a volte era piu' basso
    // dell'intestazione vera, cosi' il titolo del capitolo finiva NASCOSTO sotto di essa. Si
    // misura ogni volta, con un piccolo margine sotto per staccare il titolo dal bordo.
    function headerOffset() {
      var h = document.querySelector('header');
      return (h ? h.getBoundingClientRect().height : 64) + 12;
    }

    // Se si clicca un capitolo nuovo prima che la correzione del precedente sia scattata, quella
    // vecchia non deve piu' agire: correggerebbe su un bersaglio ormai abbandonato, tirando la
    // pagina indietro sopra quello nuovo. Ogni vaiA() si prende un numero; solo l'ultimo vale.
    var vaiAGen = 0;

    function vaiA(id) {
      var mioGen = ++vaiAGen;
      // Se il lettore era sulla scheda del regolamento, prima si torna sulla guida:
      // i capitoli stanno nel riquadro, e da nascosto non ci si potrebbe muovere.
      var btnGuida = document.querySelector('.rank-tab-btn[data-tab="guida"]');
      if (btnGuida && !btnGuida.classList.contains('is-active')) btnGuida.click();
      var doc;
      try { doc = frame.contentDocument; } catch (e) { return; }
      if (!doc) return;
      var meta = id ? doc.getElementById(id) : null;
      var y = frame.getBoundingClientRect().top + window.pageYOffset
            + (meta ? meta.getBoundingClientRect().top + doc.documentElement.scrollTop : 0)
            - headerOffset();   // spazio per l'intestazione fissa del sito (altezza reale)
      scorriA(Math.max(0, y), meta && function () {
        if (mioGen !== vaiAGen) return;   // superato da un click piu' recente: non correggere
        // Ricontrollo a scorrimento DAVVERO finito: fra il calcolo di sopra e l'arrivo
        // l'intestazione puo' essere cambiata altezza (loghi/font ancora in caricamento, riga in
        // piu' da loggato) e il titolo restare comunque sotto la barra (segnalato da un
        // giocatore loggato: la mappa fazioni non si leggeva). Rimisuro la posizione VERA del
        // capitolo nel viewport e correggo se e' ancora coperto, invece di fidarmi del calcolo
        // fatto prima di muovermi.
        var top = frame.getBoundingClientRect().top + meta.getBoundingClientRect().top;
        var scarto = headerOffset() - top;   // > 0 = ancora sotto la barra
        if (scarto > 2) window.scrollTo(0, Math.max(0, window.pageYOffset + scarto));
      });
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
      integrateIntoSite();
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
