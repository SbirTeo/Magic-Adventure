<?php if (!empty($__conSidebar)): // chiude la griglia aperta in header.php ?>
  </div>
  <?php sidebar_colonna(); ?>
</div>
<?php endif; ?>
</main>
<footer class="site-footer">
  <div class="wrap footer-inner">
    <p>&copy; <?= date('Y') ?> <?= h(site_setting('site_name', 'MAGICADVENTURE')) ?> — server Minecraft. Gioca su <strong>mc.magicadventure.it</strong></p>
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
