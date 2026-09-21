package com.teolo.magixfactions.minimap;

import org.bukkit.map.MapFont;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MinecraftFont;

import java.awt.Color;
import java.util.List;

/**
 * "Cuoce" le righe di testo del PANNELLO INFO (orologio, coordinate, info personalizzabili) nei 128x128
 * pixel di una mappa finta, esattamente come {@link com.teolo.magixfactions.map.MapService#renderPaletteWithHeader}
 * fa per la minimap. La differenza e' solo la FIRMA magica nei primi 3 pixel: lo shader del pack la
 * riconosce e piazza QUESTA mappa come una striscia sotto la minimap (vedi text.vsh, ramo {@code custom==3}),
 * invece che al centro della minimap.
 *
 * <p><b>Testo → pixel:</b> si usa il font mappa vanilla ({@link MinecraftFont}, 8px), lo stesso che
 * Minecraft usa per il testo scritto sulle mappe-item. Il testo e' gia' RISOLTO da PlaceholderAPI dal
 * chiamante ({@code MinimapManager}), quindi qui arriva testo semplice; i codici colore Minecraft
 * ({@code &x}/{@code §x}) vengono tolti (il font mappa non colora per-carattere: il colore del testo e'
 * uno solo, scelto da config) e i caratteri che il font non conosce vengono saltati.
 *
 * <p><b>Perche' una mappa a parte e non la stessa della minimap:</b> un item frame mostra UNA mappa, e un
 * quad shader diventa UN rettangolo a schermo — non si puo' piegare in una "L". Minimap e pannello sono
 * due rettangoli distinti a schermo, quindi servono due mappe (due quad).
 */
public final class InfoPanelRenderer {

    private InfoPanelRenderer() {}

    /** Altezza del font mappa vanilla (px). */
    private static final int FONT_HEIGHT = 8;
    /** Spazio verticale tra una riga e l'altra (px). */
    private static final int LINE_SPACING = 1;
    /** Righe di header in cima (nascoste dallo shader): riga 0 = firma, riga 1 = margine. */
    private static final int TOP_MARGIN = 2;
    /** Margine sinistro del testo (px). */
    private static final int LEFT_MARGIN = 2;
    /** Margine in fondo (px). */
    private static final int BOTTOM_MARGIN = 1;

    // Firma magica del pannello: byte palette 18/49/4 -> RGB 0xFF0000/0x3737DC/0x597D27 (MK0/MK2/MK1),
    // un ordine DISTINTO da quello della minimap (18/4/49 = MK0/MK1/MK2) e della mappa-item (49/4/18 =
    // MK2/MK1/MK0). Lo shader (text.vsh) sceglie il ramo in base a questa terna. DEVE combaciare col vsh.
    private static final byte SIGN0 = 18; // MK0 (0xFF0000)
    private static final byte SIGN1 = 49; // MK2 (0x3737DC)
    private static final byte SIGN2 = 4;  // MK1 (0x597D27)

    // Colore CHIAVE per lo sfondo trasparente: le mappe non hanno trasparenza, quindi lo sfondo e' per
    // forza un colore pieno. Con lo sfondo "trasparente" lo riempiamo di questo colore-sentinella e lo
    // shader (text.fsh, custom==3) SCARTA (discard) i pixel che gli corrispondono -> resta solo il testo
    // "che galleggia". Deve essere un colore che non capita nel testo/ombra: magenta puro.
    private static final Color TRANSPARENT_KEY = new Color(255, 0, 255);

    /** Byte palette del colore-chiave dello sfondo trasparente. */
    public static byte transparentKeyByte() {
        return MapPalette.matchColor(TRANSPARENT_KEY);
    }

    /** Colore RGB EFFETTIVO reso dal byte-chiave (puo' differire dal magenta esatto: la palette mappa al
     *  piu' vicino). Lo shader confronta con QUESTO, non col magenta teorico, cosi' il discard e' esatto. */
    public static Color transparentKeyRendered() {
        return MapPalette.getColor(transparentKeyByte());
    }

    /** Lo sfondo e' "trasparente"? (valore vuoto / "transparent" / "none" nel config). */
    public static boolean isTransparentBg(String s) {
        if (s == null || s.isBlank()) return true;
        String t = s.trim();
        return t.equalsIgnoreCase("transparent") || t.equalsIgnoreCase("none");
    }

    /**
     * Righe di texture usate dal pannello per {@code lineCount} righe di testo. DEVE combaciare col valore
     * {@code __PANEL_ROWS__} sostituito nello shader (vedi {@code ResourcePackContent}): shader e renderer
     * devono concordare sull'altezza, altrimenti la striscia a schermo e il contenuto non coincidono.
     * Cambiare il NUMERO di righe richiede quindi un riavvio (rigenera il pack); cambiarne il TESTO e' live.
     */
    public static int panelRows(int lineCount) {
        int n = Math.max(1, lineCount);
        int rows = TOP_MARGIN + n * (FONT_HEIGHT + LINE_SPACING) + BOTTOM_MARGIN;
        return Math.max(FONT_HEIGHT + TOP_MARGIN, Math.min(128, rows));
    }

    /** Byte palette piu' vicino a un colore "#RRGGBB" / "RRGGBB" (fallback: bianco). */
    public static byte colorByte(String hex, Color fallback) {
        Color c = parseHex(hex, fallback);
        return MapPalette.matchColor(c);
    }

    private static Color parseHex(String s, Color fallback) {
        if (s == null || s.isBlank()) return fallback;
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() == 6 && t.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) {
            return new Color(Integer.parseInt(t.substring(0, 2), 16),
                    Integer.parseInt(t.substring(2, 4), 16),
                    Integer.parseInt(t.substring(4, 6), 16));
        }
        return fallback;
    }

    /**
     * Costruisce i 128x128 byte-palette del pannello: sfondo pieno, firma magica nei primi 3 pixel, e le
     * righe di testo (gia' risolte) disegnate col font mappa vanilla nel colore scelto.
     *
     * @param lines     righe gia' risolte da PlaceholderAPI (i codici colore vengono tolti)
     * @param textColor byte palette del testo
     * @param bgColor   byte palette dello sfondo
     */
    public static byte[] render(List<String> lines, byte textColor, byte bgColor, boolean transparent) {
        byte[] out = new byte[128 * 128];
        // Sfondo: pieno del colore scelto, oppure il colore-chiave che lo shader scarta (trasparente).
        java.util.Arrays.fill(out, transparent ? transparentKeyByte() : bgColor);

        // Firma magica (riga 0): dice allo shader "questo e' il pannello info, agganciami sotto la minimap".
        out[0] = SIGN0;
        out[1] = SIGN1;
        out[2] = SIGN2;

        // Ombra del testo: col fondo trasparente il testo galleggia sul mondo, serve un contorno scuro per
        // restare leggibile su qualunque sfondo. La disegniamo come una copia del testo spostata di 1px in
        // basso a destra, in nero (byte palette del nero). Sul fondo pieno e' innocua (sparisce nel colore).
        byte shadow = MapPalette.matchColor(java.awt.Color.BLACK);

        MapFont font = MinecraftFont.Font;
        int y = TOP_MARGIN;
        for (String raw : lines) {
            String text = raw == null ? "" : raw;
            // Ombra PRIMA (sempre nera, ignora i colori del testo), poi il testo colorato sopra. I due passi
            // consumano i codici allo stesso modo, quindi avanzano di x identico e restano allineati.
            if (transparent) drawLine(out, font, text, LEFT_MARGIN + 1, y + 1, textColor, true, shadow);
            drawLine(out, font, text, LEFT_MARGIN, y, textColor, false, shadow);
            y += FONT_HEIGHT + LINE_SPACING;
            if (y + FONT_HEIGHT > 128) break; // niente spazio per altre righe
        }
        return out;
    }

    /**
     * Disegna una riga a partire da (left, top) interpretando i codici Minecraft:
     * <ul>
     *   <li><b>Colore</b>: {@code &0}-{@code &f} (i 16 classici), {@code &r} (torna al default), ed RGB
     *       esadecimale in tre sintassi — {@code &#RRGGBB}, {@code <#RRGGBB>}, legacy {@code &x&R&R&G&G&B&B}.</li>
     *   <li><b>Formato</b>: {@code &l} grassetto, {@code &o} corsivo, {@code &n} sottolineato, {@code &m}
     *       barrato — resi "a mano" nei pixel (il font mappa non li ha nativi). {@code &k} (offuscato) si
     *       consuma ma non si rende. Come in vanilla, un codice colore azzera i formati attivi.</li>
     * </ul>
     * In {@code shadowPass} tutto viene interpretato UGUALE (per allineare l'ombra al testo) ma il colore
     * resta quello dell'ombra. I glifi assenti dal font si saltano lasciando uno spazio.
     */
    private static void drawLine(byte[] out, MapFont font, String text, int left, int top,
                                 byte defColor, boolean shadowPass, byte shadowColor) {
        int x = left;
        byte cur = shadowPass ? shadowColor : defColor;
        boolean bold = false, italic = false, under = false, strike = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            boolean amp = ch == '&' || ch == '§';

            // RGB legacy &x&R&R&G&G&B&B
            if (amp && i + 13 < n && Character.toLowerCase(text.charAt(i + 1)) == 'x' && isLegacyHex(text, i)) {
                if (!shadowPass) cur = MapPalette.matchColor(parseHex("" + text.charAt(i + 3) + text.charAt(i + 5)
                        + text.charAt(i + 7) + text.charAt(i + 9) + text.charAt(i + 11) + text.charAt(i + 13), Color.WHITE));
                bold = italic = under = strike = false; i += 13; continue;
            }
            // RGB &#RRGGBB
            if (amp && i + 7 < n && text.charAt(i + 1) == '#' && isHex6(text.substring(i + 2, i + 8))) {
                if (!shadowPass) cur = MapPalette.matchColor(parseHex(text.substring(i + 2, i + 8), Color.WHITE));
                bold = italic = under = strike = false; i += 7; continue;
            }
            // RGB <#RRGGBB>
            if (ch == '<' && i + 8 < n && text.charAt(i + 1) == '#'
                    && isHex6(text.substring(i + 2, i + 8)) && text.charAt(i + 8) == '>') {
                if (!shadowPass) cur = MapPalette.matchColor(parseHex(text.substring(i + 2, i + 8), Color.WHITE));
                bold = italic = under = strike = false; i += 8; continue;
            }
            // Codici a lettera singola
            if (amp && i + 1 < n) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                Byte cb = codeColorByte(code);
                if (cb != null) { if (!shadowPass) cur = cb; bold = italic = under = strike = false; i++; continue; }
                boolean handled = true;
                switch (code) {
                    case 'r' -> { if (!shadowPass) cur = defColor; bold = italic = under = strike = false; }
                    case 'l' -> bold = true;
                    case 'o' -> italic = true;
                    case 'n' -> under = true;
                    case 'm' -> strike = true;
                    case 'k' -> { /* offuscato: non supportato, solo consumato */ }
                    default -> handled = false; // codice sconosciuto: '&' e' testo normale
                }
                if (handled) { i++; continue; }
            }

            // Disegno del carattere/spazio, con l'avanzamento (adv) che tiene conto del grassetto.
            int adv;
            if (ch == ' ') {
                adv = 4;
            } else {
                MapFont.CharacterSprite sprite;
                try { sprite = font.getChar(ch); } catch (Throwable t) { sprite = null; }
                if (sprite == null) {
                    adv = 4;
                } else {
                    int w = sprite.getWidth();
                    int h = sprite.getHeight();
                    int drawn = w + (bold ? 1 : 0);
                    if (x + drawn > 127) break; // fine riga
                    for (int cy = 0; cy < h; cy++) {
                        int shear = italic ? (h - 1 - cy) / 3 : 0; // corsivo: righe alte spostate a destra
                        for (int cx = 0; cx < w; cx++) {
                            if (!sprite.get(cy, cx)) continue;
                            putPixel(out, x + cx + shear, top + cy, cur);
                            if (bold) putPixel(out, x + cx + 1 + shear, top + cy, cur); // grassetto: +1px
                        }
                    }
                    adv = drawn + 1; // +1 di spaziatura
                }
            }
            if (under)  for (int cx = 0; cx < adv; cx++) putPixel(out, x + cx, top + FONT_HEIGHT, cur);     // sottolineato
            if (strike) for (int cx = 0; cx < adv; cx++) putPixel(out, x + cx, top + FONT_HEIGHT / 2, cur); // barrato
            x += adv;
            if (x > 127) break;
        }
    }

    /** Scrive un pixel nella palette, ignorando le coordinate fuori dai 128x128. */
    private static void putPixel(byte[] out, int px, int py, byte color) {
        if (px < 0 || px >= 128 || py < 0 || py >= 128) return;
        out[py * 128 + px] = color;
    }

    /** Vero se a partire da i c'e' {@code &x&R&R&G&G&B&B} (6 coppie "&"+cifra-hex dopo "&x"). */
    private static boolean isLegacyHex(String t, int i) {
        for (int k = 0; k < 6; k++) {
            char sep = t.charAt(i + 2 + k * 2);
            char hx = t.charAt(i + 3 + k * 2);
            if (sep != '&' && sep != '§') return false;
            if (Character.digit(hx, 16) < 0) return false;
        }
        return true;
    }

    /** Vero se {@code s} sono 6 cifre esadecimali. */
    private static boolean isHex6(String s) {
        return s.length() == 6 && s.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }

    /** Byte palette per un codice colore Minecraft {@code 0}-{@code 9}/{@code a}-{@code f}, altrimenti null. */
    private static Byte codeColorByte(char c) {
        Color col = switch (c) {
            case '0' -> new Color(0, 0, 0);        case '1' -> new Color(0, 0, 170);
            case '2' -> new Color(0, 170, 0);      case '3' -> new Color(0, 170, 170);
            case '4' -> new Color(170, 0, 0);      case '5' -> new Color(170, 0, 170);
            case '6' -> new Color(255, 170, 0);    case '7' -> new Color(170, 170, 170);
            case '8' -> new Color(85, 85, 85);     case '9' -> new Color(85, 85, 255);
            case 'a' -> new Color(85, 255, 85);    case 'b' -> new Color(85, 255, 255);
            case 'c' -> new Color(255, 85, 85);    case 'd' -> new Color(255, 85, 255);
            case 'e' -> new Color(255, 255, 85);   case 'f' -> new Color(255, 255, 255);
            default -> null;
        };
        return col == null ? null : MapPalette.matchColor(col);
    }
}
