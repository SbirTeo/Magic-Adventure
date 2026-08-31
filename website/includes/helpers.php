<?php
require_once __DIR__ . '/db.php';

function h(?string $s): string {
    return htmlspecialchars($s ?? '', ENT_QUOTES, 'UTF-8');
}

function slugify(string $text): string {
    $text = trim($text);
    $text = iconv('UTF-8', 'ASCII//TRANSLIT//IGNORE', $text) ?: $text;
    $text = strtolower($text);
    $text = preg_replace('/[^a-z0-9]+/', '-', $text);
    $text = trim($text, '-');
    if ($text === '') {
        $text = 'n-' . bin2hex(random_bytes(3));
    }
    return $text;
}

function csrf_token(): string {
    if (empty($_SESSION['csrf'])) {
        $_SESSION['csrf'] = bin2hex(random_bytes(32));
    }
    return $_SESSION['csrf'];
}

function csrf_field(): string {
    return '<input type="hidden" name="csrf" value="' . h(csrf_token()) . '">';
}

function csrf_check(): void {
    $sent = $_POST['csrf'] ?? '';
    if (empty($_SESSION['csrf']) || !hash_equals($_SESSION['csrf'], $sent)) {
        http_response_code(400);
        die('Richiesta non valida (CSRF). Torna indietro e riprova.');
    }
}

function redirect(string $path): void {
    header('Location: ' . $path);
    exit;
}

/**
 * Ritratti dei giocatori (testa e figura intera). Il servizio sta scritto in un posto SOLO
 * perche' cambiarlo e' gia' servito: mc-heads.net continuava a servire la skin di Steve per
 * profili premium con la skin aggiornata da tempo (cache sua, non aggirabile dal sito),
 * minotar.net legge la texture giusta dal profilo Mojang.
 *
 * L'UUID va passato SENZA trattini: con i trattini minotar risponde 301 e ogni immagine
 * costerebbe un viaggio in piu'.
 */
function mc_avatar_url(?string $uuid, int $size = 64): string {
    return 'https://minotar.net/helm/' . rawurlencode(str_replace('-', '', (string) $uuid)) . '/' . $size;
}

/** Figura intera con i livelli esterni della skin (cappello, giacca...). */
function mc_body_url(?string $uuid, int $size = 160): string {
    return 'https://minotar.net/armor/body/' . rawurlencode(str_replace('-', '', (string) $uuid)) . '/' . $size;
}

/**
 * La TEXTURE grezza della skin (il PNG 64x64 di Minecraft), non un ritratto gia' composto.
 * Serve a chi si costruisce il personaggio pezzo per pezzo ritagliando la texture: la
 * visuale 3D del profilo e il corridore del conto alla rovescia in home.
 *
 * Senza UUID torna la skin predefinita: minotar serve Steve per i nomi che non conosce.
 */
function mc_skin_url(?string $uuid): string {
    $id = str_replace('-', '', (string) $uuid);
    return 'https://minotar.net/skin/' . rawurlencode($id !== '' ? $id : 'Steve');
}

function site_settings(): array {
    static $cache = null;
    if ($cache === null) {
        $cache = [];
        $rows = db()->query('SELECT setting_key, setting_value FROM site_settings')->fetchAll();
        foreach ($rows as $row) {
            $cache[$row['setting_key']] = $row['setting_value'];
        }
    }
    return $cache;
}

function site_setting(string $key, string $default = ''): string {
    $s = site_settings();
    return $s[$key] ?? $default;
}

/**
 * Spiegazione dei due cursori del velo (intensita' e altezza della sfumatura).
 * Sta qui, in un posto solo, perche' il velo del blog e quello delle card dello store
 * funzionano allo stesso modo: con il testo copiato in quattro moduli diversi tornerebbe
 * a divergere alla prima modifica, ed e' gia' successo.
 */
function aiuto_velo(string $quale): string {
    if ($quale === 'intensita') {
        return 'Quanto copre il velo <strong>nel punto in cui arriva a pieno</strong>: 0% = copertina '
             . 'intatta, 100% = colore pieno. Da solo non copre tutta la tessera: quanta ne copre lo '
             . 'decide l\'altezza qui sotto.';
    }
    return 'Dove il velo arriva a colore pieno, partendo dal lato scelto come direzione. '
         . '<strong>Più il numero è basso, più tessera viene coperta:</strong> a 30% il colore è già '
         . 'pieno a un terzo e tutto il resto è tinta unita; a 100% è pieno solo sul bordo e la '
         . 'copertina resta quasi intatta. Si legge al contrario dell\'intensità: '
         . '<em>intensità alta + altezza bassa = velo massimo</em>.';
}

/**
 * Avviso da mettere sotto all'interruttore del velo, quando quel velo e' SPENTO.
 *
 * Serve a chi apre il modulo per cambiare colori e intensita' senza accorgersi che la
 * casella qui sopra e' disattivata: senza questa riga si regola a lungo qualcosa che sul
 * sito non si vede, ed e' il primo posto in cui si va a cercare un guasto che non c'e'.
 * Con il velo acceso non restituisce niente: la casella spuntata parla gia' da sola.
 *
 * $dove: 'blog' (tessere degli articoli in home) oppure 'store' (card dei pacchetti).
 */
function nota_interruttore_veli(string $dove): string {
    $chiave = $dove === 'store' ? 'card_overlay_store' : 'card_overlay_blog';
    if (site_setting($chiave, '1') === '1') {
        return '';
    }
    $cosa = $dove === 'store' ? 'delle card dello store' : 'delle tessere degli articoli';
    return '<div class="alert alert-error" style="margin:0 0 14px;"><strong>Il velo ' . $cosa
         . ' &egrave; spento.</strong> La casella qui sopra &egrave; disattivata: le copertine si vedono '
         . 'pulite e ai testi arriva un&rsquo;ombra al posto della sfumatura, quindi <strong>colori, '
         . 'intensit&agrave; e altezza qui sotto non hanno effetto</strong> finch&eacute; non la rimetti.</div>';
}

/**
 * Testo introduttivo della pagina /tutorial, modificabile dal gestionale
 * (Aspetto → Guida del server). Vuoto = si usa quello predefinito, cosi' la pagina
 * non resta mai senza spiegazione.
 */
function guida_intro(): string {
    $predefinito = "Tutto quello che serve per cominciare: fazioni, potenza, territori, mappa e "
        . "l'elenco completo dei comandi. È la stessa guida che trovi in gioco, quindi resta "
        . "sempre allineata a com'è il server adesso.";
    $testo = trim(site_setting('tutorial_intro', ''));
    return $testo !== '' ? $testo : $predefinito;
}

/**
 * Costruisce l'INTERA tavolozza di un tema partendo da tre soli colori: fondo della pagina,
 * fondo dei pannelli e colore del testo. Tutto il resto (bordi, fondi intermedi, testi
 * tenui, veli) si ricava da questi, quindi cambiando i tre di base il tema resta coerente.
 *
 * I due testi tenui passano da colore_leggibile(): vengono spinti finche' non superano la
 * soglia di contrasto sul fondo scelto, cosi' nessuna combinazione produce testo illeggibile.
 * Funziona uguale per il tema chiaro e per quello scuro: la direzione la capisce da sola
 * guardando quanto e' luminoso il fondo.
 *
 * @return string dichiarazioni CSS pronte da mettere dentro una regola
 */
function tavolozza_tema(string $sfondo, string $pannello, string $testo): string {
    $righe = [
        '--bg: ' . $sfondo,
        '--bg-panel: ' . $pannello,
        // Fondi intermedi: il pannello tirato di poco verso il colore del testo
        '--bg-panel-2: ' . hex_mix($testo, $pannello, 0.05),
        '--bg-elevated: ' . hex_mix($testo, $pannello, 0.10),
        '--border: ' . hex_mix($testo, $sfondo, 0.14),
        '--border-strong: ' . hex_mix($testo, $sfondo, 0.32),
        '--text: ' . $testo,
        '--text-dim: ' . colore_leggibile(hex_mix($testo, $sfondo, 0.66), $sfondo),
        '--text-dimmer: ' . colore_leggibile(hex_mix($testo, $sfondo, 0.5), $sfondo),
        '--bg-testata: ' . hex_to_rgba($pannello, 0.9),
        // Coppia "contrasto massimo" (pulsante d'invio della chat, pagina corrente)
        '--contrasto: ' . $testo,
        '--contrasto-testo: ' . $pannello,
        '--bordo-fuoco: ' . hex_to_rgba($testo, 0.45),
        '--alone-fuoco: ' . hex_to_rgba($testo, 0.08),
        '--velo-scuro: ' . hex_to_rgba($sfondo, 0.6),
        '--riga-chiara: ' . hex_to_rgba($testo, 0.1),
    ];
    return implode(";\n  ", $righe) . ';';
}

/**
 * Tema scelto da chi sta guardando: 'scuro', 'chiaro' oppure 'auto' (segue le preferenze
 * del browser/sistema). La scelta del visitatore (cookie) vince su quella del gestionale;
 * qualsiasi valore strano ricade sul predefinito del sito.
 */
function tema_scelto(): string {
    $validi = ['scuro', 'chiaro', 'auto'];
    $predefinito = site_setting('tema_predefinito', 'scuro');
    if (!in_array($predefinito, $validi, true)) {
        $predefinito = 'scuro';
    }
    $cookie = $_COOKIE['tema'] ?? '';
    return in_array($cookie, $validi, true) ? $cookie : $predefinito;
}

function is_valid_hex_color(string $s): bool {
    return (bool) preg_match('/^#[0-9a-fA-F]{6}$/', $s);
}

function hex_to_rgba(string $hex, float $alpha): string {
    if (!is_valid_hex_color($hex)) {
        return "rgba(0,0,0,$alpha)";
    }
    $hex = ltrim($hex, '#');
    $r = hexdec(substr($hex, 0, 2));
    $g = hexdec(substr($hex, 2, 2));
    $b = hexdec(substr($hex, 4, 2));
    return "rgba($r,$g,$b,$alpha)";
}

// $amount: -1 (nero) .. 0 (invariato) .. 1 (bianco)
function hex_shade(string $hex, float $amount): string {
    if (!is_valid_hex_color($hex)) {
        return $hex;
    }
    $hex = ltrim($hex, '#');
    $r = hexdec(substr($hex, 0, 2));
    $g = hexdec(substr($hex, 2, 2));
    $b = hexdec(substr($hex, 4, 2));
    $target = $amount >= 0 ? 255 : 0;
    $amount = abs($amount);
    $r = (int) round($r + ($target - $r) * $amount);
    $g = (int) round($g + ($target - $g) * $amount);
    $b = (int) round($b + ($target - $b) * $amount);
    return sprintf('#%02x%02x%02x', $r, $g, $b);
}

function mc_write_varint(int $value): string {
    $bytes = '';
    do {
        $byte = $value & 0x7F;
        $value >>= 7;
        if ($value !== 0) {
            $byte |= 0x80;
        }
        $bytes .= chr($byte);
    } while ($value !== 0);
    return $bytes;
}

function mc_read_varint($socket): ?int {
    $result = 0;
    $shift = 0;
    for ($i = 0; $i < 5; $i++) {
        $byte = fread($socket, 1);
        if ($byte === '' || $byte === false) return null;
        $b = ord($byte);
        $result |= ($b & 0x7F) << $shift;
        if (($b & 0x80) === 0) {
            return $result;
        }
        $shift += 7;
    }
    return null;
}

/**
 * Interroga il server Minecraft via Server List Ping (protocollo reale, non un finto stato).
 * Ritorna null se il server non risponde o non è raggiungibile entro il timeout.
 */
function mc_server_status(string $host = 'mc.magicadventure.it', int $port = 25565, float $timeout = 1.5): ?array {
    $sock = @fsockopen($host, $port, $errno, $errstr, $timeout);
    if (!$sock) {
        return null;
    }
    stream_set_timeout($sock, (int) ceil($timeout));

    $handshakeData = mc_write_varint(0)
        . mc_write_varint(760)
        . mc_write_varint(strlen($host)) . $host
        . pack('n', $port)
        . mc_write_varint(1);
    fwrite($sock, mc_write_varint(strlen($handshakeData)) . $handshakeData);

    $requestData = mc_write_varint(0);
    fwrite($sock, mc_write_varint(strlen($requestData)) . $requestData);

    $len = mc_read_varint($sock);
    if ($len === null) { fclose($sock); return null; }
    mc_read_varint($sock); // packet id, non usato
    $strLen = mc_read_varint($sock);
    if ($strLen === null || $strLen <= 0 || $strLen > 200000) { fclose($sock); return null; }

    $json = '';
    while (strlen($json) < $strLen) {
        $chunk = fread($sock, $strLen - strlen($json));
        if ($chunk === '' || $chunk === false) break;
        $json .= $chunk;
    }
    fclose($sock);

    $decoded = json_decode($json, true);
    if (!is_array($decoded)) {
        return null;
    }
    return [
        'online' => true,
        'players_online' => $decoded['players']['online'] ?? null,
        'players_max' => $decoded['players']['max'] ?? null,
    ];
}

/**
 * Grado in gioco (LuckPerms) -> tag sul sito.
 *
 * I dati arrivano dalla tabella `mc_ranks`, scritta dal plugin MagixWeb a ogni join
 * (e ogni 5 minuti per chi e' online): gruppo primario + testo/colore del prefisso.
 * Il colore mostrato qui e' quindi ESATTAMENTE quello del prefisso in chat
 * (es. prefisso "&cAdmin" -> testo "Admin", colore #FF5555).
 *
 * Da usare nelle query insieme a RANK_JOIN_SQL / RANK_SELECT_SQL.
 */
const RANK_SELECT_SQL = 'r.group_name, r.group_display, r.tag_text, r.tag_color, r.tags_json, r.name_color';

/**
 * `users`.mc_uuid e `mc_ranks`.mc_uuid sono state create in momenti diversi: il COLLATE
 * esplicito evita "Illegal mix of collations" se le due collation non combaciano.
 */
function rank_join_sql(string $userAlias = 'u', string $rankAlias = 'r'): string {
    return " LEFT JOIN mc_ranks {$rankAlias} ON {$rankAlias}.mc_uuid = {$userAlias}.mc_uuid COLLATE utf8mb4_unicode_ci ";
}

/**
 * Risolve i gradi di una riga (colonne di RANK_SELECT_SQL, piu' eventuale is_admin) in una
 * lista di ['label' => 'Admin', 'color' => '#FF5555'] — vuota se non va mostrato niente.
 *
 * Sono PIU' di uno quando il prefisso in gioco ne impila diversi (in LuckPerms:
 * `meta-formatting.prefix.format` = piu' alto della track staff + piu' alto fuori track,
 * quindi es. "Admin" + "VIP"). L'ordine e' quello del prefisso in chat.
 */
function player_ranks(?array $row): array {
    if (!$row) {
        return [];
    }

    $tags = json_decode((string) ($row['tags_json'] ?? ''), true);
    if (is_array($tags) && $tags) {
        $out = [];
        foreach ($tags as $t) {
            $label = trim((string) ($t['text'] ?? ''));
            if ($label === '') {
                continue;
            }
            $color = (string) ($t['color'] ?? '');
            $out[] = [
                'label' => $label,
                'color' => is_valid_hex_color($color) ? $color : '#AAAAAA',
            ];
        }
        if ($out) {
            return $out;
        }
    }

    // Riga sincronizzata da una versione precedente del plugin (senza tags_json), o nessuna riga.
    $group = $row['group_name'] ?? null;
    $label = trim((string) ($row['tag_text'] ?? ''));
    $color = (string) ($row['tag_color'] ?? '');

    if ($label === '') {
        if ($group === null) {
            // Nessun dato sincronizzato: il giocatore non e' ancora entrato in partita da quando
            // esistono i tag. Nessun tag, punto: il ruolo web-admin NON e' un grado di gioco e non
            // deve mai comparire qui (altrimenti un web-admin sembrerebbe Admin anche in gioco).
            return [];
        } elseif ($group === 'default') {
            // Gruppo base senza prefisso: in gioco non ha nessun tag, e nemmeno qui.
            return [];
        } else {
            $label = (string) ($row['group_display'] ?? $group);
            $label = mb_convert_case($label, MB_CASE_TITLE, 'UTF-8');
        }
    }

    if (!is_valid_hex_color($color)) {
        $color = '#AAAAAA'; // grigio "vanilla" (&7), usato quando il prefisso non dichiara colori
    }

    return [['label' => $label, 'color' => $color]];
}

/** Solo i tag colorati, nell'ordine del prefisso (stringa vuota se il giocatore non ne ha). */
function player_tag(?array $row): string {
    $out = '';
    foreach (player_ranks($row) as $rank) {
        // Il testo del tag sta su un fondo tinto al 14%: sul tema chiaro quel fondo e' quasi
        // bianco ma non del tutto, quindi il contrasto si calcola sulla tinta VERA (vedi
        // hex_mix), altrimenti l'etichetta resta appena sotto la soglia di leggibilita'.
        $fondoTag = hex_mix($rank['color'], '#ffffff', 0.14);
        $out .= '<span class="player-tag" style="--tag-color:' . $rank['color']
            . ';--tag-color-chiaro:' . h(colore_leggibile($rank['color'], $fondoTag))
            . ';--tag-bg:' . hex_to_rgba($rank['color'], 0.14)
            . ';--tag-border:' . hex_to_rgba($rank['color'], 0.45) . '">'
            . h($rank['label']) . '</span>';
    }
    return $out;
}

/** Entro quanti minuti dall'ultima pagina aperta uno si considera ancora "sul sito". */
const PRESENZA_MINUTI = 5;

/** Vero se la riga porta una `last_seen` recente (serve la colonna nella SELECT). */
function e_sul_sito(?array $row): bool {
    $visto = $row['last_seen'] ?? null;
    return $visto !== null && strtotime((string) $visto) >= time() - PRESENZA_MINUTI * 60;
}

/**
 * Pallino verde "e' sul sito adesso", da mettere accanto al nome di QUALCUN ALTRO.
 *
 * Sul proprio nome non compare mai: che tu sia collegato lo sai gia', e nella barra in alto
 * dava solo fastidio. Il confronto e' sul nome utente, che e' unico, perche' le righe che
 * arrivano qui non portano tutte l'id dell'utente.
 * Stringa vuota se la riga non ha `last_seen` (query che non la seleziona) o se e' vecchia.
 */
function presenza_dot(?array $row, ?string $username = null): string {
    if (!e_sul_sito($row)) {
        return '';
    }
    if ($username !== null && function_exists('current_user')) {
        $io = current_user();
        if ($io && strcasecmp((string) $io['mc_username'], $username) === 0) {
            return '';
        }
    }
    return '<span class="presenza" title="Sul sito ora"></span>';
}

/**
 * Chi e' collegato AL SITO adesso: utenti che hanno aperto una pagina da poco
 * (`users.last_seen`, che current_user aggiorna al massimo una volta al minuto).
 *
 * Da non confondere con chi e' in partita: quello lo dice mc_server_status().
 * Il piu' recente per primo.
 */
function utenti_sul_sito(int $minuti = 5, int $max = 100): array {
    // I due numeri entrano nella query gia' ridotti a interi in un intervallo sensato:
    // MariaDB non accetta un parametro dentro INTERVAL ne' dentro LIMIT.
    $minuti = max(1, min(1440, $minuti));
    $max = max(1, min(500, $max));

    try {
        return db()->query(
            'SELECT u.id, u.mc_uuid, u.mc_username, u.last_seen, r.weight, ' . RANK_SELECT_SQL
            . ' FROM users u' . rank_join_sql()
            . " WHERE u.last_seen >= DATE_SUB(NOW(), INTERVAL {$minuti} MINUTE)"
            // Prima il grado piu' pesante (com'e' in gioco: lo staff in cima), poi chi si e'
            // fatto vedere da meno tempo. Il peso puo' mancare per chi non e' mai entrato
            // in partita: COALESCE lo tratta come zero invece di buttarlo in fondo a caso.
            . " ORDER BY COALESCE(r.weight, 0) DESC, u.last_seen DESC LIMIT {$max}"
        )->fetchAll();
    } catch (PDOException $e) {
        return []; // colonna last_seen non ancora creata: il modulo non compare, punto
    }
}

/**
 * Colore del grado piu' importante: `name_color` (il grado di PESO PIU' ALTO, calcolato dal
 * plugin su tutti i gruppi, track o no) e, se manca, quello del primo grado del prefisso.
 * null quando il giocatore non ha nessun grado da mostrare.
 *
 * Serve dove il nome va colorato SENZA i tag accanto (es. la barra di navigazione).
 */
function player_name_color(?array $row): ?string {
    $ranks = player_ranks($row);
    if (!$ranks) {
        return null;
    }
    $color = (string) ($row['name_color'] ?? '');
    return is_valid_hex_color($color) ? $color : $ranks[0]['color'];
}

/**
 * Giocatore come si vede in chat: PRIMA i tag dei gradi, poi il nome, col colore del grado
 * piu' importante (vedi player_name_color). Senza gradi resta il solo nome, non colorato.
 *
 * Se la riga porta `last_seen` (basta selezionarla nella query) davanti al nome compare il
 * pallino di presenza: cosi' forum, firme degli articoli e gestionale l'hanno preso tutti
 * insieme, senza toccare i template uno per uno.
 */
function player_name(?array $row, ?string $username): string {
    $name = h((string) $username);
    $pallino = presenza_dot($row, $username);
    $color = player_name_color($row);
    if ($color === null) {
        return $pallino . $name;
    }
    return player_tag($row) . $pallino
        . '<span class="player-rank-name colore-grado" style="' . stile_colore_grado($color) . '">'
        . $name . '</span>';
}

/**
 * Le due versioni di un colore che arriva dal gioco, come dichiarazioni CSS: quella
 * originale (tema scuro) e quella scurita quanto basta per leggersi sul tema chiaro.
 * Quale delle due si veda lo decide il foglio di stile (classe .colore-grado), non il
 * server: cosi' funziona anche col tema "auto", che il server non puo' conoscere.
 */
function stile_colore_grado(string $colore): string {
    return '--c:' . h($colore) . ';--c-chiaro:' . h(colore_leggibile($colore, '#ffffff'));
}

/**
 * Colori delle relazioni fra fazioni, gli stessi di MagixFactions (`relations.colors`:
 * &a membro / &d alleato / &c nemico) resi con gli hex VERI del client Minecraft, come
 * gia' si fa per i colori dei gradi.
 *
 * `none` (chi non ha nessuna fazione) e' ROSSO come `enemy`, non bianco: scelta esplicita
 * dell'utente — chi e' senza fazione va visto come nemico da tutti. Se cambia in gioco
 * (`relations.colors.none` nel config di MagixFactions) va cambiato anche qui.
 */
const FACTION_REL_COLORS = [
    'member' => '#55FF55',
    'ally'   => '#FF55FF',
    'enemy'  => '#FF5555',
    'none'   => '#FF5555',
];

/**
 * Relazione di chi LEGGE verso il mittente, stessa logica di FactionManager.relationColor:
 * mittente senza fazione = none, lettore senza fazione = enemy, stessa fazione = member,
 * altrimenti alleato solo se l'alleanza e' reciproca.
 */
function faction_relation(?int $lettore, ?int $mittente, array $alleati): string {
    if (!$mittente) {
        return 'none';
    }
    if (!$lettore) {
        return 'enemy';
    }
    if ($lettore === $mittente) {
        return 'member';
    }
    return in_array($mittente, $alleati, true) ? 'ally' : 'enemy';
}

/**
 * Fazioni alleate di una fazione. In gioco l'alleanza vale solo se e' RECIPROCA (la tabella
 * `relations` contiene un desiderio per verso, l'assenza di riga significa nemico): il JOIN
 * della tabella su se stessa tiene quindi solo le coppie presenti in entrambe le direzioni.
 */
function faction_allies(?int $factionId): array {
    if (!$factionId) {
        return [];
    }
    $stmt = db()->prepare(
        'SELECT r1.other_id
           FROM factions_magixfactions.relations r1
           JOIN factions_magixfactions.relations r2
             ON r2.faction_id = r1.other_id AND r2.other_id = r1.faction_id
          WHERE r1.faction_id = ?
            AND (r1.type IS NULL OR r1.type = \'ALLY\')
            AND (r2.type IS NULL OR r2.type = \'ALLY\')'
    );
    $stmt->execute([$factionId]);
    return array_map('intval', $stmt->fetchAll(PDO::FETCH_COLUMN));
}

/**
 * Il mittente di un messaggio di chat COME SI VEDE IN GIOCO: `[Fazione]` (colore della RELAZIONE di
 * chi legge), poi i tag dei GRADI, poi il nome (colore del GRADO piu' alto) — cioe' `chat.public-format`
 * di MagixFactions (`&8[{relcolor}{faction}&8] %luckperms_prefix%&7%magixweb_namecolor%{name}`). Chi non
 * ha una fazione usa il formato senza fazione: solo i tag del grado + nome, sempre col colore del grado.
 *
 * <p>Regola chiave (allineata al server): SOLO il tag `[Fazione]` e' colorato per relazione (rosso ai
 * nemici); il NOME prende il colore del suo grado (default grigio &7), mai quello della relazione.
 */
function chat_sender_html(array $riga, string $relazione): string {
    $fazione = trim((string) ($riga['faction_name'] ?? ''));
    $coloreRel = FACTION_REL_COLORS[$relazione] ?? FACTION_REL_COLORS['none'];

    // Nome = colore del GRADO (name_color sincronizzato da RankSync, lo stesso di %magixweb_namecolor%
    // in gioco), grigio &7 (#AAAAAA) di default. La RELAZIONE colora solo il tag [Fazione], non il nome.
    // Stesso trattamento dei colori dei gradi: si stampano DUE tinte (quella di gioco e quella scurita
    // per il tema chiaro) e a scegliere e' il foglio di stile.
    // NIENTE pallino di presenza qui: in chat i messaggi arrivano sia dal gioco sia dal sito, e un
    // pallino che parla SOLO del sito, accanto a un nome, si legge come "e' in partita". Da dove arriva
    // il messaggio lo dice gia' l'icona a inizio riga.
    $coloreNome = (string) ($riga['name_color'] ?? '');
    if (!is_valid_hex_color($coloreNome)) {
        $coloreNome = '#AAAAAA'; // &7 grigio (grado default o nessun colore dichiarato)
    }
    // Il nome porta alla scheda del giocatore: e' il gesto che uno si aspetta leggendo una
    // chat ("chi e' questo?"), e la scheda esiste gia' (/utente).
    $nome = '<a class="chat-nome colore-grado" href="/utente?nome='
        . h(rawurlencode((string) $riga['mc_username'])) . '" style="' . stile_colore_grado($coloreNome) . '">'
        . h((string) $riga['mc_username']) . '</a>';

    if ($fazione === '') {
        // Senza fazione: solo i tag del grado + nome (come public-format-no-faction in gioco).
        return player_tag($riga) . $nome;
    }
    // Ordine come in gioco: [Fazione] (colore relazione) -> tag del grado -> nome (colore del grado).
    return '<span class="chat-fac">[<span class="colore-grado" style="' . stile_colore_grado($coloreRel) . '">'
            . h($fazione) . '</span>]</span> '
        . player_tag($riga)
        . $nome;
}

/**
 * Slug libero per una tabella: se "vip" esiste gia' prova "vip-2", "vip-3"...
 * Il nome tabella finisce nella query, quindi e' ammesso solo dall'elenco qui sotto.
 */
/**
 * Colore di testo leggibile sopra un colore di sfondo: scuro sui colori chiari,
 * chiaro sui colori scuri. Evita di dover chiedere all'admin anche il colore del testo.
 */
/**
 * Luminanza relativa secondo WCAG (0 = nero, 1 = bianco). Non e' la media dei canali:
 * ogni canale va prima "linearizzato", altrimenti i colori accesi sembrano piu' chiari
 * di quanto l'occhio li veda davvero.
 */
function luminanza_relativa(string $hex): float {
    if (!is_valid_hex_color($hex)) {
        return 0.0;
    }
    $canale = function (int $v): float {
        $v /= 255;
        return $v <= 0.03928 ? $v / 12.92 : pow(($v + 0.055) / 1.055, 2.4);
    };
    return 0.2126 * $canale(hexdec(substr($hex, 1, 2)))
         + 0.7152 * $canale(hexdec(substr($hex, 3, 2)))
         + 0.0722 * $canale(hexdec(substr($hex, 5, 2)));
}

/**
 * Mescola due colori: $peso e' quanto pesa il primo (0.14 = 14% del colore, 86% del fondo).
 * Serve a sapere di che tinta diventa DAVVERO un fondo semitrasparente, per poi calcolarci
 * sopra il contrasto: confrontare il testo col bianco puro sbaglierebbe di poco, ma quel
 * poco basta a stare sotto la soglia di leggibilita'.
 */
function hex_mix(string $colore, string $fondo, float $peso): string {
    if (!is_valid_hex_color($colore) || !is_valid_hex_color($fondo)) {
        return $fondo;
    }
    $peso = max(0, min(1, $peso));
    $canali = [];
    for ($i = 0; $i < 3; $i++) {
        $c = hexdec(substr($colore, 1 + $i * 2, 2));
        $f = hexdec(substr($fondo, 1 + $i * 2, 2));
        $canali[] = (int) round($c * $peso + $f * (1 - $peso));
    }
    return sprintf('#%02x%02x%02x', ...$canali);
}

/** Rapporto di contrasto fra due colori: 1 = identici, 21 = nero su bianco. */
function contrasto(string $a, string $b): float {
    $la = luminanza_relativa($a);
    $lb = luminanza_relativa($b);
    return (max($la, $lb) + 0.05) / (min($la, $lb) + 0.05);
}

/**
 * Testo NERO o BIANCO sopra un colore pieno, scegliendo quello che si legge meglio:
 * si confrontano i due contrasti e vince il maggiore. Prima si guardava una soglia fissa
 * di luminanza, che sbagliava sui colori medi (es. un verde acido dava testo bianco).
 */
function text_on_color(string $hex): string {
    if (!is_valid_hex_color($hex)) {
        return '#f0f0ee';
    }
    return contrasto($hex, '#1a1a1a') >= contrasto($hex, '#f0f0ee') ? '#1a1a1a' : '#f0f0ee';
}

/**
 * Adatta un colore di TESTO perche' resti leggibile sopra un fondo: lo scurisce (o schiarisce)
 * a piccoli passi finche' il contrasto non arriva alla soglia. Serve ai colori che arrivano
 * dal gioco — i gradi di LuckPerms, le relazioni fra fazioni — che sono pensati per la chat
 * di Minecraft, cioe' per un fondo scuro: sul tema chiaro un giallo o un verde acido
 * sparirebbero.
 *
 * @param float $soglia 4.5 = testo normale secondo WCAG, 3.0 = testo grande/grassetto
 */
function colore_leggibile(string $hex, string $sfondo, float $soglia = 4.5): string {
    if (!is_valid_hex_color($hex) || !is_valid_hex_color($sfondo)) {
        return $hex;
    }
    // Su fondo chiaro si scurisce, su fondo scuro si schiarisce.
    $verso = luminanza_relativa($sfondo) > 0.5 ? -1 : 1;
    $colore = $hex;
    for ($i = 0; $i < 20 && contrasto($colore, $sfondo) < $soglia; $i++) {
        $colore = hex_shade($colore, $verso * 0.06);
    }
    return $colore;
}

/**
 * Corpo di un articolo o di una pagina, pronto da stampare.
 *
 * Chi scrive dal gestionale butta giu' testo semplice e si aspetta che gli a capo si vedano:
 * per quello c'e' sempre stato nl2br(). Ma un articolo curato a mano puo' contenere HTML
 * vero (titoletti, riquadri, elenchi), e li' nl2br() aggiungerebbe un <br> dopo ogni riga
 * del codice, sfasando tutto. Quindi: se il testo contiene tag di BLOCCO lo si lascia stare,
 * altrimenti si continua a fare come prima.
 */
function corpo_articolo(?string $body): string {
    $t = (string) $body;
    $haBlocchi = (bool) preg_match('#<(?:p|div|section|h[1-6]|ul|ol|table|blockquote|figure|article)[\s>/]#i', $t);
    return $haBlocchi ? $t : nl2br($t);
}

/** true se il corpo e' scritto in HTML: serve al foglio di stile (niente pre-wrap sopra i tag). */
function corpo_e_html(?string $body): bool {
    return (bool) preg_match('#<(?:p|div|section|h[1-6]|ul|ol|table|blockquote|figure|article)[\s>/]#i', (string) $body);
}

function unique_slug(string $table, string $base): string {
    $consentite = ['store_categories', 'store_packages', 'blog_posts', 'site_pages', 'forum_categories'];
    if (!in_array($table, $consentite, true)) {
        throw new InvalidArgumentException('Tabella non ammessa per unique_slug: ' . $table);
    }

    $base = $base !== '' ? $base : 'voce';
    $slug = $base;
    $i = 2;
    $check = db()->prepare("SELECT COUNT(*) FROM {$table} WHERE slug = ?");
    while (true) {
        $check->execute([$slug]);
        if ((int) $check->fetchColumn() === 0) {
            return $slug;
        }
        $slug = $base . '-' . $i++;
    }
}

/**
 * Il pacchetto in evidenza dello store: uno solo in tutto il negozio, categorie comprese.
 * Conta solo se e' anche visibile, altrimenti il banner della home punterebbe a una card
 * che nel negozio non c'e'. Il risultato si calcola una volta sola per richiesta.
 */
function store_pacchetto_evidenza(): ?array {
    static $pacchetto = false; // false = non ancora cercato
    if ($pacchetto === false) {
        $q = db()->query('SELECT * FROM store_packages WHERE featured = 1 AND enabled = 1 LIMIT 1');
        $pacchetto = $q->fetch() ?: null;
    }
    return $pacchetto;
}

/** Indirizzo della pagina del pacchetto in evidenza, o null se non ce n'e' uno. */
function store_link_evidenza(): ?string {
    $p = store_pacchetto_evidenza();
    return $p ? '/pacchetto/' . rawurlencode($p['slug']) : null;
}

function time_ago(string $datetime): string {
    $ts = strtotime($datetime);
    $diff = time() - $ts;
    if ($diff < 60) return 'adesso';
    if ($diff < 3600) return floor($diff / 60) . ' min fa';
    if ($diff < 86400) return floor($diff / 3600) . ' h fa';
    if ($diff < 2592000) return floor($diff / 86400) . ' giorni fa';
    return date('d/m/Y', $ts);
}

/**
 * Dall'altra parte c'e' un browser vero, o un programma automatico?
 *
 * Si riconosce il browser invece di elencare i bot: i nomi dei programmi cambiano ogni
 * settimana, mentre un browser vero si presenta SEMPRE come "Mozilla/5.0 ..." seguito dal
 * suo motore (Chrome, Safari, Firefox, Gecko, Edg, Opera). Chi dice di essere un bot e'
 * fuori comunque, anche se imita la forma giusta.
 */
function ospite_e_browser_vero(): bool {
    $ua = trim((string) ($_SERVER['HTTP_USER_AGENT'] ?? ''));
    // Nessun browser vero si presenta senza nome: chi non lo manda e' un programma.
    if ($ua === '' || strlen($ua) > 500) {
        return false;
    }
    if (preg_match('/bot|crawl|spider|scan|scout|slurp|scrap|fetch|monitor|probe|'
                 . 'headless|phantom|curl|wget|python|java\/|go-http|libwww|okhttp|axios|'
                 . 'httpx|zgrab|masscan|nuclei|censys|shodan|ahrefs|semrush|dataprovider|'
                 . 'expanse|l9explore|l9tcpid|rootevidence|inspect|preview|archiver/i', $ua)) {
        return false;
    }
    return preg_match('#Mozilla/5\.0#i', $ua) === 1
        && preg_match('/Chrome|CriOS|Safari|Firefox|FxiOS|Gecko|Edg|OPR|Opera/i', $ua) === 1;
}

/**
 * Presenza degli OSPITI (chi guarda senza aver fatto l'accesso).
 *
 * Si registra una riga per sessione del browser, con l'ultima volta che ha aperto una
 * pagina; la chiave e' l'hash della sessione, quindi la tabella non dice CHI e' nessuno, solo
 * QUANTI sono. Come per gli utenti, si scrive al massimo una volta al minuto.
 */
function ospiti_registra(): void {
    if (session_status() !== PHP_SESSION_ACTIVE || is_logged_in()) {
        return;
    }
    // Programmi automatici fuori dal conteggio: senza questo filtro il riquadro diceva
    // "4 ospiti" mentre erano scanner di Internet (Censys, Ahrefs, LeakIX...) che il sito
    // lo trovano da soli, dall'indirizzo IP e dai registri pubblici dei certificati.
    if (!ospite_e_browser_vero()) {
        return;
    }
    // Il cookie di sessione DEVE essere tornato indietro: e' la prova che dall'altra parte
    // c'e' un browser vero. Alla primissima pagina il cookie e' appena stato dato e non e'
    // ancora tornato: quel giro non si conta, e chi resta si conta al passo dopo (basta il
    // battito della chat, che ogni 5 secondi chiama /api/chat). I programmi automatici il
    // cookie non lo rimandano mai, quindi non entrano nemmeno se si fingono browser.
    if (!isset($_COOKIE[session_name()])) {
        return;
    }
    if (time() - (int) ($_SESSION['visto_ospite'] ?? 0) <= 60) {
        return;
    }
    $_SESSION['visto_ospite'] = time();

    try {
        $chiave = hash('sha256', session_id());
        db()->prepare('INSERT INTO guests_online (guest_key, last_seen) VALUES (?, NOW())
                       ON DUPLICATE KEY UPDATE last_seen = NOW()')->execute([$chiave]);
        // Pulizia ogni tanto (una volta su venti): le righe vecchie non servono a nessuno e
        // una tabella che cresce all'infinito prima o poi si fa sentire.
        if (random_int(1, 20) === 1) {
            db()->exec('DELETE FROM guests_online WHERE last_seen < DATE_SUB(NOW(), INTERVAL 1 DAY)');
        }
    } catch (PDOException $e) {
        // tabella non ancora creata: gli ospiti semplicemente non si contano
    }
}

/** Quanti ospiti stanno guardando il sito adesso. */
function ospiti_sul_sito(int $minuti = 5): int {
    $minuti = max(1, min(1440, $minuti));
    try {
        $q = db()->query("SELECT COUNT(*) FROM guests_online
                          WHERE last_seen >= DATE_SUB(NOW(), INTERVAL {$minuti} MINUTE)");
        return (int) $q->fetchColumn();
    } catch (PDOException $e) {
        return 0;
    }
}

/**
 * Nomi cambiati su minecraft.net.
 *
 * Rinominandosi, l'UUID resta lo stesso e il nome no: il sito pero' tiene il nome in
 * `users.mc_username`, scritto una volta sola quando l'account e' stato collegato. Il plugin
 * riscrive il nome nuovo in `mc_ranks` al primo ingresso in partita, quindi da li' si puo'
 * rimettere in pari anche la riga del sito.
 *
 * Si fa di rado (una volta su venti): non e' una cosa che cambia ogni minuto. `UPDATE
 * IGNORE` perche' `mc_username` e' unico — se qualcun altro ha nel frattempo preso quel nome
 * la riga si salta invece di far fallire tutto.
 */
function allinea_nomi_mc(): void {
    try {
        if (random_int(1, 20) !== 1) {
            return;
        }
        db()->exec('UPDATE IGNORE users u
                    JOIN mc_ranks r ON r.mc_uuid = u.mc_uuid COLLATE utf8mb4_unicode_ci
                    SET u.mc_username = r.mc_username
                    WHERE r.mc_username <> u.mc_username');
    } catch (PDOException $e) {
        // tabella o colonna mancante: i nomi restano come sono
    }
}

/**
 * UUID del miglior sostenitore dello store (chi ha speso di piu'), o null.
 *
 * Stessa regola della colonna dello store — le consegne manuali contano solo se lo dice
 * l'impostazione — cosi' i posti dove compare la corona non si contraddicono fra loro.
 * Si calcola una volta per richiesta: lo chiedono la colonna, la barra in alto, il forum,
 * l'elenco utenti...
 */
function store_top_uuid(): ?string {
    static $uuid = false;   // false = non ancora calcolato (null e' una risposta valida)
    if ($uuid !== false) {
        return $uuid;
    }
    try {
        $manuali = site_setting('store_sidebar_include_manual', '0') === '1';
        $soloVeri = $manuali ? '' : " AND (paypal_capture_id IS NULL OR paypal_capture_id NOT LIKE 'MANUALE-%') ";
        $q = db()->query("SELECT mc_uuid FROM store_orders WHERE status = 'paid' {$soloVeri}
                          GROUP BY mc_uuid ORDER BY SUM(price) DESC LIMIT 1");
        $uuid = $q->fetchColumn() ?: null;
    } catch (PDOException $e) {
        $uuid = null;   // store non installato
    }
    return $uuid;
}

/**
 * Corona + cuoricini, da mettere DENTRO un contenitore posizionato. I valori dei cuori sono
 * fissi e non casuali: l'effetto e' sempre lo stesso e non "salta" a ogni ricarica.
 *
 * @param bool $conCuori tenuto per casi particolari; di serie i cuori ci sono sempre,
 *        visto che si dimensionano da soli sulla faccia
 */
function corona_top(bool $conCuori = true): string {
    $out = '<span class="store-top-corona" aria-hidden="true" title="Miglior sostenitore">&#128081;</span>';
    if (!$conCuori) {
        return $out;
    }
    // Misure in FRAZIONE della faccia (--av), non in pixel: cosi' gli stessi tre cuori
    // funzionano sulla facciona da 72px della colonna e sulla miniatura da 22px
    // dell'elenco "sul sito ora", senza diventare coriandoli o macigni.
    $cuori = [
        ['x' => '4%',  'dx' => '-0.18', 'dim' => '0.26', 'dur' => '3.4s', 'ritardo' => '0s'],
        ['x' => '70%', 'dx' => '0.16',  'dim' => '0.21', 'dur' => '4.1s', 'ritardo' => '.9s'],
        ['x' => '32%', 'dx' => '-0.07', 'dim' => '0.29', 'dur' => '3.8s', 'ritardo' => '1.8s'],
    ];
    $out .= '<span class="store-top-cuori" aria-hidden="true">';
    foreach ($cuori as $c) {
        $out .= sprintf(
            '<i style="--x:%s;--dx:%s;--dim:%s;--dur:%s;--ritardo:%s">&#9829;</i>',
            $c['x'], $c['dx'], $c['dim'], $c['dur'], $c['ritardo']
        );
    }
    return $out . '</span>';
}

/**
 * REGOLA DEL SITO: dove c'e' la faccia del MIGLIOR SOSTENITORE, ci va la corona.
 *
 * Si passa l'HTML dell'immagine gia' pronto e l'uuid di chi rappresenta: se non e' lui,
 * torna indietro identico (nessun elemento in piu' nella pagina). La misura serve solo a
 * dimensionare corona e cuori in proporzione alla faccia (--av).
 *
 * Chi aggiunge un punto nuovo del sito in cui compare un avatar deve passare da qui.
 *
 * @param string  $imgHtml immagine gia' costruita (con le sue classi e attributi)
 * @param ?string $uuid    uuid del giocatore raffigurato
 * @param int     $dim     lato dell'immagine in pixel
 * @param bool    $conCuori cuoricini oltre alla corona: di serie SI', ovunque. Erano stati
 *        tolti dagli spazi stretti quando avevano misure fisse; ora sono in proporzione alla
 *        faccia, quindi non danno piu' fastidio da nessuna parte.
 */
function avatar_top(string $imgHtml, ?string $uuid, int $dim = 40, bool $conCuori = true): string {
    $top = store_top_uuid();
    if ($uuid === null || $top === null || strcasecmp($uuid, $top) !== 0) {
        return $imgHtml;
    }
    return '<span class="avatar-top" style="--av:' . max(16, $dim) . 'px">'
        . $imgHtml . corona_top($conCuori) . '</span>';
}
