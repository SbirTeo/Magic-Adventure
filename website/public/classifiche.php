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
        'SELECT f.id, f.name, ROUND(f.score, 1) AS score, f.score_detail,
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

// Popup (card) che spiega COME si arriva al punteggio, dal JSON factions.score_detail scritto dal
// plugin: una riga per caratteristica con valore usato, barra del livello 0-100, quota di peso e punti
// aggiunti. Stessi numeri del tooltip in gioco. Torna stringa vuota se il dettaglio non c'e' ancora.
function score_popup(?string $json, float $total): string {
    $rows = $json ? json_decode($json, true) : null;
    if (!is_array($rows) || !$rows) return '';
    $h = 'Come si compone <b class="sp-tot">' . number_format($total, 1, ',', '.') . '</b><span>/100</span>';
    $body = '';
    foreach ($rows as $r) {
        $lv = max(0, min(100, (int) ($r['lv'] ?? 0)));
        $body .= '<tr>'
              . '<td class="sp-l">' . h($r['l'] ?? '?') . '</td>'
              . '<td class="sp-v">' . h($r['v'] ?? '?') . '</td>'
              . '<td class="sp-bar"><i title="livello ' . $lv . '/100"><b style="width:' . $lv . '%"></b></i></td>'
              . '<td class="sp-w">' . h($r['w'] ?? '') . '</td>'
              . '<td class="sp-p">' . h($r['p'] ?? '?') . '</td>'
              . '</tr>';
    }
    return '<div class="score-pop" role="tooltip">'
         . '<div class="sp-head">' . $h . '</div>'
         . '<table><tbody>' . $body . '</tbody></table>'
         . '<div class="sp-foot">valore · livello · peso = punti</div>'
         . '</div>';
}
?>
<style>
  /* Classifica fazioni: popup del dettaglio punteggio. Wrapper dedicato (non .tabella-scorrevole) così
     su desktop l'overflow resta VISIBILE e il popup non viene tagliato; su mobile torna a scorrere. */
  .rank-wrap { overflow: visible; }
  @media (max-width: 760px) { .rank-wrap { overflow-x: auto; -webkit-overflow-scrolling: touch; padding-bottom: 2px; } }
  .score-cell { position: relative; }
  .score-trigger { cursor: help; font-weight: 700; border-bottom: 1px dashed var(--border-strong); }
  .score-cell .score-pop {
    position: absolute; top: calc(100% + 8px); left: 0; z-index: 60;
    display: block; min-width: 288px; max-width: 340px;
    background: var(--bg-elevated); border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm); padding: 12px 14px; text-align: left; white-space: normal;
    box-shadow: 0 18px 44px -14px rgba(0,0,0,.65);
    opacity: 0; visibility: hidden; transform: translateY(-4px);
    transition: opacity .12s ease, transform .12s ease, visibility .12s;
  }
  .score-cell:hover .score-pop, .score-cell:focus-within .score-pop { opacity: 1; visibility: visible; transform: translateY(0); }
  .score-pop .sp-head { display: block; font-family: var(--font-heading); font-size: 13px; color: var(--text-dim); margin-bottom: 9px; }
  .score-pop .sp-head b.sp-tot { color: var(--purple); font-size: 17px; }
  .score-pop .sp-head span { color: var(--text-dim); }
  .score-pop table { width: 100%; border-collapse: collapse; }
  .score-pop td { padding: 4px 0; font-size: 13px; border: 0; white-space: nowrap; vertical-align: middle; }
  .score-pop .sp-l { color: var(--text); padding-right: 10px; }
  .score-pop .sp-v { color: var(--text-dim); text-align: right; padding-right: 10px; }
  .score-pop .sp-bar { width: 66px; padding-right: 10px; }
  .score-pop .sp-bar i { display: block; height: 6px; border-radius: 4px; background: var(--border); }
  .score-pop .sp-bar i b { display: block; height: 100%; border-radius: 4px; background: var(--purple); min-width: 2px; }
  .score-pop .sp-w { color: var(--text-dimmer); text-align: right; padding-right: 10px; font-size: 12px; }
  .score-pop .sp-p { color: var(--purple); font-weight: 700; text-align: right; }
  .score-pop .sp-foot { display: block; margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--border);
    color: var(--text-dimmer); font-size: 11px; text-transform: uppercase; letter-spacing: .04em; }
</style>
<h1 class="page-title">Classifiche<?php if (!$score_ready): ?> <span class="badge-soon">In arrivo</span><?php endif; ?></h1>

<h2>🏆 Top Fazioni</h2>
<div class="panel">
  <p style="color:var(--text-dim);margin-top:0">
    Il <b>Punteggio</b> (da 0 a 100) unisce piu cose, ognuna pesata: territori, membri,
    <b>giacenza media</b> della banca, da quanto esiste la fazione e la sua potenza media. Strafare in
    una cosa sola rende sempre meno: per salire conviene crescere su tutto.
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
                <span<?= $pop ? ' class="score-trigger"' : ' style="font-weight:700"' ?>><?= h(number_format((float) $f['score'], 1, ',', '.')) ?></span>
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
