<?php
/**
 * La guida dei giocatori dentro /tutorial, come contenuto della pagina (non piu' in un iframe).
 *
 * Il file lo scrive il server (MagixFactions, da docs/build_tutorial.py) ed e' un documento
 * autonomo, col suo foglio di stile, il suo titolo e il suo indice: qui se ne prende solo il
 * CONTENUTO (avviso iniziale, "Inizia da qui", parti e capitoli) e lo si mette nella pagina, dove
 * lo veste il foglio di stile del sito (.guida-doc in style.css). L'indice non si copia: lo
 * ricostruisce la pagina, fisso di lato, da quello che si trova qui.
 *
 * Se il file cambia struttura e qualcosa non torna, si ripiega sul corpo intero: meglio una
 * guida con un titolo in piu' che nessuna guida.
 */

/**
 * @return array{body: string, parts: list<array{id: string, title: string, chapters: list<array{id: string, number: string, title: string}>}>}
 */
function guide_page_parts(string $html): array {
    $vuoto = ['body' => '', 'parts' => []];
    if (trim($html) === '') {
        return $vuoto;
    }
    libxml_use_internal_errors(true);
    $doc = new DOMDocument();
    $ok = $doc->loadHTML('<?xml encoding="utf-8"?>' . $html, LIBXML_NOERROR | LIBXML_NOWARNING);
    libxml_clear_errors();
    if (!$ok) {
        return $vuoto;
    }
    $xp = new DOMXPath($doc);

    // Il contenitore del contenuto: <div class="wrap"> (o il body, se un giorno sparisse).
    $wrap = $xp->query('//body/div[contains(concat(" ", normalize-space(@class), " "), " wrap ")]')->item(0)
        ?? $xp->query('//body')->item(0);
    if (!$wrap) {
        return $vuoto;
    }

    // Via cio' che la pagina rifa' a modo suo: titolo del documento, indice, "↑ Indice", pie'.
    foreach (['./header', './/nav[@id="indice"]', './/p[contains(@class, "back")]', './footer'] as $q) {
        foreach (iterator_to_array($xp->query($q, $wrap)) as $nodo) {
            $nodo->parentNode->removeChild($nodo);
        }
    }

    // Parti e capitoli, nell'ordine in cui compaiono: diventano l'indice laterale.
    $parti = [];
    $corrente = null;
    foreach ($xp->query('./div[contains(@class, "part")] | ./section[@id]', $wrap) as $nodo) {
        if ($nodo->nodeName === 'div') {
            $titolo = trim($xp->evaluate('string(.//*[contains(@class, "part-t")])', $nodo));
            $parti[] = ['id' => $nodo->getAttribute('id'), 'title' => $titolo, 'chapters' => []];
            $corrente = count($parti) - 1;
            continue;
        }
        $h2 = $xp->query('./h2', $nodo)->item(0);
        if (!$h2) {
            continue;
        }
        $numero = trim($xp->evaluate('string(./span[contains(@class, "n")])', $h2));
        $titolo = trim(preg_replace('/\s+/u', ' ', $h2->textContent));
        if ($numero !== '' && str_starts_with($titolo, $numero)) {
            $titolo = trim(mb_substr($titolo, mb_strlen($numero)));
        }
        if ($corrente === null) {
            $parti[] = ['id' => '', 'title' => '', 'chapters' => []];
            $corrente = 0;
        }
        $parti[$corrente]['chapters'][] = ['id' => $nodo->getAttribute('id'), 'number' => $numero, 'title' => $titolo];
    }

    $corpo = '';
    foreach ($wrap->childNodes as $figlio) {
        $corpo .= $doc->saveHTML($figlio);
    }
    return ['body' => $corpo, 'parts' => $parti];
}
