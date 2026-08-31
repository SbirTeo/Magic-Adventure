package com.teolo.magixguard.sanctions;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Colori e messaggi, nello stile dei plugin Magix (vedi plugins-src/STILE-MAGIX.md):
 * viola = chi parla, verde = cosa puoi fare, grigio = cosa significa, rosso = non e' andata.
 */
public final class Text {

    /** Il cartellino davanti alle risposte. Non va nei pannelli, solo sui messaggi. */
    public static final String PREFISSO = "&#C046E8&lMagixGuard &8» &r";

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

    /** Messaggio con il cartellino davanti. */
    public static Component msg(String s) {
        return c(PREFISSO + s);
    }

    /** Riga di un pannello: nessun cartellino, altrimenti si ripete dodici volte in una scheda. */
    public static Component panel(String s) {
        return c(s);
    }

    /**
     * Sostituisce i segnaposto di un messaggio configurabile.
     * Le chiavi si passano a coppie: {@code sostituisci(testo, "{motivo}", motivo, ...)}.
     */
    public static String sostituisci(String testo, String... coppie) {
        String out = testo == null ? "" : testo;
        for (int i = 0; i + 1 < coppie.length; i += 2) {
            out = out.replace(coppie[i], coppie[i + 1] == null ? "" : coppie[i + 1]);
        }
        return out;
    }
}
