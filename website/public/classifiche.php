<?php
$page_title = 'Classifiche';
$page_description = 'Le classifiche di MAGICADVENTURE: le fazioni con piu potenza, '
    . 'i territori conquistati e i giocatori piu attivi del server Minecraft.';
$active = 'classifiche';
require __DIR__ . '/../includes/header.php';

// Segnaposto: le classifiche reali verranno collegate ai dati di MagixFactions.
$demo_factions = [
    ['pos' => 1, 'name' => '???', 'power' => '—', 'claims' => '—', 'members' => '—'],
    ['pos' => 2, 'name' => '???', 'power' => '—', 'claims' => '—', 'members' => '—'],
    ['pos' => 3, 'name' => '???', 'power' => '—', 'claims' => '—', 'members' => '—'],
];
?>
<h1 class="page-title">Classifiche <span class="badge-soon">In arrivo</span></h1>

<div class="panel">
  <p>Questa pagina mostrerà presto le classifiche reali del server, aggiornate automaticamente: fazioni più potenti, più territori conquistati e giocatori più attivi. Per ora ecco l'anteprima del formato:</p>
</div>

<h2>🏆 Top Fazioni</h2>
<div class="panel">
  <?php /* Su schermo stretto la tabella scorre dentro questo contenitore: e' lei a
           muoversi di lato, non tutta la pagina (vedi .tabella-scorrevole nel CSS). */ ?>
  <div class="tabella-scorrevole">
    <table class="rank">
      <thead>
        <tr><th>#</th><th>Fazione</th><th>Potenza</th><th>Territori</th><th>Membri</th></tr>
      </thead>
      <tbody>
        <?php foreach ($demo_factions as $f): ?>
          <tr>
            <td><?= $f['pos'] ?></td>
            <td><?= h($f['name']) ?></td>
            <td><?= h($f['power']) ?></td>
            <td><?= h($f['claims']) ?></td>
            <td><?= h($f['members']) ?></td>
          </tr>
        <?php endforeach; ?>
      </tbody>
    </table>
  </div>
</div>

<h2>⏱ Top Giocatori</h2>
<div class="panel">
  <p style="color:var(--text-dim)">Classifica per tempo di gioco/attività — in arrivo.</p>
</div>

<?php require __DIR__ . '/../includes/footer.php'; ?>
