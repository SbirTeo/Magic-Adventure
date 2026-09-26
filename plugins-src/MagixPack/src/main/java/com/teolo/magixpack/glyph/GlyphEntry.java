package com.teolo.magixpack.glyph;

/**
 * Una voce del catalogo {@code glyphs.yml}: un'icona custom via font, per chat/tablist (non per
 * gli oggetti: quelli hanno il loro modello vero, vedi {@code item.ItemEntry}). Il punto di
 * codice lo assegna {@link GlyphCatalog}, non e' scelto dallo staff: vedi la sua Javadoc.
 *
 * <p>{@code playerAvatar} ({@code type: player-avatar} in glyphs.yml): not an image of the pack but
 * the avatar of a player (the face of the skin), drawn by {@code avatar.AvatarService} with its own font
 * ({@code magixpack:avatar}); no texture, no codepoint (-1).
 */
public record GlyphEntry(String id, int height, int ascent, int codepoint, boolean playerAvatar) {
}
