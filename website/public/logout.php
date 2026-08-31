<?php
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/helpers.php';
// Chi esce non deve restare "sul sito ora" per altri cinque minuti: la presenza si calcola
// da `last_seen`, che nessuno aggiorna piu' quando il browser si chiude. Qui la si porta
// indietro esattamente alla soglia (PRESENZA_MINUTI), cosi' il pallino verde si spegne
// subito e l'ultima visita resta comunque "pochi minuti fa", che e' la verita'.
if (!empty($_SESSION['user_id'])) {
    try {
        db()->prepare('UPDATE users SET last_seen = DATE_SUB(NOW(), INTERVAL ' . PRESENZA_MINUTI . ' MINUTE) WHERE id = ?')
            ->execute([(int) $_SESSION['user_id']]);
    } catch (PDOException $e) {
        // uscire deve funzionare comunque: se il database fa i capricci, pazienza
    }
}
unset($_SESSION['user_id']);
remember_forget(); // uscendo si butta via anche il "resta collegato" di questo browser
session_regenerate_id(true);
redirect('/');
