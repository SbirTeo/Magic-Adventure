<?php
/**
 * Le pagine dei singoli pacchetti non esistono piu': lo store vende solo Magix, e i
 * pacchetti VIP si comprano in gioco. Chi arriva da un vecchio collegamento (/pacchetto/<slug>)
 * finisce sullo store.
 */
header('Location: /store', true, 301);
exit;
