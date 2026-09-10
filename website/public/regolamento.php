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
header('Location: /tutorial#regolamento', true, 301);
exit;
