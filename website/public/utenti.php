<?php
/**
 * Elenco dei giocatori registrati sul sito: /utenti
 *
 * Un elenco solo, con i filtri in cima: di serie si vedono TUTTI, a partire da chi e' sul
 * sito in questo momento e poi per peso del grado. I filtri (Staff, Sostenitori, Giocatori,
 * Online) non cambiano pagina: nascondono e mostrano quello che c'e' gia'.
 *
 * Chi finisce in quale gruppo:
 *   staff        = amministratori del sito, oppure grado di gioco fra quelli elencati in
 *                  `utenti_gruppi_staff` (di serie admin/mod/moderatore/helper)
 *   sostenitori  = grado VIP (`utenti_gruppi_vip`) oppure almeno un acquisto pagato
 *   giocatori    = chi non e' ne' l'uno ne' l'altro
 * I gruppi NON si escludono: chi e' staff e ha comprato compare in tutti e due i filtri.
 *
 * Ricerca e "carica altri" sono tutti nel browser: le tessere sono gia' tutte nella pagina,
 * quindi filtrare e' immediato e non serve un altro giro sul server.
 */
require_once __DIR__ . '/../includes/db.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/auth.php';

$page_title = 'Utenti';
$page_description = 'Tutti i giocatori di MAGICADVENTURE: staff, sostenitori e giocatori, con la scheda di ognuno.';
$active = 'utenti';

/** Quante tessere si vedono all'inizio, e quante ne aggiunge ogni "carica altri". */
const UTENTI_PER_VOLTA = 20;

/** Elenco di gruppi da un'impostazione, ripulito e in minuscolo. */
$gruppiDa = function (string $chiave, string $default): array {
    $grezzo = (string) site_setting($chiave, $default);
    return array_values(array_filter(array_map('trim', explode(',', mb_strtolower($grezzo)))));
};
$gruppiStaff = $gruppiDa('utenti_gruppi_staff', 'admin,amministratore,mod,moderatore,helper,staff');
$gruppiVip = $gruppiDa('utenti_gruppi_vip', 'vip,vip+,mvp,sostenitore');

$utenti = db()->query(
    'SELECT u.id, u.mc_uuid, u.mc_username, u.is_admin, u.last_seen, u.last_login, u.created_at, '
    . RANK_SELECT_SQL . ', r.weight, r.first_join'
    . ' FROM users u' . rank_join_sql()
    . ' ORDER BY u.mc_username ASC'
)->fetchAll();

$acquistiPerUtente = [];
try {
    foreach (db()->query("SELECT user_id, COUNT(*) n FROM store_orders WHERE status = 'paid' GROUP BY user_id") as $riga) {
        $acquistiPerUtente[(int) $riga['user_id']] = (int) $riga['n'];
    }
} catch (PDOException $e) {
    $acquistiPerUtente = [];   // store non installato: nessuno risulta sostenitore per acquisti
}

// Miglior sostenitore: stessa regola della colonna dello store (le consegne manuali contano
// solo se lo dice l'impostazione, cosi' i due punti del sito non si contraddicono).
$topUuid = null;
try {
    $contaManuali = site_setting('store_sidebar_include_manual', '0') === '1';
    $soloVeri = $contaManuali ? '' : " AND (paypal_capture_id IS NULL OR paypal_capture_id NOT LIKE 'MANUALE-%') ";
    $q = db()->query("SELECT mc_uuid FROM store_orders WHERE status = 'paid' {$soloVeri}
                      GROUP BY mc_uuid ORDER BY SUM(price) DESC LIMIT 1");
    $topUuid = $q->fetchColumn() ?: null;
} catch (PDOException $e) {
    $topUuid = null;
}

// A ogni riga si attaccano i gruppi di appartenenza: sono quelli su cui lavorano i filtri.
foreach ($utenti as &$u) {
    $gruppo = mb_strtolower((string) ($u['group_name'] ?? ''));
    $acquisti = $acquistiPerUtente[(int) $u['id']] ?? 0;

    $eStaff = (int) $u['is_admin'] === 1 || in_array($gruppo, $gruppiStaff, true);
    $eSostenitore = in_array($gruppo, $gruppiVip, true) || $acquisti > 0;

    $u['acquisti'] = $acquisti;
    $u['online'] = e_sul_sito($u);
    $u['gruppi'] = array_values(array_filter([
        $eStaff ? 'staff' : null,
        $eSostenitore ? 'sostenitori' : null,
        (!$eStaff && !$eSostenitore) ? 'giocatori' : null,
        $u['online'] ? 'online' : null,
    ]));
}
unset($u);

// Ordine dell'elenco: il miglior sostenitore in testa (ha la corona, si capisce perche'),
// poi chi e' sul sito adesso, poi il grado piu' pesante, e a parita' chi si e' visto da meno.
usort($utenti, function (array $a, array $b) use ($topUuid): int {
    $topA = $topUuid !== null && $a['mc_uuid'] === $topUuid;
    $topB = $topUuid !== null && $b['mc_uuid'] === $topUuid;
    if ($topA !== $topB) {
        return $topA ? -1 : 1;
    }
    if ($a['online'] !== $b['online']) {
        return $a['online'] ? -1 : 1;
    }
    $peso = (int) ($b['weight'] ?? 0) <=> (int) ($a['weight'] ?? 0);
    if ($peso !== 0) {
        return $peso;
    }
    $vistoA = $a['last_seen'] ?: ($a['last_login'] ?: '1970-01-01');
    $vistoB = $b['last_seen'] ?: ($b['last_login'] ?: '1970-01-01');
    return strtotime((string) $vistoB) <=> strtotime((string) $vistoA);
});

$conteggi = ['tutti' => count($utenti), 'online' => 0, 'staff' => 0, 'sostenitori' => 0, 'giocatori' => 0];
foreach ($utenti as $u) {
    foreach ($u['gruppi'] as $g) {
        $conteggi[$g]++;
    }
}

$filtri = [
    'tutti' => 'Tutti',
    'online' => 'Sul sito ora',
    'staff' => 'Staff',
    'sostenitori' => 'Sostenitori',
    'giocatori' => 'Giocatori',
];

require __DIR__ . '/../includes/header.php';
?>
<h1 class="page-title">Utenti</h1>

<div class="utenti-testata panel">
  <p class="utenti-intro">
    <?= count($utenti) ?> account collegati al server, a partire da chi &egrave; sul sito adesso.
    Clicca su un giocatore per aprire la sua scheda.
  </p>

  <?php /* La lente non e' un'icona decorativa: e' dentro al campo, come nelle app. */ ?>
  <div class="utenti-cerca">
    <span class="utenti-cerca-lente" aria-hidden="true">
      <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
        <circle cx="8.5" cy="8.5" r="5.5"></circle>
        <path d="M12.8 12.8 17 17"></path>
      </svg>
    </span>
    <input type="search" id="cercaUtente" placeholder="Cerca un giocatore…"
           autocomplete="off" aria-label="Cerca un giocatore">
    <button type="button" class="utenti-cerca-pulisci" id="pulisciCerca" hidden aria-label="Svuota la ricerca">&times;</button>
  </div>

  <?php /* I filtri non ricaricano niente: accendono e spengono le tessere gia' in pagina. */ ?>
  <div class="utenti-filtri" role="group" aria-label="Filtra l'elenco">
    <?php foreach ($filtri as $chiave => $etichetta): ?>
      <button type="button" class="utenti-filtro<?= $chiave === 'tutti' ? ' e-attivo' : '' ?>"
              data-filtro="<?= h($chiave) ?>" <?= $chiave === 'tutti' ? 'aria-pressed="true"' : 'aria-pressed="false"' ?>>
        <?= h($etichetta) ?>
        <span class="utenti-filtro-conta"><?= (int) $conteggi[$chiave] ?></span>
      </button>
    <?php endforeach; ?>
  </div>

  <p class="utenti-esito" id="esitoCerca" hidden></p>
</div>

<div class="utenti-griglia" id="elencoUtenti" data-passo="<?= UTENTI_PER_VOLTA ?>">
  <?php foreach ($utenti as $u): ?>
    <?php
      $colore = player_name_color($u);
      // Il baffo a sinistra prende il colore del grado piu' pesante (lo stesso del prefisso
      // in chat); grigio "vanilla" per chi non ha prefisso. Due tinte, come per i nomi.
      $baffo = $colore !== null
          ? '--baffo:' . h($colore) . ';--baffo-chiaro:' . h(colore_leggibile($colore, '#ffffff'))
          : '';
      $eIlTop = $topUuid !== null && $u['mc_uuid'] === $topUuid;
      $ultimaVolta = $u['last_seen'] ?: ($u['last_login'] ?: null);
    ?>
    <a class="utente-card<?= $u['online'] ? ' e-online' : '' ?><?= $colore !== null ? ' ha-grado' : '' ?><?= $eIlTop ? ' e-top' : '' ?>"
       href="/utente?nome=<?= h(rawurlencode($u['mc_username'])) ?>"
       data-nome="<?= h(mb_strtolower($u['mc_username'])) ?>"
       data-gruppi="<?= h(implode(' ', $u['gruppi'])) ?>"
       <?= $baffo !== '' ? 'style="' . $baffo . '"' : '' ?>>
      <?= avatar_top(
            '<img class="utente-card-faccia" src="' . h(mc_avatar_url($u['mc_uuid'], 64)) . '" alt="" loading="lazy">',
            $u['mc_uuid'], 44) ?>

      <span class="utente-card-testo">
        <span class="utente-card-nome colore-grado"<?= $colore !== null ? ' style="' . stile_colore_grado($colore) . '"' : '' ?>>
          <?= h($u['mc_username']) ?>
        </span>
        <span class="utente-card-gradi">
          <?= player_tag($u) ?: '<span class="utente-card-nograde">Nessun grado</span>' ?>
          <?php if ($eIlTop): ?><span class="utente-card-top">Miglior sostenitore</span><?php endif; ?>
        </span>
        <span class="utente-card-meta">
          <?php if ($u['online']): ?>
            <span class="utente-card-online">sul sito ora</span>
          <?php elseif ($ultimaVolta): ?>
            sul sito <?= h(time_ago($ultimaVolta)) ?>
          <?php else: ?>
            mai entrato sul sito
          <?php endif; ?>
        </span>
      </span>
    </a>
  <?php endforeach; ?>
</div>

<p class="utenti-vuoto" id="elencoVuoto" hidden>Nessun giocatore con questi filtri.</p>

<div class="utenti-ancora">
  <button type="button" class="btn btn-contrasto" id="caricaAltri" hidden>Carica altri giocatori</button>
  <p class="utenti-quanti" id="quantiMostrati"></p>
</div>

<script>
// Filtri, ricerca e "carica altri" lavorano sulla stessa lista: le tessere sono tutte gia'
// nella pagina, quindi qui si decide solo QUALI si vedono e QUANTE. Le tre cose insieme:
//   filtro attivo  +  testo cercato  =  elenco delle candidate, di cui si mostrano le prime N.
(function () {
  var elenco = document.getElementById('elencoUtenti');
  if (!elenco) return;

  var PASSO = parseInt(elenco.dataset.passo, 10) || 20;
  var card = [].slice.call(elenco.querySelectorAll('.utente-card'));
  var campo = document.getElementById('cercaUtente');
  var pulisci = document.getElementById('pulisciCerca');
  var esito = document.getElementById('esitoCerca');
  var vuoto = document.getElementById('elencoVuoto');
  var altri = document.getElementById('caricaAltri');
  var quanti = document.getElementById('quantiMostrati');
  var filtri = [].slice.call(document.querySelectorAll('.utenti-filtro'));

  var filtroAttivo = 'tutti';
  var mostrate = PASSO;

  function candidate() {
    var q = campo.value.trim().toLowerCase();
    return card.filter(function (c) {
      var nelGruppo = filtroAttivo === 'tutti' || (' ' + c.dataset.gruppi + ' ').indexOf(' ' + filtroAttivo + ' ') !== -1;
      var nelNome = q === '' || c.dataset.nome.indexOf(q) !== -1;
      return nelGruppo && nelNome;
    });
  }

  function disegna() {
    var lista = candidate();
    var visibili = Math.min(mostrate, lista.length);

    card.forEach(function (c) { c.hidden = true; });
    lista.slice(0, visibili).forEach(function (c) { c.hidden = false; });

    vuoto.hidden = lista.length !== 0;
    altri.hidden = lista.length <= visibili;
    altri.textContent = 'Carica altri giocatori (' + (lista.length - visibili) + ')';
    quanti.textContent = lista.length === 0 ? '' : visibili + ' di ' + lista.length;

    var q = campo.value.trim();
    pulisci.hidden = q === '';
    if (q === '') {
      esito.hidden = true;
    } else {
      esito.hidden = false;
      esito.textContent = lista.length === 0
        ? 'Nessun giocatore trovato con “' + q + '”.'
        : lista.length + (lista.length === 1 ? ' giocatore trovato' : ' giocatori trovati') + ' — premi Invio per aprire il primo.';
    }
  }

  filtri.forEach(function (b) {
    b.addEventListener('click', function () {
      filtroAttivo = b.dataset.filtro;
      mostrate = PASSO;          // cambiando filtro si riparte dalle prime 20
      filtri.forEach(function (altro) {
        var attivo = altro === b;
        altro.classList.toggle('e-attivo', attivo);
        altro.setAttribute('aria-pressed', attivo ? 'true' : 'false');
      });
      disegna();
    });
  });

  altri.addEventListener('click', function () {
    mostrate += PASSO;
    disegna();
    // Il fuoco va alla prima tessera appena comparsa: chi naviga da tastiera continua da li'
    var nuove = elenco.querySelectorAll('.utente-card:not([hidden])');
    var prima = nuove[Math.max(0, mostrate - PASSO)];
    if (prima) prima.focus({ preventScroll: true });
  });

  campo.addEventListener('input', function () {
    mostrate = PASSO;            // una ricerca nuova riparte dalle prime 20
    disegna();
  });
  campo.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    var primo = elenco.querySelector('.utente-card:not([hidden])');
    if (primo) window.location.href = primo.getAttribute('href');
  });
  pulisci.addEventListener('click', function () {
    campo.value = '';
    mostrate = PASSO;
    disegna();
    campo.focus();
  });

  disegna();
})();
</script>

<?php require __DIR__ . '/../includes/footer.php'; ?>
