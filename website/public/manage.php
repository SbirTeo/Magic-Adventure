<?php
require_once __DIR__ . '/../includes/auth.php';
require_once __DIR__ . '/../includes/helpers.php';
require_once __DIR__ . '/../includes/campo_immagine.php';   // campi immagine col pulsante Scegli
require_once __DIR__ . '/../includes/forum_ui.php';         // forum_tinta_hex(): colore delle categorie
require_once __DIR__ . '/../includes/sanzioni.php';         // archivio sanzioni, coda e ricorsi
require_once __DIR__ . '/../includes/rischio.php';           // classifica di chi controllare

// Non serve piu' essere web-admin: basta avere ALMENO un permesso web (assegnato al proprio
// gruppo in gioco). Ogni sezione e ogni azione hanno poi il loro controllo puntuale.
require_login();
if (!can_manage()) {
    http_response_code(403);
    die('Accesso riservato allo staff del sito.');
}

$section = $_GET['section'] ?? 'dashboard';
$me = current_user();
$flash = null;
$flashType = 'success';

/**
 * Permesso richiesto da ogni sezione del gestionale. La dashboard non compare qui:
 * e' accessibile a chiunque superi il controllo can_manage() sopra.
 */
$sectionPermissions = [
    'blog'      => ['blog.create', 'blog.edit', 'blog.delete', 'blog.publish'],
    'blog_edit' => ['blog.create', 'blog.edit'],
    // Il cestino lo vede chi puo' eliminare: e' l'altra meta' dello stesso gesto.
    'blog_cestino' => ['blog.delete'],
    'forum'     => ['forum.category.create', 'forum.category.edit', 'forum.category.delete',
                    'forum.topic.pin', 'forum.topic.lock', 'forum.topic.delete'],
    'users'     => ['users.view', 'users.manage'],
    'sanzioni'  => ['sanzioni.view', 'sanzioni.coda', 'sanzioni.ricorsi', 'sanzioni.revoca'],
    'rischio'   => ['sanzioni.view'],
    // 'guida' non compare qui di proposito: la guida per amministratori la legge CHIUNQUE
    // entri nel gestionale. E' documentazione, non un potere.
];

/**
 * Configurazione del sito: riservata al web-admin e NON delegabile a nessun gruppo.
 * (Le impostazioni del blog — articoli per pagina e colori dei veli — stanno dentro la
 * scheda Blog ma seguono questa stessa regola: il pannello si vede solo da web-admin.)
 */
$adminOnlySections = ['pages', 'page_edit', 'guida_edit', 'nav', 'theme', 'perms', 'store', 'store_pkg_edit', 'payments', 'console', 'sicurezza', 'menu'];

// Il banner VIP era una scheda a se': ora vive dentro "Aspetto", i vecchi link restano validi.
if ($section === 'vip_banner') {
    $section = 'theme';
}

// Stessa cosa per la navigazione: "Pagine" e "Navigazione" erano due schede separate,
// ora sono un'unica scheda "Pagine e menu" (le pagine si collegano dal menu, si gestiscono insieme).
if ($section === 'nav') {
    $section = 'pages';
}
// I pagamenti stavano in una scheda a parte: ora sono in fondo allo store, dove servono.
// I vecchi indirizzi (e i redirect delle azioni) continuano a funzionare.
if ($section === 'payments') {
    $section = 'store';
}

// "Da controllare" non e' piu' una scheda a se': sta dentro Sanzioni, che e' la maschera
// unica di tutto quello che riguarda i provvedimenti. I vecchi link restano validi.
if ($section === 'rischio') {
    $section = 'sanzioni';
}

/** Permesso richiesto da ogni azione POST (quelle che dipendono dal contesto sono gestite sotto). */
$actionPermissions = [
    'blog_clone'              => 'blog.create',
    'blog_delete'             => 'blog.delete',
    'blog_restore'            => 'blog.delete',
    'blog_toggle_publish'     => 'blog.publish',
    'forum_cat_delete'        => 'forum.category.delete',
    'forum_reorder'           => 'forum.category.edit',
    'forum_settings_save'     => 'forum.category.edit',
    'forum_cat_clone'         => 'forum.category.create',
    'forum_topic_toggle_pin'  => 'forum.topic.pin',
    'forum_topic_toggle_lock' => 'forum.topic.lock',
    'forum_topic_delete'      => 'forum.topic.delete',
    'forum_topic_move'        => 'forum.topic.move',
    'user_toggle_admin'       => 'users.manage',
    'sanzione_revoca'         => 'sanzioni.revoca',
    'coda_conferma'           => 'sanzioni.coda',
    'coda_respingi'           => 'sanzioni.coda',
    'ricorso_decidi'          => 'sanzioni.ricorsi',
    'rischio_controllato'     => 'sanzioni.view',
];

/** Azioni eseguibili solo dal web-admin, coerenti con $adminOnlySections. */
$adminOnlyActions = ['blog_purge', 'blog_settings_save', 'page_save', 'page_delete', 'nav_save', 'nav_delete',
                     'nav_toggle_enabled', 'nav_toggle_sidebar', 'nav_reorder', 'settings_save', 'vip_banner_save', 'chat_settings_save',
                     'guida_intro_save', 'perms_save',
                     'store_cat_save', 'store_cat_delete', 'store_pkg_save', 'store_pkg_delete',
                     'store_pkg_toggle', 'store_pkg_clone', 'store_pkg_deliver', 'store_reorder',
                     'store_settings_save', 'store_sidebar_save', 'store_sconto_save', 'goal_save',
                     'store_filters_save', 'store_layout_save', 'payments_save',
                     'otp_staff_save', 'otp_azzera', 'otp_revoca_gioco'];

/**
 * Sconto letto dal form (pacchetto o categoria): tipo + valore, gia' ripuliti.
 * Tipo vuoto o valore <= 0 = nessuno sconto, e si salva NULL/0.
 *
 * @return array{0: ?string, 1: float}
 */
function sconto_dal_post(): array {
    $tipo = $_POST['discount_type'] ?? '';
    $valore = round((float) str_replace(',', '.', (string) ($_POST['discount_value'] ?? '0')), 2);
    if (!in_array($tipo, ['percentuale', 'importo'], true) || $valore <= 0) {
        return [null, 0.0];
    }
    // La percentuale non puo' superare il 100%: oltre, il prezzo diventerebbe negativo.
    if ($tipo === 'percentuale') {
        $valore = min(100, $valore);
    }
    return [$tipo, $valore];
}

// ---------------------------------------------------------------------
// Azioni (POST) — tutte tornano su /manage con redirect (pattern PRG)
// ---------------------------------------------------------------------
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    csrf_check();
    $action = $_POST['action'] ?? '';
    $backSection = $_POST['section'] ?? 'dashboard';

    // Controllo permessi PRIMA di eseguire qualunque azione. Salvataggi che possono essere sia
    // creazione sia modifica richiedono il permesso corrispondente al caso reale.
    if (in_array($action, $adminOnlyActions, true)) {
        require_admin();
    } elseif (isset($actionPermissions[$action])) {
        require_perm($actionPermissions[$action]);
    } elseif ($action === 'blog_save') {
        require_perm(((int) ($_POST['id'] ?? 0)) > 0 ? 'blog.edit' : 'blog.create');
    } elseif ($action === 'forum_cat_save') {
        require_perm(((int) ($_POST['id'] ?? 0)) > 0 ? 'forum.category.edit' : 'forum.category.create');
    }

    switch ($action) {

        case 'blog_save': {
            $id = (int)($_POST['id'] ?? 0);
            $title = trim($_POST['title'] ?? '');
            $subtitle = mb_substr(trim($_POST['subtitle'] ?? ''), 0, 255);
            $body = trim($_POST['body'] ?? '');
            $coverImage = trim($_POST['cover_image'] ?? '') ?: null;
            // Due percentuali e nient'altro: il valore finisce dentro un attributo style.
            $puntoValido = static fn($v) => preg_match('/^\d{1,3}% \d{1,3}%$/', (string) $v) ? $v : '50% 50%';
            $coverPosition = $puntoValido($_POST['cover_position'] ?? '');
            $coverPositionPc = $puntoValido($_POST['cover_position_pc'] ?? '');
            $published = isset($_POST['published']) ? 1 : 0;

            if ($title === '' || $body === '') {
                redirect('/manage?section=blog_edit&id=' . $id . '&err=empty');
            }

            if ($id > 0) {
                $upd = db()->prepare('UPDATE blog_posts SET title = ?, subtitle = ?, cover_image = ?, cover_position = ?, cover_position_pc = ?, body = ?, published = ?, updated_at = NOW() WHERE id = ?');
                $upd->execute([$title, $subtitle, $coverImage, $coverPosition, $coverPositionPc, $body, $published, $id]);
            } else {
                $slug = slugify($title);
                $base = $slug;
                $i = 2;
                $check = db()->prepare('SELECT COUNT(*) FROM blog_posts WHERE slug = ?');
                while (true) {
                    $check->execute([$slug]);
                    if ((int)$check->fetchColumn() === 0) break;
                    $slug = $base . '-' . $i++;
                }
                $ins = db()->prepare('INSERT INTO blog_posts (title, subtitle, slug, cover_image, cover_position, cover_position_pc, body, author_user_id, published) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)');
                $ins->execute([$title, $subtitle, $slug, $coverImage, $coverPosition, $coverPositionPc, $body, $me['id'], $published]);
            }
            redirect('/manage?section=blog&ok=1');
        }

        case 'blog_clone': {
            $id = (int) ($_POST['id'] ?? 0);
            $orig = db()->prepare('SELECT title, subtitle, cover_image, body FROM blog_posts WHERE id = ? AND deleted_at IS NULL');
            $orig->execute([$id]);
            $post = $orig->fetch();
            if (!$post) {
                redirect('/manage?section=blog&err=empty');
            }

            // La copia nasce NON pubblicata e firmata da chi clona: e' una bozza da rivedere,
            // non un doppione che compare subito in home.
            $titolo = mb_substr($post['title'] . ' (copia)', 0, 255);
            $slug = unique_slug('blog_posts', slugify($titolo));
            $ins = db()->prepare('INSERT INTO blog_posts (title, subtitle, slug, cover_image, body, author_user_id, published) VALUES (?, ?, ?, ?, ?, ?, 0)');
            $ins->execute([$titolo, $post['subtitle'], $slug, $post['cover_image'], $post['body'], $me['id']]);
            redirect('/manage?section=blog_edit&id=' . (int) db()->lastInsertId() . '&ok=1');
        }

        case 'blog_delete': {
            // Non si cancella niente per davvero: l'articolo va nel cestino (deleted_at) e
            // sparisce dal sito. Da li' si ripesca intero, testo e copertina compresi.
            $id = (int)($_POST['id'] ?? 0);
            db()->prepare('UPDATE blog_posts SET deleted_at = NOW() WHERE id = ? AND deleted_at IS NULL')
                ->execute([$id]);
            redirect('/manage?section=blog&ok=1');
        }

        case 'blog_restore': {
            $id = (int)($_POST['id'] ?? 0);
            $riga = db()->prepare('SELECT slug FROM blog_posts WHERE id = ? AND deleted_at IS NOT NULL');
            $riga->execute([$id]);
            $slug = $riga->fetchColumn();
            if ($slug === false) {
                redirect('/manage?section=blog_cestino&err=empty');
            }

            // Nel frattempo qualcuno puo' aver pubblicato un articolo con lo stesso indirizzo:
            // in quel caso il ripescato ne prende uno libero, invece di far fallire tutto.
            $occupato = db()->prepare('SELECT COUNT(*) FROM blog_posts WHERE slug = ? AND id <> ? AND deleted_at IS NULL');
            $occupato->execute([$slug, $id]);
            $nuovoSlug = $occupato->fetchColumn() > 0 ? unique_slug('blog_posts', (string) $slug) : $slug;

            // Torna come BOZZA, non pubblicato: chi lo ripesca decide lui quando rimetterlo
            // in home, senza che ricompaia da solo sotto gli occhi di tutti.
            db()->prepare('UPDATE blog_posts SET deleted_at = NULL, published = 0, slug = ? WHERE id = ?')
                ->execute([$nuovoSlug, $id]);
            redirect('/manage?section=blog&ok=1');
        }

        case 'blog_purge': {
            // Questa si' che cancella davvero, e non si torna indietro: solo web-admin.
            $id = (int)($_POST['id'] ?? 0);
            db()->prepare('DELETE FROM blog_posts WHERE id = ? AND deleted_at IS NOT NULL')->execute([$id]);
            redirect('/manage?section=blog_cestino&ok=1');
        }

        case 'blog_toggle_publish': {
            $id = (int)($_POST['id'] ?? 0);
            db()->prepare('UPDATE blog_posts SET published = 1 - published WHERE id = ?')->execute([$id]);
            redirect('/manage?section=blog&ok=1');
        }

        case 'blog_settings_save': {
            $perPage = max(1, min(20, (int) ($_POST['blog_per_page'] ?? 6)));
            $featuredColor = trim($_POST['featured_overlay_color'] ?? '');
            $featuredIntensity = max(0, min(100, (int) ($_POST['featured_overlay_intensity'] ?? 35)));
            $gridColor = trim($_POST['grid_overlay_color'] ?? '');
            $gridIntensity = max(0, min(100, (int) ($_POST['grid_overlay_intensity'] ?? 18)));
            $borderAnim = isset($_POST['featured_border_anim']) ? '1' : '0';

            if (!is_valid_hex_color($featuredColor) || !is_valid_hex_color($gridColor)) {
                redirect('/manage?section=blog&err=empty');
            }

            $upd = db()->prepare('INSERT INTO site_settings (setting_key, setting_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)');
            $upd->execute(['blog_per_page', (string) $perPage]);
            // Interruttore del velo delle tessere: sta in questo modulo, non piu' in Aspetto,
            // perche' quello dello store e' un altro e i due si spengono per motivi diversi.
            $upd->execute(['card_overlay_blog', isset($_POST['card_overlay_blog']) ? '1' : '0']);
            $upd->execute(['featured_overlay_color', $featuredColor]);
            $upd->execute(['featured_overlay_intensity', (string) $featuredIntensity]);
            $upd->execute(['featured_overlay_stop', (string) max(20, min(100, (int) ($_POST['featured_overlay_stop'] ?? 100)))]);
            $upd->execute(['grid_overlay_color', $gridColor]);
            $upd->execute(['grid_overlay_intensity', (string) $gridIntensity]);
            $upd->execute(['grid_overlay_stop', (string) max(20, min(100, (int) ($_POST['grid_overlay_stop'] ?? 100)))]);

            // Gli stessi tre valori per il tema CHIARO, piu' il colore del testo sopra le
            // copertine nei due temi. Un colore non valido non blocca il salvataggio: si
            // ricade su quello del tema scuro, cioe' su com'era prima.
            foreach ([
                'featured_overlay_color_chiaro' => $featuredColor,
                'grid_overlay_color_chiaro' => $gridColor,
            ] as $chiave => $ripiego) {
                $valore = trim($_POST[$chiave] ?? '');
                $upd->execute([$chiave, is_valid_hex_color($valore) ? $valore : $ripiego]);
            }
            // Testo sopra le copertine: senza la spunta si salva vuoto, che vuol dire
            // "automatico" (lo decide il velo), esattamente come per le card dello store.
            foreach ([
                'tile_text_color' => 'tile_text_custom',
                'tile_text_color_chiaro' => 'tile_text_custom_chiaro',
            ] as $chiave => $spunta) {
                $valore = trim($_POST[$chiave] ?? '');
                $upd->execute([$chiave, isset($_POST[$spunta]) && is_valid_hex_color($valore) ? $valore : '']);
            }
            foreach ([
                'featured_overlay_intensity_chiaro' => $featuredIntensity,
                'grid_overlay_intensity_chiaro' => $gridIntensity,
            ] as $chiave => $ripiego) {
                $upd->execute([$chiave, (string) max(0, min(100, (int) ($_POST[$chiave] ?? $ripiego)))]);
            }
            foreach (['featured_overlay_stop_chiaro', 'grid_overlay_stop_chiaro'] as $chiave) {
                $upd->execute([$chiave, (string) max(20, min(100, (int) ($_POST[$chiave] ?? 100)))]);
            }
            foreach (['featured_border_color' => '#c04ff0', 'grid_border_color' => '#c04ff0'] as $chiave => $default) {
                $valore = trim($_POST[$chiave] ?? '');
                $upd->execute([$chiave, is_valid_hex_color($valore) ? $valore : $default]);
            }
            // Le stesse barrette per il tema chiaro: se il campo e' vuoto o sbagliato vale
            // quella del tema scuro, cioe' il comportamento di prima.
            foreach ([
                'featured_border_color_chiaro' => trim($_POST['featured_border_color'] ?? '#c04ff0'),
                'grid_border_color_chiaro' => trim($_POST['grid_border_color'] ?? '#c04ff0'),
            ] as $chiave => $ripiego) {
                $valore = trim($_POST[$chiave] ?? '');
                if (!is_valid_hex_color($valore)) {
                    $valore = is_valid_hex_color($ripiego) ? $ripiego : '#c04ff0';
                }
                $upd->execute([$chiave, $valore]);
            }
            foreach (['featured_overlay_direction', 'grid_overlay_direction'] as $chiave) {
                $upd->execute([$chiave, ($_POST[$chiave] ?? '') === 'orizzontale' ? 'orizzontale' : 'verticale']);
            }
            $upd->execute(['featured_border_anim', $borderAnim]);

            redirect('/manage?section=blog&ok=1');
        }

        case 'page_save': {
            $origSlug = trim($_POST['orig_slug'] ?? '');
            $title = trim($_POST['title'] ?? '');
            $body = trim($_POST['body'] ?? '');

            if ($title === '' || $body === '') {
                redirect('/manage?section=page_edit&slug=' . urlencode($origSlug) . '&err=empty');
            }

            $conSidebar = !empty($_POST['show_sidebar']) ? 1 : 0;

            if ($origSlug !== '') {
                $upd = db()->prepare('UPDATE site_pages SET title = ?, body = ?, show_sidebar = ? WHERE slug = ?');
                $upd->execute([$title, $body, $conSidebar, $origSlug]);
                redirect('/manage?section=pages&ok=1');
            } else {
                $slug = slugify($title);
                $base = $slug;
                $i = 2;
                $check = db()->prepare('SELECT COUNT(*) FROM site_pages WHERE slug = ?');
                while (true) {
                    $check->execute([$slug]);
                    if ((int)$check->fetchColumn() === 0) break;
                    $slug = $base . '-' . $i++;
                }
                $ins = db()->prepare('INSERT INTO site_pages (slug, title, body, show_sidebar) VALUES (?, ?, ?, ?)');
                $ins->execute([$slug, $title, $body, $conSidebar]);
                redirect('/manage?section=pages&ok=1');
            }
        }

        case 'page_delete': {
            $slug = trim($_POST['slug'] ?? '');
            if ($slug === 'regolamento') {
                redirect('/manage?section=pages&err=core');
            }
            db()->prepare('DELETE FROM site_pages WHERE slug = ?')->execute([$slug]);
            redirect('/manage?section=pages&ok=1');
        }

        case 'forum_cat_save': {
            $id = (int)($_POST['id'] ?? 0);
            $name = trim($_POST['name'] ?? '');
            $description = trim($_POST['description'] ?? '');
            $sortOrder = (int)($_POST['sort_order'] ?? 0);

            if ($name === '') {
                redirect('/manage?section=forum&err=empty');
            }

            // Colore della categoria: la spunta fa da interruttore. Senza, si salva NULL e
            // la tinta torna automatica (vedi forum_tinta() in includes/forum_ui.php).
            $colore = trim($_POST['color'] ?? '');
            $colore = (isset($_POST['color_custom']) && is_valid_hex_color($colore)) ? $colore : null;

            // Categoria superiore. Tre controlli, perche' un albero storto rende invisibili
            // delle categorie: (1) non se stessa, (2) il padre deve esistere ed essere di
            // primo livello, (3) chi ha gia' delle figlie non puo' diventare figlia.
            $padre = (int) ($_POST['parent_id'] ?? 0);
            if ($padre > 0 && $id > 0 && $padre === $id) {
                $padre = 0;
            }
            if ($padre > 0) {
                $q = db()->prepare('SELECT parent_id FROM forum_categories WHERE id = ?');
                $q->execute([$padre]);
                $nonno = $q->fetch();
                if (!$nonno || $nonno['parent_id'] !== null) {
                    $padre = 0;
                }
            }
            if ($padre > 0 && $id > 0) {
                $q = db()->prepare('SELECT COUNT(*) FROM forum_categories WHERE parent_id = ?');
                $q->execute([$id]);
                if ((int) $q->fetchColumn() > 0) {
                    $padre = 0;
                }
            }
            $padre = $padre > 0 ? $padre : null;

            if ($id > 0) {
                $upd = db()->prepare('UPDATE forum_categories SET name = ?, description = ?, sort_order = ?, color = ?, parent_id = ? WHERE id = ?');
                $upd->execute([$name, $description, $sortOrder, $colore, $padre, $id]);
            } else {
                $slug = slugify($name);
                $base = $slug;
                $i = 2;
                $check = db()->prepare('SELECT COUNT(*) FROM forum_categories WHERE slug = ?');
                while (true) {
                    $check->execute([$slug]);
                    if ((int)$check->fetchColumn() === 0) break;
                    $slug = $base . '-' . $i++;
                }
                $ins = db()->prepare('INSERT INTO forum_categories (name, slug, description, sort_order, color, parent_id) VALUES (?, ?, ?, ?, ?, ?)');
                $ins->execute([$name, $slug, $description, $sortOrder, $colore, $padre]);
            }
            redirect('/manage?section=forum&ok=1');
        }

        case 'forum_cat_clone': {
            $id = (int) ($_POST['id'] ?? 0);
            $q = db()->prepare('SELECT * FROM forum_categories WHERE id = ?');
            $q->execute([$id]);
            $orig = $q->fetch();
            if (!$orig) {
                redirect('/manage?section=forum&err=empty');
            }

            /** Copia una categoria (SENZA le sue discussioni) e restituisce l'id nuovo. */
            $clone = function (array $c, ?int $dentro): int {
                $nome = mb_substr($c['name'] . ' (copia)', 0, 100);
                $ins = db()->prepare('INSERT INTO forum_categories (name, slug, description, sort_order, color, parent_id)
                                      VALUES (?, ?, ?, ?, ?, ?)');
                $ins->execute([
                    $nome,
                    unique_slug('forum_categories', slugify($nome)),
                    $c['description'],
                    (int) $c['sort_order'],   // nasce accanto all'originale
                    $c['color'],
                    $dentro,
                ]);
                return (int) db()->lastInsertId();
            };

            $pdo = db();
            $pdo->beginTransaction();
            try {
                // Le discussioni NON si copiano mai: si duplica solo l'impianto.
                $nuovo = $clone($orig, $orig['parent_id'] ? (int) $orig['parent_id'] : null);

                // Clonando una categoria che contiene sezioni, si ricrea anche la struttura:
                // la copia nasce con le stesse sezioni, anch'esse vuote.
                $figlieOrig = $pdo->prepare('SELECT * FROM forum_categories WHERE parent_id = ? ORDER BY sort_order, name');
                $figlieOrig->execute([$id]);
                foreach ($figlieOrig->fetchAll() as $f) {
                    $clone($f, $nuovo);
                }
                $pdo->commit();
            } catch (Throwable $e) {
                $pdo->rollBack();
                error_log('Forum: clonazione categoria fallita: ' . $e->getMessage());
                redirect('/manage?section=forum&err=empty');
            }

            redirect('/manage?section=forum&edit_cat=' . $nuovo . '&ok=1');
        }

        case 'forum_cat_delete': {
            $id = (int)($_POST['id'] ?? 0);
            $count = db()->prepare('SELECT COUNT(*) FROM forum_topics WHERE category_id = ?');
            $count->execute([$id]);
            if ((int)$count->fetchColumn() > 0) {
                redirect('/manage?section=forum&err=notempty');
            }
            // Nemmeno se ha delle sotto-categorie: sparirebbero dall'elenco senza dirlo.
            $figlie = db()->prepare('SELECT COUNT(*) FROM forum_categories WHERE parent_id = ?');
            $figlie->execute([$id]);
            if ((int) $figlie->fetchColumn() > 0) {
                redirect('/manage?section=forum&err=hasfiglie');
            }
            db()->prepare('DELETE FROM forum_categories WHERE id = ?')->execute([$id]);
            redirect('/manage?section=forum&ok=1');
        }

        case 'nav_reorder': {
            // Riordino delle voci di menu col trascinamento: arriva l'elenco degli id
            // nell'ordine finale e si riscrive `sort_order` da 1 in poi. Risponde in JSON
            // perche' la pagina non si ricarica.
            header('Content-Type: application/json');
            $ordine = json_decode((string) ($_POST['ordine'] ?? ''), true);
            if (!is_array($ordine)) {
                echo json_encode(['ok' => false]);
                exit;
            }

            $pdo = db();
            $pdo->beginTransaction();
            try {
                $upd = $pdo->prepare('UPDATE nav_items SET sort_order = ? WHERE id = ?');
                foreach (array_values($ordine) as $pos => $id) {
                    if ((int) $id > 0) {
                        $upd->execute([$pos + 1, (int) $id]);
                    }
                }
                $pdo->commit();
            } catch (Throwable $e) {
                $pdo->rollBack();
                error_log('Menu: riordino fallito: ' . $e->getMessage());
                echo json_encode(['ok' => false]);
                exit;
            }

            echo json_encode(['ok' => true]);
            exit;
        }

        case 'forum_settings_save': {
            csrf_check();
            $upd = db()->prepare('INSERT INTO site_settings (setting_key, setting_value) VALUES (?, ?)
                                  ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)');
            $upd->execute(['forum_ultime_enabled', isset($_POST['forum_ultime_enabled']) ? '1' : '0']);
            $upd->execute(['forum_ultime_quante', (string) max(1, min(24, (int) ($_POST['forum_ultime_quante'] ?? 6)))]);
            redirect('/manage?section=forum&ok=1#aspetto-forum');
        }

        case 'forum_reorder': {
            // Salvataggio del trascinamento: arriva l'albero intero, si riscrivono
            // posizione e categoria superiore in un colpo solo.
            header('Content-Type: application/json');
            $dati = json_decode((string) ($_POST['ordine'] ?? ''), true);
            if (!is_array($dati)) {
                echo json_encode(['ok' => false]);
                exit;
            }

            $pdo = db();
            $pdo->beginTransaction();
            try {
                $upd = $pdo->prepare('UPDATE forum_categories SET sort_order = ?, parent_id = ? WHERE id = ?');
                foreach (array_values((array) ($dati['principali'] ?? [])) as $pos => $catId) {
                    if ((int) $catId > 0) {
                        $upd->execute([$pos, null, (int) $catId]);
                    }
                }
                foreach ((array) ($dati['figlie'] ?? []) as $riga) {
                    $padre = (int) ($riga['padre'] ?? 0);
                    $figlia = (int) ($riga['id'] ?? 0);
                    if ($figlia > 0 && $padre > 0 && $figlia !== $padre) {
                        $upd->execute([(int) ($riga['ordine'] ?? 0), $padre, $figlia]);
                    }
                }
                $pdo->commit();
            } catch (Throwable $e) {
                $pdo->rollBack();
                error_log('Forum: riordino fallito: ' . $e->getMessage());
                echo json_encode(['ok' => false]);
                exit;
            }

            echo json_encode(['ok' => true]);
            exit;
        }

        case 'forum_topic_toggle_pin': {
            $id = (int)($_POST['id'] ?? 0);
            db()->prepare('UPDATE forum_topics SET is_pinned = 1 - is_pinned WHERE id = ?')->execute([$id]);
            redirect('/manage?section=forum&ok=1');
        }

        case 'forum_topic_toggle_lock': {
            $id = (int)($_POST['id'] ?? 0);
            db()->prepare('UPDATE forum_topics SET is_locked = 1 - is_locked WHERE id = ?')->execute([$id]);
            redirect('/manage?section=forum&ok=1');
        }

        case 'forum_topic_move': {
            $id = (int) ($_POST['id'] ?? 0);
            $dove = (int) ($_POST['category_id'] ?? 0);

            // La destinazione deve esistere e NON avere sezioni dentro: le categorie che
            // ne contengono non ospitano discussioni (stessa regola del forum pubblico).
            $q = db()->prepare('SELECT c.id, (SELECT COUNT(*) FROM forum_categories f WHERE f.parent_id = c.id) AS figlie
                                FROM forum_categories c WHERE c.id = ?');
            $q->execute([$dove]);
            $dest = $q->fetch();
            if (!$dest || (int) $dest['figlie'] > 0) {
                redirect('/manage?section=forum&err=destinazione');
            }

            db()->prepare('UPDATE forum_topics SET category_id = ? WHERE id = ?')->execute([$dove, $id]);
            redirect('/manage?section=forum&ok=1#discussioni');
        }

        case 'forum_topic_delete': {
            $id = (int)($_POST['id'] ?? 0);
            db()->prepare('DELETE FROM forum_topics WHERE id = ?')->execute([$id]);
            redirect('/manage?section=forum&ok=1');
        }

        case 'nav_save': {
            $id = (int)($_POST['id'] ?? 0);
            $label = trim($_POST['label'] ?? '');
            $url = trim($_POST['url'] ?? '');
            $sortOrder = (int)($_POST['sort_order'] ?? 0);

            if ($label === '' || $url === '') {
                redirect('/manage?section=pages&err=empty#menu');
            }

            // Colonna laterale: vale per questa pagina e per tutto quello che ci sta sotto
            // (es. /forum vale anche per le discussioni).
            $conSidebar = !empty($_POST['show_sidebar']) ? 1 : 0;

            if ($id > 0) {
                $upd = db()->prepare('UPDATE nav_items SET label = ?, url = ?, sort_order = ?, show_sidebar = ? WHERE id = ?');
                $upd->execute([$label, $url, $sortOrder, $conSidebar, $id]);
            } else {
                $ins = db()->prepare('INSERT INTO nav_items (label, url, sort_order, show_sidebar) VALUES (?, ?, ?, ?)');
                $ins->execute([$label, $url, $sortOrder, $conSidebar]);
            }
            redirect('/manage?section=pages&ok=1#menu');
        }

        case 'nav_delete': {
            $id = (int)($_POST['id'] ?? 0);
            db()->prepare('DELETE FROM nav_items WHERE id = ?')->execute([$id]);
            redirect('/manage?section=pages&ok=1#menu');
        }

        case 'nav_toggle_sidebar': {
            // Interruttore rapido della colonna laterale, per non dover aprire la voce di
            // menu: e' la cosa che si cambia piu' spesso pagina per pagina.
            $id = (int) ($_POST['id'] ?? 0);
            db()->prepare('UPDATE nav_items SET show_sidebar = 1 - show_sidebar WHERE id = ?')->execute([$id]);
            redirect('/manage?section=pages&ok=1');
        }

        case 'nav_toggle_enabled': {
            $id = (int)($_POST['id'] ?? 0);
            db()->prepare('UPDATE nav_items SET enabled = 1 - enabled WHERE id = ?')->execute([$id]);
            redirect('/manage?section=pages&ok=1#menu');
        }

        case 'otp_staff_save': {
            // Interruttore: estende la verifica in due passaggi anche allo staff con
            // permessi delegati. Sui web-admin e' sempre obbligatoria e non si spegne.
            $acceso = isset($_POST['otp_staff']) ? '1' : '0';
            db()->prepare('INSERT INTO site_settings (setting_key, setting_value) VALUES (?, ?)
                           ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)')
                ->execute(['otp_staff_obbligatorio', $acceso]);
            redirect('/manage?section=sicurezza&ok=1');
        }

        case 'otp_revoca_gioco': {
            // Chiude la sessione di gioco di un altro amministratore: utile quando e' un
            // collega ad avere il problema e non e' raggiungibile. Non tocca il suo segreto
            // ne' i suoi codici — gli viene solo richiesto di nuovo il codice, subito.
            $id = (int) ($_POST['id'] ?? 0);
            $q = db()->prepare('SELECT mc_uuid FROM users WHERE id = ?');
            $q->execute([$id]);
            $uuid = $q->fetchColumn();
            if (!$uuid) {
                redirect('/manage?section=sicurezza&err=1');
            }
            otp_close_game_session((string) $uuid, $me['mc_username']);
            redirect('/manage?section=sicurezza&ok=3');
        }

        case 'otp_azzera': {
            // Telefono perso: si buttano segreto e codici di recupero. L'account NON resta
            // scoperto — al primo accesso il sito gli fa rifare l'attivazione da capo.
            // Si chiude anche ogni sessione aperta altrove: se e' stato qualcun altro a
            // prendersi l'account, questo lo mette fuori subito.
            $id = (int) ($_POST['id'] ?? 0);
            $q = db()->prepare('SELECT id, mc_username FROM users WHERE id = ?');
            $q->execute([$id]);
            $bersaglio = $q->fetch();
            if (!$bersaglio) {
                redirect('/manage?section=sicurezza&err=1');
            }
            otp_azzera($id);
            db()->prepare('UPDATE users SET session_epoch = session_epoch + 1 WHERE id = ?')->execute([$id]);
            remember_forget_all($id);
            db()->prepare('DELETE FROM auth_sessions WHERE mc_uuid = (SELECT mc_uuid FROM users WHERE id = ?)')
                ->execute([$id]);
            redirect('/manage?section=sicurezza&ok=2');
        }

        case 'settings_save': {
            $siteName = trim($_POST['site_name'] ?? '');
            $logoUrl = trim($_POST['logo_url'] ?? '');
            $logoSmallUrl = trim($_POST['logo_small_url'] ?? '');
            $faviconUrl = trim($_POST['favicon_url'] ?? '');
            $navLogoEnabled = isset($_POST['nav_logo_enabled']) ? '1' : '0';
            $navLogoSize = (string) max(20, min(64, (int) ($_POST['nav_logo_size'] ?? 44)));
            $metaDescription = trim($_POST['meta_description'] ?? '');
            $metaTitleHome = trim($_POST['meta_title_home'] ?? '');
            $ogImage = trim($_POST['og_image'] ?? '');
            // Solo lettere, cifre, trattini e underscore: e' un codice, non un testo libero,
            // e finisce dentro un tag della pagina.
            $verificaGoogle = preg_replace('/[^A-Za-z0-9_-]/', '', $_POST['google_site_verification'] ?? '');
            $colorBg = trim($_POST['color_bg'] ?? '');
            $colorPurple = trim($_POST['color_purple'] ?? '');
            $colorGreen = trim($_POST['color_green'] ?? '');
            $storeBtnAnim = isset($_POST['store_btn_border_anim']) ? '1' : '0';

            if ($siteName === '' || $logoUrl === ''
                || !is_valid_hex_color($colorBg)
                || !is_valid_hex_color($colorPurple)
                || !is_valid_hex_color($colorGreen)) {
                redirect('/manage?section=theme&err=invalid');
            }

            $upd = db()->prepare('INSERT INTO site_settings (setting_key, setting_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)');
            $upd->execute(['site_name', $siteName]);
            $upd->execute(['logo_url', $logoUrl]);
            $upd->execute(['logo_small_url', $logoSmallUrl]);
            $upd->execute(['favicon_url', $faviconUrl]);
            $upd->execute(['nav_logo_enabled', $navLogoEnabled]);
            $upd->execute(['nav_logo_size', $navLogoSize]);
            $upd->execute(['meta_description', $metaDescription]);
            $upd->execute(['meta_title_home', $metaTitleHome]);
            $upd->execute(['og_image', $ogImage]);
            $upd->execute(['google_site_verification', $verificaGoogle]);
            $upd->execute(['color_bg', $colorBg]);
            $upd->execute(['color_purple', $colorPurple]);
            $upd->execute(['color_green', $colorGreen]);
            $upd->execute(['store_btn_border_anim', $storeBtnAnim]);
            // Stile dei pulsanti principali: 'contrasto' (bianco/nero) o 'accento' (colore del sito)
            $upd->execute(['btn_stile', ($_POST['btn_stile'] ?? '') === 'accento' ? 'accento' : 'contrasto']);

            // Tavolozze dei due temi: tre colori ciascuna (fondo, pannelli, testo). Il resto
            // — bordi, fondi intermedi, testi tenui — lo ricava tavolozza_tema(), che spinge
            // i testi tenui oltre la soglia di contrasto. Un valore non valido non si salva:
            // resta quello di prima, cosi' non si spegne il sito con un colore storto.
            $tavolozza = [
                'dark_panel'  => '#17181b',
                'dark_text'   => '#f0f0ee',
                'light_bg'    => '#f2f3f6',
                'light_panel' => '#ffffff',
                'light_text'  => '#14161a',
            ];
            foreach ($tavolozza as $chiave => $default) {
                $valore = trim($_POST[$chiave] ?? '');
                if (is_valid_hex_color($valore)) {
                    $upd->execute([$chiave, $valore]);
                }
            }
            // Tema di partenza per chi arriva la prima volta: poi ognuno se lo cambia
            // dal pulsante nella barra e la sua scelta (cookie) vince su questa.
            $tema = $_POST['tema_predefinito'] ?? 'scuro';
            $upd->execute(['tema_predefinito', in_array($tema, ['scuro', 'chiaro', 'auto'], true) ? $tema : 'scuro']);
            // Testi della hero: normalizzo i fine riga, il rendering li trasforma in <br>
            $normalizza = fn(string $t): string => trim(str_replace(["
", "
"], "
", $t));
            $upd->execute(['hero_slogan', $normalizza($_POST['hero_slogan'] ?? '')]);
            $upd->execute(['hero_headline', $normalizza($_POST['hero_headline'] ?? '')]);
            $upd->execute(['hero_sub', $normalizza($_POST['hero_sub'] ?? '')]);
            redirect('/manage?section=theme&ok=1');
        }

        case 'countdown_save': {
            // La data arriva da <input type="datetime-local">: "2026-09-05T21:00", ora
            // ITALIANA. Si salva com'e' scritta; a interpretarla nel fuso giusto ci pensa
            // countdown_istante() (includes/countdown.php), perche' il server lavora in UTC.
            $acceso = isset($_POST['enabled']) ? '1' : '0';
            $quando = trim($_POST['target'] ?? '');
            if ($quando !== '' && !preg_match('/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2})?$/', $quando)) {
                redirect('/manage?section=theme&err=data#countdown');
            }
            // Da quando il portale comincia a caricarsi. Vuoto = un mese prima dell'apertura.
            $partenza = trim($_POST['start'] ?? '');
            if ($partenza !== '' && !preg_match('/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2})?$/', $partenza)) {
                redirect('/manage?section=theme&err=data#countdown');
            }
            // Tre colori a parte: portale, miccia e testo. Ognuno torna al suo se scritto male.
            $tinta = function (string $campo, string $ripiego): string {
                $c = trim($_POST[$campo] ?? '');
                return is_valid_hex_color($c) ? $c : $ripiego;
            };
            $colore = $tinta('color', '#c04ff0');
            $coloreMiccia = $tinta('fuse_color', '#c04ff0');
            $coloreTesto = $tinta('text_color', '#a3e635');

            $upd = db()->prepare('INSERT INTO site_settings (setting_key, setting_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)');
            $upd->execute(['countdown_enabled', $acceso]);
            $upd->execute(['countdown_target', $quando]);
            $upd->execute(['countdown_start', $partenza]);
            $upd->execute(['countdown_color', $colore]);
            $upd->execute(['countdown_fuse_color', $coloreMiccia]);
            $upd->execute(['countdown_text_color', $coloreTesto]);
            $upd->execute(['countdown_tag', trim($_POST['tag'] ?? '')]);
            $upd->execute(['countdown_title', trim($_POST['title'] ?? '')]);
            $upd->execute(['countdown_text', trim($_POST['text'] ?? '')]);
            $upd->execute(['countdown_done_title', trim($_POST['done_title'] ?? '')]);
            $upd->execute(['countdown_done_text', trim($_POST['done_text'] ?? '')]);
            $upd->execute(['countdown_piglin_text', trim($_POST['piglin_text'] ?? '')]);
            $upd->execute(['countdown_button_text', trim($_POST['button_text'] ?? '')]);
            $upd->execute(['countdown_button_url', trim($_POST['button_url'] ?? '')]);
            redirect('/manage?section=theme&ok=1#countdown');
        }

        case 'vip_banner_save': {
            $enabled = isset($_POST['enabled']) ? '1' : '0';
            $icon = trim($_POST['icon'] ?? '');
            $tag = trim($_POST['tag'] ?? '');
            $title = trim($_POST['title'] ?? '');
            $text = trim($_POST['text'] ?? '');
            $buttonText = trim($_POST['button_text'] ?? '');
            $buttonUrl = trim($_POST['button_url'] ?? '');
            $color = trim($_POST['color'] ?? '');
            $image = trim($_POST['image'] ?? '');
            $overlayIntensity = max(0, min(100, (int) ($_POST['overlay_intensity'] ?? 85)));
            $borderAnim = isset($_POST['border_anim']) ? '1' : '0';
            $borderAnimMobile = isset($_POST['border_anim_mobile']) ? '1' : '0';
            $aloneMobile = isset($_POST['glow_mobile']) ? '1' : '0';
            $seguiEvidenza = isset($_POST['follow_featured']) ? '1' : '0';

            if ($title === '' || $buttonText === '' || $buttonUrl === '' || !is_valid_hex_color($color)) {
                redirect('/manage?section=theme&err=empty#banner-vip');
            }

            $upd = db()->prepare('INSERT INTO site_settings (setting_key, setting_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)');
            $upd->execute(['vip_banner_enabled', $enabled]);
            $upd->execute(['vip_banner_icon', $icon]);
            $upd->execute(['vip_banner_tag', $tag]);
            $upd->execute(['vip_banner_title', $title]);
            $upd->execute(['vip_banner_text', $text]);
            $upd->execute(['vip_banner_button_text', $buttonText]);
            $upd->execute(['vip_banner_button_url', $buttonUrl]);
            $upd->execute(['vip_banner_follow_featured', $seguiEvidenza]);
            $upd->execute(['vip_banner_color', $color]);
            $upd->execute(['vip_banner_image', $image]);
            $upd->execute(['vip_banner_overlay_intensity', (string) $overlayIntensity]);
            $upd->execute(['vip_banner_border_anim', $borderAnim]);
            $upd->execute(['vip_banner_border_anim_mobile', $borderAnimMobile]);
            $upd->execute(['vip_banner_glow_mobile', $aloneMobile]);
            redirect('/manage?section=theme&ok=1#banner-vip');
        }

        case 'chat_settings_save': {
            $enabled = isset($_POST['chat_enabled']) ? '1' : '0';
            $showGame = isset($_POST['chat_show_game']) ? '1' : '0';
            // Limiti uguali a quelli applicati dall'API (public/api/chat): quello che
            // si salva qui non puo' mai essere fuori dai valori che la chat accetta.
            $history = max(5, min(100, (int) ($_POST['chat_history'] ?? 40)));
            $slowmode = max(0, min(120, (int) ($_POST['chat_slowmode'] ?? 3)));

            $upd = db()->prepare('INSERT INTO site_settings (setting_key, setting_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)');
            $upd->execute(['chat_enabled', $enabled]);
            $upd->execute(['chat_show_game', $showGame]);
            $upd->execute(['chat_history', (string) $history]);
            $upd->execute(['chat_slowmode', (string) $slowmode]);
            redirect('/manage?section=theme&ok=1#chat-live');
        }

        case 'guida_intro_save': {
            // Testo di apertura della pagina /tutorial. Vuoto = torna quello predefinito
            // (vedi guide_intro() in helpers.php): la pagina non resta mai muta.
            $intro = trim($_POST['tutorial_intro'] ?? '');
            if (mb_strlen($intro) > 600) {
                $intro = mb_substr($intro, 0, 600);
            }
            db()->prepare('INSERT INTO site_settings (setting_key, setting_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)')
                ->execute(['tutorial_intro', $intro]);
            redirect('/manage?section=pages&ok=1#guida');
        }

        case 'store_cat_save': {
            $id = (int) ($_POST['id'] ?? 0);
            $name = trim($_POST['name'] ?? '');
            $description = trim($_POST['description'] ?? '');
            $sortOrder = (int) ($_POST['sort_order'] ?? 0);
            $enabled = isset($_POST['enabled']) ? 1 : 0;

            if ($name === '') {
                redirect('/manage?section=store&err=empty');
            }

            // Velo personalizzato: se la casella non e' spuntata si salva NULL su tutti e
            // quattro i campi, cioe' "eredita dalle impostazioni generali dello store".
            $personalizza = isset($_POST['overlay_custom']);
            $vColore = trim($_POST['overlay_color'] ?? '');
            $velo = [
                'colore' => $personalizza && is_valid_hex_color($vColore) ? $vColore : null,
                'intensita' => $personalizza ? max(0, min(100, (int) ($_POST['overlay_intensity'] ?? 92))) : null,
                'altezza' => $personalizza ? max(20, min(100, (int) ($_POST['overlay_stop'] ?? 55))) : null,
                'direzione' => $personalizza ? (($_POST['overlay_direction'] ?? '') === 'orizzontale' ? 'orizzontale' : 'verticale') : null,
            ];
            $bordoCat = trim($_POST['border_color'] ?? '');
            $bordoCat = $personalizza && is_valid_hex_color($bordoCat) ? $bordoCat : null;

            // Stessi campi per il tema chiaro. Colonne vuote = "come il tema scuro", quindi
            // qui si salva NULL sia quando l'aspetto proprio e' spento sia quando il valore
            // non e' valido: la card ricadra' da sola sulla versione scura.
            $vColoreChiaro = trim($_POST['overlay_color_light'] ?? '');
            $veloChiaro = [
                'colore' => $personalizza && is_valid_hex_color($vColoreChiaro) ? $vColoreChiaro : null,
                'intensita' => $personalizza && isset($_POST['overlay_intensity_light'])
                    ? max(0, min(100, (int) $_POST['overlay_intensity_light'])) : null,
                'altezza' => $personalizza && isset($_POST['overlay_stop_light'])
                    ? max(20, min(100, (int) $_POST['overlay_stop_light'])) : null,
            ];
            $bordoCatChiaro = trim($_POST['border_color_light'] ?? '');
            $bordoCatChiaro = $personalizza && is_valid_hex_color($bordoCatChiaro) ? $bordoCatChiaro : null;

            // Testo, prezzo e targhetta dello sconto: valgono solo se l'aspetto proprio e'
            // acceso E la relativa spunta e' messa; NULL vuol dire "eredita dallo store".
            $sceltaCat = function (string $campo, string $spunta) use ($personalizza): ?string {
                $valore = trim($_POST[$campo] ?? '');
                return $personalizza && isset($_POST[$spunta]) && is_valid_hex_color($valore) ? $valore : null;
            };
            $catTesto = $sceltaCat('text_color', 'text_custom');
            $catTestoChiaro = $sceltaCat('text_color_light', 'text_custom_chiaro') ?? $catTesto;
            $catPrezzo = $sceltaCat('price_color', 'price_custom');
            $catPrezzoChiaro = $sceltaCat('price_color_light', 'price_custom_chiaro') ?? $catPrezzo;
            $catSconto = $sceltaCat('discount_color', 'sconto_custom');

            // Colori del pulsante-filtro: stessa logica del velo (NULL = usa i generali)
            $filtroCustom = isset($_POST['filter_custom']);
            $fAttivo = trim($_POST['filter_active_color'] ?? '');
            $fRiposo = trim($_POST['filter_idle_color'] ?? '');
            $filtroAttivo = $filtroCustom && is_valid_hex_color($fAttivo) ? $fAttivo : null;
            $filtroRiposo = $filtroCustom && is_valid_hex_color($fRiposo) ? $fRiposo : null;

            [$scontoTipo, $scontoValore] = sconto_dal_post();

            if ($id > 0) {
                db()->prepare('UPDATE store_categories SET name = ?, description = ?, sort_order = ?, enabled = ?, overlay_color = ?, overlay_intensity = ?, overlay_stop = ?, overlay_direction = ?, border_color = ?, overlay_color_light = ?, overlay_intensity_light = ?, overlay_stop_light = ?, border_color_light = ?, text_color = ?, text_color_light = ?, price_color = ?, price_color_light = ?, discount_color = ?, filter_active_color = ?, filter_idle_color = ?, discount_type = ?, discount_value = ? WHERE id = ?')
                    ->execute([$name, $description, $sortOrder, $enabled, $velo['colore'], $velo['intensita'], $velo['altezza'], $velo['direzione'], $bordoCat, $veloChiaro['colore'], $veloChiaro['intensita'], $veloChiaro['altezza'], $bordoCatChiaro, $catTesto, $catTestoChiaro, $catPrezzo, $catPrezzoChiaro, $catSconto, $filtroAttivo, $filtroRiposo, $scontoTipo, $scontoValore, $id]);
            } else {
                // Lo slug si genera dal nome e non cambia piu': gli URL restano stabili
                $slug = unique_slug('store_categories', slugify($name));
                db()->prepare('INSERT INTO store_categories (name, slug, description, sort_order, enabled, overlay_color, overlay_intensity, overlay_stop, overlay_direction, border_color, overlay_color_light, overlay_intensity_light, overlay_stop_light, border_color_light, text_color, text_color_light, price_color, price_color_light, discount_color, filter_active_color, filter_idle_color, discount_type, discount_value) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)')
                    ->execute([$name, $slug, $description, $sortOrder, $enabled, $velo['colore'], $velo['intensita'], $velo['altezza'], $velo['direzione'], $bordoCat, $veloChiaro['colore'], $veloChiaro['intensita'], $veloChiaro['altezza'], $bordoCatChiaro, $catTesto, $catTestoChiaro, $catPrezzo, $catPrezzoChiaro, $catSconto, $filtroAttivo, $filtroRiposo, $scontoTipo, $scontoValore]);
            }
            redirect('/manage?section=store&ok=1#storeSort');
        }

        case 'store_cat_delete': {
            $id = (int) ($_POST['id'] ?? 0);
            // I pacchetti non si perdono: restano senza categoria (ON DELETE SET NULL)
            db()->prepare('DELETE FROM store_categories WHERE id = ?')->execute([$id]);
            redirect('/manage?section=store&ok=1#storeSort');
        }

        case 'store_pkg_save': {
            $id = (int) ($_POST['id'] ?? 0);
            $name = trim($_POST['name'] ?? '');
            $categoryId = (int) ($_POST['category_id'] ?? 0) ?: null;
            $imageUrl = trim($_POST['image_url'] ?? '') ?: null;
            $description = trim($_POST['description'] ?? '');
            $longDescription = trim($_POST['long_description'] ?? '');
            $price = round((float) str_replace(',', '.', (string) ($_POST['price'] ?? '0')), 2);
            $commands = trim(str_replace(["
", "
"], "
", $_POST['commands'] ?? ''));
            $sortOrder = (int) ($_POST['sort_order'] ?? 0);
            $enabled = isset($_POST['enabled']) ? 1 : 0;
            $featured = isset($_POST['featured']) ? 1 : 0;
            // Inquadratura della copertina: due percentuali e nient'altro (finisce inline in
            // uno style), una per il telefono e una per il computer. Come per gli articoli.
            $puntoValido = static fn($v) => preg_match('/^\d{1,3}% \d{1,3}%$/', (string) $v) ? $v : '50% 50%';
            $imagePosition = $puntoValido($_POST['image_position'] ?? '');
            $imagePositionPc = $puntoValido($_POST['image_position_pc'] ?? '');

            if ($name === '' || $price < 0) {
                redirect('/manage?section=store_pkg_edit&id=' . $id . '&err=empty');
            }

            [$scontoTipo, $scontoValore] = sconto_dal_post();

            // Il pacchetto in evidenza e' uno solo in tutto lo store: se questo lo diventa,
            // gli altri vengono azzerati nella stessa transazione, cosi' non puo' mai
            // esistere un momento con due vetrine (o zero, se qualcosa va storto a meta').
            db()->beginTransaction();
            if ($id > 0) {
                db()->prepare('UPDATE store_packages SET category_id = ?, name = ?, image_url = ?, description = ?, long_description = ?, price = ?, discount_type = ?, discount_value = ?, commands = ?, sort_order = ?, enabled = ?, featured = ?, updated_at = NOW() WHERE id = ?')
                    ->execute([$categoryId, $name, $imageUrl, $description, $longDescription, $price, $scontoTipo, $scontoValore, $commands, $sortOrder, $enabled, $featured, $id]);
            } else {
                $slug = unique_slug('store_packages', slugify($name));
                db()->prepare('INSERT INTO store_packages (category_id, name, slug, image_url, description, long_description, price, discount_type, discount_value, commands, sort_order, enabled, featured) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)')
                    ->execute([$categoryId, $name, $slug, $imageUrl, $description, $longDescription, $price, $scontoTipo, $scontoValore, $commands, $sortOrder, $enabled, $featured]);
                $id = (int) db()->lastInsertId();
            }
            // Inquadratura scritta a parte e solo se le colonne esistono: cosi' il resto del
            // salvataggio funziona anche prima di lanciare la migrazione (vedi store_ha_inquadratura).
            if (store_ha_inquadratura()) {
                db()->prepare('UPDATE store_packages SET image_position = ?, image_position_pc = ? WHERE id = ?')
                    ->execute([$imagePosition, $imagePositionPc, $id]);
            }
            if ($featured) {
                db()->prepare('UPDATE store_packages SET featured = 0 WHERE id <> ?')->execute([$id]);
            }
            db()->commit();
            redirect('/manage?section=store&ok=1#storeSort');
        }

        case 'store_pkg_clone': {
            $id = (int) ($_POST['id'] ?? 0);
            $orig = db()->prepare('SELECT category_id, name, image_url, description, long_description, price, commands, sort_order FROM store_packages WHERE id = ?');
            $orig->execute([$id]);
            $pkg = $orig->fetch();
            if (!$pkg) {
                redirect('/manage?section=store&err=empty');
            }

            // Copia nascosta e messa subito dopo l'originale: cosi' non compare in vetrina
            // finche' non la si rivede, ma nell'elenco sta accanto a quello da cui nasce.
            $nome = mb_substr($pkg['name'] . ' (copia)', 0, 255);
            $slug = unique_slug('store_packages', slugify($nome));
            $ins = db()->prepare('INSERT INTO store_packages (category_id, name, slug, image_url, description, long_description, price, commands, sort_order, enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)');
            $ins->execute([$pkg['category_id'], $nome, $slug, $pkg['image_url'], $pkg['description'], $pkg['long_description'],
                           $pkg['price'], $pkg['commands'], (int) $pkg['sort_order'] + 1]);
            redirect('/manage?section=store_pkg_edit&id=' . (int) db()->lastInsertId() . '&ok=1');
        }

        case 'store_pkg_deliver': {
            $id = (int) ($_POST['id'] ?? 0);
            $nome = trim($_POST['player'] ?? '');

            $q = db()->prepare('SELECT * FROM store_packages WHERE id = ?');
            $q->execute([$id]);
            $pkg = $q->fetch();
            if (!$pkg) {
                redirect('/manage?section=store&err=empty#storeSort');
            }

            // Destinatario: per nome. Serve l'UUID, quindi il giocatore dev'essere gia' noto al
            // sito — o perche' ha collegato l'account (users) o perche' e' entrato in partita
            // almeno una volta da quando esistono i gradi (mc_ranks).
            if ($nome === '' || strcasecmp($nome, (string) $me['mc_username']) === 0) {
                $destUuid = $me['mc_uuid'];
                $destNome = $me['mc_username'];
                $destUserId = $me['id'];
            } else {
                $cerca = db()->prepare('SELECT id, mc_uuid, mc_username FROM users WHERE mc_username = ?');
                $cerca->execute([$nome]);
                $trovato = $cerca->fetch();
                if (!$trovato) {
                    $cerca = db()->prepare('SELECT NULL AS id, mc_uuid, mc_username FROM mc_ranks WHERE mc_username = ?');
                    $cerca->execute([$nome]);
                    $trovato = $cerca->fetch();
                }
                if (!$trovato) {
                    redirect('/manage?section=store_pkg_edit&id=' . $id . '&err=giocatore');
                }
                $destUuid = $trovato['mc_uuid'];
                $destNome = $trovato['mc_username'];
                $destUserId = $trovato['id'] !== null ? (int) $trovato['id'] : null;
            }

            // Ordine vero, ma segnato come consegna MANUALE: passa dalla stessa funzione del
            // pagamento PayPal (store_completa_ordine), cosi' la coda dei comandi e' identica
            // e non esiste una seconda strada da tenere allineata.
            $ins = db()->prepare('INSERT INTO store_orders (user_id, package_id, package_name, mc_uuid, mc_username, price, currency) VALUES (?, ?, ?, ?, ?, ?, ?)');
            $ins->execute([$destUserId, $pkg['id'], $pkg['name'], $destUuid, $destNome,
                           $pkg['price'], site_setting('store_currency', 'EUR')]);
            $orderId = (int) db()->lastInsertId();

            $q = db()->prepare('SELECT * FROM store_orders WHERE id = ?');
            $q->execute([$orderId]);
            $order = $q->fetch();

            require_once __DIR__ . '/../includes/paypal.php';
            store_completa_ordine($order, 'MANUALE-' . mb_substr((string) $me['mc_username'], 0, 24));

            redirect('/manage?section=store&consegnato=' . urlencode($destNome) . '&pacchetto=' . urlencode($pkg['name']) . '#storeSort');
        }

        case 'store_pkg_delete': {
            $id = (int) ($_POST['id'] ?? 0);
            db()->prepare('DELETE FROM store_packages WHERE id = ?')->execute([$id]);
            redirect('/manage?section=store&ok=1#storeSort');
        }

        case 'store_pkg_toggle': {
            $id = (int) ($_POST['id'] ?? 0);
            db()->prepare('UPDATE store_packages SET enabled = 1 - enabled WHERE id = ?')->execute([$id]);
            redirect('/manage?section=store&ok=1#storeSort');
        }

        case 'goal_save': {
            $upd = db()->prepare('INSERT INTO site_settings (setting_key, setting_value) VALUES (?, ?)
                                  ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)');
            $upd->execute(['goal_enabled', isset($_POST['goal_enabled']) ? '1' : '0']);
            $upd->execute(['goal_home', isset($_POST['goal_home']) ? '1' : '0']);
            $upd->execute(['goal_show_amount', isset($_POST['goal_show_amount']) ? '1' : '0']);
            $upd->execute(['goal_title', trim($_POST['goal_title'] ?? '') ?: 'Obiettivo del server']);
            $upd->execute(['goal_text', trim($_POST['goal_text'] ?? '')]);

            // La cifra arriva come testo: la virgola italiana diventa punto, e non si
            // salvano valori negativi (una barra con obiettivo negativo non ha senso).
            $cifra = str_replace(',', '.', trim($_POST['goal_amount'] ?? '0'));
            $upd->execute(['goal_amount', (string) max(0, round((float) $cifra, 2))]);

            $periodo = $_POST['goal_period'] ?? 'mensile';
            $upd->execute(['goal_period', in_array($periodo, ['settimanale', 'mensile', 'annuale'], true) ? $periodo : 'mensile']);
            redirect('/manage?section=store&ok=1#obiettivo');
        }

        case 'store_settings_save': {
            $colore = trim($_POST['store_overlay_color'] ?? '');
            if (!is_valid_hex_color($colore)) {
                redirect('/manage?section=store&err=empty');
            }
            $upd = db()->prepare('INSERT INTO site_settings (setting_key, setting_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)');
            $upd->execute(['store_overlay_color', $colore]);
            // Interruttore del velo delle card: separato da quello degli articoli.
            $upd->execute(['card_overlay_store', isset($_POST['card_overlay_store']) ? '1' : '0']);
            $upd->execute(['store_overlay_intensity', (string) max(0, min(100, (int) ($_POST['store_overlay_intensity'] ?? 92)))]);
            $upd->execute(['store_overlay_stop', (string) max(20, min(100, (int) ($_POST['store_overlay_stop'] ?? 55)))]);
            $bordo = trim($_POST['store_border_color'] ?? '');
            $upd->execute(['store_border_color', is_valid_hex_color($bordo) ? $bordo : '#f0c75e']);

            // Gli stessi valori per il tema CHIARO: campo vuoto o sbagliato = quello del tema
            // scuro, cioe' il comportamento di prima.
            $coloreChiaro = trim($_POST['store_overlay_color_chiaro'] ?? '');
            $upd->execute(['store_overlay_color_chiaro', is_valid_hex_color($coloreChiaro) ? $coloreChiaro : $colore]);
            $upd->execute(['store_overlay_intensity_chiaro', (string) max(0, min(100, (int) ($_POST['store_overlay_intensity_chiaro'] ?? $_POST['store_overlay_intensity'] ?? 92)))]);
            $upd->execute(['store_overlay_stop_chiaro', (string) max(20, min(100, (int) ($_POST['store_overlay_stop_chiaro'] ?? $_POST['store_overlay_stop'] ?? 55)))]);
            $bordoChiaro = trim($_POST['store_border_color_chiaro'] ?? '');
            $upd->execute(['store_border_color_chiaro', is_valid_hex_color($bordoChiaro) ? $bordoChiaro : (is_valid_hex_color($bordo) ? $bordo : '#f0c75e')]);

            // Testo sopra le card: senza la spunta si salva la stringa vuota, che vuol dire
            // "automatico" (lo decide il velo). Un tema alla volta, sono indipendenti.
            $sconto = trim($_POST['store_sconto_color'] ?? '');
            $upd->execute(['store_sconto_color', is_valid_hex_color($sconto) ? $sconto : '']);

            foreach ([
                'store_text_color' => 'store_text_custom',
                'store_text_color_chiaro' => 'store_text_custom_chiaro',
                'store_price_color' => 'store_price_custom',
                'store_price_color_chiaro' => 'store_price_custom_chiaro',
            ] as $chiave => $spunta) {
                $valore = trim($_POST[$chiave] ?? '');
                $upd->execute([$chiave, isset($_POST[$spunta]) && is_valid_hex_color($valore) ? $valore : '']);
            }

            foreach (['store_overlay_direction', 'store_featured_overlay_direction'] as $chiave) {
                $upd->execute([$chiave, ($_POST[$chiave] ?? '') === 'orizzontale' ? 'orizzontale' : 'verticale']);
            }
            redirect('/manage?section=store&ok=1#velo');
        }

        case 'store_layout_save': {
            // Quante card per riga nella vetrina (desktop/tablet); i telefoni restano compatti.
            $colonne = max(2, min(6, (int) ($_POST['store_cols'] ?? 3)));
            db()->prepare('INSERT INTO site_settings (setting_key, setting_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)')
                ->execute(['store_cols', (string) $colonne]);
            redirect('/manage?section=store&ok=1#layout');
        }

        case 'store_filters_save': {
            $attivo = trim($_POST['store_filter_active_color'] ?? '');
            $riposo = trim($_POST['store_filter_idle_color'] ?? '');
            if (!is_valid_hex_color($attivo) || !is_valid_hex_color($riposo)) {
                redirect('/manage?section=store&err=empty');
            }
            $upd = db()->prepare('INSERT INTO site_settings (setting_key, setting_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)');
            $upd->execute(['store_filter_active_color', $attivo]);
            $upd->execute(['store_filter_idle_color', $riposo]);
            redirect('/manage?section=store&ok=1#filtri');
        }

        case 'store_sconto_save': {
            [$tipo, $valore] = sconto_dal_post();
            $upd = db()->prepare('INSERT INTO site_settings (setting_key, setting_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)');
            // Il tipo si salva comunque, cosi' riaccendendo lo sconto si ritrova la scelta.
            $upd->execute(['store_discount_type', $tipo ?? ($_POST['discount_type'] === 'importo' ? 'importo' : 'percentuale')]);
            $upd->execute(['store_discount_value', number_format($valore, 2, '.', '')]);
            redirect('/manage?section=store&ok=1#sconti');
        }

        case 'store_sidebar_save': {
            // Limiti uguali a quelli che applica store.php quando legge le impostazioni.
            $quanti = max(0, min(20, (int) ($_POST['recent_count'] ?? 5)));
            $giorni = max(0, min(3650, (int) ($_POST['top_days'] ?? 0)));
            $titoloRecenti = trim($_POST['recent_title'] ?? '') ?: 'Ultimi acquisti';
            $titoloTop = trim($_POST['top_title'] ?? '') ?: 'Miglior sostenitore';

            $upd = db()->prepare('INSERT INTO site_settings (setting_key, setting_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)');
            $upd->execute(['store_sidebar_enabled', isset($_POST['enabled']) ? '1' : '0']);
            $upd->execute(['store_sidebar_recent_count', (string) $quanti]);
            $upd->execute(['store_sidebar_recent_title', mb_substr($titoloRecenti, 0, 60)]);
            $upd->execute(['store_sidebar_show_amount', isset($_POST['show_amount']) ? '1' : '0']);
            $upd->execute(['store_sidebar_show_package', isset($_POST['show_package']) ? '1' : '0']);
            $upd->execute(['store_sidebar_show_date', isset($_POST['show_date']) ? '1' : '0']);
            $upd->execute(['store_sidebar_show_name', isset($_POST['show_name']) ? '1' : '0']);
            $upd->execute(['store_sidebar_show_rank', isset($_POST['show_rank']) ? '1' : '0']);
            $upd->execute(['store_sidebar_top_enabled', isset($_POST['top_enabled']) ? '1' : '0']);
            $upd->execute(['store_sidebar_top_title', mb_substr($titoloTop, 0, 60)]);
            $upd->execute(['store_sidebar_top_days', (string) $giorni]);
            $upd->execute(['store_sidebar_include_manual', isset($_POST['include_manual']) ? '1' : '0']);
            redirect('/manage?section=store&ok=1#sidebar');
        }

        case 'store_reorder': {
            header('Content-Type: application/json');
            $dati = json_decode((string) ($_POST['ordine'] ?? ''), true);
            if (!is_array($dati)) {
                echo json_encode(['ok' => false]);
                exit;
            }

            $pdo = db();
            $pdo->beginTransaction();
            try {
                // Ordine delle categorie: la posizione nell'elenco diventa sort_order.
                // L'id 0 e' il gruppo "senza categoria", che non esiste come riga.
                $updCat = $pdo->prepare('UPDATE store_categories SET sort_order = ? WHERE id = ?');
                foreach (array_values((array) ($dati['categorie'] ?? [])) as $posizione => $catId) {
                    if ((int) $catId > 0) {
                        $updCat->execute([$posizione, (int) $catId]);
                    }
                }

                // Ogni pacchetto porta con se' la categoria in cui e' stato lasciato cadere
                $updPkg = $pdo->prepare('UPDATE store_packages SET category_id = ?, sort_order = ? WHERE id = ?');
                foreach ((array) ($dati['pacchetti'] ?? []) as $riga) {
                    $catId = (int) ($riga['categoria'] ?? 0);
                    $updPkg->execute([$catId > 0 ? $catId : null, (int) ($riga['ordine'] ?? 0), (int) ($riga['id'] ?? 0)]);
                }
                $pdo->commit();
            } catch (Throwable $e) {
                $pdo->rollBack();
                error_log('Store: riordino fallito: ' . $e->getMessage());
                echo json_encode(['ok' => false]);
                exit;
            }

            echo json_encode(['ok' => true]);
            exit;
        }

        case 'payments_save': {
            $mode = ($_POST['paypal_mode'] ?? 'sandbox') === 'live' ? 'live' : 'sandbox';
            $currency = strtoupper(preg_replace('/[^A-Za-z]/', '', (string) ($_POST['store_currency'] ?? 'EUR')));
            if (strlen($currency) !== 3) {
                $currency = 'EUR';
            }

            $upd = db()->prepare('INSERT INTO site_settings (setting_key, setting_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)');
            $upd->execute(['paypal_enabled', isset($_POST['paypal_enabled']) ? '1' : '0']);
            $upd->execute(['paypal_mode', $mode]);
            $upd->execute(['paypal_email', trim($_POST['paypal_email'] ?? '')]);
            $upd->execute(['paypal_client_id', trim($_POST['paypal_client_id'] ?? '')]);
            $upd->execute(['store_currency', $currency]);

            // Il segreto si riscrive solo se ne e' stato digitato uno nuovo: il campo parte
            // sempre vuoto, cosi' non viene mai rimandato al browser.
            $secret = trim($_POST['paypal_secret'] ?? '');
            if ($secret !== '') {
                $upd->execute(['paypal_secret', $secret]);
            }
            redirect('/manage?section=store&ok=1#pagamenti');
        }

        case 'perms_save': {
            $group = trim($_POST['group'] ?? '');
            $selected = array_intersect((array) ($_POST['perms'] ?? []), all_web_permissions());

            $exists = db()->prepare('SELECT COUNT(*) FROM web_groups WHERE name = ?');
            $exists->execute([$group]);
            if ($group === '' || (int) $exists->fetchColumn() === 0) {
                redirect('/manage?section=perms&err=nogroup');
            }

            // Riscrittura completa dei permessi del gruppo: piu' semplice e sempre coerente
            // con le caselle spuntate, senza dover calcolare differenze.
            db()->prepare('DELETE FROM web_group_permissions WHERE group_name = ?')->execute([$group]);
            $ins = db()->prepare('INSERT INTO web_group_permissions (group_name, permission) VALUES (?, ?)');
            foreach ($selected as $perm) {
                $ins->execute([$group, $perm]);
            }
            redirect('/manage?section=perms&ok=1');
        }

        case 'user_toggle_admin': {
            $id = (int)($_POST['id'] ?? 0);
            if ($id === (int)$me['id']) {
                redirect('/manage?section=users&err=self');
            }

            // Account protetto: il ruolo web-admin non si tocca dal sito, per nessuno.
            // Il controllo sta QUI, non solo sul pulsante nascosto, perche' la richiesta
            // puo' essere costruita a mano.
            $target = db()->prepare('SELECT mc_uuid, is_admin FROM users WHERE id = ?');
            $target->execute([$id]);
            $targetUser = $target->fetch();
            if ($targetUser && is_protected_admin($targetUser)) {
                redirect('/manage?section=users&err=protected');
            }

            db()->prepare('UPDATE users SET is_admin = 1 - is_admin WHERE id = ?')->execute([$id]);
            redirect('/manage?section=users&ok=1');
        }

        // -----------------------------------------------------------------
        // SANZIONI — il sito non crea provvedimenti (li scrive MagixGuard):
        // qui si registrano le DECISIONI dello staff, che il server poi esegue.
        // -----------------------------------------------------------------
        case 'sanzione_revoca': {
            $id = (int) ($_POST['id'] ?? 0);
            $motivo = mb_substr(trim((string) ($_POST['motivo'] ?? '')), 0, 255);
            if ($id <= 0 || $motivo === '') {
                redirect('/manage?section=sanzioni&err=empty');
            }
            // revoke_applied = 0: in gioco il ban c'e' ancora finche' il plugin non
            // lo toglie. Il gestionale lo mostra come "da applicare", senza far finta.
            db()->prepare(
                "UPDATE punishments
                    SET status = 'revocata', revoked_by = ?, revoked_at = NOW(),
                        revoke_reason = ?, revoke_applied = 0
                  WHERE id = ? AND status = 'attiva'"
            )->execute([(string) $me['mc_username'], $motivo, $id]);
            redirect('/manage?section=sanzioni&ok=1');
        }

        case 'coda_conferma':
        case 'coda_respingi': {
            $id = (int) ($_POST['id'] ?? 0);
            if ($id <= 0) {
                redirect('/manage?section=sanzioni&err=empty');
            }
            $nuovo = $action === 'coda_conferma' ? 'confermata' : 'respinta';

            // Confermando si puo' cambiare il provvedimento: una SEGNALAZIONE non propone una
            // pena, apre un caso — e anche su una proposta automatica lo staff puo' decidere
            // che ci vuole altro. Quello che si sceglie qui e' quello che il server applichera'.
            if ($nuovo === 'confermata') {
                $tipo = (string) ($_POST['tipo'] ?? '');
                if (isset(SANZIONI_TIPI[$tipo])) {
                    $secondi = duration_in_seconds((string) ($_POST['durata'] ?? ''));
                    db()->prepare('UPDATE punishment_queue SET type = ?, duration_seconds = ? WHERE id = ? AND status = \'attesa\'')
                        ->execute([$tipo, $secondi === 0 ? null : $secondi, $id]);
                }
            }

            db()->prepare(
                "UPDATE punishment_queue
                    SET status = ?, decided_by = ?, decided_at = NOW()
                  WHERE id = ? AND status = 'attesa'"
            )->execute([$nuovo, (string) $me['mc_username'], $id]);
            redirect('/manage?section=sanzioni&ok=' . ($nuovo === 'confermata' ? '4' : '5'));
        }

        case 'ricorso_decidi': {
            $id = (int) ($_POST['id'] ?? 0);
            $esito = (string) ($_POST['esito'] ?? '');
            $risposta = trim((string) ($_POST['risposta'] ?? ''));
            $pubblico = mb_substr(trim((string) ($_POST['esito_pubblico'] ?? '')), 0, 255);
            if ($id <= 0 || !in_array($esito, ['accolto', 'respinto'], true) || $risposta === '') {
                redirect('/manage?section=sanzioni&err=empty');
            }
            db()->prepare(
                'UPDATE punishment_appeals
                    SET status = ?, reply = ?, outcome_public = ?, staff_name = ?, decided_at = NOW()
                  WHERE id = ?'
            )->execute([$esito, mb_substr($risposta, 0, 5000), $pubblico ?: null, (string) $me['mc_username'], $id]);

            // Accogliere un ricorso VUOL DIRE togliere il provvedimento: lasciarlo attivo
            // sarebbe una contraddizione che il sanzionato pagherebbe di persona.
            if ($esito === 'accolto') {
                db()->prepare(
                    "UPDATE punishments s
                        JOIN punishment_appeals r ON r.punishment_id = s.id
                       SET s.status = 'revocata', s.revoked_by = ?, s.revoked_at = NOW(),
                           s.revoke_reason = 'Ricorso accolto', s.revoke_applied = 0
                     WHERE r.id = ? AND s.status = 'attiva'"
                )->execute([(string) $me['mc_username'], $id]);
            }
            redirect('/manage?section=sanzioni&ok=6');
        }

        case 'rischio_controllato': {
            $uuid = trim((string) ($_POST['uuid'] ?? ''));
            $nome = trim((string) ($_POST['nome'] ?? ''));
            $esito = ($_POST['esito'] ?? 'pulito') === 'sospetto' ? 'sospetto' : 'pulito';
            $nota = mb_substr(trim((string) ($_POST['nota'] ?? '')), 0, 500);
            if ($uuid === '' || $nome === '') {
                redirect('/manage?section=rischio&err=empty');
            }
            // Ogni controllo e' una riga nuova: e' anche lo storico di chi ha guardato cosa,
            // e serve a non far ricontrollare due volte la stessa persona a due membri diversi.
            db()->prepare('INSERT INTO punishment_checks (mc_uuid, mc_username, staff_name, note, outcome) '
                        . 'VALUES (?, ?, ?, ?, ?)')
                ->execute([$uuid, $nome, (string) $me['mc_username'], $nota ?: null, $esito]);
            redirect('/manage?section=rischio&ok=7');
        }

        default:
            redirect('/manage?section=' . $backSection);
    }
}

if (isset($_GET['ok'])) {
    $flash = match ($_GET['ok']) {
        '2' => "Verifica azzerata: quell'account e' stato fatto uscire da tutti i dispositivi e dovra' riconfigurarla al prossimo accesso.",
        '3' => "Sessione di gioco chiusa: se quell'account e' in partita adesso, entro pochi secondi si ritrova bloccato e senza codice non prosegue.",
        '4' => 'Sanzione confermata: il server la applica entro pochi secondi.',
        '5' => 'Proposta respinta: nessun provvedimento e\' stato preso.',
        '7' => 'Controllo registrato: quel giocatore esce dalla lista per una settimana.',
        '6' => 'Ricorso deciso. L\'esito e\' visibile all\'interessato, e nell\'elenco pubblico se hai scritto la motivazione breve.',
        default => 'Fatto.',
    };
    $flashType = 'success';
}
if (isset($_GET['consegnato'])) {
    // Il messaggio viene stampato con h(): qui va il testo grezzo, niente escape doppio.
    $flash = 'Consegna avviata: «' . ($_GET['pacchetto'] ?? 'pacchetto') . '» per '
        . $_GET['consegnato'] . '. Il server esegue i comandi entro pochi secondi'
        . ' (se il giocatore è offline, i comandi partono comunque: verifica l\'esito in Pagamenti).';
    $flashType = 'success';
}
if (isset($_GET['err'])) {
    $flashType = 'error';
    $flash = match ($_GET['err']) {
        'giocatore' => 'Giocatore sconosciuto: deve aver collegato l\'account al sito o essere entrato in partita almeno una volta.',
        'empty' => 'Compila tutti i campi obbligatori.',
        'notempty' => 'Non puoi eliminare una categoria che contiene discussioni: spostale o eliminale prima.',
        'hasfiglie' => 'Questa categoria ha delle sotto-categorie: spostale fuori o eliminale prima.',
        'destinazione' => 'Non puoi spostare una discussione lì: quella categoria contiene delle sezioni, quindi le discussioni vanno dentro una di quelle.',
        'nogroup' => 'Gruppo non riconosciuto: l\'elenco arriva da LuckPerms, riprova dopo il prossimo allineamento.',
        'protected' => 'Questo account è protetto: il suo ruolo web-admin si può cambiare solo dal server, non dal sito.',
        'self' => 'Non puoi togliere i permessi di amministratore a te stesso da qui.',
        'core' => 'Questa pagina è protetta e non può essere eliminata.',
        'data' => 'Data del conto alla rovescia non valida: usa il selettore di data e ora.',
        'invalid' => 'Controlla i valori inseriti (i colori devono essere in formato esadecimale, es. #a3e635).',
        default => 'Si è verificato un errore.',
    };
}

// Una sezione si apre solo se si ha almeno uno dei permessi che la riguardano.
if (isset($sectionPermissions[$section]) && !can_any($sectionPermissions[$section])) {
    http_response_code(403);
    die('Non hai il permesso per aprire questa sezione.');
}
if (in_array($section, $adminOnlySections, true) && !is_admin()) {
    http_response_code(403);
    die('Sezione riservata al web-admin.');
}

$page_title = 'Gestione sito';
require __DIR__ . '/../includes/header.php';
?>
<h1 class="page-title">Gestione sito</h1>

<div class="tabs-row">
  <a href="/manage?section=dashboard" class="<?= $section === 'dashboard' ? 'active' : '' ?>">Dashboard</a>
  <?php if (can_any($sectionPermissions['blog'])): ?>
    <a href="/manage?section=blog" class="<?= in_array($section, ['blog', 'blog_edit', 'blog_cestino']) ? 'active' : '' ?>">Blog</a>
  <?php endif; ?>
  <?php if (can_any($sectionPermissions['forum'])): ?>
    <a href="/manage?section=forum" class="<?= $section === 'forum' ? 'active' : '' ?>">Forum</a>
  <?php endif; ?>
  <?php if (is_admin()): ?>
    <?php /* Lo store ha l'oro come accento (vedi .area-store): anche la sua scheda si
             sottolinea d'oro invece che di magenta. */ ?>
    <a href="/manage?section=store" class="<?= in_array($section, ['store', 'store_pkg_edit']) ? 'active e-oro' : '' ?>">Store</a>
    <a href="/manage?section=pages" class="<?= in_array($section, ['pages', 'page_edit', 'guida_edit']) ? 'active' : '' ?>">Pagine e menu</a>
    <a href="/manage?section=theme" class="<?= $section === 'theme' ? 'active' : '' ?>">Aspetto</a>
  <?php endif; ?>
  <?php if (can_any($sectionPermissions['users'])): ?>
    <a href="/manage?section=users" class="<?= $section === 'users' ? 'active' : '' ?>">Utenti</a>
  <?php endif; ?>
  <?php if (can_any($sectionPermissions['sanzioni'])): ?>
    <a href="/manage?section=sanzioni" class="<?= $section === 'sanzioni' ? 'active' : '' ?>">Sanzioni</a>
  <?php endif; ?>
  <?php /* La guida la legge chiunque entri qui dentro: e' il manuale del mestiere. */ ?>
  <a href="/manage?section=guida" class="<?= $section === 'guida' ? 'active' : '' ?>">Guida</a>
  <?php if (is_admin()): ?>
    <a href="/manage?section=perms" class="<?= $section === 'perms' ? 'active' : '' ?>">Permessi</a>
    <a href="/manage?section=sicurezza" class="<?= $section === 'sicurezza' ? 'active' : '' ?>">Sicurezza</a>
    <?php /* La scheda che comanda la macchina: console, avvio e arresto dei server. */ ?>
    <a href="/manage?section=console" class="<?= $section === 'console' ? 'active' : '' ?>">Server</a>
    <?php /* I menu che i giocatori aprono in gioco: si disegnano qui e si applicano al server. */ ?>
    <a href="/manage?section=menu" class="<?= $section === 'menu' ? 'active' : '' ?>">Menu di gioco</a>
  <?php endif; ?>
</div>

<?php if ($flash): ?>
  <div class="alert alert-<?= $flashType === 'error' ? 'error' : 'success' ?>"><?= h($flash) ?></div>
<?php endif; ?>

<?php

// ---------------------------------------------------------------------
// DASHBOARD
// ---------------------------------------------------------------------
if ($section === 'dashboard') {
    $postCount = (int) db()->query('SELECT COUNT(*) FROM blog_posts WHERE deleted_at IS NULL')->fetchColumn();
    $topicCount = (int) db()->query('SELECT COUNT(*) FROM forum_topics')->fetchColumn();
    $userCount = (int) db()->query('SELECT COUNT(*) FROM users')->fetchColumn();
    $catCount = (int) db()->query('SELECT COUNT(*) FROM forum_categories')->fetchColumn();
    ?>
    <div class="stat-grid">
      <div class="stat-box"><div class="num"><?= $postCount ?></div><div class="label">Articoli blog</div></div>
      <div class="stat-box"><div class="num"><?= $topicCount ?></div><div class="label">Discussioni forum</div></div>
      <div class="stat-box"><div class="num"><?= $catCount ?></div><div class="label">Categorie forum</div></div>
      <div class="stat-box"><div class="num"><?= $userCount ?></div><div class="label">Utenti registrati</div></div>
    </div>
    <div class="panel">
      <p>Benvenuto <?= player_name($me, $me['mc_username']) ?>. Le schede qui sopra sono quelle a cui hai accesso:
      <?php if (is_admin()): ?>
        sei <strong>web-admin</strong>, quindi puoi fare tutto e assegnare i permessi agli altri gruppi.
      <?php else: ?>
        i tuoi permessi arrivano dai gruppi che hai in gioco (<?= h(implode(', ', user_groups($me))) ?>).
      <?php endif; ?>
      </p>
    </div>
    <?php

// ---------------------------------------------------------------------
// BLOG — lista
// ---------------------------------------------------------------------
} elseif ($section === 'blog') {
    $posts = db()->query('SELECT id, title, slug, published, created_at FROM blog_posts
                          WHERE deleted_at IS NULL ORDER BY created_at DESC')->fetchAll();
    $quantiCestino = (int) db()->query('SELECT COUNT(*) FROM blog_posts WHERE deleted_at IS NOT NULL')->fetchColumn();
    $s = site_settings();
    ?>
    <?php if (is_admin()): /* impostazioni del sito: non delegabili allo staff */ ?>
    <?php /* Come nello store: le impostazioni stanno in un pannello con la barretta
             laterale e un titolo, cosi' si vede a colpo d'occhio dove finiscono e dove
             comincia l'elenco degli articoli. */ ?>
    <div class="barra-sezione"><h2>Impostazioni del blog</h2></div>
    <div class="panel pannello-predefinito">
      <h3 style="margin-top:0;">Aspetto degli articoli in home</h3>
      <p class="sub" style="margin:-6px 0 14px;">
        Riguarda solo come si vedono le tessere degli articoli nella pagina iniziale —
        quante ne compaiono, il velo sulle copertine e le barrette laterali. Il testo degli
        articoli si modifica qui sotto, uno per uno.
      </p>
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="blog_settings_save">
        <div>
          <label for="blog_per_page">Articoli per pagina in home</label>
          <input type="text" id="blog_per_page" name="blog_per_page" value="<?= h($s['blog_per_page'] ?? '6') ?>" style="max-width:100px;">
        </div>
        <?php
          // Il velo del tema chiaro parte dai valori del tema scuro: finche' non lo si tocca
          // il sito si comporta esattamente come prima, e chi apre questa pagina vede da dove
          // sta partendo invece di trovare campi vuoti.
          $vFC = $s['featured_overlay_color_chiaro'] ?? ($s['featured_overlay_color'] ?? '#c04ff0');
          $vFI = $s['featured_overlay_intensity_chiaro'] ?? ($s['featured_overlay_intensity'] ?? '35');
          $vFS = $s['featured_overlay_stop_chiaro'] ?? ($s['featured_overlay_stop'] ?? '100');
          $vGC = $s['grid_overlay_color_chiaro'] ?? ($s['grid_overlay_color'] ?? '#9a9aa0');
          $vGI = $s['grid_overlay_intensity_chiaro'] ?? ($s['grid_overlay_intensity'] ?? '18');
          $vGS = $s['grid_overlay_stop_chiaro'] ?? ($s['grid_overlay_stop'] ?? '100');
          $vTS = $s['tile_text_color'] ?? '';
          $vTC = $s['tile_text_color_chiaro'] ?? '';
        ?>
        <h3 id="velo" style="margin:22px 0 2px; font-size:15px; scroll-margin-top:96px;">Velo sulle copertine</h3>
        <label class="campo-check" style="margin:8px 0 2px;">
          <input type="checkbox" name="card_overlay_blog" value="1"
                 <?= ($s['card_overlay_blog'] ?? '1') === '1' ? 'checked' : '' ?>>
          Velo acceso sulle tessere degli articoli
        </label>
        <p style="color:var(--text-dim); font-size:12px; margin:0 0 10px;">
          Riguarda <strong>solo il blog</strong>: lo store ha il suo interruttore, in
          <a href="/manage?section=store#velo">Store</a>. Spento, le copertine si vedono pulite e
          al testo sopra arriva un&rsquo;ombra al posto della sfumatura.
        </p>
        <?= nota_interruttore_veli('blog') ?>
        <p style="color:var(--text-dim); font-size:12.5px; margin:0 0 8px;">
          La sfumatura che copre la copertina perch&eacute; il testo sopra si legga. Un velo giusto sul
          fondo nero pu&ograve; essere troppo (o troppo poco) su quello bianco, quindi i due temi si
          regolano a parte: finch&eacute; non tocchi la colonna del tema chiaro vale quella del tema scuro.
          La <strong>direzione</strong> &egrave; una sola per entrambi i temi.
        </p>
        <p style="color:var(--text-dim); font-size:12px; margin:0 0 4px;"><strong>Intensit&agrave;</strong> &mdash; <?= help_overlay('intensita') ?></p>
        <p style="color:var(--text-dim); font-size:12px; margin:0 0 14px;"><strong>Altezza</strong> &mdash; <?= help_overlay('altezza') ?></p>
        <div class="tavolozze">
          <div class="tavolozza">
            <h4>&#9790; Tema scuro</h4>

            <p class="tavolozza-gruppo">Articolo in evidenza</p>
            <label for="featured_overlay_color">Colore velo</label>
            <input type="color" id="featured_overlay_color" name="featured_overlay_color" value="<?= h($s['featured_overlay_color'] ?? '#c04ff0') ?>" style="height:44px; padding:4px;">
            <label for="featured_overlay_intensity" style="display:block; margin-top:12px;">Intensit&agrave; (<?= h($s['featured_overlay_intensity'] ?? '35') ?>%)</label>
            <input type="range" id="featured_overlay_intensity" name="featured_overlay_intensity" min="0" max="100" step="1" value="<?= h($s['featured_overlay_intensity'] ?? '35') ?>" oninput="this.previousElementSibling.textContent=this.previousElementSibling.textContent.replace(/\(\d+%\)/, '('+this.value+'%)')">
            <label for="featured_overlay_stop" style="display:block; margin-top:12px;">Altezza della sfumatura (<?= h($s['featured_overlay_stop'] ?? '100') ?>%)</label>
            <input type="range" id="featured_overlay_stop" name="featured_overlay_stop" min="20" max="100" step="5" value="<?= h($s['featured_overlay_stop'] ?? '100') ?>" oninput="this.previousElementSibling.textContent=this.previousElementSibling.textContent.replace(/\(\d+%\)/, '('+this.value+'%)')">
            <label for="featured_overlay_direction" style="display:block; margin-top:12px;">Direzione</label>
            <select id="featured_overlay_direction" name="featured_overlay_direction">
              <option value="verticale" <?= ($s['featured_overlay_direction'] ?? 'verticale') !== 'orizzontale' ? 'selected' : '' ?>>Verticale (dal basso)</option>
              <option value="orizzontale" <?= ($s['featured_overlay_direction'] ?? 'verticale') === 'orizzontale' ? 'selected' : '' ?>>Orizzontale (da sinistra)</option>
            </select>

            <p class="tavolozza-gruppo">Altri articoli</p>
            <label for="grid_overlay_color">Colore velo</label>
            <input type="color" id="grid_overlay_color" name="grid_overlay_color" value="<?= h($s['grid_overlay_color'] ?? '#9a9aa0') ?>" style="height:44px; padding:4px;">
            <label for="grid_overlay_intensity" style="display:block; margin-top:12px;">Intensit&agrave; (<?= h($s['grid_overlay_intensity'] ?? '18') ?>%)</label>
            <input type="range" id="grid_overlay_intensity" name="grid_overlay_intensity" min="0" max="100" step="1" value="<?= h($s['grid_overlay_intensity'] ?? '18') ?>" oninput="this.previousElementSibling.textContent=this.previousElementSibling.textContent.replace(/\(\d+%\)/, '('+this.value+'%)')">
            <label for="grid_overlay_stop" style="display:block; margin-top:12px;">Altezza della sfumatura (<?= h($s['grid_overlay_stop'] ?? '100') ?>%)</label>
            <input type="range" id="grid_overlay_stop" name="grid_overlay_stop" min="20" max="100" step="5" value="<?= h($s['grid_overlay_stop'] ?? '100') ?>" oninput="this.previousElementSibling.textContent=this.previousElementSibling.textContent.replace(/\(\d+%\)/, '('+this.value+'%)')">
            <label for="grid_overlay_direction" style="display:block; margin-top:12px;">Direzione</label>
            <select id="grid_overlay_direction" name="grid_overlay_direction">
              <option value="verticale" <?= ($s['grid_overlay_direction'] ?? 'verticale') !== 'orizzontale' ? 'selected' : '' ?>>Verticale (dal basso)</option>
              <option value="orizzontale" <?= ($s['grid_overlay_direction'] ?? 'verticale') === 'orizzontale' ? 'selected' : '' ?>>Orizzontale (da sinistra)</option>
            </select>
          </div>

          <div class="tavolozza">
            <h4>&#9728; Tema chiaro</h4>

            <p class="tavolozza-gruppo">Articolo in evidenza</p>
            <label for="featured_overlay_color_chiaro">Colore velo</label>
            <input type="color" id="featured_overlay_color_chiaro" name="featured_overlay_color_chiaro" value="<?= h($vFC) ?>" style="height:44px; padding:4px;">
            <label for="featured_overlay_intensity_chiaro" style="display:block; margin-top:12px;">Intensit&agrave; (<?= h($vFI) ?>%)</label>
            <input type="range" id="featured_overlay_intensity_chiaro" name="featured_overlay_intensity_chiaro" min="0" max="100" step="1" value="<?= h($vFI) ?>" oninput="this.previousElementSibling.textContent=this.previousElementSibling.textContent.replace(/\(\d+%\)/, '('+this.value+'%)')">
            <label for="featured_overlay_stop_chiaro" style="display:block; margin-top:12px;">Altezza della sfumatura (<?= h($vFS) ?>%)</label>
            <input type="range" id="featured_overlay_stop_chiaro" name="featured_overlay_stop_chiaro" min="20" max="100" step="5" value="<?= h($vFS) ?>" oninput="this.previousElementSibling.textContent=this.previousElementSibling.textContent.replace(/\(\d+%\)/, '('+this.value+'%)')">

            <p class="tavolozza-gruppo">Altri articoli</p>
            <label for="grid_overlay_color_chiaro">Colore velo</label>
            <input type="color" id="grid_overlay_color_chiaro" name="grid_overlay_color_chiaro" value="<?= h($vGC) ?>" style="height:44px; padding:4px;">
            <label for="grid_overlay_intensity_chiaro" style="display:block; margin-top:12px;">Intensit&agrave; (<?= h($vGI) ?>%)</label>
            <input type="range" id="grid_overlay_intensity_chiaro" name="grid_overlay_intensity_chiaro" min="0" max="100" step="1" value="<?= h($vGI) ?>" oninput="this.previousElementSibling.textContent=this.previousElementSibling.textContent.replace(/\(\d+%\)/, '('+this.value+'%)')">
            <label for="grid_overlay_stop_chiaro" style="display:block; margin-top:12px;">Altezza della sfumatura (<?= h($vGS) ?>%)</label>
            <input type="range" id="grid_overlay_stop_chiaro" name="grid_overlay_stop_chiaro" min="20" max="100" step="5" value="<?= h($vGS) ?>" oninput="this.previousElementSibling.textContent=this.previousElementSibling.textContent.replace(/\(\d+%\)/, '('+this.value+'%)')">
          </div>
        </div>

        <h3 style="margin:22px 0 2px; font-size:15px;">Testo sopra le copertine</h3>
        <p style="color:var(--text-dim); font-size:12.5px; margin:0 0 12px;">
          Titolo, riassunto, riga dei dati e &ldquo;Leggi tutto&rdquo; delle tessere. Senza la spunta il
          colore lo decide il velo &mdash; nero sui veli chiari, bianco su quelli scuri &mdash; ed e' la
          <strong>stessa regola delle card dei pacchetti</strong>, cosi' le due famiglie di tessere si
          leggono allo stesso modo su entrambi i temi. Il colore scelto vale per il titolo; le altre
          righe lo riprendono piu' tenue.
        </p>
        <div class="tavolozze">
          <div class="tavolozza">
            <h4>&#9790; Tema scuro</h4>
            <label class="campo-check">
              <input type="checkbox" name="tile_text_custom" value="1" style="width:auto;" <?= is_valid_hex_color((string) $vTS) ? 'checked' : '' ?>>
              Scelgo io il colore
            </label>
            <input type="color" id="tile_text_color" name="tile_text_color" value="<?= h(is_valid_hex_color((string) $vTS) ? (string) $vTS : '#f2f2f0') ?>" style="height:44px; padding:4px;">
          </div>
          <div class="tavolozza">
            <h4>&#9728; Tema chiaro</h4>
            <label class="campo-check">
              <input type="checkbox" name="tile_text_custom_chiaro" value="1" style="width:auto;" <?= is_valid_hex_color((string) $vTC) ? 'checked' : '' ?>>
              Scelgo io il colore
            </label>
            <input type="color" id="tile_text_color_chiaro" name="tile_text_color_chiaro" value="<?= h(is_valid_hex_color((string) $vTC) ? (string) $vTC : '#14161a') ?>" style="height:44px; padding:4px;">
          </div>
        </div>

        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="featured_border_anim" value="1" style="width:auto;" <?= ($s['featured_border_anim'] ?? '1') === '1' ? 'checked' : '' ?>>
            Bordo animato luccicante sull'articolo in evidenza
          </label>
        </div>

        <?php
          $vBF = $s['featured_border_color'] ?? '#c04ff0';
          $vBG = $s['grid_border_color'] ?? '#c04ff0';
          $vBFc = $s['featured_border_color_chiaro'] ?? $vBF;
          $vBGc = $s['grid_border_color_chiaro'] ?? $vBG;
        ?>
        <h3 style="margin:22px 0 2px; font-size:15px;">Barretta laterale</h3>
        <p style="color:var(--text-dim); font-size:12.5px; margin:0 0 12px;">
          La riga di colore sul fianco sinistro della tessera. Un colore acceso che spicca sul fondo
          nero puo' sparire su quello bianco: anche qui i due temi si regolano a parte, e finche' non
          tocchi la colonna del tema chiaro vale quella del tema scuro.
        </p>
        <div class="tavolozze">
          <div class="tavolozza">
            <h4>&#9790; Tema scuro</h4>
            <label for="featured_border_color">Articolo in evidenza</label>
            <input type="color" id="featured_border_color" name="featured_border_color" value="<?= h($vBF) ?>" style="height:44px; padding:4px;">
            <label for="grid_border_color" style="display:block; margin-top:12px;">Altri articoli</label>
            <input type="color" id="grid_border_color" name="grid_border_color" value="<?= h($vBG) ?>" style="height:44px; padding:4px;">
          </div>
          <div class="tavolozza">
            <h4>&#9728; Tema chiaro</h4>
            <label for="featured_border_color_chiaro">Articolo in evidenza</label>
            <input type="color" id="featured_border_color_chiaro" name="featured_border_color_chiaro" value="<?= h($vBFc) ?>" style="height:44px; padding:4px;">
            <label for="grid_border_color_chiaro" style="display:block; margin-top:12px;">Altri articoli</label>
            <input type="color" id="grid_border_color_chiaro" name="grid_border_color_chiaro" value="<?= h($vBGc) ?>" style="height:44px; padding:4px;">
          </div>
        </div>
        <div>
          <label for="grid_overlay_direction">Direzione del velo — altri articoli</label>
          <select id="grid_overlay_direction" name="grid_overlay_direction">
            <option value="verticale" <?= ($s['grid_overlay_direction'] ?? 'verticale') !== 'orizzontale' ? 'selected' : '' ?>>Verticale (dal basso)</option>
            <option value="orizzontale" <?= ($s['grid_overlay_direction'] ?? 'verticale') === 'orizzontale' ? 'selected' : '' ?>>Orizzontale (da sinistra)</option>
          </select>
        </div>
        <button type="submit" class="btn btn-accent">Salva impostazioni blog</button>
      </form>
    </div>
    <?php endif; ?>

    <?php /* Il pulsante verde sta su una riga sua col titolo della lista: sotto il
             pulsante viola delle impostazioni sembrava attaccato, come se fossero due
             pulsanti dello stesso modulo. */ ?>
    <div class="barra-sezione">
      <h2>Articoli</h2>
      <?php if (can('blog.delete') && $quantiCestino > 0): ?>
        <a href="/manage?section=blog_cestino" class="btn btn-ghost btn-small">Articoli eliminati (<?= $quantiCestino ?>)</a>
      <?php endif; ?>
      <?php if (can('blog.create')): ?>
        <a href="/blog/new" class="btn btn-green btn-small">+ Nuovo articolo</a>
      <?php endif; ?>
    </div>
    <div>
      <?php if (!$posts): ?>
        <div class="panel"><p>Nessun articolo ancora.</p></div>
      <?php endif; ?>
      <?php foreach ($posts as $p): ?>
        <div class="manage-row">
          <div>
            <div class="title"><?= h($p['title']) ?></div>
            <div class="sub"><?= time_ago($p['created_at']) ?> · <?= $p['published'] ? '<span class="badge-yes">pubblicato</span>' : '<span class="badge-no">bozza</span>' ?></div>
          </div>
          <div class="actions">
            <a href="/blog/<?= urlencode($p['slug']) ?>" class="btn btn-ghost btn-small">Vedi</a>
            <?php if (can('blog.edit')): ?>
              <a href="/manage?section=blog_edit&id=<?= $p['id'] ?>" class="btn btn-accent btn-small">Modifica</a>
            <?php endif; ?>
            <?php if (can('blog.create')): ?>
              <form method="post">
                <?= csrf_field() ?>
                <input type="hidden" name="action" value="blog_clone">
                <input type="hidden" name="id" value="<?= $p['id'] ?>">
                <button type="submit" class="btn btn-ghost btn-small" title="Crea una copia non pubblicata e aprila">Clona</button>
              </form>
            <?php endif; ?>
            <?php if (can('blog.publish')): ?>
              <form method="post">
                <?= csrf_field() ?>
                <input type="hidden" name="action" value="blog_toggle_publish">
                <input type="hidden" name="id" value="<?= $p['id'] ?>">
                <button type="submit" class="btn btn-ghost btn-small"><?= $p['published'] ? 'Nascondi' : 'Pubblica' ?></button>
              </form>
            <?php endif; ?>
            <?php if (can('blog.delete')): ?>
              <form method="post" onsubmit="return confirm('Spostare questo articolo negli eliminati? Da lì si può recuperare.');">
                <?= csrf_field() ?>
                <input type="hidden" name="action" value="blog_delete">
                <input type="hidden" name="id" value="<?= $p['id'] ?>">
                <button type="submit" class="btn btn-danger btn-small">Elimina</button>
              </form>
            <?php endif; ?>
          </div>
        </div>
      <?php endforeach; ?>
    </div>
    <?php

// ---------------------------------------------------------------------
// BLOG — articoli eliminati (il cestino)
// ---------------------------------------------------------------------
} elseif ($section === 'blog_cestino') {
    $eliminati = db()->query('SELECT b.*, u.mc_username FROM blog_posts b
                              LEFT JOIN users u ON u.id = b.author_user_id
                              WHERE b.deleted_at IS NOT NULL
                              ORDER BY b.deleted_at DESC')->fetchAll();
    ?>
    <div class="barra-sezione">
      <h2>Articoli eliminati</h2>
      <a href="/manage?section=blog" class="btn btn-ghost btn-small">← Torna agli articoli</a>
    </div>
    <div class="panel pannello-predefinito">
      <p class="sub" style="margin:0;">
        Qui finiscono gli articoli eliminati dal blog: non sono sul sito e non li vede nessuno,
        ma sono ancora tutti interi. <strong>Recupera</strong> li rimette fra gli articoli come
        <em>bozza</em>, cosí puoi rileggerli prima di ripubblicarli.
        <?php if (is_admin()): ?>
          <strong>Elimina per sempre</strong> invece cancella davvero, e da lì non si torna indietro.
        <?php endif; ?>
      </p>
    </div>
    <div>
      <?php if (!$eliminati): ?>
        <div class="panel"><p>Il cestino è vuoto: nessun articolo eliminato.</p></div>
      <?php endif; ?>
      <?php foreach ($eliminati as $p): ?>
        <div class="manage-row">
          <div>
            <div class="title"><?= h($p['title']) ?></div>
            <div class="sub">
              eliminato <?= time_ago($p['deleted_at']) ?>
              <?= $p['mc_username'] ? ' · scritto da ' . h($p['mc_username']) : '' ?>
              · pubblicato in origine il <?= date('d/m/Y', strtotime($p['created_at'])) ?>
            </div>
            <?php /* Due righe del testo: bastano a riconoscere quale articolo era, senza
                     doverlo per forza recuperare per andare a vedere. */ ?>
            <div class="sub" style="opacity:.75; margin-top:4px;"><?= h(seo_riassunto($p['body'], 120)) ?></div>
          </div>
          <div class="actions">
            <form method="post">
              <?= csrf_field() ?>
              <input type="hidden" name="action" value="blog_restore">
              <input type="hidden" name="id" value="<?= $p['id'] ?>">
              <button type="submit" class="btn btn-green btn-small">Recupera</button>
            </form>
            <?php if (is_admin()): ?>
              <form method="post" onsubmit="return confirm('Cancellare per sempre «<?= h(addslashes($p['title'])) ?>»? Questa non si può annullare.');">
                <?= csrf_field() ?>
                <input type="hidden" name="action" value="blog_purge">
                <input type="hidden" name="id" value="<?= $p['id'] ?>">
                <button type="submit" class="btn btn-danger btn-small">Elimina per sempre</button>
              </form>
            <?php endif; ?>
          </div>
        </div>
      <?php endforeach; ?>
    </div>
    <?php

// ---------------------------------------------------------------------
// BLOG — modifica
// ---------------------------------------------------------------------
} elseif ($section === 'blog_edit') {
    $id = (int)($_GET['id'] ?? 0);
    $stmt = db()->prepare('SELECT * FROM blog_posts WHERE id = ? AND deleted_at IS NULL');
    $stmt->execute([$id]);
    $post = $stmt->fetch();
    if (!$post) {
        echo '<div class="panel"><p>Articolo non trovato.</p><a href="/manage?section=blog">← Torna al blog</a></div>';
    } else {
        ?>
        <a href="/manage?section=blog">← Torna alla lista</a>
        <div class="panel" style="margin-top:14px;">
          <form method="post" class="stack">
            <?= csrf_field() ?>
            <input type="hidden" name="action" value="blog_save">
            <input type="hidden" name="id" value="<?= $post['id'] ?>">
            <div>
              <label for="title">Titolo</label>
              <input type="text" id="title" name="title" value="<?= h($post['title']) ?>">
            </div>
            <div>
              <label for="subtitle">Sottotitolo (facoltativo)</label>
              <input type="text" id="subtitle" name="subtitle" maxlength="255" value="<?= h($post['subtitle'] ?? '') ?>">
              <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Compare <strong>a capo, sotto il titolo</strong>. È anche la frase che Google mostra come descrizione dell'articolo.</p>
            </div>
            <?php campo_immagine('cover_image', 'cover_image', (string) ($post['cover_image'] ?? ''),
                'Immagine di presentazione (opzionale)',
                'Incolla un indirizzo oppure carica un file dal computer con <strong>Scegli</strong>.'); ?>
            <?php /* Anteprima delle due inquadrature (telefono e computer): si trascina
                     l'immagine col mouse per scegliere quale parte resta in vista. Il
                     punto scelto finisce nel campo nascosto qui sotto. */ ?>
            <div class="inquadratura" data-inquadratura
                 data-src="<?= h($post['cover_image'] ?? '') ?>"
                 <?= empty($post['cover_image']) ? 'hidden' : '' ?>>
              <label>Inquadratura della copertina</label>
              <p class="sub" style="margin:-2px 0 10px;">Nelle tessere della home l&rsquo;immagine viene ritagliata, e i due formati tagliano in modo diverso: <strong>trascinale una per una</strong> per scegliere cosa tenere in vista. Sono indipendenti.</p>
              <div class="inquadratura-riquadri">
                <figure class="inquadratura-box e-telefono">
                  <div class="inquadratura-tela" data-tela="telefono" data-campo="cover_position"></div>
                  <figcaption>Telefono</figcaption>
                </figure>
                <figure class="inquadratura-box e-computer">
                  <div class="inquadratura-tela" data-tela="computer" data-campo="cover_position_pc"></div>
                  <figcaption>Computer</figcaption>
                </figure>
                <button type="button" class="btn btn-ghost btn-small" data-centra>Rimetti al centro</button>
              </div>
              <input type="hidden" name="cover_position" value="<?= h($post['cover_position'] ?? '50% 50%') ?>">
              <input type="hidden" name="cover_position_pc" value="<?= h($post['cover_position_pc'] ?? '50% 50%') ?>">
            </div>
            <div>
              <label for="body">Testo</label>
              <?php require __DIR__ . '/../includes/editor_toolbar.php'; ?>
              <textarea id="body" name="body" rows="16"><?= h($post['body']) ?></textarea>
            </div>
            <div>
              <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
                <input type="checkbox" name="published" value="1" style="width:auto;" <?= $post['published'] ? 'checked' : '' ?>>
                Pubblicato
              </label>
            </div>
            <button type="submit" class="btn btn-accent">Salva modifiche</button>
          </form>
        </div>
        <?php
    }

// ---------------------------------------------------------------------
// PAGINE E MENU — le pagine del sito e la barra di navigazione che le collega
// ---------------------------------------------------------------------
} elseif ($section === 'pages') {
    $pages = db()->query('SELECT slug, title, updated_at FROM site_pages ORDER BY (slug = "regolamento") DESC, title')->fetchAll();
    ?>
    <h2 style="margin-top:0;">Pagine del sito</h2>
    <a href="/manage?section=page_edit" class="btn btn-green btn-small">+ Nuova pagina</a>
    <div style="margin-top:16px;">
      <?php foreach ($pages as $p): ?>
        <div class="manage-row">
          <div>
            <div class="title"><?= h($p['title']) ?> <?= $p['slug'] === 'regolamento' ? '<span class="badge-yes" title="Il testo si modifica, la pagina non si puo\' eliminare ne\' rinominare l\'indirizzo">non eliminabile</span>' : '' ?></div>
            <div class="sub">
              <?= $p['slug'] === 'regolamento' ? '/regolamento — il contenuto si modifica, la pagina resta' : '/pagina/' . h($p['slug']) ?>
            </div>
          </div>
          <div class="actions">
            <a href="<?= $p['slug'] === 'regolamento' ? '/regolamento' : '/pagina/' . urlencode($p['slug']) ?>" class="btn btn-ghost btn-small" target="_blank">Vedi</a>
            <a href="/manage?section=page_edit&slug=<?= urlencode($p['slug']) ?>" class="btn btn-accent btn-small">Modifica</a>
            <?php if ($p['slug'] !== 'regolamento'): ?>
              <form method="post" onsubmit="return confirm('Eliminare questa pagina?');">
                <?= csrf_field() ?>
                <input type="hidden" name="action" value="page_delete">
                <input type="hidden" name="slug" value="<?= h($p['slug']) ?>">
                <button type="submit" class="btn btn-danger btn-small">Elimina</button>
              </form>
            <?php endif; ?>
          </div>
        </div>

        <?php if ($p['slug'] === 'regolamento'): ?>
          <?php /* La Guida sta nell'elenco come le altre pagine, subito sotto il Regolamento:
                   per chi gestisce il sito e' una pagina come le altre. La differenza e' che i
                   capitoli non stanno nel database (li scrive il plugin): da qui si cambia il
                   testo di apertura. */ ?>
          <div class="manage-row">
            <div>
              <div class="title">Guida del server <span class="badge-yes" title="Il testo di apertura si modifica, i capitoli arrivano dal plugin">non eliminabile</span></div>
              <div class="sub">/tutorial — il testo di apertura si modifica, i capitoli arrivano dal server</div>
            </div>
            <div class="actions">
              <a href="/tutorial" class="btn btn-ghost btn-small" target="_blank">Vedi</a>
              <a href="/manage?section=guida_edit" class="btn btn-accent btn-small">Modifica</a>
            </div>
          </div>
        <?php endif; ?>
      <?php endforeach; ?>
    </div>

    <?php
    // --- Sezioni del sito -------------------------------------------------------------
    // Non sono pagine di testo (il loro contenuto e' generato), ma restano pagine che si
    // possono configurare: almeno la colonna laterale, e per alcune anche il resto. Si
    // ricavano dalle voci di menu, saltando quelle che puntano a pagine gia' elencate.
    $vociMenu = db()->query('SELECT * FROM nav_items ORDER BY sort_order, id')->fetchAll();

    // Dove si modifica ciascuna, quando c'e' qualcosa da modificare oltre alla colonna
    $doveSiModifica = [
        '/' => ['/manage?section=theme', 'testi e colori della home'],
        '/forum' => ['/manage?section=forum', 'categorie e discussioni'],
        '/store' => ['/manage?section=store', 'pacchetti, colonna e obiettivo'],
        '/tutorial' => ['/manage?section=guida_edit', 'testo di apertura'],
        '/regolamento' => ['/manage?section=page_edit&slug=regolamento', 'testo della pagina'],
    ];
    // Queste hanno gia' la loro riga qui sopra: non le ripeto
    $giaElencate = ['/regolamento', '/tutorial'];
    foreach ($pages as $pg) {
        $giaElencate[] = '/pagina/' . $pg['slug'];
    }
    ?>
    <h3 style="margin:26px 0 10px; font-size:15px; color:var(--text-dim);">Sezioni del sito</h3>
    <div style="margin-bottom:6px;">
      <?php foreach ($vociMenu as $voce): ?>
        <?php if (in_array($voce['url'], $giaElencate, true)) continue; ?>
        <?php $modifica = $doveSiModifica[$voce['url']] ?? null; ?>
        <div class="manage-row">
          <div>
            <div class="title">
              <?= h($voce['label']) ?>
              <span class="badge-yes" title="Il contenuto e' generato dal sito: qui si configura">sezione</span>
              <?= $voce['enabled'] ? '' : '<span class="badge-no">fuori dal menu</span>' ?>
            </div>
            <div class="sub">
              <?= h($voce['url']) ?> &middot; colonna laterale
              <strong><?= (int) $voce['show_sidebar'] === 1 ? 'attiva' : 'spenta' ?></strong>
              <?= $modifica ? ' &middot; ' . h($modifica[1]) : '' ?>
            </div>
          </div>
          <div class="actions">
            <a href="<?= h($voce['url']) ?>" class="btn btn-ghost btn-small" target="_blank">Vedi</a>
            <?php if ($modifica): ?>
              <a href="<?= h($modifica[0]) ?>" class="btn btn-accent btn-small">Modifica</a>
            <?php endif; ?>
            <form method="post">
              <?= csrf_field() ?>
              <input type="hidden" name="action" value="nav_toggle_sidebar">
              <input type="hidden" name="id" value="<?= (int) $voce['id'] ?>">
              <button type="submit" class="btn btn-ghost btn-small">
                <?= (int) $voce['show_sidebar'] === 1 ? 'Togli colonna' : 'Metti colonna' ?>
              </button>
            </form>
          </div>
        </div>
      <?php endforeach; ?>
    </div>

    <?php
    // --- Barra di navigazione: stessa scheda, perche' e' il posto da cui si collegano le pagine ---
    $items = db()->query('SELECT * FROM nav_items ORDER BY sort_order, id')->fetchAll();
    $editId = (int)($_GET['edit'] ?? 0);
    $editItem = null;
    if ($editId > 0) {
        $s = db()->prepare('SELECT * FROM nav_items WHERE id = ?');
        $s->execute([$editId]);
        $editItem = $s->fetch();
    }
    ?>
    <h2 id="menu" style="margin-top:34px;">Barra di navigazione</h2>
    <div class="panel">
      <p style="color:var(--text-dim); font-size:13px; margin:0;">
        Le voci del menu in alto, nell'ordine in cui compaiono. Per collegare una pagina creata qui sopra
        usa come URL <span class="code-box">/pagina/nome-pagina</span> (lo trovi sotto il titolo della pagina).
        La voce che punta a <span class="code-box">/store</span> viene disegnata come pulsante oro.
      </p>
    </div>
    <p class="sub" style="margin:14px 0 10px;">
      <strong>Trascina una voce</strong> per cambiare l&rsquo;ordine del menu: si salva da sola.
    </p>
    <div id="navSort" data-csrf="<?= h(csrf_token()) ?>">
    <?php foreach ($items as $it): ?>
      <div class="manage-row nav-sort-voce" data-id="<?= (int) $it['id'] ?>">
        <span class="nav-sort-presa" aria-hidden="true" title="Trascina per riordinare">&#8942;&#8942;</span>
        <div>
          <div class="title">
            <?= h($it['label']) ?>
            <?= $it['url'] === '/store' ? '<span class="badge-yes">pulsante</span>' : '' ?>
            <?= $it['enabled'] ? '' : '<span class="badge-no">disattivo</span>' ?>
          </div>
          <div class="sub"><?= h($it['url']) ?> · ordine <span data-ordine><?= (int)$it['sort_order'] ?></span></div>
        </div>
        <div class="actions">
          <a href="/manage?section=pages&edit=<?= $it['id'] ?>#menu" class="btn btn-accent btn-small">Modifica</a>
          <form method="post">
            <?= csrf_field() ?>
            <input type="hidden" name="action" value="nav_toggle_enabled">
            <input type="hidden" name="id" value="<?= $it['id'] ?>">
            <button type="submit" class="btn btn-ghost btn-small"><?= $it['enabled'] ? 'Disattiva' : 'Attiva' ?></button>
          </form>
          <form method="post" onsubmit="return confirm('Eliminare questa voce di menu?');">
            <?= csrf_field() ?>
            <input type="hidden" name="action" value="nav_delete">
            <input type="hidden" name="id" value="<?= $it['id'] ?>">
            <button type="submit" class="btn btn-danger btn-small">Elimina</button>
          </form>
        </div>
      </div>
    <?php endforeach; ?>
    </div>
    <p class="forum-sort-stato" id="navSortStato"></p>

    <div class="panel" style="margin-top:18px;">
      <h3 style="margin-top:0;"><?= $editItem ? 'Modifica voce' : 'Nuova voce di menu' ?></h3>
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="nav_save">
        <input type="hidden" name="id" value="<?= $editItem ? $editItem['id'] : 0 ?>">
        <div>
          <label for="label">Testo del link</label>
          <input type="text" id="label" name="label" value="<?= h($editItem['label'] ?? '') ?>">
        </div>
        <div>
          <label for="url">URL</label>
          <input type="text" id="url" name="url" placeholder="/classifiche" value="<?= h($editItem['url'] ?? '') ?>">
        </div>
        <div>
          <label for="nav_sort">Ordine (numero, crescente)</label>
          <input type="text" id="nav_sort" name="sort_order" value="<?= h((string)($editItem['sort_order'] ?? 0)) ?>">
        </div>
        <?php /* La spunta vale per la pagina della voce e per tutto quel che ci sta sotto:
                 spuntando Forum l'hanno anche le categorie e le discussioni. Home e Store
                 hanno una colonna loro, dentro la pagina, e non guardano questa spunta. */ ?>
        <label class="campo-check">
          <input type="checkbox" name="show_sidebar" value="1"<?= (int)($editItem['show_sidebar'] ?? 1) === 1 ? ' checked' : '' ?>>
          <span>Mostra la colonna laterale (chat, scheda giocatore, chi &egrave; sul sito)</span>
        </label>
        <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
          <button type="submit" class="btn btn-accent"><?= $editItem ? 'Salva voce' : 'Aggiungi voce' ?></button>
          <?php if ($editItem): ?>
            <a href="/manage?section=pages#menu" class="btn btn-ghost">Annulla</a>
          <?php endif; ?>
        </div>
      </form>
    </div>
    <?php

// ---------------------------------------------------------------------
// PAGINE — modifica/crea
// ---------------------------------------------------------------------
} elseif ($section === 'page_edit') {
    $slug = $_GET['slug'] ?? '';
    $page = ['title' => '', 'body' => ''];
    if ($slug !== '') {
        $stmt = db()->prepare('SELECT * FROM site_pages WHERE slug = ?');
        $stmt->execute([$slug]);
        $found = $stmt->fetch();
        if (!$found) {
            echo '<div class="panel"><p>Pagina non trovata.</p><a href="/manage?section=pages">← Torna alle pagine</a></div>';
            $slug = null;
        } else {
            $page = $found;
        }
    }
    if ($slug !== null) {
        ?>
        <a href="/manage?section=pages">← Torna alle pagine</a>
        <div class="panel" style="margin-top:14px;">
          <form method="post" class="stack">
            <?= csrf_field() ?>
            <input type="hidden" name="action" value="page_save">
            <input type="hidden" name="orig_slug" value="<?= h($slug) ?>">
            <div>
              <label for="title">Titolo</label>
              <input type="text" id="title" name="title" value="<?= h($page['title']) ?>">
            </div>
            <?php if ($slug !== ''): ?>
              <p style="color:var(--text-dim); font-size:13px;">URL: <?= $slug === 'regolamento' ? '/regolamento' : '/pagina/' . h($slug) ?> (non modificabile)</p>
            <?php endif; ?>
            <div>
              <label for="body">Testo</label>
              <?php require __DIR__ . '/../includes/editor_toolbar.php'; ?>
              <textarea id="body" name="body" rows="18"><?= h($page['body']) ?></textarea>
            </div>
            <label class="campo-check">
              <input type="checkbox" name="show_sidebar" value="1"<?= (int)($page['show_sidebar'] ?? 1) === 1 ? ' checked' : '' ?>>
              <span>Mostra la colonna laterale (chat, scheda giocatore, chi &egrave; sul sito)</span>
            </label>
            <button type="submit" class="btn btn-accent">Salva pagina</button>
          </form>
        </div>
        <?php
    }

// ---------------------------------------------------------------------
// GUIDA DEL SERVER — testo di apertura di /tutorial (i capitoli arrivano dal plugin)
// ---------------------------------------------------------------------
} elseif ($section === 'guida_edit') {
    $impGuida = site_settings();
    ?>
    <a href="/manage?section=pages">← Torna alle pagine</a>
    <div class="panel" style="margin-top:14px;">
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="guida_intro_save">
        <div>
          <label>Titolo</label>
          <?php /* Il titolo e' fisso: la pagina e' una sola e il menu la chiama "Guida". */ ?>
          <input type="text" value="Guida del server" disabled>
        </div>
        <p style="color:var(--text-dim); font-size:13px;">URL: /tutorial (non modificabile)</p>
        <div>
          <label for="tutorial_intro">Testo di apertura</label>
          <textarea id="tutorial_intro" name="tutorial_intro" rows="6" maxlength="600"
                    placeholder="<?= h(guide_intro()) ?>"><?= h($impGuida['tutorial_intro'] ?? '') ?></textarea>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            È il testo che apre la pagina, sopra la guida vera e propria. Lascia vuoto per
            rimettere quello predefinito (lo vedi in grigio qui sopra). Massimo 600 caratteri;
            gli a capo si vedono anche sulla pagina.
          </p>
        </div>
        <button type="submit" class="btn btn-accent">Salva pagina</button>
      </form>
    </div>
    <div class="panel">
      <h3 style="margin-top:0;">I capitoli non si modificano da qui</h3>
      <p style="margin:0; color:var(--text-dim); font-size:14px;">
        Fazioni, potenza, territori, mappa, elenco comandi: quel testo arriva dal plugin
        MagixFactions ed è lo stesso che i giocatori leggono in gioco. Si aggiorna da solo:
        appena il server riscrive la sua guida, il sito la ricopia entro pochi secondi.
        <a href="/assets/guida/magixfactions.html" target="_blank" rel="noopener">Vedi la guida a schermo intero →</a>
      </p>
    </div>
    <?php

// ---------------------------------------------------------------------
// FORUM — categorie + moderazione discussioni
// ---------------------------------------------------------------------
} elseif ($section === 'forum') {
    $cats = db()->query("
        SELECT c.*, (SELECT COUNT(*) FROM forum_topics t WHERE t.category_id = c.id) AS topic_count
        FROM forum_categories c ORDER BY c.sort_order, c.name
    ")->fetchAll();
    $editCatId = (int)($_GET['edit_cat'] ?? 0);
    $editCat = null;
    if ($editCatId > 0) {
        $s = db()->prepare('SELECT * FROM forum_categories WHERE id = ?');
        $s->execute([$editCatId]);
        $editCat = $s->fetch();
    }
    ?>
    <?php
    // Albero: prima le principali, e dentro ognuna le sue sotto-categorie.
    $principali = [];
    $figlie = [];
    foreach ($cats as $c) {
        if ($c['parent_id']) {
            $figlie[(int) $c['parent_id']][] = $c;
        } else {
            $principali[] = $c;
        }
    }
    // Una figlia il cui padre non esiste piu' resterebbe invisibile: la tratto da principale.
    $idPrincipali = array_map('intval', array_column($principali, 'id'));
    foreach ($figlie as $padre => $elenco) {
        if (!in_array((int) $padre, $idPrincipali, true)) {
            $principali = array_merge($principali, $elenco);
            unset($figlie[$padre]);
        }
    }

    /** Una riga dell'elenco, uguale per principali e sotto-categorie. */
    $categoryRow = function (array $c) use ($figlie): void { ?>
      <div class="riga-categoria">
        <span class="manina" title="Trascina per spostare" aria-hidden="true">&#10303;</span>
        <div class="riga-categoria-testo">
          <div class="title">
            <?php /* Pallino con la tinta vera della categoria: si riconosce a colpo d'occhio
                     quale colore avra' sul forum, scelto o automatico. */ ?>
            <span class="pallino-colore" style="background:<?= h($c['color'] ?: forum_tinta_hex((int) $c['id'])) ?>"
                  title="<?= $c['color'] ? 'colore scelto' : 'colore automatico' ?>"></span>
            <?= h($c['name']) ?>
          </div>
          <div class="sub">
            <?= h($c['description']) ?> &middot; <?= (int) $c['topic_count'] ?> discussioni<?php
              $quante = count($figlie[(int) $c['id']] ?? []);
              echo $quante ? ' &middot; ' . $quante . ' sotto-categorie' : '';
            ?>
          </div>
        </div>
        <div class="actions">
          <?php if (can('forum.category.edit')): ?>
            <a href="/manage?section=forum&edit_cat=<?= $c['id'] ?>" class="btn btn-accent btn-small">Modifica</a>
          <?php endif; ?>
          <?php if (can('forum.category.create')): ?>
            <?php /* Copia l'impianto (nome, descrizione, colore e sezioni), mai le discussioni. */ ?>
            <form method="post" onsubmit="return confirm('Creare una copia di questa categoria? Le discussioni NON vengono copiate.');">
              <?= csrf_field() ?>
              <input type="hidden" name="action" value="forum_cat_clone">
              <input type="hidden" name="id" value="<?= $c['id'] ?>">
              <button type="submit" class="btn btn-ghost btn-small">Clona</button>
            </form>
          <?php endif; ?>
          <?php if (can('forum.category.delete')): ?>
            <form method="post" onsubmit="return confirm('Eliminare questa categoria?');">
              <?= csrf_field() ?>
              <input type="hidden" name="action" value="forum_cat_delete">
              <input type="hidden" name="id" value="<?= $c['id'] ?>">
              <button type="submit" class="btn btn-danger btn-small">Elimina</button>
            </form>
          <?php endif; ?>
        </div>
      </div>
    <?php };
    ?>
    <?php $impForum = site_settings(); ?>
    <h2 id="aspetto-forum" style="margin-top:0;">Aspetto del forum</h2>
    <div class="panel">
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="forum_settings_save">
        <div>
          <label class="campo-check">
            <input type="checkbox" name="forum_ultime_enabled" value="1" style="width:auto;"
                   <?= ($impForum['forum_ultime_enabled'] ?? '1') === '1' ? 'checked' : '' ?>>
            Mostra il riquadro &ldquo;Ultime discussioni&rdquo; in cima al forum
          </label>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            &Egrave; la fascia con le discussioni pi&ugrave; recenti che sta sopra all&rsquo;elenco delle
            categorie. Spenta, il forum parte direttamente dalle categorie.
          </p>
        </div>
        <div>
          <label for="forum_ultime_quante">Quante discussioni mostrare</label>
          <input type="number" id="forum_ultime_quante" name="forum_ultime_quante" min="1" max="24"
                 style="max-width:120px;"
                 value="<?= h($impForum['forum_ultime_quante'] ?? '6') ?>">
        </div>
        <button type="submit" class="btn btn-accent">Salva aspetto del forum</button>
      </form>
    </div>

    <h2 style="margin-top:34px;">Categorie</h2>
    <p class="sub" style="margin:-6px 0 14px;">
      Trascina una categoria per cambiarne la posizione, oppure trascinala <strong>dentro</strong>
      un&rsquo;altra per farla diventare una sotto-categoria. L&rsquo;ordine si salva da solo.
    </p>
    <div class="forum-sort" id="forumSort" data-csrf="<?= h(csrf_token()) ?>">
      <?php foreach ($principali as $c): ?>
        <section class="forum-sort-gruppo" data-id="<?= (int) $c['id'] ?>" data-tipo="categoria">
          <?php $categoryRow($c); ?>
          <?php /* Anche vuota deve restare: e' la zona in cui si lascia cadere una figlia. */ ?>
          <div class="forum-sort-figlie">
            <?php foreach ($figlie[(int) $c['id']] ?? [] as $f): ?>
              <div class="forum-sort-figlia" data-id="<?= (int) $f['id'] ?>" data-tipo="figlia">
                <?php $categoryRow($f); ?>
              </div>
            <?php endforeach; ?>
          </div>
        </section>
      <?php endforeach; ?>
    </div>
    <p class="forum-sort-stato" id="forumSortStato" aria-live="polite"></p>

    <?php if (can($editCat ? 'forum.category.edit' : 'forum.category.create')): ?>
    <div class="panel" style="margin-top:18px;">
      <h3 style="margin-top:0;"><?= $editCat ? 'Modifica categoria' : 'Nuova categoria' ?></h3>
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="forum_cat_save">
        <input type="hidden" name="id" value="<?= $editCat ? $editCat['id'] : 0 ?>">
        <div>
          <label for="name">Nome</label>
          <input type="text" id="name" name="name" value="<?= h($editCat['name'] ?? '') ?>">
        </div>
        <div>
          <label for="description">Descrizione</label>
          <input type="text" id="description" name="description" value="<?= h($editCat['description'] ?? '') ?>">
        </div>
        <div>
          <label for="sort_order">Ordine (numero, crescente)</label>
          <input type="text" id="sort_order" name="sort_order" value="<?= h((string)($editCat['sort_order'] ?? 0)) ?>">
        </div>
        <?php
          // Categoria superiore: si scelgono solo quelle di PRIMO livello, cosi' l'albero
          // resta profondo uno. Una categoria che ha gia' delle figlie non puo' diventare
          // figlia a sua volta, altrimenti le sue sparirebbero dall'elenco.
          $haFiglie = $editCat && !empty($figlie[(int) $editCat['id']]);
        ?>
        <div>
          <label for="parent_id">Categoria superiore</label>
          <select id="parent_id" name="parent_id" <?= $haFiglie ? 'disabled' : '' ?>>
            <option value="0">&mdash; nessuna (categoria principale)</option>
            <?php foreach ($principali as $p): ?>
              <?php if ($editCat && (int) $p['id'] === (int) $editCat['id']) continue; ?>
              <option value="<?= (int) $p['id'] ?>" <?= $editCat && (int) $editCat['parent_id'] === (int) $p['id'] ? 'selected' : '' ?>><?= h($p['name']) ?></option>
            <?php endforeach; ?>
          </select>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            <?= $haFiglie
              ? 'Questa categoria ha gi&agrave; delle sotto-categorie, quindi non pu&ograve; entrare dentro un&rsquo;altra.'
              : 'Scegliendo una categoria superiore, questa diventa una sua sotto-categoria. Si pu&ograve; fare anche trascinandola nell&rsquo;elenco qui sopra.' ?>
          </p>
        </div>
        <?php
          // Colore: senza la spunta resta quello automatico. Il selettore parte comunque
          // dalla tinta che la categoria avrebbe da sola, cosi' si vede da dove si parte.
          // Per una categoria nuova l'id non esiste ancora: uso il prossimo della fila.
          $idPerTinta = (int) ($editCat['id'] ?? (count($cats) + 1));
          $coloreProprio = !empty($editCat['color']);
        ?>
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="color_custom" value="1" id="coloreProprio" style="width:auto;"
                   onchange="document.getElementById('color').disabled = !this.checked"
                   <?= $coloreProprio ? 'checked' : '' ?>>
            <strong>Scegli tu il colore</strong>
          </label>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 8px;">
            Senza la spunta la categoria prende da sola una delle tinte del sito e non cambia
            più. Il colore si vede sulla barretta a lato, sull'iniziale e sull'alone quando ci
            passi sopra col mouse.
          </p>
          <div class="tavolozze">
            <div class="tavolozza">
              <h4>Colore della categoria</h4>
              <input type="color" id="color" name="color" style="height:44px; padding:4px;"
                     value="<?= h($editCat['color'] ?? forum_tinta_hex($idPerTinta)) ?>"
                     <?= $coloreProprio ? '' : 'disabled' ?>>
            </div>
          </div>
        </div>
        <button type="submit" class="btn btn-accent"><?= $editCat ? 'Salva' : 'Crea categoria' ?></button>
      </form>
    </div>
    <?php endif; ?>

    <?php
    // Elenco COMPLETO, non piu' le ultime 50 e basta: si filtra per categoria, si cerca nel
    // titolo e si sfoglia. I filtri viaggiano nell'indirizzo, cosi' la pagina si puo'
    // mandare a qualcuno o tenere nei preferiti.
    $perPagina = 25;
    $paginaOra = max(1, (int) ($_GET['p'] ?? 1));
    $filtroCat = (int) ($_GET['cat'] ?? 0);
    $cerca = trim((string) ($_GET['q'] ?? ''));

    $dove = [];
    $valori = [];
    if ($filtroCat > 0) {
        $dove[] = 't.category_id = ?';
        $valori[] = $filtroCat;
    }
    if ($cerca !== '') {
        $dove[] = 't.title LIKE ?';
        $valori[] = '%' . $cerca . '%';
    }
    $condizione = $dove ? ' WHERE ' . implode(' AND ', $dove) : '';

    $conta = db()->prepare("SELECT COUNT(*) FROM forum_topics t $condizione");
    $conta->execute($valori);
    $totaleDiscussioni = (int) $conta->fetchColumn();
    $paginePossibili = max(1, (int) ceil($totaleDiscussioni / $perPagina));
    $paginaOra = min($paginaOra, $paginePossibili);

    $sql = db()->prepare("
        SELECT t.*, c.name AS cat_name, u.mc_username
        FROM forum_topics t
        JOIN forum_categories c ON c.id = t.category_id
        JOIN users u ON u.id = t.user_id
        $condizione
        ORDER BY t.last_post_at DESC
        LIMIT :quanti OFFSET :salta
    ");
    foreach ($valori as $i => $v) {
        $sql->bindValue($i + 1, $v);
    }
    $sql->bindValue(':quanti', $perPagina, PDO::PARAM_INT);
    $sql->bindValue(':salta', ($paginaOra - 1) * $perPagina, PDO::PARAM_INT);
    $sql->execute();
    $topics = $sql->fetchAll();

    /** Indirizzo di questa stessa vista cambiando un solo parametro. */
    $linkElenco = function (array $cambia = []) use ($filtroCat, $cerca, $paginaOra): string {
        $par = array_merge([
            'section' => 'forum',
            'cat' => $filtroCat ?: null,
            'q' => $cerca !== '' ? $cerca : null,
            'p' => $paginaOra > 1 ? $paginaOra : null,
        ], $cambia);
        $par = array_filter($par, fn($v) => $v !== null && $v !== '');
        return '/manage?' . http_build_query($par) . '#discussioni';
    };
    ?>
    <h2 id="discussioni">Discussioni</h2>
    <?php

    // Dove si puo' spostare una discussione: le categorie SENZA sezioni dentro (quelle che
    // ne hanno non ospitano discussioni) e tutte le sezioni. Le sezioni si mostrano come
    // "Categoria > Sezione", cosi' nel menu si capisce dove si sta mandando la discussione.
    $destinazioni = [];
    foreach ($cats as $c) {
        $haFiglieQui = !empty($figlie[(int) $c['id']]);
        if (!$c['parent_id'] && !$haFiglieQui) {
            $destinazioni[(int) $c['id']] = $c['name'];
        }
    }
    foreach ($figlie as $padreId => $elenco) {
        $nomePadre = '';
        foreach ($cats as $c) {
            if ((int) $c['id'] === (int) $padreId) { $nomePadre = $c['name']; break; }
        }
        foreach ($elenco as $f) {
            $destinazioni[(int) $f['id']] = ($nomePadre !== '' ? $nomePadre . ' › ' : '') . $f['name'];
        }
    }
    ?>
    <form method="get" class="filtri-discussioni">
      <input type="hidden" name="section" value="forum">
      <label class="solo-lettori" for="filtroCat">Categoria</label>
      <select id="filtroCat" name="cat">
        <option value="0">Tutte le categorie</option>
        <?php foreach ($destinazioni as $destId => $etichetta): ?>
          <option value="<?= (int) $destId ?>" <?= $filtroCat === (int) $destId ? 'selected' : '' ?>><?= h($etichetta) ?></option>
        <?php endforeach; ?>
      </select>
      <label class="solo-lettori" for="cercaTitolo">Cerca nel titolo</label>
      <input type="text" id="cercaTitolo" name="q" value="<?= h($cerca) ?>" placeholder="Cerca nel titolo…">
      <button type="submit" class="btn btn-ghost btn-small">Filtra</button>
      <?php if ($filtroCat || $cerca !== ''): ?>
        <a href="/manage?section=forum#discussioni" class="btn btn-ghost btn-small">Azzera</a>
      <?php endif; ?>
      <span class="filtri-conteggio">
        <?= $totaleDiscussioni ?> discussion<?= $totaleDiscussioni === 1 ? 'e' : 'i' ?><?php
          echo $totaleDiscussioni > $perPagina ? ' · pagina ' . $paginaOra . ' di ' . $paginePossibili : '';
        ?>
      </span>
    </form>
    <?php if (!$topics): ?>
      <div class="panel"><p><?= $filtroCat || $cerca !== '' ? 'Nessuna discussione con questi filtri.' : 'Nessuna discussione ancora.' ?></p></div>
    <?php endif; ?>
    <?php foreach ($topics as $t): ?>
      <div class="manage-row">
        <div>
          <div class="title">
            <?php if ($t['is_pinned']): ?><span class="pin">📌</span><?php endif; ?>
            <?php if ($t['is_locked']): ?><span class="lock">🔒</span><?php endif; ?>
            <?= h($t['title']) ?>
          </div>
          <div class="sub"><?= h($t['cat_name']) ?> · di <?= h($t['mc_username']) ?> · <?= time_ago($t['created_at']) ?></div>
        </div>
        <div class="actions">
          <a href="/forum/discussione/<?= $t['id'] ?>" class="btn btn-ghost btn-small">Vedi</a>
          <?php if (can('forum.topic.move') && count($destinazioni) > 1): ?>
            <?php /* Si sceglie la destinazione e si conferma: niente salvataggio al volo,
                     cosi' un tocco per sbaglio non sposta una discussione. */ ?>
            <form method="post" class="sposta-discussione">
              <?= csrf_field() ?>
              <input type="hidden" name="action" value="forum_topic_move">
              <input type="hidden" name="id" value="<?= $t['id'] ?>">
              <label class="solo-lettori" for="dove<?= (int) $t['id'] ?>">Sposta in</label>
              <select id="dove<?= (int) $t['id'] ?>" name="category_id">
                <?php foreach ($destinazioni as $destId => $etichetta): ?>
                  <option value="<?= (int) $destId ?>" <?= (int) $t['category_id'] === (int) $destId ? 'selected' : '' ?>><?= h($etichetta) ?></option>
                <?php endforeach; ?>
              </select>
              <button type="submit" class="btn btn-ghost btn-small">Sposta</button>
            </form>
          <?php endif; ?>
          <?php if (can('forum.topic.pin')): ?>
            <form method="post">
              <?= csrf_field() ?>
              <input type="hidden" name="action" value="forum_topic_toggle_pin">
              <input type="hidden" name="id" value="<?= $t['id'] ?>">
              <button type="submit" class="btn btn-ghost btn-small"><?= $t['is_pinned'] ? 'Rimuovi pin' : 'Metti in evidenza' ?></button>
            </form>
          <?php endif; ?>
          <?php if (can('forum.topic.lock')): ?>
            <form method="post">
              <?= csrf_field() ?>
              <input type="hidden" name="action" value="forum_topic_toggle_lock">
              <input type="hidden" name="id" value="<?= $t['id'] ?>">
              <button type="submit" class="btn btn-ghost btn-small"><?= $t['is_locked'] ? 'Sblocca' : 'Blocca' ?></button>
            </form>
          <?php endif; ?>
          <?php if (can('forum.topic.delete')): ?>
            <form method="post" onsubmit="return confirm('Eliminare questa discussione e tutte le risposte?');">
              <?= csrf_field() ?>
              <input type="hidden" name="action" value="forum_topic_delete">
              <input type="hidden" name="id" value="<?= $t['id'] ?>">
              <button type="submit" class="btn btn-danger btn-small">Elimina</button>
            </form>
          <?php endif; ?>
        </div>
      </div>
    <?php endforeach; ?>

    <?php if ($paginePossibili > 1): ?>
      <?php /* Sfogliare mantiene filtro e ricerca: l'indirizzo se li porta dietro. */ ?>
      <div class="pagination">
        <?php if ($paginaOra > 1): ?>
          <a href="<?= h($linkElenco(['p' => $paginaOra - 1 > 1 ? $paginaOra - 1 : null])) ?>">‹</a>
        <?php else: ?>
          <span class="disabled">‹</span>
        <?php endif; ?>

        <?php for ($n = 1; $n <= $paginePossibili; $n++): ?>
          <?php if ($n === $paginaOra): ?>
            <span class="active"><?= $n ?></span>
          <?php else: ?>
            <a href="<?= h($linkElenco(['p' => $n > 1 ? $n : null])) ?>"><?= $n ?></a>
          <?php endif; ?>
        <?php endfor; ?>

        <?php if ($paginaOra < $paginePossibili): ?>
          <a href="<?= h($linkElenco(['p' => $paginaOra + 1])) ?>">›</a>
        <?php else: ?>
          <span class="disabled">›</span>
        <?php endif; ?>
      </div>
    <?php endif; ?>
    <?php

// ---------------------------------------------------------------------
// ASPETTO (tema, testi della hero e banner VIP)
// ---------------------------------------------------------------------
} elseif ($section === 'theme') {
    $s = site_settings();
    ?>
    <div class="panel">
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="settings_save">
        <div>
          <label for="site_name">Nome del sito</label>
          <input type="text" id="site_name" name="site_name" value="<?= h($s['site_name'] ?? 'MAGICADVENTURE') ?>">
        </div>
        <?php campo_immagine('logo_url', 'logo_url', (string) ($s['logo_url'] ?? '/assets/img/logo.png'),
            'Logo del sito',
            'Compare in cima a ogni pagina. Incolla un indirizzo oppure carica un file con <strong>Scegli</strong>.'); ?>
        <?php campo_immagine('logo_small_url', 'logo_small_url', (string) ($s['logo_small_url'] ?? ''),
            'Logo piccolo (quadrato)',
            'Il marchio in versione ridotta. Compare <strong>accanto alla voce Home</strong> nella barra in alto, ed &egrave; anche la figura di scorta nelle anteprime dei link condivisi quando un articolo non ha una copertina propria. Consigliato quadrato, almeno 512&times;512. Vuoto = si usa il logo grande.'); ?>
        <?php /* La casella del velo non sta piu' qui: e' una per il blog e una per lo store,
                 e ognuna vive nel modulo del velo della sua sezione, accanto ai colori che
                 governa. Questo riquadro resta perche' e' il posto in cui la si cerca. */ ?>
        <div class="usato-da" id="veli" style="scroll-margin-top:96px; margin:4px 0 8px;">
          <strong>Velo sopra le copertine</strong> &mdash; la sfumatura fra l&rsquo;immagine e il testo.
          Si accende, si spegne e si regola separatamente nei due posti in cui compare:
          <a href="/manage?section=blog#velo">Blog</a> (le tessere degli articoli in home) e
          <a href="/manage?section=store#velo">Store</a> (le card dei pacchetti).
        </div>
        <label class="campo-check">
          <input type="checkbox" name="nav_logo_enabled" value="1"
                 <?= ($s['nav_logo_enabled'] ?? '1') === '1' ? 'checked' : '' ?>>
          Mostra il logo piccolo nella barra in alto, accanto alla voce Home
        </label>
        <div>
          <label for="nav_logo_size">Altezza del logo nella barra (pixel)</label>
          <input type="number" id="nav_logo_size" name="nav_logo_size" min="20" max="64" step="2"
                 value="<?= h($s['nav_logo_size'] ?? '44') ?>" style="max-width:120px;">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Da 20 a 64. La larghezza si adatta da sola alle proporzioni dell&rsquo;immagine. Oltre i 48 pixel la barra in alto comincia a crescere.</p>
        </div>
        <?php campo_immagine('favicon_url', 'favicon_url', (string) ($s['favicon_url'] ?? ''),
            'Icona del sito (favicon)',
            'La minuscola icona nella linguetta del browser, nei preferiti e nella schermata Home del telefono. Meglio un PNG quadrato 512&times;512 con un disegno semplice: a 16 pixel i dettagli spariscono. Vuoto = si usa il logo piccolo.'); ?>
        <div>
          <label for="meta_description">Descrizione per i motori di ricerca (meta description)</label>
          <textarea id="meta_description" name="meta_description" rows="3" maxlength="300"><?= h($s['meta_description'] ?? '') ?></textarea>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Il testo che Google mostra sotto il titolo nei risultati di ricerca. Consigliati 140-160 caratteri circa.</p>
        </div>
        <div>
          <label for="meta_title_home">Titolo della home per i motori di ricerca</label>
          <input type="text" id="meta_title_home" name="meta_title_home" maxlength="70"
                 value="<?= h($s['meta_title_home'] ?? '') ?>">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">La riga blu cliccabile su Google per la pagina iniziale. Meglio se contiene le parole che la gente cerca davvero (&ldquo;server Minecraft italiano fazioni&rdquo;) e sta sotto i 60 caratteri. Vuoto = solo il nome del sito.</p>
        </div>
        <div>
          <label for="google_site_verification">Codice di verifica di Google Search Console</label>
          <input type="text" id="google_site_verification" name="google_site_verification" maxlength="120"
                 value="<?= h($s['google_site_verification'] ?? '') ?>">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Serve a dimostrare a Google che il sito è nostro. In Search Console scegli <strong>Tag HTML</strong> e incolla qui solo il codice dopo <code>content=</code> (quello che comincia per <code>google-site-verification=</code> va bene lo stesso: la parte iniziale viene tolta).</p>
        </div>
        <?php campo_immagine('og_image', 'og_image', (string) ($s['og_image'] ?? ''),
            'Immagine per le anteprime dei link',
            'Quella che si vede quando qualcuno incolla un link del sito su Discord, WhatsApp o Telegram. Formato consigliato 1200&times;630. Vuoto = il logo.'); ?>
        <div>
          <label for="hero_slogan">Slogan sotto il logo</label>
          <textarea id="hero_slogan" name="hero_slogan" rows="2"><?= h($s['hero_slogan'] ?? '') ?></textarea>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Ogni riga va a capo così com'è scritta. Lascia vuoto per non mostrarlo.</p>
        </div>
        <div>
          <label for="hero_headline">Titolo grande a destra del logo</label>
          <textarea id="hero_headline" name="hero_headline" rows="3"><?= h($s['hero_headline'] ?? '') ?></textarea>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Una riga per riga. L'<strong>ultima</strong> riga viene colorata con il colore primario.</p>
        </div>
        <div>
          <label for="hero_sub">Testo sotto il titolo</label>
          <textarea id="hero_sub" name="hero_sub" rows="3"><?= h($s['hero_sub'] ?? '') ?></textarea>
        </div>
        <div>
          <?php $temaOra = $s['tema_predefinito'] ?? 'scuro'; ?>
          <label for="tema_predefinito">Tema di partenza</label>
          <select id="tema_predefinito" name="tema_predefinito">
            <option value="scuro" <?= $temaOra === 'scuro' ? 'selected' : '' ?>>Scuro</option>
            <option value="chiaro" <?= $temaOra === 'chiaro' ? 'selected' : '' ?>>Chiaro</option>
            <option value="auto" <?= $temaOra === 'auto' ? 'selected' : '' ?>>Automatico (segue il browser)</option>
          </select>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            Vale per chi arriva la prima volta. Ognuno poi se lo cambia dal pulsante ☾ nella
            barra in alto o dal proprio profilo, e quella scelta non scade più. Il colore qui sotto
            riguarda solo il tema scuro: sul chiaro il fondo è quello della tavolozza chiara.
          </p>
        </div>
        <?php /* Le due tavolozze, una accanto all'altra: gli stessi tre colori per ogni
                 tema, cosi' si vede subito che sono la stessa cosa in due versioni. */ ?>
        <div>
          <label>Tavolozza dei temi</label>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 10px;">
            Bastano tre colori per tema: <strong>sfondo</strong> della pagina, <strong>pannelli</strong>
            e <strong>testo</strong>. Bordi, fondi intermedi e testi tenui si ricavano da questi, e i
            testi tenui vengono scuriti (o schiariti) finché non superano la soglia di leggibilità:
            qualunque combinazione scegli, il testo resta leggibile.
          </p>
          <div class="tavolozze">
            <div class="tavolozza">
              <h4>☾ Tema scuro</h4>
              <label for="color_bg">Sfondo</label>
              <input type="color" id="color_bg" name="color_bg" value="<?= h($s['color_bg'] ?? '#0b0c0e') ?>">
              <label for="dark_panel">Pannelli</label>
              <input type="color" id="dark_panel" name="dark_panel" value="<?= h($s['dark_panel'] ?? '#17181b') ?>">
              <label for="dark_text">Testo</label>
              <input type="color" id="dark_text" name="dark_text" value="<?= h($s['dark_text'] ?? '#f0f0ee') ?>">
            </div>
            <div class="tavolozza">
              <h4>☀ Tema chiaro</h4>
              <label for="light_bg">Sfondo</label>
              <input type="color" id="light_bg" name="light_bg" value="<?= h($s['light_bg'] ?? '#f2f3f6') ?>">
              <label for="light_panel">Pannelli</label>
              <input type="color" id="light_panel" name="light_panel" value="<?= h($s['light_panel'] ?? '#ffffff') ?>">
              <label for="light_text">Testo</label>
              <input type="color" id="light_text" name="light_text" value="<?= h($s['light_text'] ?? '#14161a') ?>">
            </div>
          </div>
        </div>
        <?php $btnStile = ($s['btn_stile'] ?? 'contrasto') === 'accento' ? 'accento' : 'contrasto'; ?>
        <div>
          <label for="btn_stile">Stile dei pulsanti principali</label>
          <select id="btn_stile" name="btn_stile">
            <option value="contrasto" <?= $btnStile === 'contrasto' ? 'selected' : '' ?>>Bianco e nero (come l&rsquo;invio della chat)</option>
            <option value="accento" <?= $btnStile === 'accento' ? 'selected' : '' ?>>Colore d&rsquo;accento del sito</option>
          </select>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            Vale per tutti i pulsanti pieni del sito (&ldquo;Accedi&rdquo;, &ldquo;Salva&rdquo;, &ldquo;Vai&rdquo;&hellip;).
            <strong>Bianco e nero</strong> = bianco sul tema scuro e nero su quello chiaro, come il
            pulsante di invio della chat. <strong>Colore d&rsquo;accento</strong> = la sfumatura col
            colore primario scelto qui sotto, com&rsquo;era prima.
          </p>
        </div>
        <div>
          <label>Colori d&rsquo;accento</label>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 10px;">
            Le due tinte del sito: la prima e' quella dei collegamenti, dei pulsanti e della striscia
            in cima, la seconda accompagna. Il testo che ci finisce sopra (nero o bianco) si sceglie
            da solo in base al contrasto.
          </p>
          <div class="tavolozze">
            <div class="tavolozza">
              <h4>Primario</h4>
              <input type="color" id="color_purple" name="color_purple" value="<?= h($s['color_purple'] ?? '#c04ff0') ?>" style="height:44px; padding:4px;">
            </div>
            <div class="tavolozza">
              <h4>Secondario</h4>
              <input type="color" id="color_green" name="color_green" value="<?= h($s['color_green'] ?? '#a3e635') ?>" style="height:44px; padding:4px;">
            </div>
          </div>
        </div>
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="store_btn_border_anim" value="1" style="width:auto;" <?= ($s['store_btn_border_anim'] ?? '1') === '1' ? 'checked' : '' ?>>
            Bordo animato luccicante sul pulsante Store
          </label>
        </div>
        <button type="submit" class="btn btn-accent">Salva aspetto</button>
      </form>
    </div>

    <?php
    // Conto alla rovescia dell'apertura. La data salvata puo' avere lo spazio al posto
    // della T (se qualcuno l'ha scritta a mano nel database): il campo del browser
    // accetta solo la forma con la T.
    $cdTarget = substr(str_replace(' ', 'T', trim((string) ($s['countdown_target'] ?? ''))), 0, 16);
    $cdStart = substr(str_replace(' ', 'T', trim((string) ($s['countdown_start'] ?? ''))), 0, 16);
    ?>
    <h2 id="countdown" style="margin-top:34px;">Conto alla rovescia</h2>
    <p class="sub" style="margin-bottom:14px;">
      Il <strong>portale</strong> in home, sotto al logo: quanto manca all&rsquo;apertura del server al
      pubblico. Pi&ugrave; la data si avvicina, pi&ugrave; il portale si accende. Passata la data e
      l&rsquo;ora, al posto dell&rsquo;orologio compare il messaggio di apertura.
    </p>
    <div class="panel">
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="countdown_save">
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="enabled" value="1" style="width:auto;" <?= ($s['countdown_enabled'] ?? '0') === '1' ? 'checked' : '' ?>>
            Mostra il conto alla rovescia in home
          </label>
        </div>
        <div>
          <label for="cd_target">Data e ora dell&rsquo;apertura</label>
          <input type="datetime-local" id="cd_target" name="target" value="<?= h($cdTarget) ?>" style="max-width:260px;">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            <strong>Ora italiana.</strong> Senza data la fascia non compare, anche se la spunta qui sopra &egrave; accesa.
          </p>
        </div>
        <div>
          <label for="cd_start">Inizio dello sviluppo <span style="text-transform:none; color:var(--text-dim);">(facoltativo)</span></label>
          <input type="datetime-local" id="cd_start" name="start" value="<?= h($cdStart) ?>" style="max-width:260px;">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            Il giorno in cui sono cominciati i lavori. &Egrave; da qui che parte la miccia: il tratto
            acceso dice quanta strada &egrave; stata fatta, e il portale si accende di pari passo.
            <strong>Vuoto = primo luglio.</strong>
          </p>
        </div>
        <div>
          <label for="cd_tag">Etichetta piccola</label>
          <input type="text" id="cd_tag" name="tag" maxlength="60" value="<?= h($s['countdown_tag'] ?? 'Apertura al pubblico') ?>">
        </div>
        <div>
          <label for="cd_title">Titolo (mentre si aspetta)</label>
          <input type="text" id="cd_title" name="title" maxlength="120" value="<?= h($s['countdown_title'] ?? 'Il server apre fra') ?>">
        </div>
        <div>
          <label for="cd_text">Testo sotto l&rsquo;orologio</label>
          <textarea id="cd_text" name="text" rows="2" maxlength="300"><?= h($s['countdown_text'] ?? '') ?></textarea>
        </div>
        <div>
          <label for="cd_done_title">Titolo dopo l&rsquo;apertura</label>
          <input type="text" id="cd_done_title" name="done_title" maxlength="120" value="<?= h($s['countdown_done_title'] ?? 'Il server è APERTO') ?>">
        </div>
        <div>
          <label for="cd_done_text">Testo dopo l&rsquo;apertura</label>
          <textarea id="cd_done_text" name="done_text" rows="2" maxlength="300"><?= h($s['countdown_done_text'] ?? 'Entra adesso e prenditi il tuo territorio.') ?></textarea>
        </div>
        <div>
          <label for="cd_piglin">Frase del piglin <span style="text-transform:none; color:var(--text-dim);">(vuoto = non esce nessuno)</span></label>
          <input type="text" id="cd_piglin" name="piglin_text" maxlength="60"
                 value="<?= h($s['countdown_piglin_text'] ?? 'Dai, vieni a dominare!') ?>">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            Ogni tanto un piglin zombificato caccia la testa fuori dal portale e dice questa frase
            in una nuvoletta. Tienila corta: sta tutta su una riga.
          </p>
        </div>
        <div>
          <label for="cd_button_text">Testo del pulsante <span style="text-transform:none; color:var(--text-dim);">(vuoto = nessun pulsante)</span></label>
          <input type="text" id="cd_button_text" name="button_text" maxlength="60" value="<?= h($s['countdown_button_text'] ?? '') ?>">
        </div>
        <div>
          <label for="cd_button_url">Indirizzo del pulsante</label>
          <input type="text" id="cd_button_url" name="button_url" value="<?= h($s['countdown_button_url'] ?? '') ?>">
        </div>
        <div>
          <label for="cd_color">Colore del portale</label>
          <input type="color" id="cd_color" name="color" value="<?= h($s['countdown_color'] ?? '#c04ff0') ?>" style="max-width:80px; padding:2px;">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Il vortice dentro l&rsquo;ossidiana, il suo alone e le scintille. Il viola &egrave; quello del Nether.</p>
        </div>
        <div>
          <label for="cd_fuse_color">Colore della miccia</label>
          <input type="color" id="cd_fuse_color" name="fuse_color" value="<?= h($s['countdown_fuse_color'] ?? '#c04ff0') ?>" style="max-width:80px; padding:2px;">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Il tratto gi&agrave; percorso e la fiamma che corre davanti al giocatore.</p>
        </div>
        <div>
          <label for="cd_text_color">Colore del testo</label>
          <input type="color" id="cd_text_color" name="text_color" value="<?= h($s['countdown_text_color'] ?? '#a3e635') ?>" style="max-width:80px; padding:2px;">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">L&rsquo;etichetta, il numero dei giorni, i due punti dell&rsquo;orologio, l&rsquo;annuncio di apertura e il pulsante.</p>
        </div>
        <button type="submit" class="btn btn-accent">Salva conto alla rovescia</button>
      </form>
    </div>

    <h2 id="banner-vip" style="margin-top:34px;">Banner VIP</h2>
    <p class="sub" style="margin-bottom:14px;">Il riquadro promozionale dorato in cima alla home.</p>
    <div class="panel">
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="vip_banner_save">
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="enabled" value="1" style="width:auto;" <?= ($s['vip_banner_enabled'] ?? '1') === '1' ? 'checked' : '' ?>>
            Mostra il banner in home
          </label>
        </div>
        <div>
          <label for="icon">Icona (emoji)</label>
          <input type="text" id="icon" name="icon" maxlength="8" value="<?= h($s['vip_banner_icon'] ?? '👑') ?>">
        </div>
        <div>
          <label for="tag">Etichetta piccola</label>
          <input type="text" id="tag" name="tag" value="<?= h($s['vip_banner_tag'] ?? '') ?>">
        </div>
        <div>
          <label for="title">Titolo</label>
          <input type="text" id="title" name="title" value="<?= h($s['vip_banner_title'] ?? '') ?>">
        </div>
        <div>
          <label for="text">Testo descrittivo</label>
          <textarea id="text" name="text" rows="2"><?= h($s['vip_banner_text'] ?? '') ?></textarea>
        </div>
        <div>
          <label for="button_text">Testo del pulsante</label>
          <input type="text" id="button_text" name="button_text" value="<?= h($s['vip_banner_button_text'] ?? 'Scopri di più') ?>">
        </div>
        <?php
        // Il banner della home e' il "link promozione": di norma porta al pacchetto in
        // evidenza dello store, cosi' cambiando vetrina cambia da solo anche il banner.
        $seguiEvidenza = ($s['vip_banner_follow_featured'] ?? '1') === '1';
        $pkgEvidenza = store_pacchetto_evidenza();
        ?>
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="follow_featured" value="1" style="width:auto;" <?= $seguiEvidenza ? 'checked' : '' ?>>
            Il pulsante porta al pacchetto in evidenza dello store
          </label>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            <?php if ($pkgEvidenza): ?>
              Adesso punta a <strong><?= h($pkgEvidenza['name']) ?></strong>
              (<span class="code-box">/store#<?= h($pkgEvidenza['slug']) ?></span>).
              Si aggiorna da solo quando cambi la vetrina in <a href="/manage?section=store">Store</a>.
            <?php else: ?>
              Nessun pacchetto è in evidenza al momento: finché non ne spunti uno in
              <a href="/manage?section=store">Store</a>, il pulsante usa il link qui sotto.
            <?php endif; ?>
          </p>
        </div>
        <div>
          <label for="button_url">Link del pulsante<?= $seguiEvidenza && $pkgEvidenza ? ' (di riserva)' : '' ?></label>
          <input type="text" id="button_url" name="button_url" placeholder="https://..." value="<?= h($s['vip_banner_button_url'] ?? '#') ?>">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            Usato quando la spunta qui sopra è tolta, o quando nessun pacchetto è in evidenza.
          </p>
        </div>
        <div>
          <label for="color">Colore oro</label>
          <input type="color" id="color" name="color" value="<?= h($s['vip_banner_color'] ?? '#f0c75e') ?>" style="height:44px; padding:4px;">
        </div>
        <?php campo_immagine('image', 'image', (string) ($s['vip_banner_image'] ?? ''),
            'Immagine di sfondo (opzionale)',
            'Se impostata, il colore oro resta in sovrimpressione sopra l\'immagine (velo semi-trasparente). Lascia vuoto per lo sfondo dorato semplice.'); ?>
        <div>
          <label for="overlay_intensity">Intensità del velo dorato (<?= h($s['vip_banner_overlay_intensity'] ?? '85') ?>%)</label>
          <input type="range" id="overlay_intensity" name="overlay_intensity" min="0" max="100" value="<?= h($s['vip_banner_overlay_intensity'] ?? '85') ?>" oninput="this.previousElementSibling.textContent=this.previousElementSibling.textContent.replace(/\(\d+%\)/, '('+this.value+'%)')">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            0% = trasparente (si vede solo l'immagine), 100% = colore pieno. Ha effetto soprattutto
            quando è impostata un'immagine di sfondo. Qui, a differenza di blog e store, il velo
            <strong>copre il banner in modo uniforme</strong>: non c'è sfumatura, quindi 100% vuol
            dire davvero immagine nascosta del tutto.
          </p>
        </div>
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="border_anim" value="1" style="width:auto;" <?= ($s['vip_banner_border_anim'] ?? '1') === '1' ? 'checked' : '' ?>>
            Bordo animato luccicante sul banner
          </label>
        </div>
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="border_anim_mobile" value="1" style="width:auto;" <?= ($s['vip_banner_border_anim_mobile'] ?? '0') === '1' ? 'checked' : '' ?>>
            &hellip; anche su telefono
          </label>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px; margin-top:8px;">
            <input type="checkbox" name="glow_mobile" value="1" style="width:auto;" <?= ($s['vip_banner_glow_mobile'] ?? '0') === '1' ? 'checked' : '' ?>>
            Alone dorato sotto al banner anche su telefono
          </label>
          <p style="color:var(--text-dim); font-size:12px; margin:6px 0 0;">
            Sotto i 900px il banner diventa una fascia a tutta larghezza: li' il bordo luccicante e
            l&rsquo;alone dorato appesantiscono, quindi di serie restano spenti. Sul grande non cambia nulla.
          </p>
        </div>
        <button type="submit" class="btn btn-accent">Salva banner</button>
      </form>
    </div>

    <h2 id="chat-live" style="margin-top:34px;">Chat live</h2>
    <p class="sub" style="margin-bottom:14px;">Il riquadro di chat in home, sopra la scheda giocatore. È collegato alla chat del server: quello che si scrive qui compare in gioco e viceversa.</p>
    <div class="panel">
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="chat_settings_save">
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="chat_enabled" value="1" style="width:auto;" <?= ($s['chat_enabled'] ?? '1') === '1' ? 'checked' : '' ?>>
            Mostra la chat in home
          </label>
        </div>
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="chat_show_game" value="1" style="width:auto;" <?= ($s['chat_show_game'] ?? '1') === '1' ? 'checked' : '' ?>>
            Mostra anche i messaggi scritti in gioco
          </label>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Se la togli, sul sito restano solo i messaggi scritti dal sito — ma quelli continuano comunque ad arrivare in gioco.</p>
        </div>
        <div>
          <label for="chat_history">Messaggi mostrati all'apertura</label>
          <input type="number" id="chat_history" name="chat_history" min="5" max="100" value="<?= h($s['chat_history'] ?? '40') ?>">
        </div>
        <div>
          <label for="chat_slowmode">Attesa fra due messaggi (secondi)</label>
          <input type="number" id="chat_slowmode" name="chat_slowmode" min="0" max="120" value="<?= h($s['chat_slowmode'] ?? '3') ?>">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">0 = nessuna attesa. Oltre a questo, c'è comunque un tetto fisso di 12 messaggi al minuto per giocatore.</p>
        </div>
        <button type="submit" class="btn btn-accent">Salva chat</button>
      </form>
    </div>

    <p><a href="/" target="_blank">Vedi la home →</a></p>
    <?php

// ---------------------------------------------------------------------
// STORE — categorie e pacchetti, riordinabili trascinando (solo web-admin)
// ---------------------------------------------------------------------
} elseif ($section === 'store') {
    $cats = db()->query('SELECT * FROM store_categories ORDER BY sort_order, name')->fetchAll();
    $pkgs = db()->query('SELECT p.*, c.name AS cat_name FROM store_packages p
                         LEFT JOIN store_categories c ON c.id = p.category_id
                         ORDER BY p.sort_order, p.name')->fetchAll();
    $editCatId = (int) ($_GET['edit_cat'] ?? 0);
    $editCat = null;
    if ($editCatId > 0) {
        $q = db()->prepare('SELECT * FROM store_categories WHERE id = ?');
        $q->execute([$editCatId]);
        $editCat = $q->fetch();
    }

    // Chi sta usando i valori predefiniti: e' l'informazione che rende chiaro
    // cosa si sta per cambiare quando si tocca uno dei due pannelli generali.
    $ereditaVelo = [];
    $ereditaFiltro = [];
    foreach ($cats as $c) {
        if (empty($c['overlay_color'])) {
            $ereditaVelo[] = $c['name'];
        }
        if (empty($c['filter_active_color'])) {
            $ereditaFiltro[] = $c['name'];
        }
    }
    $elenco = fn(array $nomi): string => $nomi
        ? h(implode(', ', $nomi))
        : '<em>nessuna categoria: le hai personalizzate tutte</em>';

    $perCat = [];
    foreach ($pkgs as $p) {
        $perCat[(int) $p['category_id']][] = $p;
    }
    // Il gruppo finale (id 0) raccoglie i pacchetti senza categoria: non si trascina
    // come categoria, ma puo' ricevere e cedere pacchetti come tutti gli altri.
    $gruppi = $cats;
    $gruppi[] = ['id' => 0, 'name' => 'Senza categoria', 'enabled' => 1, 'sort_order' => 999, 'description' => null];
    ?>
    <div class="area-store">
    <p class="sub" style="margin-bottom:16px;">
      Trascina per riordinare: le <strong>categorie</strong> si spostano fra loro, i <strong>pacchetti</strong>
      si riordinano dentro una categoria e si trascinano da una categoria all'altra.
      Ogni spostamento viene salvato da solo.
    </p>
    <?php $imp = site_settings(); ?>
    <?php $g = $imp; ?>
    <h2 id="obiettivo" style="margin-top:34px;">Obiettivo del server</h2>
    <p class="sub" style="margin-bottom:14px;">
      La barra di avanzamento con quanto si è raccolto nel periodo in corso. Conta gli acquisti
      <strong>pagati</strong>; le consegne manuali seguono la stessa regola della colonna qui sopra.
    </p>
    <div class="panel">
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="goal_save">
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="goal_enabled" value="1" style="width:auto;" <?= ($g['goal_enabled'] ?? '0') === '1' ? 'checked' : '' ?>>
            <strong>Mostra l&rsquo;obiettivo nello store</strong>
          </label>
        </div>
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="goal_home" value="1" style="width:auto;" <?= ($g['goal_home'] ?? '0') === '1' ? 'checked' : '' ?>>
            Mostralo anche in home
          </label>
        </div>
        <div>
          <label for="goal_title">Titolo</label>
          <input type="text" id="goal_title" name="goal_title" maxlength="80"
                 value="<?= h($g['goal_title'] ?? 'Obiettivo del server') ?>">
        </div>
        <div>
          <label for="goal_text">Riga di spiegazione (facoltativa)</label>
          <input type="text" id="goal_text" name="goal_text" maxlength="140"
                 placeholder="es. serve a pagare il server e i backup"
                 value="<?= h($g['goal_text'] ?? '') ?>">
        </div>
        <div>
          <label for="goal_amount">Cifra da raggiungere (<?= h(site_setting('store_currency', 'EUR')) ?>)</label>
          <input type="text" id="goal_amount" name="goal_amount" style="max-width:160px;"
                 value="<?= h(number_format((float) ($g['goal_amount'] ?? 0), 2, ',', '')) ?>">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            A zero la sezione non compare, anche se la spunta qui sopra è messa.
          </p>
        </div>
        <div>
          <?php $periodoOra = $g['goal_period'] ?? 'mensile'; ?>
          <label for="goal_period">Ogni quanto riparte</label>
          <select id="goal_period" name="goal_period">
            <option value="settimanale" <?= $periodoOra === 'settimanale' ? 'selected' : '' ?>>Ogni settimana (dal lunedì)</option>
            <option value="mensile" <?= $periodoOra === 'mensile' ? 'selected' : '' ?>>Ogni mese (dal primo giorno)</option>
            <option value="annuale" <?= $periodoOra === 'annuale' ? 'selected' : '' ?>>Ogni anno (dal 1° gennaio)</option>
          </select>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            Il conteggio riparte da solo: si sommano gli acquisti dall&rsquo;inizio del periodo in corso.
          </p>
        </div>
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="goal_show_amount" value="1" style="width:auto;" <?= ($g['goal_show_amount'] ?? '1') === '1' ? 'checked' : '' ?>>
            Mostra gli importi (raccolto / obiettivo)
          </label>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            Togliendo la spunta resta solo la percentuale: la barra si vede, le cifre no.
          </p>
        </div>
        <button type="submit" class="btn btn-accent">Salva obiettivo</button>
      </form>
    </div>

    <div class="panel" id="layout" style="margin-bottom:18px; scroll-margin-top:96px;">
      <h3 style="margin-top:0;">Layout della vetrina</h3>
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="store_layout_save">
        <div>
          <label for="store_cols">Pacchetti per riga</label>
          <input type="number" id="store_cols" name="store_cols" min="2" max="6" style="max-width:120px;"
                 value="<?= h($imp['store_cols'] ?? '3') ?>">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            Quante card affiancare su computer e tablet (da 2 a 6). Sui telefoni le card restano
            piccole e ordinate a prescindere.
          </p>
        </div>
        <button type="submit" class="btn btn-green btn-small">Salva</button>
      </form>
    </div>

    <div class="panel pannello-predefinito" id="velo" style="margin-bottom:18px;">
      <h3 style="margin-top:0;">Velo sulle copertine <span class="tag-predefinito">valore predefinito</span></h3>
      <p class="sub" style="margin:-6px 0 12px;">
        Quello che cambi qui vale per <strong>tutte le categorie che non hanno un velo proprio</strong>
        e per i pacchetti senza categoria. Per differenziarne una sola, usa il riquadro
        "Velo solo per questa categoria" nel modulo in fondo alla pagina.
      </p>
      <p class="usato-da">In questo momento lo usano: <?= $elenco($ereditaVelo) ?></p>
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="store_settings_save">
        <label class="campo-check" style="margin:0 0 2px;">
          <input type="checkbox" name="card_overlay_store" value="1"
                 <?= ($imp['card_overlay_store'] ?? '1') === '1' ? 'checked' : '' ?>>
          Velo acceso sulle card dei pacchetti
        </label>
        <p style="color:var(--text-dim); font-size:12px; margin:0 0 10px;">
          Riguarda <strong>solo lo store</strong> (card e pagina di un pacchetto): gli articoli hanno
          il loro interruttore, in <a href="/manage?section=blog#velo">Blog</a>. Qui il velo &egrave; anche
          quello che tiene leggibile il <strong>prezzo</strong> scritto sopra l&rsquo;immagine.
        </p>
        <?= nota_interruttore_veli('store') ?>
        <?php
          // Come per gli articoli: il tema chiaro parte dai valori del tema scuro finche'
          // non lo si tocca, cosi' chi non entra qui non vede cambiare nulla.
          $vSC = $imp['store_overlay_color_chiaro'] ?? ($imp['store_overlay_color'] ?? '#0a0804');
          $vSI = $imp['store_overlay_intensity_chiaro'] ?? ($imp['store_overlay_intensity'] ?? '92');
          $vSS = $imp['store_overlay_stop_chiaro'] ?? ($imp['store_overlay_stop'] ?? '55');
          $vSB = $imp['store_border_color_chiaro'] ?? ($imp['store_border_color'] ?? '#f0c75e');
        ?>
        <p style="color:var(--text-dim); font-size:12px; margin:0 0 4px;"><strong>Intensit&agrave;</strong> &mdash; <?= help_overlay('intensita') ?></p>
        <p style="color:var(--text-dim); font-size:12px; margin:0 0 14px;"><strong>Altezza</strong> &mdash; <?= help_overlay('altezza') ?></p>
        <div class="tavolozze">
          <div class="tavolozza">
            <h4>&#9790; Tema scuro</h4>

            <p class="tavolozza-gruppo">Velo delle card</p>
            <label for="store_overlay_color">Colore</label>
            <input type="color" id="store_overlay_color" name="store_overlay_color" value="<?= h($imp['store_overlay_color'] ?? '#0a0804') ?>" style="height:44px; padding:4px;">
            <label for="store_overlay_intensity" style="display:block; margin-top:12px;">Intensit&agrave; (<?= h($imp['store_overlay_intensity'] ?? '92') ?>%)</label>
            <input type="range" id="store_overlay_intensity" name="store_overlay_intensity" min="0" max="100" step="1" value="<?= h($imp['store_overlay_intensity'] ?? '92') ?>" oninput="this.previousElementSibling.textContent=this.previousElementSibling.textContent.replace(/\(\d+%\)/, '('+this.value+'%)')">
            <label for="store_overlay_stop" style="display:block; margin-top:12px;">Altezza della sfumatura (<?= h($imp['store_overlay_stop'] ?? '55') ?>%)</label>
            <input type="range" id="store_overlay_stop" name="store_overlay_stop" min="20" max="100" step="5" value="<?= h($imp['store_overlay_stop'] ?? '55') ?>" oninput="this.previousElementSibling.textContent=this.previousElementSibling.textContent.replace(/\(\d+%\)/, '('+this.value+'%)')">

            <p class="tavolozza-gruppo">Barretta laterale</p>
            <input type="color" id="store_border_color" name="store_border_color" value="<?= h($imp['store_border_color'] ?? '#f0c75e') ?>" style="height:44px; padding:4px;">

            <?php $tsScuro = $imp['store_text_color'] ?? ''; ?>
            <p class="tavolozza-gruppo">Testo sopra il velo</p>
            <label class="campo-check">
              <input type="checkbox" name="store_text_custom" value="1" style="width:auto;" <?= is_valid_hex_color($tsScuro) ? 'checked' : '' ?>>
              Scelgo io il colore
            </label>
            <p style="color:var(--text-dim); font-size:12px; margin:4px 0 8px;">
              Senza la spunta il colore lo decide il velo: nero sui veli chiari, bianco su quelli
              scuri. Vale per titolo, elenco e <strong>prezzo</strong> delle card con copertina.
            </p>
            <input type="color" id="store_text_color" name="store_text_color" value="<?= h(is_valid_hex_color($tsScuro) ? $tsScuro : '#f2f2f0') ?>" style="height:44px; padding:4px;">

            <?php $prScuro = $imp['store_price_color'] ?? ''; ?>
            <p class="tavolozza-gruppo">Prezzo</p>
            <label class="campo-check">
              <input type="checkbox" name="store_price_custom" value="1" style="width:auto;" <?= is_valid_hex_color($prScuro) ? 'checked' : '' ?>>
              Colore a parte per il prezzo
            </label>
            <p style="color:var(--text-dim); font-size:12px; margin:4px 0 8px;">
              Senza la spunta il prezzo segue il colore del testo qui sopra.
            </p>
            <input type="color" id="store_price_color" name="store_price_color" value="<?= h(is_valid_hex_color($prScuro) ? $prScuro : '#f8e6b7') ?>" style="height:44px; padding:4px;">

            <?php $scColore = $imp['store_sconto_color'] ?? ''; ?>
            <p class="tavolozza-gruppo">Targhetta dello sconto (vale per i due temi)</p>
            <p style="color:var(--text-dim); font-size:12px; margin:0 0 8px;">
              Il &ldquo;-5%&rdquo; accanto al prezzo: stessa tinta sulle card dello store, sulla pagina
              del pacchetto e sul <strong>banner VIP</strong>. Il testo sopra (nero o bianco) si sceglie
              da solo in base al contrasto, quindi un colore basta per entrambi i temi.
            </p>
            <input type="color" id="store_sconto_color" name="store_sconto_color" value="<?= h(is_valid_hex_color($scColore) ? $scColore : ($imp['color_green'] ?? '#a3e635')) ?>" style="height:44px; padding:4px;">

            <p class="tavolozza-gruppo">Direzione (vale per i due temi)</p>
            <label for="store_overlay_direction">Card dei pacchetti</label>
            <select id="store_overlay_direction" name="store_overlay_direction">
              <option value="verticale" <?= ($imp['store_overlay_direction'] ?? 'verticale') !== 'orizzontale' ? 'selected' : '' ?>>Verticale (dal basso)</option>
              <option value="orizzontale" <?= ($imp['store_overlay_direction'] ?? 'verticale') === 'orizzontale' ? 'selected' : '' ?>>Orizzontale (da sinistra)</option>
            </select>
            <label for="store_featured_overlay_direction" style="display:block; margin-top:12px;">Vetrina in cima</label>
            <select id="store_featured_overlay_direction" name="store_featured_overlay_direction">
              <option value="verticale" <?= ($imp['store_featured_overlay_direction'] ?? 'orizzontale') !== 'orizzontale' ? 'selected' : '' ?>>Verticale (dal basso)</option>
              <option value="orizzontale" <?= ($imp['store_featured_overlay_direction'] ?? 'orizzontale') === 'orizzontale' ? 'selected' : '' ?>>Orizzontale (da sinistra)</option>
            </select>
          </div>

          <div class="tavolozza">
            <h4>&#9728; Tema chiaro</h4>

            <p class="tavolozza-gruppo">Velo delle card</p>
            <label for="store_overlay_color_chiaro">Colore</label>
            <input type="color" id="store_overlay_color_chiaro" name="store_overlay_color_chiaro" value="<?= h($vSC) ?>" style="height:44px; padding:4px;">
            <label for="store_overlay_intensity_chiaro" style="display:block; margin-top:12px;">Intensit&agrave; (<?= h($vSI) ?>%)</label>
            <input type="range" id="store_overlay_intensity_chiaro" name="store_overlay_intensity_chiaro" min="0" max="100" step="1" value="<?= h($vSI) ?>" oninput="this.previousElementSibling.textContent=this.previousElementSibling.textContent.replace(/\(\d+%\)/, '('+this.value+'%)')">
            <label for="store_overlay_stop_chiaro" style="display:block; margin-top:12px;">Altezza della sfumatura (<?= h($vSS) ?>%)</label>
            <input type="range" id="store_overlay_stop_chiaro" name="store_overlay_stop_chiaro" min="20" max="100" step="5" value="<?= h($vSS) ?>" oninput="this.previousElementSibling.textContent=this.previousElementSibling.textContent.replace(/\(\d+%\)/, '('+this.value+'%)')">

            <p class="tavolozza-gruppo">Barretta laterale</p>
            <input type="color" id="store_border_color_chiaro" name="store_border_color_chiaro" value="<?= h($vSB) ?>" style="height:44px; padding:4px;">

            <?php $tsChiaro = $imp['store_text_color_chiaro'] ?? ''; ?>
            <p class="tavolozza-gruppo">Testo sopra il velo</p>
            <label class="campo-check">
              <input type="checkbox" name="store_text_custom_chiaro" value="1" style="width:auto;" <?= is_valid_hex_color($tsChiaro) ? 'checked' : '' ?>>
              Scelgo io il colore
            </label>
            <p style="color:var(--text-dim); font-size:12px; margin:4px 0 8px;">
              Sul tema chiaro il velo e' spesso piu' tenue: se il testo automatico non ti convince,
              qui lo imposti a mano.
            </p>
            <input type="color" id="store_text_color_chiaro" name="store_text_color_chiaro" value="<?= h(is_valid_hex_color($tsChiaro) ? $tsChiaro : '#14161a') ?>" style="height:44px; padding:4px;">

            <?php $prChiaro = $imp['store_price_color_chiaro'] ?? ''; ?>
            <p class="tavolozza-gruppo">Prezzo</p>
            <label class="campo-check">
              <input type="checkbox" name="store_price_custom_chiaro" value="1" style="width:auto;" <?= is_valid_hex_color($prChiaro) ? 'checked' : '' ?>>
              Colore a parte per il prezzo
            </label>
            <p style="color:var(--text-dim); font-size:12px; margin:4px 0 8px;">
              Senza la spunta il prezzo segue il colore del testo qui sopra.
            </p>
            <input type="color" id="store_price_color_chiaro" name="store_price_color_chiaro" value="<?= h(is_valid_hex_color($prChiaro) ? $prChiaro : '#7a5c00') ?>" style="height:44px; padding:4px;">
          </div>
        </div>
        <button type="submit" class="btn btn-accent">Salva velo</button>
      </form>
    </div>

    <div class="panel pannello-predefinito" id="filtri" style="margin-bottom:18px;">
      <h3 style="margin-top:0;">Colori dei filtri <span class="tag-predefinito">valore predefinito</span></h3>
      <p class="sub" style="margin:-6px 0 12px;">
        I pulsanti sopra la griglia dello store, per le categorie che non hanno colori propri
        (e per il pulsante "Tutto", che non appartiene a nessuna categoria).
        Il colore del testo si calcola da solo in base a quanto è chiaro lo sfondo scelto.
      </p>
      <p class="usato-da">In questo momento li usano: <?= $elenco($ereditaFiltro) ?></p>
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="store_filters_save">
        <div class="tavolozze">
          <div class="tavolozza">
            <h4>Selezionato</h4>
            <input type="color" id="store_filter_active_color" name="store_filter_active_color" value="<?= h($imp['store_filter_active_color'] ?? '#f0c75e') ?>" style="height:44px; padding:4px;">
          </div>
          <div class="tavolozza">
            <h4>A riposo</h4>
            <input type="color" id="store_filter_idle_color" name="store_filter_idle_color" value="<?= h($imp['store_filter_idle_color'] ?? '#17181b') ?>" style="height:44px; padding:4px;">
          </div>
        </div>
        <button type="submit" class="btn btn-accent">Salva filtri</button>
      </form>
    </div>

    <div class="panel pannello-predefinito" id="sconti" style="margin-bottom:18px;">
      <h3 style="margin-top:0;">Sconto su tutto lo store <span class="tag-predefinito">valore predefinito</span></h3>
      <p class="sub" style="margin:-6px 0 12px;">
        Si applica a ogni pacchetto che non ha uno sconto proprio e la cui categoria non ne ha uno.
        L'ordine di precedenza è: <strong>pacchetto → categoria → store</strong>.
      </p>
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="store_sconto_save">
        <?php
          $scStoreTipo = ($imp['store_discount_value'] ?? '0') > 0 ? ($imp['store_discount_type'] ?? 'percentuale') : '';
          $scStoreValore = (float) ($imp['store_discount_value'] ?? 0);
        ?>
        <div>
          <label for="store_sconto_tipo">Sconto</label>
          <select id="store_sconto_tipo" name="discount_type">
            <option value="" <?= $scStoreTipo === '' ? 'selected' : '' ?>>Nessuno sconto</option>
            <option value="percentuale" <?= $scStoreTipo === 'percentuale' ? 'selected' : '' ?>>Percentuale (%)</option>
            <option value="importo" <?= $scStoreTipo === 'importo' ? 'selected' : '' ?>>Importo fisso (<?= h($imp['store_currency'] ?? 'EUR') ?>)</option>
          </select>
        </div>
        <div>
          <label for="store_sconto_valore">Valore dello sconto</label>
          <input type="text" id="store_sconto_valore" name="discount_value" value="<?= h($scStoreValore > 0 ? number_format($scStoreValore, 2, ',', '') : '') ?>" placeholder="es. 10">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Con la percentuale scrivi solo il numero (10 = 10%). Con l'importo fisso, quanto togliere dal prezzo.</p>
        </div>
        <button type="submit" class="btn btn-accent">Salva sconto</button>
      </form>
    </div>

    <div class="panel pannello-predefinito" id="sidebar" style="margin-bottom:18px;">
      <h3 style="margin-top:0;">Colonna laterale <span class="tag-predefinito">ultimi acquisti</span></h3>
      <p class="sub" style="margin:-6px 0 12px;">
        La colonna a destra della vetrina: mostra chi ha comprato di recente e chi ha sostenuto
        di più il server, con la skin del giocatore. Contano solo gli ordini <strong>pagati</strong>.
      </p>
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="store_sidebar_save">
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="enabled" value="1" style="width:auto;" <?= ($imp['store_sidebar_enabled'] ?? '1') === '1' ? 'checked' : '' ?>>
            Mostra la colonna laterale
          </label>
        </div>
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="show_amount" value="1" style="width:auto;" <?= ($imp['store_sidebar_show_amount'] ?? '1') === '1' ? 'checked' : '' ?>>
            Mostra gli importi
          </label>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Se la togli restano nomi, pacchetti e classifica, ma senza cifre.</p>
        </div>
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="show_name" value="1" style="width:auto;" <?= ($imp['store_sidebar_show_name'] ?? '1') === '1' ? 'checked' : '' ?>>
            Mostra il nickname
          </label>
        </div>
        <div style="margin-left:26px;">
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="show_rank" value="1" style="width:auto;" <?= ($imp['store_sidebar_show_rank'] ?? '1') === '1' ? 'checked' : '' ?>>
            …e anche il grado
          </label>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Senza questa spunta resta il solo nickname, senza tag colorato. Vale solo se il nickname è mostrato.</p>
        </div>
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="show_package" value="1" style="width:auto;" <?= ($imp['store_sidebar_show_package'] ?? '1') === '1' ? 'checked' : '' ?>>
            Mostra il nome del pacchetto
          </label>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Riguarda l'elenco degli ultimi acquisti: se la togli resta solo quanto tempo fa.</p>
        </div>
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="show_date" value="1" style="width:auto;" <?= ($imp['store_sidebar_show_date'] ?? '1') === '1' ? 'checked' : '' ?>>
            Mostra quando è stato acquistato
          </label>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Il «2 h fa» accanto al pacchetto negli ultimi acquisti.</p>
        </div>
        <div>
          <label for="recent_title">Titolo del riquadro acquisti</label>
          <input type="text" id="recent_title" name="recent_title" maxlength="60" value="<?= h($imp['store_sidebar_recent_title'] ?? 'Ultimi acquisti') ?>">
        </div>
        <div>
          <label for="recent_count">Quanti acquisti mostrare</label>
          <input type="number" id="recent_count" name="recent_count" min="0" max="20" value="<?= h($imp['store_sidebar_recent_count'] ?? '5') ?>">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">0 = nascondi del tutto il riquadro degli acquisti.</p>
        </div>
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="top_enabled" value="1" style="width:auto;" <?= ($imp['store_sidebar_top_enabled'] ?? '1') === '1' ? 'checked' : '' ?>>
            Mostra il miglior sostenitore
          </label>
        </div>
        <div>
          <label for="top_title">Titolo del riquadro sostenitore</label>
          <input type="text" id="top_title" name="top_title" maxlength="60" value="<?= h($imp['store_sidebar_top_title'] ?? 'Miglior sostenitore') ?>">
        </div>
        <div>
          <label for="top_days">Periodo della classifica (giorni)</label>
          <input type="number" id="top_days" name="top_days" min="0" max="3650" value="<?= h($imp['store_sidebar_top_days'] ?? '0') ?>">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">0 = da sempre. Con 30 vince chi ha speso di più nell'ultimo mese.</p>
        </div>
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="include_manual" value="1" style="width:auto;" <?= ($imp['store_sidebar_include_manual'] ?? '0') === '1' ? 'checked' : '' ?>>
            Conta anche le consegne manuali
          </label>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Le consegne fatte da qui (regali, prove, rimborsi) non sono incassi: di norma vanno lasciate fuori.</p>
        </div>
        <button type="submit" class="btn btn-accent">Salva colonna laterale</button>
      </form>
    </div>

    <div style="display:flex; align-items:center; gap:14px; margin-bottom:14px;">
      <a href="/manage?section=store_pkg_edit" class="btn btn-green btn-small">+ Nuovo pacchetto</a>
      <span class="store-sort-stato" id="storeSortStato"></span>
    </div>

    <div id="storeSort" data-csrf="<?= h(csrf_token()) ?>">
      <?php foreach ($gruppi as $c): $catId = (int) $c['id']; ?>
        <div class="store-sort-gruppo" data-id="<?= $catId ?>" data-tipo="<?= $catId > 0 ? 'categoria' : 'fisso' ?>">
          <div class="store-sort-titolo">
            <span class="store-drag" aria-hidden="true">⠿</span>
            <strong><?= h($c['name']) ?></strong>
            <?= $c['enabled'] ? '' : '<span class="badge-no">nascosta</span>' ?>
            <span class="sub"><?= count($perCat[$catId] ?? []) ?> pacchetti</span>
            <?php if (!empty($c['overlay_color'])): ?>
              <span class="store-pallino" title="Velo personalizzato" style="background:<?= h($c['overlay_color']) ?>"></span>
            <?php endif; ?>
            <?php if ($catId > 0): ?>
              <span class="store-sort-azioni">
                <a href="/manage?section=store&edit_cat=<?= $catId ?>#categoria" class="btn btn-ghost btn-small">Modifica</a>
                <form method="post" onsubmit="return confirm('Eliminare la categoria? I pacchetti restano, senza categoria.');">
                  <?= csrf_field() ?>
                  <input type="hidden" name="action" value="store_cat_delete">
                  <input type="hidden" name="id" value="<?= $catId ?>">
                  <button type="submit" class="btn btn-danger btn-small">Elimina</button>
                </form>
              </span>
            <?php endif; ?>
          </div>

          <div class="store-sort-pacchetti">
            <?php foreach ($perCat[$catId] ?? [] as $p): ?>
              <div class="store-sort-pacchetto" data-id="<?= (int) $p['id'] ?>" data-tipo="pacchetto">
                <span class="store-drag" aria-hidden="true">⠿</span>
                <div class="store-sort-nome">
                  <div class="title">
                    <?= h($p['name']) ?>
                    <?= $p['enabled'] ? '' : '<span class="badge-no">nascosto</span>' ?>
                    <?php if (!empty($p['featured'])): ?>
                      <span class="badge-yes" title="Vetrina dello store e destinazione del banner promozione in home">★ in evidenza</span>
                    <?php endif; ?>
                  </div>
                  <div class="sub">
                    <?= h(number_format((float) $p['price'], 2, ',', '.')) ?> <?= h(site_setting('store_currency', 'EUR')) ?> ·
                    <?= $p['commands'] ? count(array_filter(array_map('trim', explode("\n", $p['commands'])))) . ' comandi' : 'nessun comando' ?>
                  </div>
                </div>
                <div class="actions">
                  <a href="/manage?section=store_pkg_edit&id=<?= (int) $p['id'] ?>" class="btn btn-accent btn-small">Modifica</a>
                  <form method="post">
                    <?= csrf_field() ?>
                    <input type="hidden" name="action" value="store_pkg_clone">
                    <input type="hidden" name="id" value="<?= (int) $p['id'] ?>">
                    <button type="submit" class="btn btn-ghost btn-small" title="Crea una copia nascosta e aprila">Clona</button>
                  </form>
                  <form method="post" onsubmit="return confirm('Consegnare subito questo pacchetto al TUO account, senza pagamento?');">
                    <?= csrf_field() ?>
                    <input type="hidden" name="action" value="store_pkg_deliver">
                    <input type="hidden" name="id" value="<?= (int) $p['id'] ?>">
                    <button type="submit" class="btn btn-ghost btn-small" title="Esegue in gioco i comandi del pacchetto sul tuo account, senza passare da PayPal">Prova consegna</button>
                  </form>
                  <form method="post">
                    <?= csrf_field() ?>
                    <input type="hidden" name="action" value="store_pkg_toggle">
                    <input type="hidden" name="id" value="<?= (int) $p['id'] ?>">
                    <button type="submit" class="btn btn-ghost btn-small"><?= $p['enabled'] ? 'Nascondi' : 'Mostra' ?></button>
                  </form>
                  <form method="post" onsubmit="return confirm('Eliminare definitivamente questo pacchetto?');">
                    <?= csrf_field() ?>
                    <input type="hidden" name="action" value="store_pkg_delete">
                    <input type="hidden" name="id" value="<?= (int) $p['id'] ?>">
                    <button type="submit" class="btn btn-danger btn-small">Elimina</button>
                  </form>
                </div>
              </div>
            <?php endforeach; ?>
            <?php if (empty($perCat[$catId])): ?>
              <div class="store-sort-vuoto">Trascina qui un pacchetto</div>
            <?php endif; ?>
          </div>
        </div>
      <?php endforeach; ?>
    </div>

    <div class="panel" style="margin-top:22px;">
      <h3 style="margin-top:0;" id="categoria">
        <?= $editCat ? 'Modifica categoria: ' . h($editCat['name']) : 'Nuova categoria' ?>
      </h3>
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="store_cat_save">
        <input type="hidden" name="id" value="<?= $editCat ? (int) $editCat['id'] : 0 ?>">
        <div>
          <label for="cat_name">Nome</label>
          <input type="text" id="cat_name" name="name" value="<?= h($editCat['name'] ?? '') ?>">
        </div>
        <div>
          <label for="cat_desc">Descrizione</label>
          <input type="text" id="cat_desc" name="description" value="<?= h($editCat['description'] ?? '') ?>">
        </div>
        <input type="hidden" name="sort_order" value="<?= h((string) ($editCat['sort_order'] ?? 0)) ?>">
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="enabled" value="1" style="width:auto;" <?= ($editCat['enabled'] ?? 1) ? 'checked' : '' ?>>
            Visibile nello store
          </label>
        </div>

        <?php $veloProprio = !empty($editCat['overlay_color']); ?>
        <div class="velo-categoria">
          <div>
            <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
              <input type="checkbox" name="overlay_custom" value="1" style="width:auto;" id="overlayCustom"
                     onchange="document.getElementById('veloCampi').classList.toggle('is-spento', !this.checked)"
                     <?= $veloProprio ? 'checked' : '' ?>>
              <strong>Aspetto proprio per questa categoria</strong>
            </label>
            <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
              Senza la spunta questa categoria usa il velo predefinito. I campi qui sotto valgono
              <strong>solo per <?= $editCat ? h($editCat['name']) : 'questa categoria' ?></strong>,
              non per tutto lo store.
            </p>
          </div>
          <div id="veloCampi" class="<?= $veloProprio ? '' : 'is-spento' ?>">
        <?php
          // Il tema chiaro della categoria parte dai valori del tema scuro: le colonne
          // *_chiaro vuote significano "come il tema scuro", quindi qui si mostra quello.
          $cVC = $editCat['overlay_color_light'] ?? ($editCat['overlay_color'] ?? ($imp['store_overlay_color'] ?? '#0a0804'));
          $cVI = $editCat['overlay_intensity_light'] ?? ($editCat['overlay_intensity'] ?? ($imp['store_overlay_intensity'] ?? '92'));
          $cVS = $editCat['overlay_stop_light'] ?? ($editCat['overlay_stop'] ?? ($imp['store_overlay_stop'] ?? '55'));
          $cVB = $editCat['border_color_light'] ?? ($editCat['border_color'] ?? ($imp['store_border_color'] ?? '#f0c75e'));
        ?>
        <p style="color:var(--text-dim); font-size:12px; margin:0 0 4px;"><strong>Intensit&agrave;</strong> &mdash; <?= help_overlay('intensita') ?></p>
        <p style="color:var(--text-dim); font-size:12px; margin:0 0 14px;"><strong>Altezza</strong> &mdash; <?= help_overlay('altezza') ?></p>
        <div class="tavolozze">
          <div class="tavolozza">
            <h4>&#9790; Tema scuro</h4>

            <p class="tavolozza-gruppo">Velo della categoria</p>
            <label for="overlay_color">Colore</label>
            <input type="color" id="overlay_color" name="overlay_color" value="<?= h($editCat['overlay_color'] ?? ($imp['store_overlay_color'] ?? '#0a0804')) ?>" style="height:44px; padding:4px;">
            <label for="overlay_intensity" style="display:block; margin-top:12px;">Intensit&agrave; (<?= h((string) ($editCat['overlay_intensity'] ?? ($imp['store_overlay_intensity'] ?? '92'))) ?>%)</label>
            <input type="range" id="overlay_intensity" name="overlay_intensity" min="0" max="100" step="1" value="<?= h((string) ($editCat['overlay_intensity'] ?? ($imp['store_overlay_intensity'] ?? '92'))) ?>" oninput="this.previousElementSibling.textContent=this.previousElementSibling.textContent.replace(/\(\d+%\)/, '('+this.value+'%)')">
            <label for="overlay_stop" style="display:block; margin-top:12px;">Altezza della sfumatura (<?= h((string) ($editCat['overlay_stop'] ?? ($imp['store_overlay_stop'] ?? '55'))) ?>%)</label>
            <input type="range" id="overlay_stop" name="overlay_stop" min="20" max="100" step="5" value="<?= h((string) ($editCat['overlay_stop'] ?? ($imp['store_overlay_stop'] ?? '55'))) ?>" oninput="this.previousElementSibling.textContent=this.previousElementSibling.textContent.replace(/\(\d+%\)/, '('+this.value+'%)')">

            <p class="tavolozza-gruppo">Barretta laterale</p>
            <input type="color" id="border_color" name="border_color" value="<?= h($editCat['border_color'] ?? ($imp['store_border_color'] ?? '#f0c75e')) ?>" style="height:44px; padding:4px;">

            <?php $cTS = $editCat['text_color'] ?? ''; ?>
            <p class="tavolozza-gruppo">Testo sopra il velo</p>
            <label class="campo-check">
              <input type="checkbox" name="text_custom" value="1" style="width:auto;" <?= is_valid_hex_color((string) $cTS) ? 'checked' : '' ?>>
              Colore solo per questa categoria
            </label>
            <p style="color:var(--text-dim); font-size:12px; margin:4px 0 8px;">
              Senza la spunta vale la scelta generale dello store e, se manca anche quella, il colore
              calcolato dal velo (nero sui veli chiari, bianco su quelli scuri).
            </p>
            <input type="color" id="text_color" name="text_color" value="<?= h(is_valid_hex_color((string) $cTS) ? (string) $cTS : ($imp['store_text_color'] ?? '#f2f2f0')) ?>" style="height:44px; padding:4px;">

            <?php $cPR = $editCat['price_color'] ?? ''; ?>
            <p class="tavolozza-gruppo">Prezzo</p>
            <label class="campo-check">
              <input type="checkbox" name="price_custom" value="1" style="width:auto;" <?= is_valid_hex_color((string) $cPR) ? 'checked' : '' ?>>
              Colore solo per questa categoria
            </label>
            <input type="color" id="price_color" name="price_color" value="<?= h(is_valid_hex_color((string) $cPR) ? (string) $cPR : ($imp['store_price_color'] ?? '#f8e6b7')) ?>" style="height:44px; padding:4px;">

            <?php $cSC = $editCat['discount_color'] ?? ''; ?>
            <p class="tavolozza-gruppo">Targhetta dello sconto (vale per i due temi)</p>
            <label class="campo-check">
              <input type="checkbox" name="sconto_custom" value="1" style="width:auto;" <?= is_valid_hex_color((string) $cSC) ? 'checked' : '' ?>>
              Colore solo per questa categoria
            </label>
            <p style="color:var(--text-dim); font-size:12px; margin:4px 0 8px;">
              Il testo dentro la targhetta (nero o bianco) si sceglie da solo in base al contrasto.
            </p>
            <input type="color" id="discount_color" name="discount_color" value="<?= h(is_valid_hex_color((string) $cSC) ? (string) $cSC : ($imp['store_sconto_color'] ?: ($imp['color_green'] ?? '#a3e635'))) ?>" style="height:44px; padding:4px;">

            <p class="tavolozza-gruppo">Direzione (vale per i due temi)</p>
            <select id="overlay_direction" name="overlay_direction">
              <option value="verticale" <?= ($editCat['overlay_direction'] ?? 'verticale') !== 'orizzontale' ? 'selected' : '' ?>>Verticale (dal basso)</option>
              <option value="orizzontale" <?= ($editCat['overlay_direction'] ?? '') === 'orizzontale' ? 'selected' : '' ?>>Orizzontale (da sinistra)</option>
            </select>
          </div>

          <div class="tavolozza">
            <h4>&#9728; Tema chiaro</h4>

            <p class="tavolozza-gruppo">Velo della categoria</p>
            <label for="overlay_color_light">Colore</label>
            <input type="color" id="overlay_color_light" name="overlay_color_light" value="<?= h((string) $cVC) ?>" style="height:44px; padding:4px;">
            <label for="overlay_intensity_light" style="display:block; margin-top:12px;">Intensit&agrave; (<?= h((string) $cVI) ?>%)</label>
            <input type="range" id="overlay_intensity_light" name="overlay_intensity_light" min="0" max="100" step="1" value="<?= h((string) $cVI) ?>" oninput="this.previousElementSibling.textContent=this.previousElementSibling.textContent.replace(/\(\d+%\)/, '('+this.value+'%)')">
            <label for="overlay_stop_light" style="display:block; margin-top:12px;">Altezza della sfumatura (<?= h((string) $cVS) ?>%)</label>
            <input type="range" id="overlay_stop_light" name="overlay_stop_light" min="20" max="100" step="5" value="<?= h((string) $cVS) ?>" oninput="this.previousElementSibling.textContent=this.previousElementSibling.textContent.replace(/\(\d+%\)/, '('+this.value+'%)')">

            <p class="tavolozza-gruppo">Barretta laterale</p>
            <input type="color" id="border_color_light" name="border_color_light" value="<?= h((string) $cVB) ?>" style="height:44px; padding:4px;">

            <?php $cTSc = $editCat['text_color_light'] ?? ''; ?>
            <p class="tavolozza-gruppo">Testo sopra il velo</p>
            <label class="campo-check">
              <input type="checkbox" name="text_custom_chiaro" value="1" style="width:auto;" <?= is_valid_hex_color((string) $cTSc) ? 'checked' : '' ?>>
              Colore solo per questa categoria
            </label>
            <input type="color" id="text_color_light" name="text_color_light" value="<?= h(is_valid_hex_color((string) $cTSc) ? (string) $cTSc : ($imp['store_text_color_chiaro'] ?? '#14161a')) ?>" style="height:44px; padding:4px;">

            <?php $cPRc = $editCat['price_color_light'] ?? ''; ?>
            <p class="tavolozza-gruppo">Prezzo</p>
            <label class="campo-check">
              <input type="checkbox" name="price_custom_chiaro" value="1" style="width:auto;" <?= is_valid_hex_color((string) $cPRc) ? 'checked' : '' ?>>
              Colore solo per questa categoria
            </label>
            <input type="color" id="price_color_light" name="price_color_light" value="<?= h(is_valid_hex_color((string) $cPRc) ? (string) $cPRc : ($imp['store_price_color_chiaro'] ?? '#7a5c00')) ?>" style="height:44px; padding:4px;">
          </div>
          </div>
        </div>
        </div>

        <?php $filtroProprio = !empty($editCat['filter_active_color']); ?>
        <div class="velo-categoria">
          <div>
            <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
              <input type="checkbox" name="filter_custom" value="1" style="width:auto;"
                     onchange="document.getElementById('filtroCampi').classList.toggle('is-spento', !this.checked)"
                     <?= $filtroProprio ? 'checked' : '' ?>>
              <strong>Colori del filtro solo per questa categoria</strong>
            </label>
            <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
              Il pulsante di <?= $editCat ? h($editCat['name']) : 'questa categoria' ?> sopra la griglia dello store.
              Senza la spunta usa i colori generali.
            </p>
          </div>
          <div id="filtroCampi" class="<?= $filtroProprio ? '' : 'is-spento' ?>">
            <div class="tavolozze">
              <div class="tavolozza">
                <h4>Selezionato</h4>
                <input type="color" id="filter_active_color" name="filter_active_color" value="<?= h($editCat['filter_active_color'] ?? ($imp['store_filter_active_color'] ?? '#f0c75e')) ?>" style="height:44px; padding:4px;">
              </div>
              <div class="tavolozza">
                <h4>A riposo</h4>
                <input type="color" id="filter_idle_color" name="filter_idle_color" value="<?= h($editCat['filter_idle_color'] ?? ($imp['store_filter_idle_color'] ?? '#17181b')) ?>" style="height:44px; padding:4px;">
              </div>
            </div>
          </div>
        </div>
        <?php
          // Blocco sconto riusato identico da pacchetto e categoria: lo stesso nome dei campi
          // significa che entrambi passano da sconto_dal_post().
          $scTipo = $editCat['discount_type'] ?? '';
          $scValore = (float) ($editCat['discount_value'] ?? 0);
        ?>
        <div>
          <label for="cat_sconto_tipo">Sconto</label>
          <select id="cat_sconto_tipo" name="discount_type">
            <option value="" <?= $scTipo === '' ? 'selected' : '' ?>>Nessuno sconto</option>
            <option value="percentuale" <?= $scTipo === 'percentuale' ? 'selected' : '' ?>>Percentuale (%)</option>
            <option value="importo" <?= $scTipo === 'importo' ? 'selected' : '' ?>>Importo fisso (<?= h(site_setting('store_currency', 'EUR')) ?>)</option>
          </select>
        </div>
        <div>
          <label for="cat_sconto_valore">Valore dello sconto</label>
          <input type="text" id="cat_sconto_valore" name="discount_value" value="<?= h($scValore > 0 ? number_format($scValore, 2, ',', '') : '') ?>" placeholder="es. 20">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Si applica a tutti i pacchetti della categoria che non hanno uno sconto proprio.</p>
        </div>
        <button type="submit" class="btn btn-accent"><?= $editCat ? 'Salva categoria' : 'Crea categoria' ?></button>
      </form>
    </div>
    </div>

    <?php /* --- Pagamenti: stessa scheda dello store, perche' e' qui che servono --- */ ?>
    <h2 id="pagamenti" style="margin-top:34px;">Pagamenti</h2>
    <p class="sub" style="margin-bottom:14px;">
      L&rsquo;account PayPal che incassa gli acquisti. Senza questi dati i pacchetti restano
      visibili ma non acquistabili.
    </p>
    <?php
    $s = site_settings();
    $segretoImpostato = trim($s['paypal_secret'] ?? '') !== '';
    ?>
    <div class="alert alert-info">
      Qui si configura solo l'account che incassera' i pagamenti. La cassa vera e propria
      (pulsante di acquisto, conferma da PayPal e invio dei comandi al server) e' il passo successivo:
      finche' non c'e', i pacchetti restano visibili ma non acquistabili.
    </div>
    <div class="panel">
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="payments_save">
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="paypal_enabled" value="1" style="width:auto;" <?= ($s['paypal_enabled'] ?? '0') === '1' ? 'checked' : '' ?>>
            PayPal attivo
          </label>
        </div>
        <div>
          <label for="paypal_mode">Ambiente</label>
          <select id="paypal_mode" name="paypal_mode">
            <option value="sandbox" <?= ($s['paypal_mode'] ?? 'sandbox') === 'sandbox' ? 'selected' : '' ?>>Sandbox (prove, nessun soldo vero)</option>
            <option value="live" <?= ($s['paypal_mode'] ?? '') === 'live' ? 'selected' : '' ?>>Live (pagamenti reali)</option>
          </select>
        </div>
        <div>
          <label for="paypal_email">Email dell'account PayPal</label>
          <input type="text" id="paypal_email" name="paypal_email" value="<?= h($s['paypal_email'] ?? '') ?>">
        </div>
        <div>
          <label for="paypal_client_id">Client ID</label>
          <input type="text" id="paypal_client_id" name="paypal_client_id" value="<?= h($s['paypal_client_id'] ?? '') ?>">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Si crea su developer.paypal.com creando un'app; il Client ID non e' segreto.</p>
        </div>
        <div>
          <label for="paypal_secret">Secret <?= $segretoImpostato ? '(già impostato)' : '(non impostato)' ?></label>
          <input type="password" id="paypal_secret" name="paypal_secret" value="" autocomplete="new-password" placeholder="<?= $segretoImpostato ? 'lascia vuoto per non cambiarlo' : 'incolla qui il secret' ?>">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Non viene mai rimandato al browser: il campo resta vuoto e si riscrive solo se digiti qualcosa.</p>
        </div>
        <div>
          <label for="store_currency">Valuta (codice a 3 lettere)</label>
          <input type="text" id="store_currency" name="store_currency" maxlength="3" value="<?= h($s['store_currency'] ?? 'EUR') ?>" style="max-width:100px;">
        </div>
        <button type="submit" class="btn btn-accent">Salva pagamenti</button>
      </form>
    </div>

    <?php

// ---------------------------------------------------------------------
// STORE — creazione/modifica di un pacchetto
// ---------------------------------------------------------------------
} elseif ($section === 'store_pkg_edit') {
    $id = (int) ($_GET['id'] ?? 0);
    $pkg = ['category_id' => null, 'name' => '', 'image_url' => '', 'image_position' => '50% 50%', 'image_position_pc' => '50% 50%',
            'description' => '', 'long_description' => '',
            'price' => '0.00', 'commands' => '', 'sort_order' => 0, 'enabled' => 1, 'featured' => 0];
    if ($id > 0) {
        $q = db()->prepare('SELECT * FROM store_packages WHERE id = ?');
        $q->execute([$id]);
        $found = $q->fetch();
        if ($found) {
            $pkg = $found;
        } else {
            $id = 0;
        }
    }
    $cats = db()->query('SELECT id, name FROM store_categories ORDER BY sort_order, name')->fetchAll();

    // Chi e' in evidenza adesso (anche se nascosto): serve per avvisare che spuntando
    // questo pacchetto l'altro perde la vetrina.
    $evidenzaOra = db()->query('SELECT id, name FROM store_packages WHERE featured = 1 LIMIT 1')->fetch() ?: null;
    $altroInEvidenza = $evidenzaOra && (int) $evidenzaOra['id'] !== $id ? $evidenzaOra['name'] : null;
    ?>
    <div class="area-store">
    <a href="/manage?section=store">← Torna allo store</a>
    <div class="panel" style="margin-top:14px;">
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="store_pkg_save">
        <input type="hidden" name="id" value="<?= (int) $id ?>">
        <div>
          <label for="pkg_name">Titolo</label>
          <input type="text" id="pkg_name" name="name" value="<?= h($pkg['name']) ?>">
        </div>
        <div>
          <label for="pkg_cat">Categoria</label>
          <select id="pkg_cat" name="category_id">
            <option value="0">— senza categoria —</option>
            <?php foreach ($cats as $c): ?>
              <option value="<?= (int) $c['id'] ?>" <?= (int) $pkg['category_id'] === (int) $c['id'] ? 'selected' : '' ?>><?= h($c['name']) ?></option>
            <?php endforeach; ?>
          </select>
        </div>
        <div>
          <label for="pkg_price">Prezzo (<?= h(site_setting('store_currency', 'EUR')) ?>)</label>
          <input type="text" id="pkg_price" name="price" value="<?= h(number_format((float) $pkg['price'], 2, '.', '')) ?>" style="max-width:140px;">
        </div>
        <?php campo_immagine('pkg_image', 'image_url', (string) $pkg['image_url'],
            'Copertina del pacchetto',
            'Fa da sfondo alla card nello store e alla pagina del pacchetto. Incolla un indirizzo oppure carica un file con <strong>Scegli</strong>.'); ?>
        <?php if (store_ha_inquadratura()): ?>
          <?php /* Inquadratura della copertina (telefono e computer), come per gli articoli:
                   si trascina l'immagine per scegliere quale parte resta in vista sulla card. */ ?>
          <div class="inquadratura" data-inquadratura data-src-campo="#pkg_image"
               data-src="<?= h((string) $pkg['image_url']) ?>"
               <?= empty($pkg['image_url']) ? 'hidden' : '' ?>>
            <label>Inquadratura della copertina</label>
            <p class="sub" style="margin:-2px 0 10px;">Sulla card l&rsquo;immagine viene ritagliata, e telefono e computer tagliano in modo diverso: <strong>trascinale una per una</strong> per scegliere cosa tenere in vista. Sono indipendenti.</p>
            <div class="inquadratura-riquadri">
              <figure class="inquadratura-box e-telefono">
                <div class="inquadratura-tela" data-tela="telefono" data-campo="image_position"></div>
                <figcaption>Telefono</figcaption>
              </figure>
              <figure class="inquadratura-box e-computer">
                <div class="inquadratura-tela" data-tela="computer" data-campo="image_position_pc"></div>
                <figcaption>Computer</figcaption>
              </figure>
              <button type="button" class="btn btn-ghost btn-small" data-centra>Rimetti al centro</button>
            </div>
            <input type="hidden" name="image_position" value="<?= h($pkg['image_position'] ?? '50% 50%') ?>">
            <input type="hidden" name="image_position_pc" value="<?= h($pkg['image_position_pc'] ?? '50% 50%') ?>">
          </div>
        <?php else: ?>
          <p class="sub" style="margin:-6px 0 4px; color:var(--text-dim); font-size:12px;">
            Per scegliere l&rsquo;inquadratura della copertina (telefono e computer) lancia la migrazione
            <code>2026-09-14-store-inquadratura.sql</code> e ricarica.
          </p>
        <?php endif; ?>
        <div>
          <label for="pkg_desc">Cosa ottieni (una voce per riga)</label>
          <textarea id="pkg_desc" name="description" rows="4"><?= h((string) $pkg['description']) ?></textarea>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            Diventa l'elenco puntato che si vede sulla card e nella pagina del pacchetto.
          </p>
        </div>
        <div>
          <label for="pkg_long">Descrizione dettagliata (pagina del pacchetto)</label>
          <textarea id="pkg_long" name="long_description" rows="6"><?= h((string) ($pkg['long_description'] ?? '')) ?></textarea>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            Testo esteso mostrato solo su <span class="code-box">/pacchetto/<?= h($pkg['slug'] ?? 'nome-pacchetto') ?></span>,
            la pagina che si apre cliccando la card. Se lo lasci vuoto, la pagina mostra solo l'elenco qui sopra.
          </p>
        </div>
        <div>
          <label for="pkg_commands">Comandi eseguiti all'acquisto</label>
          <textarea id="pkg_commands" name="commands" rows="5" placeholder="lp user {player} parent add vip&#10;give {player} diamond 64"><?= h((string) $pkg['commands']) ?></textarea>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            Uno per riga, senza <code>/</code> iniziale, eseguiti dalla console del server.
            Usa <code>{player}</code> per il nome di chi acquista.
          </p>
        </div>
        <div>
          <label for="pkg_sort">Ordine (numero, crescente)</label>
          <input type="text" id="pkg_sort" name="sort_order" value="<?= h((string) $pkg['sort_order']) ?>">
        </div>
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" name="enabled" value="1" style="width:auto;" <?= $pkg['enabled'] ? 'checked' : '' ?>>
            Visibile nello store
          </label>
        </div>
        <div>
          <label style="text-transform:none; display:flex; align-items:center; gap:8px;">
            <input type="checkbox" id="pkg_featured" name="featured" value="1" style="width:auto;"
                   <?= !empty($pkg['featured']) ? 'checked' : '' ?>
                   <?= $altroInEvidenza !== null ? 'data-altro="' . h($altroInEvidenza) . '"' : '' ?>>
            Pacchetto in evidenza (promozione)
          </label>
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
            Ne esiste <strong>uno solo in tutto lo store</strong>, categorie comprese: fa da vetrina
            (la card larga in cima) ed è il pacchetto a cui punta il pulsante del banner promozione in home.
            <?php if ($altroInEvidenza !== null): ?>
              <br>In evidenza adesso: <strong><?= h($altroInEvidenza) ?></strong> — spuntando qui, lo sostituisci.
            <?php endif; ?>
          </p>
          <?php if ($altroInEvidenza !== null): ?>
            <div class="alert alert-info" id="avvisoEvidenza" hidden style="margin-top:10px;">
              «<?= h($altroInEvidenza) ?>» perderà la vetrina al salvataggio: in evidenza resterà solo questo pacchetto.
            </div>
          <?php endif; ?>
        </div>
        <?php if (!empty($pkg['featured']) && empty($pkg['enabled'])): ?>
          <div class="alert alert-error">
            Questo pacchetto è in evidenza ma <strong>nascosto</strong>: finché resta nascosto, lo store
            mette in vetrina il primo pacchetto disponibile e il banner della home torna al suo indirizzo manuale.
          </div>
        <?php endif; ?>
        <?php
          // Blocco sconto riusato identico da pacchetto e categoria: lo stesso nome dei campi
          // significa che entrambi passano da sconto_dal_post().
          $scTipo = $pkg['discount_type'] ?? '';
          $scValore = (float) ($pkg['discount_value'] ?? 0);
        ?>
        <div>
          <label for="pkg_sconto_tipo">Sconto</label>
          <select id="pkg_sconto_tipo" name="discount_type">
            <option value="" <?= $scTipo === '' ? 'selected' : '' ?>>Nessuno sconto</option>
            <option value="percentuale" <?= $scTipo === 'percentuale' ? 'selected' : '' ?>>Percentuale (%)</option>
            <option value="importo" <?= $scTipo === 'importo' ? 'selected' : '' ?>>Importo fisso (<?= h(site_setting('store_currency', 'EUR')) ?>)</option>
          </select>
        </div>
        <div>
          <label for="pkg_sconto_valore">Valore dello sconto</label>
          <input type="text" id="pkg_sconto_valore" name="discount_value" value="<?= h($scValore > 0 ? number_format($scValore, 2, ',', '') : '') ?>" placeholder="es. 20">
          <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">Vince sullo sconto della categoria e su quello generale. Lascia vuoto (o 0) per non scontare questo pacchetto.</p>
        </div>
        <button type="submit" class="btn btn-accent"><?= $id > 0 ? 'Salva pacchetto' : 'Crea pacchetto' ?></button>
      </form>
    </div>

    <?php if ($id > 0): ?>
      <h2 style="margin-top:34px;">Consegna manuale</h2>
      <p class="sub" style="margin-bottom:14px;">
        Esegue in gioco i comandi di questo pacchetto <strong>senza passare da PayPal</strong>: serve per
        provare la consegna, per un regalo o per rimediare a un ordine andato storto. L'ordine viene
        registrato in Pagamenti con la dicitura <code>MANUALE</code>, così resta traccia di chi l'ha fatto.
      </p>
      <div class="panel">
        <form method="post" class="stack" onsubmit="return confirm('Consegnare subito questo pacchetto? I comandi verranno eseguiti in gioco.');">
          <?= csrf_field() ?>
          <input type="hidden" name="action" value="store_pkg_deliver">
          <input type="hidden" name="id" value="<?= (int) $id ?>">
          <div>
            <label for="player">A chi</label>
            <input type="text" id="player" name="player" value="<?= h((string) $me['mc_username']) ?>" maxlength="32">
            <p style="color:var(--text-dim); font-size:12px; margin:4px 0 0;">
              Nome Minecraft. Deve essere un giocatore già noto al sito: account collegato con
              entrato in partita almeno una volta.
            </p>
          </div>
          <button type="submit" class="btn btn-ghost">Consegna ora</button>
        </form>
      </div>
    <?php endif; ?>
    </div>
    <script>
    // Spuntare "in evidenza" quando la vetrina e' gia' di un altro pacchetto e' una
    // sostituzione: si chiede conferma prima, e l'avviso resta visibile fino al salvataggio.
    (function () {
      var spunta = document.getElementById('pkg_featured');
      if (!spunta || !spunta.dataset.altro) return;
      var avviso = document.getElementById('avvisoEvidenza');
      spunta.addEventListener('change', function () {
        if (spunta.checked && !confirm('In evidenza c\'è già «' + spunta.dataset.altro + '».\n\nMettendo in evidenza questo pacchetto, l\'altro perde la vetrina e il banner della home punterà qui.\n\nVuoi sostituirlo?')) {
          spunta.checked = false;
        }
        if (avviso) avviso.hidden = !spunta.checked;
      });
    })();
    </script>
    <?php

// ---------------------------------------------------------------------
// PAGAMENTI — configurazione PayPal (solo web-admin)
// ---------------------------------------------------------------------
} elseif ($section === 'perms') {
    $groups = web_groups();
    $assigned = web_group_permissions();
    ?>
    <p class="sub" style="margin-bottom:16px;">
      I gruppi sono quelli di LuckPerms, allineati dal server: non si creano qui.
      Spunta cosa può fare ciascun gruppo sul sito — i permessi valgono per tutti i giocatori che lo hanno,
      e chi ne ha più di uno somma i permessi di tutti.
    </p>
    <div class="alert alert-info">
      La configurazione del sito — <strong>Pagine e menu, Aspetto, Banner VIP, impostazioni del blog</strong>
      e questa scheda stessa — non è delegabile: resta sempre e solo al <strong>web-admin</strong>.
      Allo staff si possono assegnare i contenuti (blog, forum) e la gestione utenti.
    </div>

    <?php if (!$groups): ?>
      <div class="alert alert-info">Nessun gruppo ancora ricevuto dal server. Compaiono al primo allineamento dopo l'avvio.</div>
    <?php endif; ?>

    <?php foreach ($groups as $g): ?>
      <?php $has = $assigned[$g['name']] ?? []; ?>
      <div class="panel" style="margin-bottom:18px;">
        <form method="post">
          <?= csrf_field() ?>
          <input type="hidden" name="action" value="perms_save">
          <input type="hidden" name="group" value="<?= h($g['name']) ?>">

          <div class="perm-group-head">
            <div>
              <?= player_tag(['tag_text' => $g['display'], 'tag_color' => $g['color'], 'group_name' => $g['name']]) ?>
              <strong><?= h($g['name']) ?></strong>
              <span class="sub">peso <?= (int) $g['weight'] ?> · <?= count($has) ?> permessi</span>
            </div>
            <button type="submit" class="btn btn-green btn-small">Salva <?= h($g['name']) ?></button>
          </div>

          <div class="perm-grid">
            <?php foreach (WEB_PERMISSIONS as $category): ?>
              <div class="perm-category">
                <div class="perm-category-title"><?= h($category['label']) ?></div>
                <?php foreach ($category['perms'] as $code => $desc): ?>
                  <label class="perm-item">
                    <input type="checkbox" name="perms[]" value="<?= h($code) ?>"
                      <?= in_array($code, $has, true) ? 'checked' : '' ?>>
                    <span><?= h($desc) ?><br><code class="perm-code"><?= h($code) ?></code></span>
                  </label>
                <?php endforeach; ?>
              </div>
            <?php endforeach; ?>
          </div>
        </form>
      </div>
    <?php endforeach; ?>
    <?php

// ---------------------------------------------------------------------
// UTENTI
// ---------------------------------------------------------------------
} elseif ($section === 'users') {
    // Il badge "admin" e' il ruolo SUL SITO; il tag colorato accanto e' il gruppo LuckPerms in gioco.
    // Ordinati per peso del grado in gioco (admin 100 in cima); chi non ha ancora un grado
    // sincronizzato finisce in fondo, e a parita' di peso vale il piu' recente.
    $users = db()->query('SELECT u.id, u.mc_username, u.mc_uuid, u.is_admin, u.created_at, u.last_login, u.last_seen, ' . RANK_SELECT_SQL
        . ' FROM users u' . rank_join_sql()
        . ' ORDER BY COALESCE(r.weight, -1) DESC, u.created_at DESC')->fetchAll();
    ?>
    <p class="sub" style="margin-bottom:14px;">
      Qui compaiono i giocatori che si sono registrati entrando in partita.
      Il pulsante <strong>Rendi web-admin</strong> dà accesso a questa gestione (blog, pagine, forum, aspetto);
      non c'entra col grado in gioco, che arriva da LuckPerms.
    </p>
    <?php if (count($users) === 1): ?>
      <div class="alert alert-info">Sei l'unico account registrato: appena un altro giocatore entrerà in partita e sceglierà una password, comparirà qui col pulsante per renderlo web-admin.</div>
    <?php endif; ?>
    <?php foreach ($users as $u): ?>
      <div class="manage-row">
        <div>
          <div class="title"><?= player_name($u, $u['mc_username']) ?> <?= $u['is_admin'] ? '<span class="badge-yes">web-admin</span>' : '' ?></div>
          <div class="sub">registrato <?= time_ago($u['created_at']) ?><?= $u['last_login'] ? ' · ultimo accesso ' . time_ago($u['last_login']) : '' ?></div>
        </div>
        <div class="actions">
          <?php if ((int)$u['id'] === (int)$me['id']): ?>
            <?php /* Nessun pulsante su se stessi: toglierti il web-admin ti chiuderebbe fuori dalla gestione. */ ?>
            <span class="sub">(il tuo account)</span>
          <?php elseif (is_protected_admin($u)): ?>
            <span class="sub">🔒 protetto — modificabile solo dal server</span>
          <?php elseif (can('users.manage')): ?>
            <form method="post">
              <?= csrf_field() ?>
              <input type="hidden" name="action" value="user_toggle_admin">
              <input type="hidden" name="id" value="<?= $u['id'] ?>">
              <button type="submit" class="btn <?= $u['is_admin'] ? 'btn-danger' : 'btn-green' ?> btn-small">
                <?= $u['is_admin'] ? 'Togli web-admin' : 'Rendi web-admin' ?>
              </button>
            </form>
          <?php endif; ?>
        </div>
      </div>
    <?php endforeach;

// ---------------------------------------------------------------------
// SICUREZZA — verifica in due passaggi (solo web-admin)
// ---------------------------------------------------------------------
// ---------------------------------------------------------------------
// SANZIONI — coda da confermare, ricorsi, revoche in sospeso, archivio
// ---------------------------------------------------------------------
} elseif ($section === 'sanzioni') {

    if (!sanctions_ready()) {
        ?>
        <div class="panel">
          <h3 style="margin-top:0;">Archivio non ancora attivo</h3>
          <p style="margin:0; color:var(--text-dim); font-size:14px;">
            Le tabelle delle sanzioni non ci sono ancora su questo database: lancia la migrazione
            <code>2026-08-29-sanzioni-e-guida-staff.sql</code> e ricarica la pagina.
          </p>
        </div>
        <?php
    } else {
        $cerca = trim((string) ($_GET['q'] ?? ''));

        // La classifica di chi controllare: e' la prima cosa che serve a chi apre questa pagina.
        $mostraTutti = isset($_GET['tutti']);
        $elenco = rischio_elenco($mostraTutti);

        $coda = db()->query("SELECT * FROM punishment_queue WHERE status = 'attesa' ORDER BY created_at ASC")->fetchAll();

        $ricorsi = db()->query(
            "SELECT r.*, s.mc_username, s.type, s.category, s.reason, s.ends_at, s.status AS sanzione_stato
               FROM punishment_appeals r JOIN punishments s ON s.id = r.punishment_id
              WHERE r.status = 'aperto' ORDER BY r.opened_at ASC"
        )->fetchAll();

        $daApplicare = db()->query(
            "SELECT * FROM punishments WHERE status = 'revocata' AND revoke_applied = 0 ORDER BY revoked_at DESC"
        )->fetchAll();

        if ($cerca !== '') {
            $q = db()->prepare('SELECT * FROM punishments WHERE mc_username LIKE ? ORDER BY created_at DESC LIMIT 50');
            $q->execute(['%' . str_replace(['%', '_'], ['\%', '\_'], $cerca) . '%']);
        } else {
            $q = db()->query('SELECT * FROM punishments ORDER BY created_at DESC LIMIT 30');
        }
        $archivio = $q->fetchAll();
        ?>

        <?php /* Un indice corto: la pagina e' lunga, ma resta una sola. */ ?>
        <nav class="sanzioni-indice">
          <a href="#da-controllare">Da controllare<?= $elenco ? ' <span class="conta-badge">' . count($elenco) . '</span>' : '' ?></a>
          <a href="#coda">Proposte<?= $coda ? ' <span class="conta-badge">' . count($coda) . '</span>' : '' ?></a>
          <a href="#ricorsi">Ricorsi<?= $ricorsi ? ' <span class="conta-badge">' . count($ricorsi) . '</span>' : '' ?></a>
          <a href="#archivio">Archivio</a>
        </nav>

        <?php require __DIR__ . '/../includes/rischio_pannello.php'; ?>

        <?php /* --- Coda: il lavoro che aspetta una persona --- */ ?>
        <?php if (can('sanzioni.coda')): ?>
          <div class="panel" id="coda">
            <h3 style="margin-top:0;">
              Proposte da decidere
              <?php if ($coda): ?><span class="conta-badge"><?= count($coda) ?></span><?php endif; ?>
            </h3>
            <?php if (!$coda): ?>
              <p style="margin:0; color:var(--text-dim); font-size:14px;">
                Niente in attesa. Ci finisce quello che il server non se la sente di decidere da solo
                e quello che supera il tetto di durata di chi l'ha chiesto.
              </p>
            <?php else: ?>
              <div class="sanzioni-coda">
                <?php foreach ($coda as $c): ?>
                  <div class="coda-riga">
                    <div class="coda-testa">
                      <?php $eSegnalazione = ($c['source'] ?? '') === 'report'; ?>
                      <strong><?= h($c['mc_username']) ?></strong>
                      <?php if ($eSegnalazione): ?>
                        <span class="coda-tipo coda-segnalazione">Segnalazione</span>
                        <span class="coda-fonte">da <?= h($c['proposed_by'] ?: 'un giocatore') ?></span>
                      <?php else: ?>
                        <span class="coda-tipo"><?= h(sanction_type((string) $c['type'])) ?></span>
                        <span class="coda-cat"><?= h(sanction_category((string) $c['category'])) ?></span>
                        <span class="coda-durata">
                          <?= h(duration_readable($c['duration_seconds'] === null ? null : (int) $c['duration_seconds'])) ?>
                        </span>
                        <span class="coda-fonte">proposta da <?= h($c['proposed_by'] ?: $c['source']) ?></span>
                      <?php endif; ?>
                      <span class="coda-fonte"><?= h(time_ago((string) $c['created_at'])) ?></span>
                    </div>
                    <p class="coda-motivo"><?= h($c['reason']) ?></p>
                    <?php if ($c['detail']): ?>
                      <details class="coda-prove">
                        <summary>Le prove</summary>
                        <pre><?= h($c['detail']) ?></pre>
                      </details>
                    <?php endif; ?>
                    <?php /* Su una segnalazione il provvedimento NON è proposto da nessuno: lo
                             sceglie chi chiude il caso. Sulle proposte automatiche i campi
                             partono già compilati, e restano modificabili. */ ?>
                    <div class="coda-azioni">
                      <form method="post" class="coda-decisione">
                        <?= csrf_field() ?>
                        <input type="hidden" name="action" value="coda_conferma">
                        <input type="hidden" name="id" value="<?= (int) $c['id'] ?>">
                        <label>
                          <span>Provvedimento</span>
                          <select name="tipo">
                            <?php foreach (SANZIONI_TIPI as $codice => $t): ?>
                              <option value="<?= h($codice) ?>" <?= $c['type'] === $codice ? 'selected' : '' ?>>
                                <?= h($t['etichetta']) ?>
                              </option>
                            <?php endforeach; ?>
                          </select>
                        </label>
                        <label>
                          <span>Durata</span>
                          <input type="text" name="durata" placeholder="30m, 6h, 3d, permanente"
                                 value="<?= $c['duration_seconds'] === null ? '' : h(duration_readable_short((int) $c['duration_seconds'])) ?>">
                        </label>
                        <button type="submit" class="btn btn-accent">Conferma e applica</button>
                      </form>
                      <form method="post">
                        <?= csrf_field() ?>
                        <input type="hidden" name="action" value="coda_respingi">
                        <input type="hidden" name="id" value="<?= (int) $c['id'] ?>">
                        <button type="submit" class="btn btn-ghost">Archivia senza provvedimenti</button>
                      </form>
                    </div>
                  </div>
                <?php endforeach; ?>
              </div>
            <?php endif; ?>
          </div>
        <?php endif; ?>

        <?php /* --- Ricorsi aperti --- */ ?>
        <?php if (can('sanzioni.ricorsi')): ?>
          <div class="panel" id="ricorsi">
            <h3 style="margin-top:0;">
              Ricorsi aperti
              <?php if ($ricorsi): ?><span class="conta-badge"><?= count($ricorsi) ?></span><?php endif; ?>
            </h3>
            <?php if (!$ricorsi): ?>
              <p style="margin:0; color:var(--text-dim); font-size:14px;">Nessun ricorso in attesa di risposta.</p>
            <?php else: ?>
              <?php foreach ($ricorsi as $r): ?>
                <div class="ricorso-riga">
                  <div class="coda-testa">
                    <strong><?= h($r['mc_username']) ?></strong>
                    <span class="coda-tipo"><?= h(sanction_type((string) $r['type'])) ?></span>
                    <span class="coda-cat"><?= h(sanction_category((string) $r['category'])) ?></span>
                    <a href="/sanzione/<?= (int) $r['punishment_id'] ?>" target="_blank" rel="noopener">apri il provvedimento →</a>
                  </div>
                  <p class="coda-motivo"><em>Motivo della sanzione:</em> <?= h($r['reason']) ?></p>
                  <blockquote class="ricorso-testo"><?= nl2br(h($r['text'])) ?></blockquote>
                  <form method="post" class="stack">
                    <?= csrf_field() ?>
                    <input type="hidden" name="action" value="ricorso_decidi">
                    <input type="hidden" name="id" value="<?= (int) $r['id'] ?>">
                    <label>Risposta all'interessato (privata)</label>
                    <textarea name="risposta" rows="4" required maxlength="5000"></textarea>
                    <label>Motivazione breve, pubblica (facoltativa)</label>
                    <input type="text" name="esito_pubblico" maxlength="255"
                           placeholder="Compare nell'elenco pubblico accanto all'esito">
                    <div class="ricorso-azioni">
                      <button type="submit" name="esito" value="accolto" class="btn btn-accent">
                        Accogli e revoca
                      </button>
                      <button type="submit" name="esito" value="respinto" class="btn btn-ghost">Respingi</button>
                    </div>
                  </form>
                </div>
              <?php endforeach; ?>
            <?php endif; ?>
          </div>
        <?php endif; ?>

        <?php /* --- Revoche decise qui e non ancora eseguite in gioco --- */ ?>
        <?php if ($daApplicare): ?>
          <div class="panel">
            <h3 style="margin-top:0;">Revoche in attesa del server <span class="conta-badge"><?= count($daApplicare) ?></span></h3>
            <p style="margin:0 0 10px; color:var(--text-dim); font-size:14px;">
              Decise qui, ma in gioco il provvedimento c'è ancora: il server le esegue appena
              MagixGuard le legge. Se restano ferme, il server è spento o il plugin non gira.
            </p>
            <ul class="revoche-attesa">
              <?php foreach ($daApplicare as $r): ?>
                <li>
                  <strong><?= h($r['mc_username']) ?></strong> —
                  <?= h(sanction_type((string) $r['type'])) ?>,
                  revocata da <?= h($r['revoked_by'] ?: 'staff') ?>
                  <?= $r['revoked_at'] ? h(time_ago((string) $r['revoked_at'])) : '' ?>
                </li>
              <?php endforeach; ?>
            </ul>
          </div>
        <?php endif; ?>

        <?php /* --- Archivio --- */ ?>
        <div class="panel" id="archivio">
          <h3 style="margin-top:0;">Archivio</h3>
          <form method="get" class="riga-cerca">
            <input type="hidden" name="section" value="sanzioni">
            <input type="search" name="q" value="<?= h($cerca) ?>" placeholder="Cerca un giocatore…">
            <button type="submit" class="btn btn-contrasto">Cerca</button>
            <?php if ($cerca !== ''): ?><a href="/manage?section=sanzioni">Azzera</a><?php endif; ?>
            <a href="/sanzioni" target="_blank" rel="noopener" style="margin-left:auto;">Vedi l'elenco pubblico →</a>
          </form>

          <?php if (!$archivio): ?>
            <p style="margin:0; color:var(--text-dim); font-size:14px;">
              <?= $cerca !== '' ? 'Nessuna sanzione per quel nome.' : 'Nessuna sanzione registrata.' ?>
            </p>
          <?php else: ?>
            <div class="archivio-elenco">
              <?php foreach ($archivio as $s): ?>
                <?php $st = sanction_status($s); ?>
                <div class="archivio-riga<?= $st !== 'attiva' ? ' e-conclusa' : '' ?>"
                     style="--accento:<?= h(sanction_color((string) $s['type'])) ?>">
                  <div class="archivio-dati">
                    <div class="coda-testa">
                      <a href="/sanzione/<?= (int) $s['id'] ?>" target="_blank" rel="noopener"><strong><?= h($s['mc_username']) ?></strong></a>
                      <span class="coda-tipo"><?= h(sanction_type((string) $s['type'], 'breve')) ?></span>
                      <span class="coda-cat"><?= h(sanction_category((string) $s['category'])) ?></span>
                      <span class="coda-durata"><?= h(sanction_duration($s)) ?></span>
                      <span class="sanzione-pallino sanzione-<?= h($st) ?>"><?= h($st) ?></span>
                    </div>
                    <p class="coda-motivo"><?= h($s['reason']) ?></p>
                  </div>
                  <?php if ($st === 'attiva' && can('sanzioni.revoca')): ?>
                    <form method="post" class="revoca-form">
                      <?= csrf_field() ?>
                      <input type="hidden" name="action" value="sanzione_revoca">
                      <input type="hidden" name="id" value="<?= (int) $s['id'] ?>">
                      <input type="text" name="motivo" required maxlength="255" placeholder="Perché la revochi">
                      <button type="submit" class="btn btn-ghost">Revoca</button>
                    </form>
                  <?php endif; ?>
                </div>
              <?php endforeach; ?>
            </div>
          <?php endif; ?>
        </div>
        <?php
    }


// ---------------------------------------------------------------------
// GUIDA PER AMMINISTRATORI — un capitolo per plugin, scritto dai plugin stessi
// ---------------------------------------------------------------------
} elseif ($section === 'guida') {
    $capitoli = guide_staff_capitoli();
    ?>
    <div class="panel">
      <h3 style="margin-top:0;">Guida per amministratori</h3>
      <p style="margin:0; color:var(--text-dim); font-size:14px;">
        Il manuale dei nostri plugin: cosa fanno, quali comandi hanno, chi può usarli e cosa
        fare quando qualcosa non va. <strong>Non è scritta a mano</strong>: la riscrive ogni
        plugin a ogni avvio del server, quindi non può raccontare una versione che non esiste più.
      </p>
    </div>

    <?php /* Cercare a mano dentro dieci capitoli di manuale e' il modo piu' veloce per non
             leggerli: qui si scrive la domanda e si finisce sul capitolo giusto. Stesso
             motore della guida dei giocatori (includes/guida_ricerca.php), veste sobria. */ ?>
    <?php if ($capitoli): ?>
      <div class="guida-ia guida-ia--sobria" data-guida-ia data-ambito="staff" data-modo="singola">
        <div class="guida-ia-testa">
          <h2>Cerca nella guida</h2>
          <span class="guida-ia-sub">risponde solo con quello che i plugin hanno scritto</span>
        </div>

        <form class="guida-ia-form" autocomplete="off">
          <input type="text" name="q" maxlength="200"
                 placeholder="Scrivi la domanda, per esempio: come si revoca un ban?"
                 aria-label="La tua domanda sulla guida per amministratori">
          <button type="submit" class="guida-ia-invia">Cerca</button>
        </form>

        <div class="guida-ia-risposte" data-guida-ia-risposte aria-live="polite"></div>

        <p class="guida-ia-nota">
          Non inventa niente: cerca nei capitoli qui sotto, cita il pezzo che risponde e ti
          porta al punto. Se lì una cosa non è scritta, lo dice invece di indovinare.
        </p>
      </div>
    <?php endif; ?>

    <?php if (!$capitoli): ?>
      <div class="panel">
        <p style="margin:0; color:var(--text-dim); font-size:14px;">
          Nessun capitolo ancora. I plugin lo pubblicano da soli al primo avvio dopo
          l'aggiornamento che introduce la guida; se il server è acceso e qui resta vuoto,
          controlla che MagixWeb sia attivo (è lui a raccogliere i capitoli).
        </p>
      </div>
    <?php else: ?>
      <div class="guida-staff">
        <nav class="guida-indice" aria-label="Indice della guida">
          <?php foreach ($capitoli as $c): ?>
            <a href="#guida-<?= h($c['plugin']) ?>"><?= h($c['title']) ?></a>
          <?php endforeach; ?>
        </nav>

        <div class="guida-capitoli">
          <?php foreach ($capitoli as $c): ?>
            <?php
              $quando = strtotime((string) $c['updated_at']);
              // Un capitolo fermo da una settimana e' sospetto: o il server e' rimasto spento,
              // o quel plugin non parte piu'. Meglio dirlo che far finta di niente.
              $vecchia = $quando < strtotime('-7 days');
            ?>
            <section class="panel" id="guida-<?= h($c['plugin']) ?>">
              <div class="guida-testa">
                <h3 style="margin:0;"><?= h($c['title']) ?></h3>
                <span class="guida-versione">
                  <?= h($c['plugin']) ?><?= $c['version'] ? ' ' . h($c['version']) : '' ?>
                  · aggiornata <?= h(time_ago((string) $c['updated_at'])) ?>
                </span>
              </div>
              <?php if ($vecchia): ?>
                <p class="guida-avviso">
                  Non si aggiorna da <?= h(time_ago((string) $c['updated_at'])) ?>:
                  potrebbe descrivere una versione diversa da quella in funzione.
                </p>
              <?php endif; ?>
              <div class="guida-corpo"><?= $c['body_html'] ?></div>
            </section>
          <?php endforeach; ?>
        </div>
      </div>
    <?php endif; ?>
    <?php

} elseif ($section === 'sicurezza') {
    $staffObbligatorio = site_setting('otp_staff_obbligatorio', '0') === '1';

    // Chi e' soggetto alla verifica: i web-admin sempre, piu' — se l'interruttore e'
    // acceso — chi ha permessi del gestionale. Si guardano tutti gli account che hanno
    // almeno una delle due cose, cosi' l'elenco resta corto e leggibile.
    $righe = db()->query('SELECT u.id, u.mc_username, u.mc_uuid, u.is_admin, u.last_login,
                                 u.totp_activated_at, u.totp_locked_until, r.groups_json,
                                 (SELECT COUNT(*) FROM otp_recovery_codes c
                                   WHERE c.user_id = u.id AND c.used_at IS NULL) AS codici
                          FROM users u
                          LEFT JOIN mc_ranks r ON r.mc_uuid = u.mc_uuid COLLATE utf8mb4_unicode_ci
                          ORDER BY u.is_admin DESC, u.mc_username')->fetchAll();
    $soggetti = array_values(array_filter($righe, fn($u) => otp_serve_per($u)));
    ?>
    <p class="sub" style="margin-bottom:16px;">
      Sugli account che comandano il sito la password da sola non basta: serve anche un
      codice a sei cifre che cambia ogni trenta secondi (TOTP, quello delle app come Google
      Authenticator o Aegis). Lo stesso codice vale per entrare in gioco.
    </p>

    <?php if (!otp_chiave_pronta()): ?>
      <div class="alert alert-error">
        Manca <code>OTP_CHIAVE</code> in <code>includes/config.php</code>: senza quella chiave i
        segreti non si possono cifrare e <strong>nessuno riesce ad attivare la verifica</strong>.
        Va generata sul server e copiata anche nella configurazione del plugin MagixWeb.
      </div>
    <?php endif; ?>

    <div class="panel">
      <h2>Chi deve usarla</h2>
      <p style="color:var(--text-dim); font-size:14px;">
        Sui <strong>web-admin</strong> e' sempre obbligatoria e non si puo' spegnere: sono
        gli account che possono cambiare prezzi, pagine e ruoli di tutti gli altri.
      </p>
      <form method="post" class="stack">
        <?= csrf_field() ?>
        <input type="hidden" name="action" value="otp_staff_save">
        <label class="campo-check">
          <input type="checkbox" name="otp_staff" value="1"<?= $staffObbligatorio ? ' checked' : '' ?>>
          <span>Richiedila anche allo <strong>staff con permessi del gestionale</strong>
                (moderatori del forum, chi scrive sul blog...)</span>
        </label>
        <button type="submit" class="btn btn-accent">Salva</button>
      </form>
    </div>

    <div class="panel">
      <h2>Account soggetti alla verifica</h2>
      <?php if (!$soggetti): ?>
        <p style="color:var(--text-dim);">Nessuno: non risulta nessun web-admin.</p>
      <?php endif; ?>

      <?php foreach ($soggetti as $u): ?>
        <?php
          $attiva = !empty($u['totp_activated_at']);
          $bloccato = !empty($u['totp_locked_until']) && strtotime($u['totp_locked_until']) > time();
        ?>
        <div class="manage-row">
          <div>
            <div class="title"><?= h($u['mc_username']) ?>
              <?= $u['is_admin'] ? '<span class="badge-yes">web-admin</span>' : '<span class="sub">staff</span>' ?></div>
            <div class="otp-stato" style="margin-top:4px;">
              <span class="otp-pallino<?= $attiva ? '' : ' is-spento' ?>"></span>
              <span style="font-size:13px; color:var(--text-dim);">
                <?php if ($attiva): ?>
                  attiva dal <?= date('d/m/Y', strtotime($u['totp_activated_at'])) ?>,
                  <?= (int) $u['codici'] ?> codici di recupero rimasti
                  <?php if ((int) $u['codici'] === 0): ?>
                    &mdash; <strong>senza codici di scorta</strong>
                  <?php endif; ?>
                <?php else: ?>
                  mai attivata: gliela chiede il sito al prossimo accesso
                <?php endif; ?>
                <?php if ($bloccato): ?>
                  &middot; <strong>bloccata</strong> fino alle <?= date('H:i', strtotime($u['totp_locked_until'])) ?>
                <?php endif; ?>
                <?php
                  // Sessione di gioco: la fiducia che il server Minecraft gli sta dando
                  // adesso (dopo una verifica riuscita vale 12 ore per indirizzo di rete).
                  $sessioni = $attiva ? otp_sessioni_gioco($u['mc_uuid']) : [];
                ?>
                <?php if ($sessioni): ?>
                  <br>in gioco: verificato <?= h(time_ago($sessioni[0]['verified_at'])) ?>
                  dalla rete <?= h(otp_ip_mascherato($sessioni[0]['ip'])) ?>
                <?php elseif ($attiva): ?>
                  <br>in gioco: nessuna verifica in corso
                <?php endif; ?>
              </span>
            </div>
          </div>
          <div class="actions">
            <?php if ($attiva): ?>
              <?php if ($sessioni): ?>
                <form method="post"
                      onsubmit="return confirm('Chiudere la sessione di gioco di <?= h($u['mc_username']) ?>? Se e' in partita adesso viene bloccato subito e gli viene richiesto il codice.');">
                  <?= csrf_field() ?>
                  <input type="hidden" name="action" value="otp_revoca_gioco">
                  <input type="hidden" name="id" value="<?= (int) $u['id'] ?>">
                  <button type="submit" class="btn btn-ghost btn-small">Chiudi sessione di gioco</button>
                </form>
              <?php endif; ?>
              <form method="post"
                    onsubmit="return confirm('Azzerare la verifica di <?= h($u['mc_username']) ?>? Dovra' riconfigurarla al prossimo accesso, e intanto viene fatto uscire da ogni dispositivo.');">
                <?= csrf_field() ?>
                <input type="hidden" name="action" value="otp_azzera">
                <input type="hidden" name="id" value="<?= (int) $u['id'] ?>">
                <button type="submit" class="btn btn-danger btn-small">Azzera verifica</button>
              </form>
            <?php endif; ?>
          </div>
        </div>
      <?php endforeach; ?>
    </div>

    <div class="panel">
      <h2>Se un amministratore resta fuori</h2>
      <ol style="margin:0 0 0 18px; padding:0; color:var(--text-dim); font-size:14px;">
        <li>Prima strada: entra con uno dei <strong>codici di recupero</strong> salvati all'attivazione.</li>
        <li>Seconda: un altro web-admin gli <strong>azzera la verifica</strong> qui sopra.</li>
        <li>Ultima, se non c'e' nessun altro web-admin: dal server, via SSH
          <span class="code-box">sudo mariadb magicadventure_web -e "UPDATE users SET totp_secret=NULL, totp_activated_at=NULL WHERE mc_username='NOME'"</span>
        </li>
      </ol>
    </div>
    <?php

// ---------------------------------------------------------------------
// SERVER — console, avvio e arresto (scheda "Server")
// ---------------------------------------------------------------------
} elseif ($section === 'console') {
    require_once __DIR__ . '/../includes/console.php';
    // Primo stato disegnato dal server: la scheda si apre gia' piena, poi tocca al
    // copione tenerla aggiornata (accendere o spegnere richiede decine di secondi).
    $statoIniziale = console_stato();
    ?>
    <?php if (!console_ponte_pronto()): ?>
      <div class="alert alert-error">
        Il ponte con la macchina non è installato. Sul VPS, dalla cartella del sito:
        <code>sudo bash vps/installa-console.sh</code>
      </div>
    <?php endif; ?>

    <div id="consoleServer" class="console-area"
         data-csrf="<?= h(csrf_token()) ?>"
         data-modo="<?= h($statoIniziale['modo_screen']) ?>">

      <div class="barra-sezione">
        <h2>Server</h2>
        <span class="sub" id="consoleAggiornato"></span>
      </div>
      <div id="consoleAvvisi"></div>
      <div id="consoleIstanze" class="console-griglia"></div>

      <div class="barra-sezione"><h2>Console</h2></div>
      <div class="panel console-pannello">
        <div class="console-testata">
          <div class="console-schede" id="consoleSchede"></div>
          <div class="console-strumenti">
            <label class="console-strumento">Righe
              <select id="consoleRighe">
                <option value="100">100</option>
                <option value="300" selected>300</option>
                <option value="1000">1000</option>
              </select>
            </label>
            <label class="console-strumento">
              <input type="checkbox" id="consoleAuto" checked> aggiorna da sola
            </label>
            <button type="button" class="btn btn-ghost btn-small" id="consoleGiu">In fondo</button>
          </div>
        </div>
        <pre class="console-schermo" id="consoleSchermo">Caricamento…</pre>
        <form class="console-comando" id="consoleForm" autocomplete="off">
          <span class="console-prompt">&gt;</span>
          <input type="text" id="consoleComando" maxlength="400"
                 placeholder="comando da mandare alla console (senza / davanti)">
          <button type="submit" class="btn btn-accent btn-small">Invia</button>
        </form>
        <div class="console-rapidi" id="consoleRapidi">
          <button type="button" class="btn btn-ghost btn-small" data-comando="save-all">Salva i mondi</button>
          <button type="button" class="btn btn-ghost btn-small" data-comando="list">Chi è online</button>
          <button type="button" class="btn btn-ghost btn-small" data-comando="tps">Prestazioni (tps)</button>
          <button type="button" class="btn btn-ghost btn-small" data-comando="whitelist on">Whitelist ON</button>
          <button type="button" class="btn btn-ghost btn-small" data-comando="whitelist off">Whitelist OFF</button>
          <button type="button" class="btn btn-ghost btn-small" data-riempi="say ">Annuncio…</button>
          <button type="button" class="btn btn-ghost btn-small" data-riempi="kick ">Espelli…</button>
        </div>
        <p class="sub console-nota">
          I comandi vanno scritti come in console, <strong>senza la barra</strong> davanti.
          Attenzione a <code>stop</code>: scritto qui il server si <em>riavvia</em> da solo
          (start.sh ha il ciclo di riavvio); per spegnerlo davvero usa il pulsante <strong>Ferma</strong>.
        </p>
      </div>

      <div class="barra-sezione"><h2>Altre screen del VPS</h2></div>
      <div class="panel">
        <p class="sub" style="margin-top:0;">
          Le sessioni <code>screen</code> aperte sulla macchina che non sono server configurati
          (per esempio aperte a mano via SSH).
          <?php if ($statoIniziale['modo_screen'] === 'pieno'): ?>
            Qui il sito può anche scriverci dentro e chiuderle: è l'impostazione
            <code>screen_esterni=pieno</code> di <code>/etc/magicadventure/istanze.conf</code>.
          <?php else: ?>
            Il sito le legge soltanto: per poterci anche scrivere, metti
            <code>opzione screen_esterni=pieno</code> in <code>/etc/magicadventure/istanze.conf</code>.
          <?php endif; ?>
        </p>
        <div id="consoleScreens"></div>
      </div>

      <p class="sub">
        Le modalità che aggiungerai (proxy, lobby, mondi eventi…) compaiono qui da sole:
        basta una riga in <code>/etc/magicadventure/istanze.conf</code>, dove ci sono già gli esempi.
        Ogni avvio, arresto e comando finisce in <code>/var/log/magix-console.log</code> col nome di chi l'ha fatto.
      </p>
    </div>
    <script id="consoleStatoIniziale" type="application/json"><?= json_encode($statoIniziale, JSON_UNESCAPED_UNICODE | JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT) ?></script>
    <?php

// ---------------------------------------------------------------------
// MENU DI GIOCO - l'editor dei menu di MagixMenus (scheda "Menu di gioco")
// ---------------------------------------------------------------------
//  Qui dentro c'e' solo il guscio: quello che si vede lo disegna menu-editor.js, che
//  prende i menu da /api/menu. Il motivo e' lo stesso della scheda Server: si disegna,
//  si salva e si applica senza ricaricare la pagina, e ricostruire una griglia di
//  cinquantaquattro caselle a ogni clic dal server non avrebbe senso.
} elseif ($section === 'menu') {
    ?>
    <div id="menuEditor" class="menu-editor" data-csrf="<?= h(csrf_token()) ?>">
      <div class="barra-sezione">
        <h2>Menu di gioco</h2>
        <span class="sub" id="menuStato"></span>
      </div>
      <div id="menuAvvisi"></div>
      <div id="menuApp" class="menu-app">
        <p class="muted">Carico i menu dal server…</p>
      </div>
    </div>
    <?php
}
?>

<?php if (in_array($section, ['blog_edit', 'page_edit'])): ?>
<script src="/assets/js/editor.js"></script>
<?php endif; ?>

<?php if (in_array($section, ['blog_edit', 'store_pkg_edit'], true)): ?>
<script src="/assets/js/inquadratura.js?v=<?= @filemtime(__DIR__ . '/assets/js/inquadratura.js') ?: time() ?>"></script>
<?php endif; ?>

<?php if ($section === 'store'): ?>
<script src="/assets/js/store-admin.js"></script>
<?php endif; ?>

<?php if ($section === 'forum'): ?>
<script src="/assets/js/forum-admin.js?v=<?= @filemtime(__DIR__ . '/assets/js/forum-admin.js') ?: time() ?>"></script>
<?php endif; ?>

<?php /* Pagine e navigazione: il trascinamento delle voci di menu */ ?>
<?php if ($section === 'pages'): ?>
<script src="/assets/js/nav-admin.js?v=<?= @filemtime(__DIR__ . '/assets/js/nav-admin.js') ?: time() ?>"></script>
<?php endif; ?>

<?php if ($section === 'console'): ?>
<script src="/assets/js/console-server.js?v=<?= @filemtime(__DIR__ . '/assets/js/console-server.js') ?: time() ?>"></script>
<?php endif; ?>

<?php if ($section === 'menu'): ?>
<script src="/assets/js/menu-editor.js?v=<?= @filemtime(__DIR__ . '/assets/js/menu-editor.js') ?: time() ?>"></script>
<?php endif; ?>

<?php /* La ricerca dentro la guida per amministratori (stesso copione di /tutorial). */ ?>
<?php if ($section === 'guida'): ?>
<script src="/assets/js/guida-ia.js?v=<?= @filemtime(__DIR__ . '/assets/js/guida-ia.js') ?: time() ?>"></script>
<script>
// I link dell'indice puntano ai capitoli con un'ancora: il salto nativo del browser e' secco
// e per giunta lascerebbe il titolo mezzo coperto dalla barra fissa. Lo intercetto per fare
// uno scorrimento morbido fino al capitolo, con lo stesso stacco (altezza reale della barra,
// misurata in --h-testata) usato dal resto del sito. Stesso motore di guida-ia.js.
(function () {
  var indice = document.querySelector('.guida-indice');
  if (!indice) return;
  function stacco() {
    var h = document.querySelector('.site-header');
    return (h ? h.getBoundingClientRect().height : 98) + 16;
  }
  indice.addEventListener('click', function (ev) {
    var a = ev.target.closest ? ev.target.closest('a[href^="#"]') : null;
    if (!a) return;
    var meta = document.getElementById(a.getAttribute('href').slice(1));
    if (!meta) return;   // ancora senza destinazione: lascio fare al browser
    ev.preventDefault();
    var y = Math.max(0, meta.getBoundingClientRect().top + window.pageYOffset - stacco());
    var partenza = window.pageYOffset;
    window.scrollTo({ top: y, behavior: 'smooth' });
    // Lo scorrimento morbido lo ignorano in silenzio certi browser e chi ha spento le
    // animazioni: se dopo un attimo siamo ancora fermi, si salta e basta.
    setTimeout(function () {
      if (Math.abs(window.pageYOffset - partenza) < 2 && Math.abs(y - partenza) > 2) {
        window.scrollTo(0, y);
      }
    }, 350);
  });
})();
</script>
<?php endif; ?>

<?php /* Il pulsante "Scegli" dei campi immagine: sezioni con almeno un campo di quel tipo. */ ?>
<?php if (in_array($section, ['blog_edit', 'theme', 'store_pkg_edit'])): ?>
<script src="/assets/js/carica-immagine.js"></script>
<?php endif; ?>

<script>
// Su telefono le schede scorrono di lato: se quella aperta e' fuori vista la porto in vista,
// altrimenti si atterra sulla sezione senza vedere dove ci si trova.
(function () {
  var barra = document.querySelector('.tabs-row');
  var attiva = barra && barra.querySelector('a.active');
  if (!attiva || barra.scrollWidth <= barra.clientWidth) return;
  var sinistra = attiva.offsetLeft - (barra.clientWidth - attiva.offsetWidth) / 2;
  barra.scrollLeft = Math.max(0, sinistra);
})();
</script>

<?php require __DIR__ . '/../includes/footer.php'; ?>
