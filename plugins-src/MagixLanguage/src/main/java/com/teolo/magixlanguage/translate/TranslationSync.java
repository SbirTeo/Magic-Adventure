package com.teolo.magixlanguage.translate;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Copia il testo che i giocatori vedono in gioco — {@code messages.yml} e gli altri file elencati
 * in {@code translations.files} — dalla cartella dati di ciascun plugin elencato in
 * {@code translations.plugins} dentro {@code plugins/MagixLanguage/translations/<Plugin>/}, un
 * file per lingua ({@code it.yml}, {@code en.yml}, {@code es.yml}, {@code de.yml}...).
 *
 * <h2>Perche' l'italiano e' la lingua SORGENTE</h2>
 * Per regola di progetto ogni {@code messages.yml} del server e' scritto in italiano (i VALORI, non
 * le chiavi: vedi CLAUDE.md). {@code it.yml} quindi non e' una traduzione: e' uno SPECCHIO di quello
 * che il plugin sta usando davvero in questo momento, riscritto per intero ad ogni sincronizzazione.
 * Chi vuole cambiare un testo in italiano lo cambia nel {@code messages.yml} del plugin originale,
 * non qui: questo file si limiterebbe a ricopiarlo alla sincronizzazione successiva.
 *
 * <h2>Come si comportano gli altri file lingua</h2>
 * {@code en.yml}, {@code es.yml}, {@code de.yml} sono lavoro dello STAFF: una chiave nuova (presente
 * in {@code it.yml} ma non ancora nel file lingua) viene aggiunta con il valore italiano come
 * segnaposto, cosi' chi traduce parte da un testo vero e non da una chiave vuota. Una chiave gia'
 * tradotta — cioe' il cui valore e' DIVERSO da quello italiano corrispondente — non viene mai
 * toccata. Le chiavi sparite dal sorgente vengono tolte (con una copia di sicurezza prima), perche'
 * un file di traduzione che il codice non legge piu' e' solo un residuo.
 *
 * <p>Non lancia mai: un plugin non installato, un file mancante o un file illeggibile vengono
 * saltati e finiscono nel log, non in un'eccezione che blocchi l'avvio del server.</p>
 */
public final class TranslationSync {

    /** Lingua in cui e' scritto ogni messages.yml del server: vedi la classe, non e' configurabile. */
    private static final String SOURCE_LANGUAGE = "it";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_BACKUPS = 10;

    private final JavaPlugin plugin;
    private final Logger log;

    public TranslationSync(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public record Result(int pluginsScanned, int keysAdded, int keysRemoved, int pendingTranslations) {}

    /** Va chiamato fuori dal thread principale: legge e scrive parecchi piccoli file. */
    public Result run() {
        List<String> pluginNames = plugin.getConfig().getStringList("translations.plugins");
        List<String> fileNames = plugin.getConfig().getStringList("translations.files");
        List<String> targetLanguages = new ArrayList<>(plugin.getConfig().getStringList("supported-languages"));
        targetLanguages.remove(SOURCE_LANGUAGE);

        File pluginsFolder = plugin.getDataFolder().getParentFile();
        File translationsRoot = new File(plugin.getDataFolder(), "translations");

        int scanned = 0, added = 0, removed = 0;
        // lingua -> righe "Plugin: chiave" ancora identiche al testo italiano, quindi non tradotte.
        Map<String, List<String>> pending = new LinkedHashMap<>();
        for (String lang : targetLanguages) pending.put(lang, new ArrayList<>());

        for (String pluginName : pluginNames) {
            Map<String, Object> source = readSourceText(pluginsFolder, pluginName, fileNames);
            if (source.isEmpty()) {
                continue; // plugin non installato, o nessuno dei file configurati esiste
            }
            scanned++;
            File catalogDir = new File(translationsRoot, pluginName);
            catalogDir.mkdirs();

            writeMirror(new File(catalogDir, SOURCE_LANGUAGE + ".yml"), source);

            for (String lang : targetLanguages) {
                File target = new File(catalogDir, lang + ".yml");
                Map<String, Object> existing = target.isFile() ? flattenFile(target) : new LinkedHashMap<>();
                SyncOutcome outcome = merge(source, existing);
                if (outcome.changed()) {
                    writeCatalog(target, outcome.result, pluginName, lang);
                }
                added += outcome.added;
                removed += outcome.removed;
                for (String key : outcome.stillUntranslated) {
                    pending.get(lang).add(pluginName + ": " + key);
                }
            }
        }

        int pendingTotal = writePendingReports(translationsRoot, pending);
        log.info("MagixLanguage: sincronizzazione completata (" + scanned + " plugin, " + added
                + " chiavi nuove, " + removed + " rimosse, " + pendingTotal + " in attesa di traduzione).");
        return new Result(scanned, added, removed, pendingTotal);
    }

    // ------------------------------------------------------------- lettura del sorgente

    /** Testo (stringhe e liste di stringhe) di tutti i file configurati, per un plugin, unito in un'unica mappa. */
    private Map<String, Object> readSourceText(File pluginsFolder, String pluginName, List<String> fileNames) {
        Map<String, Object> merged = new LinkedHashMap<>();
        File pluginFolder = new File(pluginsFolder, pluginName);
        if (!pluginFolder.isDirectory()) {
            return merged;
        }
        for (String fileName : fileNames) {
            File f = new File(pluginFolder, fileName);
            if (!f.isFile()) {
                continue;
            }
            try {
                merged.putAll(flattenFile(f));
            } catch (Exception e) {
                log.warning("MagixLanguage: impossibile leggere " + pluginName + "/" + fileName + " (" + e + ").");
            }
        }
        return merged;
    }

    /**
     * Il catalogo di un file di traduzione gia' sincronizzato (o di un messages.yml originale),
     * appiattito in chiave.puntata -> valore. Usato da {@link com.teolo.magixlanguage.MagixLanguage}
     * per rispondere alle richieste di traduzione senza ricaricare il file ad ogni chiamata.
     */
    public static Map<String, Object> loadFlatCatalog(File f) {
        return f.isFile() ? flattenFile(f) : Map.of();
    }

    /** Appiattisce un file yml in chiave.puntata -> valore, tenendo solo TESTO (stringhe e liste di stringhe). */
    private static Map<String, Object> flattenFile(File f) {
        Map<String, Object> out = new LinkedHashMap<>();
        flattenSection(YamlConfiguration.loadConfiguration(f), "", out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flattenSection(ConfigurationSection section, String prefix, Map<String, Object> out) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                flattenSection(child, path, out);
                continue;
            }
            Object value = section.get(key);
            if (value instanceof String s) {
                out.put(path, s);
            } else if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
                out.put(path, new ArrayList<>((List<String>) list));
            }
            // numeri, booleani, colori RGB: non sono testo da tradurre, si ignorano.
        }
    }

    // ------------------------------------------------------------- unione

    private record SyncOutcome(Map<String, Object> result, int added, int removed, List<String> stillUntranslated) {
        boolean changed() { return added > 0 || removed > 0; }
    }

    private static SyncOutcome merge(Map<String, Object> source, Map<String, Object> existing) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> stillUntranslated = new ArrayList<>();
        int added = 0;
        for (Map.Entry<String, Object> e : source.entrySet()) {
            String key = e.getKey();
            Object sourceValue = e.getValue();
            if (existing.containsKey(key)) {
                Object currentValue = existing.get(key);
                result.put(key, currentValue);
                if (currentValue.equals(sourceValue)) {
                    stillUntranslated.add(key); // presente ma mai stato toccato da chi traduce
                }
            } else {
                result.put(key, sourceValue); // segnaposto: parte gia' dal testo italiano
                stillUntranslated.add(key);
                added++;
            }
        }
        Set<String> stale = new LinkedHashSet<>(existing.keySet());
        stale.removeAll(source.keySet());
        return new SyncOutcome(result, added, stale.size(), stillUntranslated);
    }

    // ------------------------------------------------------------- scrittura

    private void writeMirror(File file, Map<String, Object> source) {
        String body = toYaml(source);
        String header = "# Specchio automatico di " + SOURCE_LANGUAGE + " (il testo italiano davvero in uso su "
                + "questo server). NON si modifica qui: si cambia nel messages.yml del plugin originale e si "
                + "rilancia la sincronizzazione (/language sync). Rigenerato ad ogni sincronizzazione.\n";
        writeIfChanged(file, header + body);
    }

    private void writeCatalog(File file, Map<String, Object> result, String pluginName, String lang) {
        String body = toYaml(result);
        String header = "# Traduzione (" + lang + ") dei messaggi di " + pluginName + ". Le chiavi nuove arrivano "
                + "qui col testo italiano come segnaposto: cercale in translations/PENDING-" + lang + ".txt e "
                + "sostituisci il VALORE (mai il nome della chiave a sinistra dei due punti). Questo file viene "
                + "riscritto ad ogni sincronizzazione: solo i VALORI sono al sicuro, eventuali commenti aggiunti "
                + "a mano vengono persi.\n";
        writeIfChanged(file, header + body);
    }

    private String toYaml(Map<String, Object> flat) {
        YamlConfiguration yaml = new YamlConfiguration();
        // Ordinato per chiave: un diff leggibile fra due sincronizzazioni, invece dell'ordine
        // (non garantito) con cui i plugin dichiarano le sezioni.
        for (Map.Entry<String, Object> e : new TreeMap<>(flat).entrySet()) {
            yaml.set(e.getKey(), e.getValue());
        }
        return yaml.saveToString();
    }

    private void writeIfChanged(File file, String newText) {
        try {
            if (file.isFile()) {
                String current = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                if (current.equals(newText)) {
                    return; // niente di cambiato: non si tocca ne' si sposta il file
                }
                if (!backup(file)) {
                    return; // niente scrittura senza una copia di sicurezza riuscita
                }
            }
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            Files.writeString(file.toPath(), newText, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("MagixLanguage: impossibile scrivere " + file + " (" + e + "). File lasciato com'era.");
        }
    }

    /** Come ConfigAlign: copia col timestamp prima di ogni scrittura, tenendo solo le ultime {@value #MAX_BACKUPS}. */
    private boolean backup(File file) {
        try {
            String stamp = LocalDateTime.now().format(STAMP);
            File copy = new File(file.getParentFile(), file.getName() + ".bak-" + stamp);
            Files.copy(file.toPath(), copy.toPath());
            pruneBackups(file);
            return true;
        } catch (IOException e) {
            log.warning("MagixLanguage: copia di sicurezza di " + file + " fallita (" + e + "): file NON toccato.");
            return false;
        }
    }

    private void pruneBackups(File original) {
        File dir = original.getParentFile();
        File[] backups = dir == null ? null : dir.listFiles((d, n) -> n.startsWith(original.getName() + ".bak-"));
        if (backups == null || backups.length <= MAX_BACKUPS) {
            return;
        }
        java.util.Arrays.sort(backups, java.util.Comparator.comparing(File::getName));
        for (int i = 0; i < backups.length - MAX_BACKUPS; i++) {
            backups[i].delete();
        }
    }

    // ------------------------------------------------------------- rapporto per lo staff

    /** Un file per lingua, rigenerato per intero ad ogni sincronizzazione: sempre lo stato vero, mai accumulo. */
    private int writePendingReports(File translationsRoot, Map<String, List<String>> pendingByLang) {
        int total = 0;
        for (Map.Entry<String, List<String>> e : pendingByLang.entrySet()) {
            List<String> lines = e.getValue();
            total += lines.size();
            File report = new File(translationsRoot, "PENDING-" + e.getKey() + ".txt");
            try {
                if (lines.isEmpty()) {
                    Files.deleteIfExists(report.toPath());
                    continue;
                }
                String text = "# Chiavi ancora col testo italiano come segnaposto, lingua " + e.getKey() + ".\n"
                        + "# Rigenerato ad ogni /language sync: non si modifica a mano.\n"
                        + String.join("\n", lines) + "\n";
                Files.writeString(report.toPath(), text, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                log.warning("MagixLanguage: impossibile scrivere " + report + " (" + ex + ").");
            }
        }
        return total;
    }
}
