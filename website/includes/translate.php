<?php
// Traduzione automatica del sito: mai una chiamata di rete durante una richiesta (il sito
// incassa pagamenti veri, non puo' aspettare MyMemory). Una pagina che incontra una frase senza
// ancora una traduzione la mostra in italiano e la accoda in site_translations; un giro del
// plugin (MagixWeb, vedi language/SiteTranslationWorker.java) la traduce fuori banda, e la
// visita successiva la trova gia' pronta. Stessa quota giornaliera del plugin di gioco: vedi
// MagixLanguage/config.yml, translations.auto-translate.
require_once __DIR__ . '/db.php';

// Oltre questa lunghezza una frase non viene ne' tradotta ne' accodata: resta in italiano.
// Un paragrafo lungo o un blocco di codice finito per errore in un nodo di testo non deve
// diventare una riga di database da 50000 caratteri ne' una chiamata che MyMemory rifiuta comunque.
const TRANSLATABLE_MAX_LENGTH = 4000;

/**
 * Il testo tradotto in cache per $lang, o il testo italiano invariato se $lang e' 'it', il
 * testo non ha nulla da tradurre (vuoto, solo numeri/simboli), o la traduzione non e' ancora
 * pronta (viene accodata per il prossimo giro del plugin). Non lancia mai un'eccezione: un
 * database irraggiungibile fa semplicemente restare la pagina in italiano.
 */
function translate_phrase(string $text, string $lang): string {
    $trimmed = trim($text);
    if ($lang === 'it' || $trimmed === '' || !translatable_text($trimmed)) {
        return $text;
    }
    $translated = translate_batch([$trimmed], $lang);
    return $translated[$trimmed] ?? $text;
}

/**
 * Traduce il testo di ogni pagina in $lang, DOMDocument alla mano: cammina i nodi di testo (e
 * poche' attributi che un visitatore legge davvero: alt, placeholder, title, i meta di SEO) e
 * sostituisce quelli gia' in cache. Un errore di parsing o di database restituisce $html
 * invariato: un buco nella traduzione e' accettabile, una pagina rotta no.
 */
function translate_html(string $html, string $lang): string {
    if ($lang === 'it' || trim($html) === '') {
        return $html;
    }
    try {
        libxml_use_internal_errors(true);
        $dom = new DOMDocument();
        // mb_convert_encoding con HTML-ENTITIES evita il solito problema di DOMDocument che
        // interpreta l'HTML come Latin-1 quando non trova una dichiarazione di charset propria:
        // i caratteri accentati diventano entita' numeriche, che il browser mostra comunque bene.
        $ok = $dom->loadHTML(
            mb_convert_encoding($html, 'HTML-ENTITIES', 'UTF-8'),
            LIBXML_HTML_NOIMPLIED | LIBXML_HTML_NODEFDTD
        );
        libxml_clear_errors();
        if (!$ok) {
            return $html;
        }

        $xpath = new DOMXPath($dom);
        // Vale sia per i nodi di testo che per gli attributi: l'asse "ancestor" di un attributo
        // e' quello del suo elemento (che pero' NON include l'elemento stesso, per questo si
        // aggiunge anche "parent::*[...]" per chi mette data-no-tr sullo stesso tag).
        $noTranslate = 'ancestor::script or ancestor::style or ancestor::textarea '
            . 'or ancestor::code or ancestor::pre '
            . 'or ancestor-or-self::*[@data-no-tr] or ancestor-or-self::*[@translate="no"]';
        $textNodes = $xpath->query("//text()[not($noTranslate)]");
        $attrNodes = $xpath->query(
            "//@alt[not(parent::*[@data-no-tr] or parent::*[@translate='no'] or $noTranslate)] "
            . "| //@placeholder[not(parent::*[@data-no-tr] or parent::*[@translate='no'] or $noTranslate)] "
            . "| //@title[not(parent::*[@data-no-tr] or parent::*[@translate='no'] or $noTranslate)] "
            . '| //meta[@name="description"]/@content '
            . '| //meta[@property="og:title"]/@content '
            . '| //meta[@property="og:description"]/@content'
        );

        // Un solo giro di raccolta, cosi' la ricerca/accodamento in site_translations e' UNA
        // query (piu' un solo batch di insert), non una per frase: una pagina puo' avere
        // centinaia di nodi di testo.
        $phrases = [];
        foreach ([$textNodes, $attrNodes] as $lista) {
            foreach ($lista as $nodo) {
                $trimmed = trim($nodo->nodeValue);
                if ($trimmed !== '' && translatable_text($trimmed)) {
                    $phrases[$trimmed] = true;
                }
            }
        }
        if (empty($phrases)) {
            return $html;
        }

        $translated = translate_batch(array_keys($phrases), $lang);
        if (empty($translated)) {
            return $html;
        }

        foreach ([$textNodes, $attrNodes] as $lista) {
            foreach ($lista as $nodo) {
                $originale = $nodo->nodeValue;
                $trimmed = trim($originale);
                if (!isset($translated[$trimmed])) {
                    continue;
                }
                // Si preservano gli spazi/interruzioni di riga intorno al testo: contano per
                // l'impaginazione (es. spazio fra due elementi inline).
                $prefisso = substr($originale, 0, strpos($originale, $trimmed));
                $suffisso = substr($originale, strpos($originale, $trimmed) + strlen($trimmed));
                $nodo->nodeValue = $prefisso . $translated[$trimmed] . $suffisso;
            }
        }

        $out = $dom->saveHTML();
        return $out !== false ? $out : $html;
    } catch (\Throwable $e) {
        error_log('translate_html: ' . $e->getMessage());
        return $html;
    }
}

/** Almeno una lettera dentro, e non spropositatamente lunga: filtra numeri, simboli, spazi soli. */
function translatable_text(string $text): bool {
    return strlen($text) <= TRANSLATABLE_MAX_LENGTH && preg_match('/\p{L}/u', $text) === 1;
}

/**
 * testo italiano (gia' rifilato) -> traduzione, presente solo per quelle gia' in cache. Le
 * frasi mai viste vengono accodate (status 'pending') per il prossimo giro del plugin.
 */
function translate_batch(array $texts, string $lang): array {
    $texts = array_values(array_unique($texts));
    if (empty($texts)) {
        return [];
    }
    try {
        $hashToText = [];
        foreach ($texts as $t) {
            $hashToText[sha1($t)] = $t;
        }
        $hashes = array_keys($hashToText);

        $segnaposto = implode(',', array_fill(0, count($hashes), '?'));
        $stmt = db()->prepare(
            "SELECT phrase_hash, translated_text, status FROM site_translations "
            . "WHERE lang = ? AND phrase_hash IN ($segnaposto)"
        );
        $stmt->execute([$lang, ...$hashes]);

        $out = [];
        $conosciuti = [];
        foreach ($stmt->fetchAll() as $riga) {
            $conosciuti[$riga['phrase_hash']] = true;
            if ($riga['status'] === 'done' && $riga['translated_text'] !== null) {
                $out[$hashToText[$riga['phrase_hash']]] = $riga['translated_text'];
            }
        }

        $daAccodare = array_diff($hashes, array_keys($conosciuti));
        if (!empty($daAccodare)) {
            $righe = [];
            $valori = [];
            foreach ($daAccodare as $hash) {
                $righe[] = '(?, ?, ?)';
                $valori[] = $lang;
                $valori[] = $hash;
                $valori[] = $hashToText[$hash];
            }
            db()->prepare(
                'INSERT IGNORE INTO site_translations (lang, phrase_hash, source_text) VALUES '
                . implode(', ', $righe)
            )->execute($valori);
        }

        return $out;
    } catch (\Throwable $e) {
        error_log('translate_batch: ' . $e->getMessage());
        return [];
    }
}
