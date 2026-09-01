<?php
/**
 * Ponte fra il gestionale e le console dei server sul VPS.
 *
 * Qui dentro non c'e' niente di "potente": tutto quello che si puo' davvero fare lo
 * decide /usr/local/bin/magix-console (installato da vps/installa-console.sh), che il
 * sito lancia con sudo e che accetta solo le istanze elencate in
 * /etc/magicadventure/istanze.conf. Questo file si limita a chiamarlo e a tradurre
 * le sue righe (campi separati da TAB) in array PHP.
 *
 * Il programma di sistema stampa testo semplice apposta: costruire JSON dentro uno
 * script bash e' un invito agli errori di virgolette, e il JSON lo sa fare PHP.
 */

/** Programma di sistema che fa il lavoro vero. */
const CONSOLE_PONTE = '/usr/local/bin/magix-console';

/** Il ponte e' installato? Se no, la scheda lo dice invece di riempirsi di errori. */
function console_ponte_pronto(): bool {
    return is_file(CONSOLE_PONTE);
}

/**
 * Lancia il ponte e ritorna ['ok' => bool, 'out' => string, 'err' => string].
 *
 * Gli argomenti passano sempre da escapeshellarg(): un comando di console puo'
 * contenere virgolette, e-commerciali e qualunque altra cosa.
 *
 * @param string|null $ingresso testo da dare al programma sulla sua entrata standard.
 *        Serve per il contenuto dei file dei menu: passarlo come ARGOMENTO significherebbe
 *        far comparire un file intero nella lista dei processi della macchina, e mettere
 *        migliaia di caratteri dentro una riga di comando.
 */
function console_esegui(string $sotto, array $argomenti = [], int $secondi = 25,
                        ?string $ingresso = null): array {
    if (!console_ponte_pronto()) {
        return ['ok' => false, 'out' => '', 'err' => 'Il ponte con il server non è installato su questa macchina.'];
    }

    $pezzi = ['timeout', (string) $secondi, 'sudo', '-n', CONSOLE_PONTE, $sotto];
    foreach ($argomenti as $a) {
        $pezzi[] = (string) $a;
    }
    $comando = implode(' ', array_map('escapeshellarg', $pezzi));

    $descrittori = [1 => ['pipe', 'w'], 2 => ['pipe', 'w']];
    if ($ingresso !== null) {
        $descrittori[0] = ['pipe', 'r'];
    }
    $processo = @proc_open($comando, $descrittori, $tubi);
    if (!is_resource($processo)) {
        return ['ok' => false, 'out' => '', 'err' => 'Impossibile eseguire il ponte con il server.'];
    }
    if ($ingresso !== null) {
        // Si scrive e si CHIUDE subito: il programma dall'altra parte legge fino alla fine
        // dell'entrata, e se il tubo restasse aperto resterebbero fermi tutti e due.
        fwrite($tubi[0], $ingresso);
        fclose($tubi[0]);
    }
    $out = stream_get_contents($tubi[1]);
    $err = stream_get_contents($tubi[2]);
    fclose($tubi[1]);
    fclose($tubi[2]);
    $uscita = proc_close($processo);

    $err = trim((string) $err);
    if ($uscita === 124) {
        $err = 'Il server non ha risposto in tempo (' . $secondi . 's).';
    } elseif ($uscita !== 0 && $err === '') {
        $err = 'Comando fallito (codice ' . $uscita . ').';
    }
    return ['ok' => $uscita === 0, 'out' => (string) $out, 'err' => $err];
}

/**
 * Stato di tutte le istanze e di tutte le screen del VPS.
 *
 * Ritorna ['ok' => bool, 'errore' => ?string, 'modo_screen' => string,
 *          'istanze' => [...], 'screens' => [...]].
 */
function console_stato(): array {
    $r = console_esegui('lista', [], 15);
    $dati = ['ok' => $r['ok'], 'errore' => $r['ok'] ? null : $r['err'],
             'modo_screen' => 'lettura', 'istanze' => [], 'screens' => []];
    if (!$r['ok']) {
        return $dati;
    }

    foreach (explode("\n", $r['out']) as $riga) {
        if ($riga === '') {
            continue;
        }
        $c = explode("\t", $riga);
        if ($c[0] === 'V') {
            $dati['modo_screen'] = $c[2] ?? 'lettura';
        } elseif ($c[0] === 'I' && count($c) >= 11) {
            // Il lavoro in corso arriva come "ferma:12" (azione e secondi trascorsi)
            $lavoro = $c[10] === '-' ? null : explode(':', $c[10]);
            $dati['istanze'][] = [
                'id'        => $c[1],
                'nome'      => $c[2],
                'screen'    => $c[3],
                'servizio'  => $c[4] === '-' ? null : $c[4],
                'accesa'    => $c[5] === 'accesa',
                'pid'       => $c[6] === '-' ? null : (int) $c[6],
                'da'        => $c[7] === '-' ? null : (int) $c[7],
                'processo'  => $c[8],   // viva | assente | na
                'porta'     => $c[9] === '-' ? null : (int) $c[9],
                'lavoro'    => $lavoro ? $lavoro[0] : null,
                'lavoro_da' => $lavoro ? (int) ($lavoro[1] ?? 0) : null,
            ];
        } elseif ($c[0] === 'S' && count($c) >= 5) {
            $dati['screens'][] = [
                'sessione' => $c[1],
                'pid'      => (int) $c[2],
                'da'       => $c[3] === '' ? null : (int) $c[3],
                'utente'   => $c[4],
            ];
        }
    }
    return $dati;
}

/** Durata in forma leggibile: 5 s -> "5 s", 3700 s -> "1 h 1 min". */
function console_duration(?int $secondi): string {
    if ($secondi === null || $secondi < 0) {
        return '—';
    }
    if ($secondi < 60) {
        return $secondi . ' s';
    }
    $minuti = intdiv($secondi, 60);
    if ($minuti < 60) {
        return $minuti . ' min';
    }
    $ore = intdiv($minuti, 60);
    $minuti %= 60;
    if ($ore < 24) {
        return $ore . ' h' . ($minuti ? ' ' . $minuti . ' min' : '');
    }
    $giorni = intdiv($ore, 24);
    $ore %= 24;
    return $giorni . ' g' . ($ore ? ' ' . $ore . ' h' : '');
}
