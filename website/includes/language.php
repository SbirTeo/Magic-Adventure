<?php
// Lingua del sito: come per i gradi, MagixLanguage decide in gioco (GeoIP o /language set) e
// MagixWeb la scrive in mc_ranks.language (vedi includes/auth.php, current_user()). Un
// visitatore che non ha mai giocato, o che il sito vede prima di riconoscerlo, sceglie da solo
// con il selettore in pagina (?lingua=xx), oppure riceve il tentativo migliore dal browser.
require_once __DIR__ . '/auth.php';

const SITE_LANGUAGES = ['it', 'en', 'es', 'de'];
const LANGUAGE_COOKIE = 'ma_lingua';

/**
 * La lingua di questa richiesta. Ordine: scelta esplicita in pagina (?lingua=, sticky nel
 * cookie), poi il cookie da una scelta precedente, poi — solo se non ha mai scelto lui stesso —
 * la lingua di gioco di chi e' collegato, poi il browser, infine l'italiano.
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
    if (is_string($scelta) && in_array($scelta, SITE_LANGUAGES, true)) {
        $_SESSION['lingua_sito'] = $scelta;
        setcookie(LANGUAGE_COOKIE, $scelta, [
            'expires' => time() + 365 * 24 * 3600,
            'path' => '/',
            'secure' => (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
                || ($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https',
            'httponly' => false, // il selettore in pagina la legge per evidenziare la scelta attuale
            'samesite' => 'Lax',
        ]);
        return $risolta = $scelta;
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

/** L'indirizzo della pagina corrente con ?lingua=$lang al posto di quella eventualmente gia' presente. */
function language_switch_url(string $lang): string {
    $uri = $_SERVER['REQUEST_URI'] ?? '/';
    $parti = parse_url($uri);
    $query = [];
    if (!empty($parti['query'])) {
        parse_str($parti['query'], $query);
    }
    $query['lingua'] = $lang;
    return ($parti['path'] ?? '/') . '?' . http_build_query($query);
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
