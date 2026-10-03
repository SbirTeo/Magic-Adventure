<?php
/**
 * Pezzi comuni delle due pagine del giocatore: il profilo personale (/profilo) e la scheda
 * pubblica (/utente?nome=...). Stessa impaginazione: a sinistra la "carta" (skin, nome, gradi,
 * date), a destra i blocchi "In gioco" e "Sul sito". Qui stanno la lettura dei dati di gioco e
 * il disegno di quei blocchi, cosi' le due pagine non si allontanano piu' l'una dall'altra.
 */

/**
 * Fazione, grado nella fazione, potenza e territori (database di MagixFactions, a parte).
 * Null se quel database non risponde: la pagina si apre lo stesso, con un avviso al posto dei dati.
 */
function profile_game_stats(int $userId): ?array {
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
        return $q->fetch() ?: null;
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

/**
 * Blocco "In gioco": fazione (con sigla, grado e territori) e potenza con la sua barra.
 * $own cambia solo le parole ("Non sei in nessuna fazione" / "Non è in nessuna fazione").
 */
function profile_game_panel(?array $stats, bool $own): string {
    ob_start();
    ?>
    <section class="panel profilo-blocco" aria-labelledby="profiloInGioco">
      <h2 class="profilo-blocco-titolo" id="profiloInGioco"><?= ui_icon('swords') ?> In gioco</h2>
      <?php if ($stats === null): ?>
        <p class="profilo-vuoto">I dati di gioco non sono raggiungibili in questo momento: riprova fra poco.</p>
      <?php else: ?>
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

        <?php if ($stats['power'] !== null && (int) $stats['max_power'] > 0): ?>
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
          <p class="profilo-vuoto"><?= $own ? 'Entra su <strong>mc.magicadventure.it</strong> per vedere qui la tua potenza.' : 'Non è ancora entrato in gioco.' ?></p>
        <?php endif; ?>
      <?php endif; ?>
    </section>
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
