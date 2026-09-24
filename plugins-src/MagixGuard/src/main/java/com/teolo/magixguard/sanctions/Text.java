package com.teolo.magixguard.sanctions;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Colori e messaggi, nello stile dei plugin Magix (vedi plugins-src/STILE-MAGIX.md):
 * viola = chi parla, verde = cosa puoi fare, grigio = cosa significa, rosso = non e' andata.
 */
public final class Text {

    /** Il valore di default, usato finche' {@link #load} non ha ancora letto messages.yml. */
    private static final String DEFAULT_PREFISSO = "&#C046E8&lMagixGuard &8» &r";

    /** Il cartellino davanti alle risposte. Non va nei pannelli, solo sui messaggi. */
    private static String prefisso = DEFAULT_PREFISSO;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private Text() {
    }

    /**
     * Rilegge il cartellino da messages.yml. Va chiamato all'avvio e a ogni reload, cosi' il
     * prefisso si puo' cambiare sul server senza ricompilare (vedi plugins-src/STILE-MAGIX.md).
     */
    public static void load(JavaPlugin plugin) {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        if (!f.exists()) plugin.saveResource("messages.yml", false);
        prefisso = YamlConfiguration.loadConfiguration(f).getString("prefix", DEFAULT_PREFISSO);
    }

    /** Da testo con i codici colore a Component. */
    public static Component c(String s) {
        return LEGACY.deserialize(s == null ? "" : s);
    }

    /** Messaggio con il cartellino davanti. */
    public static Component msg(String s) {
        return c(prefisso + s);
    }

    /** Riga di un pannello: nessun cartellino, altrimenti si ripete dodici volte in una scheda. */
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
