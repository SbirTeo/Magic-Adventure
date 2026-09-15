package com.teolo.magixessentials.util;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Il {@code Modules.yml} di CMI, letto — e quando serve corretto — da qui.
 *
 * <h2>Perche' esiste</h2>
 * Tablist, targhette sopra la testa, MOTD: sono cose che <b>non hanno un proprietario</b>, ce l'ha
 * chi ha scritto per ultimo. Finche' anche CMI le gestisce, i due si sovrascrivono a vicenda e il
 * risultato dipende dall'ordine di caricamento, cioe' dal caso. L'unica via pulita e' spegnere il
 * suo modulo — e per farlo bisogna saper leggere il suo file.
 *
 * <p>MagixEssentials nasce per assorbire cio' che oggi fa CMI: finche' dura la convivenza, questa
 * classe e' l'<b>unico punto</b> del plugin che sa dove tiene i suoi interruttori e come sono
 * scritti. Se un domani CMI sparisce, sparisce questo file e nient'altro.</p>
 *
 * <h2>Perche' si modifica una riga sola</h2>
 * Il file e' di un altro plugin, pieno di commenti suoi: riscriverlo con
 * {@link YamlConfiguration#save} vorrebbe dire restituirglielo senza commenti e riordinato. Qui si
 * cambia la <b>riga</b> dell'interruttore e nient'altro, dopo averne messo una copia di scorta
 * accanto — la stessa regola che {@link ConfigAlign} applica ai nostri file.
 *
 * <p>CMI il suo file lo legge all'avvio: la modifica si vede al <b>riavvio</b> del server, non
 * subito. Chi chiama lo dice nel log.</p>
 */
public final class CmiModules {

    /** Dove CMI tiene i suoi interruttori, a partire dalla cartella dei plugin. */
    private static final String PATH = "CMI/Settings/Modules.yml";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private CmiModules() {
    }

    /** Se CMI e' installato e acceso su questo server. */
    public static boolean installed() {
        return Bukkit.getPluginManager().getPlugin("CMI") != null;
    }

    /**
     * Lo stato di un modulo di CMI: {@code TRUE} acceso, {@code FALSE} spento, {@code null} se non
     * si sa (file assente, illeggibile, o nessuna delle chiavi cercate presente). Il {@code null} e'
     * un'informazione vera e va detta cosi': "non lo so" non e' "e' spento".
     *
     * @param names i nomi possibili della chiave: CMI ne cambia la grafia fra una versione e
     *              l'altra ({@code nameTag}, {@code nametags}...), e il confronto qui ignora
     *              maiuscole, trattini e trattini bassi.
     */
    public static Boolean enabled(JavaPlugin plugin, String... names) {
        File file = file(plugin);
        if (!file.isFile()) {
            return null;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isBoolean(key)) {
                continue;
            }
            String leaf = key.substring(key.lastIndexOf('.') + 1);
            if (matches(leaf, names)) {
                return yaml.getBoolean(key);
            }
        }
        return null;
    }

    /**
     * Spegne un modulo di CMI cambiando la sua riga nel file, con una copia di scorta accanto
     * ({@code Modules.yml.bak-<data>}). Torna {@code true} solo se ha cambiato davvero qualcosa:
     * un modulo gia' spento, una chiave che non c'e' o un file che non si riesce a scrivere
     * tornano {@code false}, senza far danni.
     */
    public static boolean disable(JavaPlugin plugin, String... names) {
        File file = file(plugin);
        if (!file.isFile()) {
            return false;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("[CMI] non sono riuscito a leggere " + PATH + ": " + e.getMessage());
            return false;
        }
        // Solo la riga dell'interruttore: chiave, due punti, il valore. Il resto del file — commenti
        // compresi — resta carattere per carattere com'era.
        Pattern row = Pattern.compile("^(\\s*)([A-Za-z0-9_\\-]+)(\\s*:\\s*)([Tt][Rr][Uu][Ee])(\\s*(?:#.*)?)$");
        int found = -1;
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = row.matcher(lines.get(i));
            if (m.matches() && matches(m.group(2), names)) {
                lines.set(i, m.group(1) + m.group(2) + m.group(3) + "false" + m.group(5));
                found = i;
                break;
            }
        }
        if (found < 0) {
            return false;   // gia' spento, o la chiave in questa versione si chiama in un altro modo
        }
        try {
            Files.copy(file.toPath(),
                    new File(file.getParentFile(), file.getName() + ".bak-" + LocalDateTime.now().format(STAMP)).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Nessuna copia, nessuna modifica: il file di un altro plugin non si tocca al buio.
            plugin.getLogger().warning("[CMI] non sono riuscito a fare la copia di scorta di " + PATH
                    + ": lascio il file com'e' (" + e.getMessage() + ").");
            return false;
        }
        try {
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("[CMI] non sono riuscito a scrivere " + PATH + ": " + e.getMessage());
            return false;
        }
        return true;
    }

    /** Il file degli interruttori di CMI, accanto alla nostra cartella dati. */
    public static File file(JavaPlugin plugin) {
        return new File(plugin.getDataFolder().getParentFile(), PATH);
    }

    /** Confronto fra nomi di chiave alla larga: maiuscole, trattini e trattini bassi non contano. */
    private static boolean matches(String key, String... names) {
        String flat = flatten(key);
        for (String name : names) {
            if (flat.equals(flatten(name))) {
                return true;
            }
        }
        return false;
    }

    private static String flatten(String text) {
        return text.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }
}
