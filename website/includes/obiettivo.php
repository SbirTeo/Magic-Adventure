<?php
/**
 * Obiettivo di raccolta del server: barra di avanzamento con quanto si e' raccolto nel
 * periodo in corso (settimana, mese o anno) rispetto alla cifra da raggiungere.
 *
 * Tutto si regola dal gestionale (Store → Obiettivo del server): cifra, periodo, se
 * mostrare gli importi o solo la percentuale, dove farlo comparire.
 */

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/helpers.php';
require_once __DIR__ . '/auth.php';   // is_admin(): matita di modifica sull'obiettivo

/**
 * Dati dell'obiettivo in corso.
 *
 * @return array{attivo:bool, titolo:string, testo:string, obiettivo:float, raccolto:float,
 *               percentuale:float, periodo:string, etichettaPeriodo:string, inizio:string,
 *               mostraImporti:bool, valuta:string, raggiunto:bool}
 */
function obiettivo_dati(): array {
    $vuoto = [
        'attivo' => false, 'titolo' => '', 'testo' => '', 'obiettivo' => 0.0, 'raccolto' => 0.0,
        'percentuale' => 0.0, 'periodo' => 'mensile', 'etichettaPeriodo' => '', 'inizio' => '',
        'mostraImporti' => true, 'valuta' => site_setting('store_currency', 'EUR'), 'raggiunto' => false,
    ];

    if (site_setting('goal_enabled', '0') !== '1') {
        return $vuoto;
    }

    $obiettivo = (float) site_setting('goal_amount', '0');
    if ($obiettivo <= 0) {
        return $vuoto;   // senza una cifra da raggiungere non c'e' niente da mostrare
    }

    $periodo = site_setting('goal_period', 'mensile');
    if (!in_array($periodo, ['settimanale', 'mensile', 'annuale'], true)) {
        $periodo = 'mensile';
    }

    // Inizio del periodo in corso. La settimana comincia di LUNEDI' (come in Italia):
    // 'monday this week' lo dice esplicitamente, altrimenti di domenica si sbaglia di 6 giorni.
    $inizio = match ($periodo) {
        'settimanale' => date('Y-m-d 00:00:00', strtotime('monday this week')),
        'annuale' => date('Y-01-01 00:00:00'),
        default => date('Y-m-01 00:00:00'),
    };
    $etichetta = match ($periodo) {
        'settimanale' => 'questa settimana',
        'annuale' => 'quest\'anno',
        default => 'questo mese',
    };

    // Le consegne manuali dal gestionale non sono soldi incassati: stessa regola della
    // colonna dello store, cosi' i due numeri non si contraddicono.
    $contaManuali = site_setting('store_sidebar_include_manual', '0') === '1';
    $soloVeri = $contaManuali ? '' : " AND (paypal_capture_id IS NULL OR paypal_capture_id NOT LIKE 'MANUALE-%') ";

    $raccolto = 0.0;
    try {
        $q = db()->prepare("SELECT COALESCE(SUM(price), 0) FROM store_orders
                            WHERE status = 'paid' AND paid_at >= ? $soloVeri");
        $q->execute([$inizio]);
        $raccolto = (float) $q->fetchColumn();
    } catch (PDOException $e) {
        return $vuoto;   // store non installato: nessun obiettivo da mostrare
    }

    $percentuale = min(100, ($raccolto / $obiettivo) * 100);

    return [
        'attivo' => true,
        'titolo' => site_setting('goal_title', 'Obiettivo del server'),
        'testo' => site_setting('goal_text', ''),
        'obiettivo' => $obiettivo,
        'raccolto' => $raccolto,
        'percentuale' => $percentuale,
        'periodo' => $periodo,
        'etichettaPeriodo' => $etichetta,
        'inizio' => $inizio,
        'mostraImporti' => site_setting('goal_show_amount', '1') === '1',
        'valuta' => site_setting('store_currency', 'EUR'),
        'raggiunto' => $raccolto >= $obiettivo,
    ];
}

/** Disegna la sezione. Non stampa nulla se l'obiettivo e' spento o senza cifra. */
function obiettivo_sezione(): void {
    $o = obiettivo_dati();
    if (!$o['attivo']) {
        return;
    }
    $cifra = fn(float $v): string => number_format($v, 2, ',', '.');
    $percento = $o['percentuale'] >= 10 ? round($o['percentuale']) : round($o['percentuale'], 1);
    ?>
    <section class="obiettivo<?= $o['raggiunto'] ? ' e-raggiunto' : '' ?>"
             style="--percentuale: <?= number_format($o['percentuale'], 2, '.', '') ?>%">
      <div class="obiettivo-testata">
        <div>
          <h2 class="obiettivo-titolo"><?= h($o['titolo']) ?></h2>
          <p class="obiettivo-periodo">
            <?= h($o['etichettaPeriodo']) ?>
            <?php if ($o['testo'] !== ''): ?> · <?= h($o['testo']) ?><?php endif; ?>
          </p>
        </div>
        <div class="obiettivo-numeri">
          <span class="obiettivo-percento"><?= h((string) $percento) ?>%</span>
          <?php if ($o['mostraImporti']): ?>
            <span class="obiettivo-importi">
              <?= h($cifra($o['raccolto'])) ?> / <?= h($cifra($o['obiettivo'])) ?>
              <small><?= h($o['valuta']) ?></small>
            </span>
          <?php endif; ?>
          <?php if (is_admin()): /* la matita sta in riga con i numeri: nell'angolo finirebbe sopra la percentuale */ ?>
            <a href="/manage?section=store#obiettivo"
               class="card-edit-btn card-edit-btn-small obiettivo-modifica"
               title="Modifica l&rsquo;obiettivo del server" aria-label="Modifica l'obiettivo del server">&#9998;</a>
          <?php endif; ?>
        </div>
      </div>

      <?php /* La barra si riempie da sola all'apertura: l'animazione parte da 0 e arriva
               alla percentuale vera, che sta nella variabile --percentuale qui sopra. */ ?>
      <div class="obiettivo-barra" role="progressbar" aria-valuemin="0" aria-valuemax="100"
           aria-valuenow="<?= h((string) round($o['percentuale'])) ?>"
           aria-label="<?= h($o['titolo']) ?>">
        <div class="obiettivo-riempimento">
          <?php
            // Faville che salgono dalla punta: quadratini (siamo su un server Minecraft),
            // ognuno con la sua traiettoria, dimensione e ritardo. Sono valori fissi e non
            // casuali, cosi' il fuoco e' sempre lo stesso e non "salta" ricaricando.
            $faville = [
                ['dx' => '14px', 'dy' => '-18px', 'dim' => 4, 'dur' => '1.2s',  'ritardo' => '0s',    'colore' => '#fff3c4'],
                ['dx' => '8px',  'dy' => '-26px', 'dim' => 3, 'dur' => '1.5s',  'ritardo' => '.12s',  'colore' => 'var(--gold-light)'],
                ['dx' => '20px', 'dy' => '-10px', 'dim' => 3, 'dur' => '1.1s',  'ritardo' => '.24s',  'colore' => '#ffb457'],
                ['dx' => '11px', 'dy' => '12px',  'dim' => 3, 'dur' => '1.35s', 'ritardo' => '.36s',  'colore' => 'var(--gold)'],
                ['dx' => '17px', 'dy' => '20px',  'dim' => 2, 'dur' => '1.25s', 'ritardo' => '.48s',  'colore' => '#ff9838'],
                ['dx' => '6px',  'dy' => '-8px',  'dim' => 5, 'dur' => '0.95s', 'ritardo' => '.6s',   'colore' => '#ffffff'],
                ['dx' => '24px', 'dy' => '-2px',  'dim' => 2, 'dur' => '1.6s',  'ritardo' => '.72s',  'colore' => 'var(--gold-light)'],
                ['dx' => '10px', 'dy' => '-32px', 'dim' => 2, 'dur' => '1.7s',  'ritardo' => '.84s',  'colore' => '#ffd98a'],
                ['dx' => '28px', 'dy' => '8px',   'dim' => 3, 'dur' => '1.45s', 'ritardo' => '.96s',  'colore' => '#ff8c28'],
                ['dx' => '4px',  'dy' => '16px',  'dim' => 4, 'dur' => '1.05s', 'ritardo' => '1.08s', 'colore' => '#fff8dc'],
                ['dx' => '19px', 'dy' => '-24px', 'dim' => 3, 'dur' => '1.55s', 'ritardo' => '1.2s',  'colore' => 'var(--gold)'],
                ['dx' => '13px', 'dy' => '26px',  'dim' => 2, 'dur' => '1.3s',  'ritardo' => '1.32s', 'colore' => '#ffb457'],
                ['dx' => '32px', 'dy' => '-6px',  'dim' => 2, 'dur' => '1.8s',  'ritardo' => '1.44s', 'colore' => 'var(--gold-light)'],
                ['dx' => '7px',  'dy' => '-14px', 'dim' => 5, 'dur' => '1.0s',  'ritardo' => '1.56s', 'colore' => '#ffffff'],
                ['dx' => '22px', 'dy' => '14px',  'dim' => 3, 'dur' => '1.4s',  'ritardo' => '1.68s', 'colore' => '#ffcf6b'],
                ['dx' => '15px', 'dy' => '-4px',  'dim' => 4, 'dur' => '0.9s',  'ritardo' => '1.8s',  'colore' => '#fff3c4'],
            ];
            foreach ($faville as $f) {
                printf(
                    '<i class="obiettivo-favilla" style="--dx:%s;--dy:%s;--dim:%dpx;--dur:%s;--ritardo:%s;--tinta:%s"></i>',
                    h($f['dx']), h($f['dy']), (int) $f['dim'], h($f['dur']), h($f['ritardo']), h($f['colore'])
                );
            }
          ?>
        </div>
      </div>

      <?php if ($o['raggiunto']): ?>
        <p class="obiettivo-fatto">Obiettivo raggiunto — grazie a chi ha sostenuto il server.</p>
      <?php endif; ?>
    </section>
    <?php
}
