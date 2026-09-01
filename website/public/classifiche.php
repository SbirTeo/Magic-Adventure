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
        'SELECT f.id, f.name, ROUND(f.score, 1) AS score,
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
?>
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
    <?php /* Su schermo stretto la tabella scorre dentro questo contenitore (vedi .tabella-scorrevole nel CSS). */ ?>
    <div class="tabella-scorrevole">
      <table class="rank">
        <thead>
          <tr><th>#</th><th>Fazione</th><th>Punteggio</th><th>Territori</th><th>Membri</th><th>Potenza</th></tr>
        </thead>
        <tbody>
          <?php foreach ($factions as $i => $f): ?>
            <tr>
              <td><?= $i + 1 ?></td>
              <td><?= h($f['name']) ?></td>
              <td><b><?= h(number_format((float) $f['score'], 1, ',', '.')) ?></b></td>
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
