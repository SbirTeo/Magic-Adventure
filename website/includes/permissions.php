<?php
/**
 * Permessi del sito, assegnati ai GRUPPI IN GIOCO (LuckPerms).
 *
 * I gruppi non si creano qui: li specchia il plugin MagixWeb nella tabella `web_groups`
 * (nome, display, peso, colore del prefisso), cosi' l'elenco e' sempre lo stesso del gioco.
 * Qui si decide solo COSA puo' fare ciascun gruppo sul sito, riga per riga in
 * `web_group_permissions`. I permessi di un giocatore sono l'unione di quelli di TUTTI i
 * gruppi che ha (in `mc_ranks.groups_json`), non solo del gruppo primario.
 *
 * `users.is_admin` (il "web-admin") resta un super-utente: puo' tutto, sempre.
 */

require_once __DIR__ . '/db.php';

/**
 * Catalogo: categoria => [etichetta, permessi => descrizione]. E' la fonte di verita' della UI.
 *
 * Qui ci sono SOLO i permessi delegabili allo staff. La configurazione del sito
 * (pagine, navigazione, aspetto, banner VIP, impostazioni del blog e i permessi stessi)
 * NON e' assegnabile a nessun gruppo: resta esclusiva del web-admin, per scelta.
 * Vedi $adminOnlySections / $adminOnlyActions in manage.php.
 */
const WEB_PERMISSIONS = [
    'blog' => [
        'label' => 'Gestione blog',
        'perms' => [
            'blog.create'   => 'Creare articoli',
            'blog.edit'     => 'Modificare articoli',
            'blog.delete'   => 'Eliminare articoli',
            'blog.publish'  => 'Pubblicare o nascondere articoli',
        ],
    ],
    'forum' => [
        'label' => 'Gestione forum',
        'perms' => [
            'forum.category.create' => 'Creare categorie',
            'forum.category.edit'   => 'Modificare categorie',
            'forum.category.delete' => 'Eliminare categorie',
            'forum.topic.pin'       => 'Mettere in evidenza le discussioni',
            'forum.topic.lock'      => 'Chiudere le discussioni',
            'forum.topic.delete'    => 'Eliminare le discussioni',
            'forum.topic.move'      => 'Spostare le discussioni di categoria',
        ],
    ],
    'chat' => [
        'label' => 'Chat live',
        'perms' => [
            'chat.moderate' => 'Eliminare i messaggi della chat in home',
            // Separato da chat.moderate apposta: togliere UN messaggio sbagliato e svuotare
            // la chat davanti a tutti sono due gesti di peso diverso, e chi puo' fare il
            // primo non e' detto debba poter fare il secondo.
            'chat.clear'    => 'Svuotare tutta la chat in home',
        ],
    ],
    'users' => [
        'label' => 'Utenti',
        'perms' => [
            'users.view'   => 'Vedere la lista utenti',
            'users.manage' => 'Assegnare o togliere il ruolo web-admin',
        ],
    ],
    // Le sanzioni le decide il gioco (MagixGuard): qui si delega solo chi le CONTROLLA
    // dal sito. I tetti di durata per grado restano nel plugin, dove si sanziona davvero.
    'sanzioni' => [
        'label' => 'Sanzioni',
        'perms' => [
            'sanzioni.view'    => 'Vedere l\'archivio completo, le prove e i ricorsi',
            'sanzioni.coda'    => 'Confermare o respingere le sanzioni proposte',
            'sanzioni.ricorsi' => 'Rispondere ai ricorsi e deciderli',
            'sanzioni.revoca'  => 'Revocare una sanzione gia\' applicata',
        ],
    ],
];

/** Tutti i codici permesso esistenti, in un unico elenco piatto. */
function all_web_permissions(): array {
    $out = [];
    foreach (WEB_PERMISSIONS as $group) {
        foreach ($group['perms'] as $code => $_) {
            $out[] = $code;
        }
    }
    return $out;
}

/** I gruppi in gioco di un utente (da mc_ranks.groups_json). Vuoto se non ancora sincronizzato. */
function user_groups(?array $user): array {
    if (!$user) {
        return [];
    }
    $groups = json_decode((string) ($user['groups_json'] ?? ''), true);
    if (!is_array($groups)) {
        return [];
    }
    return array_values(array_filter(array_map('strval', $groups), fn($g) => $g !== ''));
}

/** Permessi effettivi dell'utente loggato: unione di quelli di tutti i suoi gruppi. */
function my_permissions(): array {
    static $perms = null;
    if ($perms !== null) {
        return $perms;
    }

    $groups = user_groups(current_user());
    if (!$groups) {
        return $perms = [];
    }

    $in = implode(',', array_fill(0, count($groups), '?'));
    $stmt = db()->prepare("SELECT DISTINCT permission FROM web_group_permissions WHERE group_name IN ($in)");
    $stmt->execute($groups);

    return $perms = $stmt->fetchAll(PDO::FETCH_COLUMN);
}

/** True se l'utente loggato ha il permesso (il web-admin ha sempre tutto). */
function can(string $permission): bool {
    if (is_admin()) {
        return true;
    }
    return in_array($permission, my_permissions(), true);
}

/** True se ha almeno uno dei permessi indicati. */
function can_any(array $permissions): bool {
    foreach ($permissions as $p) {
        if (can($p)) {
            return true;
        }
    }
    return false;
}

/** True se puo' entrare nel gestionale: il web-admin sempre, lo staff se ha almeno un permesso. */
function can_manage(): bool {
    return is_admin() || can_any(all_web_permissions());
}

/** Blocca la pagina se manca il permesso. */
function require_perm(string $permission): void {
    if (!can($permission)) {
        http_response_code(403);
        die('Non hai il permesso necessario per questa operazione.');
    }
}

/**
 * True se l'account e' un web-admin protetto (vedi PROTECTED_ADMIN_UUIDS in config.php):
 * il sito non deve permettere in nessun caso di togliergli il ruolo.
 */
function is_protected_admin(?array $user): bool {
    if (!$user || empty($user['mc_uuid'])) {
        return false;
    }
    return in_array(strtolower((string) $user['mc_uuid']), array_map('strtolower', PROTECTED_ADMIN_UUIDS), true);
}

/** Gruppi del gioco specchiati dal plugin, dal piu' importante al meno importante. */
function web_groups(): array {
    return db()->query('SELECT name, display, weight, color FROM web_groups ORDER BY weight DESC, name ASC')->fetchAll();
}

/** Permessi assegnati, come [nome_gruppo => [codice, codice, ...]]. */
function web_group_permissions(): array {
    $out = [];
    foreach (db()->query('SELECT group_name, permission FROM web_group_permissions')->fetchAll() as $row) {
        $out[$row['group_name']][] = $row['permission'];
    }
    return $out;
}
