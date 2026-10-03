<?php
/**
 * Chat vocale del sito (/voice): chi può entrare in quale stanza, e il gettone per entrarci.
 *
 * La voce la trasporta il server LiveKit sul VPS (servizio magix-voce, vedi
 * server-voce/livekit.yaml e predisponi-voce.yml). Quel server non sa niente dei giocatori:
 * accetta chiunque presenti un gettone firmato con la chiave segreta, e il gettone vale per UNA
 * stanza sola. Quindi tutte le regole stanno qui, nel momento in cui il sito lo firma:
 *
 *  - serve l'accesso al sito, cioè l'account di gioco (stesse credenziali);
 *  - l'identità nella stanza è l'UUID di gioco, il nome è quello di gioco: nessuno sceglie come
 *    chiamarsi;
 *  - un ban attivo (qualunque ambito) = niente gettone; un mute attivo = si entra ad ascoltare
 *    ma non si può parlare;
 *  - la stanza della fazione si apre solo a chi ne è membro adesso (database di MagixFactions).
 *
 * Il gettone dura pochi minuti: serve solo per entrare. Chi è dentro resta collegato (il
 * server della voce lo rinnova da sé), chi esce e rientra ne chiede uno nuovo e ripassa dalle
 * regole: un ban dato nel frattempo vale dal rientro.
 */

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/sanzioni.php';

/** Durata del gettone, in secondi: il tempo di collegarsi, non di restare. */
const VOICE_TOKEN_TTL = 600;

/** Indirizzo a cui si collega il browser: la segnalazione passa da nginx (vedi nginx-magicadventure.conf). */
function voice_url(): string {
    $base = defined('SITE_URL') ? SITE_URL : 'https://magicadventure.it';
    return preg_replace('#^http#', 'ws', rtrim($base, '/')) . '/voice-rtc';
}

/** True se il server della voce è configurato (chiavi scritte da predisponi-voce.yml). */
function voice_ready(): bool {
    return defined('VOICE_API_KEY') && defined('VOICE_API_SECRET')
        && VOICE_API_KEY !== '' && VOICE_API_SECRET !== '';
}

/**
 * La fazione di cui il giocatore è membro adesso, o null. Letta dal database di MagixFactions
 * (lo stesso che usano classifiche e profilo); se non è raggiungibile, semplicemente niente
 * stanza di fazione.
 */
function voice_player_faction(string $uuid): ?array {
    try {
        $q = db()->prepare(
            'SELECT f.id, f.name
               FROM factions_magixfactions.faction_members m
               JOIN factions_magixfactions.factions f ON f.id = m.faction_id
              WHERE m.uuid = ? COLLATE utf8mb4_unicode_ci
              LIMIT 1'
        );
        $q->execute([$uuid]);
        $riga = $q->fetch();
        return $riga ? ['id' => (int) $riga['id'], 'name' => (string) $riga['name']] : null;
    } catch (PDOException $e) {
        return null;
    }
}

/**
 * La modalità (faction, hub...) in cui il giocatore è in gioco adesso, o null se non è in gioco.
 * La scrive MagixBridge di ogni server in network_presence: una riga per giocatore, rinfrescata
 * ogni pochi secondi, quindi vale solo se recente.
 */
function voice_player_server(string $uuid): ?string {
    try {
        $q = db()->prepare(
            'SELECT server FROM network_presence
              WHERE mc_uuid = ? AND seen_at > NOW() - INTERVAL 60 SECOND
              LIMIT 1'
        );
        $q->execute([$uuid]);
        $server = $q->fetchColumn();
        return is_string($server) && preg_match('/^[a-z0-9]{1,32}$/', $server) ? $server : null;
    } catch (PDOException $e) {
        return null;
    }
}

/**
 * Le stanze in cui il giocatore può entrare, nell'ordine in cui la pagina le mostra.
 * Ogni stanza: id (il nome della stanza sul server della voce), name, desc.
 */
function voice_rooms(array $utente): array {
    $stanze = [];
    // Prossimità: solo per la modalità in cui il giocatore è in gioco adesso. La stanza si chiama
    // near-<server> come la apre MagixBridge di quel server (voice/ProximityVoice), che manda a
    // ognuno volume e lato dei vicini.
    $server = voice_player_server((string) $utente['mc_uuid']);
    if ($server !== null) {
        $etichetta = defined('GAME_SERVERS') && isset(GAME_SERVERS[$server]) ? GAME_SERVERS[$server]['label'] : ucfirst($server);
        $stanze[] = [
            'id'   => 'near-' . $server,
            'name' => 'Vicini in ' . $etichetta,
            'desc' => 'Senti chi ti sta vicino in gioco: più è lontano, meno si sente, e dal lato in cui sta.',
        ];
    }
    $stanze[] = [
        'id'   => 'network',
        'name' => 'Tutta la rete',
        'desc' => 'La stanza di tutti: chiunque sia in gioco, su qualunque modalità.',
    ];
    $fazione = voice_player_faction((string) $utente['mc_uuid']);
    if ($fazione !== null) {
        $stanze[] = [
            'id'   => 'faction-' . $fazione['id'],
            'name' => 'Fazione ' . $fazione['name'],
            'desc' => 'Solo i membri della tua fazione.',
        ];
    }
    return $stanze;
}

/**
 * Ban e mute attivi del giocatore, in qualunque ambito (gioco, sito o entrambi): la voce è
 * un pezzo di gioco che passa dal sito, e un ban di gioco deve valere anche qui.
 * Ritorna ['ban' => riga|null, 'mute' => riga|null].
 */
function voice_sanctions(string $uuid): array {
    $esito = ['ban' => null, 'mute' => null];
    if (!sanctions_ready()) {
        return $esito;
    }
    $q = db()->prepare(
        "SELECT * FROM punishments
          WHERE mc_uuid = ? AND status = 'attiva' AND type IN ('ban','mute')
            AND (ends_at IS NULL OR ends_at > NOW())
          ORDER BY (ends_at IS NULL) DESC, ends_at DESC"
    );
    $q->execute([$uuid]);
    foreach ($q->fetchAll() as $riga) {
        if ($esito[$riga['type']] === null) {
            $esito[$riga['type']] = $riga;
        }
    }
    return $esito;
}

function voice_base64url(string $dati): string {
    return rtrim(strtr(base64_encode($dati), '+/', '-_'), '=');
}

/**
 * Il gettone (JWT firmato HS256 con il segreto del server della voce) per entrare in $stanza.
 * $puoParlare = false: entra ad ascoltare, il server rifiuta qualunque microfono.
 * $extra finisce nei metadati del partecipante, che vedono gli altri (es. l'immagine della testa).
 */
function voice_token(array $utente, string $stanza, bool $puoParlare, array $extra = []): string {
    $ora = time();
    $intestazione = ['alg' => 'HS256', 'typ' => 'JWT'];
    $dati = [
        'iss'      => VOICE_API_KEY,
        'sub'      => (string) $utente['mc_uuid'],
        'name'     => (string) $utente['mc_username'],
        'nbf'      => $ora - 10,
        'exp'      => $ora + VOICE_TOKEN_TTL,
        'jti'      => bin2hex(random_bytes(8)),
        'metadata' => json_encode($extra, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        'video'    => [
            'room'           => $stanza,
            'roomJoin'       => true,
            'canSubscribe'   => true,
            'canPublish'     => $puoParlare,
            // Solo il microfono: niente video, schermo o dati inviati dai browser.
            'canPublishSources' => $puoParlare ? ['microphone'] : [],
            'canPublishData' => false,
            'canUpdateOwnMetadata' => false,
        ],
    ];
    $corpo = voice_base64url(json_encode($intestazione)) . '.'
        . voice_base64url(json_encode($dati, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
    return $corpo . '.' . voice_base64url(hash_hmac('sha256', $corpo, VOICE_API_SECRET, true));
}

// ---------------------------------------------------------------------------------------------
// Chi c'è nelle stanze adesso (riquadro della colonna laterale, schede di /voce).
//
// Lo dice il server della voce stesso, tramite le sue API di amministrazione: raggiungibili SOLO
// da dentro il VPS (127.0.0.1:7880, nginx inoltra solo /rtc), con un gettone firmato col segreto.
// La risposta resta in memoria pochi secondi in un file temporaneo: la colonna compare su molte
// pagine, e non serve chiedere al server della voce a ogni visita. Se il server non risponde, le
// stanze risultano vuote: la pagina non si rompe mai per colpa della voce.
// ---------------------------------------------------------------------------------------------

/** Secondi di memoria per l'elenco delle stanze e delle persone. */
const VOICE_CACHE_TTL = 10;

/** Un gettone di amministrazione (solo per le chiamate dal sito al server della voce). */
function voice_admin_token(array $permessi): string {
    $ora = time();
    $corpo = voice_base64url(json_encode(['alg' => 'HS256', 'typ' => 'JWT'])) . '.'
        . voice_base64url(json_encode([
            'iss'   => VOICE_API_KEY,
            'sub'   => 'sito',
            'nbf'   => $ora - 10,
            'exp'   => $ora + 60,
            'video' => $permessi,
        ]));
    return $corpo . '.' . voice_base64url(hash_hmac('sha256', $corpo, VOICE_API_SECRET, true));
}

/** Una chiamata alle API del server della voce. null se non risponde o risponde male. */
function voice_api_call(string $metodo, array $dati, array $permessi): ?array {
    if (!voice_ready()) {
        return null;
    }
    $contesto = stream_context_create(['http' => [
        'method'        => 'POST',
        'header'        => "Content-Type: application/json\r\nAuthorization: Bearer " . voice_admin_token($permessi) . "\r\n",
        'content'       => json_encode((object) $dati),
        'timeout'       => 1.5,
        'ignore_errors' => true,
    ]]);
    $testo = @file_get_contents('http://127.0.0.1:7880/twirp/livekit.RoomService/' . $metodo, false, $contesto);
    $codice = 0;
    // PHP 8.4+: http_get_last_response_headers(); la variabile magica e' deprecata dalla 8.5.
    $intestazioni = function_exists('http_get_last_response_headers')
        ? (http_get_last_response_headers() ?? [])
        : ($http_response_header ?? []);
    foreach ($intestazioni as $riga) {
        if (preg_match('#^HTTP/\S+\s+(\d+)#', $riga, $m)) {
            $codice = (int) $m[1];
        }
    }
    if ($testo === false || $codice !== 200) {
        return null;
    }
    $json = json_decode($testo, true);
    return is_array($json) ? $json : null;
}

/** $calcola() tenuto in memoria per VOICE_CACHE_TTL secondi (file temporaneo, scrittura atomica). */
function voice_cached(string $chiave, callable $calcola) {
    $file = sys_get_temp_dir() . '/magix-voce-' . md5($chiave) . '.json';
    $st = @stat($file);
    if ($st && time() - $st['mtime'] < VOICE_CACHE_TTL) {
        $dati = json_decode((string) @file_get_contents($file), true);
        if (is_array($dati)) {
            return $dati['v'];
        }
    }
    $valore = $calcola();
    $tmp = $file . '.' . bin2hex(random_bytes(4));
    if (@file_put_contents($tmp, json_encode(['v' => $valore])) !== false) {
        @rename($tmp, $file);
    }
    return $valore;
}

/** Stanza -> quante persone ci sono adesso (solo le stanze con qualcuno dentro). */
function voice_room_counts(): array {
    return voice_cached('stanze', function () {
        $r = voice_api_call('ListRooms', [], ['roomList' => true]);
        $conti = [];
        foreach ($r['rooms'] ?? [] as $stanza) {
            $n = (int) ($stanza['num_participants'] ?? $stanza['numParticipants'] ?? 0);
            if ($n > 0 && isset($stanza['name'])) {
                $conti[(string) $stanza['name']] = $n;
            }
        }
        return $conti;
    });
}

/** Chi c'è adesso in $stanza: [['name', 'avatar']], in ordine di nome. */
function voice_room_people(string $stanza): array {
    if (!isset(voice_room_counts()[$stanza])) {
        return [];
    }
    return voice_cached('persone:' . $stanza, function () use ($stanza) {
        $r = voice_api_call('ListParticipants', ['room' => $stanza], ['roomAdmin' => true, 'room' => $stanza]);
        $persone = [];
        foreach ($r['participants'] ?? [] as $p) {
            $meta = json_decode((string) ($p['metadata'] ?? ''), true);
            $avatar = is_array($meta) && is_string($meta['avatar'] ?? null)
                && str_starts_with($meta['avatar'], 'https://minotar.net/') ? $meta['avatar'] : '';
            $persone[] = [
                'name'   => (string) ($p['name'] ?? $p['identity'] ?? ''),
                'avatar' => $avatar,
            ];
        }
        usort($persone, fn ($a, $b) => strcasecmp($a['name'], $b['name']));
        return $persone;
    });
}

/**
 * Tutto quello che serve al riquadro della colonna laterale, per chi guarda ($utente può essere
 * null). I nomi si vedono solo per le stanze in cui chi guarda potrebbe entrare: la stanza di
 * tutti e quella della sua fazione. Delle altre fazioni solo il totale.
 */
function voice_overview(?array $utente): array {
    $conti = voice_room_counts();
    $stanze = $utente ? voice_rooms($utente) : [[
        'id' => 'network', 'name' => 'Tutta la rete', 'desc' => '',
    ]];
    $mie = [];
    foreach ($stanze as $s) {
        $s['count'] = $conti[$s['id']] ?? 0;
        $s['people'] = $s['count'] > 0 ? voice_room_people($s['id']) : [];
        $mie[$s['id']] = $s;
    }
    $altreFazioni = 0;
    $altreStanze = 0;
    foreach ($conti as $id => $n) {
        if (str_starts_with($id, 'faction-') && !isset($mie[$id])) {
            $altreFazioni += $n;
            $altreStanze++;
        }
    }
    return [
        'rooms'         => array_values($mie),
        'total'         => array_sum($conti),
        'other_people'  => $altreFazioni,
        'other_rooms'   => $altreStanze,
    ];
}

/** L'icona di una stanza: la mappa per i vicini, lo scudo per la fazione, le persone per la rete. */
function voice_room_icon(string $stanza): string {
    if (str_starts_with($stanza, 'near-')) {
        return 'map';
    }
    return str_starts_with($stanza, 'faction-') ? 'shield' : 'users';
}
