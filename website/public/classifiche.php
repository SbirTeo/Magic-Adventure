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
          ORDER BY f.score DESC, f.name ASC
          LIMIT 20'
    );
    $factions = $stmt->fetchAll();
} catch (PDOException $e) {
    // Colonna score assente (schema del plugin non ancora aggiornato) o DB fazioni non raggiungibile.
    $score_ready = false;
}

// Popup (card) che spiega COME si calcola il punteggio, dal JSON factions.score_detail scritto dal
// plugin. Metodo RELATIVO: in ogni voce la fazione migliore vale il massimo (il suo peso), le altre ne
// prendono la percentuale rispetto a lei; il punteggio è la somma. Il sito mostra e basta i numeri già
// pronti, non ricalcola nulla. Nel JSON: l=etichetta, v=valore grezzo, pct="76%" (rispetto al migliore),
// p=punti dati, m=massimo della voce (= peso).
function score_popup(?string $json, float $total): string {
    $rows = $json ? json_decode($json, true) : null;
    if (!is_array($rows) || !$rows) return '';
    $body = '';
    $maxTotal = 0.0;   // somma dei massimi delle voci = punteggio massimo possibile
    foreach ($rows as $r) {
        $pct = max(0, min(100, (int) rtrim((string) ($r['pct'] ?? '0'), '%')));   // % rispetto al migliore
        $maxTotal += (float) str_replace(',', '.', (string) ($r['m'] ?? '0'));
        $body .= '<tr>'
              . '<td class="sp-l">' . h($r['l'] ?? '?') . ' <span class="sp-v">' . h($r['v'] ?? '') . '</span></td>'
              . '<td class="sp-lvl"><i class="sp-bar"><b style="width:' . $pct . '%"></b></i><span>' . $pct . '%</span></td>'
              . '<td class="sp-p"><b>' . h($r['p'] ?? '?') . '</b> <span class="sp-max">/ ' . h($r['m'] ?? '1') . '</span></td>'
              . '</tr>';
    }
    // Massimo totale come testo pulito (5 invece di 5,00).
    $maxTxt = rtrim(rtrim(number_format($maxTotal, 2, ',', '.'), '0'), ',');
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
  .score-pop .sp-lvl { padding-right: 10px; }
  .score-pop .sp-lvl .sp-bar { display: inline-block; width: 54px; height: 6px; border-radius: 4px; background: var(--border); vertical-align: middle; overflow: hidden; }
  .score-pop .sp-lvl .sp-bar b { display: block; height: 100%; border-radius: 4px; background: var(--purple); min-width: 2px; }
  .score-pop .sp-lvl span { color: var(--text-dim); font-size: 12px; margin-left: 7px; }
  .score-pop .sp-p b { color: var(--purple); font-weight: 700; }
  .score-pop .sp-p .sp-max { color: var(--text-dimmer); font-size: 12px; }
  .score-pop tfoot td { padding-top: 9px; border-top: 1px solid var(--border-strong); font-family: var(--font-heading); color: var(--text); }
  .score-pop tfoot .sp-tot { color: var(--purple); font-size: 16px; }
</style>
<h1 class="page-title">Classifiche<?php if (!$score_ready): ?> <span class="badge-soon">In arrivo</span><?php endif; ?></h1>

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
          <tr><th>#</th><th>Fazione</th><th>Punteggio</th><th>Territori</th><th>Membri</th><th>Potenza</th></tr>
        </thead>
        <tbody>
          <?php foreach ($factions as $i => $f): ?>
            <tr>
              <td><?= $i + 1 ?></td>
              <td><?= h($f['name']) ?></td>
              <?php $pop = score_popup($f['score_detail'] ?? null, (float) $f['score']); ?>
              <td<?= $pop ? ' class="score-cell" tabindex="0"' : '' ?>>
                <span<?= $pop ? ' class="score-trigger"' : ' style="font-weight:700"' ?>><?= h(number_format((float) $f['score'], 2, ',', '.')) ?></span>
                <?= $pop ?>
              </td>
              <td><?= (int) $f['claims'] ?></td>
              <td><?= (int) $f['members'] ?></td>
              <td><?= (int) $f['power'] ?></td>
            </tr>
          <?php endforeach; ?>
        </tbody>
      </table>
    </div>
  <?php endif; ?>
</div>

<h2>⏱ Top Giocatori</h2>
<div class="panel">
  <p style="color:var(--text-dim)">Classifica per tempo di gioco/attività — in arrivo.</p>
</div>

<?php require __DIR__ . '/../includes/footer.php'; ?>
