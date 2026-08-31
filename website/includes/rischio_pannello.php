<?php
/**
 * Il pannello "Da controllare": la classifica di chi conviene andare a guardare.
 *
 * Sta in un file suo perche' vive DENTRO la scheda Sanzioni — una maschera sola per tutto
 * quello che riguarda i provvedimenti — ma e' abbastanza lungo da rendere illeggibile
 * manage.php se ci stesse in mezzo.
 *
 * Si aspetta gia' pronte due variabili: $elenco (da rischio_elenco()) e $mostraTutti.
 */
?>
<div class="panel" id="da-controllare">
  <h3 style="margin-top:0;">Da controllare</h3>
  <p style="margin:0; color:var(--text-dim); font-size:14px;">
    I giocatori in ordine di <strong>quanto conviene andarli a guardare</strong>, non di quanto
    sono colpevoli. Il punteggio somma i punti delle violazioni registrate dal server, le
    segnalazioni dei giocatori ancora aperte e i provvedimenti già attivi — e accanto trovi
    sempre da cosa è fatto, perché un numero senza il suo perché non è una prova.
  </p>
  <p style="margin:10px 0 0; color:var(--text-dimmer); font-size:13px;">
    Quando ne hai guardato uno, segnalo come controllato: esce dalla lista per una settimana,
    così non lo ricontrolli tu domani e un altro dello staff dopodomani.
  </p>
</div>

<div class="panel">
  <div class="riga-cerca">
    <?php if ($mostraTutti): ?>
      <a href="/manage?section=sanzioni#da-controllare" class="btn btn-ghost">Nascondi chi è stato controllato</a>
    <?php else: ?>
      <a href="/manage?section=sanzioni&amp;tutti=1#da-controllare" class="btn btn-ghost">Mostra anche i controllati</a>
    <?php endif; ?>
    <span style="color:var(--text-dimmer); font-size:13px;">
      <?= count($elenco) ?> <?= count($elenco) === 1 ? 'giocatore' : 'giocatori' ?> in elenco
    </span>
    <a href="/sanzioni" target="_blank" rel="noopener" style="margin-left:auto;">Elenco pubblico delle sanzioni →</a>
  </div>

  <?php if (!$elenco): ?>
    <p style="margin:0; color:var(--text-dim); font-size:14px;">
      <?= $mostraTutti
            ? 'Nessuno ha ancora accumulato niente: non c\'è nessuno da controllare.'
            : 'Nessuno da controllare adesso. Chi era in lista è stato guardato di recente.' ?>
    </p>
  <?php else: ?>
    <div class="rischio-elenco">
      <?php foreach ($elenco as $i => $g): ?>
        <?php [$fascia, $colore] = rischio_fascia((float) $g['punteggio']); ?>
        <div class="rischio-riga<?= $g['controllato_di_recente'] ? ' e-controllato' : '' ?>"
             style="--accento:<?= h($colore) ?>">

          <div class="rischio-posto"><?= $i + 1 ?></div>

          <div class="rischio-chi">
            <?= avatar_top('<img class="forum-faccia" src="' . h(mc_avatar_url($g['uuid'], 40))
                  . '" alt="" width="34" height="34" loading="lazy">', $g['uuid'], 34) ?>
            <div>
              <a href="/utente?nome=<?= h(rawurlencode($g['nome'])) ?>" target="_blank" rel="noopener"
                 class="rischio-nome"><?= h($g['nome']) ?></a>
              <span class="rischio-fascia" style="color:<?= h($colore) ?>"><?= h($fascia) ?></span>
            </div>
          </div>

          <div class="rischio-conto">
            <div class="rischio-punteggio" style="color:<?= h($colore) ?>"><?= round($g['punteggio']) ?></div>
            <div class="rischio-dettaglio">
              <?php if ($g['da_punti'] > 0): ?>
                <span><?= round($g['da_punti']) ?> da <?= (int) $g['n_violazioni'] ?>
                  <?= $g['n_violazioni'] === 1 ? 'violazione' : 'violazioni' ?></span>
              <?php endif; ?>
              <?php if ($g['segnalazioni'] > 0): ?>
                <span><?= (int) $g['segnalazioni'] ?>
                  <?= $g['segnalazioni'] === 1 ? 'segnalazione' : 'segnalazioni' ?>
                  da <?= (int) $g['segnalatori'] ?>
                  <?= $g['segnalatori'] === 1 ? 'giocatore' : 'giocatori diversi' ?></span>
              <?php endif; ?>
              <?php if ($g['ban_attivi'] > 0): ?><span class="rischio-grave">ban attivo</span><?php endif; ?>
              <?php if ($g['mute_attivi'] > 0): ?><span class="rischio-grave">mute attivo</span><?php endif; ?>
            </div>
            <?php if ($g['categorie']): ?>
              <div class="rischio-categorie">
                <?php foreach ($g['categorie'] as [$cat, $n]): ?>
                  <span><?= h(sanzione_categoria($cat)) ?><?= $n > 1 ? " ×$n" : '' ?></span>
                <?php endforeach; ?>
              </div>
            <?php endif; ?>
            <div class="rischio-quando">
              <?php if (!empty($g['ultima_violazione'])): ?>
                ultima violazione <?= h(time_ago((string) $g['ultima_violazione'])) ?>
              <?php endif; ?>
              <?php if (!empty($g['ultima_segnalazione'])): ?>
                · ultima segnalazione <?= h(time_ago((string) $g['ultima_segnalazione'])) ?>
              <?php endif; ?>
            </div>
          </div>

          <div class="rischio-azione">
            <?php if ($g['controllo']): ?>
              <p class="rischio-controllo">
                Controllato <?= h(time_ago((string) $g['controllo']['controllato_il'])) ?>
                da <?= h($g['controllo']['staff_nome']) ?>
                <?php if ($g['controllo']['esito'] === 'sospetto'): ?>
                  <span class="rischio-grave">— rimasto sospetto</span>
                <?php endif; ?>
                <?php if (!empty($g['controllo']['nota'])): ?>
                  <br><em><?= h($g['controllo']['nota']) ?></em>
                <?php endif; ?>
              </p>
            <?php endif; ?>
            <form method="post" class="rischio-form">
              <?= csrf_field() ?>
              <input type="hidden" name="action" value="rischio_controllato">
              <input type="hidden" name="uuid" value="<?= h($g['uuid']) ?>">
              <input type="hidden" name="nome" value="<?= h($g['nome']) ?>">
              <input type="text" name="nota" maxlength="500" placeholder="Cosa hai visto (facoltativo)">
              <button type="submit" name="esito" value="pulito" class="btn btn-accent">Controllato, pulito</button>
              <button type="submit" name="esito" value="sospetto" class="btn btn-ghost">Ancora sospetto</button>
            </form>
          </div>
        </div>
      <?php endforeach; ?>
    </div>
  <?php endif; ?>
</div>
