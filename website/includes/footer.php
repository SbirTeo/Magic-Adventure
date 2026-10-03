<?php if (!empty($__conSidebar)): // chiude la griglia aperta in header.php ?>
  </div>
  <?php sidebar_colonna(); ?>
</div>
<?php endif; ?>
</main>
<footer class="site-footer">
  <div class="wrap footer-inner">
    <?php /* Le pagine legali devono essere raggiungibili da ogni pagina: qui in fondo, dove le si cerca. */ ?>
    <nav class="footer-link" aria-label="Informazioni">
      <a href="/tutorial#regolamento">Regolamento</a>
      <a href="/privacy">Privacy</a>
      <a href="/cookie">Cookie</a>
      <a href="/termini">Termini di vendita</a>
    </nav>
    <p>&copy; <?= date('Y') ?> <?= h(site_setting('site_name', 'MAGICADVENTURE')) ?> — server Minecraft. Gioca su <strong>mc.magicadventure.it</strong></p>
    <p class="footer-nota">Non affiliato a Mojang Studios né a Microsoft. Minecraft è un marchio di Mojang AB.</p>
  </div>
</footer>
<?php
// Stesso cache-busting del CSS (?v=<data di modifica>): senza, dopo un deploy il browser
// continuerebbe a usare la copia vecchia degli script — gia' successo piu' volte col CSS.
$__siteVer = @filemtime(__DIR__ . '/../public/assets/js/site.js') ?: time();
$__chatVer = @filemtime(__DIR__ . '/../public/assets/js/chat.js') ?: time();
?>
<script src="/assets/js/site.js?v=<?= $__siteVer ?>"></script>
<script src="/assets/js/chat.js?v=<?= $__chatVer ?>"></script>
</body>
</html>
<?php
// Chiude il buffer aperto in header.php e traduce tutta la pagina in un colpo solo (vedi
// translate_html): un'unica passata su tutto l'HTML, invece di una traduzione sparsa dentro
// ogni singola pagina, cosi' nessuna pagina puo' dimenticarsi di farlo.
if (ob_get_level() > 0) {
    echo translate_html(ob_get_clean(), $GLOBALS['__siteLang'] ?? 'it');
}
