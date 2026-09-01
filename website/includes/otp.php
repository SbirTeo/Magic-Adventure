<?php
/**
 * Verifica in due passaggi (OTP) per gli account che comandano il sito.
 *
 * Il codice a sei cifre che cambia ogni trenta secondi e' lo standard TOTP (RFC 6238):
 * lo stesso di Google Authenticator, Aegis, 1Password, Bitwarden. Server e telefono
 * partono da un segreto in comune e dall'orologio, e ricavano lo stesso numero senza
 * scambiarsi mai niente — per questo funziona anche col telefono in aereo.
 *
 * Qui dentro non ci sono librerie esterne: sono un centinaio di righe fra base32 e HMAC,
 * e tirarsi in casa un pacchetto (con il suo aggiornamento da seguire) per una cosa che
 * PHP sa gia' fare da solo sarebbe un peso, non una sicurezza.
 *
 * Le difese, in ordine:
 *  - il segreto sta nel database CIFRATO (AES-256-GCM) con una chiave che vive solo in
 *    config.php, fuori dal docroot: chi leggesse il database — o entrasse da phpMyAdmin —
 *    non se ne farebbe niente;
 *  - un codice gia' usato non si puo' riusare (si tiene da parte l'ultimo intervallo
 *    accettato): chi lo sbircia alle spalle ha comunque le mani legate;
 *  - dopo cinque tentativi sbagliati l'account si ferma per quindici minuti, cosi'
 *    provare un milione di combinazioni non e' una strada;
 *  - i codici di recupero sono monouso e nel database stanno come hash, come le password.
 */

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/helpers.php';   // site_setting(), per l'interruttore dello staff

/** Durata di un codice, in secondi. Trenta e' il valore che si aspettano tutte le app. */
const OTP_PASSO = 30;

/** Cifre del codice. */
const OTP_CIFRE = 6;

/**
 * Quanti intervalli prima e dopo si accettano: 1 = piu' o meno mezzo minuto di tolleranza
 * sull'orologio del telefono. Alzarlo allarga anche la finestra di chi indovina.
 */
const OTP_TOLLERANZA = 1;

/** Tentativi sbagliati di fila prima del blocco temporaneo, e per quanti minuti. */
const OTP_TENTATIVI_MAX = 5;
const OTP_BLOCCO_MINUTI = 15;

/** Quanti codici di recupero si consegnano quando si attiva la verifica. */
const OTP_CODICI_RECUPERO = 10;

// ---------------------------------------------------------------------------------------
// BASE32 — l'alfabeto con cui le app OTP scrivono il segreto (RFC 4648, niente 0/1/8/9
// per non confonderli con O/I/B/g quando si digita a mano).
// ---------------------------------------------------------------------------------------

const OTP_ALFABETO = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

function otp_base32_encode(string $dati): string
{
    if ($dati === '') return '';
    $bit = '';
    foreach (str_split($dati) as $c) {
        $bit .= str_pad(decbin(ord($c)), 8, '0', STR_PAD_LEFT);
    }
    $out = '';
    foreach (str_split($bit, 5) as $pezzo) {
        $out .= OTP_ALFABETO[bindec(str_pad($pezzo, 5, '0', STR_PAD_RIGHT))];
    }
    return $out;
}

function otp_base32_decode(string $testo): string
{
    $testo = strtoupper(preg_replace('/[^A-Za-z2-7]/', '', $testo));
    if ($testo === '') return '';
    $bit = '';
    foreach (str_split($testo) as $c) {
        $bit .= str_pad(decbin(strpos(OTP_ALFABETO, $c)), 5, '0', STR_PAD_LEFT);
    }
    $out = '';
    foreach (str_split($bit, 8) as $pezzo) {
        // L'ultimo gruppo puo' essere spaiato (il base32 non finisce sempre in byte interi):
        // quei bit di troppo sono riempimento e si buttano.
        if (strlen($pezzo) === 8) $out .= chr(bindec($pezzo));
    }
    return $out;
}

// ---------------------------------------------------------------------------------------
// IL CODICE A SEI CIFRE
// ---------------------------------------------------------------------------------------

/** Segreto nuovo di zecca: 20 byte casuali, la lunghezza consigliata dallo standard. */
function otp_nuovo_segreto(): string
{
    return otp_base32_encode(random_bytes(20));
}

/** Il codice valido in un dato intervallo (l'intervallo di adesso e' time()/30). */
function otp_code(string $segretoBase32, int $passo): string
{
    $chiave = otp_base32_decode($segretoBase32);
    if ($chiave === '') return '';

    // Il numero dell'intervallo viaggia come otto byte, dal piu' pesante al piu' leggero.
    $messaggio = pack('J', $passo);
    $hash = hash_hmac('sha1', $messaggio, $chiave, true);

    // "Troncamento dinamico" (RFC 4226): le ultime quattro cifre binarie dicono da dove
    // pescare i quattro byte che diventeranno il numero.
    $inizio = ord($hash[19]) & 0x0F;
    $numero = ((ord($hash[$inizio]) & 0x7F) << 24)
            | (ord($hash[$inizio + 1]) << 16)
            | (ord($hash[$inizio + 2]) << 8)
            | ord($hash[$inizio + 3]);

    return str_pad((string) ($numero % (10 ** OTP_CIFRE)), OTP_CIFRE, '0', STR_PAD_LEFT);
}

/**
 * Il codice e' giusto?
 *
 * @param int|null $ultimoPasso l'intervallo gia' speso da questo account (niente riuso)
 * @param int|null $passoUsato  ci finisce dentro l'intervallo accettato, da salvare
 */
function otp_verifica(string $segretoBase32, string $codice, ?int $ultimoPasso, ?int &$passoUsato = null): bool
{
    $codice = preg_replace('/\D/', '', $codice);
    if (strlen($codice) !== OTP_CIFRE) return false;

    $adesso = intdiv(time(), OTP_PASSO);
    for ($d = -OTP_TOLLERANZA; $d <= OTP_TOLLERANZA; $d++) {
        $passo = $adesso + $d;
        // Un intervallo gia' usato (o precedente all'ultimo buono) non vale piu': senza
        // questo controllo un codice letto alle spalle resterebbe buono per mezzo minuto.
        if ($ultimoPasso !== null && $passo <= $ultimoPasso) continue;
        if (hash_equals(otp_code($segretoBase32, $passo), $codice)) {
            $passoUsato = $passo;
            return true;
        }
    }
    return false;
}

/**
 * L'indirizzo che finisce dentro il QR: dice all'app come si chiama l'account, di che
 * sito e', e qual e' il segreto.
 */
function otp_uri(string $nomeUtente, string $segretoBase32): string
{
    $emittente = SITE_NAME;
    return 'otpauth://totp/' . rawurlencode($emittente . ':' . $nomeUtente)
        . '?secret=' . $segretoBase32
        . '&issuer=' . rawurlencode($emittente)
        . '&algorithm=SHA1&digits=' . OTP_CIFRE . '&period=' . OTP_PASSO;
}

/** Il segreto scritto a gruppi di quattro, per chi lo digita a mano invece di inquadrare. */
function otp_segreto_leggibile(string $segretoBase32): string
{
    return trim(chunk_split($segretoBase32, 4, ' '));
}

// ---------------------------------------------------------------------------------------
// IL SEGRETO NEL DATABASE — cifrato, mai in chiaro
// ---------------------------------------------------------------------------------------

/** true se in config.php c'e' una chiave utilizzabile (32 byte in base64). */
function otp_chiave_pronta(): bool
{
    if (!defined('OTP_CHIAVE')) return false;
    $k = base64_decode((string) OTP_CHIAVE, true);
    return $k !== false && strlen($k) === 32;
}

/**
 * Cifra il segreto per metterlo nel database.
 *
 * Senza la chiave in config.php il segreto finirebbe in chiaro, e allora e' meglio
 * fermarsi: attivare una protezione che si smonta con una lettura del database darebbe
 * solo l'illusione di essere protetti. Il gestionale lo dice a chiare lettere.
 */
function otp_cifra(string $segreto): string
{
    if (!otp_chiave_pronta()) {
        throw new RuntimeException('OTP_CHIAVE mancante o non valida in config.php.');
    }
    $chiave = base64_decode((string) OTP_CHIAVE, true);
    $iv = random_bytes(12);
    $tag = '';
    $cifrato = openssl_encrypt($segreto, 'aes-256-gcm', $chiave, OPENSSL_RAW_DATA, $iv, $tag);
    // iv + marchio di integrita' + testo cifrato, tutto in una stringa sola
    return base64_encode($iv . $tag . $cifrato);
}

/** Il contrario: dal database al segreto. null se la chiave e' sbagliata o il dato e' rotto. */
function otp_decifra(?string $dato): ?string
{
    if (!$dato || !otp_chiave_pronta()) return null;
    $grezzo = base64_decode($dato, true);
    if ($grezzo === false || strlen($grezzo) < 29) return null;

    $chiave = base64_decode((string) OTP_CHIAVE, true);
    $iv = substr($grezzo, 0, 12);
    $tag = substr($grezzo, 12, 16);
    $cifrato = substr($grezzo, 28);
    $segreto = openssl_decrypt($cifrato, 'aes-256-gcm', $chiave, OPENSSL_RAW_DATA, $iv, $tag);
    return $segreto === false ? null : $segreto;
}

// ---------------------------------------------------------------------------------------
// CHI DEVE USARLA
// ---------------------------------------------------------------------------------------

/**
 * Questo account deve passare dal codice a sei cifre?
 *
 * I web-admin sempre: sono i super-utenti, possono cambiare l'aspetto del sito, i prezzi
 * dello store e i ruoli di tutti gli altri. Lo staff con permessi delegati solo se
 * l'interruttore nel gestionale e' acceso.
 */
function otp_serve_per(?array $utente): bool
{
    if (!$utente) return false;
    if ((int) ($utente['is_admin'] ?? 0) === 1) return true;

    if (site_setting('otp_staff_obbligatorio', '0') !== '1') return false;

    // Staff = chi ha almeno un permesso del gestionale. Si guarda l'utente passato, che
    // non e' detto sia quello collegato (serve anche negli elenchi del gestionale).
    return otp_user_is_staff($utente);
}

/** True se l'utente ha almeno un permesso del gestionale (per i suoi gruppi di gioco). */
function otp_user_is_staff(array $utente): bool
{
    require_once __DIR__ . '/permissions.php';

    // I gruppi di gioco stanno in mc_ranks e di solito arrivano gia' uniti alla riga
    // dell'utente. Ma /login e /set-password leggono la tabella `users` e basta: li' la
    // colonna non c'e', e senza questo ripiego uno dello staff passerebbe liscio.
    if (!array_key_exists('groups_json', $utente) && !empty($utente['mc_uuid'])) {
        $q = db()->prepare('SELECT groups_json FROM mc_ranks WHERE mc_uuid = ?');
        $q->execute([$utente['mc_uuid']]);
        $utente['groups_json'] = $q->fetchColumn() ?: null;
    }

    $gruppi = user_groups($utente);
    if (!$gruppi) return false;

    $segnaposto = implode(',', array_fill(0, count($gruppi), '?'));
    $q = db()->prepare("SELECT COUNT(*) FROM web_group_permissions WHERE group_name IN ($segnaposto)");
    $q->execute($gruppi);
    return (int) $q->fetchColumn() > 0;
}

/** True se l'account ha gia' attivato la verifica. */
function otp_enabled(?array $utente): bool
{
    return $utente !== null && !empty($utente['totp_secret']) && !empty($utente['totp_activated_at']);
}

// ---------------------------------------------------------------------------------------
// TENTATIVI SBAGLIATI
// ---------------------------------------------------------------------------------------

/** Secondi che mancano alla fine del blocco, 0 se l'account non e' bloccato. */
function otp_blocco_residuo(array $utente): int
{
    $fino = $utente['totp_locked_until'] ?? null;
    if (!$fino) return 0;
    return max(0, strtotime((string) $fino) - time());
}

/** Segna uno sbaglio e, arrivati al limite, chiude la porta per un quarto d'ora. */
function otp_segna_errore(int $userId): void
{
    db()->prepare(
        'UPDATE users
            SET totp_attempts = totp_attempts + 1,
                totp_locked_until = IF(totp_attempts + 1 >= ?, DATE_ADD(NOW(), INTERVAL ? MINUTE), totp_locked_until)
          WHERE id = ?'
    )->execute([OTP_TENTATIVI_MAX, OTP_BLOCCO_MINUTI, $userId]);
}

/** Accesso riuscito: il conto degli sbagli riparte da zero. */
function otp_azzera_errori(int $userId): void
{
    db()->prepare('UPDATE users SET totp_attempts = 0, totp_locked_until = NULL WHERE id = ?')
        ->execute([$userId]);
}

// ---------------------------------------------------------------------------------------
// CODICI DI RECUPERO — la via d'uscita quando il telefono non c'e' piu'
// ---------------------------------------------------------------------------------------

/**
 * Genera (e sostituisce) i codici di recupero di un account.
 * Li restituisce in chiaro UNA volta sola: nel database ne resta solo l'hash.
 *
 * @return string[]
 */
function otp_generate_recovery(int $userId): array
{
    db()->prepare('DELETE FROM otp_recovery_codes WHERE user_id = ?')->execute([$userId]);

    $codici = [];
    $ins = db()->prepare('INSERT INTO otp_recovery_codes (user_id, code_hash) VALUES (?, ?)');
    for ($i = 0; $i < OTP_CODICI_RECUPERO; $i++) {
        // Dieci caratteri dell'alfabeto base32 (niente lettere che si confondono con le
        // cifre), spezzati a meta' per poterli leggere ad alta voce senza sbagliare.
        $grezzo = '';
        for ($c = 0; $c < 10; $c++) {
            $grezzo .= OTP_ALFABETO[random_int(0, strlen(OTP_ALFABETO) - 1)];
        }
        $codice = substr($grezzo, 0, 5) . '-' . substr($grezzo, 5);
        $codici[] = $codice;
        $ins->execute([$userId, password_hash($codice, PASSWORD_BCRYPT)]);
    }
    return $codici;
}

/** Quanti codici di recupero sono ancora spendibili. */
function otp_recupero_rimasti(int $userId): int
{
    $q = db()->prepare('SELECT COUNT(*) FROM otp_recovery_codes WHERE user_id = ? AND used_at IS NULL');
    $q->execute([$userId]);
    return (int) $q->fetchColumn();
}

/**
 * Spende un codice di recupero, se e' giusto. Ogni codice vale una volta sola.
 *
 * Il confronto va per forza fatto uno per uno: gli hash delle password hanno un sale
 * diverso ciascuno, quindi non si puo' cercare l'hash nel database.
 */
function otp_usa_recupero(int $userId, string $codice): bool
{
    $codice = strtoupper(trim($codice));
    if ($codice === '') return false;

    $q = db()->prepare('SELECT id, code_hash FROM otp_recovery_codes WHERE user_id = ? AND used_at IS NULL');
    $q->execute([$userId]);
    foreach ($q->fetchAll() as $riga) {
        if (password_verify($codice, $riga['code_hash'])) {
            db()->prepare('UPDATE otp_recovery_codes SET used_at = NOW() WHERE id = ?')
                ->execute([$riga['id']]);
            return true;
        }
    }
    return false;
}

// ---------------------------------------------------------------------------------------
// ATTIVAZIONE E AZZERAMENTO
// ---------------------------------------------------------------------------------------

/** Mette il segreto in cassaforte e accende la verifica per quell'account. */
function otp_enable(int $userId, string $segretoBase32, int $passoUsato): void
{
    db()->prepare(
        'UPDATE users
            SET totp_secret = ?, totp_activated_at = NOW(), totp_last_step = ?,
                totp_attempts = 0, totp_locked_until = NULL
          WHERE id = ?'
    )->execute([otp_cifra($segretoBase32), $passoUsato, $userId]);
}

/**
 * Spegne la verifica e butta via segreto e codici di recupero.
 *
 * Serve a chi ha perso il telefono: al prossimo accesso l'account rifa' l'attivazione da
 * capo, con un segreto nuovo. Siccome per un web-admin la verifica e' obbligatoria, non
 * resta comunque scoperto: gli viene solo richiesto di riconfigurarla.
 */
function otp_azzera(int $userId): void
{
    db()->prepare(
        'UPDATE users SET totp_secret = NULL, totp_activated_at = NULL, totp_last_step = NULL,
                          totp_attempts = 0, totp_locked_until = NULL WHERE id = ?'
    )->execute([$userId]);
    db()->prepare('DELETE FROM otp_recovery_codes WHERE user_id = ?')->execute([$userId]);
}

// ---------------------------------------------------------------------------------------
// LA SESSIONE DI GIOCO — "sono gia' passato dal codice, su questo server e da questa rete"
// ---------------------------------------------------------------------------------------

/**
 * Le verifiche in gioco ancora valide per un account.
 *
 * In gioco il codice non si chiede a ogni ingresso: dopo una verifica riuscita il server
 * segna account + indirizzo di rete e per qualche ora lascia passare (vedi `sessione-ore`
 * nella configurazione del plugin). Queste sono quelle righe.
 *
 * @return array<int,array{ip:string,verified_at:string}>
 */
function otp_sessioni_gioco(?string $mcUuid, int $ore = 12): array
{
    if (!$mcUuid) return [];
    try {
        // Le sessioni di gioco le tiene MagixAuth in `auth_sessions`, una riga per
        // DISPOSITIVO e non per indirizzo: l'IP cambia da solo sulle linee mobili, e in una
        // stessa casa e' condiviso da tutti. La scadenza e' scritta nella riga, quindi $ore
        // non serve piu' — resta nella firma solo per non toccare chi la chiama.
        $q = db()->prepare('SELECT ip, COALESCE(otp_ok_at, password_ok_at) AS verified_at
                              FROM auth_sessions
                             WHERE mc_uuid = ? AND expires_at > NOW()
                             ORDER BY verified_at DESC');
        $q->execute([$mcUuid]);
        return $q->fetchAll();
    } catch (PDOException) {
        return [];   // tabella non ancora creata: nessuna sessione da mostrare
    }
}

/**
 * Chiude la sessione di gioco: la fiducia salvata sparisce e, se il giocatore e' in partita
 * proprio adesso, il plugin lo ricongela entro pochi secondi chiedendogli di nuovo il codice.
 *
 * Le due cose insieme sono il punto: cancellare e basta avrebbe effetto solo dal prossimo
 * ingresso, e chi fosse gia' dentro con l'account continuerebbe a giocare indisturbato.
 */
function otp_close_game_session(?string $mcUuid, ?string $chiestoDa = null): bool
{
    if (!$mcUuid) return false;
    try {
        db()->prepare('DELETE FROM auth_sessions WHERE mc_uuid = ?')->execute([$mcUuid]);
        db()->prepare('INSERT INTO otp_game_revoke (mc_uuid, requested_by) VALUES (?, ?)
                       ON DUPLICATE KEY UPDATE requested_at = NOW(), requested_by = VALUES(requested_by)')
            ->execute([$mcUuid, $chiestoDa]);
        return true;
    } catch (PDOException) {
        return false;
    }
}

/**
 * L'indirizzo di rete accorciato: basta a riconoscere "casa" da "il PC di un amico",
 * senza stampare in chiaro un dato che non serve a nessuno per intero.
 */
function otp_ip_mascherato(string $ip): string
{
    if (str_contains($ip, ':')) {                       // IPv6
        $pezzi = explode(':', $ip);
        return $pezzi[0] . ':' . ($pezzi[1] ?? '') . ':…';
    }
    $pezzi = explode('.', $ip);
    return count($pezzi) === 4 ? $pezzi[0] . '.' . $pezzi[1] . '.x.x' : $ip;
}
