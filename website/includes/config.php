<?php
// Configurazione sito MAGICADVENTURE — NON pubblico (fuori da public/)

define('DB_HOST', '127.0.0.1');
define('DB_NAME', 'magicadventure_web');
define('DB_USER', 'magicweb');
define('DB_PASS', 'CHANGE_ME_DB_PASSWORD');

define('SITE_NAME', 'MAGICADVENTURE');
// Dominio canonico (senza www: il www ci reindirizza qui). Usato per gli URL assoluti
// che devono tornare al sito, ad esempio il rientro da PayPal dopo il pagamento.
define('SITE_URL', 'https://magicadventure.it');

// Non serve piu' a niente: /link non esiste, l'account nasce in gioco e le stesse
// credenziali aprono il sito. Resta definita perche' qualche pagina vecchia potrebbe
// ancora nominarla, e una costante mancante e' un errore fatale in PHP.
define('LINK_CODE_TTL_MINUTES', 10);

// Chiave con cui si cifrano i segreti della verifica in due passaggi (OTP) prima di
// metterli nel database: 32 byte in base64. Senza di questa, chiunque legga la tabella
// `users` — o entri da phpMyAdmin — potrebbe generare i codici a sei cifre degli admin.
//
// ATTENZIONE: se questa chiave cambia o si perde, i segreti gia' salvati diventano
// illeggibili e TUTTI gli admin devono riconfigurare la verifica (il sito glielo chiede
// da solo al primo accesso, quindi nessuno resta fuori). Va copiata identica nella
// configurazione del plugin MagixWeb, che verifica gli stessi codici in gioco.
define('OTP_CHIAVE', 'I4pMryhCrdnuKpNfmEN031ltc6pvSJK+VYT1arrNO2s=');

// Account il cui ruolo web-admin NON e' revocabile dal sito, da nessuno e in nessun modo:
// nemmeno da un altro web-admin, nemmeno forzando la richiesta a mano. L'unico modo per
// toglierlo e' un comando diretto sul database via SSH, es:
//   sudo mariadb -e "UPDATE magicadventure_web.users SET is_admin=0 WHERE mc_uuid='...'"
// Identificati per UUID Minecraft (stabile) e non per nome, che puo' cambiare.
define('PROTECTED_ADMIN_UUIDS', [
    'ace7e765-2fe1-4cd4-99b9-56564964fc03', // DorinoJ (proprietario)
]);
