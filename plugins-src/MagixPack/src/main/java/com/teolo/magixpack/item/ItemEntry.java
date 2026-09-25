package com.teolo.magixpack.item;

import java.util.List;

/**
 * Una voce del catalogo {@code items.yml}: un oggetto custom con texture e modello propri (come
 * Oraxen), sopra un item base di Minecraft che decide solo le meccaniche (danno, durabilita',
 * impilabilita'...), mai l'aspetto.
 *
 * <p>{@code customModelData} e' assegnato da {@link ItemCatalog}, non scelto dallo staff (stesso
 * principio del codepoint dei glifi, vedi {@code glyph.GlyphCatalog}): serve al meccanismo
 * "vecchio" (predicate override sul modello vanilla dell'item base), tenuto INSIEME al componente
 * {@code item_model} per la massima compatibilita' — la stessa doppia strada che raccomanda Oraxen
 * stesso (item_properties + model_data_ids) quando non e' certo quale dei due il client risolva.
 */
public record ItemEntry(String id, String material, String name, List<String> lore, int customModelData) {
}
