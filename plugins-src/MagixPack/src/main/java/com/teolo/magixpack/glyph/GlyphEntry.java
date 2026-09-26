package com.teolo.magixpack.glyph;

/**
 * Una voce del catalogo {@code glyphs.yml}: un'icona custom via font, per chat/tablist (non per
 * gli oggetti: quelli hanno il loro modello vero, vedi {@code item.ItemEntry}). Il punto di
 * codice lo assegna {@link GlyphCatalog}, non e' scelto dallo staff: vedi la sua Javadoc.
 */
public record GlyphEntry(String id, int height, int ascent, int codepoint) {
}
