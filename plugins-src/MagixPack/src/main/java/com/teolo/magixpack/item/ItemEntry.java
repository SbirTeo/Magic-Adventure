package com.teolo.magixpack.item;

import java.util.List;

/**
 * Una voce del catalogo {@code items.yml}: un oggetto custom con texture e modello propri (come
 * Oraxen), sopra un item base di Minecraft che decide solo le meccaniche (danno, durabilita',
 * impilabilita'...), mai l'aspetto.
 */
public record ItemEntry(String id, String material, String name, List<String> lore) {
}
