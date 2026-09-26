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
 *
 * <p>{@code furniture}: se true, l'oggetto si puo' piazzare per terra (tasto destro su un blocco,
 * vedi {@code furniture.FurnitureListener}) invece di restare solo in mano/inventario — di
 * default false, va scelto voce per voce in items.yml. {@code furnitureSolid} conta solo quando
 * furniture e' true: se true, il posto occupato blocca davvero il movimento (un blocco LIGHT
 * invisibile di livello 0), altrimenti resta attraversabile — scelta per oggetto, non globale.
 * {@code furnitureShiftRequired} (default true) decide se il tasto destro deve avvenire con shift
 * premuto (per non entrare in conflitto con l'uso normale del blocco cliccato, es. un baule) o se
 * basta il click semplice — anche questa scelta e' per oggetto, non globale.
 */
public record ItemEntry(String id, String material, String name, List<String> lore, int customModelData,
                         boolean furniture, boolean furnitureSolid, boolean furnitureShiftRequired) {
}
