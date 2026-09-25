<?php
/**
 * Pezzi comuni delle pagine del forum.
 *
 * Il forum e' volutamente sobrio: l'unico colore e' il filo di sinistra della categoria,
 * che vale anche per le sue sezioni. Se nel gestionale non e' stato scelto un colore, se
 * lo prende da qui — sempre lo stesso per la stessa categoria (dipende dall'id, non
 * dall'ordine) e pescato dalle tinte del tema, cosi' cambiando i colori del sito cambia
 * anche il forum.
 */

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/helpers.php';

/**
 * Dichiarazioni CSS con la tinta della categoria, pronte per l'attributo style.
 *
 * $colore e' quello scelto a mano nel gestionale: se c'e', vince. Se manca (NULL) la
 * tinta e' automatica e dipende dall'id, quindi non cambia riordinando le categorie.
 */
function forum_tinta(int $categoriaId, int $posizione = 0, ?string $colore = null): string {
    if ($colore !== null && is_valid_hex_color($colore)) {
        return '--accento:' . $colore
            . ';--accento-alone:' . hex_to_rgba($colore, 0.35)
            . ';--i:' . $posizione;
    }

    $tinte = ['purple', 'green', 'gold'];
    $t = $tinte[$categoriaId % count($tinte)];
    return '--accento:var(--' . $t . ');--accento-alone:var(--' . $t . '-glow);--i:' . $posizione;
}

/**
 * La tinta automatica come colore vero (non come variabile CSS): serve al gestionale,
 * dove il selettore di colore deve partire da quello che si vedrebbe senza sceglierlo.
 */
function forum_tinta_hex(int $categoriaId): string {
    $tinte = [
        site_setting('color_purple', '#c04ff0'),
        site_setting('color_green', '#a3e635'),
        site_setting('vip_banner_color', '#f0c75e'),
    ];
    $colore = $tinte[$categoriaId % count($tinte)];
    return is_valid_hex_color($colore) ? $colore : '#c04ff0';
}

/**
 * L'ultimo messaggio di ogni categoria, per la riga "ultima attivita'" in /forum.
 * Una query sola per tutte le categorie: MAX(id) per categoria e poi il join.
 *
 * @return array<int,array> indicizzato per category_id
 */
function forum_latest_posts(): array {
    $sql = "SELECT t.category_id, t.id AS topic_id, t.title, p.created_at,
                   u.mc_username, u.mc_uuid, u.premium_uuid, u.is_admin, u.last_seen, " . RANK_SELECT_SQL . "
            FROM forum_posts p
            JOIN forum_topics t ON t.id = p.topic_id
            JOIN users u ON u.id = p.user_id" . rank_join_sql() . "
            WHERE p.id IN (
                SELECT MAX(p2.id) FROM forum_posts p2
                JOIN forum_topics t2 ON t2.id = p2.topic_id
                GROUP BY t2.category_id
            )";

    $per = [];
    foreach (db()->query($sql)->fetchAll() as $riga) {
        $per[(int) $riga['category_id']] = $riga;
    }
    return $per;
}

/**
 * L'albero completo del forum: categorie principali, le loro sezioni e i numeri di ognuna.
 *
 * Sta qui e non dentro le pagine perche' la prima pagina e quella di una categoria devono
 * mostrare le stesse cose (nome, descrizione, numeri, ultimo messaggio): se ognuna se li
 * calcolasse per conto suo, prima o poi direbbero due cose diverse.
 *
 * @return array{
 *   principali: array<int,array>,
 *   sezioni: array<int,array<int,array>>,
 *   ultimi: array<int,array>,
 *   totali: array{discussioni:int,messaggi:int}
 * }
 */
function forum_albero(): array {
    $cats = db()->query("
        SELECT c.*,
               (SELECT COUNT(*) FROM forum_topics t WHERE t.category_id = c.id) AS topic_count,
               (SELECT COUNT(*) FROM forum_posts p
                  JOIN forum_topics t ON t.id = p.topic_id
                 WHERE t.category_id = c.id) AS post_count
        FROM forum_categories c
        ORDER BY c.sort_order, c.name
    ")->fetchAll();

    $idEsistenti = array_map('intval', array_column($cats, 'id'));

    $sezioni = [];
    $principali = [];
    foreach ($cats as $c) {
        // Una sezione il cui padre e' stato cancellato tornerebbe invisibile: la tratto
        // come categoria principale invece di farla sparire dal forum.
        $orfana = $c['parent_id'] && !in_array((int) $c['parent_id'], $idEsistenti, true);
        if ($c['parent_id'] && !$orfana) {
            $sezioni[(int) $c['parent_id']][] = $c;
        } else {
            $principali[] = $c;
        }
    }

    $totali = ['discussioni' => 0, 'messaggi' => 0];
    foreach ($cats as $c) {
        $totali['discussioni'] += (int) $c['topic_count'];
        $totali['messaggi'] += (int) $c['post_count'];
    }

    return [
        'principali' => $principali,
        'sezioni' => $sezioni,
        'ultimi' => forum_latest_posts(),
        'totali' => $totali,
    ];
}

/**
 * Numeri di una categoria comprese le sue sezioni, e il suo messaggio piu' recente.
 * Una categoria che contiene sezioni ha quasi sempre zero discussioni proprie: senza
 * questa somma direbbe "0 discussioni" mentre dentro ce ne sono.
 *
 * @return array{discussioni:int,messaggi:int,ultimo:?array}
 */
function forum_riepilogo(array $cat, array $sezioni, array $ultimi): array {
    $discussioni = (int) $cat['topic_count'];
    $messaggi = (int) $cat['post_count'];
    $ultimo = $ultimi[(int) $cat['id']] ?? null;

    foreach ($sezioni as $s) {
        $discussioni += (int) $s['topic_count'];
        $messaggi += (int) $s['post_count'];
        $suo = $ultimi[(int) $s['id']] ?? null;
        if ($suo && (!$ultimo || strtotime($suo['created_at']) > strtotime($ultimo['created_at']))) {
            $ultimo = $suo;
        }
    }

    return ['discussioni' => $discussioni, 'messaggi' => $messaggi, 'ultimo' => $ultimo];
}

/**
 * "1 discussione" e non "1 discussioni". Il numero in grassetto e la parola accanto:
 * il forum e' pieno di contatori e leggerli sgrammaticati fa sciatto.
 */
function forum_conta(int $n, string $uno, string $molti): string {
    return '<strong>' . $n . '</strong> ' . ($n === 1 ? $uno : $molti);
}

/**
 * Una riga "sezione". Identica in prima pagina e nella pagina della categoria madre, cosi'
 * si impara a riconoscerla una volta sola. E' un collegamento vero e proprio: la sezione ha
 * una pagina sua, non e' un'ancora dentro un'altra pagina.
 *
 * L'id "sotto-<slug>" c'era gia' quando le sezioni erano ancore: cosi' i vecchi indirizzi
 * girati in chat continuano a portare al punto giusto.
 */
function forum_riga_sezione(array $sez, ?array $ultimo): void {
    ?>
    <a class="forum-sezione-riga" id="sotto-<?= h($sez['slug']) ?>"
       href="/forum/<?= urlencode($sez['slug']) ?>">
      <span class="forum-sezione-testo">
        <span class="forum-sezione-nome"><?= h($sez['name']) ?></span>
        <?php if ($sez['description']): ?>
          <span class="forum-sezione-desc"><?= h($sez['description']) ?></span>
        <?php endif; ?>
      </span>
      <span class="forum-sezione-numeri">
        <span><?= forum_conta((int) $sez['topic_count'], 'discussione', 'discussioni') ?></span>
        <span><?= forum_conta((int) $sez['post_count'], 'messaggio', 'messaggi') ?></span>
      </span>
      <span class="forum-sezione-ultimo">
        <?php if ($ultimo): ?>
          <?= forum_faccia($ultimo, 28) ?>
          <span class="forum-sezione-ultimo-testo">
            <span class="forum-ultimo-titolo"><?= h($ultimo['title']) ?></span>
            <span class="forum-ultimo-meta"><?= h(time_ago($ultimo['created_at'])) ?></span>
          </span>
        <?php else: ?>
          <span class="forum-vuoto">Ancora vuota</span>
        <?php endif; ?>
      </span>
      <span class="forum-sezione-freccia" aria-hidden="true">&rsaquo;</span>
    </a>
    <?php
}

/** Faccia del giocatore, con il posto gia' riservato: niente salti mentre carica. */
function forum_faccia(?array $riga, int $misura = 40): string {
    $img = '<img class="forum-faccia" src="' . h(mc_avatar_url($riga['mc_uuid'] ?? null, $misura * 2, $riga['premium_uuid'] ?? null))
        . '" alt="" width="' . $misura . '" height="' . $misura . '" loading="lazy">';
    // Corona del miglior sostenitore: passa da qui tutto il forum (autori, elenchi, firme).
    // I cuoricini solo sulle facce grandi: su una da 24px sarebbero coriandoli.
    return avatar_top($img, $riga['mc_uuid'] ?? null, $misura);
}

/**
 * "Mi piace" dei messaggi passati: quanti ne ha ciascuno e quali ho messo io.
 * Una query per il conteggio e una per i miei, invece di due per ogni messaggio.
 *
 * @return array{conta: array<int,int>, miei: array<int,bool>}
 */
function forum_mi_piace(array $postIds, ?int $ioId): array {
    $vuoto = ['conta' => [], 'miei' => []];
    $postIds = array_values(array_unique(array_map('intval', $postIds)));
    if (!$postIds) {
        return $vuoto;
    }
    $segni = implode(',', array_fill(0, count($postIds), '?'));

    try {
        $q = db()->prepare("SELECT post_id, COUNT(*) AS n FROM forum_likes
                            WHERE post_id IN ($segni) GROUP BY post_id");
        $q->execute($postIds);
        $conta = [];
        foreach ($q->fetchAll() as $r) {
            $conta[(int) $r['post_id']] = (int) $r['n'];
        }

        $miei = [];
        if ($ioId) {
            $q = db()->prepare("SELECT post_id FROM forum_likes
                                WHERE user_id = ? AND post_id IN ($segni)");
            $q->execute(array_merge([$ioId], $postIds));
            foreach ($q->fetchAll() as $r) {
                $miei[(int) $r['post_id']] = true;
            }
        }
        return ['conta' => $conta, 'miei' => $miei];
    } catch (PDOException $e) {
        return $vuoto; // tabella non ancora creata: nessun mi piace, pagina intatta
    }
}

/** Mette o toglie il mi piace: e' un interruttore, non si accumula. */
function forum_like_toggle(int $postId, int $userId): void {
    $del = db()->prepare('DELETE FROM forum_likes WHERE post_id = ? AND user_id = ?');
    $del->execute([$postId, $userId]);
    if ($del->rowCount() === 0) {
        $ins = db()->prepare('INSERT IGNORE INTO forum_likes (post_id, user_id) VALUES (?, ?)');
        $ins->execute([$postId, $userId]);
    }
}

/**
 * Numeri di ogni giocatore sul sito: discussioni aperte, messaggi scritti e mi piace
 * RICEVUTI (sui propri messaggi, non quelli messi agli altri).
 *
 * Tre query in tutto, non tre per autore: nella stessa discussione lo stesso autore
 * compare spesso molte volte.
 *
 * @return array<int,array{discussioni:int,messaggi:int,mi_piace:int}>
 */
function forum_user_stats(array $userIds): array {
    $userIds = array_values(array_unique(array_filter(array_map('intval', $userIds))));
    if (!$userIds) {
        return [];
    }
    $segni = implode(',', array_fill(0, count($userIds), '?'));
    $out = [];
    foreach ($userIds as $uid) {
        $out[$uid] = ['discussioni' => 0, 'messaggi' => 0, 'mi_piace' => 0];
    }

    $q = db()->prepare("SELECT user_id, COUNT(*) AS n FROM forum_topics
                        WHERE user_id IN ($segni) GROUP BY user_id");
    $q->execute($userIds);
    foreach ($q->fetchAll() as $r) {
        $out[(int) $r['user_id']]['discussioni'] = (int) $r['n'];
    }

    $q = db()->prepare("SELECT user_id, COUNT(*) AS n FROM forum_posts
                        WHERE user_id IN ($segni) GROUP BY user_id");
    $q->execute($userIds);
    foreach ($q->fetchAll() as $r) {
        $out[(int) $r['user_id']]['messaggi'] = (int) $r['n'];
    }

    try {
        $q = db()->prepare("SELECT p.user_id, COUNT(*) AS n FROM forum_likes l
                            JOIN forum_posts p ON p.id = l.post_id
                            WHERE p.user_id IN ($segni) GROUP BY p.user_id");
        $q->execute($userIds);
        foreach ($q->fetchAll() as $r) {
            $out[(int) $r['user_id']]['mi_piace'] = (int) $r['n'];
        }
    } catch (PDOException $e) {
        // tabella dei mi piace non ancora creata: restano a zero
    }

    return $out;
}

/**
 * Le discussioni toccate piu' di recente, di tutto il forum.
 *
 * In prima pagina c'era l'elenco delle categorie e basta: chi arrivava non vedeva NIENTE
 * di quello che si stava dicendo, e doveva entrare a caso per scoprirlo. Questa e' la
 * porta d'ingresso; le categorie restano sotto, per chi cerca un posto preciso.
 *
 * @return array<int,array>
 */
function forum_ultime_discussioni(int $quante = 6): array {
    $stmt = db()->prepare("
        SELECT t.id, t.title, t.last_post_at, t.views,
               c.name AS cat_name, c.slug AS cat_slug, c.id AS cat_id, c.color AS cat_color,
               u.mc_username, u.mc_uuid, u.premium_uuid, u.is_admin, u.last_seen, " . RANK_SELECT_SQL . ",
               (SELECT COUNT(*) FROM forum_posts p WHERE p.topic_id = t.id) AS post_count
        FROM forum_topics t
        JOIN forum_categories c ON c.id = t.category_id
        JOIN users u ON u.id = t.user_id" . rank_join_sql() . "
        ORDER BY t.last_post_at DESC
        LIMIT " . max(1, $quante));
    $stmt->execute();
    return $stmt->fetchAll();
}
