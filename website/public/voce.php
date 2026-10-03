<?php
/**
 * /voce — vecchio indirizzo della chat vocale: ora è /voice. Resta per i link già girati.
 * Il pezzo dopo # (la stanza) lo tiene il browser da solo nel reindirizzamento.
 */
require_once __DIR__ . '/../includes/url-language.php';
header('Location: /voice', true, 301);
exit;
