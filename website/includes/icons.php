<?php
/**
 * Icone del sito: piccoli disegni a tratto (SVG) al posto delle emoji.
 *
 * Le emoji le disegna il sistema del visitatore, ognuno a modo suo (Windows, Android, iPhone):
 * colori diversi, margini diversi, e in mezzo a un testo curato fanno "fatto in casa". Queste sono
 * linee che prendono il colore del testo (currentColor), quindi seguono temi e stati da sole, e
 * hanno tutte lo stesso tratto. Stile ispirato a Lucide (licenza ISC): 24x24, tratto 2, angoli tondi.
 *
 * Uso: <?= ui_icon('trophy') ?> dentro un testo; la misura e' quella della scritta (1em).
 * Per un'icona che non c'e' torna una stringa vuota: meglio niente che un quadratino.
 */

/** Il disegno di ogni icona (contenuto dell'<svg>, viewBox 0 0 24 24). */
const UI_ICONS = [
    'trophy'    => '<path d="M8 21h8M12 17v4M7 4h10v5a5 5 0 0 1-10 0z"/><path d="M17 5h3v2a3 3 0 0 1-3 3M7 5H4v2a3 3 0 0 0 3 3"/>',
    'user'      => '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
    'shield'    => '<path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"/>',
    'shield-check' => '<path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"/><path d="M9 12l2 2 4-4"/>',
    'map'       => '<path d="M9 4L3 6v14l6-2 6 2 6-2V4l-6 2z"/><path d="M9 4v14M15 6v14"/>',
    'coins'     => '<circle cx="9" cy="9" r="6"/><path d="M15.5 9.3a6 6 0 1 1-6.2 6.2"/>',
    'hourglass' => '<path d="M6 3h12M6 21h12M7 3v3a5 5 0 0 0 10 0V3M7 21v-3a5 5 0 0 1 10 0v3"/>',
    'zap'       => '<path d="M13 2L4 14h7l-1 8 9-12h-7z"/>',
    'swords'    => '<path d="M14.5 17.5L3 6V3h3l11.5 11.5M13 19l6-6M16 16l4 4M19 21l2-2"/><path d="M9.5 6.5L13 3h3v3l-3.5 3.5M5 14l4 4M7 17l-3 3M3 19l2 2"/>',
    'gem'       => '<path d="M6 3h12l4 6-10 12L2 9z"/><path d="M2 9h20M12 21L8 9l4-6 4 6z"/>',
    'clock'     => '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    'book'      => '<path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20"/>',
    'scroll'    => '<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><path d="M14 3v6h6M8 13h8M8 17h6"/>',
    'pin'       => '<path d="M12 17v5M9 3h6l-1 6 4 4H6l4-4z"/>',
    'lock'      => '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
    'thumbs-up' => '<path d="M7 10v11M7 10l4-8a3 3 0 0 1 3 3v4h5a2 2 0 0 1 2 2.3l-1.4 7A2 2 0 0 1 17.6 21H7M7 10H4a1 1 0 0 0-1 1v9a1 1 0 0 0 1 1h3"/>',
    'crown'     => '<path d="M3 8l4.5 4L12 5l4.5 7L21 8l-2 11H5z"/><path d="M5 19h14"/>',
    'palette'   => '<path d="M12 3a9 9 0 1 0 0 18c1 0 1.5-.8 1.5-1.5 0-.6-.4-1-.4-1.6 0-.8.7-1.4 1.5-1.4H17a4 4 0 0 0 4-4c0-5-4-8.5-9-8.5z"/><circle cx="7.5" cy="11" r="1"/><circle cx="10" cy="7" r="1"/><circle cx="14.5" cy="7" r="1"/>',
    'message'   => '<path d="M21 12a8 8 0 0 1-11.5 7.2L4 21l1.8-5.5A8 8 0 1 1 21 12z"/>',
    'cart'      => '<circle cx="9" cy="20" r="1"/><circle cx="18" cy="20" r="1"/><path d="M2 3h3l2.5 12h11l2-8H6"/>',
    'image'     => '<rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="9" cy="9" r="2"/><path d="M21 15l-5-5L5 21"/>',
    'mic'       => '<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10v1a7 7 0 0 0 14 0v-1M12 18v4M8 22h8"/>',
    'mic-off'   => '<path d="M2 2l20 20M15 9.3V5a3 3 0 0 0-5.7-1.3M9 9v2a3 3 0 0 0 5.1 2.1M19 10v1a7 7 0 0 1-1.1 3.8M5 10v1a7 7 0 0 0 11.3 5.5M12 18v4M8 22h8"/>',
    'headphones' => '<path d="M3 18v-6a9 9 0 0 1 18 0v6"/><path d="M21 19a2 2 0 0 1-2 2h-1v-6h3zM3 19a2 2 0 0 0 2 2h1v-6H3z"/>',
    'users'     => '<circle cx="9" cy="8" r="4"/><path d="M2 21a7 7 0 0 1 14 0M16 3.1a4 4 0 0 1 0 7.8M22 21a7 7 0 0 0-5-6.7"/>',
    'phone-off' => '<path d="M10.7 13.3a16 16 0 0 1-3-4l1.5-1.5a1 1 0 0 0 .2-1.1L8 3.6A1 1 0 0 0 7 3H4a1 1 0 0 0-1 1.1A17 17 0 0 0 7.8 16M2 2l20 20M14 15.3l1.6-1.6a1 1 0 0 1 1.1-.2l3.1 1.4a1 1 0 0 1 .6.9V19a1 1 0 0 1-1.1 1 17 17 0 0 1-7.4-2.4"/>',
];

/** Un'icona in linea col testo (1em, colore del testo). Decorativa: lo screen reader la salta. */
function ui_icon(string $name, string $class = ''): string {
    $disegno = UI_ICONS[$name] ?? null;
    if ($disegno === null) {
        return '';
    }
    $classi = 'ico' . ($class !== '' ? ' ' . htmlspecialchars($class, ENT_QUOTES) : '');
    return '<svg class="' . $classi . '" viewBox="0 0 24 24" aria-hidden="true" focusable="false">' . $disegno . '</svg>';
}
