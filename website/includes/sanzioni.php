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
function sanzioni_pronte(): bool {
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
function sanzione_tipo(string $tipo, string $campo = 'etichetta'): string {
    return SANZIONI_TIPI[$tipo][$campo] ?? ucfirst($tipo);
}

/** Colore del tipo, per il filo a sinistra della riga. */
function sanzione_colore(string $tipo): string {
    return SANZIONI_TIPI[$tipo]['colore'] ?? '#94959b';
}

/** Nome leggibile della categoria; se il plugin ne inventa una, si mostra ripulita. */
function sanzione_categoria(string $codice): string {
    if (isset(SANZIONI_CATEGORIE[$codice])) {
        return SANZIONI_CATEGORIE[$codice];
    }
    return ucfirst(str_replace(['.', '-', '_'], ' ', $codice));
}

/**
 * Una sanzione e' attiva davvero? Non basta lo stato scritto: conta anche la scadenza.
 * Revocata = mai piu' attiva, qualunque cosa dica la data.
 */
function sanzione_e_attiva(array $s): bool {
    if (($s['status'] ?? '') !== 'attiva') {
        return false;
    }
    if (empty($s['ends_at'])) {
        return true;   // permanente
    }
    return strtotime((string) $s['ends_at']) > time();
}

/** Stato da mostrare: 'attiva' | 'scaduta' | 'revocata'. */
function sanzione_stato(array $s): string {
    if (($s['status'] ?? '') === 'revocata') {
        return 'revocata';
    }
    return sanzione_e_attiva($s) ? 'attiva' : 'scaduta';
}

/** Durata del provvedimento in forma leggibile: 'permanente', '7 giorni', '30 minuti'. */
function sanzione_durata(array $s): string {
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
function durata_leggibile(?int $secondi): string {
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
function durata_in_secondi(?string $testo): ?int {
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
function durata_leggibile_breve(?int $secondi): string {
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
function sanzione_scadenza(array $s): string {
    if ($s['type'] === 'warn' || $s['type'] === 'kick') {
        return '';
    }
    if (sanzione_stato($s) === 'revocata') {
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
function sanzione_autore(array $s): string {
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
function sanzioni_blocco_sito(?string $uuid): array {
    $vuoto = ['ban' => null, 'mute' => null];
    if (!$uuid || !sanzioni_pronte()) {
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
function utente_silenziato(?array $utente): bool {
    if (!$utente) {
        return false;
    }
    return sanzioni_blocco_sito($utente['mc_uuid'] ?? null)['mute'] !== null;
}

/** Il ricorso di una sanzione, se c'e'. */
function sanzione_ricorso(int $sanzioneId): ?array {
    if (!sanzioni_pronte()) {
        return null;
    }
    $stmt = db()->prepare('SELECT * FROM punishment_appeals WHERE punishment_id = ?');
    $stmt->execute([$sanzioneId]);
    return $stmt->fetch() ?: null;
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
function regolamento_blocco_sanzioni(): ?array {
    if (!sanzioni_pronte()) {
        return null;
    }
    try {
        $riga = db()->query('SELECT body_html, version, updated_at FROM punishment_rules WHERE id = 1')->fetch();
    } catch (PDOException $e) {
        return null;
    }
    return $riga ?: null;
}

/** I capitoli della guida per amministratori, nell'ordine deciso dai plugin. */
function guide_staff_capitoli(): array {
    try {
        return db()->query('SELECT * FROM guide_staff ORDER BY sort_order ASC, plugin ASC')->fetchAll();
    } catch (PDOException $e) {
        return [];
    }
}
