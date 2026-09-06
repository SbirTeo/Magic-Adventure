<?php
$page_title = 'Classifiche';
$page_description = 'Le classifiche di MAGICADVENTURE: le fazioni con il punteggio piu alto, '
    . 'i territori conquistati e i membri del server Minecraft.';
$active = 'classifiche';
require __DIR__ . '/../includes/header.php';

// Classifica fazioni per PUNTEGGIO composito: lo calcola il plugin MagixFactions (sintesi pesata di
// territori, membri, giacenza media della banca, longevita' e potenza media) e lo salva nella colonna
// factions_magixfactions.factions.score a ogni campionamento. Qui si legge solo quella colonna gia'
// pronta, cosi' il sito non deve duplicare la formula e i pesi del config del plugin.
// Le colonne di contorno (territori, membri, potenza) sono conteggi diretti, per far capire da cosa
// nasce la posizione. Se la colonna 'score' non c'e' ancora (plugin non ancora riavviato dopo
// l'aggiornamento), la pagina degrada con un avviso invece di rompersi.
$factions = [];
$score_ready = true;
try {
    $stmt = db()->query(
        'SELECT f.id, f.name, ROUND(f.score, 2) AS score, f.score_detail,
                (SELECT COUNT(*) FROM factions_magixfactions.claims c WHERE c.faction_id = f.id) AS claims,
                (SELECT COUNT(*) FROM factions_magixfactions.faction_members m WHERE m.faction_id = f.id) AS members,
                (SELECT COALESCE(SUM(pl.power), 0)
                   FROM factions_magixfactions.faction_members m2
                   JOIN factions_magixfactions.players pl ON pl.uuid = m2.uuid
                  WHERE m2.faction_id = f.id) AS power
           FROM factions_magixfactions.factions f
          WHERE f.ranked = 1
          ORDER BY f.score DESC, f.name ASC
          LIMIT 20'
    );
    $factions = $stmt->fetchAll();
} catch (PDOException $e) {
    // Colonna score assente (schema del plugin non ancora aggiornato) o DB fazioni non raggiungibile.
    $score_ready = false;
}

// Colonne NUOVE (uccisioni/morti di fazione e valore in minerali): in una query SEPARATA e tollerante,
// cosi' se le colonne del plugin non ci sono ancora (server non ancora riavviato dopo l'aggiornamento) la
// classifica fazioni continua a mostrarsi lo stesso, solo senza questi tre valori. faction_id -> riga.
$fstats = [];
if ($factions) {
    try {
        $rows = db()->query(
            'SELECT f.id,
                    (SELECT COALESCE(SUM(pl.kills), 0)
                       FROM factions_magixfactions.faction_members mk
                       JOIN factions_magixfactions.players pl ON pl.uuid = mk.uuid
                      WHERE mk.faction_id = f.id) AS kills,
                    (SELECT COALESCE(SUM(pl.deaths), 0)
                       FROM factions_magixfactions.faction_members md
                       JOIN factions_magixfactions.players pl ON pl.uuid = md.uuid
                      WHERE md.faction_id = f.id) AS deaths,
                    (SELECT COALESCE(SUM(cv.value), 0)
                       FROM factions_magixfactions.claims cv WHERE cv.faction_id = f.id) AS value
               FROM factions_magixfactions.factions f
              WHERE f.ranked = 1'
        )->fetchAll();
        foreach ($rows as $r) $fstats[$r['id']] = $r;
    } catch (PDOException $e) {
        $fstats = [];   // colonne non ancora presenti: le nuove colonne mostreranno 0
    }
}

// Prossimo aggiornamento delle statistiche: il plugin ricalcola lo snapshot (fazioni + giocatori) ogni
// score.sample-interval-seconds e aggiorna factions.score_sampled_at. Leggiamo l'ultimo campione per un
// conto alla rovescia sobrio. NB: $stats_interval deve combaciare con score.sample-interval-seconds del
// config del plugin (5 minuti).
$stats_interval = 300;
$last_sample_ms = 0;
try {
    $r = db()->query('SELECT MAX(score_sampled_at) AS last FROM factions_magixfactions.factions')->fetch();
    $last_sample_ms = (int) ($r['last'] ?? 0);
} catch (PDOException $e) {
    $last_sample_ms = 0;
}

// Fazione del VISITATORE loggato (via MagixWeb /link), per colorare i nomi fazione secondo la RELAZIONE
// in-game. Match dell'UUID tollerante al trattino (users.mc_uuid vs faction_members.uuid). Ospite = 0.
$viewer_faction_id = 0;
$allies = [];
$viewer = current_user();
if ($viewer && !empty($viewer['mc_uuid'])) {
    $undashed = strtolower(str_replace('-', '', (string) $viewer['mc_uuid']));
    try {
        $q = db()->prepare('SELECT faction_id FROM factions_magixfactions.faction_members
                             WHERE LOWER(REPLACE(uuid, "-", "")) = ? LIMIT 1');
        $q->execute([$undashed]);
        $viewer_faction_id = (int) ($q->fetchColumn() ?: 0);
        if ($viewer_faction_id > 0) {
            // Alleanze EFFETTIVE (mutue): una riga ALLY in ENTRAMBE le direzioni, come nel plugin.
            $qa = db()->prepare('SELECT r1.other_id FROM factions_magixfactions.relations r1
                                  JOIN factions_magixfactions.relations r2
                                    ON r2.faction_id = r1.other_id AND r2.other_id = r1.faction_id AND r2.type = "ALLY"
                                 WHERE r1.faction_id = ? AND r1.type = "ALLY"');
            $qa->execute([$viewer_faction_id]);
            foreach ($qa->fetchAll(PDO::FETCH_COLUMN) as $oid) $allies[(int) $oid] = true;
        }
    } catch (PDOException $e) {
        $viewer_faction_id = 0;
        $allies = [];
    }
}

// Popup (card) che spiega COME si calcola il punteggio, dal JSON factions.score_detail scritto dal
// plugin. Metodo RELATIVO: in ogni voce la fazione migliore vale il massimo (il suo peso), le altre ne
// prendono la percentuale rispetto a lei; il punteggio è la somma. Il sito mostra e basta i numeri già
// pronti, non ricalcola nulla. Nel JSON: l=etichetta, v=valore grezzo, pct="76%" (rispetto al migliore),
// p=punti dati, m=massimo della voce (= peso).
// Valore grezzo di una voce dal dettaglio (per le colonne di contorno della tabella), cercando per
// prefisso dell'etichetta ("Banca" trova "Banca (media)", "Longev" trova "Longevità"). '—' se assente.
function detail_value(?string $json, string $prefix): string {
    $rows = $json ? json_decode($json, true) : null;
    if (is_array($rows)) {
        foreach ($rows as $r) {
            if (isset($r['l'], $r['v']) && stripos($r['l'], $prefix) === 0) return (string) $r['v'];
        }
    }
    return '—';
}

function score_popup(?string $json, float $total): string {
    $rows = $json ? json_decode($json, true) : null;
    if (!is_array($rows) || !$rows) return '';
    $body = '';
    $maxTotal = 0.0;   // somma dei massimi delle voci = punteggio massimo possibile
    foreach ($rows as $r) {
        $pct = max(0, min(100, (int) rtrim((string) ($r['pct'] ?? '0'), '%')));   // % rispetto al migliore
        $maxTotal += (float) str_replace(',', '.', (string) ($r['m'] ?? '0'));
        // Riferimento: la fazione che fa da "migliore" (il 100%) in questa voce, e il suo valore.
        $self = !empty($r['self']);
        $best = $self
            ? '<span class="sp-best sp-best-self">★ sei tu il migliore</span>'
            : '<span class="sp-best">migliore: ' . h($r['bn'] ?? '?') . ' · ' . h($r['bv'] ?? '?') . '</span>';
        $body .= '<tr>'
              . '<td class="sp-l">' . h($r['l'] ?? '?') . ' <span class="sp-v">' . h($r['v'] ?? '') . '</span>' . $best . '</td>'
              . '<td class="sp-lvl"><i class="sp-bar"><b style="width:' . $pct . '%"></b></i><span>' . $pct . '%</span></td>'
              . '<td class="sp-p"><b>' . h($r['p'] ?? '?') . '</b> <span class="sp-max">/ ' . h($r['m'] ?? '1') . '</span></td>'
              . '</tr>';
    }
    // Massimo totale come testo pulito (5 invece di 5,00).
    $maxTxt = rtrim(rtrim(number_format($maxTotal, 2, ',', '.'), '0'), ',');
    // (kd_ratio / format_playtime definite piu' sotto, usate anche dalle classifiche giocatore.)
    return '<div class="score-pop" role="tooltip">'
         . '<div class="sp-head">Come si calcola il punteggio</div>'
         . '<div class="sp-intro">In ogni voce la fazione <b>migliore</b> vale il massimo; tu ne prendi la '
         . '<b>percentuale rispetto a lei</b>. Il punteggio è la somma delle voci.</div>'
         . '<table>'
         . '<colgroup><col class="c-l"><col class="c-lvl"><col class="c-p"></colgroup>'
         . '<thead><tr><th>Voce</th><th>% del migliore</th><th>Punti / max</th></tr></thead>'
         . '<tbody>' . $body . '</tbody>'
         . '<tfoot><tr><td>Totale</td><td></td><td><b class="sp-tot">' . number_format($total, 2, ',', '.') . '</b> <span class="sp-max">/ ' . h($maxTxt) . '</span></td></tr></tfoot>'
         . '</table>'
         . '</div>';
}

// Rapporto K/D come testo: con 0 morti mostra le uccisioni (evita la divisione per zero), altrimenti
// uccisioni/morti a due decimali. Stessa regola del plugin (ScoreManager/PlayerStatsManager).
function kd_ratio(int $kills, int $deaths): string {
    if ($deaths <= 0) return number_format((float) $kills, 2, ',', '.');
    return number_format($kills / $deaths, 2, ',', '.');
}

// Classe CSS del colore del nome fazione, in base alla RELAZIONE in-game col visitatore loggato (stesso
// criterio del gioco): la TUA fazione verde, un'ALLEATA viola/magenta, una NEMICA rossa. Ospite non
// loggato (o senza fazione) -> bianca (nessuna relazione da mostrare). $allies = insieme degli id alleati.
function faction_rel_class(int $factionId, int $viewerFactionId, array $allies): string {
    if ($viewerFactionId <= 0) return 'fac-guest';        // ospite / senza fazione
    if ($factionId === $viewerFactionId) return 'fac-own';
    if (isset($allies[$factionId])) return 'fac-ally';
    return 'fac-enemy';
}

// Secondi di gioco in forma leggibile: "3g 4h", "5h 12m", "42m" (le due unita' piu' grandi che contano).
function format_playtime(int $seconds): string {
    if ($seconds <= 0) return '—';
    $d = intdiv($seconds, 86400);
    $h = intdiv($seconds % 86400, 3600);
    $m = intdiv($seconds % 3600, 60);
    if ($d > 0) return $h > 0 ? "{$d}g {$h}h" : "{$d}g";
    if ($h > 0) return $m > 0 ? "{$h}h {$m}m" : "{$h}h";
    return "{$m}m";
}
?>
<style>
  /* Classifica fazioni: popup del dettaglio punteggio. Wrapper dedicato (non .tabella-scorrevole) così
     su desktop l'overflow resta VISIBILE e il popup non viene tagliato; su mobile torna a scorrere. */
  .rank-wrap { overflow: visible; }
  @media (max-width: 760px) { .rank-wrap { overflow-x: auto; -webkit-overflow-scrolling: touch; padding-bottom: 2px; } }
  .score-cell { position: relative; }
  .score-trigger { cursor: pointer; font-weight: 700; border-bottom: 1px dashed var(--border-strong); }
  .score-cell .score-pop {
    position: absolute; top: calc(100% + 8px); left: 0; z-index: 60;
    display: block; width: 400px; max-width: 92vw; box-sizing: border-box;
    background: var(--bg-elevated); border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm); padding: 14px 16px; text-align: left; white-space: normal;
    box-shadow: 0 18px 44px -14px rgba(0,0,0,.65);
    opacity: 0; visibility: hidden; transform: translateY(-4px);
    transition: opacity .12s ease, transform .12s ease, visibility .12s;
  }
  .score-pop table { table-layout: fixed; }
  .score-pop col.c-l { width: 48%; }
  .score-pop col.c-lvl { width: 30%; }
  .score-pop col.c-p { width: 22%; }
  .score-pop .sp-l { white-space: normal; }
  .score-cell:hover .score-pop, .score-cell:focus-within .score-pop { opacity: 1; visibility: visible; transform: translateY(0); }
  .score-pop .sp-head { font-family: var(--font-heading); font-size: 14px; font-weight: 700; color: var(--text); margin-bottom: 6px; }
  .score-pop .sp-intro { font-size: 12px; line-height: 1.45; color: var(--text-dim); margin-bottom: 12px; }
  .score-pop .sp-intro b { color: var(--text); font-weight: 600; }
  .score-pop table { width: 100%; border-collapse: collapse; }
  .score-pop th { font-family: var(--font-heading); font-size: 10.5px; text-transform: uppercase; letter-spacing: .04em;
    color: var(--text-dimmer); font-weight: 600; text-align: left; padding: 0 0 6px; border-bottom: 1px solid var(--border); }
  .score-pop th:last-child, .score-pop td:last-child { text-align: right; }
  .score-pop td { padding: 6px 0; font-size: 13px; border: 0; white-space: nowrap; vertical-align: middle; border-bottom: 1px solid var(--border); }
  .score-pop tbody tr:last-child td { border-bottom: 0; }
  .score-pop .sp-l { color: var(--text); padding-right: 10px; }
  .score-pop .sp-l .sp-v { color: var(--text-dim); font-size: 12px; }
  .score-pop .sp-best { display: block; color: var(--text-dimmer); font-size: 11px; margin-top: 1px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .score-pop .sp-best-self { color: var(--green); }
  .score-pop .sp-lvl { padding-right: 10px; }
  .score-pop .sp-lvl .sp-bar { display: inline-block; width: 54px; height: 6px; border-radius: 4px; background: var(--border); vertical-align: middle; overflow: hidden; }
  .score-pop .sp-lvl .sp-bar b { display: block; height: 100%; border-radius: 4px; background: var(--purple); min-width: 2px; }
  .score-pop .sp-lvl span { color: var(--text-dim); font-size: 12px; margin-left: 7px; }
  .score-pop .sp-p b { color: var(--purple); font-weight: 700; }
  .score-pop .sp-p .sp-max { color: var(--text-dimmer); font-size: 12px; }
  .score-pop tfoot td { padding-top: 9px; border-top: 1px solid var(--border-strong); font-family: var(--font-heading); color: var(--text); }
  .score-pop tfoot .sp-tot { color: var(--purple); font-size: 16px; }
  /* Uccisioni: morti e K/D non sono colonne, si leggono passando il mouse sul numero (tooltip nativo). */
  .kills-cell { border-bottom: 1px dotted var(--border-strong); cursor: help; }
  /* Nome fazione colorato per RELAZIONE in-game col visitatore (come nel gioco); punteggio in viola. */
  .rank .fac-name { font-weight: 600; }
  .rank .fac-own   { color: var(--green); }   /* la tua fazione */
  .rank .fac-ally  { color: #d876e0; }        /* alleata (magenta, come in gioco) */
  .rank .fac-enemy { color: #e05a5a; }        /* nemica (rossa) */
  .rank .fac-guest { color: var(--text); }    /* ospite non loggato / senza fazione: bianca */
  /* I nomi (fazione e giocatore) prendono il colore dalle classi fac-* qui sopra; qui solo il peso. */
  .rank .player-name { font-weight: 600; }
  .rank .score-value { color: var(--purple); font-weight: 700; }
  /* Timer sobrio del prossimo aggiornamento delle statistiche. */
  .stats-refresh { color: var(--text-dimmer); font-size: 12.5px; margin: -6px 0 14px; }
  .stats-refresh b { color: var(--text-dim); font-weight: 600; font-variant-numeric: tabular-nums; }
</style>
<h1 class="page-title">Classifiche<?php if (!$score_ready): ?> <span class="badge-soon">In arrivo</span><?php endif; ?></h1>
<?php if ($last_sample_ms > 0): ?>
  <p class="stats-refresh">↻ Statistiche aggiornate ogni <?= intdiv($stats_interval, 60) ?> min · prossimo aggiornamento tra <b id="stats-refresh-countdown">—</b></p>
<?php endif; ?>

<h2>🏆 Top Fazioni</h2>
<div class="panel">
  <p style="color:var(--text-dim);margin-top:0">
    Il <b>Punteggio</b> confronta le fazioni voce per voce: in ogni caratteristica (territori, membri,
    <b>giacenza media</b> della banca, longevità, potenza media) la <b>migliore</b> vale il massimo e le
    altre in proporzione a lei. La somma delle voci è il punteggio — passa il mouse su un valore per il
    dettaglio.
  </p>
  <?php if (!$score_ready): ?>
    <p style="color:var(--text-dim)">La classifica reale sara disponibile appena il server si aggiorna. Torna a trovarci!</p>
  <?php elseif (!$factions): ?>
    <p style="color:var(--text-dim)">Non esiste ancora nessuna fazione in classifica. Creane una in gioco con <code>/f create</code>!</p>
  <?php else: ?>
    <?php /* Wrapper dedicato (.rank-wrap): overflow visibile su desktop così il popup del dettaglio non
             viene tagliato; su mobile torna a scorrere in orizzontale. Vedi lo <style> in cima. */ ?>
    <div class="rank-wrap">
      <table class="rank">
        <thead>
          <tr><th>#</th><th>🛡️ Fazione</th><th>🏆 Punteggio</th><th>🗺️ Territori</th><th>👥 Membri</th><th>🏦 Banca</th><th>⏳ Longevità</th><th>⚡ Potenza</th><th>⚔️ Uccisioni</th><th>💎 Valore</th></tr>
        </thead>
        <tbody>
          <?php foreach ($factions as $i => $f): ?>
            <tr>
              <td><?= $i + 1 ?></td>
              <td class="fac-name <?= faction_rel_class((int) $f['id'], $viewer_faction_id, $allies) ?>"><?= h($f['name']) ?></td>
              <?php $pop = score_popup($f['score_detail'] ?? null, (float) $f['score']); ?>
              <td<?= $pop ? ' class="score-cell" tabindex="0"' : '' ?>>
                <span class="score-value<?= $pop ? ' score-trigger' : '' ?>"><?= h(number_format((float) $f['score'], 2, ',', '.')) ?></span>
                <?= $pop ?>
              </td>
              <td><?= (int) $f['claims'] ?></td>
              <td><?= (int) $f['members'] ?></td>
              <td><?= h(detail_value($f['score_detail'] ?? null, 'Banca')) ?></td>
              <td><?= h(detail_value($f['score_detail'] ?? null, 'Longev')) ?></td>
              <td><?= (int) $f['power'] ?></td>
              <?php $fs = $fstats[$f['id']] ?? ['kills' => 0, 'deaths' => 0, 'value' => 0]; ?>
              <td><span class="kills-cell" title="Morti: <?= (int) $fs['deaths'] ?> · K/D: <?= h(kd_ratio((int) $fs['kills'], (int) $fs['deaths'])) ?>"><?= (int) $fs['kills'] ?></span></td>
              <td><?= h(number_format((float) $fs['value'], 0, ',', '.')) ?></td>
            </tr>
          <?php endforeach; ?>
        </tbody>
      </table>
    </div>
  <?php endif; ?>
</div>

<?php
// Classifiche dedicate al GIOCATORE, lette direttamente dalla tabella players del plugin (colonne
// aggiunte da MagixFactions: play_seconds, money_avg_accum, money_seconds, kills, deaths). Come per le
// fazioni, se le colonne non ci sono ancora (plugin non riaggiornato) la sezione degrada con un avviso.
// - Tempo di gioco: TOTALE reale dalla statistica vanilla di Minecraft (storico incluso), in play_seconds.
// - Ricchezza media: giacenza MEDIA personale = money_avg_accum / money_seconds (media sul solo tempo
//   online da quando la feature è attiva — play_seconds NON è il denominatore, è il totale storico).
// - Uccisioni / K-D: uccisioni PvP valide (l'anti fake-kill e' nel plugin), col K/D a fianco.
$top_time = $top_money = $top_kills = [];
$players_ready = true;
try {
    // faction_id di ogni giocatore (sottoquery) per colorare il nome secondo la relazione col visitatore.
    $top_time = db()->query(
        'SELECT p.name, p.play_seconds,
                (SELECT m.faction_id FROM factions_magixfactions.faction_members m WHERE m.uuid = p.uuid) AS faction_id
           FROM factions_magixfactions.players p
          WHERE p.play_seconds > 0 AND p.name IS NOT NULL
          ORDER BY p.play_seconds DESC, p.name ASC LIMIT 10'
    )->fetchAll();
    $top_money = db()->query(
        'SELECT p.name, (p.money_avg_accum / p.money_seconds) AS avg_money,
                (SELECT m.faction_id FROM factions_magixfactions.faction_members m WHERE m.uuid = p.uuid) AS faction_id
           FROM factions_magixfactions.players p
          WHERE p.money_seconds > 0 AND p.name IS NOT NULL
          ORDER BY avg_money DESC, p.name ASC LIMIT 10'
    )->fetchAll();
    $top_kills = db()->query(
        'SELECT p.name, p.kills, p.deaths,
                (SELECT m.faction_id FROM factions_magixfactions.faction_members m WHERE m.uuid = p.uuid) AS faction_id
           FROM factions_magixfactions.players p
          WHERE p.kills > 0 AND p.name IS NOT NULL
          ORDER BY p.kills DESC, p.deaths ASC, p.name ASC LIMIT 10'
    )->fetchAll();
} catch (PDOException $e) {
    $players_ready = false;
}
?>
<h2>⏱ Top Giocatori</h2>
<?php if (!$players_ready): ?>
  <div class="panel">
    <p style="color:var(--text-dim)">Le classifiche dei giocatori saranno disponibili appena il server si aggiorna. Torna a trovarci!</p>
  </div>
<?php else: ?>
  <div class="panel">
    <p style="color:var(--text-dim);margin-top:0">
      Il <b>tempo di gioco</b> conta i secondi passati online; la <b>ricchezza media</b> è la giacenza
      media sul solo tempo online (parcheggiare soldi da offline non la gonfia); le <b>uccisioni</b> sono
      solo quelle PvP valide — il server scarta le «fake kill» tra amici, gli alt sullo stesso IP e le
      vittime uccise troppo in fretta.
    </p>

    <h3>🕒 Tempo di gioco</h3>
    <div class="rank-wrap">
      <table class="rank">
        <thead><tr><th>#</th><th>👤 Giocatore</th><th>🕒 Tempo di gioco</th></tr></thead>
        <tbody>
          <?php if (!$top_time): ?>
            <tr><td colspan="3" style="color:var(--text-dim)">Ancora nessun dato.</td></tr>
          <?php else: foreach ($top_time as $i => $p): ?>
            <tr><td><?= $i + 1 ?></td><td class="player-name <?= faction_rel_class((int) ($p['faction_id'] ?? 0), $viewer_faction_id, $allies) ?>"><?= h($p['name']) ?></td><td><?= h(format_playtime((int) $p['play_seconds'])) ?></td></tr>
          <?php endforeach; endif; ?>
        </tbody>
      </table>
    </div>

    <h3>💰 Ricchezza media</h3>
    <div class="rank-wrap">
      <table class="rank">
        <thead><tr><th>#</th><th>👤 Giocatore</th><th>💰 Giacenza media</th></tr></thead>
        <tbody>
          <?php if (!$top_money): ?>
            <tr><td colspan="3" style="color:var(--text-dim)">Ancora nessun dato.</td></tr>
          <?php else: foreach ($top_money as $i => $p): ?>
            <tr><td><?= $i + 1 ?></td><td class="player-name <?= faction_rel_class((int) ($p['faction_id'] ?? 0), $viewer_faction_id, $allies) ?>"><?= h($p['name']) ?></td><td><?= h(number_format((float) $p['avg_money'], 0, ',', '.')) ?></td></tr>
          <?php endforeach; endif; ?>
        </tbody>
      </table>
    </div>

    <h3>⚔️ Uccisioni e K/D</h3>
    <div class="rank-wrap">
      <table class="rank">
        <thead><tr><th>#</th><th>👤 Giocatore</th><th>⚔️ Uccisioni</th></tr></thead>
        <tbody>
          <?php if (!$top_kills): ?>
            <tr><td colspan="3" style="color:var(--text-dim)">Ancora nessun dato.</td></tr>
          <?php else: foreach ($top_kills as $i => $p): ?>
            <tr><td><?= $i + 1 ?></td><td class="player-name <?= faction_rel_class((int) ($p['faction_id'] ?? 0), $viewer_faction_id, $allies) ?>"><?= h($p['name']) ?></td><td><span class="kills-cell" title="Morti: <?= (int) $p['deaths'] ?> · K/D: <?= h(kd_ratio((int) $p['kills'], (int) $p['deaths'])) ?>"><?= (int) $p['kills'] ?></span></td></tr>
          <?php endforeach; endif; ?>
        </tbody>
      </table>
    </div>
  </div>
<?php endif; ?>

<?php if ($last_sample_ms > 0): ?>
<script>
(function () {
  var last = <?= $last_sample_ms ?>, interval = <?= $stats_interval * 1000 ?>;
  var el = document.getElementById('stats-refresh-countdown');
  if (!el) return;
  // Punta al prossimo campione FUTURO: se la pagina resta aperta oltre un ciclo, avanza da solo.
  var target = last + interval, now = Date.now();
  while (target <= now) target += interval;
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function tick() {
    var s = Math.round((target - Date.now()) / 1000);
    if (s <= 0) { el.textContent = 'in corso…'; setTimeout(function () { location.reload(); }, 2000); return; }
    el.textContent = Math.floor(s / 60) + ':' + pad(s % 60);
    setTimeout(tick, 1000);
  }
  tick();
})();
</script>
<?php endif; ?>

<?php require __DIR__ . '/../includes/footer.php'; ?>
