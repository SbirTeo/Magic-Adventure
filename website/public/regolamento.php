<?php
/**
 * /regolamento — reindirizzamento.
 *
 * Il regolamento non e' piu' una pagina a se': ora vive dentro /tutorial, nella scheda
 * "Regolamento" (guida e regolamento condividono la voce di menu e si scambiano senza
 * ricaricare). Questo indirizzo resta per i vecchi link e i segnalibri: manda alla
 * scheda giusta con un reindirizzamento permanente. Il testo si continua a modificare
 * dal gestionale, la pagina resta.
 */
// Da /en/regolamento a /en/tutorial: il redirect resta nella lingua dell'indirizzo.
require_once __DIR__ . '/../includes/url-language.php';
header('Location: /tutorial#regolamento', true, 301);
exit;
