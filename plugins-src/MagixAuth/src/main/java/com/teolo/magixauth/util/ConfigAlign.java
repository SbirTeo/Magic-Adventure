package com.teolo.magixauth.util;

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
 *       a schema fisso ({@code config.yml} e {@code messages.yml}): una riga che nessuno legge e'
 *       solo una trappola per chi configura. Prima di cancellare fa una copia del file col
 *       timestamp, e scrive nel log che cosa ha tolto.</li>
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
        for (String name : ymlInJar(plugin)) {
            if (new File(plugin.getDataFolder(), name).isFile()) align(plugin, name);
        }
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
        String source = resource(plugin, fileName);
        if (source == null) return null;

        File onDisk = new File(plugin.getDataFolder(), fileName);
        // Se non c'e' ancora, lo creera' il plugin (saveDefaultConfig/saveResource) e sara'
        // allineato per definizione: qui non si inventano file.
        if (!onDisk.isFile()) return null;
        try {
            Result result = merge(source, Files.readString(onDisk.toPath(), StandardCharsets.UTF_8));
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
                Files.writeString(onDisk.toPath(), result.text, StandardCharsets.UTF_8);
                plugin.getLogger().info(fileName + ": aggiunte le chiavi nuove di questa versione ("
                        + String.join(", ", result.added) + ").");
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

        List<String> out = new ArrayList<>();
        mergeNode(onDisk, parse(source), "", result, out);
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
    private static void mergeNode(Node disk, Node source, String path, Result result, List<String> out) {
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
                d.collectLeaves(sotto, result.unknown);
                out.addAll(d.allLines());
            } else {
                mergeNode(d, s, sotto, result, out);
            }
            writeNew(toInsert.remove(d.key), childIndent, path, result, out);
        }
        // Sezione vuota sul disco (o tutte chiavi nuove): entrano in coda.
        writeNew(toInsert.remove(""), childIndent, path, result, out);
        for (List<Node> rest : toInsert.values()) writeNew(rest, childIndent, path, result, out);
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
        final String key;
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
