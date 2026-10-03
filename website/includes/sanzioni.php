<?php
/**
 * Sanzioni: il lato SITO.
 *
 * Le sanzioni le CREA MagixGuard, che e' l'unica penna (vedi
 * plugins-src/MagixGuard/PROGETTO-SANZIONI.md): il sito non inventa provvedimenti.
 * Quello che nasce qui sono i RICORSI e le decisioni dello staff sul gestionale —
 * revoche e conferme dalla coda — che il plugin poi esegue in gioco: una revoca resta
 * marcata `revoke_applied = 0` finche' il server non ha davvero tolto il ban.
 *
 * Nota sullo stato: una sanzione scaduta NON viene riscritta a 'scaduta' da qui. Lo stato
 * vero si calcola leggendo (`sanzione_e_attiva`): cosi' l'elenco pubblico dice la verita'
 * anche quando il server di gioco e' spento e nessuno ha aggiornato niente.
 */

require_once __DIR__ . '/db.php';

/** Tipi di provvedimento: etichetta al singolare e colore del filo a sinistra. */
const SANZIONI_TIPI = [
    'ban'  => ['etichetta' => 'Bandito',    'breve' => 'Ban',     'colore' => '#e05a5a'],
    'kick' => ['etichetta' => 'Espulso',    'breve' => 'Espulso', 'colore' => '#f0883e'],
    'mute' => ['etichetta' => 'Silenziato', 'breve' => 'Mute',    'colore' => '#f4c531'],
    'warn' => ['etichetta' => 'Richiamo',   'breve' => 'Richiamo','colore' => '#94959b'],
];

/**
 * Categorie note, con il nome in italiano che legge il pubblico. I codici sono gli stessi
 * di `sanzioni.yml` nel plugin: se il plugin ne aggiunge una che qui manca, si mostra il
 * codice ripulito invece di sparire.
 */
const SANZIONI_CATEGORIE = [
    'chat.spam'           => 'Spam in chat',
    'chat.insulti'        => 'Insulti',
    'chat.pubblicita'     => 'Pubblicità di altri server',
    'chat.dati-personali' => 'Dati personali',
    'cheat.movimento'     => 'Cheat di movimento',
    'cheat.combat'        => 'Cheat in combattimento',
    'cheat.xray'          => 'X-ray',
    'afk.elusione'        => 'Elusione dell\'anti-AFK',
    'report.confermato'   => 'Segnalazione confermata',
    'manuale'             => 'Decisione dello staff',
];

/** Ambiti: dove vale il provvedimento. */
const SANZIONI_AMBITI = [
    'gioco'   => 'Solo in gioco',
    'sito'    => 'Solo sul sito',
    'entrambi' => 'Gioco e sito',
];

/**
 * True se le tabelle esistono. Prima che la migrazione giri, le pagine non devono
 * esplodere: mostrano l'elenco vuoto e lo dicono.
 */
function sanctions_ready(): bool {
    static $pronte = null;
    if ($pronte !== null) {
        return $pronte;
    }
    try {
        db()->query('SELECT 1 FROM punishments LIMIT 1');
        return $pronte = true;
    } catch (PDOException $e) {
        return $pronte = false;
    }
}

/** Etichetta del tipo ('Bandito'), o il codice se sconosciuto. */
function sanction_type(string $tipo, string $campo = 'etichetta'): string {
    return SANZIONI_TIPI[$tipo][$campo] ?? ucfirst($tipo);
}

/** Colore del tipo, per il filo a sinistra della riga. */
function sanction_color(string $tipo): string {
    return SANZIONI_TIPI[$tipo]['colore'] ?? '#94959b';
}

/** Nome leggibile della categoria; se il plugin ne inventa una, si mostra ripulita. */
function sanction_category(string $codice): string {
    if (isset(SANZIONI_CATEGORIE[$codice])) {
        return SANZIONI_CATEGORIE[$codice];
    }
    return ucfirst(str_replace(['.', '-', '_'], ' ', $codice));
}

/**
 * Una sanzione e' attiva davvero? Non basta lo stato scritto: conta anche la scadenza.
 * Revocata = mai piu' attiva, qualunque cosa dica la data.
 */
function sanction_is_active(array $s): bool {
    if (($s['status'] ?? '') !== 'attiva') {
        return false;
    }
    if (empty($s['ends_at'])) {
        return true;   // permanente
    }
    return strtotime((string) $s['ends_at']) > time();
}

/** Stato da mostrare: 'attiva' | 'scaduta' | 'revocata'. */
function sanction_status(array $s): string {
    if (($s['status'] ?? '') === 'revocata') {
        return 'revocata';
    }
    return sanction_is_active($s) ? 'attiva' : 'scaduta';
}

/** Durata del provvedimento in forma leggibile: 'permanente', '7 giorni', '30 minuti'. */
function sanction_duration(array $s): string {
    if ($s['type'] === 'warn' || $s['type'] === 'kick') {
        return 'immediata';
    }
    if (empty($s['ends_at'])) {
        return 'permanente';
    }
    $secondi = strtotime((string) $s['ends_at']) - strtotime((string) $s['starts_at']);
    if ($secondi < 60) {
        return max(0, $secondi) . ' secondi';
    }
    $minuti = intdiv($secondi, 60);
    if ($minuti < 60) {
        return $minuti . ($minuti === 1 ? ' minuto' : ' minuti');
    }
    $ore = intdiv($minuti, 60);
    if ($ore < 24) {
        return $ore . ($ore === 1 ? ' ora' : ' ore');
    }
    $giorni = intdiv($ore, 24);
    if ($giorni < 31) {
        return $giorni . ($giorni === 1 ? ' giorno' : ' giorni');
    }
    $mesi = (int) round($giorni / 30);
    return $mesi . ($mesi === 1 ? ' mese' : ' mesi');
}

/**
 * Una durata in secondi detta in italiano: 1800 -> "30 minuti", 604800 -> "7 giorni".
 * La usa la coda del gestionale, dove la proposta ha una durata ma non ha ancora date.
 */
function duration_readable(?int $secondi): string {
    if ($secondi === null) {
        return 'permanente';
    }
    if ($secondi < 60) {
        return max(0, $secondi) . ' secondi';
    }
    $minuti = intdiv($secondi, 60);
    if ($minuti < 60) {
        return $minuti . ($minuti === 1 ? ' minuto' : ' minuti');
    }
    $ore = intdiv($minuti, 60);
    if ($ore < 24) {
        return $ore . ($ore === 1 ? ' ora' : ' ore');
    }
    $giorni = intdiv($ore, 24);
    return $giorni . ($giorni === 1 ? ' giorno' : ' giorni');
}

/**
 * Una durata scritta a mano -> secondi. Accetta 30m, 6h, 3d, 2w e "permanente".
 * Ritorna NULL per il permanente e 0 se non si capisce: chi chiama decide, ma qui non si
 * indovina mai una durata al posto di chi l'ha scritta.
 */
function duration_in_seconds(?string $testo): ?int {
    $s = strtolower(trim((string) $testo));
    if ($s === '' || $s === '0') {
        return 0;
    }
    if (in_array($s, ['permanente', 'perm', '-1', 'per sempre'], true)) {
        return null;
    }
    if (!preg_match_all('/(\d+)\s*([smhdwog])/', $s, $m, PREG_SET_ORDER)) {
        return ctype_digit($s) ? ((int) $s) * 60 : 0;   // un numero secco vale minuti
    }
    $unita = ['s' => 1, 'm' => 60, 'h' => 3600, 'o' => 3600, 'd' => 86400, 'g' => 86400, 'w' => 604800];
    $totale = 0;
    foreach ($m as $pezzo) {
        $totale += ((int) $pezzo[1]) * ($unita[$pezzo[2]] ?? 0);
    }
    return $totale;
}

/** La durata in forma compatta (3d, 6h, 30m): serve a precompilare il campo del gestionale. */
function duration_readable_short(?int $secondi): string {
    if ($secondi === null || $secondi <= 0) {
        return '';
    }
    if ($secondi % 604800 === 0) { return ($secondi / 604800) . 'w'; }
    if ($secondi % 86400 === 0)  { return ($secondi / 86400) . 'd'; }
    if ($secondi % 3600 === 0)   { return ($secondi / 3600) . 'h'; }
    if ($secondi % 60 === 0)     { return ($secondi / 60) . 'm'; }
    return $secondi . 's';
}

/** Quanto manca alla fine, o da quanto e' finita. Vuoto per i provvedimenti immediati. */
function sanction_expiry(array $s): string {
    if ($s['type'] === 'warn' || $s['type'] === 'kick') {
        return '';
    }
    if (sanction_status($s) === 'revocata') {
        return 'revocata' . (!empty($s['revoked_at']) ? ' il ' . date('d/m/Y', strtotime((string) $s['revoked_at'])) : '');
    }
    if (empty($s['ends_at'])) {
        return 'non scade';
    }
    $fine = strtotime((string) $s['ends_at']);
    if ($fine <= time()) {
        return 'finita il ' . date('d/m/Y', $fine);
    }
    return 'fino al ' . date('d/m/Y H:i', $fine);
}

/**
 * Chi ha deciso: il nome dello staff, oppure la dicitura per le automatiche.
 * Nell'elenco pubblico questo campo si vede, come da scelta di trasparenza.
 */
function sanction_author(array $s): string {
    if (!empty($s['staff_name'])) {
        return (string) $s['staff_name'];
    }
    return (int) ($s['automatic'] ?? 0) === 1 ? 'Sistema automatico' : 'Staff';
}

/**
 * Le sanzioni che pesano SUL SITO per un giocatore: solo quelle attive e con ambito
 * 'sito' o 'entrambi'. Ritorna ['ban' => riga|null, 'mute' => riga|null].
 *
 * E' la funzione che fa valere l'ambito: un ban di solo gioco non chiude il sito, e un
 * ban del sito non impedisce MAI di arrivare alla propria pagina di ricorso.
 */
function sanctions_site_block(?string $uuid): array {
    $vuoto = ['ban' => null, 'mute' => null];
    if (!$uuid || !sanctions_ready()) {
        return $vuoto;
    }
    $stmt = db()->prepare(
        "SELECT * FROM punishments
          WHERE mc_uuid = ? AND status = 'attiva' AND scope IN ('sito','entrambi')
            AND type IN ('ban','mute') AND (ends_at IS NULL OR ends_at > NOW())
          ORDER BY (ends_at IS NULL) DESC, ends_at DESC"
    );
    $stmt->execute([$uuid]);
    foreach ($stmt->fetchAll() as $riga) {
        $tipo = $riga['type'];
        if ($vuoto[$tipo] === null) {
            $vuoto[$tipo] = $riga;
        }
    }
    return $vuoto;
}

/** Comodita': true se l'utente non puo' scrivere sul sito (chat live, forum). */
function user_muted(?array $utente): bool {
    if (!$utente) {
        return false;
    }
    return sanctions_site_block($utente['mc_uuid'] ?? null)['mute'] !== null;
}

/** Il ricorso di una sanzione, se c'e'. */
function sanction_appeal(int $sanzioneId): ?array {
    if (!sanctions_ready()) {
        return null;
    }
    $stmt = db()->prepare('SELECT * FROM punishment_appeals WHERE punishment_id = ?');
    $stmt->execute([$sanzioneId]);
    return $stmt->fetch() ?: null;
}

/**
 * Revoca decisa dallo staff sul sito. revoke_applied = 0: in gioco il provvedimento c'e' ancora
 * finche' MagixGuard non lo legge (SiteSync) e lo toglie. True se la riga e' cambiata (era in
 * corso), false se era gia' revocata o non esiste.
 */
function sanction_revoke(int $id, string $staff, string $motivo): bool {
    $stmt = db()->prepare(
        "UPDATE punishments
            SET status = 'revocata', revoked_by = ?, revoked_at = NOW(),
                revoke_reason = ?, revoke_applied = 0
          WHERE id = ? AND status = 'attiva'"
    );
    $stmt->execute([$staff, mb_substr($motivo, 0, 255), $id]);
    return $stmt->rowCount() > 0;
}

/** True se esiste la tabella dello storico delle modifiche (migrazione 2026-10-03). */
function sanction_edits_ready(): bool {
    static $pronta = null;
    if ($pronta === null) {
        try {
            db()->query('SELECT 1 FROM punishment_edits LIMIT 1');
            $pronta = true;
        } catch (PDOException $e) {
            $pronta = false;
        }
    }
    return $pronta;
}

/**
 * Cambia motivo e/o durata di una sanzione IN CORSO, lasciando la traccia in punishment_edits.
 *
 * Basta scrivere sulla riga: MagixGuard legge le sanzioni dal database ogni volta che servono
 * (il ban all'ingresso, i mute a ogni giro di NetworkSync), quindi la nuova durata vale in gioco
 * da sola, su tutti i server, nel giro di pochi secondi.
 *
 * @param ?int $secondi durata nuova contata dall'INIZIO del provvedimento; null = permanente.
 *                      Ignorata per richiami ed espulsioni, che non hanno durata.
 * @param bool $cambiaDurata false = la durata resta com'e' (si cambia solo il motivo)
 * @return string|null  null se fatto, altrimenti il motivo per cui non si e' fatto
 */
function sanction_edit(array $s, string $staff, string $motivo, bool $cambiaDurata, ?int $secondi, string $nota): ?string {
    if (!sanction_edits_ready()) {
        return 'Manca la tabella dello storico delle modifiche: va lanciata la migrazione 2026-10-03-sanzioni-modifiche.sql.';
    }
    if (!sanction_is_active($s)) {
        return 'Si possono modificare solo i provvedimenti ancora in corso.';
    }
    $motivo = mb_substr(trim($motivo), 0, 255);
    $nota = mb_substr(trim($nota), 0, 255);
    if ($motivo === '' || $nota === '') {
        return 'Servono il motivo del provvedimento e la nota che spiega la modifica.';
    }
    $haDurata = in_array($s['type'], ['ban', 'mute'], true);
    $vecchiaFine = $s['ends_at'] ?: null;
    $nuovaFine = $vecchiaFine;
    if ($haDurata && $cambiaDurata) {
        $nuovaFine = $secondi === null
            ? null
            : date('Y-m-d H:i:s', strtotime((string) $s['starts_at']) + $secondi);
    }
    $durataCambiata = $nuovaFine !== $vecchiaFine;
    if (!$durataCambiata && $motivo === (string) $s['reason']) {
        return 'Non hai cambiato niente: né la durata né il motivo.';
    }

    $pdo = db();
    $pdo->beginTransaction();
    try {
        $upd = $pdo->prepare("UPDATE punishments SET reason = ?, ends_at = ? WHERE id = ? AND status = 'attiva'");
        $upd->execute([$motivo, $nuovaFine, (int) $s['id']]);
        if ($upd->rowCount() === 0) {   // revocata da qualcun altro nel frattempo
            $pdo->rollBack();
            return 'Il provvedimento non è più in corso.';
        }
        $pdo->prepare(
            'INSERT INTO punishment_edits
                (punishment_id, staff_name, old_ends_at, new_ends_at, old_reason, new_reason, duration_changed, note)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)'
        )->execute([(int) $s['id'], $staff, $vecchiaFine, $nuovaFine, (string) $s['reason'], $motivo,
                    $durataCambiata ? 1 : 0, $nota]);
        $pdo->commit();
    } catch (Throwable $e) {
        $pdo->rollBack();
        throw $e;
    }
    return null;
}

/** Lo storico delle modifiche di una sanzione, dalla piu' vecchia. [] se la tabella non c'e'. */
function sanction_edits(int $id): array {
    if (!sanction_edits_ready()) {
        return [];
    }
    $stmt = db()->prepare('SELECT * FROM punishment_edits WHERE punishment_id = ? ORDER BY id ASC');
    $stmt->execute([$id]);
    return $stmt->fetchAll();
}

/** La durata che avrebbe una sanzione con quella fine, detta come sanction_duration(). */
function sanction_duration_until(array $s, ?string $fine): string {
    return sanction_duration(['ends_at' => $fine] + $s);
}

/** Come si racconta lo stato di un ricorso nell'elenco pubblico. */
function ricorso_etichetta(?array $r): string {
    if (!$r) {
        return '';
    }
    return match ($r['status']) {
        'accolto'  => 'Ricorso accolto',
        'respinto' => 'Ricorso respinto',
        default    => 'Ricorso in esame',
    };
}

/**
 * Il blocco del regolamento generato dal plugin dalla sua configurazione.
 * NULL se il plugin non l'ha ancora scritto (server mai avviato con MagixGuard 0.2+).
 */
function rulebook_sanctions_block(): ?array {
    if (!sanctions_ready()) {
        return null;
    }
    try {
        $riga = db()->query('SELECT body_html, version, updated_at FROM punishment_rules WHERE id = 1')->fetch();
    } catch (PDOException $e) {
        return null;
    }
    return $riga ?: null;
}

/**
 * Il regolamento diviso in capitoli, per mostrarlo con lo stesso stile della guida: ogni <h2> del
 * testo scritto nel gestionale apre un capitolo, numerato in ordine; quello che sta prima del primo
 * <h2> e' l'introduzione. Numeri e indice li calcola la pagina: lo staff scrive solo i titoli.
 * Un testo senza <h2> (scritto alla vecchia, a righe) torna tutto come introduzione.
 */
function rulebook_chapters(string $html): array {
    $pezzi = preg_split('~(?=<h2\b)~i', $html) ?: [$html];
    $intro = '';
    $capitoli = [];
    foreach ($pezzi as $pezzo) {
        if (!preg_match('~^<h2[^>]*>(.*?)</h2>~is', $pezzo, $m)) {
            $intro .= $pezzo;
            continue;
        }
        $titolo = trim(html_entity_decode(strip_tags($m[1]), ENT_QUOTES, 'UTF-8'));
        $slug = @iconv('UTF-8', 'ASCII//TRANSLIT', $titolo) ?: $titolo;
        $slug = trim((string) preg_replace('~[^a-z0-9]+~', '-', strtolower($slug)), '-');
        $capitoli[] = [
            'id'     => 'regola-' . ($slug !== '' ? substr($slug, 0, 40) : count($capitoli) + 1),
            'numero' => count($capitoli) + 1,
            'titolo' => $titolo,
            'corpo'  => substr($pezzo, strlen($m[0])),
        ];
    }
    return ['intro' => $intro, 'capitoli' => $capitoli];
}

/**
 * Le aree in cui si divide la guida per amministratori, nell'ordine in cui compaiono. Ogni
 * plugin va in un'area sola; uno nuovo che non c'e' qui finisce in "Altri plugin" finche'
 * qualcuno non lo aggiunge: la guida resta completa anche senza toccare questo elenco.
 */
function guide_staff_areas(): array {
    return [
        'moderazione' => ['Moderazione e accessi', 'Sanzioni, account, password e sicurezza',
                          ['MagixGuard', 'MagixAuth']],
        'gioco'       => ['Il gioco', 'Fazioni, utilita\' del server, entita\', ora e cosmetici',
                          ['MagixFactions', 'MagixEssentials', 'MagixEntities', 'MagixTime', 'MagixMusic', 'MagixCosmetics']],
        'grafica'     => ['Grafica e interfaccia', 'Pacchetto risorse, menu e scoreboard',
                          ['MagixPack', 'MagixMenus', 'MagixScoreboard']],
        'rete'        => ['Rete, sito e lingue', 'Il ponte col sito, il proxy e le traduzioni',
                          ['MagixBridge', 'MagixProxy', 'MagixLanguage']],
        'altro'       => ['Altri plugin', 'Capitoli non ancora assegnati a un\'area', []],
    ];
}

/** L'area di un plugin (chiave di guide_staff_areas()). */
function guide_staff_area_of(string $plugin): string {
    foreach (guide_staff_areas() as $chiave => [, , $plugin_area]) {
        if (in_array($plugin, $plugin_area, true)) {
            return $chiave;
        }
    }
    return 'altro';
}

/** L'id di un sottocapitolo (<h4>) dentro il capitolo di un plugin: lo usano pagina e ricerca. */
function guide_staff_section_id(string $plugin, string $titolo): string {
    $t = html_entity_decode(strip_tags($titolo), ENT_QUOTES, 'UTF-8');
    $t = @iconv('UTF-8', 'ASCII//TRANSLIT', $t) ?: $t;
    $t = trim((string) preg_replace('~[^a-z0-9]+~', '-', strtolower($t)), '-');
    return 'guida-' . $plugin . '-' . substr($t, 0, 40);
}

/**
 * Un capitolo pronto da mostrare: nome breve e sottotitolo (il titolo e' "MagixGuard — sanzioni
 * e prove"), i sottocapitoli con il loro id, e il corpo con quegli id sugli <h4>.
 */
function guide_staff_prepare(array $c): array {
    $titolo = (string) $c['title'];
    $pezzi = preg_split('~\s+[—–-]\s+~u', $titolo, 2) ?: [$titolo];
    $sezioni = [];
    $corpo = preg_replace_callback('~<h4>(.*?)</h4>~is', function ($m) use ($c, &$sezioni) {
        $id = guide_staff_section_id((string) $c['plugin'], $m[1]);
        $sezioni[] = ['id' => $id, 'titolo' => html_entity_decode(strip_tags($m[1]), ENT_QUOTES, 'UTF-8')];
        return '<h4 id="' . htmlspecialchars($id, ENT_QUOTES) . '">' . $m[1] . '</h4>';
    }, (string) $c['body_html']) ?? (string) $c['body_html'];
    return $c + [
        'nome'       => $pezzi[0],
        'sottotitolo'=> $pezzi[1] ?? '',
        'sezioni'    => $sezioni,
        'corpo'      => $corpo,
        'area'       => guide_staff_area_of((string) $c['plugin']),
    ];
}

/** I capitoli della guida per amministratori, nell'ordine deciso dai plugin. */
function guide_staff_capitoli(): array {
    try {
        return db()->query('SELECT * FROM guide_staff ORDER BY sort_order ASC, plugin ASC')->fetchAll();
    } catch (PDOException $e) {
        return [];
    }
}
