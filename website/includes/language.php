<?php
// Lingua del sito: come per i gradi, MagixLanguage decide in gioco (GeoIP o /language set) e
// MagixBridge la scrive in mc_ranks.language (vedi includes/auth.php, current_user()). Un
// visitatore che non ha mai giocato, o che il sito vede prima di riconoscerlo, sceglie da solo
// con il selettore in pagina (?lingua=xx), oppure riceve il tentativo migliore dal browser.
require_once __DIR__ . '/auth.php';

const SITE_LANGUAGES = ['it', 'en', 'es', 'de'];
const LANGUAGE_COOKIE = 'ma_lingua';

/**
 * La lingua di questa richiesta. Ordine: scelta esplicita in pagina (?lingua=, sticky nel
 * cookie), poi il prefisso dell'indirizzo (/en, /es, /de: vedi url-language.php), poi il cookie
 * da una scelta precedente, poi — solo se non ha mai scelto lui stesso — la lingua di gioco di chi
 * e' collegato, poi il browser, infine l'italiano. Un indirizzo SENZA prefisso che si risolve in
 * un'altra lingua viene poi mandato alla sua versione col prefisso: vedi language_redirect().
 *
 * ?lingua=auto cancella la scelta esplicita (sessione + cookie) invece di impostarla, cosi' chi
 * l'aveva fissata a mano puo' tornare a seguire la lingua di gioco (o il browser) senza dover
 * cancellare il cookie a mano dalle impostazioni del browser: vedi il link "Automatica" nel
 * selettore, header.php.
 *
 * La gestionale (manage.php) resta SEMPRE in italiano: e' per lo staff, che scrive e legge le
 * guide in italiano indipendentemente da dove si trovi chi la apre.
 */
function site_language(): string {
    static $risolta = null;
    if ($risolta !== null) {
        return $risolta;
    }
    if (basename($_SERVER['SCRIPT_NAME'] ?? '') === 'manage.php') {
        return $risolta = 'it';
    }

    $scelta = $_GET['lingua'] ?? null;
    if ($scelta === 'auto') {
        unset($_SESSION['lingua_sito']);
        setcookie(LANGUAGE_COOKIE, '', [
            'expires' => time() - 3600,
            'path' => '/',
            'secure' => (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
                || ($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https',
            'httponly' => false,
            'samesite' => 'Lax',
        ]);
        // Anche per il resto di QUESTA richiesta: il cookie cancellato sparisce dal browser solo
        // dalla prossima, e senza questo language_redirect() lo avrebbe ancora seguito.
        unset($_COOKIE[LANGUAGE_COOKIE]);
        $scelta = null; // da qui in poi come se non ci fosse mai stata una scelta esplicita
    } elseif (is_string($scelta) && in_array($scelta, SITE_LANGUAGES, true)) {
        $_SESSION['lingua_sito'] = $scelta;
        setcookie(LANGUAGE_COOKIE, $scelta, [
            'expires' => time() + 365 * 24 * 3600,
            'path' => '/',
            'secure' => (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
                || ($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https',
            'httponly' => false, // il selettore in pagina la legge per evidenziare la scelta attuale
            'samesite' => 'Lax',
        ]);
        $GLOBALS['__siteLangManual'] = true;
        return $risolta = $scelta;
    }

    // "Automatica" mostrata solo se c'e' davvero una scelta manuale da togliere.
    $GLOBALS['__siteLangManual'] = $scelta === null && (
        in_array($_SESSION['lingua_sito'] ?? null, SITE_LANGUAGES, true)
        || in_array($_COOKIE[LANGUAGE_COOKIE] ?? null, SITE_LANGUAGES, true)
    );

    // L'indirizzo dice la lingua (/en/...): vale lui, anche per chi ha scelto altro. Un link
    // /en/... mandato da un amico si apre in inglese senza cambiare la scelta salvata.
    if ($scelta === null && ($GLOBALS['__urlLang'] ?? null) !== null) {
        return $risolta = $GLOBALS['__urlLang'];
    }

    if (is_string($_SESSION['lingua_sito'] ?? null) && in_array($_SESSION['lingua_sito'], SITE_LANGUAGES, true)) {
        return $risolta = $_SESSION['lingua_sito'];
    }

    $cookie = $_COOKIE[LANGUAGE_COOKIE] ?? null;
    if (is_string($cookie) && in_array($cookie, SITE_LANGUAGES, true)) {
        return $risolta = $cookie;
    }

    $utente = current_user();
    if ($utente !== null && !empty($utente['language']) && in_array($utente['language'], SITE_LANGUAGES, true)) {
        return $risolta = $utente['language'];
    }

    return $risolta = browser_language() ?? 'it';
}

/**
 * L'indirizzo della pagina corrente in $lang, col prefisso giusto (/en/...) e ?lingua=$lang:
 * aprendolo la scelta si salva nel cookie e language_redirect() toglie subito il ?lingua=,
 * lasciando nella barra l'indirizzo pulito. "auto" va sull'indirizzo senza prefisso: li' la lingua
 * la decidono di nuovo gioco e browser.
 */
function language_switch_url(string $lang): string {
    $query = current_query_without_language();
    $query['lingua'] = $lang;
    $percorso = (string) (parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/');
    return localized_path($lang, $percorso) . '?' . http_build_query($query);
}

/** I parametri dell'indirizzo corrente, senza "lingua". */
function current_query_without_language(): array {
    $query = [];
    parse_str((string) (parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_QUERY) ?? ''), $query);
    unset($query['lingua']);
    return $query;
}

/**
 * Porta il visitatore all'indirizzo giusto per la lingua di questa pagina, se non ci e' gia':
 * dopo una scelta (?lingua=) all'indirizzo pulito senza il parametro, e da un indirizzo senza
 * prefisso alla sua versione /en, /es, /de quando la lingua risolta non e' l'italiano. Solo per
 * GET/HEAD (un redirect su una POST perderebbe il modulo) e mai per la gestionale.
 */
function language_redirect(string $lang): void {
    $metodo = $_SERVER['REQUEST_METHOD'] ?? 'GET';
    if (($metodo !== 'GET' && $metodo !== 'HEAD') || basename($_SERVER['SCRIPT_NAME'] ?? '') === 'manage.php') {
        return;
    }
    $haScelta = isset($_GET['lingua']);
    $inIndirizzo = $GLOBALS['__urlLang'] ?? null;
    if (!$haScelta && ($inIndirizzo !== null || $lang === 'it')) {
        return; // gia' al posto giusto
    }
    $percorso = (string) (parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/');
    $query = current_query_without_language();
    $dove = localized_path($lang, $percorso) . ($query ? '?' . http_build_query($query) : '');
    // Gia' nella lingua giusta: non passa dal callback che rimette il prefisso dell'indirizzo
    // (passando all'italiano da /en/... lo rimetterebbe, e si tornerebbe indietro).
    $GLOBALS['__locationLocalized'] = true;
    header('Cache-Control: no-store');
    header('Location: ' . $dove, true, 302);
    exit;
}

/**
 * Gli indirizzi della pagina in ogni lingua, per i <link rel="alternate" hreflang> che dicono a
 * Google che /en/regolamento e' la traduzione di /regolamento (e non un doppione). $canonico e'
 * l'indirizzo canonico italiano, assoluto.
 */
function language_alternates(string $canonico): array {
    $parti = parse_url($canonico);
    $base = ($parti['scheme'] ?? 'https') . '://' . ($parti['host'] ?? '');
    $percorso = ($parti['path'] ?? '/') . (isset($parti['query']) ? '?' . $parti['query'] : '');
    $out = [];
    foreach (SITE_LANGUAGES as $lang) {
        $out[$lang] = $base . localized_path($lang, $percorso);
    }
    return $out;
}

/** og:locale vuole il formato lingua_PAESE (es. en_US), non i due caratteri da soli. */
function og_locale(string $lang): string {
    return match ($lang) {
        'en' => 'en_US',
        'es' => 'es_ES',
        'de' => 'de_DE',
        default => 'it_IT',
    };
}

/**
 * Bandierina come SVG inline, stesso riquadro (24x16) per tutte. Non un'emoji: Windows non ha i
 * disegni delle bandiere nel suo font di sistema e mostra al loro posto le due lettere del
 * codice paese (IT, GB...) — che sembrano di nuovo delle sigle, il problema che dovevano
 * risolvere. Un SVG nostro si vede identico su qualunque sistema.
 */
function language_flag_svg(string $lang): string {
    $bandiere = [
        'it' => '<rect width="24" height="16" fill="#009246"/><rect x="8" width="8" height="16" fill="#fff"/><rect x="16" width="8" height="16" fill="#ce2b37"/>',
        'en' => '<rect width="24" height="16" fill="#00247d"/>'
            . '<path d="M0,0 L24,16 M24,0 L0,16" stroke="#fff" stroke-width="3.2"/>'
            . '<path d="M0,0 L24,16 M24,0 L0,16" stroke="#cf142b" stroke-width="1.1"/>'
            . '<path d="M12,0 V16 M0,8 H24" stroke="#fff" stroke-width="5.4"/>'
            . '<path d="M12,0 V16 M0,8 H24" stroke="#cf142b" stroke-width="3.2"/>',
        'es' => '<rect width="24" height="16" fill="#c60b1e"/><rect y="4" width="24" height="8" fill="#ffc400"/>',
        'de' => '<rect width="24" height="16" fill="#000"/><rect y="5.33" width="24" height="5.34" fill="#d00"/><rect y="10.67" width="24" height="5.33" fill="#ffce00"/>',
    ];
    $contenuto = $bandiere[$lang] ?? $bandiere['it'];
    return '<svg viewBox="0 0 24 16" class="bandiera-lingua" aria-hidden="true">' . $contenuto . '</svg>';
}

/**
 * Icona per l'opzione "Automatica" del selettore: stesso riquadro (24x16) delle bandiere, per
 * allinearsi nel menu, ma un piccolo orologio invece di una bandiera — segue la lingua di gioco
 * (o il browser, per chi non gioca) invece di restare fissa su una scelta.
 */
function language_auto_icon_svg(): string {
    return '<svg viewBox="0 0 24 16" class="bandiera-lingua" aria-hidden="true">'
        . '<circle cx="12" cy="8" r="6.5" fill="none" stroke="currentColor" stroke-width="1.5"/>'
        . '<path d="M12 8 L12 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>'
        . '<path d="M12 8 L15 9.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>'
        . '</svg>';
}

function browser_language(): ?string {
    $header = $_SERVER['HTTP_ACCEPT_LANGUAGE'] ?? '';
    foreach (explode(',', $header) as $parte) {
        $codice = strtolower(substr(trim(explode(';', $parte)[0]), 0, 2));
        if (in_array($codice, SITE_LANGUAGES, true)) {
            return $codice;
        }
    }
    return null;
}
