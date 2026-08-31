<?php
/**
 * Ricerca nella guida: si scrive una domanda in italiano, si riceve la risposta.
 *
 * NON c'e' nessun servizio esterno e nessuna chiave da pagare: il motore sta tutto qui.
 * Fa tre cose, in quest'ordine:
 *
 *   1. SPEZZA la guida in passaggi — un paragrafo, un elenco, un riquadro, una riga della
 *      tabella dei comandi — tenendosi per ognuno capitolo, sotto-titolo e ancora;
 *   2. PESA ogni passaggio rispetto alla domanda: le parole rare valgono piu' delle comuni,
 *      il titolo del capitolo vale piu' del corpo, i comandi (/f home) contano come parole
 *      a se', e chi copre PIU' parole della domanda vince su chi ne ripete una sola;
 *   3. COMPONE la risposta col passaggio migliore, ripreso alla lettera dalla guida, e
 *      rimanda al capitolo da cui viene.
 *
 * La conseguenza che conta: non puo' INVENTARE. Ogni parola che legge il giocatore e'
 * scritta nella guida; se una cosa nella guida non c'e', la ricerca lo dice e basta.
 * (E' il motivo per cui il testo viene citato e non riscritto: una guida che si contraddice
 * da sola e' peggio di nessuna guida.)
 *
 * Lo stesso motore serve due guide diverse:
 *   - 'pubblica' → assets/guida/magixfactions.html, la guida dei giocatori mostrata in
 *                  /tutorial (file autonomo generato da build_tutorial.py: NON si tocca);
 *   - 'staff'    → i capitoli che ogni plugin pubblica nel gestionale (tabella guide_staff).
 *
 * Chi lo chiama: public/api/guida-cerca.php.
 */

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/helpers.php';

/** Numero massimo di passaggi citati in una risposta. */
const GUIDA_IA_MAX_PASSAGGI = 3;
/** Numero massimo di capitoli suggeriti sotto la risposta. */
const GUIDA_IA_MAX_CORRELATI = 3;
/** Sotto questa copertura della domanda la risposta non si da': meglio ammettere. */
const GUIDA_IA_COPERTURA_MINIMA = 0.34;

// =====================================================================
//  VOCABOLARIO
// =====================================================================

/**
 * Parole che non distinguono un passaggio dall'altro: se le pesassimo, "come si fa a
 * conquistare" premierebbe i paragrafi pieni di "si" e "a" invece di quelli su "conquistare".
 * Ci stanno dentro anche le parole interrogative: servono a capire il TIPO di domanda
 * (vedi guida_ia_apertura), non a cercare.
 */
function guida_ia_stopword(): array {
    static $set = null;
    if ($set === null) {
        $set = array_flip(preg_split('~\s+~', trim('
            a ad ai al alla alle allo agli all anche ancora avere aveva c ce che chi ci
            cio cioe come con contro cosa cosi cui da dai dal dalla dalle dallo degli dei del
            della delle dello di dove due e ed essere fa fai fare fanno gli ha hai hanno ho i
            il in io la le lei li lo loro ma me mi mia mie miei mio molto ne nei nel nella
            nelle nello no noi non nostra nostro o ogni oppure per perche piu po posso possono
            potere puo puoi qual quale quali quando quanta quante quanti quanto quel quella
            quelle quelli quello questa queste questi questo qui sara se sei senza si sia
            siamo sono sopra sotto sta stai stanno su sua sue sui sul sulla sulle sullo suo
            ti tra tu tua tue tuo tuoi tutti tutto un una uno vi voi vuoi
            serve servono servire serva serviva usare usa uso
        ')));
    }
    return $set;
}

/**
 * Il lessico del server: quello che il giocatore SCRIVE non e' sempre quello che la guida
 * USA. Chi chiede "come rubo un terreno" non trovera' mai la parola "terreno" nella guida
 * (li' si chiama "territorio"), e chi arriva da altri server dira' "claim" e "power".
 *
 * Vale in una direzione sola — dalla domanda alla guida — e con peso ridotto: un sinonimo
 * indovinato da noi non deve mai contare quanto una parola scritta davvero.
 */
function guida_ia_sinonimi(): array {
    return [
        // --- territori --------------------------------------------------
        'chunk' => ['territorio', 'terreno'],
        'claim' => ['conquistare', 'territorio'],
        'claimare' => ['conquistare', 'territorio'],
        'terreno' => ['territorio', 'chunk'],
        'terra' => ['territorio'],
        'zona' => ['territorio'],
        'pezzo' => ['chunk', 'territorio'],
        // "base" per un giocatore e' il posto dove torna, non il territorio in generale:
        // mandarlo sui territori gli fa leggere la pagina sbagliata.
        'base' => ['casa', 'home'],
        'proteggere' => ['protezione', 'territorio'],
        'protetto' => ['protezione', 'territorio'],
        'costruire' => ['protezione', 'territorio'],
        'rubare' => ['conquistare', 'nemico'],
        'perdere' => ['decadimento', 'sovraccarico'],
        'sovraccarico' => ['decadimento'],
        // --- potenza ----------------------------------------------------
        'power' => ['potenza'],
        'morire' => ['morte', 'potenza'],
        'muoio' => ['morte', 'potenza'],
        'uccidere' => ['morte', 'potenza'],
        'risalire' => ['potenza', 'recupero'],
        // --- comandi e luoghi -------------------------------------------
        'home' => ['casa'],
        'casa' => ['home'],
        'tp' => ['home', 'casa', 'teletrasporto'],
        'teletrasporto' => ['home', 'casa'],
        'map' => ['mappa'],
        'mappa' => ['map', 'minimappa'],
        'minimappa' => ['mappa', 'map'],
        // --- fazione e persone ------------------------------------------
        'ally' => ['alleato', 'alleanza'],
        'alleanza' => ['alleato', 'ally'],
        'alleato' => ['alleanza', 'ally'],
        'enemy' => ['nemico'],
        'nemico' => ['enemy', 'guerra'],
        'guerra' => ['nemico', 'enemy'],
        'rank' => ['grado'],
        'grado' => ['rank', 'permesso'],
        'permesso' => ['grado'],
        'capo' => ['leader'],
        'leader' => ['capo'],
        'invito' => ['invitare', 'invite'],
        'invitare' => ['invite', 'membro'],
        'entrare' => ['join', 'membro'],
        'espellere' => ['kick', 'membro'],
        'cacciare' => ['kick', 'espellere'],
        'fondare' => ['creare', 'create'],
        'creare' => ['create', 'fondare'],
        'sciogliere' => ['disband'],
        'canale' => ['chat'],
        'messaggio' => ['chat'],
        'squadra' => ['fazione'],
        'gilda' => ['fazione'],
        'clan' => ['fazione'],
        // --- gestionale (guida per amministratori) ----------------------
        'ban' => ['sanzione', 'bannare'],
        'mute' => ['sanzione', 'silenziare'],
        'sanzione' => ['ban', 'mute', 'punti'],
        'punizione' => ['sanzione', 'ban'],
        'config' => ['configurazione', 'impostazione'],
        'configurazione' => ['config', 'impostazione'],
        'impostazione' => ['config', 'configurazione'],
        'errore' => ['guasto', 'problema'],
        'problema' => ['guasto', 'errore'],
        'installare' => ['installazione', 'avvio'],
    ];
}

// =====================================================================
//  DALLE PAROLE ALLE RADICI
// =====================================================================

/** Minuscole e accenti via: "Potenza", "potenza" e "poténza" devono essere la stessa cosa. */
function guida_ia_normalizza(string $testo): string {
    $testo = mb_strtolower($testo, 'UTF-8');
    return strtr($testo, [
        'à' => 'a', 'á' => 'a', 'â' => 'a', 'ä' => 'a',
        'è' => 'e', 'é' => 'e', 'ê' => 'e', 'ë' => 'e',
        'ì' => 'i', 'í' => 'i', 'î' => 'i', 'ï' => 'i',
        'ò' => 'o', 'ó' => 'o', 'ô' => 'o', 'ö' => 'o',
        'ù' => 'u', 'ú' => 'u', 'û' => 'u', 'ü' => 'u',
        'ç' => 'c', 'ñ' => 'n', '’' => "'", '‘' => "'",
    ]);
}

/**
 * Le parole di un testo. I comandi restano interi ("/f") perche' sono il modo piu' preciso
 * di indovinare la risposta: chi scrive "/f sethome" sa gia' cosa cerca.
 */
function guida_ia_parole(string $testo): array {
    preg_match_all('~/[a-z]{1,14}|[a-z][a-z0-9]+~', guida_ia_normalizza($testo), $trovate);
    return $trovate[0];
}

/**
 * La radice di una parola, per far combaciare "territori" con "territorio" e "conquistare"
 * con "conquista". Non e' uno stemmer serio (quelli sbagliano in modo piu' elegante): toglie
 * le desinenze italiane piu' frequenti e poi le vocali finali, ma solo finche' resta
 * abbastanza parola da distinguerla dalle altre.
 */
function guida_ia_radice(string $parola): string {
    if ($parola === '' || $parola[0] === '/') {
        return $parola;                       // i comandi non si tagliano mai
    }
    foreach (['issimo', 'issima', 'issimi', 'issime', 'amento', 'amenti', 'azione', 'azioni',
              'mente', 'ando', 'endo', 'are', 'ere', 'ire', 'ato', 'ata', 'ati', 'ate',
              'ito', 'ita', 'iti', 'ite', 'uto', 'uta'] as $desinenza) {
        if (str_ends_with($parola, $desinenza) && strlen($parola) - strlen($desinenza) >= 5) {
            $parola = substr($parola, 0, -strlen($desinenza));
            break;
        }
    }
    $vocali = function (string $p): string {
        while (strlen($p) > 4 && str_contains('aeio', substr($p, -1))) {
            $p = substr($p, 0, -1);
        }
        return $p;
    };
    $accorciata = $vocali($parola);
    // La erre finale rimasta dagli infiniti: senza questo passaggio "creare" diventa "crear"
    // e non incontra mai "crea", che e' proprio la parola che il giocatore scrive.
    //
    // Si tocca SOLO se prima e' caduta una vocale, cioe' se la parola finiva per vocale: le
    // parole che gia' finiscono per erre non sono infiniti tagliati, sono parole. Senza
    // questa cautela "server" diventava "serv" e rispondeva alle domande su "a cosa serve".
    if ($accorciata !== $parola && strlen($accorciata) > 4 && str_ends_with($accorciata, 'r')) {
        $accorciata = $vocali(substr($accorciata, 0, -1));
    }
    return $accorciata;
}

/** Conteggio delle radici di un testo: [radice => quante volte]. */
function guida_ia_conta(string $testo): array {
    $conteggio = [];
    $stopword = guida_ia_stopword();
    foreach (guida_ia_parole($testo) as $parola) {
        if (isset($stopword[$parola]) || strlen($parola) < 2) {
            continue;
        }
        $radice = guida_ia_radice($parola);
        $conteggio[$radice] = ($conteggio[$radice] ?? 0) + 1;
    }
    return $conteggio;
}

// =====================================================================
//  LA GUIDA A PEZZI
// =====================================================================

/** Testo semplice da un frammento di HTML (entita' sciolte, spazi normalizzati). */
function guida_ia_testo(string $html): string {
    $testo = preg_replace('~<(script|style)\b[^>]*>.*?</\1>~is', ' ', $html);
    $testo = preg_replace('~<[^>]+>~', ' ', (string) $testo);
    $testo = html_entity_decode((string) $testo, ENT_QUOTES | ENT_HTML5, 'UTF-8');
    return trim(preg_replace('~\s+~u', ' ', $testo) ?? '');
}

/**
 * Il passaggio come lo vedra' chi legge: si tengono solo grassetti, corsivi, comandi e righe
 * di elenco. Tutto il resto si butta — la guida porta con se' un foglio di stile suo
 * (riquadri, barre della potenza, tabelle) che dentro la risposta non c'entra nulla, e gli
 * attributi che sopravvivono a strip_tags sarebbero l'unico modo di infilare qui roba non
 * voluta.
 */
function guida_ia_ripulisci(string $html): string {
    // I comandi della guida sono <span class="cmd">: diventano <code>, che il sito veste gia'.
    $html = preg_replace('~<span\s+class="cmd"[^>]*>(.*?)</span>~is', '<code>$1</code>', $html);
    // Le voci di elenco diventano righe col punto elenco davanti: niente <ul> a meta'.
    $html = preg_replace('~<li\b[^>]*>~i', '<br>• ', (string) $html);
    // Le celle di una riga di tabella si separano con un trattino ("comando — cosa fa").
    $html = preg_replace('~</t[dh]>\s*<t[dh][^>]*>~i', ' — ', (string) $html);
    $html = strip_tags((string) $html, '<b><strong><i><em><code><br>');
    // Via ogni attributo dai tag rimasti: restano sei tag nudi, e nient'altro.
    $html = preg_replace('~<\s*(b|strong|i|em|code|br)\b[^>]*>~i', '<$1>', (string) $html);
    $html = preg_replace('~^(\s|<br>)+~i', '', (string) $html);
    return trim(preg_replace('~\s+~u', ' ', (string) $html) ?? '');
}

/**
 * Spezza il corpo di un capitolo nei suoi passaggi.
 *
 * Un passaggio e' l'unita' piu' piccola che si regge da sola: un paragrafo, un elenco, un
 * riquadro col consiglio, una riga della tabella dei comandi. La tabella si spezza riga per
 * riga di proposito: a "cosa fa /f unclaimall" si deve poter rispondere con quella riga li',
 * non con l'intera tabella dei venti comandi.
 *
 * @param string $corpo    HTML del capitolo
 * @param string $tagSotto tag dei sotto-titoli: h3 nella guida dei giocatori, h4 in quella dello staff
 */
function guida_ia_passaggi(string $corpo, string $tagSotto = 'h3'): array {
    $passaggi = [];
    $sotto = '';
    $schema = '~<' . $tagSotto . '\b[^>]*>(?P<sotto>.*?)</' . $tagSotto . '>'
            . '|<p\b[^>]*>(?P<p>.*?)</p>'
            . '|<(?P<lista>ul|ol)\b[^>]*>(?P<voci>.*?)</(?P=lista)>'
            . '|<div\s+class="(?:tip|warn|nota|guida-nota|guida-mai)"[^>]*>(?P<box>.*?)</div>'
            . '|<tr\b[^>]*>(?P<riga>.*?)</tr>~is';

    preg_match_all($schema, $corpo, $blocchi, PREG_SET_ORDER);
    foreach ($blocchi as $b) {
        if (($b['sotto'] ?? '') !== '') {
            $sotto = guida_ia_testo($b['sotto']);
            continue;
        }
        $html = '';
        foreach (['p', 'voci', 'box', 'riga'] as $tipo) {
            if (($b[$tipo] ?? '') !== '') {
                $html = $b[$tipo];
                break;
            }
        }
        $pulito = guida_ia_ripulisci($html);
        $testo = guida_ia_testo($html);
        // Scarti: intestazioni di tabella, "↑ Torna su", righe rimaste vuote.
        if (mb_strlen($testo) < 12 || preg_match('~^(comando\s|cosa fa|torna su)~iu', $testo)) {
            continue;
        }
        $passaggi[] = [
            'sotto' => $sotto,
            'html'  => $pulito,
            'testo' => $testo,
            // Le righe della tabella dei comandi si segnano: sono un promemoria di una riga,
            // non la spiegazione. A parita' di punteggio deve vincere il capitolo che spiega.
            'riga'  => ($b['riga'] ?? '') !== '',
            'tf'    => guida_ia_conta($testo),
            'tfs'   => guida_ia_conta($sotto),
        ];
    }
    return $passaggi;
}

/** Percorso della guida dei giocatori (il file che il server riscrive da solo). */
function guida_ia_file_pubblico(): string {
    return __DIR__ . '/../public/assets/guida/magixfactions.html';
}

/** I capitoli della guida dei giocatori. */
function guida_ia_capitoli_pubblici(): array {
    $file = guida_ia_file_pubblico();
    if (!is_file($file)) {
        return [];
    }
    $html = (string) file_get_contents($file);
    $capitoli = [];
    preg_match_all('~<section\s+id="([^"]+)"[^>]*>(.*?)</section>~is', $html, $sezioni, PREG_SET_ORDER);
    foreach ($sezioni as $s) {
        [, $ancora, $corpo] = $s;
        if (!preg_match('~<h2[^>]*>(.*?)</h2>~is', $corpo, $t)) {
            continue;
        }
        $numero = preg_match('~<span class="n">(\d+)</span>~', $t[1], $n) ? $n[1] : '';
        $titolo = guida_ia_testo(preg_replace('~<span class="n">.*?</span>~is', '', $t[1]) ?? '');
        $passaggi = guida_ia_passaggi($corpo, 'h3');
        if (!$passaggi) {
            continue;
        }
        $capitoli[] = [
            'ancora'   => $ancora,
            'numero'   => $numero,
            'titolo'   => $titolo,
            'gruppo'   => '',
            'tf'       => guida_ia_conta($titolo),
            'passaggi' => $passaggi,
        ];
    }
    return $capitoli;
}

/**
 * I capitoli della guida per amministratori. Qui un "capitolo" per la ricerca e' il singolo
 * blocco <h4> dentro il capitolo di un plugin ("Tutti i comandi", "Quando qualcosa non va"…):
 * cosi' la risposta rimanda al punto giusto e non a mezzo manuale. L'ancora resta pero'
 * quella del plugin, che e' l'unico id che esiste davvero nella pagina.
 */
function guida_ia_capitoli_staff(): array {
    require_once __DIR__ . '/sanzioni.php';
    $capitoli = [];
    foreach (guide_staff_capitoli() as $c) {
        $corpo = (string) $c['body_html'];
        $titoloPlugin = (string) $c['title'];
        $ancora = 'guida-' . $c['plugin'];

        // Il testo prima del primo <h4> e' l'introduzione del plugin: vale come blocco suo.
        $pezzi = preg_split('~(?=<h4\b)~i', $corpo) ?: [];
        foreach ($pezzi as $pezzo) {
            $sottoTitolo = preg_match('~<h4[^>]*>(.*?)</h4>~is', $pezzo, $h) ? guida_ia_testo($h[1]) : '';
            $passaggi = guida_ia_passaggi($pezzo, 'h5');
            if (!$passaggi) {
                continue;
            }
            $capitoli[] = [
                'ancora'   => $ancora,
                'numero'   => '',
                'titolo'   => $sottoTitolo !== '' ? $sottoTitolo : 'In breve',
                'gruppo'   => $titoloPlugin,
                // Il nome del plugin pesa come il titolo: "come configuro MagixTime" deve
                // portare al capitolo di MagixTime, non a quello che nomina i minuti.
                'tf'       => guida_ia_conta($sottoTitolo . ' ' . $titoloPlugin . ' ' . $c['plugin']),
                'passaggi' => $passaggi,
            ];
        }
    }
    return $capitoli;
}

// =====================================================================
//  L'INDICE (e la sua cache)
// =====================================================================

/**
 * L'indice completo di una guida: capitoli, passaggi e in quanti passaggi compare ogni
 * radice (serve a capire quali parole sono rare, cioe' quali contano davvero).
 */
function guida_ia_costruisci(string $ambito): array {
    $capitoli = $ambito === 'staff' ? guida_ia_capitoli_staff() : guida_ia_capitoli_pubblici();
    $df = [];
    $totale = 0;
    foreach ($capitoli as $c) {
        foreach ($c['passaggi'] as $p) {
            $totale++;
            foreach (array_keys($p['tf'] + $p['tfs'] + $c['tf']) as $radice) {
                $df[$radice] = ($df[$radice] ?? 0) + 1;
            }
        }
    }
    return ['capitoli' => $capitoli, 'df' => $df, 'passaggi' => $totale];
}

/**
 * Da cosa dipende la freschezza dell'indice: la data del file per la guida dei giocatori,
 * l'ultima riscrittura dei plugin per quella dello staff. Cambia quella, si rifa' l'indice.
 */
function guida_ia_impronta(string $ambito): string {
    if ($ambito === 'staff') {
        try {
            $riga = db()->query('SELECT COUNT(*) c, COALESCE(MAX(updated_at), "") m FROM guide_staff')->fetch();
            return 'staff:' . ($riga['c'] ?? 0) . ':' . ($riga['m'] ?? '');
        } catch (Throwable $e) {
            return 'staff:vuota';
        }
    }
    $file = guida_ia_file_pubblico();
    return 'pubblica:' . (is_file($file) ? filemtime($file) . ':' . filesize($file) : '0');
}

/**
 * L'indice, tenuto da parte tra una domanda e l'altra. Spezzare 250 KB di guida a ogni
 * domanda si potrebbe anche fare, ma e' lavoro buttato: qui si rifa' solo quando la guida
 * cambia. Se la cartella temporanea non e' scrivibile non succede niente di grave — si
 * ricostruisce ogni volta e la ricerca funziona lo stesso, solo piu' lenta.
 */
function guida_ia_indice(string $ambito): array {
    static $memoria = [];
    if (isset($memoria[$ambito])) {
        return $memoria[$ambito];
    }
    $impronta = guida_ia_impronta($ambito);
    // Nel nome c'e' anche l'utente di sistema: lo stesso indice puo' venire costruito dal
    // sito (www-data) e da una prova a riga di comando (un'altra utenza), e un file scritto
    // dall'altro sarebbe leggibile ma non riscrivibile — cioe' una cache che non si aggiorna
    // piu' quando la guida cambia.
    $chi = function_exists('posix_geteuid') ? posix_geteuid() : 'x';
    $cache = sys_get_temp_dir() . '/magicadventure-guida-' . $ambito . '-' . $chi . '.idx';

    $salvato = @file_get_contents($cache);
    if ($salvato !== false) {
        $dati = @unserialize($salvato, ['allowed_classes' => false]);
        if (is_array($dati) && ($dati['impronta'] ?? null) === $impronta) {
            return $memoria[$ambito] = $dati['indice'];
        }
    }

    $indice = guida_ia_costruisci($ambito);
    @file_put_contents($cache, serialize(['impronta' => $impronta, 'indice' => $indice]), LOCK_EX);
    return $memoria[$ambito] = $indice;
}

// =====================================================================
//  LA RICERCA
// =====================================================================

/**
 * La domanda tradotta in radici pesate. Le parole scritte dal giocatore valgono 1; i
 * sinonimi che aggiungiamo noi valgono meno di meta': sono un'ipotesi, non un dato.
 */
function guida_ia_domanda(string $domanda): array {
    $stopword = guida_ia_stopword();
    $sinonimi = guida_ia_sinonimi();
    $termini = [];
    $gruppi = [];
    $parole = guida_ia_parole($domanda);

    foreach ($parole as $parola) {
        if (isset($stopword[$parola]) || strlen($parola) < 2) {
            continue;
        }
        $radice = guida_ia_radice($parola);
        $termini[$radice] = max($termini[$radice] ?? 0, 1.0);
        // Ogni parola della domanda si porta dietro i suoi sinonimi in un GRUPPO. Serve piu'
        // avanti per la copertura: "come torno alla base" e' una domanda di due parole, non
        // di quattro, e trovare "casa" al posto di "base" vuol dire aver risposto — non aver
        // risposto a meta'.
        $gruppo = [$radice];
        foreach ($sinonimi[$parola] ?? [] as $simile) {
            $r = guida_ia_radice($simile);
            $termini[$r] = max($termini[$r] ?? 0, 0.45);
            $gruppo[] = $r;
        }
        $gruppi[] = $gruppo;
    }
    return [
        'termini' => $termini,
        'gruppi'  => $gruppi,
        'parole'  => $parole,
        'frase'   => guida_ia_normalizza($domanda),
    ];
}

/** Quanto e' rara una radice: una parola in due passaggi su duecento vale piu' di "fazione". */
function guida_ia_rarita(array $indice, string $radice): float {
    $totale = max(1, $indice['passaggi']);
    $in = $indice['df'][$radice] ?? 0;
    return log(1 + $totale / (1 + $in));
}

/**
 * Il punteggio di un passaggio. Due idee, entrambe importanti:
 *  - conta DOVE si trova la parola (titolo del capitolo > sotto-titolo > corpo);
 *  - conta QUANTE parole diverse della domanda copre: un passaggio che ne prende tre su tre
 *    batte sempre uno che ripete cinque volte la prima. E' la differenza tra rispondere alla
 *    domanda e rispondere a una parola della domanda.
 */
function guida_ia_punteggio(array $indice, array $domanda, array $capitolo, array $passaggio): float {
    $punti = 0.0;
    $trovataRara = false;

    foreach ($domanda['termini'] as $radice => $peso) {
        $nelCorpo  = $passaggio['tf'][$radice] ?? 0;
        $nelSotto  = $passaggio['tfs'][$radice] ?? 0;
        $nelTitolo = $capitolo['tf'][$radice] ?? 0;
        if (!$nelCorpo && !$nelSotto && !$nelTitolo) {
            continue;
        }
        $rarita = guida_ia_rarita($indice, $radice);
        $punti += $peso * $rarita * (min($nelCorpo, 4) * 0.8 + $nelSotto * 1.4 + $nelTitolo * 2.2);
        if ($rarita >= 1.0 && $peso >= 1.0) {
            $trovataRara = true;      // almeno una parola di sostanza, non solo sinonimi
        }
    }
    if ($punti <= 0) {
        return 0.0;
    }
    $punti *= 0.3 + 0.7 * guida_ia_copertura($domanda, $capitolo, $passaggio);
    if (!$trovataRara) {
        $punti *= 0.5;
    }
    // Una riga della tabella dei comandi e' corta, quindi "copre" la domanda con facilita':
    // senza questo freno "come si crea una fazione" risponderebbe sempre con la tabella
    // invece che col capitolo che lo spiega. Chi cerca il comando esatto la trova lo stesso,
    // perche' li' vince il comando in se', che e' rarissimo.
    if (!empty($passaggio['riga'])) {
        $punti *= 0.78;
    }
    // La domanda ricopiata dentro il passaggio ("conquistare un territorio") e' il segnale
    // piu' forte che esista: un premio, non un raddoppio.
    $normale = guida_ia_normalizza($passaggio['testo']);
    $stopword = guida_ia_stopword();
    $parole = array_values(array_filter($domanda['parole'],
        fn($p) => !isset($stopword[$p]) && strlen($p) > 2));
    for ($i = 0; $i + 1 < count($parole); $i++) {
        if (str_contains($normale, $parole[$i] . ' ' . $parole[$i + 1])) {
            $punti *= 1.3;
            break;
        }
    }
    return $punti;
}

/**
 * Quante PAROLE della domanda hanno trovato risposta qui dentro (0 = nessuna, 1 = tutte).
 * Si contano le parole scritte dal giocatore, non le radici: una parola vale coperta anche
 * se a comparire e' un suo sinonimo, altrimenti chi usa parole sue verrebbe punito due volte.
 */
function guida_ia_copertura(array $domanda, array $capitolo, array $passaggio): float {
    $gruppi = $domanda['gruppi'] ?: [];
    if (!$gruppi) {
        return 0.0;
    }
    $coperti = 0;
    foreach ($gruppi as $gruppo) {
        foreach ($gruppo as $radice) {
            if (($passaggio['tf'][$radice] ?? 0) || ($passaggio['tfs'][$radice] ?? 0)
                || ($capitolo['tf'][$radice] ?? 0)) {
                $coperti++;
                break;
            }
        }
    }
    return $coperti / count($gruppi);
}

/** Evidenzia nella risposta le parole della domanda, senza mai entrare dentro i tag. */
function guida_ia_evidenzia(string $html, array $domanda): string {
    $radici = array_keys(array_filter($domanda['termini'], fn($p) => $p >= 1.0));
    if (!$radici) {
        return $html;
    }
    $pezzi = preg_split('~(<[^>]+>)~', $html, -1, PREG_SPLIT_DELIM_CAPTURE) ?: [];
    foreach ($pezzi as $i => $pezzo) {
        if ($pezzo === '' || $pezzo[0] === '<') {
            continue;
        }
        $pezzi[$i] = preg_replace_callback('~[\p{L}\p{N}]+~u', function ($m) use ($radici) {
            $radice = guida_ia_radice(guida_ia_normalizza($m[0]));
            return in_array($radice, $radici, true) ? '<mark>' . $m[0] . '</mark>' : $m[0];
        }, $pezzo);
    }
    return implode('', $pezzi);
}

/** Il nome per esteso di un capitolo, come compare sotto la risposta. */
function guida_ia_etichetta(array $capitolo): string {
    $titolo = $capitolo['titolo'];
    if ($capitolo['numero'] !== '') {
        $titolo = $capitolo['numero'] . '. ' . $titolo;
    }
    if ($capitolo['gruppo'] !== '') {
        $titolo = $capitolo['gruppo'] . ' › ' . $titolo;
    }
    return $titolo;
}

/**
 * La riga che apre la risposta. Non riassume — riassumere vuol dire riscrivere, e riscrivere
 * vuol dire prima o poi dire una cosa che nella guida non c'e'. Dice da dove arriva quello
 * che si sta per leggere e, se la domanda chiedeva un comando e nel passaggio c'e', mette
 * subito il comando in cima.
 */
function guida_ia_apertura(array $domanda, array $capitolo, array $passaggi): string {
    $chiedeComando = (bool) preg_match(
        '~\b(comando|comandi|come si|come faccio|come fare|come posso|qual e)\b~u', $domanda['frase']);
    $apertura = '';
    if ($chiedeComando && preg_match_all('~<code>(.*?)</code>~is', $passaggi[0]['html'], $trovati)) {
        // Tra i comandi del passaggio si sceglie quello che assomiglia di piu' alla domanda:
        // a "come torno alla base" il paragrafo risponde con sethome E home, e mettere in
        // cima il primo che capita ("imposta la casa") sarebbe la risposta sbagliata.
        $migliore = $trovati[1][0];
        $massimo = -1;
        foreach ($trovati[1] as $codice) {
            $affinita = 0;
            foreach (guida_ia_parole(guida_ia_testo($codice)) as $parola) {
                if (($domanda['termini'][guida_ia_radice($parola)] ?? 0) > 0) {
                    $affinita++;
                }
            }
            if ($affinita > $massimo) {
                $massimo = $affinita;
                $migliore = $codice;
            }
        }
        // Niente h(): il testo arriva dalla guida gia' pronto per l'HTML (i <nome> ci stanno
        // come &lt;nome&gt;), e riescaparlo lo farebbe leggere "&amp;lt;nome&amp;gt;".
        $apertura = 'Comando: <code>' . strip_tags($migliore) . '</code>. ';
    }
    return $apertura . 'Dalla guida, capitolo <strong>' . h(guida_ia_etichetta($capitolo)) . '</strong>:';
}

/** Gli altri capitoli che parlano della stessa cosa, per chi vuole leggere il resto. */
function guida_ia_correlati(array $indice, array $perCapitolo, int $escluso): array {
    arsort($perCapitolo);
    $massimo = reset($perCapitolo) ?: 0;
    $fuori = [];
    foreach ($perCapitolo as $ic => $punti) {
        if ($ic === $escluso || $punti < $massimo * 0.25) {
            continue;
        }
        $c = $indice['capitoli'][$ic];
        // Nella guida dello staff piu' blocchi condividono la stessa ancora (il plugin):
        // due voci che portano allo stesso punto sarebbero solo rumore.
        $fuori[$c['ancora']] = ['titolo' => guida_ia_etichetta($c), 'ancora' => $c['ancora']];
        if (count($fuori) >= GUIDA_IA_MAX_CORRELATI) {
            break;
        }
    }
    return array_values($fuori);
}

/** Quando non c'e' proprio niente: i primi capitoli, come punto di partenza. */
function guida_ia_suggerimenti(array $indice): array {
    $fuori = [];
    foreach (array_slice($indice['capitoli'], 0, GUIDA_IA_MAX_CORRELATI) as $c) {
        $fuori[] = ['titolo' => guida_ia_etichetta($c), 'ancora' => $c['ancora']];
    }
    return $fuori;
}

/**
 * La risposta a una domanda.
 *
 * @return array{ok:bool,apertura:string,passaggi:array,fonte:?array,correlati:array,nota:string}
 */
function guida_ia_cerca(string $testoDomanda, string $ambito = 'pubblica'): array {
    $ambito = $ambito === 'staff' ? 'staff' : 'pubblica';
    $vuota = ['ok' => false, 'apertura' => '', 'passaggi' => [], 'fonte' => null,
              'correlati' => [], 'nota' => ''];

    $testoDomanda = trim(mb_substr($testoDomanda, 0, 200));
    if (mb_strlen($testoDomanda) < 3) {
        return array_merge($vuota, ['nota' => 'Scrivi una domanda un po\' più lunga.']);
    }

    $indice = guida_ia_indice($ambito);
    if (!$indice['capitoli']) {
        return array_merge($vuota, ['nota' => 'La guida non è al momento disponibile.']);
    }

    $domanda = guida_ia_domanda($testoDomanda);
    if (!$domanda['termini']) {
        return array_merge($vuota, [
            'nota' => 'Nella domanda non c\'è nessuna parola da cercare.',
            'correlati' => guida_ia_suggerimenti($indice),
        ]);
    }

    // Le parole che nella guida non compaiono MAI escono dal conto della copertura: sono
    // domande a cui questa guida non puo' rispondere, e tenerle dentro affosserebbe anche le
    // parole a cui invece sa rispondere ("quali permessi servono per la coda" non deve
    // fallire perche' "servono" non e' scritto da nessuna parte). Se non ne resta nessuna,
    // la risposta e' che qui dentro non c'e'.
    $domanda['gruppi'] = array_values(array_filter($domanda['gruppi'], function ($gruppo) use ($indice) {
        foreach ($gruppo as $radice) {
            if (($indice['df'][$radice] ?? 0) > 0) {
                return true;
            }
        }
        return false;
    }));
    if (!$domanda['gruppi']) {
        return array_merge($vuota, [
            'nota' => 'Nella guida non ho trovato niente su questo.',
            'correlati' => guida_ia_suggerimenti($indice),
        ]);
    }

    // Punteggio di ogni passaggio, e punteggio complessivo di ogni capitolo.
    $classifica = [];
    $perCapitolo = [];
    foreach ($indice['capitoli'] as $ic => $capitolo) {
        foreach ($capitolo['passaggi'] as $ip => $passaggio) {
            $punti = guida_ia_punteggio($indice, $domanda, $capitolo, $passaggio);
            if ($punti <= 0) {
                continue;
            }
            $classifica[] = ['capitolo' => $ic, 'passaggio' => $ip, 'punti' => $punti];
            // Il capitolo vale il suo passaggio migliore piu' un pizzico degli altri: due
            // paragrafi discreti sullo stesso tema battono un paragrafo isolato.
            $precedente = $perCapitolo[$ic] ?? 0.0;
            $perCapitolo[$ic] = $punti > $precedente
                ? $punti + $precedente * 0.25
                : $precedente + $punti * 0.25;
        }
    }
    if (!$classifica) {
        return array_merge($vuota, [
            'nota' => 'Nella guida non ho trovato niente su questo.',
            'correlati' => guida_ia_suggerimenti($indice),
        ]);
    }

    usort($classifica, fn($a, $b) => $b['punti'] <=> $a['punti']);
    $migliore = $classifica[0];
    $capitolo = $indice['capitoli'][$migliore['capitolo']];

    // Quanta parte della domanda ha trovato davvero risposta: se e' poca, e' piu' onesto
    // dire "non lo so" e mostrare i capitoli vicini che spacciare un paragrafo a caso.
    $copertura = guida_ia_copertura($domanda, $capitolo, $capitolo['passaggi'][$migliore['passaggio']]);
    if ($copertura < GUIDA_IA_COPERTURA_MINIMA) {
        return array_merge($vuota, [
            'nota' => 'Nella guida non ho trovato una risposta chiara. Forse cercavi:',
            'correlati' => guida_ia_correlati($indice, $perCapitolo, -1),
        ]);
    }

    // Il passaggio migliore, piu' i suoi vicini dello stesso capitolo che se lo meritano:
    // spesso la risposta e' "il paragrafo e l'elenco che lo segue", non una frase sola.
    $scelti = [];
    foreach ($classifica as $riga) {
        if ($riga['capitolo'] !== $migliore['capitolo']) {
            continue;
        }
        if ($scelti && $riga['punti'] < $migliore['punti'] * 0.5) {
            continue;
        }
        $scelti[] = $riga['passaggio'];
        if (count($scelti) >= GUIDA_IA_MAX_PASSAGGI) {
            break;
        }
    }
    sort($scelti);   // rimessi nell'ordine in cui stanno nella guida

    $passaggi = [];
    foreach ($scelti as $ip) {
        $p = $capitolo['passaggi'][$ip];
        $passaggi[] = [
            'sotto' => $p['sotto'],
            'html'  => guida_ia_evidenzia($p['html'], $domanda),
        ];
    }

    return [
        'ok'        => true,
        'apertura'  => guida_ia_apertura($domanda, $capitolo, $passaggi),
        'passaggi'  => $passaggi,
        'fonte'     => [
            'titolo' => guida_ia_etichetta($capitolo),
            'ancora' => $capitolo['ancora'],
        ],
        'correlati' => guida_ia_correlati($indice, $perCapitolo, $migliore['capitolo']),
        'nota'      => '',
    ];
}
