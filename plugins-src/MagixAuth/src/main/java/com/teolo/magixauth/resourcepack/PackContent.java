package com.teolo.magixauth.resourcepack;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Legge dal jar di MagixAuth tutto cio' che sta sotto {@code resourcepack/} — le schermate di
 * accesso e i tasti del tastierino OTP — e lo restituisce pronto da registrare nel resource pack
 * UNICO del server (vedi {@code hook.MagixPackHook} e il plugin MagixPack).
 *
 * <p>Nessun segnaposto da risolvere: questi file sono statici. L'elenco non si scrive a mano (come
 * {@code util.ConfigAlign} per i file yml): si guarda dentro il jar, cosi' un file aggiunto domani
 * finisce nel pacchetto senza dover toccare questa classe. {@code pack.mcmeta} NON si registra: lo
 * fornisce MagixPack, uguale per tutti i contributori.
 */
public final class PackContent {

    private static final String ROOT = "resourcepack/";

    private PackContent() {}

    public static Map<String, byte[]> build(JavaPlugin plugin) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String path : listInJar(plugin)) {
            try (InputStream in = PackContent.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) continue;
                out.put(path.substring(ROOT.length()), in.readAllBytes());
            }
        }
        return out;
    }

    private static List<String> listInJar(JavaPlugin plugin) throws IOException {
        List<String> out = new ArrayList<>();
        try {
            URI location = plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            File file = new File(location);
            if (!file.isFile()) return out; // avviato non impacchettato (sviluppo)
            try (JarFile jar = new JarFile(file)) {
                jar.stream()
                        .map(e -> e.getName())
                        .filter(n -> n.startsWith(ROOT) && !n.equals(ROOT) && !n.equals(ROOT + "pack.mcmeta"))
                        .forEach(out::add);
            }
        } catch (Exception e) {
            throw new IOException(e);
        }
        return out;
    }
}
