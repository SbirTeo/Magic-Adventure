<?php
/**
 * Scheda «Voice» del gestionale (/manage?section=voice): chi è nelle stanze vocali adesso, e i due
 * interventi dello staff.
 *
 *  - Silenzia: un mute di SOLA voce, a tempo (tabella voice_mutes). Il microfono si spegne subito in
 *    ogni stanza (API del server della voce, includes/voice.php), e resta spento a ogni rientro fino
 *    alla scadenza: il sito non firma più gettoni per parlare, e MagixBridge riapplica il mute se
 *    serve (voice/VoiceModeration). Non è una sanzione: niente archivio pubblico, niente ricorso.
 *    Per un provvedimento vero si usa MagixGuard, e un suo mute vale da solo anche in voce.
 *  - Togli dalla stanza: fuori subito. Può rientrare (regole permettendo): serve a spezzare una
 *    situazione, non a punire.
 *
 * Permessi: voice.view per vedere, voice.moderate per intervenire (includes/permissions.php).
 */
require_once __DIR__ . '/voice.php';

/** Durate proposte per il mute di voce: valore del modulo => [minuti, etichetta]. */
const VOICE_MUTE_DURATIONS = [
    '15m' => [15, '15 minuti'],
    '1h'  => [60, '1 ora'],
    '24h' => [1440, '24 ore'],
    '7d'  => [10080, '7 giorni'],
];

/** Esegue un'azione del pannello e dice dove tornare (pattern PRG di manage.php). */
function voice_panel_action(string $azione, array $me): string {
    $uuid = strtolower(trim((string) ($_POST['uuid'] ?? '')));
    if (!preg_match('/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/', $uuid) || !voice_ready()) {
        return '/manage?section=voice&err=voice';
    }
    $nome = trim((string) ($_POST['name'] ?? ''));
    $nome = preg_match('/^[A-Za-z0-9_]{1,32}$/', $nome) ? $nome : substr($uuid, 0, 8);

    if ($azione === 'voice_mute') {
        $durata = VOICE_MUTE_DURATIONS[$_POST['duration'] ?? ''] ?? VOICE_MUTE_DURATIONS['15m'];
        $motivo = mb_substr(trim((string) ($_POST['reason'] ?? '')), 0, 200);
        db()->prepare('INSERT INTO voice_mutes (mc_uuid, mc_username, ends_at, staff, reason)
                       VALUES (?, ?, NOW() + INTERVAL ? MINUTE, ?, ?)')
            ->execute([$uuid, $nome, $durata[0], (string) $me['mc_username'], $motivo !== '' ? $motivo : null]);
        voice_live_set_publish($uuid, false);
    } elseif ($azione === 'voice_unmute') {
        db()->prepare('UPDATE voice_mutes SET lifted_at = NOW(), lifted_by = ?
                        WHERE mc_uuid = ? AND lifted_at IS NULL AND ends_at > NOW()')
            ->execute([(string) $me['mc_username'], $uuid]);
        // Il microfono torna solo se non c'è ANCHE un mute di MagixGuard.
        if (voice_sanctions($uuid)['mute'] === null) {
            voice_live_set_publish($uuid, true);
        }
    } elseif ($azione === 'voice_kick') {
        voice_live_remove($uuid);
    }
    return '/manage?section=voice&ok=' . $azione;
}

/** La scheda: stanze aperte con chi c'è dentro, e i mute di voce in corso. */
function voice_panel_render(array $me): void {
    if (!voice_ready()) {
        echo '<div class="alert alert-info">Il server della voce non è ancora configurato su questo sito '
            . '(predisponi-voce.yml).</div>';
        return;
    }
    $stanze = voice_admin_rooms();
    $mute = voice_active_mutes();
    $mutati = array_column($mute, null, 'mc_uuid');
    $puoIntervenire = can('voice.moderate');
    $roomLabel = function (string $stanza): string {
        if (str_starts_with($stanza, 'near-')) {
            $s = substr($stanza, 5);
            return 'Vicini in ' . (GAME_SERVERS[$s]['label'] ?? ucfirst($s));
        }
        if (str_starts_with($stanza, 'faction-')) {
            try {
                $q = db()->prepare('SELECT name FROM factions_magixfactions.factions WHERE id = ?');
                $q->execute([(int) substr($stanza, 8)]);
                $n = $q->fetchColumn();
                if ($n !== false) {
                    return 'Fazione ' . $n;
                }
            } catch (PDOException $e) { /* database delle fazioni non raggiungibile */ }
            return 'Fazione n. ' . (int) substr($stanza, 8);
        }
        return $stanza === 'network' ? 'Tutta la rete' : $stanza;
    };
    ?>
    <p class="sub" style="margin-bottom:14px;">
      Chi è adesso nelle stanze di <a href="/voice">Voice</a>. <strong>Silenzia</strong> toglie la parola in tutte
      le stanze fino alla scadenza (resta ad ascoltare); <strong>Togli</strong> lo fa uscire dalla stanza, ma può
      rientrare. Non sono sanzioni: per un provvedimento vero usa MagixGuard in gioco, che vale da solo anche qui
      (un ban fa uscire, un mute toglie la parola, entro pochi secondi).
    </p>

    <?php if (!$stanze): ?>
      <div class="alert alert-info">Nessuno in chat vocale in questo momento.</div>
    <?php endif; ?>

    <?php foreach ($stanze as $s): ?>
      <section class="panel voice-admin-stanza">
        <h3><?= ui_icon(voice_room_icon($s['name'])) ?> <?= h($roomLabel($s['name'])) ?>
          <span class="online-conta"><?= count($s['people']) ?></span></h3>
        <?php foreach ($s['people'] as $p): ?>
          <div class="manage-row">
            <div>
              <div class="title">
                <img src="<?= h(mc_avatar_url($p['identity'], 32)) ?>" alt="" width="20" height="20"
                     style="vertical-align:middle;border-radius:4px;image-rendering:pixelated">
                <?= h($p['name']) ?>
                <?php if (isset($mutati[$p['identity']])): ?><span class="badge-no">silenziato</span><?php endif; ?>
              </div>
              <div class="sub">
                <?= !$p['can_publish'] ? 'solo ascolto' : ($p['mic'] ? 'microfono acceso' : 'microfono spento') ?>
                <?= $p['joined'] > 0 ? ' · dentro da ' . h(time_ago(date('Y-m-d H:i:s', $p['joined']))) : '' ?>
              </div>
            </div>
            <?php if ($puoIntervenire): ?>
              <div class="actions">
                <?php if (!isset($mutati[$p['identity']])): ?>
                  <form method="post" class="voice-admin-mute">
                    <?= csrf_field() ?>
                    <input type="hidden" name="action" value="voice_mute">
                    <input type="hidden" name="uuid" value="<?= h($p['identity']) ?>">
                    <input type="hidden" name="name" value="<?= h($p['name']) ?>">
                    <select name="duration" aria-label="Durata">
                      <?php foreach (VOICE_MUTE_DURATIONS as $k => $d): ?>
                        <option value="<?= h($k) ?>"><?= h($d[1]) ?></option>
                      <?php endforeach; ?>
                    </select>
                    <input type="text" name="reason" maxlength="200" placeholder="Motivo (facoltativo)">
                    <button type="submit" class="btn btn-danger btn-small">Silenzia</button>
                  </form>
                <?php endif; ?>
                <form method="post" onsubmit="return confirm('Togliere <?= h(addslashes($p['name'])) ?> dalla stanza?');">
                  <?= csrf_field() ?>
                  <input type="hidden" name="action" value="voice_kick">
                  <input type="hidden" name="uuid" value="<?= h($p['identity']) ?>">
                  <input type="hidden" name="name" value="<?= h($p['name']) ?>">
                  <button type="submit" class="btn btn-ghost btn-small">Togli</button>
                </form>
              </div>
            <?php endif; ?>
          </div>
        <?php endforeach; ?>
      </section>
    <?php endforeach; ?>

    <h2 class="voice-admin-titolo">Silenziati in voce</h2>
    <?php if (!$mute): ?>
      <p class="sub">Nessuno.</p>
    <?php endif; ?>
    <?php foreach ($mute as $m): ?>
      <div class="manage-row">
        <div>
          <div class="title"><?= h($m['mc_username']) ?></div>
          <div class="sub">fino al <?= h(date('d/m/Y H:i', strtotime((string) $m['ends_at']))) ?> · da
            <?= h($m['staff']) ?><?= $m['reason'] ? ' · ' . h($m['reason']) : '' ?></div>
        </div>
        <?php if ($puoIntervenire): ?>
          <div class="actions">
            <form method="post">
              <?= csrf_field() ?>
              <input type="hidden" name="action" value="voice_unmute">
              <input type="hidden" name="uuid" value="<?= h($m['mc_uuid']) ?>">
              <input type="hidden" name="name" value="<?= h($m['mc_username']) ?>">
              <button type="submit" class="btn btn-green btn-small">Ridai la parola</button>
            </form>
          </div>
        <?php endif; ?>
      </div>
    <?php endforeach;
}
