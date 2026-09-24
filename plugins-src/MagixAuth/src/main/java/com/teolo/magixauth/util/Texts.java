package com.teolo.magixauth.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Converte in {@link Component} il testo colorato in stile "&" che arriva da
 * {@code lang.Messages} (chi legge quei testi al cancello, li' e' gia' tradotto se serve).
 */
public final class Texts {

    /** Colori in stile "&": la stessa forma usata nei config, esadecimali compresi. */
    private static final LegacyComponentSerializer AMPERSAND =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private Texts() {}

    /** Il testo colorato, pronto da mandare al giocatore. */
    public static Component c(String text) {
        return AMPERSAND.deserialize(text);
    }
}
