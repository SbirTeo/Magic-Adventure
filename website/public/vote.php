<?php
/**
 * Scorciatoia per votare: /vote
 *
 * Reindirizza alla pagina del server su minecraft-italia.net. L'indirizzo e' lo stesso del
 * tasto "Vota" (vote_url in site_settings), cosi' si cambia in un posto solo; se e' vuoto
 * il voto e' spento e si torna in home.
 */
require_once __DIR__ . '/../includes/db.php';
require_once __DIR__ . '/../includes/helpers.php';

$url = site_setting('vote_url', 'https://minecraft-italia.net/lista/server/magic-adventure');
header('Cache-Control: no-store');
header('Location: ' . ($url !== '' ? $url : '/'), true, 302);
exit;
