<?php
// Lingua nell'indirizzo: la stessa pagina in inglese sta su /en/..., in spagnolo su /es/..., in
// tedesco su /de/...; l'italiano, lingua di casa, senza prefisso. nginx (nginx-magicadventure.conf)
// toglie il prefisso prima di scegliere il file .php da eseguire; qui, prima che qualunque pagina
// guardi l'indirizzo, lo si toglie anche da $_SERVER['REQUEST_URI'] e lo si ricorda a parte:
// cosi' tutto il resto del sito (menu attivo, indirizzo canonico, pagine private...) continua a
// ragionare sul percorso italiano, uguale in ogni lingua, e non deve sapere niente dei prefissi.
//
// Caricato da db.php, che ogni pagina include per primo (anche passando da auth.php).

const URL_LANGUAGES = ['en', 'es', 'de'];

if (!isset($GLOBALS['__urlLang'])) {
    $GLOBALS['__urlLang'] = null;
    $__uri = (string) ($_SERVER['REQUEST_URI'] ?? '/');
    if (preg_match('#^/(en|es|de)(?=/|\?|$)#', $__uri, $__m)) {
        $GLOBALS['__urlLang'] = $__m[1];
        $__resto = substr($__uri, strlen($__m[0]));
        $_SERVER['REQUEST_URI'] = ($__resto === '' || $__resto[0] !== '/') ? '/' . $__resto : $__resto;
        // Un redirect scritto da una pagina ("Location: /profilo", dopo un login o un modulo)
        // resta nella lingua dell'indirizzo invece di ricadere su quello italiano.
        header_register_callback('localize_location_header');
    }
    unset($__uri, $__m, $__resto);
}

/**
 * Il percorso $path (italiano, senza prefisso, es. "/regolamento?page=2") nella lingua $lang.
 * Restano senza prefisso i percorsi che non sono pagine del sito: file (/assets, /sitemap.xml),
 * chiamate /api, e la gestionale, che e' sempre in italiano.
 */
function localized_path(string $lang, string $path): string {
    if ($lang === 'it' || !in_array($lang, URL_LANGUAGES, true) || !localizable_path($path)) {
        return $path;
    }
    return $path === '/' ? '/' . $lang : '/' . $lang . $path;
}

/** Se $path e' l'indirizzo di una pagina del sito a cui ha senso mettere il prefisso di lingua. */
function localizable_path(string $path): bool {
    if ($path === '' || $path[0] !== '/' || str_starts_with($path, '//')) {
        return false; // relativo, esterno (//host) o vuoto: si lascia com'e'
    }
    $solo = (string) parse_url($path, PHP_URL_PATH);
    if (preg_match('#^/(?:en|es|de)(?:/|$)#', $solo)) {
        return false; // ha gia' un prefisso
    }
    if (preg_match('#^/(?:assets|api|uploads)(?:/|$)#', $solo) || preg_match('#^/manage(?:\.php)?$#', $solo)) {
        return false;
    }
    // Un file vero (sitemap.xml, robots.txt, un'immagine): niente prefisso. Le pagine .php si',
    // anche se di solito i link sono gia' senza estensione.
    $ultimo = basename($solo);
    return !str_contains($ultimo, '.') || str_ends_with($ultimo, '.php');
}

/** Riscrive "Location: /..." nella lingua dell'indirizzo, se una pagina non l'ha gia' fatto. */
function localize_location_header(): void {
    $lang = $GLOBALS['__urlLang'] ?? null;
    if ($lang === null || !empty($GLOBALS['__locationLocalized'])) {
        return;
    }
    foreach (headers_list() as $riga) {
        if (stripos($riga, 'Location:') !== 0) {
            continue;
        }
        $dove = trim(substr($riga, strlen('Location:')));
        $nuovo = localized_path($lang, $dove);
        if ($nuovo !== $dove) {
            header('Location: ' . $nuovo, true);
        }
    }
}
