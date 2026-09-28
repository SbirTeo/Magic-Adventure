package com.teolo.magixfactions.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Scrolling text ("marquee") for placeholders: a window of {@code width} visible characters that
 * slides over the text from right to left, one character every step, and starts again after a gap.
 *
 * <p>The text arrives already colored (§ codes, hex as §x§R§R§G§G§B§B): every visible character
 * carries the formatting active at its position, so a character cut out of the window keeps its
 * color instead of inheriting the one of whatever came before it in the window.</p>
 */
public final class Marquee {

    private Marquee() {}

    /**
     * @param colored text with § codes
     * @param width   visible characters shown at once
     * @param gap     blank characters between the end of the text and its next start
     * @param step    how many characters the text has advanced so far (grows with time)
     */
    public static String window(String colored, int width, int gap, long step) {
        List<Character> chars = new ArrayList<>();
        List<String> formats = new ArrayList<>();
        String color = "";
        StringBuilder decorations = new StringBuilder();
        for (int i = 0; i < colored.length(); i++) {
            char c = colored.charAt(i);
            if (c == '§' && i + 1 < colored.length()) {
                char code = Character.toLowerCase(colored.charAt(i + 1));
                if (code == 'x' && i + 13 < colored.length()) {
                    color = colored.substring(i, i + 14);
                    decorations.setLength(0);
                    i += 13;
                } else if ("0123456789abcdefr".indexOf(code) >= 0) {
                    color = "§" + code;
                    decorations.setLength(0);
                    i++;
                } else if ("klmno".indexOf(code) >= 0) {
                    decorations.append('§').append(code);
                    i++;
                } else {
                    i++;
                }
                continue;
            }
            if (c == '\n') c = ' ';
            chars.add(c);
            formats.add(color + decorations);
        }
        if (chars.isEmpty() || width <= 0) return colored;
        for (int g = 0; g < Math.max(0, gap); g++) {
            chars.add(' ');
            formats.add("");
        }
        int n = chars.size();
        int start = (int) Math.floorMod(step, (long) n);
        StringBuilder out = new StringBuilder();
        String last = null;
        for (int k = 0; k < width; k++) {
            int idx = (start + k) % n;
            String f = formats.get(idx);
            char c = chars.get(idx);
            // A space needs no color; skipping it keeps the output short (scoreboard lines are long enough).
            if (c != ' ' && !f.equals(last)) {
                out.append(f.isEmpty() ? "§r" : f);
                last = f;
            }
            out.append(c);
        }
        return out.toString();
    }
}
