<?php
/**
 * Tutto quello che serve ai motori di ricerca e alle anteprime dei social.
 *
 * Le pagine non devono fare niente di speciale: basta che, PRIMA di includere
 * header.php, riempiano le variabili che gia' usavano ($page_title, $page_description)
 * piu' — se serve — queste, tutte facoltative:
 *
 *   $page_image      immagine per l'anteprima social (percorso o URL intero)
 *   $page_type       'website' (di serie) oppure 'article' / 'product'
 *   $page_noindex    true = "non mettermi su Google" (pagine private o senza senso da cercare)
 *   $page_canonical  indirizzo ufficiale della pagina, se diverso da quello chiesto
 *   $page_jsonld     una scheda dati strutturati, o un elenco di schede
 *   $page_published / $page_modified / $page_author   solo per gli articoli
 *
 * L'indirizzo ufficiale (canonical) serve perche' la stessa pagina si raggiunge in piu'
 * modi (con www, con .php in fondo, con ?utm_source= appiccicato dai social): senza,
 * Google le conta come pagine diverse e nessuna delle due sale.
 */

require_once __DIR__ . '/config.php';

/** Parametri che cambiano DAVVERO il contenuto: solo questi restano nell'indirizzo ufficiale. */
const SEO_PARAMETRI_VERI = ['slug', 'id', 'page', 'cat', 'categoria', 'q'];

/** Indirizzi che non hanno niente da fare su Google (roba privata o di servizio). */
const SEO_PERCORSI_PRIVATI = [
    '/manage', '/profilo', '/login', '/logout', '/link', '/set-password', '/otp',
    '/cambia-password', '/password-dimenticata',
    '/store/checkout', '/store/return', '/store/cancel',
    '/blog/new', '/forum/new_topic',
];

/** Da percorso a URL intero sul dominio ufficiale. Se e' gia' un URL intero, resta com'e'. */
function seo_url(string $percorso): string
{
    if ($percorso === '' || preg_match('#^https?://#i', $percorso)) {
        return $percorso;
    }
    return rtrim(SITE_URL, '/') . '/' . ltrim($percorso, '/');
}

/**
 * L'indirizzo ufficiale della pagina che si sta mostrando: dominio senza www, niente
 * .php in fondo, niente barra finale e solo i parametri che contano davvero.
 */
function seo_canonical(?string $forzato = null): string
{
    if ($forzato !== null && $forzato !== '') {
        return seo_url($forzato);
    }

    $uri = $_SERVER['REQUEST_URI'] ?? '/';
    $percorso = (string) (parse_url($uri, PHP_URL_PATH) ?: '/');
    $percorso = preg_replace('/\.php$/', '', $percorso);
    // /forum/index -> /forum, e la barra finale via (tranne la home, che e' solo "/")
    $percorso = preg_replace('#/index$#', '', $percorso);
    if ($percorso === '') $percorso = '/';
    if ($percorso !== '/') $percorso = rtrim($percorso, '/');

    parse_str((string) (parse_url($uri, PHP_URL_QUERY) ?? ''), $params);
    $tenuti = array_intersect_key($params, array_flip(SEO_PARAMETRI_VERI));
    // page=1 e' la stessa cosa di nessun page: toglierlo evita un doppione
    if (isset($tenuti['page']) && (string) $tenuti['page'] === '1') unset($tenuti['page']);
    ksort($tenuti);

    return seo_url($percorso) . ($tenuti ? '?' . http_build_query($tenuti) : '');
}

/** true se questa pagina va tenuta fuori dai motori di ricerca. */
function seo_da_nascondere(bool $forzato = false): bool
{
    if ($forzato) return true;
    $percorso = rtrim((string) (parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/'), '/');
    $percorso = preg_replace('/\.php$/', '', $percorso);
    foreach (SEO_PERCORSI_PRIVATI as $privato) {
        if ($percorso === $privato) return true;
    }
    // Pagina di errore: nessun motivo di indicizzarla
    return http_response_code() === 404;
}

/**
 * Riduce un testo qualsiasi (anche con HTML dentro) a una descrizione da meta tag:
 * niente marcatori, niente a capo, tagliata su una parola intera.
 */
function seo_riassunto(?string $testo, int $max = 160): string
{
    $t = trim(preg_replace('/\s+/u', ' ', strip_tags(html_entity_decode((string) $testo, ENT_QUOTES, 'UTF-8'))));
    if ($t === '' || mb_strlen($t) <= $max) return $t;
    $tagliato = mb_substr($t, 0, $max - 1);
    $spazio = mb_strrpos($tagliato, ' ');
    if ($spazio !== false && $spazio > $max * 0.6) $tagliato = mb_substr($tagliato, 0, $spazio);
    return rtrim($tagliato, " ,.;:-") . '…';
}

/** Data in formato ISO 8601, come la vogliono Google e i social. */
function seo_data(?string $sql): ?string
{
    if (!$sql) return null;
    $t = strtotime($sql);
    return $t ? date('c', $t) : null;
}

/** Stampa una scheda di dati strutturati (JSON-LD). */
function seo_jsonld(array $dati): string
{
    return '<script type="application/ld+json">'
        . json_encode($dati, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE)
        . '</script>';
}

/** Le briciole di pane ("Home › Forum › Categoria") come dati strutturati. */
function seo_briciole(array $voci): array
{
    $elementi = [];
    $n = 1;
    foreach ($voci as $nome => $percorso) {
        $elementi[] = [
            '@type' => 'ListItem',
            'position' => $n++,
            'name' => $nome,
            'item' => seo_url($percorso),
        ];
    }
    return ['@context' => 'https://schema.org', '@type' => 'BreadcrumbList', 'itemListElement' => $elementi];
}

/**
 * La carta d'identita' del sito: chi siamo (Organization) e cos'e' questo indirizzo
 * (WebSite). Va in ogni pagina una volta sola, la mette header.php.
 */
function seo_scheda_sito(string $nome, string $logo, string $descrizione): array
{
    return [
        '@context' => 'https://schema.org',
        '@graph' => [
            [
                '@type' => 'Organization',
                '@id' => seo_url('/#organizzazione'),
                'name' => $nome,
                'url' => seo_url('/'),
                'logo' => seo_url($logo),
                'description' => $descrizione,
            ],
            [
                '@type' => 'WebSite',
                '@id' => seo_url('/#sito'),
                'name' => $nome,
                'url' => seo_url('/'),
                'inLanguage' => 'it-IT',
                'publisher' => ['@id' => seo_url('/#organizzazione')],
            ],
        ],
    ];
}
