package com.teolo.magixlanguage.translate;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Traduce il testo dei MENU (MagixMenus e affini): il titolo, il nome e la descrizione di ogni
 * item, il testo delle finestre di dialogo (corpo, bottoni, campi), e il testo scritto dentro le
 * azioni {@code message}/{@code broadcast}/{@code title}/{@code actionbar}.
 *
 * <h2>Perche' non e' un catalogo chiave-valore come TranslationSync</h2>
 * Un file di menu non ha "chiavi di messaggio" stabili come messages.yml: il nome di un item
 * (es. {@code vetrina:}) e' un identificatore tecnico scelto da chi scrive il menu, non una frase
 * per il giocatore, e le azioni sono righe libere dentro liste miste (testo o blocchi
 * {@code if/then/else}). Invece di inseguire un percorso per ogni possibile frase, si traduce PER
 * FRASE: ogni testo italiano diventa la propria chiave (un hash stabile), e chi lo mostra manda
 * alla ricerca lo stesso testo italiano che sta per scrivere — se c'e' una traduzione la usa,
 * altrimenti resta in italiano. Vedi {@code MagixLanguageAPI.translatePhrase}.
 *
 * <h2>Cosa NON viene scandito da solo</h2>
 * Le azioni dentro un blocco condizionale ({@code if/then/else}) non vengono cercate in
 * automatico, per non dover ricostruire qui la stessa logica di lettura di MagixMenus. Se serve
 * tradurre anche una di quelle, lo staff puo' comunque aggiungere la frase A MANO nel file
 * {@code -overrides.yml} (vedi sotto): la ricerca a runtime e' per TESTO, non per percorso, quindi
 * funziona lo stesso anche per una frase che questa scansione non ha trovato da sola.
 */
public final class MenuPhraseSync {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_BACKUPS = 10;

    /** Residui di un segnaposto non ripristinato: vedi lo stesso controllo in TranslationSync. */
    private static final Pattern SUSPECT_LEFTOVER = Pattern.compile("(?i)qx\\s*\\d+\\s*xq|\\[\\d+]");

    /**
     * Tipi di azione il cui argomento e' testo per il giocatore (message/broadcast/title/actionbar
     * di {@code Action.Type} in MagixMenus, coi loro sinonimi). MagixLanguage non dipende da
     * MagixMenus: questo elenco si ripete a mano, e cambia raramente.
     */
    private static final Set<String> TEXT_ACTION_TYPES = Set.of(
            "message", "msg", "messaggio", "chat",
            "broadcast", "annuncio",
            "title", "titolo",
            "actionbar", "barra");

    /** Le chiavi (inglese + sinonimo italiano) di una lista di azioni legata a un tasto o a un item. */
    private static final String[][] ACTION_KEY_ALIASES = {
            {"actions", "azioni"},
            {"left_click_actions", "azioni_sinistro"},
            {"right_click_actions", "azioni_destro"},
            {"shift_left_click_actions", "azioni_shift_sinistro"},
            {"shift_right_click_actions", "azioni_shift_destro"},
            {"middle_click_actions", "azioni_centrale"},
            {"number_key_actions", "azioni_numero"},
            {"double_click_actions", "azioni_doppio"},
    };

    /** Le chiavi (inglese + sinonimi italiani) dei blocchi requisiti di un item: ognuno puo' avere
     *  un suo "deny_actions" con testo per il giocatore (es. "message: Ti servono 100 monete."). */
    private static final String[][] REQUIREMENT_KEY_ALIASES = {
            {"show_requirements", "mostra_se", "mostra_requisiti"},
            {"click_requirements", "click_se"},
            {"left_click_requirements", "click_se_sinistro"},
            {"right_click_requirements", "click_se_destro"},
            {"shift_left_click_requirements", "click_se_shift_sinistro"},
            {"shift_right_click_requirements", "click_se_shift_destro"},
            {"middle_click_requirements", "click_se_centrale"},
            {"number_key_requirements", "click_se_numero"},
            {"double_click_requirements", "click_se_doppio"},
    };

    /** Dentro un blocco requisiti, le chiavi (inglese + sinonimi) della lista di azioni negate. */
    private static final String[] DENY_ACTIONS_KEYS = {"deny_actions", "deny_commands", "azioni_negate"};

    private final JavaPlugin plugin;
    private final Logger log;

public MenuPhraseSync(JavaPlugin plugin, Logger log) {
        this.plugin = plugin;
        this.log = log;
    }

    /** L'hash stabile usato come chiave: lo stesso identico calcolo lato lettura (MagixLanguage). */
    public static String hash(String phrase) {
        return "p" + Integer.toHexString(phrase.hashCode());
    }

    public record Stats(int totalPhrases, int translated, int reused, int missing) {
        static final Stats VUOTO = new Stats(0, 0, 0, 0);
    }

    /**
     * Scandisce plugins/&lt;pluginName&gt;/menus/*.yml (se esiste) e i testi dichiarati nel
     * {@code translatable.yml} del plugin (vedi {@link #extractDeclared}), e traduce le frasi trovate.
     */
    public Stats sync(String pluginName, List<String> targetLanguages, Translator translator, int delayMs,
               boolean autoTranslateEnabled, Map<String, List<String>> failuresByLang) {
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        File pluginFolder = new File(pluginsFolder, pluginName);
        File menusFolder = new File(pluginFolder, "menus");
        File[] files = menusFolder.isDirectory()
                ? menusFolder.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"))
                : null;

        Set<String> phrases = new LinkedHashSet<>();
        if (files != null) {
            for (File f : files) {
                try {
                    YamlConfiguration yaml = new YamlConfiguration();
                    yaml.load(f);
                    extract(yaml, phrases);
                } catch (Exception e) {
                    log.warning("MagixLanguage: impossibile leggere il menu " + pluginName + "/menus/"
                            + f.getName() + " (" + e + ").");
                }
            }
        }
        extractDeclared(pluginName, pluginFolder, phrases);
        if (phrases.isEmpty()) {
            return Stats.VUOTO;
        }

        File catalogDir = new File(new File(plugin.getDataFolder(), "translations"), pluginName);
        catalogDir.mkdirs();

        int translated = 0, reused = 0, missing = 0;
        for (String lang : targetLanguages) {
            int[] counts = syncLanguage(catalogDir, pluginName, lang, phrases, translator, delayMs,
                    autoTranslateEnabled, failuresByLang.get(lang));
            translated += counts[0];
            reused += counts[1];
            missing += counts[2];
        }
        return new Stats(phrases.size(), translated, reused, missing);
    }

    // ------------------------------------------------------------------ una lingua

    private int[] syncLanguage(File catalogDir, String pluginName, String lang, Set<String> phrases,
                                Translator translator, int delayMs, boolean autoTranslateEnabled,
                                List<String> failuresForLang) {
        File catalogFile = new File(catalogDir, "menu-phrases-" + lang + ".yml");
        File overridesFile = new File(catalogDir, "menu-phrases-" + lang + "-overrides.yml");
        File cacheFile = new File(catalogDir, ".menu-cache-" + lang + ".yml");

        ensureOverridesStub(overridesFile, lang);
        Map<String, String> overrides = loadOverrides(overridesFile);
        Map<String, String> cache = loadCache(cacheFile);

        Map<String, Object> catalogOut = new LinkedHashMap<>();
        Map<String, String> newCache = new LinkedHashMap<>();

        int translated = 0, reused = 0, missing = 0;
        for (String phrase : phrases) {
            String hash = hash(phrase);

            String override = overrides.get(phrase);
            if (override != null) {
                catalogOut.put(hash, entry(phrase, override));
                continue;
            }

            String cached = cache.get(hash);
            // Stessi controlli dei messaggi (pezzi protetti persi o separati, spazi ai bordi...): una
            // frase rovinata da una versione vecchia del traduttore si ritraduce da sola.
            if (cached != null && !looksCorrupted(cached) && !TranslationChecks.looksCorrupted(phrase, cached, lang)) {
                catalogOut.put(hash, entry(phrase, cached));
                newCache.put(hash, cached);
                reused++;
                continue;
            }

            if (!autoTranslateEnabled) {
                missing++;
                continue;
            }
            if (!translator.isAvailable()) {
                missing++;
                failuresForLang.add(pluginName + " (menu): " + phrase);
                continue;
            }
            String result = translateLines(translator, phrase, lang, delayMs);
            if (result == null) {
                missing++;
                failuresForLang.add(pluginName + " (menu): " + phrase);
            } else {
                catalogOut.put(hash, entry(phrase, result));
                newCache.put(hash, result);
                translated++;
            }
        }

        writeCatalog(catalogFile, catalogOut, pluginName, lang);
        saveCache(cacheFile, newCache);
        return new int[]{translated, reused, missing};
    }

    /** Un testo su piu' righe (un messaggio di kick) si traduce riga per riga: il servizio di
     *  traduzione non garantisce di lasciare gli a-capo dove sono. Null se una riga fallisce. */
    private static String translateLines(Translator translator, String phrase, String lang, int delayMs) {
        String[] lines = phrase.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            String line = lines[i];
            if (!hasWords(line)) {
                out.append(line);
                continue;
            }
            String t = translator.translate(line, lang);
            if (delayMs > 0) sleepQuietly(delayMs);
            if (t == null) return null;
            out.append(t);
        }
        return out.toString();
    }

    private static Map<String, Object> entry(String source, String translated) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("translated", translated);
        return m;
    }

    private static boolean looksCorrupted(String value) {
        return SUSPECT_LEFTOVER.matcher(value).find();
    }

    private static void sleepQuietly(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ testi dichiarati dal plugin

    /** Il file, dentro il jar di ciascun plugin, che elenca dove stanno i suoi testi fuori da messages.yml. */
    public static final String DECLARED_FILE = "translatable.yml";

    /**
     * I testi per i giocatori che un plugin tiene FUORI da messages.yml (righe della scoreboard,
     * titoli dei territori, il pannello sotto la minimap, i {@code msg:} delle entita'...): ogni
     * plugin li dichiara nel proprio {@code translatable.yml}, dentro il jar, cosi' MagixLanguage
     * non deve sapere com'e' fatto il config di nessuno. Formato (file -> elenco di percorsi):
     * <pre>
     * config.yml:
     *   - "scoreboards.*.lines.*.frames"          # '*' = ogni chiave di una sezione, o ogni voce di una lista
     *   - path: "entities.*.commands"             # forma lunga: solo le righe che cominciano con prefix,
     *     prefix: "msg:"                          # e la frase e' quello che segue il prefisso
     * </pre>
     * Il testo si legge dal file VERO del server (quello che lo staff ha scritto), non dal jar. Chi
     * lo mostra chiede la stessa frase a {@code MagixLanguageAPI.translatePhrase}.
     */
    private void extractDeclared(String pluginName, File pluginFolder, Set<String> out) {
        org.bukkit.plugin.Plugin owner = plugin.getServer().getPluginManager().getPlugin(pluginName);
        if (owner == null) {
            return;
        }
        Object spec;
        try (java.io.InputStream in = owner.getResource(DECLARED_FILE)) {
            if (in == null) {
                return;
            }
            spec = new org.yaml.snakeyaml.Yaml().load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warning("MagixLanguage: " + DECLARED_FILE + " di " + pluginName + " illeggibile (" + e + ").");
            return;
        }
        if (!(spec instanceof Map<?, ?> files)) {
            return;
        }
        for (Map.Entry<?, ?> fileEntry : files.entrySet()) {
            File f = new File(pluginFolder, String.valueOf(fileEntry.getKey()));
            if (!f.isFile() || !(fileEntry.getValue() instanceof List<?> paths)) {
                continue;
            }
            Object data;
            try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                data = new org.yaml.snakeyaml.Yaml().load(r);
            } catch (Exception e) {
                log.warning("MagixLanguage: impossibile leggere " + pluginName + "/" + f.getName() + " (" + e + ").");
                continue;
            }
            for (Object p : paths) {
                String path;
                String prefix = null;
                if (p instanceof Map<?, ?> m) {
                    path = m.get("path") != null ? String.valueOf(m.get("path")) : null;
                    prefix = m.get("prefix") != null ? String.valueOf(m.get("prefix")) : null;
                } else {
                    path = p != null ? String.valueOf(p) : null;
                }
                if (path != null && !path.isBlank()) {
                    collect(data, path.split("\\."), 0, prefix, out);
                }
            }
        }
    }

    private static void collect(Object node, String[] segments, int i, String prefix, Set<String> out) {
        if (node == null) {
            return;
        }
        if (i == segments.length) {
            if (node instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof String s) addDeclared(s, prefix, out);
                }
            } else if (node instanceof String s) {
                addDeclared(s, prefix, out);
            }
            return;
        }
        String seg = segments[i];
        if (seg.equals("*")) {
            if (node instanceof Map<?, ?> m) {
                for (Object v : m.values()) collect(v, segments, i + 1, prefix, out);
            } else if (node instanceof List<?> list) {
                for (Object v : list) collect(v, segments, i + 1, prefix, out);
            }
        } else if (node instanceof Map<?, ?> m) {
            collect(m.get(seg), segments, i + 1, prefix, out);
        }
    }

    private static void addDeclared(String value, String prefix, Set<String> out) {
        if (prefix != null) {
            if (!value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return;
            }
            value = value.substring(prefix.length()).trim();
        }
        if (hasWords(value)) {
            out.add(value);
        }
    }

    /** Solo il testo che ha delle parole: una riga fatta di soli placeholder, colori o trattini non si traduce. */
    private static boolean hasWords(String s) {
        String bare = s.replaceAll("&#[0-9A-Fa-f]{6}|<#[0-9A-Fa-f]{6}>|[&§][0-9a-fk-orxA-FK-ORX]|%[^%\\s]+%|\\{[^}]+}", "");
        return bare.codePoints().filter(Character::isLetter).count() >= 2;
    }

    // ------------------------------------------------------------------ estrazione dal file YAML

    private void extract(ConfigurationSection root, Set<String> out) {
        ConfigurationSection m = root.getConfigurationSection("menu");
        if (m == null) {
            m = root;
        }

        addText(textOf(m, "title", "titolo"), out);

        // Finestra di dialogo: corpo, cercato prima dentro "menu:", poi in cima al file.
        List<String> body = listOf(m, "body", "corpo", "testo");
        if (body.isEmpty()) {
            body = listOf(root, "body", "corpo", "testo");
        }
        for (String line : body) {
            addText(line, out);
        }

        Object rawButtons = firstRaw(m, "buttons", "bottoni");
        if (rawButtons == null) {
            rawButtons = firstRaw(root, "buttons", "bottoni");
        }
        if (rawButtons instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> b) {
                    addText(stringOf(b, "label", "etichetta", "nome", "testo"), out);
                    addText(stringOf(b, "tooltip", "suggerimento", "descrizione"), out);
                    extractActions(firstRawFromMap(b, "actions", "azioni"), out);
                }
            }
        }

        ConfigurationSection fields = firstSection(m, "inputs", "campi");
        if (fields == null) {
            fields = firstSection(root, "inputs", "campi");
        }
        if (fields != null) {
            for (String key : fields.getKeys(false)) {
                ConfigurationSection s = fields.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                addText(textOf(s, "label", "etichetta"), out);
                for (String opt : listOf(s, "options", "opzioni", "valori")) {
                    addText(opt, out);
                }
            }
        }

        ConfigurationSection items = firstSection(root, "items", "item", "oggetti");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection s = items.getConfigurationSection(key);
                if (s != null) {
                    extractItem(s, out);
                }
            }
        }

        ConfigurationSection content = firstSection(root, "content", "contenuto", "elenco");
        if (content != null) {
            ConfigurationSection entry = firstSection(content, "entry", "voce", "modello", "item");
            if (entry != null) {
                extractItem(entry, out);
            }
        }

        extractActions(firstRaw(m, "open_actions", "azioni_apertura"), out);
        extractActions(firstRaw(m, "close_actions", "azioni_chiusura"), out);
        extractDenyActions(firstSection(m, "open_requirements", "open_requirement", "apri_se", "apri_requisiti"), out);
    }

    private void extractItem(ConfigurationSection s, Set<String> out) {
        addText(textOf(s, "display_name", "name", "nome", "titolo"), out);
        for (String line : listOf(s, "lore", "description", "descrizione", "testo")) {
            addText(line, out);
        }
        for (String[] aliases : ACTION_KEY_ALIASES) {
            extractActions(firstRaw(s, aliases), out);
        }
        for (String[] aliases : REQUIREMENT_KEY_ALIASES) {
            extractDenyActions(firstSection(s, aliases), out);
        }
    }

    /** Dentro un blocco requisiti (show/click/open), il testo delle azioni che scattano se non
     *  soddisfatto (es. "message: Ti servono 100 monete."). */
    private void extractDenyActions(ConfigurationSection requirementBlock, Set<String> out) {
        if (requirementBlock == null) {
            return;
        }
        extractActions(firstRaw(requirementBlock, DENY_ACTIONS_KEYS), out);
    }

    private void extractActions(Object raw, Set<String> out) {
        if (raw == null) {
            return;
        }
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                extractOneAction(o, out);
            }
        } else {
            extractOneAction(raw, out);
        }
    }

    private void extractOneAction(Object o, Set<String> out) {
        if (o == null || o instanceof Map<?, ?>) {
            // Un blocco if/then/else: non lo si segue (vedi il commento in cima alla classe).
            return;
        }
        String row = String.valueOf(o).trim();
        int colon = row.indexOf(':');
        if (colon < 0) {
            return;
        }
        String type = row.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        if (!TEXT_ACTION_TYPES.contains(type)) {
            return;
        }
        addText(row.substring(colon + 1).trim(), out);
    }

    private static void addText(String s, Set<String> out) {
        if (s != null && !s.isBlank()) {
            out.add(s);
        }
    }

    private static String textOf(ConfigurationSection s, String... keys) {
        if (s == null) {
            return null;
        }
        for (String k : keys) {
            String v = s.getString(k);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static List<String> listOf(ConfigurationSection s, String... keys) {
        if (s == null) {
            return List.of();
        }
        for (String k : keys) {
            if (!s.isSet(k)) {
                continue;
            }
            List<String> v = s.getStringList(k);
            if (!v.isEmpty()) {
                return v;
            }
            String single = s.getString(k);
            if (single != null && !single.isBlank()) {
                return List.of(single);
            }
        }
        return List.of();
    }

    private static Object firstRaw(ConfigurationSection s, String... keys) {
        if (s == null) {
            return null;
        }
        for (String k : keys) {
            if (s.isSet(k)) {
                return s.get(k);
            }
        }
        return null;
    }

    private static ConfigurationSection firstSection(ConfigurationSection s, String... keys) {
        if (s == null) {
            return null;
        }
        for (String k : keys) {
            ConfigurationSection cs = s.getConfigurationSection(k);
            if (cs != null) {
                return cs;
            }
        }
        return null;
    }

    private static String stringOf(Map<?, ?> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private static Object firstRawFromMap(Map<?, ?> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ correzioni dello staff

    /** Frase italiana -> correzione. File piatto, letto con SnakeYAML grezzo: niente percorsi a
     *  punti (una frase con un punto dentro non deve essere scambiata per una chiave annidata). */
    private Map<String, String> loadOverrides(File file) {
        if (!file.isFile()) {
            return Map.of();
        }
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Object loaded = new org.yaml.snakeyaml.Yaml().load(r);
            if (!(loaded instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            return out;
        } catch (Exception e) {
            log.warning("MagixLanguage: impossibile leggere " + file + " (" + e + ").");
            return Map.of();
        }
    }

    private void ensureOverridesStub(File file, String lang) {
        if (file.isFile()) {
            return;
        }
        String header = "# Le TUE correzioni per il testo dei MENU in \"" + lang + "\": una frase messa qui "
                + "(la frase ITALIANA esatta, come chiave) vince sempre sulla traduzione automatica, e questo "
                + "file non viene MAI toccato dalla sincronizzazione.\n"
                + "# A differenza di messages.yml qui non ci sono nomi di chiave: si scrive la frase italiana "
                + "esatta (con placeholder %tipo_questo% compresi) e il testo corretto. Esempio:\n"
                + "#\n"
                + "# \"Clicca per comprare\": \"Click here to buy\"\n";
        try {
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            Files.writeString(file.toPath(), header, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("MagixLanguage: impossibile creare " + file + " (" + e + ").");
        }
    }

    // ------------------------------------------------------------------ cache

    private Map<String, String> loadCache(File file) {
        if (!file.isFile()) {
            return Map.of();
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = cfg.getConfigurationSection("translated");
        if (section == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            String v = section.getString(key);
            if (v != null) {
                out.put(key, v);
            }
        }
        return out;
    }

    private void saveCache(File file, Map<String, String> translated) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, String> e : translated.entrySet()) {
            yaml.set("translated." + e.getKey(), e.getValue());
        }
        try {
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            Files.writeString(file.toPath(),
                    "# Cache delle traduzioni automatiche dei menu: non si modifica a mano.\n"
                            + yaml.saveToString(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("MagixLanguage: impossibile salvare " + file + " (" + e + ").");
        }
    }

    // ------------------------------------------------------------------ scrittura del catalogo

    private void writeCatalog(File file, Map<String, Object> entries, String pluginName, String lang) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Object> e : new java.util.TreeMap<>(entries).entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pair = (Map<String, Object>) e.getValue();
            yaml.set(e.getKey() + ".source", pair.get("source"));
            yaml.set(e.getKey() + ".translated", pair.get("translated"));
        }
        String header = "# Traduzione (" + lang + ") del testo dei menu di " + pluginName + ", AUTOMATICA: "
                + "rigenerata per intero a ogni sincronizzazione. NON si modifica qui: le modifiche "
                + "sparirebbero al prossimo riavvio.\n"
                + "# Per correggere una traduzione: metti la frase italiana esatta (colonna \"source\" qui "
                + "sotto) in menu-phrases-" + lang + "-overrides.yml, nella stessa cartella. Vince sempre lei.\n";
        writeIfChanged(file, header + yaml.saveToString());
    }

    private void writeIfChanged(File file, String newText) {
        try {
            if (file.isFile()) {
                String current = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                if (current.equals(newText)) {
                    return;
                }
                if (!backup(file)) {
                    return;
                }
            }
            File dir = file.getParentFile();
            if (dir != null) dir.mkdirs();
            Files.writeString(file.toPath(), newText, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warning("MagixLanguage: impossibile scrivere " + file + " (" + e + "). File lasciato com'era.");
        }
    }

    private boolean backup(File file) {
        try {
            String stamp = LocalDateTime.now().format(STAMP);
            File copy = new File(file.getParentFile(), file.getName() + ".bak-" + stamp);
            if (!copy.exists()) {
                Files.copy(file.toPath(), copy.toPath());
                pruneBackups(file);
            }
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
}
