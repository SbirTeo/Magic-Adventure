<?php
/**
 * Campo "indirizzo di un'immagine" con il pulsante Scegli, usato in tutto il gestionale.
 *
 * Si puo' sempre incollare un indirizzo a mano (anche di un altro sito); il pulsante
 * carica un file dal computer, lo salva sul sito e scrive l'indirizzo nel campo.
 * Il caricamento vero lo fa /api/carica-immagine; qui c'e' solo il modulo.
 */

if (!function_exists('campo_immagine')) {
    /**
     * @param string $id        id del campo di testo (per l'etichetta)
     * @param string $nome      name del campo, come lo legge il modulo
     * @param string $valore    indirizzo attuale
     * @param string $etichetta testo dell'etichetta
     * @param string $aiuto     riga di spiegazione sotto il campo (HTML gia' pronto)
     */
    function campo_immagine(string $id, string $nome, string $valore, string $etichetta, string $aiuto = ''): void {
        ?>
        <div class="campo-immagine" data-campo-immagine>
          <label for="<?= h($id) ?>"><?= h($etichetta) ?></label>
          <div class="campo-immagine-riga">
            <input type="text" id="<?= h($id) ?>" name="<?= h($nome) ?>" placeholder="https://… oppure carica un file"
                   value="<?= h($valore) ?>" data-indirizzo>
            <button type="button" class="btn btn-ghost btn-small" data-scegli>Scegli</button>
            <?php /* Il campo file resta nascosto: lo apre il pulsante, cosi' l'aspetto e'
                     quello degli altri pulsanti del gestionale e non quello del browser. */ ?>
            <input type="file" accept="image/jpeg,image/png,image/gif,image/webp" hidden data-file>
          </div>
          <p class="campo-immagine-stato" data-stato hidden></p>
          <div class="campo-immagine-anteprima"<?= $valore === '' ? ' hidden' : '' ?> data-anteprima>
            <img src="<?= h($valore) ?>" alt="" data-img>
          </div>
          <?php if ($aiuto !== ''): ?>
            <p style="color:var(--text-dim); font-size:12px; margin:6px 0 0;"><?= $aiuto ?></p>
          <?php endif; ?>
        </div>
        <?php
    }
}
