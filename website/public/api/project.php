<?php
/**
 * API della scheda "Progetto" del gestionale: lo spazio di lavoro condiviso fra i web-admin.
 * Bacheca delle attivita' (idee -> da fare -> in corso -> fatto), obiettivi con avanzamento,
 * calendario degli appuntamenti, chat privata (immagini, risposte, reazioni, modifiche,
 * messaggi fissati, "sta scrivendo", spunte di lettura) e registro di chi ha fatto cosa.
 *
 * Solo web-admin (is_admin()): NON e' un permesso delegabile ai gruppi, come le altre schede
 * di configurazione del sito. Risponde SEMPRE in JSON, anche sugli errori.
 *
 *   GET  ?action=state                     tutto lo stato (primo caricamento e dopo ogni modifica)
 *   GET  ?action=poll&after=<id>&since=<t>&rev=<n>
 *                                          messaggi nuovi/cambiati, chi scrive, letture, revisione
 *   POST action=...                        vedi handle_post() (tutte con gettone CSRF)
 */
require_once __DIR__ . '/../../includes/auth.php';
require_once __DIR__ . '/../../includes/helpers.php';
require_once __DIR__ . '/../../includes/immagini.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

const PROJECT_MSG_MAX = 4000;
const PROJECT_HISTORY = 300;
const PROJECT_REACTIONS = ['👍', '❤️', '😂', '🎉', '👀', '✅', '🔥', '🤔'];
const PROJECT_STATUSES = ['idea', 'todo', 'doing', 'done'];
const PROJECT_PRIORITIES = ['low', 'normal', 'high'];

function project_json(array $data, int $code = 200): void {
    http_response_code($code);
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}

function project_fail(string $message, int $code = 400): void {
    project_json(['ok' => false, 'error' => $message], $code);
}

if (!is_logged_in()) {
    project_fail('Devi accedere.', 401);
}
if (!is_admin()) {
    project_fail('Riservato agli amministratori del sito.', 403);
}
$me = current_user();
$myId = (int) $me['id'];

// ------------------------------------------------------------------------------------ utilita'

/** Una riga nel registro ("Teo ha spostato «X» in Fatto"): la leggono tutti e due. */
function project_log(int $userId, string $text, ?int $taskId = null): void {
    $st = db()->prepare('INSERT INTO project_activity (user_id, text, task_id) VALUES (?, ?, ?)');
    $st->execute([$userId, mb_substr($text, 0, 255), $taskId]);
}

function project_str(string $key, int $max, bool $required = false): ?string {
    $v = trim((string) ($_POST[$key] ?? ''));
    if ($v === '') {
        if ($required) project_fail('Campo obbligatorio mancante.');
        return null;
    }
    return mb_substr($v, 0, $max);
}

function project_date(string $key): ?string {
    $v = trim((string) ($_POST[$key] ?? ''));
    if ($v === '') return null;
    $d = DateTime::createFromFormat('Y-m-d', $v);
    return $d && $d->format('Y-m-d') === $v ? $v : null;
}

function project_datetime(string $key): ?string {
    $v = trim((string) ($_POST[$key] ?? ''));
    if ($v === '') return null;
    foreach (['Y-m-d\TH:i', 'Y-m-d H:i', 'Y-m-d\TH:i:s', 'Y-m-d H:i:s'] as $f) {
        $d = DateTime::createFromFormat($f, $v);
        if ($d) return $d->format('Y-m-d H:i:s');
    }
    return null;
}

function project_color(string $key, string $default): string {
    $v = trim((string) ($_POST[$key] ?? ''));
    return preg_match('/^#[0-9a-fA-F]{6}$/', $v) ? strtolower($v) : $default;
}

/** L'id di un amministratore, o null (nessuno / non piu' admin). */
function project_admin_id($raw): ?int {
    $id = (int) $raw;
    if ($id <= 0) return null;
    $st = db()->prepare('SELECT id FROM users WHERE id = ? AND is_admin = 1');
    $st->execute([$id]);
    return $st->fetchColumn() ? $id : null;
}

function project_status_label(string $s): string {
    return ['idea' => 'Idee', 'todo' => 'Da fare', 'doing' => 'In corso', 'done' => 'Fatto'][$s] ?? $s;
}

/** Salva un'immagine allegata alla chat (stesse difese del pulsante "Scegli" del gestionale). */
function project_store_image(array $file): string {
    if ($file['error'] !== UPLOAD_ERR_OK) project_fail('Caricamento dell\'immagine non riuscito.');
    if ($file['size'] > 8 * 1024 * 1024) project_fail('Immagine troppo pesante: il limite è 8 MB.');
    if (!is_uploaded_file($file['tmp_name'])) project_fail('Caricamento non valido.');
    $info = @getimagesize($file['tmp_name']);
    $ext = [IMAGETYPE_JPEG => 'jpg', IMAGETYPE_PNG => 'png', IMAGETYPE_GIF => 'gif', IMAGETYPE_WEBP => 'webp'];
    if (!$info || !isset($ext[$info[2]])) project_fail('Formato non supportato: usa JPG, PNG, GIF o WEBP.');
    $dir = __DIR__ . '/../assets/img/caricate';
    if (!is_dir($dir) && !@mkdir($dir, 0755, true)) project_fail('Cartella delle immagini non disponibile.', 500);
    $name = date('Ymd') . '-' . bin2hex(random_bytes(6)) . '.' . $ext[$info[2]];
    if (!move_uploaded_file($file['tmp_name'], $dir . '/' . $name)) project_fail('Salvataggio non riuscito.', 500);
    @chmod($dir . '/' . $name, 0644);
    immagine_ottimizza($dir . '/' . $name);
    return '/assets/img/caricate/' . $name;
}

/** Messaggi con le loro reazioni, pronti per la pagina. */
function project_messages(string $where, array $args): array {
    $st = db()->prepare("SELECT id, user_id, body, image_url, reply_to, created_at, edited_at,
                                deleted_at, pinned, updated_at
                         FROM project_messages WHERE $where ORDER BY id");
    $st->execute($args);
    $rows = $st->fetchAll(PDO::FETCH_ASSOC);
    if (!$rows) return [];
    $ids = array_column($rows, 'id');
    $in = implode(',', array_map('intval', $ids));
    $reactions = [];
    foreach (db()->query("SELECT message_id, user_id, emoji FROM project_message_reactions WHERE message_id IN ($in)") as $r) {
        $reactions[(int) $r['message_id']][$r['emoji']][] = (int) $r['user_id'];
    }
    $out = [];
    foreach ($rows as $r) {
        $deleted = $r['deleted_at'] !== null;
        $out[] = [
            'id' => (int) $r['id'],
            'user' => (int) $r['user_id'],
            'body' => $deleted ? '' : $r['body'],
            'image' => $deleted ? null : $r['image_url'],
            'reply_to' => $r['reply_to'] !== null ? (int) $r['reply_to'] : null,
            'at' => $r['created_at'],
            'edited' => !$deleted && $r['edited_at'] !== null,
            'deleted' => $deleted,
            'pinned' => !$deleted && (int) $r['pinned'] === 1,
            'reactions' => $deleted ? (object) [] : (object) ($reactions[(int) $r['id']] ?? []),
        ];
    }
    return $out;
}

function project_reads(int $myId): array {
    $out = ['read' => [], 'typing' => []];
    foreach (db()->query('SELECT user_id, last_message_id, typing_until > NOW() AS typing, seen_at FROM project_reads') as $r) {
        $uid = (int) $r['user_id'];
        $out['read'][$uid] = (int) $r['last_message_id'];
        if ($uid !== $myId && (int) $r['typing'] === 1) $out['typing'][] = $uid;
    }
    $out['read'] = (object) $out['read'];
    return $out;
}

/**
 * Cambia a ogni modifica di bacheca, obiettivi o appuntamenti: la pagina dell'altro, vedendola
 * cambiare nel sondaggio, ricarica lo stato. Ultima riga del registro + ultimo ritocco a una
 * scheda (il riordino dentro una colonna non finisce nel registro, ma deve comunque arrivare).
 */
function project_revision(): string {
    return db()->query("SELECT CONCAT((SELECT COALESCE(MAX(id), 0) FROM project_activity), '-',
                                      (SELECT COALESCE(UNIX_TIMESTAMP(MAX(updated_at)), 0) FROM project_tasks))")->fetchColumn();
}

// ------------------------------------------------------------------------------------ GET

if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    // Solo lettura da qui in poi: la sessione non serve piu' e non deve bloccare le altre
    // richieste dello stesso browser (il sondaggio ogni pochi secondi).
    session_write_close();
    $action = $_GET['action'] ?? 'state';

    if ($action === 'poll') {
        $after = max(0, (int) ($_GET['after'] ?? 0));
        $since = (string) ($_GET['since'] ?? '');
        if (!preg_match('/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/', $since)) $since = '1970-01-01 00:00:00';
        $now = db()->query('SELECT NOW()')->fetchColumn();
        project_json([
            'ok' => true,
            'now' => $now,
            'rev' => project_revision(),
            // I nuovi, e quelli gia' in pagina ma cambiati (modificati, cancellati, reazioni).
            'messages' => project_messages('id > ? OR updated_at >= ?', [$after, $since]),
        ] + project_reads($myId));
    }

    // Stato completo.
    $admins = [];
    foreach (db()->query('SELECT id, mc_username, mc_uuid, premium_uuid FROM users WHERE is_admin = 1 ORDER BY id') as $u) {
        $admins[] = [
            'id' => (int) $u['id'],
            'name' => $u['mc_username'],
            'avatar' => mc_avatar_url($u['mc_uuid'], 64, $u['premium_uuid'] ?? null),
        ];
    }
    $goals = db()->query('SELECT id, title, description, color, due_date, sort_order FROM project_goals ORDER BY sort_order, id')->fetchAll(PDO::FETCH_ASSOC);
    $tasks = db()->query('SELECT id, title, notes, status, priority, assignee_id, goal_id, due_date, sort_order,
                                 created_by, created_at, completed_at
                          FROM project_tasks ORDER BY sort_order, id')->fetchAll(PDO::FETCH_ASSOC);
    $events = db()->query('SELECT id, title, notes, starts_at, ends_at, all_day, color, created_by
                           FROM project_events ORDER BY starts_at')->fetchAll(PDO::FETCH_ASSOC);
    $activity = db()->query('SELECT id, user_id, text, task_id, created_at FROM project_activity ORDER BY id DESC LIMIT 60')->fetchAll(PDO::FETCH_ASSOC);
    // Attivita' chiuse per settimana (lunedi' di inizio), ultime 12 settimane.
    $weekly = db()->query("SELECT DATE_SUB(DATE(completed_at), INTERVAL WEEKDAY(completed_at) DAY) AS week, COUNT(*) AS n
                           FROM project_tasks
                           WHERE status = 'done' AND completed_at >= DATE_SUB(CURDATE(), INTERVAL 12 WEEK)
                           GROUP BY week ORDER BY week")->fetchAll(PDO::FETCH_ASSOC);
    $minId = (int) db()->query('SELECT COALESCE(MAX(id), 0) FROM project_messages')->fetchColumn() - PROJECT_HISTORY;
    $myRead = db()->prepare('SELECT last_activity_id FROM project_reads WHERE user_id = ?');
    $myRead->execute([$myId]);

    $ints = function (array $rows, array $keys): array {
        foreach ($rows as &$r) foreach ($keys as $k) if ($r[$k] !== null) $r[$k] = (int) $r[$k];
        return $rows;
    };
    project_json([
        'ok' => true,
        'me' => $myId,
        'now' => db()->query('SELECT NOW()')->fetchColumn(),
        'rev' => project_revision(),
        'admins' => $admins,
        'goals' => $ints($goals, ['id', 'sort_order']),
        'tasks' => $ints($tasks, ['id', 'assignee_id', 'goal_id', 'sort_order', 'created_by']),
        'events' => $ints($events, ['id', 'all_day', 'created_by']),
        'activity' => $ints($activity, ['id', 'user_id', 'task_id']),
        'activity_seen' => (int) ($myRead->fetchColumn() ?: 0),
        'weekly' => $ints($weekly, ['n']),
        'messages' => project_messages('id > ?', [$minId]),
        'reactions' => PROJECT_REACTIONS,
    ] + project_reads($myId));
}

// ------------------------------------------------------------------------------------ POST

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    project_fail('Metodo non ammesso.', 405);
}
// csrf_check() risponderebbe in HTML: qui serve JSON.
if (empty($_SESSION['csrf']) || !hash_equals($_SESSION['csrf'], (string) ($_POST['csrf'] ?? ''))) {
    project_fail('Sessione scaduta: ricarica la pagina e riprova.', 419);
}
session_write_close();

$action = (string) ($_POST['action'] ?? '');
$name = $me['mc_username'];

switch ($action) {
    // ---------------------------------------------------------------- attivita'
    case 'task_save': {
        $id = (int) ($_POST['id'] ?? 0);
        $title = project_str('title', 160, true);
        $status = in_array($_POST['status'] ?? '', PROJECT_STATUSES, true) ? $_POST['status'] : 'todo';
        $priority = in_array($_POST['priority'] ?? '', PROJECT_PRIORITIES, true) ? $_POST['priority'] : 'normal';
        $goal = (int) ($_POST['goal_id'] ?? 0) ?: null;
        $fields = [$title, project_str('notes', 5000), $status, $priority, project_admin_id($_POST['assignee_id'] ?? 0), $goal, project_date('due_date')];
        if ($id > 0) {
            $old = db()->prepare('SELECT status FROM project_tasks WHERE id = ?');
            $old->execute([$id]);
            $oldStatus = $old->fetchColumn();
            if ($oldStatus === false) project_fail('Attività non trovata.', 404);
            $st = db()->prepare("UPDATE project_tasks SET title = ?, notes = ?, status = ?, priority = ?, assignee_id = ?,
                                        goal_id = ?, due_date = ?,
                                        completed_at = CASE WHEN ? = 'done' THEN COALESCE(completed_at, NOW()) ELSE NULL END
                                 WHERE id = ?");
            $st->execute(array_merge($fields, [$status, $id]));
            project_log($myId, $oldStatus !== $status
                ? "$name ha spostato «{$title}» in " . project_status_label($status)
                : "$name ha modificato «{$title}»", $id);
        } else {
            $order = (int) db()->query('SELECT COALESCE(MAX(sort_order), 0) + 1 FROM project_tasks')->fetchColumn();
            $st = db()->prepare("INSERT INTO project_tasks (title, notes, status, priority, assignee_id, goal_id, due_date,
                                                           sort_order, created_by, completed_at)
                                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? = 'done' THEN NOW() ELSE NULL END)");
            $st->execute(array_merge($fields, [$order, $myId, $status]));
            $id = (int) db()->lastInsertId();
            project_log($myId, "$name ha aggiunto «{$title}» in " . project_status_label($status), $id);
        }
        project_json(['ok' => true, 'id' => $id]);
    }
    case 'task_move': {
        $id = (int) ($_POST['id'] ?? 0);
        $status = $_POST['status'] ?? '';
        if (!in_array($status, PROJECT_STATUSES, true)) project_fail('Colonna non valida.');
        $cur = db()->prepare('SELECT title, status FROM project_tasks WHERE id = ?');
        $cur->execute([$id]);
        $task = $cur->fetch(PDO::FETCH_ASSOC);
        if (!$task) project_fail('Attività non trovata.', 404);
        $pdo = db();
        $pdo->beginTransaction();
        $pdo->prepare("UPDATE project_tasks SET status = ?,
                              completed_at = CASE WHEN ? = 'done' THEN COALESCE(completed_at, NOW()) ELSE NULL END
                       WHERE id = ?")->execute([$status, $status, $id]);
        // L'ordine della colonna di arrivo, come l'ha lasciato il trascinamento.
        $order = array_values(array_filter(array_map('intval', explode(',', (string) ($_POST['order'] ?? '')))));
        $up = $pdo->prepare('UPDATE project_tasks SET sort_order = ? WHERE id = ? AND status = ?');
        foreach ($order as $i => $tid) $up->execute([$i + 1, $tid, $status]);
        $pdo->commit();
        if ($task['status'] !== $status) {
            project_log($myId, "$name ha spostato «{$task['title']}» in " . project_status_label($status), $id);
        }
        project_json(['ok' => true]);
    }
    case 'task_delete': {
        $id = (int) ($_POST['id'] ?? 0);
        $cur = db()->prepare('SELECT title FROM project_tasks WHERE id = ?');
        $cur->execute([$id]);
        $title = $cur->fetchColumn();
        if ($title === false) project_fail('Attività non trovata.', 404);
        db()->prepare('DELETE FROM project_tasks WHERE id = ?')->execute([$id]);
        project_log($myId, "$name ha eliminato «{$title}»");
        project_json(['ok' => true]);
    }

    // ---------------------------------------------------------------- obiettivi
    case 'goal_save': {
        $id = (int) ($_POST['id'] ?? 0);
        $title = project_str('title', 120, true);
        $fields = [$title, project_str('description', 2000), project_color('color', '#a3e635'), project_date('due_date')];
        if ($id > 0) {
            db()->prepare('UPDATE project_goals SET title = ?, description = ?, color = ?, due_date = ? WHERE id = ?')
                ->execute(array_merge($fields, [$id]));
            project_log($myId, "$name ha modificato l'obiettivo «{$title}»");
        } else {
            $order = (int) db()->query('SELECT COALESCE(MAX(sort_order), 0) + 1 FROM project_goals')->fetchColumn();
            db()->prepare('INSERT INTO project_goals (title, description, color, due_date, sort_order, created_by) VALUES (?, ?, ?, ?, ?, ?)')
                ->execute(array_merge($fields, [$order, $myId]));
            $id = (int) db()->lastInsertId();
            project_log($myId, "$name ha creato l'obiettivo «{$title}»");
        }
        project_json(['ok' => true, 'id' => $id]);
    }
    case 'goal_delete': {
        $id = (int) ($_POST['id'] ?? 0);
        $cur = db()->prepare('SELECT title FROM project_goals WHERE id = ?');
        $cur->execute([$id]);
        $title = $cur->fetchColumn();
        if ($title === false) project_fail('Obiettivo non trovato.', 404);
        // Le attivita' restano: perdono solo il collegamento all'obiettivo.
        db()->prepare('UPDATE project_tasks SET goal_id = NULL WHERE goal_id = ?')->execute([$id]);
        db()->prepare('DELETE FROM project_goals WHERE id = ?')->execute([$id]);
        project_log($myId, "$name ha eliminato l'obiettivo «{$title}»");
        project_json(['ok' => true]);
    }

    // ---------------------------------------------------------------- appuntamenti
    case 'event_save': {
        $id = (int) ($_POST['id'] ?? 0);
        $title = project_str('title', 160, true);
        $start = project_datetime('starts_at');
        if ($start === null) project_fail('Data di inizio non valida.');
        $end = project_datetime('ends_at');
        if ($end !== null && $end < $start) $end = null;
        $fields = [$title, project_str('notes', 3000), $start, $end, !empty($_POST['all_day']) ? 1 : 0, project_color('color', '#c04ff0')];
        $when = date('d/m H:i', strtotime($start));
        if ($id > 0) {
            db()->prepare('UPDATE project_events SET title = ?, notes = ?, starts_at = ?, ends_at = ?, all_day = ?, color = ? WHERE id = ?')
                ->execute(array_merge($fields, [$id]));
            project_log($myId, "$name ha modificato l'appuntamento «{$title}» ($when)");
        } else {
            db()->prepare('INSERT INTO project_events (title, notes, starts_at, ends_at, all_day, color, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)')
                ->execute(array_merge($fields, [$myId]));
            $id = (int) db()->lastInsertId();
            project_log($myId, "$name ha fissato «{$title}» per il $when");
        }
        project_json(['ok' => true, 'id' => $id]);
    }
    case 'event_delete': {
        $id = (int) ($_POST['id'] ?? 0);
        $cur = db()->prepare('SELECT title FROM project_events WHERE id = ?');
        $cur->execute([$id]);
        $title = $cur->fetchColumn();
        if ($title === false) project_fail('Appuntamento non trovato.', 404);
        db()->prepare('DELETE FROM project_events WHERE id = ?')->execute([$id]);
        project_log($myId, "$name ha annullato l'appuntamento «{$title}»");
        project_json(['ok' => true]);
    }

    // ---------------------------------------------------------------- chat
    case 'msg_send': {
        $body = trim((string) ($_POST['body'] ?? ''));
        if (mb_strlen($body) > PROJECT_MSG_MAX) project_fail('Messaggio troppo lungo (massimo ' . PROJECT_MSG_MAX . ' caratteri).');
        $image = isset($_FILES['image']) && $_FILES['image']['error'] !== UPLOAD_ERR_NO_FILE
            ? project_store_image($_FILES['image']) : null;
        if ($body === '' && $image === null) project_fail('Messaggio vuoto.');
        $reply = (int) ($_POST['reply_to'] ?? 0) ?: null;
        db()->prepare('INSERT INTO project_messages (user_id, body, image_url, reply_to) VALUES (?, ?, ?, ?)')
            ->execute([$myId, $body, $image, $reply]);
        $id = (int) db()->lastInsertId();
        // Chi scrive ha letto fino al suo messaggio, e ha smesso di scrivere.
        db()->prepare('INSERT INTO project_reads (user_id, last_message_id, typing_until, seen_at) VALUES (?, ?, NULL, NOW())
                       ON DUPLICATE KEY UPDATE last_message_id = GREATEST(last_message_id, VALUES(last_message_id)),
                                               typing_until = NULL, seen_at = NOW()')->execute([$myId, $id]);
        project_json(['ok' => true, 'id' => $id]);
    }
    case 'msg_edit': {
        $id = (int) ($_POST['id'] ?? 0);
        $body = trim((string) ($_POST['body'] ?? ''));
        if ($body === '' || mb_strlen($body) > PROJECT_MSG_MAX) project_fail('Testo non valido.');
        $st = db()->prepare('UPDATE project_messages SET body = ?, edited_at = NOW() WHERE id = ? AND user_id = ? AND deleted_at IS NULL');
        $st->execute([$body, $id, $myId]);
        if ($st->rowCount() === 0) project_fail('Puoi modificare solo i tuoi messaggi.', 403);
        project_json(['ok' => true]);
    }
    case 'msg_delete': {
        $id = (int) ($_POST['id'] ?? 0);
        $st = db()->prepare('UPDATE project_messages SET deleted_at = NOW(), pinned = 0 WHERE id = ? AND user_id = ? AND deleted_at IS NULL');
        $st->execute([$id, $myId]);
        if ($st->rowCount() === 0) project_fail('Puoi eliminare solo i tuoi messaggi.', 403);
        project_json(['ok' => true]);
    }
    case 'msg_pin': {
        $id = (int) ($_POST['id'] ?? 0);
        $st = db()->prepare('UPDATE project_messages SET pinned = 1 - pinned WHERE id = ? AND deleted_at IS NULL');
        $st->execute([$id]);
        if ($st->rowCount() === 0) project_fail('Messaggio non trovato.', 404);
        project_json(['ok' => true]);
    }
    case 'msg_react': {
        $id = (int) ($_POST['id'] ?? 0);
        $emoji = (string) ($_POST['emoji'] ?? '');
        if (!in_array($emoji, PROJECT_REACTIONS, true)) project_fail('Reazione non valida.');
        $del = db()->prepare('DELETE FROM project_message_reactions WHERE message_id = ? AND user_id = ? AND emoji = ?');
        $del->execute([$id, $myId, $emoji]);
        if ($del->rowCount() === 0) {
            db()->prepare('INSERT IGNORE INTO project_message_reactions (message_id, user_id, emoji) VALUES (?, ?, ?)')
                ->execute([$id, $myId, $emoji]);
        }
        // Tocca il messaggio perche' il sondaggio dell'altro lo ripeschi con le reazioni nuove.
        db()->prepare('UPDATE project_messages SET updated_at = NOW() WHERE id = ?')->execute([$id]);
        project_json(['ok' => true]);
    }
    case 'typing': {
        db()->prepare('INSERT INTO project_reads (user_id, typing_until) VALUES (?, DATE_ADD(NOW(), INTERVAL 6 SECOND))
                       ON DUPLICATE KEY UPDATE typing_until = VALUES(typing_until)')->execute([$myId]);
        project_json(['ok' => true]);
    }
    case 'read': {
        $msg = max(0, (int) ($_POST['message_id'] ?? 0));
        $act = max(0, (int) ($_POST['activity_id'] ?? 0));
        db()->prepare('INSERT INTO project_reads (user_id, last_message_id, last_activity_id, seen_at) VALUES (?, ?, ?, NOW())
                       ON DUPLICATE KEY UPDATE last_message_id = GREATEST(last_message_id, VALUES(last_message_id)),
                                               last_activity_id = GREATEST(last_activity_id, VALUES(last_activity_id)),
                                               seen_at = NOW()')->execute([$myId, $msg, $act]);
        project_json(['ok' => true]);
    }
}

project_fail('Azione sconosciuta.');
