<?php
/**
 * Pagine legali (privacy, cookie, termini di vendita): i dati di chi gestisce il sito.
 *
 * I testi stanno nelle tre pagine (public/privacy.php, cookie.php, termini.php) e descrivono
 * quello che il sito fa davvero: se cambia qualcosa (un cookie nuovo, un servizio esterno, un
 * dato raccolto in piu') va cambiata anche la pagina, nello stesso commit.
 *
 * Chi e' il titolare non lo puo' sapere il codice: nome, indirizzo, contatto e partita IVA si
 * scrivono nel gestionale (Aspetto -> Dati legali). Finche' mancano, al loro posto compare un
 * segnaposto ben visibile e lo staff vede un avviso in cima alla pagina: una privacy senza
 * titolare non vale niente, ed e' meglio che si veda.
 */
require_once __DIR__ . '/helpers.php';

/** Le chiavi in site_settings, con l'etichetta che si legge nel gestionale. */
const LEGAL_FIELDS = [
    'legal_owner'   => 'Titolare (nome e cognome, oppure ragione sociale)',
    'legal_address' => 'Indirizzo (residenza o sede)',
    'legal_email'   => 'Email per privacy, ordini e reclami',
    'legal_vat'     => 'Partita IVA o codice fiscale (se c\'è)',
];

/** Data dell'ultima revisione dei testi: si aggiorna a mano quando si cambia una pagina. */
const LEGAL_UPDATED = '3 ottobre 2026';

/** Il valore di un campo, gia' pronto per la pagina (con il segnaposto se manca). */
function legal_value(string $chiave): string {
    $valore = trim(site_setting($chiave, ''));
    if ($valore === '') {
        return '<span class="legale-mancante">[' . h(LEGAL_FIELDS[$chiave] ?? $chiave) . ' — da completare]</span>';
    }
    if ($chiave === 'legal_email') {
        return '<a href="mailto:' . h($valore) . '">' . h($valore) . '</a>';
    }
    return h($valore);
}

/** True se ci sono almeno titolare, indirizzo e contatto: il minimo perche' i testi valgano. */
function legal_ready(): bool {
    foreach (['legal_owner', 'legal_address', 'legal_email'] as $k) {
        if (trim(site_setting($k, '')) === '') {
            return false;
        }
    }
    return true;
}

/**
 * Testata comune delle tre pagine: titolo, data di revisione, avviso allo staff se mancano
 * i dati del titolare, e i collegamenti alle altre due.
 */
function legal_header(string $titolo, string $attiva): void {
    $pagine = ['privacy' => 'Privacy', 'cookie' => 'Cookie', 'termini' => 'Termini di vendita'];
    ?>
    <h1 class="page-title"><?= h($titolo) ?></h1>
    <p class="legale-data">Ultimo aggiornamento: <?= h(LEGAL_UPDATED) ?></p>
    <nav class="legale-indice" aria-label="Documenti legali">
      <?php foreach ($pagine as $slug => $nome): ?>
        <a href="/<?= $slug ?>"<?= $slug === $attiva ? ' class="is-attiva" aria-current="page"' : '' ?>><?= h($nome) ?></a>
      <?php endforeach; ?>
    </nav>
    <?php if (!legal_ready() && is_admin()): ?>
      <div class="alert alert-error">
        <strong>Mancano i dati del titolare.</strong> Senza nome, indirizzo e contatto questi testi
        non valgono. Si completano in <a href="/manage?section=theme#dati-legali">Gestione → Aspetto → Dati legali</a>.
      </div>
    <?php endif; ?>
    <?php
}
