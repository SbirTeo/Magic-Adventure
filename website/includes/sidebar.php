<?php
/**
 * La colonna di destra del sito (chat live + scheda giocatore + "sul sito ora").
 *
 * Stava dentro index.php: ora e' qui perche' la usano anche le altre pagine (forum,
 * classifiche, regolamento, guida, pagine create dal gestionale). Chi la mostra e chi no
 * si decide da "Pagine e navigazione" (vedi pagina_con_sidebar).
 */

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/helpers.php';
require_once __DIR__ . '/auth.php';
require_once __DIR__ . '/permissions.php';

/**
 * Vero se la pagina all'indirizzo $path deve avere la colonna laterale.
 *
 * Due sorgenti, entrambe modificabili dal gestionale:
 *  - le voci della barra di navigazione (nav_items.show_sidebar) valgono per la loro pagina
 *    e per tutto quello che ci sta sotto (es. /forum vale anche per /forum/topic);
 *  - le pagine create dal gestionale (site_pages.show_sidebar), che stanno in /pagina/<slug>.
 * Se nessuno dei due dice niente, la colonna non si mostra.
 */
function pagina_con_sidebar(string $path): bool {
    static $memoria = [];
    $path = rtrim($path, '/');
    if ($path === '') {
        $path = '/';
    }
    if (isset($memoria[$path])) {
        return $memoria[$path];
    }

    $risposta = false;
    try {
        // Pagina personalizzata: la sua spunta sta in site_pages.
        if (preg_match('#^/pagina/([A-Za-z0-9._-]+)$#', $path, $m)) {
            $q = db()->prepare('SELECT show_sidebar FROM site_pages WHERE slug = ?');
            $q->execute([$m[1]]);
            $v = $q->fetchColumn();
            $risposta = $v !== false && (int) $v === 1;
        } else {
            // Voce di menu: vince la piu' lunga che combacia, cosi' /forum/topic segue /forum
            // e non la voce Home ("/", che altrimenti combacerebbe con tutto).
            $righe = db()->query('SELECT url, show_sidebar FROM nav_items')->fetchAll();
            $miglior = -1;
            foreach ($righe as $r) {
                $url = rtrim((string) $r['url'], '/');
                if ($url === '') {
                    $url = '/';
                }
                $combacia = $path === $url || ($url !== '/' && str_starts_with($path, $url . '/'));
                if ($combacia && strlen($url) > $miglior) {
                    $miglior = strlen($url);
                    $risposta = (int) $r['show_sidebar'] === 1;
                }
            }
        }
    } catch (PDOException $e) {
        $risposta = false; // colonna show_sidebar non ancora creata
    }

    $memoria[$path] = $risposta;
    return $risposta;
}

/** Stampa la colonna. Le pagine che se la disegnano da sole (home, store) la chiamano loro. */
/**
 * @param bool $conContenitore false = stampa solo i riquadri, senza il <div class="side-col">.
 *        Serve allo store, che ha gia' una sua colonna: i riquadri del sito si aggiungono
 *        sotto ai suoi invece di aprire una seconda colonna appiccicata.
 */
function sidebar_colonna(bool $conContenitore = true): void {
    $me = current_user();

    $playerStats = null;
    if ($me) {
        try {
            $q = db()->prepare("
                SELECT p.power, p.max_power, f.name AS faction_name, f.tag,
                       (SELECT COUNT(*) FROM factions_magixfactions.claims c WHERE c.faction_id = f.id) AS territories
                FROM users u
                LEFT JOIN factions_magixfactions.players p ON p.uuid = u.mc_uuid COLLATE utf8mb4_unicode_ci
                LEFT JOIN factions_magixfactions.faction_members fm ON fm.uuid = u.mc_uuid COLLATE utf8mb4_unicode_ci
                LEFT JOIN factions_magixfactions.factions f ON f.id = fm.faction_id
                WHERE u.id = ?
            ");
            $q->execute([$me['id']]);
            $playerStats = $q->fetch() ?: null;
        } catch (PDOException $e) {
            $playerStats = null; // database del gioco non raggiungibile: trattini al posto dei numeri
        }
    }

    // Chi sta navigando il sito adesso, in ordine di grado (lo staff in cima). I nomi vanno
    // in fila separati da virgola: cosi' in poche righe ci stanno molte piu' persone che con
    // un elenco di facce, e il riquadro non diventa una colonna infinita.
    $onlineMinuti = max(1, (int) site_setting('online_finestra_minuti', '5'));
    $onlineVisibili = max(1, min(50, (int) site_setting('online_max_visibili', '15')));
    $sulSito = users_on_site($onlineMinuti, 500);
    $sulSitoPrimi = array_slice($sulSito, 0, $onlineVisibili);
    $sulSitoAltri = array_slice($sulSito, $onlineVisibili);
    $ospiti = guests_on_site($onlineMinuti);

    /** Un nome cliccabile, col colore del grado piu' importante, con mini-scheda avatar al passaggio del
     *  mouse (come la live chat). Porta alla sua scheda. L'avatar porta la corona del miglior sostenitore. */
    $nameOnline = function (array $p): string {
        $colore = player_name_color($p);
        $style = $colore !== null ? ' style="' . rank_color_style($colore) . '"' : '';
        $avatar = avatar_top(
            '<img src="' . h(mc_avatar_url($p['mc_uuid'], 64)) . '" alt="' . h($p['mc_username']) . '" '
            . 'width="48" height="48" loading="lazy">', $p['mc_uuid'], 48);
        $card = '<span class="online-card">' . $avatar
            . '<span class="online-card-name colore-grado"' . $style . '>' . h($p['mc_username']) . '</span></span>';
        return '<span class="online-nome-wrap">'
            . '<a class="online-nome colore-grado" href="/utente?nome=' . h(rawurlencode($p['mc_username'])) . '"' . $style . '>'
            . h($p['mc_username']) . '</a>' . $card . '</span>';
    };
    ?>
    <?php if ($conContenitore): ?><div class="side-col"><?php endif; ?>

    <?php if (site_setting('chat_enabled', '1') === '1'): ?>
      <?php /* Ponte con la chat del server: i messaggi scritti qui li ripubblica in gioco
               il plugin MagixWeb, e la chat pubblica del gioco arriva qui. Il contenuto lo
               riempie /assets/js/chat.js interrogando /api/chat. */ ?>
      <section class="chat-module" id="liveChat"
               data-logged="<?= $me ? '1' : '0' ?>"
               data-csrf="<?= h(csrf_token()) ?>"
               data-can-delete="<?= can('chat.moderate') ? '1' : '0' ?>"
               data-can-clear="<?= can('chat.clear') ? '1' : '0' ?>"
               data-poll="5000">
        <div class="chat-head">
          <span class="chat-dot" aria-hidden="true"></span>
          <h3>Chat live</h3>
          <span class="chat-head-sub">in diretta col server</span>
          <?php /* Da computer la chat si apre a tutta pagina (vedi chat.js): su telefono il
                   pulsante non compare, li' la chat e' gia' larga quanto lo schermo. */ ?>
          <button type="button" class="card-edit-btn card-edit-btn-small chat-ingrandisci"
                  id="chatIngrandisci" title="Ingrandisci" aria-label="Ingrandisci la chat"
                  aria-pressed="false">⤢</button>
          <?php if (can('chat.clear')): ?>
            <?php /* Svuota tutta la chat: la vede solo chi ha il permesso chat.clear. Il
                     cestino la distingue dalla ✕ sul singolo messaggio, che invece ne toglie
                     uno solo. La conferma la chiede chat.js. */ ?>
            <button type="button" class="card-edit-btn card-edit-btn-small chat-svuota"
                    id="chatSvuota" title="Svuota la chat" aria-label="Svuota la chat">
              <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8"
                   stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M3.5 5.5h13M8 5.5V3.6h4v1.9M5.6 5.5l.7 10.9h7.4l.7-10.9"/>
              </svg>
            </button>
          <?php endif; ?>
          <?php if (is_admin()): ?>
            <a href="/manage?section=theme#chat-live" class="card-edit-btn card-edit-btn-small chat-edit" title="Modifica" aria-label="Modifica">✎</a>
          <?php endif; ?>
        </div>
        <div class="chat-messages" id="chatMessages" aria-live="polite">
          <p class="chat-vuoto">Caricamento…</p>
        </div>
        <?php if ($me): ?>
          <form class="chat-form" id="chatForm" autocomplete="off">
            <input type="text" id="chatInput" maxlength="200" placeholder="Scrivi in chat…" aria-label="Messaggio">
            <button type="submit" class="chat-send" aria-label="Invia">➤</button>
          </form>
        <?php else: ?>
          <a href="/login" class="btn btn-accent btn-small chat-login">Accedi per scrivere</a>
        <?php endif; ?>
        <p class="chat-stato" id="chatStato"></p>
      </section>
    <?php endif; ?>

    <aside class="player-sidebar">
    <?php if ($me): ?>
      <div class="player-avatar-wrap">
        <?= avatar_top(
              '<img src="' . h(mc_avatar_url($me['mc_uuid'], 88)) . '" alt="" class="player-avatar"'
              . ' width="72" height="72" decoding="async">',
              $me['mc_uuid'], 72) ?>
      </div>
      <div class="player-name">
        <?= player_name($me, $me['mc_username']) ?>
      </div>

      <div class="player-stats">
        <div class="player-stat">
          <span>Fazione</span>
          <strong><?= $playerStats && $playerStats['faction_name'] ? h($playerStats['faction_name']) : '—' ?></strong>
        </div>
        <div class="player-stat">
          <span>Potenza</span>
          <strong><?= $playerStats && $playerStats['power'] !== null ? (int) $playerStats['power'] . ' / ' . (int) $playerStats['max_power'] : '—' ?></strong>
        </div>
        <div class="player-stat">
          <span>Territori</span>
          <strong><?= $playerStats && $playerStats['territories'] !== null ? (int) $playerStats['territories'] : '0' ?></strong>
        </div>
      </div>

      <a href="/logout" class="btn btn-ghost btn-small player-sidebar-btn">Esci</a>
    <?php else: ?>
      <div class="player-name">Non hai effettuato l'accesso</div>
      <p style="color:var(--text-dim); font-size:13px; margin:8px 0 16px;">Accedi per vedere il tuo profilo, la tua fazione e le tue statistiche.</p>
      <a href="/login" class="btn btn-accent player-sidebar-btn">Accedi</a>
    <?php endif; ?>
    </aside>

    <?php if ($sulSito || $ospiti > 0): ?>
      <section class="panel modulo-online">
        <div class="online-head">
          <span class="online-dot" aria-hidden="true"></span>
          <h3>Sul sito ora</h3>
          <span class="online-conta"><?= count($sulSito) + $ospiti ?></span>
        </div>

        <?php if ($sulSito): ?>
          <p class="online-nomi">
            <?php foreach ($sulSitoPrimi as $i => $p): ?>
              <?= $i > 0 ? '<span class="online-virgola">, </span>' : '' ?><?= $nameOnline($p) ?>
            <?php endforeach; ?>
            <?php if ($sulSitoAltri): ?>
              <span class="online-virgola">, </span>
              <?php /* Il resto sta in una finestrella, non nel riquadro: qui dentro venti nomi
                       in piu' allungherebbero la colonna oltre lo schermo. */ ?>
              <button type="button" class="online-altri" id="apriOnline">e altri <?= count($sulSitoAltri) ?>…</button>
            <?php endif; ?>
          </p>
        <?php endif; ?>

        <?php if ($ospiti > 0): ?>
          <p class="online-ospiti">
            <?= $ospiti === 1 ? '1 ospite sta guardando' : $ospiti . ' ospiti stanno guardando' ?>
            <?= $sulSito ? '' : 'il sito' ?>
          </p>
        <?php endif; ?>

        <?php if ($sulSitoAltri): ?>
          <?php /* Elenco completo per la finestrella: e' gia' qui, nascosto, cosi' aprirla non
                   richiede un altro giro sul server. Il "carica altri" lo fa site.js. */ ?>
          <template id="datiOnline"><?php
            foreach ($sulSito as $p) {
                echo '<li class="online-voce">'
                    . avatar_top(
                        '<img class="online-avatar" src="' . h(mc_avatar_url($p['mc_uuid'], 32)) . '" alt="" '
                        . 'width="22" height="22" loading="lazy">',
                        $p['mc_uuid'], 22)
                    . $nameOnline($p) . '</li>';
            }
          ?></template>

          <dialog class="online-finestra" id="finestraOnline" aria-label="Chi e' sul sito adesso">
            <div class="online-finestra-testata">
              <h3>Sul sito ora <span class="online-conta"><?= count($sulSito) ?></span></h3>
              <button type="button" class="online-chiudi" id="chiudiOnline" aria-label="Chiudi">&times;</button>
            </div>
            <ul class="online-lista" id="listaOnline"></ul>
            <button type="button" class="btn btn-contrasto btn-small" id="altriOnline" hidden>Carica altri</button>
          </dialog>
        <?php endif; ?>
      </section>
    <?php endif; ?>


    <?php if (is_admin()): ?>
      <?php /* Stesso collegamento della colonna dello store: da qui si decide su quali
               pagine questa colonna compare (voce per voce, in Pagine e menu). */ ?>
      <a class="colonna-modifica" href="/manage?section=pages#menu"
         title="Scegli su quali pagine mostrare questa colonna">&#9998; Configura questa colonna</a>
    <?php endif; ?>
    <?php if ($conContenitore): ?></div><?php endif; ?>
    <?php
}
