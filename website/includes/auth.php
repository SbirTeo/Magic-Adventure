<?php
require_once __DIR__ . '/db.php';
require_once __DIR__ . '/helpers.php';
require_once __DIR__ . '/permissions.php';
require_once __DIR__ . '/otp.php';

if (session_status() === PHP_SESSION_NONE) {
    // Rifiuta gli id di sessione non generati da noi (difesa dalla session fixation)
    ini_set('session.use_strict_mode', '1');
    session_set_cookie_params([
        'lifetime' => 0,          // dura finche' il browser resta aperto
        'path' => '/',
        // Secure solo se la richiesta e' davvero in HTTPS: cosi' un eventuale accesso
        // in chiaro (es. in locale) non resta senza cookie e quindi senza login.
        'secure' => (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
            || ($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https',
        'httponly' => true,       // non leggibile da JavaScript
        'samesite' => 'Lax',      // non viaggia sulle richieste da altri siti
    ]);
    session_start();
}

/**
 * "Resta collegato": il cookie di sessione muore alla chiusura del browser, questo no.
 *
 * Nel cookie viaggia `selettore:validatore`: nel database si salva il selettore in chiaro
 * (serve solo a trovare la riga) e il validatore SOLO come hash, cosi' chi leggesse la
 * tabella non potrebbe rifabbricarsi il cookie. Il confronto e' a tempo costante.
 */
const REMEMBER_COOKIE = 'ma_resta_collegato';
const REMEMBER_GIORNI = 365;

/** Stessi criteri del cookie di sessione (vedi sopra), ma con una scadenza vera. */
function remember_cookie_options(int $expires): array {
    return [
        'expires' => $expires,
        'path' => '/',
        'secure' => (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
            || ($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https',
        'httponly' => true,
        'samesite' => 'Lax',
    ];
}

/**
 * Rilascia un cookie "resta collegato" nuovo (da chiamare dopo un accesso riuscito).
 *
 * $otpOk dice se questo accesso e' passato dal codice a sei cifre: solo allora il cookie
 * vale come "dispositivo fidato" e potra' riportare dentro senza richiedere il codice.
 * I cookie nati prima che la verifica esistesse valgono 0 e fanno ripassare da /otp.
 */
function remember_me(int $userId, bool $otpOk = false): void {
    $selettore = bin2hex(random_bytes(16));
    $validatore = bin2hex(random_bytes(32));
    $scadenza = time() + REMEMBER_GIORNI * 86400;

    $ins = db()->prepare(
        'INSERT INTO remember_tokens (user_id, selector, validator_hash, expires_at, otp_ok)
         VALUES (?, ?, ?, FROM_UNIXTIME(?), ?)'
    );
    $ins->execute([$userId, $selettore, hash('sha256', $validatore), $scadenza, $otpOk ? 1 : 0]);

    // Ogni tanto si portano via le righe scadute: nessun cron da mantenere per una tabella
    // che cresce di una riga per accesso.
    if (random_int(1, 50) === 1) {
        db()->exec('DELETE FROM remember_tokens WHERE expires_at < NOW()');
    }

    setcookie(REMEMBER_COOKIE, $selettore . ':' . $validatore, remember_cookie_options($scadenza));
}

/** Butta via il cookie di questo browser e la riga corrispondente. */
function remember_forget(): void {
    $cookie = $_COOKIE[REMEMBER_COOKIE] ?? '';
    if ($cookie !== '' && str_contains($cookie, ':')) {
        [$selettore] = explode(':', $cookie, 2);
        $del = db()->prepare('DELETE FROM remember_tokens WHERE selector = ?');
        $del->execute([$selettore]);
    }
    unset($_COOKIE[REMEMBER_COOKIE]);
    setcookie(REMEMBER_COOKIE, '', remember_cookie_options(time() - 3600));
}

/** Tutti i browser di un utente (da usare quando cambia la password). */
function remember_forget_all(int $userId): void {
    $del = db()->prepare('DELETE FROM remember_tokens WHERE user_id = ?');
    $del->execute([$userId]);
}

/**
 * Butta fuori tutti gli ALTRI dispositivi, lasciando dentro questo.
 *
 * Due mosse: si alza `session_epoch` (le sessioni aperte altrove non combaciano piu' e
 * cadono da sole, vedi current_user) e si cancellano i "resta collegato" tranne quello di
 * questo browser — senza il secondo passo gli altri rientrerebbero subito dal cookie.
 * La sessione da cui si e' chiesto sopravvive perche' si aggiorna anche la sua epoca.
 */
function logout_other_devices(int $userId): void {
    $upd = db()->prepare('UPDATE users SET session_epoch = session_epoch + 1 WHERE id = ?');
    $upd->execute([$userId]);

    $q = db()->prepare('SELECT session_epoch FROM users WHERE id = ?');
    $q->execute([$userId]);
    $_SESSION['epoch'] = (int) $q->fetchColumn();

    $cookie = $_COOKIE[REMEMBER_COOKIE] ?? '';
    $mio = ($cookie !== '' && str_contains($cookie, ':')) ? explode(':', $cookie, 2)[0] : '';
    $del = db()->prepare('DELETE FROM remember_tokens WHERE user_id = ? AND selector <> ?');
    $del->execute([$userId, $mio]);
}

/**
 * Riapre la sessione dal cookie, se c'e' ed e' valido. Gira all'inizio di ogni richiesta
 * (vedi in fondo al file), quindi PRIMA che la pagina scriva qualsiasi cosa: setcookie e
 * session_regenerate_id hanno bisogno che le intestazioni siano ancora aperte.
 *
 * Il validatore NON viene ruotato a ogni uso: le pagine fanno richieste in parallelo
 * (la chat interroga /api/chat.php da sola) e due richieste con il cookie vecchio
 * finirebbero per sbattere fuori chi e' appena rientrato. Si allunga solo la scadenza.
 */
function remember_try_login(): void {
    $cookie = $_COOKIE[REMEMBER_COOKIE] ?? '';
    if ($cookie === '' || !str_contains($cookie, ':')) {
        return;
    }
    [$selettore, $validatore] = explode(':', $cookie, 2);

    try {
        $q = db()->prepare(
            'SELECT t.id, t.validator_hash, t.otp_ok, u.id AS user_id, u.session_epoch
             FROM remember_tokens t JOIN users u ON u.id = t.user_id
             WHERE t.selector = ? AND t.expires_at > NOW()'
        );
        $q->execute([$selettore]);
        $riga = $q->fetch();
    } catch (PDOException $e) {
        return; // tabella non ancora creata: si resta semplicemente sloggati
    }

    if (!$riga || !hash_equals((string) $riga['validator_hash'], hash('sha256', $validatore))) {
        remember_forget(); // cookie vecchio o falso: via
        return;
    }

    session_regenerate_id(true);
    $_SESSION['user_id'] = (int) $riga['user_id'];
    $_SESSION['epoch'] = (int) $riga['session_epoch'];
    // Dispositivo gia' passato dal codice a sei cifre: non lo si richiede di nuovo. Se il
    // cookie e' piu' vecchio della verifica in due passaggi, questo resta spento e la
    // guardia qui sotto manda l'utente a /otp.
    $_SESSION['otp_ok'] = ((int) ($riga['otp_ok'] ?? 0) === 1);

    $scadenza = time() + REMEMBER_GIORNI * 86400;
    $upd = db()->prepare('UPDATE remember_tokens SET expires_at = FROM_UNIXTIME(?), last_used_at = NOW() WHERE id = ?');
    $upd->execute([$scadenza, $riga['id']]);
    setcookie(REMEMBER_COOKIE, $cookie, remember_cookie_options($scadenza));
}

function current_user(): ?array {
    static $user = false; // false = non ancora calcolato
    if ($user === false) {
        if (!empty($_SESSION['user_id'])) {
            $stmt = db()->prepare('SELECT u.*, ' . RANK_SELECT_SQL . ', r.groups_json, r.mc_username AS nome_gioco, r.language FROM users u' . rank_join_sql() . ' WHERE u.id = ?');
            $stmt->execute([$_SESSION['user_id']]);
            $user = $stmt->fetch() ?: null;

            // Cambiando password l'utente incrementa session_epoch: le sessioni aperte
            // altrove non combaciano piu' e cadono da sole, senza aspettare la scadenza.
            if ($user !== null && (int) ($_SESSION['epoch'] ?? 0) !== (int) $user['session_epoch']) {
                $_SESSION = [];
                session_destroy();
                $user = null;
            }

            // Presenza sul sito (serve all'elenco "Sul sito ora" nella home): basta sapere
            // che sei passato di qui da poco, quindi si scrive al massimo una volta al
            // minuto — una UPDATE a ogni pagina sarebbe sprecata.
            if ($user !== null && time() - (int) ($_SESSION['visto'] ?? 0) > 60) {
                $_SESSION['visto'] = time();
                try {
                    $t = db()->prepare('UPDATE users SET last_seen = NOW() WHERE id = ?');
                    $t->execute([(int) $user['id']]);
                    $user['last_seen'] = date('Y-m-d H:i:s');
                } catch (PDOException $e) {
                    // colonna non ancora creata: la presenza semplicemente non si aggiorna
                }

                // Nome cambiato su minecraft.net: l'UUID resta, il nome no. Il plugin scrive
                // quello nuovo in mc_ranks al primo ingresso in partita; qui si rimette in
                // pari la riga del sito, cosi' chi si e' rinominato lo vede subito.
                if (!empty($user['nome_gioco']) && $user['nome_gioco'] !== $user['mc_username']) {
                    try {
                        db()->prepare('UPDATE IGNORE users SET mc_username = ? WHERE id = ?')
                            ->execute([$user['nome_gioco'], (int) $user['id']]);
                        $user['mc_username'] = $user['nome_gioco'];
                    } catch (PDOException $e) {
                        // nome gia' preso da un altro account: si resta com'era
                    }
                }
            }
        } else {
            $user = null;
        }
    }
    return $user;
}

function is_logged_in(): bool {
    return current_user() !== null;
}

function is_admin(): bool {
    $u = current_user();
    return $u !== null && (int)$u['is_admin'] === 1;
}

function require_login(): void {
    if (!is_logged_in()) {
        redirect('/login');
    }
}

function require_admin(): void {
    if (!is_admin()) {
        http_response_code(403);
        die('Accesso riservato agli amministratori.');
    }
}

/**
 * Porta a termine un accesso: da qui in poi l'utente e' dentro davvero.
 *
 * La usano /login, /set-password e /otp, cosi' le tre strade fanno le stesse identiche
 * cose (id di sessione nuovo, epoca, cookie del dispositivo) e non c'e' modo che una si
 * dimentichi un pezzo.
 */
function accesso_completato(array $utente, bool $ricordami, bool $otpFatto): void {
    session_regenerate_id(true);
    $_SESSION['user_id'] = (int) $utente['id'];
    $_SESSION['epoch'] = (int) $utente['session_epoch'];
    $_SESSION['otp_ok'] = $otpFatto;
    unset($_SESSION['otp_attesa'], $_SESSION['otp_segreto_nuovo']);

    db()->prepare('UPDATE users SET last_login = NOW() WHERE id = ?')->execute([(int) $utente['id']]);

    if ($ricordami) {
        remember_me((int) $utente['id'], $otpFatto);
    }
}

/**
 * Mette da parte l'accesso a meta' strada, in attesa del codice a sei cifre.
 *
 * Nota: NON si scrive user_id in sessione. Finche' il codice non arriva l'utente non e'
 * collegato per niente — non basta "nascondere" le pagine, non deve proprio esserci un
 * accesso valido in giro.
 */
function otp_metti_in_attesa(int $userId, bool $ricordami): void {
    $_SESSION['otp_attesa'] = [
        'user_id' => $userId,
        'ricordami' => $ricordami,
        'ora' => time(),
    ];
}

/**
 * Guardia: un account che deve usare la verifica in due passaggi non puo' restare
 * collegato senza averla passata.
 *
 * Serve nei casi che il modulo di accesso da solo non copre: qualcuno viene promosso
 * web-admin mentre e' collegato, oppure rientra da un cookie rilasciato prima che la
 * verifica esistesse. In quel caso la sessione si chiude e si riparte da /otp.
 */
function otp_guard(): void {
    if (empty($_SESSION['user_id']) || !empty($_SESSION['otp_ok'])) {
        return;
    }

    $utente = current_user();
    if ($utente === null || !otp_serve_per($utente)) {
        return;
    }

    // Pagine che devono restare raggiungibili, se no si gira in tondo.
    $percorso = rtrim((string) (parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/'), '/');
    $percorso = preg_replace('/\.php$/', '', $percorso);
    if (in_array($percorso, ['/otp', '/logout'], true)) {
        return;
    }

    $userId = (int) $utente['id'];
    $_SESSION['user_id'] = null;
    unset($_SESSION['user_id']);
    otp_metti_in_attesa($userId, false);

    // Le chiamate della chat e simili non devono ricevere una pagina HTML al posto del JSON:
    // per loro basta la sessione tolta qui sopra, ci penseranno da sole a dire "non collegato".
    if (!str_starts_with($percorso, '/api/')) {
        redirect('/otp');
    }
}

// Sessione scaduta (o browser riaperto) ma cookie "resta collegato" ancora buono: si rientra
// da soli. Qui, in fondo al file: auth.php e' la prima cosa che ogni pagina carica, quindi
// non e' ancora stato stampato niente e le intestazioni sono ancora modificabili.
if (empty($_SESSION['user_id']) && !empty($_COOKIE[REMEMBER_COOKIE])) {
    remember_try_login();
}

// ...e subito dopo: chi deve passare dal codice a sei cifre non prosegue senza.
otp_guard();
