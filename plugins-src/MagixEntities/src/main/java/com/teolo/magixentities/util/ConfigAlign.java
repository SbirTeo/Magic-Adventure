package com.teolo.magixentities.util;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiene i file di configurazione SUL SERVER allineati a quelli del jar: a ogni avvio (e a ogni
 * reload) le chiavi nuove compaiono nel file vero, al loro posto e col loro commento, senza toccare
 * i valori gia' scelti.
 *
 * <h2>Perche' esiste</h2>
 * Il deploy porta il jar, non i config: {@code saveDefaultConfig()} scrive il file solo se non
 * esiste, e il plugin legge quello sul disco. Aggiungendo una chiave nuova, quindi, sul server non
 * compariva niente: valeva il default del jar, ma chi apriva il file non la vedeva nemmeno. Un file
 * che il plugin legge ma che nessuno aggiorna smette in fretta di dire la verita'.
 *
 * <h2>Perche' non basta copyDefaults</h2>
 * {@code getConfig().options().copyDefaults(true)} + {@code saveConfig()} le chiavi le aggiunge, ma
 * riscrive il file col serializzatore YAML: le sezioni cambiano ordine, i commenti di blocco (quelli
 * che spiegano un gruppo di chiavi, non la singola) spariscono e le righe lunghe vengono spezzate.
 * Questi config sono per meta' documentazione, quindi qui si lavora sul TESTO: si aggiunge solo
 * cio' che manca, e tutto il resto del file resta identico.
 *
 * <h2>Cosa fa e cosa non fa</h2>
 * <ul>
 *   <li><b>Aggiunge</b> le chiavi del sorgente che sul disco non ci sono, col commento che le
 *       accompagna, nella posizione che hanno nel sorgente (dopo l'ultima sorella gia' presente).</li>
 *   <li><b>Non tocca</b> i valori scelti sul server, ne' i commenti, ne' l'ordine di cio' che c'e'.</li>
 *   <li><b>Rinomina</b> le chiavi che nel codice hanno cambiato nome, portandosi dietro il valore
 *       scelto sul server e togliendo quella vecchia. Le rinomine non si indovinano: si dichiarano
 *       in {@code renames.yml} (un file per sezione, {@code vecchio.percorso: nuovo.percorso}) nello
 *       stesso commit in cui si rinomina nel codice.</li>
 *   <li><b>Cancella</b> le righe morte, cioe' le chiavi che nel sorgente non esistono piu', dai file
 *       a schema fisso — tutti tranne i cataloghi dello staff: una riga che nessuno legge e' solo
 *       una trappola per chi configura. Prima di cancellare fa una copia del file col timestamp, e
 *       scrive nel log che cosa ha tolto.</li>
 *   <li><b>Non cancella</b> negli altri file ({@code menus/*.yml}, {@code sanctions.yml}): li' le
 *       voci in piu' non sono residui, sono lavoro dello staff. Su quelli aggiunge e rinomina soltanto.</li>
 * </ul>
 */
public final class ConfigAlign {

    private ConfigAlign() {
    }

    /**
     * Righe che SEMBRANO chiavi, guardate con la lente larga: se ce n'e' almeno una e il parser non
     * ne ha riconosciuta nessuna, il file e' scritto in un modo che non capiamo e non si tocca. Un
     * file di soli commenti, invece, e' legittimo: li' le chiavi ci vanno scritte.
     */
    private static final Pattern LOOKS_LIKE_KEY =
            Pattern.compile("(?m)^[ \\t]*[A-Za-z0-9_.+-]+[ \\t]*:");

    /** Riga che apre una chiave: indentazione, nome, due punti. */
    private static final Pattern KEY_LINE = Pattern.compile("^([ \t]*)([A-Za-z0-9_.+-]+):([ \t].*)?$");

    /** Esito di un allineamento: cosa e' stato aggiunto, cosa e' rimasto indietro, com'e' il file. */
    public static final class Result {
        /** Chiavi aggiunte, col percorso completo (es. "tablist.priority.enabled"). */
        public final List<String> added = new ArrayList<>();
        /** Rinomine applicate, come "vecchio -> nuovo": il valore scelto sul server e' stato portato. */
        public final List<String> renamed = new ArrayList<>();
        /** Righe morte tolte: chiavi che nel sorgente non esistono piu'. */
        public final List<String> removed = new ArrayList<>();
        /** Chiavi sconosciute LASCIATE dov'erano (nei file che non si ripuliscono, es. i menu). */
        public final List<String> unknown = new ArrayList<>();
        /** Il testo del file dopo l'allineamento. */
        public String text = "";
        /** true se il file sul disco non si e' riusciti a leggerlo: non e' stato toccato. */
        public boolean unreadable = false;
        /** Rinomine dichiarate ma non applicabili da sole (la chiave ha cambiato anche genitore). */
        public final List<String> skipped = new ArrayList<>();
        /** Chiavi che sarebbero finite doppie: l'allineamento e' stato annullato. */
        public final List<String> duplicated = new ArrayList<>();

        public boolean changed() {
            return !added.isEmpty() || !renamed.isEmpty() || !removed.isEmpty();
        }
    }

    /**
     * Allinea TUTTI i file yml che il plugin si porta nel jar (config.yml, messages.yml, i menu...)
     * con quelli nella sua cartella dati. Da chiamare all'avvio e a ogni reload.
     *
     * <p>L'elenco non si scrive a mano: si guarda dentro il jar. Una risorsa yml aggiunta domani
     * viene allineata senza che nessuno debba ricordarsi di aggiungerla a una lista — che e' poi lo
     * stesso motivo per cui esiste questa classe.</p>
     *
     * <p>Si allineano solo i file che nella cartella dati ESISTONO gia': crearli e' compito del
     * plugin (saveDefaultConfig, saveResource), cosi' un menu cancellato apposta resta cancellato.</p>
     */
    public static void alignAll(JavaPlugin plugin) {
        Map<String, Map<String, String>> renames = renamesFromJar(plugin);
        for (String name : ymlInJar(plugin)) {
            if (name.equals(RENAMES_FILE)) continue;
            if (!new File(plugin.getDataFolder(), name).isFile()) continue;
            align(plugin, name, renames.getOrDefault(name, Map.of()), cleanable(name));
        }
    }

    /** Il file con le rinomine dichiarate dal plugin. */
    private static final String RENAMES_FILE = "renames.yml";

    /**
     * Su quali file si possono TOGLIERE le chiavi che il sorgente non ha piu'.
     *
     * <p>Quasi tutti: {@code config.yml}, {@code messages.yml}, {@code modules.yml} e i file delle
     * singole funzioni ({@code tablist.yml}...) hanno uno schema fisso — ogni chiave la legge il
     * codice — quindi una che nel sorgente non c'e' piu' e' una riga morta, e una riga morta e' solo
     * una trappola per chi configura.</p>
     *
     * <p>Le eccezioni sono i CATALOGHI, e si elencano qui: {@code menus/*.yml} e
     * {@code sanctions.yml} li allunga lo staff, e li' le voci in piu' sono lavoro suo, non residui.
     * La lista e' fatta cosi', per esclusione, perche' i file a schema fisso crescono — un plugin
     * aggiunge il file di una funzione nuova — mentre i cataloghi sono questi e si sanno.</p>
     */
    private static boolean cleanable(String fileName) {
        return !fileName.startsWith("menus/") && !fileName.equals("sanctions.yml");
    }

    /**
     * Le rinomine dichiarate in {@code renames.yml} dentro il jar: una sezione per file, e dentro
     * {@code vecchio.percorso: nuovo.percorso}. Non si indovinano guardando i valori — si scrivono
     * nello stesso commit in cui si rinomina nel codice.
     */
    private static Map<String, Map<String, String>> renamesFromJar(JavaPlugin plugin) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        String text = resource(plugin, RENAMES_FILE);
        if (text == null) return out;
        try (java.io.InputStream in = plugin.getResource(RENAMES_FILE)) {
            org.bukkit.configuration.file.YamlConfiguration cfg =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                            new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            for (String file : cfg.getKeys(false)) {
                org.bukkit.configuration.ConfigurationSection sec = cfg.getConfigurationSection(file);
                if (sec == null) continue;
                Map<String, String> coppie = new LinkedHashMap<>();
                for (String vecchio : sec.getKeys(true)) {
                    String nuovo = sec.getString(vecchio);
                    if (nuovo != null && !nuovo.isBlank()) coppie.put(vecchio, nuovo);
                }
                out.put(file, coppie);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("renames.yml illeggibile (" + e.getClass().getSimpleName()
                    + "): le rinomine non verranno applicate.");
        }
        return out;
    }

    /** I file yml dentro il jar del plugin, tolto il suo descrittore. */
    private static List<String> ymlInJar(JavaPlugin plugin) {
        List<String> out = new ArrayList<>();
        try {
            URI location = plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            File file = new File(location);
            if (!file.isFile()) return out; // avviato non impacchettato (sviluppo)
            try (JarFile jar = new JarFile(file)) {
                jar.stream()
                        .map(e -> e.getName())
                        .filter(n -> n.endsWith(".yml"))
                        .filter(n -> !n.equals("plugin.yml") && !n.equals("paper-plugin.yml"))
                        .forEach(out::add);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Non sono riuscito a leggere le risorse del jar ("
                    + e.getClass().getSimpleName() + "): i config non verranno allineati.");
        }
        return out;
    }

    /**
     * Allinea un file della cartella dati del plugin al suo gemello dentro il jar.
     *
     * @return l'esito, o null se non c'era niente da fare (file non nel jar, o appena creato)
     */
    public static Result align(JavaPlugin plugin, String fileName) {
        return align(plugin, fileName, Map.of(), cleanable(fileName));
    }

    public static Result align(JavaPlugin plugin, String fileName,
                               Map<String, String> renames, boolean cleanable) {
        String source = resource(plugin, fileName);
        if (source == null) return null;

        File onDisk = new File(plugin.getDataFolder(), fileName);
        // Se non c'e' ancora, lo creera' il plugin (saveDefaultConfig/saveResource) e sara'
        // allineato per definizione: qui non si inventano file.
        if (!onDisk.isFile()) return null;
        try {
            Result result = merge(source, Files.readString(onDisk.toPath(), StandardCharsets.UTF_8),
                    renames, cleanable);
            if (result.unreadable) {
                plugin.getLogger().warning(fileName + ": non ci ho capito niente (nessuna chiave"
                        + " riconosciuta) e NON l'ho toccato. Va guardato a mano: e' il file che il"
                        + " plugin legge davvero.");
                return result;
            }
            if (!result.duplicated.isEmpty()) {
                plugin.getLogger().warning(fileName + ": allineamento ANNULLATO, sarebbero uscite"
                        + " chiavi doppie (" + String.join(", ", result.duplicated) + ") e in YAML"
                        + " vince l'ultima. Il file e' rimasto com'era.");
                return result;
            }
            if (result.changed()) {
                // PRIMA la copia, poi si scrive: qualsiasi cosa cambi (aggiunta, rinomina,
                // cancellazione), la versione di prima resta li' accanto, con la data nel nome.
                backup(plugin, onDisk);
                Files.writeString(onDisk.toPath(), result.text, StandardCharsets.UTF_8);
            }
            // Ogni riga solo se ha davvero qualcosa da dire: il file puo' essere stato riscritto
            // per una rinomina o una pulizia, senza che sia stata aggiunta nessuna chiave.
            if (!result.added.isEmpty()) {
                plugin.getLogger().info(fileName + ": aggiunte le chiavi nuove di questa versione ("
                        + String.join(", ", result.added) + ").");
            }
            if (!result.renamed.isEmpty()) {
                plugin.getLogger().info(fileName + ": chiavi rinominate come nel codice, col valore"
                        + " che avevi scelto (" + String.join(", ", result.renamed) + ").");
            }
            if (!result.removed.isEmpty()) {
                plugin.getLogger().info(fileName + ": tolte le righe morte, che il codice non legge"
                        + " piu' (" + String.join(", ", result.removed) + "). La copia di prima e'"
                        + " nella cartella del plugin, col nome che finisce in .bak-<data>.");
            }
            if (!result.unknown.isEmpty()) {
                plugin.getLogger().info(fileName + ": sul server ci sono chiavi che il codice non legge"
                        + " piu' (rinominate o tolte): " + String.join(", ", result.unknown) + ".");
            }
            return result;
        } catch (Exception e) {
            // Un file che non si riesce ad allineare non deve impedire l'avvio: valgono i default.
            plugin.getLogger().warning("Non sono riuscito ad allineare " + fileName + " ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + "): per le chiavi"
                    + " mancanti il plugin usa i valori del jar.");
            return null;
        }
    }

    /** Quante copie tenere per file: le piu' vecchie si cancellano, se no la cartella si riempie. */
    private static final int COPIES_KEPT = 10;

    /**
     * Copia il file accanto a se stesso, col timestamp nel nome ({@code config.yml.bak-20260915-0412}).
     * Si fa prima di ogni scrittura: se l'allineamento sbaglia qualcosa — ed e' gia' successo — la
     * versione buona e' li' a un rename di distanza, senza dover cercare backup del server.
     */
    private static void backup(JavaPlugin plugin, File file) {
        try {
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            File copy = new File(file.getParentFile(), file.getName() + ".bak-" + stamp);
            Files.copy(file.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            dropOldCopies(file);
        } catch (Exception e) {
            plugin.getLogger().warning("Non sono riuscito a fare la copia di " + file.getName()
                    + " (" + e.getClass().getSimpleName() + "): il file NON viene toccato.");
            throw new IllegalStateException("backup fallito", e); // meglio non allineare che non poter tornare indietro
        }
    }

    /** Tiene solo le ultime {@link #COPIES_KEPT} copie di quel file. */
    private static void dropOldCopies(File file) {
        File[] copies = file.getParentFile().listFiles(
                (dir, found) -> found.startsWith(file.getName() + ".bak-"));
        if (copies == null || copies.length <= COPIES_KEPT) return;
        java.util.Arrays.sort(copies, java.util.Comparator.comparing(File::getName));
        for (int i = 0; i < copies.length - COPIES_KEPT; i++) copies[i].delete();
    }

    /** Il file dentro il jar, o null se non c'e'. */
    private static String resource(JavaPlugin plugin, String fileName) {
        try (InputStream in = plugin.getResource(fileName)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------- cuore (solo testo, testabile)

    /**
     * Unisce il sorgente nel testo sul disco: aggiunge solo le chiavi mancanti e non tocca una riga
     * di quelle che ci sono gia'.
     */
    public static Result merge(String source, String disk) {
        return merge(source, disk, Map.of(), false);
    }

    public static Result merge(String source, String disk,
                               Map<String, String> renames, boolean cleanable) {
        Result result = new Result();
        Node onDisk = parse(disk);

        // Rete di sicurezza. Un file non vuoto in cui non si riconosce NESSUNA chiave vuol dire che
        // non lo si e' capito, non che e' vuoto: accodarci il sorgente intero creerebbe doppioni, e
        // in YAML vince l'ultimo — cioe' si coprirebbero i valori veri (le credenziali del
        // database, per dirne una). In quel caso non si tocca niente.
        if (onDisk.children.isEmpty() && LOOKS_LIKE_KEY.matcher(disk).find()) {
            result.text = disk;
            result.unreadable = true;
            return result;
        }

        // 1) le rinomine dichiarate: il valore scelto sul server si porta dietro il nome nuovo.
        applyRenames(onDisk, renames, result);
        // 2) aggiunte (e, dove si puo', via le righe morte).
        List<String> out = new ArrayList<>();
        mergeNode(onDisk, parse(source), "", result, out, cleanable);
        if (!result.removed.isEmpty()) squeezeBlanks(out);
        result.text = String.join(disk.contains("\r\n") ? "\r\n" : "\n", out);

        // Seconda rete: se il risultato avesse comunque una chiave doppia, il file non si scrive.
        // Meglio un config che non si aggiorna di un config che si contraddice.
        List<String> twice = duplicates(parse(result.text));
        if (!twice.isEmpty()) {
            result.text = disk;
            result.duplicated.addAll(twice);
            result.added.clear();
        }
        return result;
    }

    /** Percorsi che compaiono piu' di una volta: in YAML vincerebbe l'ultimo. */
    private static List<String> duplicates(Node node) {
        List<String> out = new ArrayList<>();
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Object piece : node.content) {
            if (!(piece instanceof Node child)) continue;
            count.merge(child.key, 1, Integer::sum);
            for (String deeper : duplicates(child)) out.add(child.key + "." + deeper);
        }
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            if (e.getValue() > 1) out.add(e.getKey());
        }
        return out;
    }

    /**
     * Riscrive il blocco del nodo che sta sul disco infilando, al posto giusto, le chiavi che
     * esistono solo nel sorgente. Ricorsiva: vale per il documento intero e per ogni sezione.
     */
    private static void mergeNode(Node disk, Node source, String path, Result result,
                                  List<String> out, boolean cleanable) {
        out.addAll(disk.heading);
        out.addAll(disk.own);

        // Ogni chiave del sorgente che sul disco non c'e' va messa DOPO l'ultima sorella presente:
        // cosi' l'ordine del sorgente si conserva anche quando il file ne ha solo un pezzo.
        Map<String, List<Node>> toInsert = new LinkedHashMap<>();
        String lastPresent = "";
        for (Node s : source.children.values()) {
            if (disk.children.containsKey(s.key)) lastPresent = s.key;
            else toInsert.computeIfAbsent(lastPresent, k -> new ArrayList<>()).add(s);
        }

        int childIndent = disk.childIndent(source);
        boolean firstChildSeen = false;

        for (Object piece : disk.content) {
            if (piece instanceof String line) {
                out.add(line);
                continue;
            }
            Node d = (Node) piece;
            if (!firstChildSeen) {
                // Le chiavi nuove che nel sorgente vengono prima di tutte entrano qui.
                writeNew(toInsert.remove(""), childIndent, path, result, out);
                firstChildSeen = true;
            }
            String sotto = path.isEmpty() ? d.key : path + "." + d.key;
            Node s = source.children.get(d.key);
            if (s == null) {
                if (cleanable) {
                    // Riga morta: il codice non la legge piu'. Si toglie, e la copia di prima
                    // resta nella cartella del plugin (vedi backup()).
                    d.collectLeaves(sotto, result.removed);
                } else {
                    d.collectLeaves(sotto, result.unknown);
                    out.addAll(d.allLines());
                }
            } else {
                mergeNode(d, s, sotto, result, out, cleanable);
            }
            writeNew(toInsert.remove(d.key), childIndent, path, result, out);
        }
        // Sezione vuota sul disco (o tutte chiavi nuove): entrano in coda.
        writeNew(toInsert.remove(""), childIndent, path, result, out);
        for (List<Node> rest : toInsert.values()) writeNew(rest, childIndent, path, result, out);
    }

    /**
     * Applica le rinomine dichiarate: la chiave cambia nome e si tiene il valore che aveva sul
     * server. Se il nome nuovo c'e' gia' non si tocca niente (ci pensera' la pulizia a togliere
     * quello vecchio); se la chiave cambia anche genitore non si indovina, e lo si dice.
     */
    private static void applyRenames(Node disk, Map<String, String> renames, Result result) {
        for (Map.Entry<String, String> e : renames.entrySet()) {
            String oldPath = e.getKey(), newPath = e.getValue();
            String oldParent = parentOf(oldPath), newParent = parentOf(newPath);
            if (!oldParent.equals(newParent)) {
                result.skipped.add(oldPath + " -> " + newPath);
                continue;
            }
            Node parent = nodeAt(disk, oldParent);
            if (parent == null) continue;
            String oldLeaf = leafOf(oldPath), newLeaf = leafOf(newPath);
            Node node = parent.children.get(oldLeaf);
            if (node == null || parent.children.containsKey(newLeaf)) continue;
            node.renameTo(newLeaf);
            parent.rekey(oldLeaf, newLeaf);
            result.renamed.add(oldPath + " -> " + newPath);
        }
    }

    private static String parentOf(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 ? "" : path.substring(0, i);
    }

    private static String leafOf(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 ? path : path.substring(i + 1);
    }

    /** Il nodo a quel percorso ("" = la radice), o null. */
    private static Node nodeAt(Node root, String path) {
        Node node = root;
        if (path.isEmpty()) return node;
        for (String step : path.split("\\.")) {
            node = node.children.get(step);
            if (node == null) return null;
        }
        return node;
    }

    /** Dopo una cancellazione restano righe vuote in fila: se ne tiene una. */
    private static void squeezeBlanks(List<String> lines) {
        for (int i = lines.size() - 1; i > 0; i--) {
            if (lines.get(i).isBlank() && lines.get(i - 1).isBlank()) lines.remove(i);
        }
        while (!lines.isEmpty() && lines.get(0).isBlank()) lines.remove(0);
    }

    /** Scrive i blocchi nuovi (commento compreso), reindentati come vuole il file di destinazione. */
    private static void writeNew(List<Node> fresh, int indent, String path, Result result, List<String> out) {
        if (fresh == null) return;
        for (Node n : fresh) {
            List<String> lines = n.allLines();
            if (lines.isEmpty()) continue;
            // Una riga vuota di stacco, se non c'e' gia': se no il commento della chiave nuova
            // sembrerebbe appartenere a quella di sopra.
            if (!out.isEmpty() && !out.get(out.size() - 1).isBlank() && !lines.get(0).isBlank()) {
                out.add("");
            }
            int delta = indent - n.indent;
            for (String r : lines) out.add(r.isBlank() ? r : shift(r, delta));
            n.collectLeaves(path.isEmpty() ? n.key : path + "." + n.key, result.added);
        }
    }

    /** Sposta una riga di {@code delta} spazi (negativo = verso sinistra). */
    private static String shift(String line, int delta) {
        if (delta == 0) return line;
        if (delta > 0) return " ".repeat(delta) + line;
        int drop = Math.min(-delta, line.length() - line.stripLeading().length());
        return line.substring(drop);
    }

    // ------------------------------------------------------------------------------------ albero

    /**
     * Un Node: una chiave col suo commento, le sue righe di valore e, in ordine, tutto quello che
     * viene dopo (righe sciolte e chiavi figlie). La radice e' il documento intero.
     */
    private static final class Node {
        String key;
        final int indent;
        /** Commento attaccato sopra la chiave: se la chiave viene copiata, il commento la segue. */
        final List<String> heading = new ArrayList<>();
        /** La riga della chiave e le righe del suo valore (elenchi, stringhe spezzate). */
        final List<String> own = new ArrayList<>();
        /** Cio' che segue, in ordine: String (riga sciolta) oppure Node (chiave figlia). */
        final List<Object> content = new ArrayList<>();
        final Map<String, Node> children = new LinkedHashMap<>();
        /**
         * true se il valore di questa chiave e' un elenco ({@code - ...}). Dentro un elenco le righe
         * tipo {@code type: mute} NON sono chiavi figlie: sono il contenuto di una voce. Trattarle
         * come chiavi faceva vedere doppioni dove non ce n'erano (sanctions.yml ha piu' voci con
         * gli stessi campi) e avrebbe potuto far infilare roba dentro un elenco.
         */
        boolean list = false;

        Node(String key, int indent) {
            this.key = key;
            this.indent = indent;
        }

        void add(Node child) {
            content.add(child);
            children.put(child.key, child);
        }

        /** Riscrive il nome sulla riga della chiave, lasciando indentazione, valore e commento. */
        void renameTo(String newLeaf) {
            own.set(0, own.get(0).replaceFirst("^([ \\t]*)" + Pattern.quote(key) + ":",
                    "$1" + Matcher.quoteReplacement(newLeaf) + ":"));
            key = newLeaf;
        }

        /** Cambia la chiave nella mappa dei figli tenendo l'ordine. */
        void rekey(String oldLeaf, String newLeaf) {
            Map<String, Node> nuovi = new LinkedHashMap<>();
            for (Map.Entry<String, Node> e : children.entrySet()) {
                if (e.getKey().equals(oldLeaf)) nuovi.put(newLeaf, e.getValue());
                else nuovi.put(e.getKey(), e.getValue());
            }
            children.clear();
            children.putAll(nuovi);
        }

        List<String> allLines() {
            List<String> out = new ArrayList<>(heading);
            out.addAll(own);
            for (Object piece : content) {
                if (piece instanceof String line) out.add(line);
                else out.addAll(((Node) piece).allLines());
            }
            return out;
        }

        /** Percorsi completi delle chiavi-foglia di questo ramo (per i messaggi nel log). */
        void collectLeaves(String mine, List<String> into) {
            if (children.isEmpty()) {
                into.add(mine);
                return;
            }
            for (Node f : children.values()) f.collectLeaves(mine + "." + f.key, into);
        }

        /** Di quanto indentare i figli: come quelli che ci sono gia', se no come nel sorgente. */
        int childIndent(Node source) {
            for (Node f : children.values()) return f.indent;
            for (Node f : source.children.values()) return indent + (f.indent - source.indent);
            return indent + 2;
        }
    }

    /**
     * Legge il testo come albero di chiavi. Non e' un parser YAML: serve solo a sapere dove comincia
     * e dove finisce il blocco di ogni chiave, cosi' il file puo' essere rimesso insieme identico.
     * Tutto cio' che non apre una chiave (elenchi {@code - ...}, righe spezzate, commenti, righe
     * vuote) resta attaccato dov'era.
     */
    private static Node parse(String text) {
        Node root = new Node("", -2);
        List<Node> stack = new ArrayList<>();
        stack.add(root);
        List<String> pending = new ArrayList<>(); // commenti e righe vuote in attesa della loro chiave

        // I file scritti da Windows finiscono le righe con \r\n: senza toglierlo la riga di
        // una chiave non combacia col regex, il file sembra non averne NESSUNA e si finisce
        // per accodarci tutto il sorgente. Le righe si tengono senza \r; il file poi si
        // riscrive con i fine riga che aveva.
        for (String line : text.split("\r?\n", -1)) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                pending.add(line);
                continue;
            }
            Matcher m = KEY_LINE.matcher(line);
            Node top = stack.get(stack.size() - 1);
            if (!m.matches()) {
                // Valore su piu' righe (elenco, stringa spezzata): e' dell'ultima chiave aperta.
                top.content.addAll(pending);
                pending.clear();
                if (line.stripLeading().startsWith("-")) top.list = true;
                if (top.content.isEmpty()) top.own.add(line);
                else top.content.add(line);
                continue;
            }
            int indent = m.group(1).length();
            while (stack.size() > 1 && stack.get(stack.size() - 1).indent >= indent) {
                stack.remove(stack.size() - 1);
            }
            Node parent = stack.get(stack.size() - 1);
            if (parent.list && parent.indent < indent) {
                // Siamo dentro una voce di elenco (es. "- points: 10" e sotto "type: mute"):
                // e' contenuto della chiave che regge l'elenco, non una chiave sua figlia.
                parent.content.addAll(pending);
                pending.clear();
                parent.content.add(line);
                continue;
            }
            Node node = new Node(m.group(2), indent);
            // Il commento ATTACCATO alla chiave (senza righe vuote in mezzo) e' suo e la segue se
            // viene copiata; quello staccato resta dov'e', che spesso e' l'intestazione del file
            // o il titolo di una sezione.
            int cut = pending.size();
            while (cut > 0 && !pending.get(cut - 1).isBlank()) cut--;
            parent.content.addAll(pending.subList(0, cut));
            node.heading.addAll(pending.subList(cut, pending.size()));
            pending.clear();
            node.own.add(line);
            parent.add(node);
            stack.add(node);
        }
        root.content.addAll(pending); // coda del file
        return root;
    }
}
