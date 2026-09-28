package com.teolo.magixpack.glyph;

/**
 * Una voce del catalogo {@code glyphs.yml}: un'icona custom via font, per chat/tablist (non per
 * gli oggetti: quelli hanno il loro modello vero, vedi {@code item.ItemEntry}). Il punto di
 * codice lo assegna {@link GlyphCatalog}, non e' scelto dallo staff: vedi la sua Javadoc.
 *
 * <p>{@code playerAvatar} ({@code type: player-avatar} in glyphs.yml): not an image of the pack but
 * the avatar of a player (the face of the skin), drawn by {@code avatar.AvatarService} with a font
 * of its own; no texture, no codepoint (-1).
 *
 * <p>{@code scale}, {@code offsetX}, {@code offsetY} are the staff's per-glyph adjustments (size,
 * GUI pixels to the right, GUI pixels up); {@code fit} is what they become in the font JSON.
 *
 * <p>{@code priority} (staff, 0-100, default 0, decimals allowed) decides who is drawn on top when several glyphs are
 * stacked in the same spot ({@link GlyphCatalog#stackLegacy}): higher = on top. {@code advance} is
 * how far the glyph moves the text on, in GUI pixels — computed, needed to step back over it.
 */
public record GlyphEntry(String id, int codepoint, boolean playerAvatar, double scale, double offsetX,
                         int offsetY, int imageRows, BitmapFit fit, double priority, double advance) {

    /** Empty lines to leave above the glyph so that it does not cover the text before it: a normal
     *  letter has ascent 7, anything higher sticks out by the difference. */
    public int emptyLinesAbove(int lineHeight) {
        return (Math.max(0, fit.ascent() - 7) + lineHeight - 1) / lineHeight;
    }
}
