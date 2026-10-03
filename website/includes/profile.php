<?php
/**
 * Pezzi comuni delle due pagine del giocatore: il profilo personale (/profilo) e la scheda
 * pubblica (/utente?nome=...). Stessa impaginazione: a sinistra la "carta" (skin, nome, gradi,
 * date), a destra i blocchi "In gioco" (una scheda per modalità) e "Sul sito". Qui stanno la
 * lettura dei dati di gioco e il disegno di quei blocchi, cosi' le due pagine non si
 * allontanano piu' l'una dall'altra.
 */

/**
 * Le modalita' di gioco che hanno statistiche da mostrare nel profilo, nell'ordine delle schede.
 *
 * Ogni modalita' porta due funzioni sue: `stats` legge i dati del giocatore dal database di quella
 * modalita' (null se il database non risponde: la scheda lo dice e la pagina si apre lo stesso), e
 * `render` li disegna. Una modalita' nuova si aggiunge QUI, con le sue due funzioni piu' sotto:
 * le pagine /profilo e /utente non vanno toccate, e con due o piu' voci compaiono da sole le
 * schede per passare dall'una all'altra. La chiave e' la stessa di GAME_SERVERS (helpers.php).
 * L'hub non c'e': e' una sala d'ingresso, non ha statistiche.
 */
const PROFILE_GAME_MODES = [
    'faction' => [
        'label'  => 'Factions',
        'icon'   => 'swords',
        'stats'  => 'profile_faction_stats',
        'render' => 'profile_faction_render',
    ],
];

/**
 * Blocco "In gioco": una scheda per ogni modalita' di PROFILE_GAME_MODES. Con una modalita' sola
 * il nome sta accanto al titolo; con piu' modalita' diventa una fila di schede (site.js).
 * $own cambia solo le parole ("Non sei in nessuna fazione" / "Non è in nessuna fazione").
 */
function profile_game_panel(int $userId, bool $own): string {
    $modi = PROFILE_GAME_MODES;
    $molte = count($modi) > 1;
    ob_start();
    ?>
    <section class="panel profilo-blocco profilo-modalita" aria-labelledby="profiloInGioco"<?= $molte ? ' data-profilo-schede' : '' ?>>
      <div class="profilo-blocco-testa">
        <h2 class="profilo-blocco-titolo" id="profiloInGioco"><?= ui_icon('gamepad') ?> In gioco</h2>
        <?php if ($molte): ?>
          <div class="profilo-schede" role="tablist" aria-label="Modalità">
            <?php $primo = true; foreach ($modi as $id => $modo): ?>
              <button type="button" role="tab" id="profiloScheda-<?= h($id) ?>" aria-controls="profiloModo-<?= h($id) ?>"
                      aria-selected="<?= $primo ? 'true' : 'false' ?>"<?= $primo ? '' : ' tabindex="-1"' ?>>
                <?= ui_icon($modo['icon']) ?><?= h($modo['label']) ?>
              </button>
            <?php $primo = false; endforeach; ?>
          </div>
        <?php else: ?>
          <?php $solo = reset($modi); ?>
          <span class="profilo-modalita-nome"><?= ui_icon($solo['icon']) ?><?= h($solo['label']) ?></span>
        <?php endif; ?>
      </div>
      <?php $primo = true; foreach ($modi as $id => $modo): ?>
        <?php $dati = is_callable($modo['stats']) ? $modo['stats']($userId) : null; ?>
        <div class="profilo-modo" id="profiloModo-<?= h($id) ?>"<?= $molte ? ' role="tabpanel" aria-labelledby="profiloScheda-' . h($id) . '"' : '' ?><?= $primo ? '' : ' hidden' ?>>
          <?php if ($dati === null || !is_callable($modo['render'])): ?>
            <p class="profilo-vuoto">I dati di <?= h($modo['label']) ?> non sono raggiungibili in questo momento: riprova fra poco.</p>
          <?php else: ?>
            <?= $modo['render']($dati, $own) ?>
          <?php endif; ?>
        </div>
      <?php $primo = false; endforeach; ?>
    </section>
    <?php
    return (string) ob_get_clean();
}

/* ---- Modalità Factions (MagixFactions) ------------------------------------------------- */

/** Fazione, grado nella fazione, potenza e territori (database di MagixFactions, a parte). */
function profile_faction_stats(int $userId): ?array {
    try {
        // `rank` fra apici inversi: e' una parola riservata di MariaDB/MySQL (funzione finestra).
        $q = db()->prepare("
            SELECT p.power, p.max_power, f.name AS faction_name, f.tag AS faction_tag,
                   fm.`rank` AS faction_rank,
                   (SELECT COUNT(*) FROM factions_magixfactions.claims c WHERE c.faction_id = f.id) AS territories
            FROM users u
            LEFT JOIN factions_magixfactions.players p ON p.uuid = u.mc_uuid COLLATE utf8mb4_unicode_ci
            LEFT JOIN factions_magixfactions.faction_members fm ON fm.uuid = u.mc_uuid COLLATE utf8mb4_unicode_ci
            LEFT JOIN factions_magixfactions.factions f ON f.id = fm.faction_id
            WHERE u.id = ?
        ");
        $q->execute([$userId]);
        return $q->fetch() ?: [];
    } catch (PDOException $e) {
        return null;
    }
}

/** Il nome del grado nella fazione, dallo specchio del config; se manca, l'id con l'iniziale grande. */
function profile_faction_rank(?array $stats): string {
    $r = (string) ($stats['faction_rank'] ?? '');
    if ($r === '') {
        return '';
    }
    $nome = faction_ranks_map()[strtolower($r)]['name'] ?? '';
    return $nome !== '' ? $nome : ucfirst($r);
}

/** Fazione (sigla, grado, territori) e potenza con la sua barra. */
function profile_faction_render(array $stats, bool $own): string {
    ob_start();
    ?>
        <?php if (!empty($stats['faction_name'])): ?>
          <?php
            $grado = profile_faction_rank($stats);
            $territori = (int) ($stats['territories'] ?? 0);
            $sigla = trim((string) ($stats['faction_tag'] ?? ''));
          ?>
          <div class="profilo-fazione">
            <span class="profilo-fazione-sigla" aria-hidden="true"><?= h($sigla !== '' ? $sigla : mb_substr((string) $stats['faction_name'], 0, 3)) ?></span>
            <div class="profilo-fazione-testo">
              <strong><?= h($stats['faction_name']) ?></strong>
              <small>
                <?= $grado !== '' ? h($grado) . ' &middot; ' : '' ?>
                <?= $territori === 1 ? '1 territorio' : $territori . ' territori' ?>
              </small>
            </div>
          </div>
        <?php else: ?>
          <div class="profilo-fazione is-vuota">
            <span class="profilo-fazione-sigla" aria-hidden="true"><?= ui_icon('users') ?></span>
            <div class="profilo-fazione-testo">
              <strong><?= $own ? 'Non sei in nessuna fazione' : 'Non è in nessuna fazione' ?></strong>
              <small><?= $own ? 'Fondane una con <code>/f create</code> o fatti invitare.' : 'Gioca da solo, per ora.' ?></small>
            </div>
          </div>
        <?php endif; ?>

        <?php if (($stats['power'] ?? null) !== null && (int) $stats['max_power'] > 0): ?>
          <?php
            $potenza = (int) $stats['power'];
            $massima = (int) $stats['max_power'];
            $quota = max(0, min(100, (int) round($potenza / $massima * 100)));
          ?>
          <div class="profilo-potenza<?= $potenza < 0 ? ' is-negativa' : '' ?>">
            <div class="profilo-potenza-riga">
              <span><?= ui_icon('zap') ?> Potenza</span>
              <strong><?= $potenza ?> <span>/ <?= $massima ?></span></strong>
            </div>
            <div class="profilo-potenza-barra" role="img" aria-label="Potenza <?= $potenza ?> su <?= $massima ?>">
              <i style="width: <?= $quota ?>%"></i>
            </div>
          </div>
        <?php else: ?>
          <p class="profilo-vuoto"><?= $own ? 'Entra su <strong>mc.magicadventure.it</strong> per vedere qui la tua potenza.' : 'Non è ancora entrato in Factions.' ?></p>
        <?php endif; ?>
    <?php
    return (string) ob_get_clean();
}

/**
 * Blocco "Sul sito": i numeri del forum in una riga sola, e gli acquisti solo se ce ne sono
 * (uno zero in piu' non dice niente a nessuno).
 *
 * @param array<string, int> $numbers etichetta => valore
 */
function profile_site_panel(array $numbers, string $extra = ''): string {
    ob_start();
    ?>
    <section class="panel profilo-blocco" aria-labelledby="profiloSulSito">
      <h2 class="profilo-blocco-titolo" id="profiloSulSito"><?= ui_icon('message') ?> Sul sito</h2>
      <div class="profilo-numeri">
        <?php foreach ($numbers as $etichetta => $valore): ?>
          <div><strong><?= (int) $valore ?></strong><span><?= h($etichetta) ?></span></div>
        <?php endforeach; ?>
      </div>
      <?= $extra ?>
    </section>
    <?php
    return (string) ob_get_clean();
}
