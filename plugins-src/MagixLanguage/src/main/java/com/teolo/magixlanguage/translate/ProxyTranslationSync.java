package com.teolo.magixlanguage.translate;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * {@link TranslationSync} for the Velocity proxy, where there is no Bukkit YamlConfiguration: the
 * same files, the same rules, written with SnakeYAML.
 *
 * <p>For every Magix plugin of the proxy (its data folder, e.g. {@code plugins/magixproxy/}) the
 * text of its messages.yml goes into {@code translations/<folder>/it.yml} (a mirror), and every
 * other language is translated key by key into {@code <lang>.yml}: a key whose Italian did not
 * change is taken from {@code .cache-<lang>.yml}, a key of {@code <lang>-overrides.yml} always wins.
 * MyMemory is called only when {@link TranslationPacing} allows it, as on the game servers.</p>
 */
public final class ProxyTranslationSync {

    private static final String SOURCE_LANGUAGE = "it";

    /** Settings read from config.yml by the caller (see the Velocity plugin class). */
    public record Settings(List<String> plugins, List<String> files, List<String> languages, boolean autoTranslate,
                           int timeoutMs, int delayMs, String contactEmail, int retryIntervalMinutes,
                           int pauseMinutes) {}

    private final Path pluginsFolder;
    private final Path translationsRoot;
    private final TranslationPacing pacing;
    private final Logger log;

    public ProxyTranslationSync(Path pluginsFolder, Path dataFolder, TranslationPacing pacing, Logger log) {
        this.pluginsFolder = pluginsFolder;
        this.translationsRoot = dataFolder.resolve("translations");
        this.pacing = pacing;
        this.log = log;
    }

    /** One run; returns how many keys are still missing (0 = nothing to retry). */
    public int run(Settings settings) {
        List<String> targets = new ArrayList<>(settings.languages());
        targets.remove(SOURCE_LANGUAGE);
        Translator translator = settings.autoTranslate()
                ? new Translator(settings.timeoutMs(), log, settings.contactEmail()) : null;
        Instant waitUntil = null;
        if (translator != null) {
            waitUntil = pacing.nextPluginAttempt(settings.retryIntervalMinutes());
            if (waitUntil != null) {
                translator.forceUnavailable();
            } else {
                pacing.markPluginAttempt();
            }
        }

        int translated = 0, reused = 0, missing = 0;
        for (String plugin : settings.plugins()) {
            Map<String, Object> source = new LinkedHashMap<>();
            for (String file : settings.files()) {
                source.putAll(flat(read(pluginsFolder.resolve(plugin).resolve(file))));
            }
            source.values().removeIf(v -> !(v instanceof String) && !(v instanceof List<?>));
            if (source.isEmpty()) {
                continue;
            }
            Path dir = translationsRoot.resolve(plugin);
            write(dir.resolve(SOURCE_LANGUAGE + ".yml"), source,
                    "# Specchio del testo italiano di " + plugin + " (si cambia nel suo messages.yml, non qui).\n");
            int[] counts = new int[3];
            for (String lang : targets) {
                syncLanguage(dir, plugin, lang, source, translator, settings, counts);
            }
            translated += counts[0];
            reused += counts[1];
            missing += counts[2];
            log.info("MagixLanguage: " + plugin + " (" + source.size() + " chiavi in italiano): " + counts[0]
                    + " tradotte ora, " + counts[1] + " già in cache, " + counts[2] + " ancora mancanti.");
        }
        if (translator != null && waitUntil == null) {
            pacing.recordOutcome(translator, settings.pauseMinutes());
        }
        log.info("MagixLanguage: sincronizzazione del proxy completata (" + translated + " tradotte, "
                + reused + " già in cache, " + missing + " mancanti).");
        return missing;
    }

    @SuppressWarnings("unchecked")
    private void syncLanguage(Path dir, String plugin, String lang, Map<String, Object> source,
                              Translator translator, Settings settings, int[] counts) {
        Path overridesFile = dir.resolve(lang + "-overrides.yml");
        Path cacheFile = dir.resolve(".cache-" + lang + ".yml");
        ensureOverridesStub(overridesFile, lang);
        Map<String, Object> overrides = flat(read(overridesFile));
        Map<String, Object> cache = read(cacheFile);
        Map<String, Object> cachedSource = cache.get("source") instanceof Map<?, ?> m
                ? flat((Map<String, Object>) m) : Map.of();
        Map<String, Object> cachedTranslated = cache.get("translated") instanceof Map<?, ?> m
                ? flat((Map<String, Object>) m) : Map.of();

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> newSource = new LinkedHashMap<>();
        Map<String, Object> newTranslated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            String key = e.getKey();
            Object italian = e.getValue();
            if (overrides.containsKey(key)) {
                result.put(key, overrides.get(key));
                continue;
            }
            Object old = cachedTranslated.get(key);
            Object refreshed = old != null && Objects.equals(cachedSource.get(key), italian)
                    ? HelpSyntax.refreshCached(italian, old, lang) : null;
            if (refreshed != null && !TranslationChecks.looksCorrupted(italian, refreshed, lang)) {
                result.put(key, refreshed);
                newSource.put(key, italian);
                newTranslated.put(key, refreshed);
                counts[1]++;
                continue;
            }
            if (translator == null) {
                result.put(key, italian);
                continue;
            }
            if (!translator.isAvailable()) {
                result.put(key, italian);
                counts[2]++;
                continue;
            }
            Object translated = TranslationChecks.translateValue(translator, italian, lang);
            sleep(settings.delayMs());
            if (translated == null) {
                result.put(key, italian);
                counts[2]++;
            } else {
                result.put(key, translated);
                newSource.put(key, italian);
                newTranslated.put(key, translated);
                counts[0]++;
            }
        }
        write(dir.resolve(lang + ".yml"), result, "# Traduzione in \"" + lang + "\" di " + plugin
                + ", scritta da MagixLanguage: NON si modifica qui (verrebbe riscritta), si corregge in "
                + lang + "-overrides.yml.\n");
        Map<String, Object> newCache = new LinkedHashMap<>();
        newCache.put("source", nested(newSource));
        newCache.put("translated", nested(newTranslated));
        writeRaw(cacheFile, newCache, "");
    }

    private void ensureOverridesStub(Path file, String lang) {
        if (Files.exists(file)) {
            return;
        }
        String header = "# Le TUE correzioni per la lingua \"" + lang + "\": una chiave messa qui vince sempre "
                + "sulla traduzione automatica in " + lang + ".yml (nella stessa cartella), e questo file non "
                + "viene MAI toccato dalla sincronizzazione.\n"
                + "# Stesso percorso di chiave di " + lang + ".yml, in forma annidata YAML. Esempio:\n"
                + "#\n"
                + "# network:\n"
                + "#   login-first: \"Testo scelto da te, in " + lang + ".\"\n";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, header, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warning("MagixLanguage: impossibile creare " + file + " (" + e + ").");
        }
    }

    /** A catalog of the proxy, flat key -> value, for the Velocity plugin class. */
    public static Map<String, Object> loadFlatCatalog(File file) {
        return flat(read(file.toPath()));
    }

    // ------------------------------------------------------------------ yaml

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(r);
            return root instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    static Map<String, Object> flat(Map<String, Object> root) {
        Map<String, Object> out = new LinkedHashMap<>();
        flatten(root, "", out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(Map<String, Object> node, String prefix, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : node.entrySet()) {
            String path = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
            Object v = e.getValue();
            if (v instanceof Map<?, ?> child) {
                flatten((Map<String, Object>) child, path, out);
            } else if (v instanceof String) {
                out.put(path, v);
            } else if (v instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
                out.put(path, new ArrayList<>(list));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> flat) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : new TreeMap<>(flat).entrySet()) {
            String[] parts = e.getKey().split("\\.");
            Map<String, Object> node = root;
            for (int i = 0; i < parts.length - 1; i++) {
                Object child = node.get(parts[i]);
                if (!(child instanceof Map)) {
                    child = new LinkedHashMap<String, Object>();
                    node.put(parts[i], child);
                }
                node = (Map<String, Object>) child;
            }
            node.put(parts[parts.length - 1], e.getValue());
        }
        return root;
    }

    private void write(Path file, Map<String, Object> flat, String header) {
        writeRaw(file, nested(flat), header);
    }

    private void writeRaw(Path file, Map<String, Object> data, String header) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        options.setWidth(4096);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, header + new Yaml(options).dump(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warning("MagixLanguage: impossibile scrivere " + file + " (" + e + ").");
        }
    }

    private static void sleep(int millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
