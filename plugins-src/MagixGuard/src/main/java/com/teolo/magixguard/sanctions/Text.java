package com.teolo.magixguard.sanctions;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Colori e messaggi, nello stile dei plugin Magix (vedi plugins-src/STILE-MAGIX.md):
 * viola = chi parla, verde = cosa puoi fare, grigio = cosa significa, rosso = non e' andata.
 */
public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private Text() {
    }

    /** Da testo con i codici colore a Component. */
    public static Component c(String s) {
        return LEGACY.deserialize(s == null ? "" : s);
    }

    /** Messaggio, risposta a un comando. Il cartellino del plugin non c'e' piu' (vedi CLAUDE.md). */
    public static Component msg(String s) {
        return c(s);
    }

    /** Riga di un pannello. */
    public static Component panel(String s) {
        return c(s);
    }

    /**
     * Sostituisce i segnaposto di un messaggio configurabile.
     * Le chiavi si passano a coppie: {@code sostituisci(testo, "{motivo}", motivo, ...)}.
     */
    public static String replace(String text, String... coppie) {
        String out = text == null ? "" : text;
        for (int i = 0; i + 1 < coppie.length; i += 2) {
            out = out.replace(coppie[i], coppie[i + 1] == null ? "" : coppie[i + 1]);
        }
        return out;
    }
}
