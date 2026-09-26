package com.teolo.magixpack.glyph;

/**
 * How a bitmap font glyph of {@code imageRows} pixel rows is written in the font JSON so that it
 * shows {@code height} GUI pixels tall with its top {@code ascent} pixels above the baseline.
 *
 * <p>The client refuses a bitmap provider whose ascent is higher than its height (the glyph could
 * never sit higher than "bottom on the baseline"). To move a glyph further UP, transparent rows are
 * added at the bottom of the image ({@code padRows}): the visible part keeps its size and the
 * provider gets taller, so the same ascent becomes legal. A negative ascent (moving down) needs
 * nothing.
 */
public record BitmapFit(int height, int ascent, int padRows, double pixelScale) {

    /** @param imageRows  rows of the image cell as drawn (before any padding)
     *  @param wantHeight wanted on-screen height in GUI pixels (may be fractional: it is rounded)
     *  @param wantAscent wanted ascent in GUI pixels */
    public static BitmapFit of(int imageRows, double wantHeight, int wantAscent) {
        int h = Math.max(1, (int) Math.round(wantHeight));
        double k = (double) h / imageRows;
        if (wantAscent <= h) return new BitmapFit(h, wantAscent, 0, k);
        // Smallest padding that makes the ascent legal; among the next few, prefer one whose height
        // comes out whole, so that the drawn pixels keep exactly the scale asked for.
        int pad = 1;
        while ((int) Math.round((imageRows + pad) * k) < wantAscent && pad < 4096) pad++;
        for (int p = pad; p < pad + imageRows; p++) {
            double exact = (imageRows + p) * k;
            if (Math.abs(exact - Math.round(exact)) < 1e-6) { pad = p; break; }
        }
        int height = (int) Math.round((imageRows + pad) * k);
        return new BitmapFit(height, wantAscent, pad, (double) height / (imageRows + pad));
    }
}
