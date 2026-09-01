<?php
/**
 * Da quello che l'editor ha in mano al file .yml che legge il plugin.
 *
 * <h3>Perche' il sito SCRIVE lo YAML ma non lo LEGGE</h3>
 * PHP, su questa macchina, non ha un lettore di YAML. Scriverne uno vorrebbe dire avere due
 * interpretazioni degli stessi file — quella del plugin e quella del sito — e il giorno in cui
 * divergono l'editor mostra un menu diverso da quello che vedono i giocatori, senza che nessuno
 * se ne accorga. La lettura la fa quindi il plugin, che pubblica cio' che ha capito in
 * `plugins/MagixMenus/menus.json`; qui si fa solo il verso opposto, che e' la meta' facile.
 *
 * <h3>Cosa NON sopravvive a un salvataggio dal sito</h3>
 * I commenti del file. Chi ha scritto un menu a mano, con le sue note, se le vede sostituire
 * dall'intestazione qui sotto. E' scritto nell'intestazione stessa e nell'editor, prima di salvare.
 *
 * <h3>Le virgolette</h3>
 * Ogni testo esce fra virgolette doppie, sempre, anche quando non servirebbero. In un menu i testi
 * sono pieni di due punti (`messaggio: &aCiao`), di cancelletti (`&#C046E8`) e di percentuali: sono
 * esattamente i caratteri che in YAML cambiano significato a una riga non quotata. Quotare sempre
 * costa due caratteri e toglie di mezzo un'intera categoria di file rotti — la stessa che ha fatto
 * rifiutare il plugin.yml di questo plugin al primo avvio.
 */

/** Il testo, con le virgolette e le fughe che servono. */
function myaml_text(?string $s): string
{
    $s = (string) $s;
    $s = str_replace(['\\', '"'], ['\\\\', '\\"'], $s);
    $s = str_replace(["\r", "\n", "\t"], ['\\r', '\\n', '\\t'], $s);
    return '"' . $s . '"';
}

function myaml_spazi(int $livello): string
{
    return str_repeat('  ', $livello);
}

/**
 * Le caselle come le scriverebbe una persona: 10,11,12,13 diventa "10-13".
 *
 * L'editor lavora con un elenco di numeri, ma un file pieno di
 * `slot: [0,1,2,3,4,5,6,7,8,9,...]` non si rilegge piu'. Gli intervalli si ricompongono qui.
 */
function myaml_slot(array $caselle): string
{
    $caselle = array_values(array_unique(array_map('intval', $caselle)));
    sort($caselle);
    if (!$caselle) {
        return '""';
    }
    $pezzi = [];
    $inizio = $precedente = $caselle[0];
    for ($i = 1; $i <= count($caselle); $i++) {
        $ora = $caselle[$i] ?? null;
        if ($ora !== null && $ora === $precedente + 1) {
            $precedente = $ora;
            continue;
        }
        // Due caselle di fila si scrivono "4,5": "4-5" non e' piu' corto e si legge peggio.
        $pezzi[] = $inizio === $precedente ? (string) $inizio
            : ($precedente - $inizio === 1 ? $inizio . ',' . $precedente : $inizio . '-' . $precedente);
        $inizio = $precedente = $ora;
    }
    $testo = implode(',', $pezzi);
    return ctype_digit($testo) ? $testo : myaml_text($testo);
}

/** Una lista di testi, in blocco. Se e' vuota non scrive niente. */
function myaml_list(string $chiave, array $valori, int $livello): string
{
    $valori = array_values(array_filter($valori, static fn($v) => $v !== null));
    if (!$valori) {
        return '';
    }
    $out = myaml_spazi($livello) . $chiave . ":\n";
    foreach ($valori as $v) {
        $out .= myaml_spazi($livello + 1) . '- ' . myaml_text((string) $v) . "\n";
    }
    return $out;
}

/** Una lista corta sulla stessa riga: comandi, argomenti. */
function myaml_short_list(string $chiave, array $valori, int $livello): string
{
    $valori = array_values(array_filter(array_map('strval', $valori), static fn($v) => $v !== ''));
    if (!$valori) {
        return '';
    }
    return myaml_spazi($livello) . $chiave . ': [' . implode(', ', array_map('myaml_text', $valori)) . "]\n";
}

function myaml_riga(string $chiave, $valore, int $livello, bool $testo = true): string
{
    if ($valore === null || $valore === '') {
        return '';
    }
    if (is_bool($valore)) {
        return myaml_spazi($livello) . $chiave . ': ' . ($valore ? 'true' : 'false') . "\n";
    }
    if (!$testo) {
        return myaml_spazi($livello) . $chiave . ': ' . $valore . "\n";
    }
    return myaml_spazi($livello) . $chiave . ': ' . myaml_text((string) $valore) . "\n";
}

/**
 * Un blocco di requisiti.
 *
 * I nomi delle singole condizioni (r1, r2...) li mette il sito: al plugin non servono, gli
 * bastano per tenerle separate, e chiedere all'utente di inventarne uno per ogni riga sarebbe
 * una domanda senza risposta interessante.
 */
function myaml_requirements(string $chiave, ?array $r, int $livello): string
{
    if (!$r || empty($r['requisiti'])) {
        return '';
    }
    $out = myaml_spazi($livello) . $chiave . ":\n";
    if (!empty($r['minimo']) && (int) $r['minimo'] > 0) {
        $out .= myaml_riga('minimum', (int) $r['minimo'], $livello + 1, false);
    }
    $out .= myaml_spazi($livello + 1) . "requirements:\n";
    $n = 0;
    foreach ($r['requisiti'] as $uno) {
        if (empty($uno['tipo'])) {
            continue;
        }
        $n++;
        $out .= myaml_spazi($livello + 2) . 'r' . $n . ":\n";
        $out .= myaml_riga('type', strtoupper((string) $uno['tipo']), $livello + 3, false);
        $out .= myaml_riga('key', $uno['chiave'] ?? '', $livello + 3);
        $out .= myaml_riga('value', $uno['valore'] ?? '', $livello + 3);
        if (isset($uno['quantita']) && (int) $uno['quantita'] !== 1) {
            $out .= myaml_riga('amount', (int) $uno['quantita'], $livello + 3, false);
        }
        // "match: true" e' il comportamento di serie: si scrive solo quando e' ribaltato.
        if (isset($uno['uguale']) && !$uno['uguale']) {
            $out .= myaml_riga('match', false, $livello + 3);
        }
    }
    if (!empty($r['azioni_negate'])) {
        $out .= myaml_actions('deny_actions', $r['azioni_negate'], $livello + 1);
    }
    return $out;
}

/** Una fila di azioni, comprese quelle a blocco (if / then / else). */
function myaml_actions(string $chiave, ?array $azioni, int $livello): string
{
    if (!$azioni) {
        return '';
    }
    $out = myaml_spazi($livello) . $chiave . ":\n";
    foreach ($azioni as $a) {
        $tipo = strtolower((string) ($a['tipo'] ?? ''));
        if ($tipo === '') {
            continue;
        }
        if ($tipo === 'if') {
            $condizione = $a['condizione'] ?? null;
            $requisiti = $condizione['requisiti'] ?? [];
            // Una condizione sola, un'equazione dritta: si scrive nella forma corta
            // `if: "%saldo% >= 100"`, che e' il caso che capita nove volte su dieci.
            $corta = count($requisiti) === 1
                && strtoupper((string) ($requisiti[0]['tipo'] ?? '')) === 'EQUATION'
                && ($requisiti[0]['uguale'] ?? true);
            if ($corta) {
                $out .= myaml_spazi($livello + 1) . '- if: ' . myaml_text((string) $requisiti[0]['chiave']) . "\n";
            } else {
                $out .= myaml_spazi($livello + 1) . "- if:\n";
                $dentro = myaml_requirements('if', $condizione, $livello + 3);
                // Si toglie la riga "if:" appena generata: qui la chiave e' gia' scritta sopra.
                $dentro = preg_replace('/^.*\n/', '', $dentro, 1);
                $out .= $dentro;
            }
            $out .= myaml_actions('then', $a['allora'] ?? [], $livello + 2);
            $out .= myaml_actions('else', $a['altrimenti'] ?? [], $livello + 2);
            continue;
        }
        $argomento = trim((string) ($a['argomento'] ?? ''));
        $riga = $argomento === '' ? $tipo : $tipo . ': ' . $argomento;
        $out .= myaml_spazi($livello + 1) . '- ' . myaml_text($riga) . "\n";
    }
    return $out;
}

/** Un item, con solo le chiavi che dicono qualcosa. */
function myaml_item(array $i, int $livello, bool $conCaselle = true): string
{
    $out = '';
    if ($conCaselle) {
        $out .= myaml_spazi($livello) . 'slot: ' . myaml_slot($i['slot'] ?? []) . "\n";
    }
    $out .= myaml_riga('id', $i['id'] ?? 'STONE', $livello, false);
    if (($i['quantita'] ?? '1') !== '1' && ($i['quantita'] ?? '') !== '') {
        $out .= myaml_riga('amount', $i['quantita'], $livello);
    }
    $out .= myaml_riga('display_name', $i['titolo'] ?? null, $livello);
    $out .= myaml_list('lore', $i['descrizione'] ?? [], $livello);
    $out .= myaml_list('enchantments', $i['incantesimi'] ?? [], $livello);
    if (!empty($i['luccica'])) {
        $out .= myaml_riga('glow', true, $livello);
    }
    if (!empty($i['indistruttibile'])) {
        $out .= myaml_riga('unbreakable', true, $livello);
    }
    if (!empty($i['nascondi_dettagli'])) {
        $out .= myaml_riga('hide_details', true, $livello);
    }
    $out .= myaml_riga('custom_model_data', $i['modello_custom'] ?? null, $livello);
    $out .= myaml_riga('item_model', $i['modello_item'] ?? null, $livello);
    $out .= myaml_riga('color', $i['colore'] ?? null, $livello);
    $out .= myaml_riga('head', $i['testa'] ?? null, $livello);
    $out .= myaml_riga('components', $i['avanzate'] ?? null, $livello);
    // Il negozio: tre chiavi al posto di un blocco di condizioni (vedi negozio/Negozio.java).
    $out .= myaml_riga('price', $i['prezzo'] ?? null, $livello);
    $out .= myaml_riga('give', $i['dai'] ?? null, $livello);
    $out .= myaml_riga('sell', $i['vendi'] ?? null, $livello);
    if (!empty($i['attesa_fra_clic'])) {
        $out .= myaml_riga('cooldown', (int) $i['attesa_fra_clic'], $livello, false);
    }
    $out .= myaml_requirements('show_requirements', $i['mostra_se'] ?? null, $livello);

    // Le chiavi dei tasti arrivano dal plugin gia' come si scrivono nel file
    // ("click_requirements", "right_click_actions"): qui non si traduce niente.
    foreach (($i['click_se'] ?? []) as $chiave => $r) {
        $out .= myaml_requirements($chiave, $r, $livello);
    }
    foreach (($i['azioni'] ?? []) as $chiave => $a) {
        $out .= myaml_actions($chiave, $a, $livello);
    }
    return $out;
}

/**
 * Il file intero.
 *
 * @param array $m il menu come lo tiene l'editor (la stessa forma di menus.json)
 */
function menu_yaml_da_modello(array $m, string $chi = ''): string
{
    $tipo = strtolower((string) ($m['tipo'] ?? 'chest'));
    $out = "# ==========================================================================================\n";
    $out .= "#  " . ($m['nome'] ?? 'menu') . ".yml\n";
    $out .= "#\n";
    $out .= "#  Scritto dall'editor del gestionale" . ($chi !== '' ? " (ultima modifica: $chi)" : '') . ".\n";
    $out .= "#  ATTENZIONE: questo file viene RISCRITTO per intero a ogni salvataggio dal sito, e i\n";
    $out .= "#  commenti che ci aggiungi a mano non sopravvivono. Se vuoi tenerlo scritto a mano,\n";
    $out .= "#  smetti di aprirlo dall'editor.\n";
    $out .= "#\n";
    $out .= "#  Dopo ogni modifica serve /menus reload (il pulsante \"Applica\" lo fa da solo).\n";
    $out .= "# ==========================================================================================\n\n";

    $out .= "menu:\n";
    $out .= myaml_riga('type', $tipo, 1, false);
    if ($tipo === 'chest') {
        $out .= myaml_riga('rows', max(1, min(6, (int) ($m['righe'] ?? 3))), 1, false);
    }
    $out .= myaml_riga('title', $m['titolo'] ?? '', 1);
    if (!empty($m['aggiornamento'])) {
        $out .= myaml_riga('update', (int) $m['aggiornamento'], 1, false);
    }
    $out .= myaml_short_list('commands', $m['comandi'] ?? [], 1);
    $out .= myaml_riga('permission', $m['permesso'] ?? null, 1);
    $out .= myaml_short_list('arguments', $m['argomenti'] ?? [], 1);
    if (isset($m['chiusura_libera']) && !$m['chiusura_libera']) {
        $out .= myaml_riga('closeable', false, 1);
    }
    $out .= myaml_requirements('open_requirements', $m['apri_se'] ?? null, 1);
    $out .= myaml_actions('open_actions', $m['azioni_apertura'] ?? [], 1);
    $out .= myaml_actions('close_actions', $m['azioni_chiusura'] ?? [], 1);

    // --- finestra di dialogo ---
    if ($tipo === 'dialog' && !empty($m['dialogo'])) {
        $d = $m['dialogo'];
        $out .= myaml_list('body', $d['corpo'] ?? [], 1);
        if (!empty($d['pausa'])) {
            $out .= myaml_riga('pause', true, 1);
        }
        if (!empty($d['campi'])) {
            $out .= "  inputs:\n";
            foreach ($d['campi'] as $c) {
                if (empty($c['chiave'])) {
                    continue;
                }
                $out .= myaml_spazi(2) . $c['chiave'] . ":\n";
                $out .= myaml_riga('type', strtolower((string) ($c['tipo'] ?? 'text')), 3, false);
                $out .= myaml_riga('label', $c['etichetta'] ?? '', 3);
                $out .= myaml_riga('default', $c['iniziale'] ?? '', 3);
                if (strtolower((string) ($c['tipo'] ?? '')) === 'number') {
                    $out .= myaml_riga('min', (float) ($c['da'] ?? 0), 3, false);
                    $out .= myaml_riga('max', (float) ($c['a'] ?? 100), 3, false);
                    $out .= myaml_riga('step', (float) ($c['passo'] ?? 1), 3, false);
                }
                if (strtolower((string) ($c['tipo'] ?? '')) === 'text') {
                    $out .= myaml_riga('max_length', (int) ($c['lunghezza'] ?? 32), 3, false);
                    if (!empty($c['piu_righe'])) {
                        $out .= myaml_riga('multiline', true, 3);
                    }
                }
                // Solo se diversa da quella di serie: scriverla sempre riempirebbe il file di
                // righe che non dicono niente (il valore di serie e' 200, vedi CaricatoreMenu).
                if (isset($c['larghezza']) && (int) $c['larghezza'] !== 200) {
                    $out .= myaml_riga('width', (int) $c['larghezza'], 3, false);
                }
                $out .= myaml_short_list('options', $c['opzioni'] ?? [], 3);
            }
        }
        if (!empty($d['bottoni'])) {
            $out .= "  buttons:\n";
            foreach ($d['bottoni'] as $b) {
                if (($b['etichetta'] ?? '') === '') {
                    continue;
                }
                $out .= myaml_spazi(2) . '- label: ' . myaml_text((string) $b['etichetta']) . "\n";
                $out .= myaml_riga('tooltip', $b['suggerimento'] ?? null, 3);
                if (isset($b['larghezza']) && (int) $b['larghezza'] !== 150) {
                    $out .= myaml_riga('width', (int) $b['larghezza'], 3, false);
                }
                $out .= myaml_requirements('show_requirements', $b['mostra_se'] ?? null, 3);
                $out .= myaml_actions('actions', $b['azioni'] ?? [], 3);
            }
        }
    }

    // --- contenuto che si genera da solo ---
    if (!empty($m['contenuto']) && !empty($m['contenuto']['fonte'])) {
        $c = $m['contenuto'];
        $out .= "\ncontent:\n";
        $out .= myaml_riga('source', strtolower((string) $c['fonte']), 1, false);
        $out .= myaml_spazi(1) . 'slot: ' . myaml_slot($c['slot'] ?? []) . "\n";
        if (strtolower((string) $c['fonte']) === 'placeholder') {
            $out .= myaml_riga('placeholder', $c['placeholder'] ?? '', 1);
            $out .= myaml_riga('separator', $c['separatore'] ?? ',', 1);
        }
        if (strtolower((string) $c['fonte']) === 'list') {
            $out .= myaml_list('list', $c['lista'] ?? [], 1);
        }
        $out .= "  entry:\n";
        $out .= myaml_item($c['voce'] ?? [], 2, false);
    }

    // --- gli item ---
    if (!empty($m['item'])) {
        $out .= "\n# L'ordine conta: se due item chiedono la stessa casella, la prende il PRIMO scritto\n";
        $out .= "# qui sotto che abbia i suoi show_requirements soddisfatti. Lo sfondo va per ultimo.\n";
        $out .= "items:\n";
        $usati = [];
        foreach ($m['item'] as $indice => $i) {
            $nome = preg_replace('/[^a-z0-9_]/', '', strtolower((string) ($i['nome'] ?? '')));
            if ($nome === '' || isset($usati[$nome])) {
                $nome = 'item' . ($indice + 1);
            }
            $usati[$nome] = true;
            $out .= "\n" . myaml_spazi(1) . $nome . ":\n";
            $out .= myaml_item($i, 2);
        }
    }
    return $out;
}
