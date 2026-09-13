<?php
/**
 * Il conto alla rovescia per l'apertura del server.
 *
 * Sta in home fra il logo e gli articoli, appoggiato sulla pagina: il tag, il titolo, i
 * numeri e la data. Sotto corre una MICCIA lunga quanto tutto lo sviluppo del server: parte
 * dal giorno in cui i lavori sono cominciati (regolabile, di norma il primo luglio) e la
 * parte accesa, a sinistra, e' la strada gia' fatta. Sopra la miccia c'e' un personaggio con
 * la SKIN di chi guarda, che CORRE verso il traguardo: piu' il tempo passa, piu' si avvicina.
 *
 * La "carica" va da 0 (giorno dell'annuncio) a 1 (apertura): da lei dipendono il pezzo di
 * miccia gia' bruciato e il punto in cui sta il corridore.
 *
 * Si regola tutto dal gestionale (Aspetto -> Conto alla rovescia).
 *
 * Il fuso: il VPS lavora in UTC, ma l'ora scritta nel gestionale e' quella italiana. La
 * conversione avviene qui, in un posto solo (vedi countdown_istante()).
 */

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/helpers.php';
require_once __DIR__ . '/auth.php';

/** Il fuso in cui l'amministratore ragiona quando scrive una data. */
const COUNTDOWN_FUSO = 'Europe/Rome';

/**
 * L'inizio dello sviluppo, quando non e' scritto nel gestionale: il primo luglio.
 * E' da li' che parte la miccia, quindi la percentuale dice davvero "quanta strada e'
 * stata fatta dall'inizio dei lavori".
 */
const COUNTDOWN_INIZIO_PREDEFINITO = '07-01 00:00';

/**
 * L'istante scelto nel gestionale, letto come ora italiana.
 *
 * @param string $valore come lo salva il modulo: "2026-09-05T21:00" oppure "2026-09-05 21:00"
 */
function countdown_istante(string $valore): ?DateTimeImmutable {
    $valore = trim(str_replace('T', ' ', $valore));
    if ($valore === '') {
        return null;
    }
    try {
        return new DateTimeImmutable($valore, new DateTimeZone(COUNTDOWN_FUSO));
    } catch (Exception $e) {
        return null;   // data scritta male: la sezione si comporta come se non ci fosse
    }
}

/** "sabato 5 settembre 2026 alle 21:00", senza dipendere da estensioni di sistema. */
function countdown_data_estesa(DateTimeImmutable $quando): string {
    $giorni = ['Sunday' => 'domenica', 'Monday' => 'lunedì', 'Tuesday' => 'martedì',
               'Wednesday' => 'mercoledì', 'Thursday' => 'giovedì', 'Friday' => 'venerdì',
               'Saturday' => 'sabato'];
    $mesi = [1 => 'gennaio', 'febbraio', 'marzo', 'aprile', 'maggio', 'giugno', 'luglio',
             'agosto', 'settembre', 'ottobre', 'novembre', 'dicembre'];

    return $giorni[$quando->format('l')] . ' ' . (int) $quando->format('j') . ' '
        . $mesi[(int) $quando->format('n')] . ' ' . $quando->format('Y')
        . ' alle ' . $quando->format('H:i');
}

/**
 * Il primo luglio da cui far partire la miccia quando l'inizio non e' scritto a mano.
 *
 * Si prende quello dell'anno dell'apertura; se l'apertura cade PRIMA (poniamo a marzo),
 * quel primo luglio sarebbe nel futuro e la miccia partirebbe gia' finita: in quel caso
 * vale il primo luglio dell'anno prima.
 */
function countdown_inizio_predefinito(DateTimeImmutable $apertura): DateTimeImmutable {
    $fuso = new DateTimeZone(COUNTDOWN_FUSO);
    $inizio = new DateTimeImmutable($apertura->format('Y') . '-' . COUNTDOWN_INIZIO_PREDEFINITO, $fuso);
    if ($inizio >= $apertura) {
        $inizio = new DateTimeImmutable(((int) $apertura->format('Y') - 1) . '-' . COUNTDOWN_INIZIO_PREDEFINITO, $fuso);
    }
    return $inizio;
}

/**
 * Quanto e' acceso il portale adesso: 0 all'inizio del conto, 1 all'apertura.
 * Il valore di partenza lo calcola il server, cosi' il portale e' gia' giusto prima che
 * parta il JavaScript; poi lo aggiorna il browser a ogni secondo.
 */
function countdown_load(int $inizio, int $fine, int $adesso): float {
    if ($fine <= $inizio) {
        return 1.0;
    }
    return max(0.0, min(1.0, ($adesso - $inizio) / ($fine - $inizio)));
}

/**
 * Stampa la sezione. Non stampa nulla se e' spenta dal gestionale o se non c'e' una data.
 *
 * L'unica eccezione: un amministratore la vede comunque, con l'invito a impostarla — se
 * fosse invisibile anche a lui, dopo averla accesa senza data penserebbe a un guasto.
 */
function countdown_sezione(): void {
    if (site_setting('countdown_enabled', '0') !== '1') {
        return;
    }

    $quando = countdown_istante(site_setting('countdown_target', ''));
    if (!$quando) {
        if (is_admin()) {
            echo '<div class="alert alert-info" style="margin-bottom:26px;">Il conto alla rovescia è acceso '
               . 'ma non ha una data: <a href="/manage?section=theme#countdown">impostala qui</a>. '
               . 'Finché manca, i visitatori non lo vedono.</div>';
        }
        return;
    }

    // L'inizio dello sviluppo: e' da li' che parte la miccia, e la parte accesa dice quanta
    // strada e' stata fatta. Se non e' scritto nel gestionale vale il primo luglio.
    $partenza = countdown_istante(site_setting('countdown_start', ''))
        ?? countdown_inizio_predefinito($quando);

    $adesso = time();
    $fineTs = $quando->getTimestamp();
    $finito = $fineTs <= $adesso;
    $carica = countdown_load($partenza->getTimestamp(), $fineTs, $adesso);

    /**
     * Tre colori, non uno: il portale, la miccia e il testo si regolano a parte. Con un
     * colore solo, volendo la miccia verde diventava verde anche il portale del Nether.
     */
    $tinta = function (string $chiave, string $ripiego): string {
        $c = site_setting($chiave, $ripiego);
        return is_valid_hex_color($c) ? $c : $ripiego;
    };
    $colore = $tinta('countdown_color', '#c04ff0');            // il portale: viola di Nether
    $coloreMiccia = $tinta('countdown_fuse_color', '#c04ff0'); // la miccia: magenta
    $coloreTesto = $tinta('countdown_text_color', '#a3e635');  // numeri ed etichetta: lime

    $tag = trim(site_setting('countdown_tag', 'Apertura al pubblico'));
    $titolo = trim(site_setting('countdown_title', 'Il server apre fra'));
    $testo = trim(site_setting('countdown_text', ''));
    // Il titolo dell'apertura non puo' restare vuoto: sarebbe un portale muto proprio nel
    // momento in cui deve dire la cosa piu' importante.
    $titoloFine = trim(site_setting('countdown_done_title', '')) ?: 'Il server è APERTO';
    $testoFine = trim(site_setting('countdown_done_text', 'Entra adesso e prenditi il tuo territorio.'));
    $bottoneTesto = trim(site_setting('countdown_button_text', ''));
    $bottoneUrl = trim(site_setting('countdown_button_url', ''));

    // Al browser servono tre numeri: quando finisce, da quando si accende e che ora e' ADESSO
    // per il server. Con l'orologio del visitatore sballato (succede, sui telefoni) il conto
    // sarebbe sbagliato: partendo dallo scarto fra i due, invece, resta giusto per tutti.
    ?>
    <section class="nether<?= $finito ? ' is-aperto' : '' ?>"
             style="--cd:<?= h($colore) ?>; --cd-alone:<?= h(hex_to_rgba($colore, 0.45)) ?>;
                    --miccia:<?= h($coloreMiccia) ?>; --miccia-alone:<?= h(hex_to_rgba($coloreMiccia, 0.45)) ?>;
                    --cd-testo:<?= h($coloreTesto) ?>; --cd-testo-alone:<?= h(hex_to_rgba($coloreTesto, 0.4)) ?>;
                    --carica:<?= round($carica, 3) ?>"
             data-countdown
             data-fine="<?= $fineTs * 1000 ?>"
             data-inizio="<?= $partenza->getTimestamp() * 1000 ?>"
             data-adesso="<?= $adesso * 1000 ?>">
      <?php if (is_admin()): ?>
        <a href="/manage?section=theme#countdown" class="card-edit-btn nether-edit"
           title="Modifica il conto alla rovescia" aria-label="Modifica il conto alla rovescia">&#9998;</a>
      <?php endif; ?>

      <div class="nether-testo">
        <?php if ($tag !== ''): ?>
          <span class="nether-tag"><?= h($tag) ?></span>
        <?php endif; ?>

        <?php /* Le due facce stanno tutte e due nella pagina: quando il conto arriva a zero
                 il browser scambia la classe, senza ricaricare niente. */ ?>
        <div class="nether-attesa">
          <?php if ($titolo !== ''): ?>
            <p class="nether-titolo"><?= h($titolo) ?></p>
          <?php endif; ?>
          <p class="nether-tempo" role="timer" aria-live="off">
            <span class="nether-giorni" data-giorni hidden>
              <b data-g>--</b><span class="nether-unita">giorni</span>
            </span>
            <span class="nether-orologio">
              <b data-h>--</b><i>:</i><b data-m>--</b><i>:</i><b data-s>--</b>
            </span>
          </p>
          <p class="nether-quando">
            <?= h(countdown_data_estesa($quando)) ?>
            <span class="nether-fuso">ora italiana</span>
          </p>
        </div>

        <div class="nether-aperto">
          <p class="nether-annuncio"><?= h($titoloFine) ?></p>
          <?php if ($testoFine !== ''): ?>
            <p class="nether-nota"><?= h($testoFine) ?></p>
          <?php endif; ?>
        </div>

        <?php if ($testo !== ''): ?><p class="nether-nota"><?= h($testo) ?></p><?php endif; ?>
        <?php if ($bottoneTesto !== '' && $bottoneUrl !== ''): ?>
          <a class="btn nether-btn" href="<?= h($bottoneUrl) ?>"><?= h($bottoneTesto) ?></a>
        <?php endif; ?>
      </div>

      <?php /* La miccia va da sinistra a destra e brucia verso destra: la parte ACCESA
               (a sinistra della fiamma) e' il tratto gia' percorso, quella spenta e' quanto
               manca — cosi' si vede a colpo d'occhio quanta strada e' stata fatta. */ ?>
      <div class="nether-miccia" aria-hidden="true">
        <span class="nether-corda"></span>
        <span class="nether-accesa"></span>
        <span class="nether-fiamma"></span>
      </div>
    </section>
    <?php
}
