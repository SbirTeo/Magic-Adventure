<?php
/**
 * La classifica di chi conviene andare a controllare.
 *
 * <p>Non e' un verdetto e non deve diventarlo: e' un <b>ordine di priorita'</b> per una persona
 * che ha tempo di guardarne cinque e vuole guardare i cinque giusti. Per questo accanto al
 * punteggio si mostra sempre da cosa e' fatto: un numero senza il suo perche' finirebbe per
 * essere usato come prova, e non lo e'.</p>
 *
 * Da cosa nasce il punteggio:
 *   - i PUNTI delle violazioni registrate dal plugin, con lo stesso decadimento del gioco
 *     (una cosa di tre mesi fa vale la meta');
 *   - le SEGNALAZIONI dei giocatori ancora aperte, pesate per quanti giocatori DIVERSI le
 *     hanno mandate: tre segnalazioni della stessa persona contano molto meno di tre persone
 *     che segnalano lo stesso nome;
 *   - i provvedimenti gia' ATTIVI, che dicono che il problema e' gia' stato riconosciuto.
 */

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/sanzioni.php';

/** Dopo quanti giorni un controllo "scade" e il giocatore torna in cima alla lista. */
const RISCHIO_CONTROLLO_VALIDO_GIORNI = 7;

/** Emivita dei punti, la stessa del plugin: mezza forza ogni 90 giorni. */
const RISCHIO_EMIVITA_GIORNI = 90;

/** Quanto pesa ogni ingrediente. Cambiarli qui cambia solo l'ORDINE della lista, non le sanzioni. */
const RISCHIO_PESI = [
    'per_segnalatore' => 10,   // ogni giocatore DIVERSO che l'ha segnalato
    'segnalazione'    => 3,    // ogni segnalazione oltre la prima dello stesso segnalatore
    'ban_attivo'      => 20,
    'mute_attivo'     => 10,
];

/**
 * L'elenco ordinato dal piu' urgente. Ogni voce contiene il punteggio E gli ingredienti,
 * cosi' chi guarda capisce perche' quel nome e' li'.
 *
 * @param bool $includiControllati se false, nasconde chi e' stato controllato di recente
 * @param int  $limite             quante voci al massimo
 */
function rischio_elenco(bool $includiControllati = false, int $limite = 60): array {
    if (!sanctions_ready()) {
        return [];
    }
    $giocatori = [];

    // --- punti delle violazioni, col decadimento gia' applicato dal database ---
    try {
        $q = db()->query(
            'SELECT mc_uuid,
                    MAX(mc_username) AS nome,
                    SUM(points * POW(0.5, DATEDIFF(NOW(), created_at) / ' . RISCHIO_EMIVITA_GIORNI . ')) AS points,
                    COUNT(*) AS n_violazioni,
                    MAX(created_at) AS ultima
               FROM punishment_violations
              WHERE cancelled = 0
              GROUP BY mc_uuid'
        );
        foreach ($q as $r) {
            $giocatori[$r['mc_uuid']] = [
                'uuid' => $r['mc_uuid'],
                'nome' => $r['nome'],
                'points' => (float) $r['points'],
                'n_violazioni' => (int) $r['n_violazioni'],
                'ultima_violazione' => $r['ultima'],
                'segnalazioni' => 0,
                'segnalatori' => 0,
                'ultima_segnalazione' => null,
                'ban_attivi' => 0,
                'mute_attivi' => 0,
                'categorie' => [],
            ];
        }
    } catch (PDOException $e) {
        // la tabella delle violazioni nasce col plugin: se non c'e' ancora, si va avanti
        // con le sole segnalazioni invece di mostrare una pagina rotta
    }

    // --- segnalazioni dei giocatori ancora aperte ---
    $q = db()->query(
        "SELECT mc_uuid, MAX(mc_username) AS nome, COUNT(*) AS n,
                COUNT(DISTINCT proposed_by) AS segnalatori, MAX(created_at) AS ultima
           FROM punishment_queue
          WHERE source = 'report' AND status = 'attesa'
          GROUP BY mc_uuid"
    );
    foreach ($q as $r) {
        $v = &$giocatori[$r['mc_uuid']];
        if (!isset($v)) {
            $v = ['uuid' => $r['mc_uuid'], 'nome' => $r['nome'], 'points' => 0.0,
                  'n_violazioni' => 0, 'ultima_violazione' => null, 'ban_attivi' => 0,
                  'mute_attivi' => 0, 'categorie' => []];
        }
        $v['segnalazioni'] = (int) $r['n'];
        $v['segnalatori'] = (int) $r['segnalatori'];
        $v['ultima_segnalazione'] = $r['ultima'];
        unset($v);
    }

    if (!$giocatori) {
        return [];
    }
    $uuidIn = implode(',', array_fill(0, count($giocatori), '?'));
    $uuids = array_keys($giocatori);

    // --- provvedimenti gia' attivi ---
    $st = db()->prepare(
        "SELECT mc_uuid,
                SUM(type = 'ban') AS ban, SUM(type = 'mute') AS mute
           FROM punishments
          WHERE status = 'attiva' AND (ends_at IS NULL OR ends_at > NOW()) AND mc_uuid IN ($uuidIn)
          GROUP BY mc_uuid"
    );
    $st->execute($uuids);
    foreach ($st as $r) {
        $giocatori[$r['mc_uuid']]['ban_attivi'] = (int) $r['ban'];
        $giocatori[$r['mc_uuid']]['mute_attivi'] = (int) $r['mute'];
    }

    // --- di cosa si tratta: le categorie piu' frequenti, per capire a colpo d'occhio ---
    try {
        $st = db()->prepare(
            "SELECT mc_uuid, category, COUNT(*) AS n
               FROM punishment_violations
              WHERE cancelled = 0 AND mc_uuid IN ($uuidIn)
              GROUP BY mc_uuid, category ORDER BY n DESC"
        );
        $st->execute($uuids);
        foreach ($st as $r) {
            if (count($giocatori[$r['mc_uuid']]['categorie']) < 3) {
                $giocatori[$r['mc_uuid']]['categorie'][] = [$r['category'], (int) $r['n']];
            }
        }
    } catch (PDOException $e) {
        // niente violazioni: si resta senza dettaglio delle categorie
    }

    // --- chi e' gia' stato controllato, e quando ---
    try {
        $st = db()->prepare(
            "SELECT c.mc_uuid, c.staff_name, c.note, c.outcome, c.checked_at
               FROM punishment_checks c
               JOIN (SELECT mc_uuid, MAX(id) AS ultimo FROM punishment_checks GROUP BY mc_uuid) u
                 ON u.ultimo = c.id
              WHERE c.mc_uuid IN ($uuidIn)"
        );
        $st->execute($uuids);
        foreach ($st as $r) {
            $giocatori[$r['mc_uuid']]['controllo'] = $r;
        }
    } catch (PDOException $e) {
        // migrazione non ancora lanciata: nessuno risulta controllato
    }

    // --- il punteggio, e il perche' ---
    $out = [];
    foreach ($giocatori as $g) {
        $g['controllo'] = $g['controllo'] ?? null;

        $daSegnalatori = $g['segnalatori'] * RISCHIO_PESI['per_segnalatore'];
        $daSegnalazioni = max(0, $g['segnalazioni'] - $g['segnalatori']) * RISCHIO_PESI['segnalazione'];
        $daSanzioni = $g['ban_attivi'] * RISCHIO_PESI['ban_attivo']
                    + $g['mute_attivi'] * RISCHIO_PESI['mute_attivo'];

        $g['punteggio'] = $g['points'] + $daSegnalatori + $daSegnalazioni + $daSanzioni;
        $g['da_punti'] = $g['points'];
        $g['da_segnalazioni'] = $daSegnalatori + $daSegnalazioni;
        $g['da_sanzioni'] = $daSanzioni;

        // Controllato di recente: esce dalla lista, a meno che non lo si chieda apposta.
        $g['controllato_di_recente'] = false;
        if ($g['controllo'] && $g['controllo']['outcome'] === 'pulito') {
            $giorni = (time() - strtotime($g['controllo']['checked_at'])) / 86400;
            $g['controllato_di_recente'] = $giorni < RISCHIO_CONTROLLO_VALIDO_GIORNI;
        }
        if (!$includiControllati && $g['controllato_di_recente']) {
            continue;
        }
        if ($g['punteggio'] <= 0) {
            continue;
        }
        $out[] = $g;
    }

    usort($out, fn($a, $b) => $b['punteggio'] <=> $a['punteggio']);
    return array_slice($out, 0, $limite);
}

/** Quanto e' urgente, detto a parole. Il colore serve a scorrere la lista, non a giudicare. */
function rischio_fascia(float $punteggio): array {
    if ($punteggio >= 60) {
        return ['Da guardare subito', '#e05a5a'];
    }
    if ($punteggio >= 30) {
        return ['Da guardare presto', '#f0883e'];
    }
    if ($punteggio >= 12) {
        return ['Da tenere d\'occhio', '#f4c531'];
    }
    return ['Poco sopra la media', '#94959b'];
}
