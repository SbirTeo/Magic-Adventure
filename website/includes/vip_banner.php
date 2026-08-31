<?php
/**
 * La striscia di promozione VIP, in un posto solo.
 *
 * La usano la home e lo store: si regola tutta dal gestionale (Aspetto → Banner VIP),
 * quindi una modifica li' cambia le due pagine insieme. Prima il markup viveva dentro
 * index.php e lo store aveva una vetrina sua, con due aspetti da tenere allineati a mano.
 */

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/helpers.php';
require_once __DIR__ . '/auth.php';
require_once __DIR__ . '/store_card.php';   // store_prezzo(): stesso calcolo dello store

/**
 * Stampa il banner. Non stampa nulla se e' spento dal gestionale.
 *
 * @param bool $compatto versione bassa, per la cima dello store (dove fa da intestazione)
 */
function vip_banner(bool $compatto = false): void {
    if (site_setting('vip_banner_enabled', '1') !== '1') {
        return;
    }

    $vipImage = trim(site_setting('vip_banner_image', ''));

    // Link della promozione: di norma e' il pacchetto in evidenza dello store, cosi' basta
    // spostare la vetrina dal gestionale perche' cambi anche il banner. Se la spunta e' tolta
    // (o se nessun pacchetto e' in evidenza) vale l'indirizzo scritto a mano.
    $vipUrl = site_setting('vip_banner_button_url', '#');
    $vipPkg = null;
    if (site_setting('vip_banner_follow_featured', '1') === '1') {
        $vipPkg = store_pacchetto_evidenza();
        $vipUrl = store_link_evidenza() ?? $vipUrl;
    }

    // Se il banner non ha una sua immagine, prende la copertina del pacchetto promosso:
    // cosi' la promozione mostra davvero cio' che sta promuovendo.
    if ($vipImage === '' && $vipPkg && !empty($vipPkg['image_url'])) {
        $vipImage = $vipPkg['image_url'];
    }

    // Su telefono cornice luccicante e alone dorato si spengono a parte: sul grande sono
    // due tocchi di lusso, su uno schermo stretto (dove il banner e' una fascia a tutta
    // larghezza) diventano rumore. Due spunte apposta in Aspetto -> Banner VIP.
    $classi = 'vip-banner'
        . ($vipImage !== '' ? ' has-image' : '')
        . (site_setting('vip_banner_border_anim', '1') === '1' ? ' has-border-anim' : '')
        . (site_setting('vip_banner_border_anim_mobile', '0') === '1' ? '' : ' senza-cornice-mobile')
        . (site_setting('vip_banner_glow_mobile', '0') === '1' ? '' : ' senza-alone-mobile')
        . ($compatto ? ' is-compatto' : '');
    ?>
    <div class="<?= $classi ?>"<?= $vipImage !== '' ? ' style="--vip-banner-image:url(\'' . h($vipImage) . '\')"' : '' ?>>
      <div class="vip-banner-shine" aria-hidden="true"></div>
      <?php if (is_admin()): ?>
        <a href="/manage?section=theme#banner-vip" class="vip-banner-edit" title="Modifica" aria-label="Modifica">✎</a>
      <?php endif; ?>
      <div class="vip-banner-icon" aria-hidden="true"><?= h(site_setting('vip_banner_icon', '👑')) ?></div>
      <div class="vip-banner-text">
        <span class="vip-banner-tag"><?= h(site_setting('vip_banner_tag', '')) ?></span>
        <h3><?= h(site_setting('vip_banner_title', '')) ?></h3>
        <p><?= h(site_setting('vip_banner_text', '')) ?></p>
        <?php if ($vipPkg): ?>
          <?php /* Riferimento esplicito al pacchetto in evidenza: nome e prezzo veri, presi dallo
                   store. Cambiando la vetrina dal gestionale cambia anche questa riga. */ ?>
          <?php $vipPrezzo = store_prezzo($vipPkg); // stesso calcolo dello store: sconti compresi ?>
          <?php /* La targhetta porta alla pagina del pacchetto promosso: e' la cosa che si
                   guarda per prima nel banner, ed era l'unica non cliccabile. */ ?>
          <a class="vip-banner-pacchetto" href="/pacchetto/<?= h(rawurlencode($vipPkg['slug'])) ?>">
            <span class="nome"><?= h($vipPkg['name']) ?></span>
            <span class="prezzo">
              <?php if ($vipPrezzo['tipo']): ?>
                <s class="store-prezzo-pieno"><?= h(number_format($vipPrezzo['pieno'], 2, ',', '.')) ?></s>
              <?php endif; ?>
              <?= h(number_format($vipPrezzo['finale'], 2, ',', '.')) ?>
              <small><?= h(site_setting('store_currency', 'EUR')) ?></small>
            </span>
            <?php if ($vipPrezzo['tipo']): ?>
              <span class="store-sconto"><?= h($vipPrezzo['etichetta']) ?></span>
            <?php endif; ?>
          </a>
        <?php endif; ?>
      </div>
      <a href="<?= h($vipUrl) ?>" class="btn btn-gold"><?= h(site_setting('vip_banner_button_text', 'Scopri di più')) ?></a>
    </div>
    <?php
}
