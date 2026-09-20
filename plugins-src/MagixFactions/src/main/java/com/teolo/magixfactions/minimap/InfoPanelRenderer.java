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
    public static byte[] render(List<String> lines, byte textColor, byte bgColor) {
        byte[] out = new byte[128 * 128];
        java.util.Arrays.fill(out, bgColor);

        // Firma magica (riga 0): dice allo shader "questo e' il pannello info, agganciami sotto la minimap".
        out[0] = SIGN0;
        out[1] = SIGN1;
        out[2] = SIGN2;

        MapFont font = MinecraftFont.Font;
        int y = TOP_MARGIN;
        for (String raw : lines) {
            drawLine(out, font, stripCodes(raw), y, textColor);
            y += FONT_HEIGHT + LINE_SPACING;
            if (y + FONT_HEIGHT > 128) break; // niente spazio per altre righe
        }
        return out;
    }

    /** Disegna una riga di testo a partire da (LEFT_MARGIN, top), saltando i glifi non presenti nel font. */
    private static void drawLine(byte[] out, MapFont font, String text, int top, byte color) {
        int x = LEFT_MARGIN;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ') { x += 4; continue; } // spazio: avanza senza disegnare
            MapFont.CharacterSprite sprite;
            try {
                sprite = font.getChar(ch);
            } catch (Throwable t) {
                sprite = null;
            }
            if (sprite == null) { x += 4; continue; } // glifo sconosciuto: salta lasciando uno spazio
            int w = sprite.getWidth();
            int h = sprite.getHeight();
            if (x + w > 127) break; // fine riga: non sforare a destra
            for (int cy = 0; cy < h; cy++) {
                int py = top + cy;
                if (py < 0 || py >= 128) continue;
                for (int cx = 0; cx < w; cx++) {
                    if (!sprite.get(cy, cx)) continue;
                    int px = x + cx;
                    if (px < 0 || px >= 128) continue;
                    out[py * 128 + px] = color;
                }
            }
            x += w + 1; // spaziatura tra caratteri
        }
    }

    /** Toglie i codici colore Minecraft ({@code &x} e {@code §x}): il font mappa usa un colore unico. */
    private static String stripCodes(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < s.length()) {
                i++; // salta anche il carattere-codice successivo
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
