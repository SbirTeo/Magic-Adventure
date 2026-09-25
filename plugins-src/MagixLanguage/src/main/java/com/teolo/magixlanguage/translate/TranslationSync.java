package com.teolo.magixlanguage.translate;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Copia il testo che i giocatori vedono in gioco — {@code messages.yml} e gli altri file elencati
 * in {@code translations.files} — dalla cartella dati di ciascun plugin elencato in
 * {@code translations.plugins} dentro {@code plugins/MagixLanguage/translations/<Plugin>/}, e lo
 * TRADUCE in automatico (vedi {@link Translator}) in ciascuna delle altre lingue supportate.
 *
 * <h2>Perche' l'italiano e' la lingua SORGENTE</h2>
 * Per regola di progetto ogni {@code messages.yml} del server e' scritto in italiano (i VALORI, non
 * le chiavi: vedi CLAUDE.md). {@code it.yml} quindi non e' una traduzione: e' uno SPECCHIO di quello
 * che il plugin sta usando davvero in questo momento, riscritto per intero ad ogni sincronizzazione.
 * Chi vuole cambiare un testo in italiano lo cambia nel {@code messages.yml} del plugin originale,
 * non qui: questo file si limiterebbe a ricopiarlo alla sincronizzazione successiva.
 *
 * <h2>Come funzionano le altre lingue: automatico + correzioni</h2>
 * {@code en.yml}, {@code es.yml}, {@code de.yml} sono interamente AUTOMATICI: rigenerati per intero
 * a ogni sincronizzazione, ritraducendo da capo ogni chiave il cui testo italiano e' cambiato dalla
 * volta prima (una cache tiene traccia di cosa e' stato tradotto e da quale testo, cosi' le chiavi
 * INVARIATE non vengono ritradotte ad ogni riavvio). Un cambio di colore o di formattazione nel
 * {@code messages.yml} originale arriva quindi da solo nella lingua tradotta, nella stessa forma.
 * <p>
 * Chi vuole CORREGGERE una traduzione a mano, senza che la sincronizzazione successiva la
 * sovrascriva, la mette in {@code <lingua>-overrides.yml} (stessa cartella, stessa chiave): quel
 * file non viene mai letto ne' modificato dalla traduzione automatica, solo COPIATO sopra di essa —
 * una chiave li' vince sempre, e resta li' finche' qualcuno non la toglie.
 *
 * <p>Non lancia mai: un plugin non installato, un file mancante, una traduzione fallita (rete,
 * limite del servizio) — tutto finisce nel log e nel testo italiano di ripiego, mai in
 * un'eccezione che blocchi l'avvio del server.</p>
 */
public final class TranslationSync {

    /** Lingua in cui e' scritto ogni messages.yml del server: vedi la classe, non e' configurabile. */
    private static final String SOURCE_LANGUAGE = "it";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_BACKUPS = 10;

    /** Un tentativo di traduzione al giorno, qualunque cosa succeda: vedi {@link #alreadyAttemptedToday()}. */
    private static final String LAST_ATTEMPT_FILE = "last-translation-attempt.txt";

    private final JavaPlugin plugin;
    private final Logger log;

    public TranslationSync(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public record Result(int pluginsScanned, int keysTranslated, int keysReused, int translationFailures,
                          Map<String, PluginStats> perPlugin) {}

    /** Un plugin, sommato su tutte le lingue: quante chiavi ha in italiano, e come stanno le altre lingue. */
    public record PluginStats(int totalKeys, int translated, int reused, int missing) {}

    /** Va chiamato fuori dal thread principale: puo' fare centinaia di chiamate di rete. */
    public Result run() {
        List<String> pluginNames = plugin.getConfig().getStringList("translations.plugins");
        List<String> fileNames = plugin.getConfig().getStringList("translations.files");
        List<String> targetLanguages = new ArrayList<>(plugin.getConfig().getStringList("supported-languages"));
        targetLanguages.remove(SOURCE_LANGUAGE);

        boolean autoTranslateEnabled = plugin.getConfig().getBoolean("translations.auto-translate.enabled", true);
        int timeoutMs = plugin.getConfig().getInt("translations.auto-translate.timeout-ms", 4000);
        int delayMs = plugin.getConfig().getInt("translations.auto-translate.delay-ms", 150);
        String contactEmail = plugin.getConfig().getString("translations.auto-translate.contact-email", "");
        Translator translator = autoTranslateEnabled ? new Translator(timeoutMs, log, contactEmail) : null;

        // Un solo tentativo di traduzione al giorno, a prescindere da quanti riavvii o /language
        // sync capitano nel frattempo: MyMemory non ha solo una quota giornaliera di parole, ha
        // anche un limite di frequenza (HTTP 429) che un IP puo' far scattare ripetendo il
        // tentativo a ogni riavvio, e quel blocco puo' restare attivo ben oltre un giorno. Lo
        // specchio it.yml e le chiavi gia' in cache continuano comunque a funzionare: solo le
        // chiamate di rete vere e proprie si fermano finche' non cambia la data.
        boolean throttledToday = false;
        if (translator != null) {
            if (alreadyAttemptedToday()) {
                translator.forceUnavailable();
                throttledToday = true;
            } else {
                markAttemptedToday();
            }
        }

        File pluginsFolder = plugin.getDataFolder().getParentFile();
        File translationsRoot = new File(plugin.getDataFolder(), "translations");

        int scanned = 0;
        Counters totals = new Counters();
        Map<String, List<String>> failures = new LinkedHashMap<>();
        for (String lang : targetLanguages) failures.put(lang, new ArrayList<>());
        Map<String, PluginStats> perPlugin = new LinkedHashMap<>();

        MenuPhraseSync menuPhraseSync = new MenuPhraseSync(plugin, log);
        for (String pluginName : pluginNames) {
            Map<String, Object> source = readSourceText(pluginsFolder, pluginName, fileNames);
            if (source.isEmpty()) {
                continue; // plugin non installato, o nessuno dei file configurati esiste
            }
            scanned++;
            File catalogDir = new File(translationsRoot, pluginName);
            catalogDir.mkdirs();

            writeMirror(new File(catalogDir, SOURCE_LANGUAGE + ".yml"), source);

            Counters pluginTotals = new Counters();
            for (String lang : targetLanguages) {
                syncLanguage(catalogDir, pluginName, lang, source, translator, delayMs,
                        autoTranslateEnabled, pluginTotals, failures.get(lang));
            }
            MenuPhraseSync.Stats menuStats = menuPhraseSync.sync(pluginName, targetLanguages, translator,
                    delayMs, autoTranslateEnabled, failures);

            totals.translated += pluginTotals.translated + menuStats.translated();
            totals.reused += pluginTotals.reused + menuStats.reused();
            totals.failed += pluginTotals.failed + menuStats.missing();
            perPlugin.put(pluginName, new PluginStats(source.size() + menuStats.totalPhrases(),
                    pluginTotals.translated + menuStats.translated(),
                    pluginTotals.reused + menuStats.reused(),
                    pluginTotals.failed + menuStats.missing()));
            log.info("MagixLanguage: " + pluginName + " (" + source.size() + " chiavi in italiano"
                    + (menuStats.totalPhrases() > 0 ? " + " + menuStats.totalPhrases() + " frasi di menu" : "")
                    + "): " + (pluginTotals.translated + menuStats.translated()) + " tradotte ora, "
                    + (pluginTotals.reused + menuStats.reused()) + " gia' in cache, "
                    + (pluginTotals.failed + menuStats.missing()) + " ancora mancanti (su "
                    + targetLanguages.size() + " lingue).");
        }

        int failureTotal = writeFailureReports(translationsRoot, failures);
        log.info("MagixLanguage: sincronizzazione completata (" + scanned + " plugin, " + totals.translated
                + " chiavi tradotte, " + totals.reused + " gia' in cache, " + failureTotal + " fallite)."
                + (throttledToday ? " Tentativo di traduzione di oggi gia' usato: nessuna nuova chiamata"
                    + " a MyMemory fino a domani (o a un /language sync di un altro giorno)." : ""));
        return new Result(scanned, totals.translated, totals.reused, failureTotal, perPlugin);
    }

    /** Se oggi si e' gia' tentata una traduzione vera (anche se fallita subito): vedi {@link #run()}. */
    private boolean alreadyAttemptedToday() {
        File f = new File(plugin.getDataFolder(), LAST_ATTEMPT_FILE);
        if (!f.isFile()) {
            return false;
        }
        try {
            String saved = Files.readString(f.toPath(), StandardCharsets.UTF_8).trim();
            return LocalDate.now().toString().equals(saved);
        } catch (IOException e) {
            return false;
        }
    }

    /** Cancella il segna-tentativo di oggi: il prossimo {@link #run()} prova MyMemory anche se oggi
     *  e' gia' stato tentato. Usato da "/language sync force", per verificare a mano se un blocco
     *  (HTTP 429) si e' liberato senza aspettare la mezzanotte. */
    public void forceNextAttempt() {
        try {
            Files.deleteIfExists(new File(plugin.getDataFolder(), LAST_ATTEMPT_FILE).toPath());
        } catch (IOException e) {
            log.warning("MagixLanguage: impossibile azzerare il tentativo di oggi (" + e + ").");
        }
    }

    /** Segna il tentativo di oggi come usato, PRIMA di sapere se andra' a buon fine: e' il punto,
     *  altrimenti un tentativo fallito subito (HTTP 429) non risparmierebbe i riavvii successivi. */
    private void markAttemptedToday() {
        try {
            Files.writeString(new File(plugin.getDataFolder(), LAST_ATTEMPT_FILE).toPath(),
                    LocalDate.now().toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("MagixLanguage: impossibile salvare la data dell'ultimo tentativo di traduzione (" + e + ").");
        }
    }

    private static final class Counters {
        int translated;
        int reused;
        int failed;
    }

    // ------------------------------------------------------------- una lingua di un plugin

    private void syncLanguage(File catalogDir, String pluginName, String lang, Map<String, Object> source,
                              Translator translator, int delayMs, boolean autoTranslateEnabled,
                              Counters totals, List<String> failuresForLang) {
        File target = new File(catalogDir, lang + ".yml");
        File overridesFile = new File(catalogDir, lang + "-overrides.yml");
        File cacheFile = new File(catalogDir, ".cache-" + lang + ".yml");

        ensureOverridesStub(overridesFile, lang);
        Map<String, Object> overrides = overridesFile.isFile() ? flattenFile(overridesFile) : Map.of();
        Cache cache = loadCache(cacheFile);

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> newCacheSource = new LinkedHashMap<>();
        Map<String, Object> newCacheTranslated = new LinkedHashMap<>();

        for (Map.Entry<String, Object> e : source.entrySet()) {
            String key = e.getKey();
            Object italianValue = e.getValue();

            if (overrides.containsKey(key)) {
                result.put(key, overrides.get(key)); // lo staff vince sempre: niente cache, niente traduzione
                continue;
            }

            Object cachedSource = cache.source().get(key);
            Object cachedTranslated = cache.translated().get(key);
            if (cachedTranslated != null && Objects.equals(cachedSource, italianValue) && !looksCorrupted(cachedTranslated)) {
                result.put(key, cachedTranslated);
                newCacheSource.put(key, italianValue);
                newCacheTranslated.put(key, cachedTranslated);
                totals.reused++;
                continue;
            }

            if (!autoTranslateEnabled) {
                result.put(key, italianValue); // traduzione spenta di proposito: non e' un fallimento da segnalare
                continue;
            }
            if (!translator.isAvailable()) {
                // troppi fallimenti di fila in questa sincronizzazione (vedi Translator): niente altre
                // chiamate di rete ne' attese inutili, si riprova tutto dal prossimo giro.
                result.put(key, italianValue);
                failuresForLang.add(pluginName + ": " + key);
                totals.failed++;
                continue;
            }
            Object translated = translateValue(translator, italianValue, lang);
            if (delayMs > 0) sleepQuietly(delayMs); // sempre, dopo un vero tentativo di rete: successo o fallimento
            if (translated == null) {
                result.put(key, italianValue); // ripiego: italiano, si riprova al prossimo giro (non va in cache)
                failuresForLang.add(pluginName + ": " + key);
                totals.failed++;
            } else {
                result.put(key, translated);
                newCacheSource.put(key, italianValue);
                newCacheTranslated.put(key, translated);
                totals.translated++;
            }
        }

        writeCatalog(target, result, pluginName, lang);
        saveCache(cacheFile, newCacheSource, newCacheTranslated);
    }

    /**
     * Residui di un vecchio segnaposto non ripristinato (visto succedere davvero: il servizio di
     * traduzione ha alterato {@code [[N]]} in {@code [N]}, lasciando quel residuo al posto di un
     * colore o di un placeholder). Una cache con un valore cosi' non si riusa: si ritraduce.
     */
    private static final Pattern SUSPECT_LEFTOVER = Pattern.compile("(?i)qx\\s*\\d+\\s*xq|\\[\\d+]");

    private static boolean looksCorrupted(Object value) {
        if (value instanceof String s) {
            return SUSPECT_LEFTOVER.matcher(s).find();
        }
        if (value instanceof List<?> list) {
            for (Object line : list) {
                if (line != null && SUSPECT_LEFTOVER.matcher(String.valueOf(line)).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object translateValue(Translator translator, Object italianValue, String lang) {
        if (italianValue instanceof String s) {
            return translator.translate(s, lang);
        }
        if (italianValue instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object line : list) {
                String translated = translator.translate(String.valueOf(line), lang);
                if (translated == null) {
                    return null; // una riga sola non tradotta: si riprova tutta la lista al prossimo giro
                }
                out.add(translated);
            }
            return out;
        }
        return null;
    }

    private static void sleepQuietly(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    // ------------------------------------------------------------- cache delle traduzioni

    /** Cosa si e' tradotto l'ultima volta (source) e con che risultato (translated), chiave per chiave. */
    private record Cache(Map<String, Object> source, Map<String, Object> translated) {}

    private static Cache loadCache(File file) {
        if (!file.isFile()) {
            return new Cache(Map.of(), Map.of());
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Map<String, Object> source = new LinkedHashMap<>();
        Map<String, Object> translated = new LinkedHashMap<>();
        ConfigurationSection sourceSection = cfg.getConfigurationSection("source");
        if (sourceSection != null) flattenSection(sourceSection, "", source);
        ConfigurationSection translatedSection = cfg.getConfigurationSection("translated");
        if (translatedSection != null) flattenSection(translatedSection, "", translated);
        return new Cache(source, translated);
    }

    private void saveCache(File file, Map<String, Object> source, Map<String, Object> translated) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Object> e : new TreeMap<>(source).entrySet()) {
            yaml.set("source." + e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Object> e : new TreeMap<>(translated).entrySet()) {
            yaml.set("translated." + e.getKey(), e.getValue());
        }
        try {
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            Files.writeString(file.toPath(),
                    "# Cache delle traduzioni automatiche: non si modifica a mano, e non serve leggerla.\n"
                            + yaml.saveToString(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("MagixLanguage: impossibile salvare " + file + " (" + e + ").");
        }
    }

    // ------------------------------------------------------------- correzioni dello staff

    /** Crea il file delle correzioni vuoto, con le istruzioni, se non esiste gia'. Non lo tocca mai altrimenti. */
    private void ensureOverridesStub(File file, String lang) {
        if (file.isFile()) {
            return;
        }
        String header = "# Le TUE correzioni per la lingua \"" + lang + "\": una chiave messa qui vince sempre "
                + "sulla traduzione automatica in " + lang + ".yml (nella stessa cartella), e questo file non "
                + "viene MAI toccato dalla sincronizzazione - ne' letto per tradurre, ne' riscritto.\n"
                + "# Usa lo stesso percorso di chiave di " + lang + ".yml, in forma annidata YAML, solo per le "
                + "chiavi che vuoi correggere (le altre restano tradotte in automatico). Esempio:\n"
                + "#\n"
                + "# gate:\n"
                + "#   otp-required: \"Testo scelto da te, in " + lang + ".\"\n";
        try {
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            Files.writeString(file.toPath(), header, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("MagixLanguage: impossibile creare " + file + " (" + e + ").");
        }
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
        String header = "# Traduzione (" + lang + ") dei messaggi di " + pluginName + ", AUTOMATICA: rigenerata "
                + "per intero a ogni sincronizzazione, anche nei valori gia' presenti se il testo italiano e' "
                + "cambiato nel frattempo (un colore, una formattazione...). NON si modifica QUI: le modifiche "
                + "sparirebbero al prossimo riavvio.\n"
                + "# Per correggere una traduzione senza che la sincronizzazione la sovrascriva piu': metti la "
                + "STESSA chiave in " + lang + "-overrides.yml, nella stessa cartella. Vince sempre lei.\n";
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
            File dir = bakDir(file);
            Files.createDirectories(dir.toPath());
            File copy = new File(dir, file.getName() + ".bak-" + stamp);
            // La marca e' al secondo: due scritture dello stesso file nello stesso secondo (due
            // sincronizzazioni ravvicinate) userebbero lo stesso nome. Una copia con quel nome
            // gia' presente vuol dire che il contenuto di un attimo fa e' gia' al sicuro: si
            // procede con la scrittura invece di bloccarla per una collisione innocua.
            if (!copy.exists()) {
                Files.copy(file.toPath(), copy.toPath());
                pruneBackups(file, dir);
            }
            return true;
        } catch (IOException e) {
            log.warning("MagixLanguage: copia di sicurezza di " + file + " fallita (" + e + "): file NON toccato.");
            return false;
        }
    }

    /**
     * La cartella dove va la copia di scorta di {@code file}: la cartella {@code plugins/} viene
     * sostituita con {@code .bak/}, il resto del percorso resta lo stesso — la stessa struttura che
     * usa {@code util/ConfigAlign} in ogni plugin. Assoluto PRIMA di risalire i genitori: su un
     * server vero {@code getDataFolder()} e' quasi sempre relativo ("plugins/MagixLanguage"), e
     * {@code getParentFile()} su un singolo segmento come "plugins" da' null - senza questo il
     * backup finiva sempre accanto al file, dentro plugins/MagixLanguage/translations/....
     */
    private File bakDir(File file) {
        File pluginsDir = plugin.getDataFolder().getAbsoluteFile().getParentFile();
        File serverRoot = pluginsDir == null ? null : pluginsDir.getParentFile();
        if (serverRoot == null) return file.getParentFile();
        String relative = pluginsDir.toPath().relativize(file.getAbsoluteFile().getParentFile().toPath()).toString();
        return relative.isEmpty() ? new File(serverRoot, ".bak") : new File(new File(serverRoot, ".bak"), relative);
    }

    private void pruneBackups(File original, File dir) {
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
    private int writeFailureReports(File translationsRoot, Map<String, List<String>> failuresByLang) {
        int total = 0;
        for (Map.Entry<String, List<String>> e : failuresByLang.entrySet()) {
            List<String> lines = e.getValue();
            total += lines.size();
            File report = new File(translationsRoot, "TRANSLATION-FAILED-" + e.getKey() + ".txt");
            try {
                if (lines.isEmpty()) {
                    Files.deleteIfExists(report.toPath());
                    continue;
                }
                String text = "# Chiavi che la traduzione automatica (" + e.getKey() + ") non e' riuscita a "
                        + "tradurre nell'ultima sincronizzazione (restano in italiano nel frattempo): si riprova "
                        + "da sola al prossimo /language sync o riavvio. Se un servizio esterno e' irraggiungibile "
                        + "per un problema di rete del VPS, resta qui finche' non torna disponibile.\n"
                        + "# Rigenerato ad ogni sincronizzazione: non si modifica a mano.\n"
                        + String.join("\n", lines) + "\n";
                Files.writeString(report.toPath(), text, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                log.warning("MagixLanguage: impossibile scrivere " + report + " (" + ex + ").");
            }
        }
        return total;
    }
}
