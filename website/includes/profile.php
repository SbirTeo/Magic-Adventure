<?php
/**
 * Pezzi comuni delle due pagine del giocatore: il profilo personale (/profilo) e la scheda
 * pubblica (/utente/...). Stessa impaginazione: a sinistra la "carta" (skin, nome, gradi,
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

/**
 * Tutto quello che il profilo mostra di Factions: le statistiche del giocatore e, se e' in una
 * fazione, la fazione intera (dati, posizione in classifica, membri, alleati). Null solo se il
 * database di MagixFactions non risponde; le letture di contorno, se falliscono, lasciano vuota
 * solo la loro parte.
 */
function profile_faction_stats(int $userId): ?array {
    $fdb = 'factions_magixfactions';
    try {
        $q = db()->prepare("
            SELECT u.mc_uuid, p.power, p.max_power, fm.faction_id, fm.`rank` AS faction_rank, fm.joined_at
            FROM users u
            LEFT JOIN {$fdb}.players p ON p.uuid = u.mc_uuid COLLATE utf8mb4_unicode_ci
            LEFT JOIN {$fdb}.faction_members fm ON fm.uuid = u.mc_uuid COLLATE utf8mb4_unicode_ci
            WHERE u.id = ?
        ");
        $q->execute([$userId]);
        $io = $q->fetch() ?: [];
    } catch (PDOException $e) {
        return null;
    }
    $dati = ['player' => $io, 'faction' => null, 'members' => [], 'allies' => []];

    // Statistiche personali (colonne aggiunte dal plugin in un secondo tempo: lettura a parte).
    try {
        $q = db()->prepare("SELECT kills, deaths, play_seconds FROM {$fdb}.players WHERE uuid = ? COLLATE utf8mb4_unicode_ci");
        $q->execute([(string) ($io['mc_uuid'] ?? '')]);
        $dati['player'] += $q->fetch() ?: [];
    } catch (PDOException $e) {
        // niente uccisioni e tempo di gioco: si mostra il resto
    }

    $fid = (int) ($io['faction_id'] ?? 0);
    if ($fid <= 0) {
        return $dati;
    }
    try {
        $q = db()->prepare("
            SELECT f.id, f.name, f.tag, f.description, f.bank, f.created_at, f.score, f.ranked,
                   (SELECT COUNT(*) FROM {$fdb}.claims c WHERE c.faction_id = f.id) AS claims
            FROM {$fdb}.factions f WHERE f.id = ?
        ");
        $q->execute([$fid]);
        $dati['faction'] = $q->fetch() ?: null;
    } catch (PDOException $e) {
        $dati['faction'] = null;
    }
    if (!$dati['faction']) {
        return $dati;
    }

    // Posizione in classifica: quante fazioni in classifica hanno un punteggio piu' alto.
    try {
        if ((int) $dati['faction']['ranked'] === 1) {
            // Stesso ordine della classifica (score DESC, nome ASC) e il punteggio confrontato SENZA passare da
            // PHP: un DOUBLE riscritto come testo si arrotonda, e la fazione finiva per contare se' stessa
            // (un posto in meno di quello vero).
            $q = db()->prepare(
                "SELECT COUNT(*) FROM {$fdb}.factions o, {$fdb}.factions me
                  WHERE me.id = ? AND o.ranked = 1 AND o.id <> me.id
                    AND (o.score > me.score OR (o.score = me.score AND o.name < me.name))"
            );
            $q->execute([(int) $dati['faction']['id']]);
            $dati['faction']['position'] = (int) $q->fetchColumn() + 1;
            $dati['faction']['ranked_total'] = (int) db()->query("SELECT COUNT(*) FROM {$fdb}.factions WHERE ranked = 1")->fetchColumn();
        }
    } catch (PDOException $e) {
        // senza posizione: il riquadro della classifica dice solo "vedi la classifica"
    }

    // Membri, dal grado piu' alto (ordine dei gradi: tabella faction_ranks, specchio del config).
    try {
        $ordine = [];
        foreach (db()->query("SELECT rank_id, ord FROM {$fdb}.faction_ranks")->fetchAll() as $r) {
            $ordine[strtolower((string) $r['rank_id'])] = (int) $r['ord'];
        }
        $q = db()->prepare("
            SELECT m.uuid, m.`rank`, m.joined_at, p.name, p.power, p.max_power,
                   u.mc_username AS site_name, u.premium_uuid
            FROM {$fdb}.faction_members m
            LEFT JOIN {$fdb}.players p ON p.uuid = m.uuid
            LEFT JOIN users u ON u.mc_uuid = m.uuid COLLATE utf8mb4_unicode_ci
            WHERE m.faction_id = ?
        ");
        $q->execute([$fid]);
        $membri = $q->fetchAll();
        usort($membri, static function ($a, $b) use ($ordine) {
            $d = ($ordine[strtolower((string) $b['rank'])] ?? 0) <=> ($ordine[strtolower((string) $a['rank'])] ?? 0);
            return $d !== 0 ? $d : strcasecmp((string) $a['name'], (string) $b['name']);
        });
        $dati['members'] = $membri;
    } catch (PDOException $e) {
        $dati['members'] = [];
    }

    // Alleati veri: alleanza dichiarata da entrambe le parti, come in gioco.
    try {
        $q = db()->prepare("
            SELECT fo.name FROM {$fdb}.relations r1
            JOIN {$fdb}.relations r2 ON r2.faction_id = r1.other_id AND r2.other_id = r1.faction_id AND r2.type = 'ALLY'
            JOIN {$fdb}.factions fo ON fo.id = r1.other_id
            WHERE r1.faction_id = ? AND r1.type = 'ALLY'
            ORDER BY fo.name
        ");
        $q->execute([$fid]);
        $dati['allies'] = $q->fetchAll(PDO::FETCH_COLUMN);
    } catch (PDOException $e) {
        $dati['allies'] = [];
    }
    return $dati;
}

/** Il nome di un grado di fazione, dallo specchio del config; se manca, l'id con l'iniziale grande. */
function profile_faction_rank(string $rankId): string {
    if ($rankId === '') {
        return '';
    }
    $nome = faction_ranks_map()[strtolower($rankId)]['name'] ?? '';
    return $nome !== '' ? $nome : ucfirst($rankId);
}

/** Una data salvata dal plugin in millisecondi, come giorno; vuota se manca. */
function profile_ms_date($ms): string {
    $ms = (int) $ms;
    return $ms > 0 ? date('d/m/Y', intdiv($ms, 1000)) : '';
}

/** Secondi di gioco in forma leggibile: "3g 4h", "5h 12m", "42m". */
function profile_playtime(int $seconds): string {
    if ($seconds <= 0) {
        return '—';
    }
    $g = intdiv($seconds, 86400);
    $o = intdiv($seconds % 86400, 3600);
    $m = intdiv($seconds % 3600, 60);
    if ($g > 0) return $o > 0 ? "{$g}g {$o}h" : "{$g}g";
    if ($o > 0) return $m > 0 ? "{$o}h {$m}m" : "{$o}h";
    return "{$m}m";
}

/**
 * La scheda Factions: la fazione (posizione in classifica, territori, potenza e se e' conquistabile,
 * banca, fondazione, membri con avatar, alleati) e sotto le statistiche personali.
 */
/**
 * L'avatar di una fazione. Per ora e' per tutte lo stemma di serie; quando le fazioni potranno
 * scegliersi il proprio basta restituire qui il loro (es. $f['avatar']) e le schede lo mostrano.
 */
function faction_avatar_url(array $f): string {
    return '/assets/img/fazione-default.svg';
}

function profile_faction_render(array $dati, bool $own): string {
    $io = $dati['player'] ?? [];
    $f = $dati['faction'] ?? null;
    $membri = $dati['members'] ?? [];
    $mioUuid = strtolower(str_replace('-', '', (string) ($io['mc_uuid'] ?? '')));
    ob_start();
    if ($f):
        $territori = (int) $f['claims'];
        $potenza = 0; $potenzaMax = 0;
        foreach ($membri as $m) { $potenza += (int) $m['power']; $potenzaMax += (int) $m['max_power']; }
        $pos = (int) ($f['position'] ?? 0);
        $desc = trim((string) ($f['description'] ?? ''));
    ?>
      <div class="profilo-fazione">
        <img class="profilo-fazione-avatar" src="<?= h(faction_avatar_url($f)) ?>" alt="" width="64" height="64">
        <div class="profilo-fazione-testo">
          <strong><?= h($f['name']) ?></strong>
          <?php if ($desc !== ''): ?>
            <p class="profilo-fazione-desc">&ldquo;<?= h($desc) ?>&rdquo;</p>
          <?php endif; ?>
        </div>
        <a class="profilo-fazione-posto<?= $pos > 0 ? ' ha-medaglia' : '' ?>" href="/classifiche" title="Apri la classifica delle fazioni">
          <?php if ($pos > 0): ?>
            <span class="posto-testo"><strong><?= $pos ?>&ordm; posto</strong><small>su <?= (int) ($f['ranked_total'] ?? $pos) ?> in classifica</small><em class="posto-vai">Vai alle classifiche &rarr;</em></span>
            <span class="medaglia medaglia-<?= $pos <= 3 ? $pos : 'altro' ?>" aria-hidden="true">
              <i class="medaglia-nastro"></i><b class="medaglia-disco"><span><?= $pos ?></span></b>
            </span>
          <?php elseif ((int) $f['ranked'] !== 1): ?>
            <?= ui_icon('trophy') ?><span class="posto-testo"><strong>Fuori classifica</strong><small>vedi la classifica</small></span>
          <?php else: ?>
            <?= ui_icon('trophy') ?><span class="posto-testo"><strong>Classifica</strong><small>vedi le posizioni</small></span>
          <?php endif; ?>
        </a>
      </div>

      <div class="profilo-fazione-numeri">
        <div><span><?= ui_icon('map') ?> Territori</span><strong><?= $territori ?></strong></div>
        <div>
          <span><?= ui_icon('zap') ?> Potenza</span>
          <strong><?= $potenza ?> <small>/ <?= $potenzaMax ?></small></strong>
          <?php if ($territori > 0): ?>
            <em class="<?= $potenza >= $territori ? 'is-sicura' : 'is-conquistabile' ?>"><?= $potenza >= $territori ? 'Al sicuro' : 'Conquistabile' ?></em>
          <?php endif; ?>
        </div>
        <div><span><?= ui_icon('users') ?> Membri</span><strong><?= count($membri) ?></strong></div>
        <div><span><?= ui_icon('coins') ?> Banca</span><strong><?= h(number_format((float) $f['bank'], 0, ',', '.')) ?></strong></div>
        <?php if (profile_ms_date($f['created_at'] ?? 0) !== ''): ?>
          <div><span><?= ui_icon('hourglass') ?> Fondata</span><strong class="is-data"><?= h(profile_ms_date($f['created_at'])) ?></strong></div>
        <?php endif; ?>
      </div>

      <?php if ($membri): ?>
        <h3 class="profilo-sottotitolo"><?= ui_icon('users') ?> Membri</h3>
        <div class="profilo-membri">
          <?php foreach ($membri as $m): ?>
            <?php
              $nome = (string) ($m['name'] ?? '');
              if ($nome === '') continue;
              $eLui = strtolower(str_replace('-', '', (string) $m['uuid'])) === $mioUuid;
              $gradoM = profile_faction_rank((string) $m['rank']);
              $capo = strtolower((string) $m['rank']) === 'leader';
              $tag = !empty($m['site_name']) ? 'a' : 'span';
              $href = !empty($m['site_name']) ? ' href="/utente/' . h(rawurlencode((string) $m['site_name'])) . '"' : '';
            ?>
            <<?= $tag ?> class="profilo-membro<?= $eLui ? ' is-lui' : '' ?>"<?= $href ?> title="<?= h($nome) ?> · Potenza <?= (int) $m['power'] ?>">
              <img src="<?= h(mc_avatar_url((string) $m['uuid'], 64, $m['premium_uuid'] ?? null)) ?>" alt="" width="32" height="32" loading="lazy">
              <span>
                <strong><?= h($nome) ?></strong>
                <small><?= $capo ? ui_icon('crown') . ' ' : '' ?><?= h($gradoM) ?></small>
              </span>
            </<?= $tag ?>>
          <?php endforeach; ?>
        </div>
      <?php endif; ?>

      <?php if (!empty($dati['allies'])): ?>
        <p class="profilo-alleati"><span><?= ui_icon('shield-check') ?> Alleati</span>
          <?php foreach ($dati['allies'] as $alleato): ?><em><?= h($alleato) ?></em><?php endforeach; ?>
        </p>
      <?php endif; ?>
    <?php else: ?>
      <div class="profilo-fazione is-vuota">
        <span class="profilo-fazione-sigla" aria-hidden="true"><?= ui_icon('users') ?></span>
        <div class="profilo-fazione-testo">
          <strong><?= $own ? 'Non sei in nessuna fazione' : 'Non è in nessuna fazione' ?></strong>
          <small><?= $own ? 'Fondane una con <code>/f create</code> o fatti invitare.' : 'Gioca da solo, per ora.' ?></small>
        </div>
        <a class="profilo-fazione-posto" href="/classifiche"><?= ui_icon('trophy') ?><span class="posto-testo"><strong>Classifica</strong><small>le fazioni più forti</small></span></a>
      </div>
    <?php endif; ?>

    <h3 class="profilo-sottotitolo"><?= ui_icon('user') ?> <?= $own ? 'Le tue statistiche' : 'Statistiche personali' ?></h3>
    <?php if (($io['power'] ?? null) !== null && (int) $io['max_power'] > 0): ?>
      <?php
        $p = (int) $io['power'];
        $pm = (int) $io['max_power'];
        // Scala da -massimo a +massimo con lo zero al centro, come nella guida: i valori
        // positivi riempiono verso destra in verde, i negativi verso sinistra in rosso.
        $quota = min(50, (int) round(abs($p) / $pm * 50));
      ?>
      <div class="profilo-potenza<?= $p < 0 ? ' is-negativa' : '' ?>">
        <div class="profilo-potenza-riga">
          <span><?= ui_icon('zap') ?> Potenza</span>
          <strong><?= $p ?> <span>/ <?= $pm ?></span></strong>
        </div>
        <div class="profilo-potenza-barra is-centrata" role="img" aria-label="Potenza <?= $p ?>, da <?= -$pm ?> a <?= $pm ?>">
          <?php if ($p !== 0): ?>
            <i class="<?= $p < 0 ? 'is-meno' : 'is-piu' ?>" style="width: <?= $quota ?>%"></i>
          <?php endif; ?>
        </div>
        <div class="profilo-potenza-scala" aria-hidden="true"><span>&minus;<?= $pm ?></span><span>0</span><span>+<?= $pm ?></span></div>
      </div>
      <?php
        $uccisioni = (int) ($io['kills'] ?? 0);
        $morti = (int) ($io['deaths'] ?? 0);
        $kd = $morti > 0 ? $uccisioni / $morti : $uccisioni;
      ?>
      <div class="profilo-numeri profilo-numeri-gioco">
        <div><strong><?= $uccisioni ?></strong><span>Uccisioni</span></div>
        <div><strong><?= $morti ?></strong><span>Morti</span></div>
        <div><strong><?= h(number_format($kd, 2, ',', '.')) ?></strong><span>K/D</span></div>
        <div><strong><?= h(profile_playtime((int) ($io['play_seconds'] ?? 0))) ?></strong><span>Tempo di gioco</span></div>
      </div>
    <?php else: ?>
      <p class="profilo-vuoto"><?= $own ? 'Entra su <strong>mc.magicadventure.it</strong> per vedere qui le tue statistiche.' : 'Non è ancora entrato in Factions.' ?></p>
    <?php endif; ?>

    <a class="profilo-link-classifica" href="/classifiche">Vedi le classifiche di Factions &rarr;</a>
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
