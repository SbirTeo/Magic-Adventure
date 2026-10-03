<?php
/**
 * Contatti social dei giocatori: ognuno li imposta nel suo profilo (/profilo), tutti li vedono
 * sulla scheda pubblica (/utente) e nel profilo.
 *
 * Si salva SOLO il nome utente, ripulito e controllato contro le regole di ogni social: chi
 * incolla un indirizzo intero ("https://www.instagram.com/nome/") si ritrova "nome". Il
 * collegamento lo costruisce il sito, sempre verso il dominio vero del social: un campo del
 * profilo non puo' diventare un link a un sito qualsiasi (truffe, phishing) ne' un pezzo di HTML.
 * Discord non ha pagine pubbliche per nome: si mostra il nome, con un tasto per copiarlo.
 */

require_once __DIR__ . '/db.php';

/**
 * I social che si possono indicare, nell'ordine in cui si mostrano.
 *  - pattern: nome utente valido per quel social (dopo la pulizia);
 *  - url: indirizzo del profilo, %s = nome utente (null = niente link, solo copia);
 *  - hosts: domini da cui si accetta un indirizzo incollato;
 *  - prefix: cosa si vede davanti al campo, per far capire che cosa scrivere.
 */
const SOCIAL_NETWORKS = [
    'discord'   => ['label' => 'Discord',   'icon' => 'message', 'pattern' => '/^[a-z0-9_.]{2,32}$/',      'url' => null,                               'hosts' => [],                                  'prefix' => 'nome utente', 'color' => '#5865f2'],
    'youtube'   => ['label' => 'YouTube',   'icon' => 'play',    'pattern' => '/^[A-Za-z0-9_.-]{3,30}$/',  'url' => 'https://www.youtube.com/@%s',     'hosts' => ['youtube.com', 'www.youtube.com', 'm.youtube.com'], 'prefix' => 'youtube.com/@', 'color' => '#ff3b30'],
    'twitch'    => ['label' => 'Twitch',    'icon' => 'tv',      'pattern' => '/^[A-Za-z0-9_]{4,25}$/',    'url' => 'https://www.twitch.tv/%s',        'hosts' => ['twitch.tv', 'www.twitch.tv', 'm.twitch.tv'],       'prefix' => 'twitch.tv/', 'color' => '#9146ff'],
    'tiktok'    => ['label' => 'TikTok',    'icon' => 'music',   'pattern' => '/^[A-Za-z0-9_.]{2,24}$/',   'url' => 'https://www.tiktok.com/@%s',      'hosts' => ['tiktok.com', 'www.tiktok.com', 'm.tiktok.com'],    'prefix' => 'tiktok.com/@', 'color' => '#25f4ee'],
    'instagram' => ['label' => 'Instagram', 'icon' => 'camera',  'pattern' => '/^[A-Za-z0-9_.]{1,30}$/',   'url' => 'https://www.instagram.com/%s',    'hosts' => ['instagram.com', 'www.instagram.com'],              'prefix' => 'instagram.com/', 'color' => '#e1306c'],
    'x'         => ['label' => 'X',         'icon' => 'at',      'pattern' => '/^[A-Za-z0-9_]{1,15}$/',    'url' => 'https://x.com/%s',                'hosts' => ['x.com', 'www.x.com', 'twitter.com', 'www.twitter.com', 'mobile.twitter.com'], 'prefix' => 'x.com/', 'color' => '#a1a1aa'],
    'telegram'  => ['label' => 'Telegram',  'icon' => 'send',    'pattern' => '/^[A-Za-z0-9_]{5,32}$/',    'url' => 'https://t.me/%s',                 'hosts' => ['t.me', 'telegram.me', 'www.t.me'],                 'prefix' => 't.me/', 'color' => '#26a5e4'],
];

/**
 * Il nome utente ripulito: '' se il campo e' vuoto (= togli il contatto), null se non e' valido.
 * Accetta il nome nudo, con la chiocciola davanti, o l'indirizzo del profilo copiato dal browser.
 */
function social_normalize(string $network, string $input): ?string {
    $rete = SOCIAL_NETWORKS[$network] ?? null;
    if ($rete === null) {
        return null;
    }
    $v = trim($input);
    if ($v === '') {
        return '';
    }
    // Un indirizzo incollato: se e' del social giusto se ne prende il primo pezzo del percorso.
    if (preg_match('~^(https?://)?([a-z0-9.-]+\.[a-z]{2,})(/.*)?$~i', $v, $m) && strpos($v, '/') !== false) {
        $host = strtolower($m[2]);
        if (!in_array($host, $rete['hosts'], true)) {
            return null;
        }
        $percorso = trim((string) ($m[3] ?? ''), '/');
        $percorso = preg_replace('~[?#].*$~', '', $percorso);
        $v = explode('/', $percorso)[0] ?? '';
        // Su YouTube e TikTok il profilo e' /@nome: /watch, /channel/..., /video/... sono altro.
        if (in_array($network, ['youtube', 'tiktok'], true) && !str_starts_with($v, '@')) {
            return null;
        }
        // Pagine dei social che non sono un profilo (un post, una storia, la home...).
        if (in_array(strtolower(ltrim($v, '@')), ['p', 'reel', 'reels', 'stories', 'explore', 'home', 'i', 'share', 'videos', 'directory', 'search'], true)) {
            return null;
        }
    }
    $v = ltrim($v, '@');
    if ($network === 'discord') {
        $v = strtolower($v);
    }
    return preg_match($rete['pattern'], $v) ? $v : null;
}

/** I contatti di un giocatore, nell'ordine di SOCIAL_NETWORKS. Vuoto se la tabella non c'e' ancora. */
function social_list(int $userId): array {
    try {
        $q = db()->prepare('SELECT network, handle FROM user_socials WHERE user_id = ?');
        $q->execute([$userId]);
        $per = [];
        foreach ($q->fetchAll() as $r) {
            $per[(string) $r['network']] = (string) $r['handle'];
        }
    } catch (PDOException $e) {
        return [];
    }
    $ordinati = [];
    foreach (SOCIAL_NETWORKS as $id => $_) {
        if (isset($per[$id])) {
            $ordinati[$id] = $per[$id];
        }
    }
    return $ordinati;
}

/**
 * Salva i contatti dal modulo del profilo. Un campo vuoto toglie il contatto; un campo non valido
 * non tocca quello salvato e torna fra gli errori (social => testo scritto).
 *
 * @return array<string,string> i campi non validi
 */
function social_save(int $userId, array $input): array {
    $errori = [];
    $salva = db()->prepare('INSERT INTO user_socials (user_id, network, handle) VALUES (?, ?, ?)
                            ON DUPLICATE KEY UPDATE handle = VALUES(handle)');
    $togli = db()->prepare('DELETE FROM user_socials WHERE user_id = ? AND network = ?');
    foreach (SOCIAL_NETWORKS as $id => $_) {
        if (!array_key_exists($id, $input)) {
            continue;
        }
        $scritto = mb_substr((string) $input[$id], 0, 200);
        $pulito = social_normalize($id, $scritto);
        if ($pulito === null) {
            $errori[$id] = $scritto;
        } elseif ($pulito === '') {
            $togli->execute([$userId, $id]);
        } else {
            $salva->execute([$userId, $id, $pulito]);
        }
    }
    return $errori;
}

/** I contatti come pulsanti: link al profilo del social, o "copia" per Discord. */
function social_links_html(array $socials): string {
    if (!$socials) {
        return '';
    }
    $html = '<div class="social-elenco">';
    foreach ($socials as $id => $nome) {
        $rete = SOCIAL_NETWORKS[$id] ?? null;
        if ($rete === null) {
            continue;
        }
        $dentro = ui_icon($rete['icon']) . '<span><small>' . h($rete['label']) . '</small>' . h(($id === 'discord' ? '' : '@') . $nome) . '</span>';
        $stile = ' style="--social:' . h($rete['color']) . '"';
        if ($rete['url'] === null) {
            $html .= '<button type="button" class="social-voce" data-copia="' . h($nome) . '"' . $stile
                   . ' title="Copia il nome ' . h($rete['label']) . '">' . $dentro . '</button>';
        } else {
            $html .= '<a class="social-voce" href="' . h(sprintf($rete['url'], rawurlencode($nome))) . '"' . $stile
                   . ' target="_blank" rel="nofollow noopener noreferrer ugc">' . $dentro . '</a>';
        }
    }
    return $html . '</div>';
}
