package com.teolo.magixauth.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Il capitolo di questo plugin nella "Guida per amministratori" del sito, <b>e</b> il README
 * che finisce nella cartella del plugin: un'unica scrittura, due formati.
 *
 * <p><b>Regola del progetto:</b> README e guida devono coincidere sempre. Per questo non
 * esistono come due testi separati da tenere allineati a mano — nascono dalla stessa chiamata,
 * e chi cambia l'uno cambia l'altro perche' sono la stessa cosa scritta due volte dalla
 * macchina: {@code plugins/<Nome>/guida-staff.html} per il sito e
 * {@code plugins/<Nome>/README.md} per chi guarda i file del server.</p>
 *
 * <p>Questa classe e' lo <b>stesso file</b> in ogni plugin Magix (cambia solo il {@code package}),
 * come {@code Help}: se la si modifica in uno, va riportata in tutti. Vedi
 * {@code plugins-src/GUIDA-STAFF.md} e {@code plugins-src/STILE-MAGIX.md}.</p>
 *
 * <p>La parte volatile — comandi, permessi, valori di configurazione, versione — non si
 * ricopia: si <b>legge</b> dal plugin.yml, dall'aiuto in gioco e dal config vivo, cosi' la
 * guida non puo' raccontare una versione che non esiste piu'.</p>
 *
 * <p>Nel testo si puo' usare <code>**grassetto**</code> per mettere in risalto le parole che
 * contano: diventa grassetto sia nella pagina sia nel README.</p>
 *
 * <p>Uso tipico, in fondo a {@code onEnable()}:</p>
 * <pre>
 * StaffGuide.crea(this, "MagixTime — ora e stagioni reali", 60)
 *     .intro("A cosa serve, in due righe oneste.")
 *     .sezione("Come funziona", "…")
 *     .comandiDettagliati()
 *     .comandi()
 *     .permessi()
 *     .impostazioni("chiave.uno", "cosa cambia")
 *     .guasto("Il problema tipico", "Cosa fare")
 *     .mai("La trappola da non fare")
 *     .scrivi();
 * </pre>
 */
public final class StaffGuide {

    private final JavaPlugin plugin;
    private final String title;
    private final int order;
    private final YamlConfiguration pluginYml;

    private final StringBuilder html = new StringBuilder();
    private final StringBuilder md = new StringBuilder();
    private final List<String[]> issues = new ArrayList<>();
    private final List<String> never = new ArrayList<>();
    /** Se impostato, ogni testo scritto a mano passa di qui: i {{segnaposto}} diventano i valori veri
     *  del config (vedi {@link ConfigValues}). Va chiamato SUBITO dopo crea(), prima dei testi. */
    private ConfigValues values;

    private StaffGuide(JavaPlugin plugin, String title, int order) {
        this.plugin = plugin;
        this.title = title;
        this.order = order;
        this.pluginYml = readPluginYml(plugin);
    }

    /**
     * @param titolo come compare nell'indice del gestionale (es. "MagixTime — ora e stagioni")
     * @param ordine posizione nell'elenco: piu' basso = piu' in alto. Chi lo staff tocca ogni
     *               giorno sta in cima.
     */
    public static StaffGuide create(JavaPlugin plugin, String title, int order) {
        return new StaffGuide(plugin, title, order);
    }

    /**
     * Lega la guida al config: da qui in poi i testi possono contenere segnaposto tipo
     * {@code {{cfg:una.chiave}}} o {@code {{ore:altra.chiave}}}, sostituiti col valore vero al momento
     * della pubblicazione. Serve perche' una guida che cita numeri scritti a mano comincia a mentire
     * alla prima modifica del config, e nessuno se ne accorge.
     */
    public StaffGuide values(ConfigValues values) { this.values = values; return this; }

    /** Applica i segnaposto, se la guida e' stata legata al config con {@link #valori(ConfigValues)}. */
    private String substitute(String text) { return values == null ? text : values.apply(text); }

    /** Due righe oneste su cosa fa il plugin, senza gergo. Va per prima e si vede piu' grande. */
    public StaffGuide intro(String text) {
        text = substitute(text);
        html.append("<p class=\"guida-apertura\">").append(ric(text)).append("</p>\n");
        md.append("> ").append(text).append("\n\n");
        return this;
    }

    /** Una sezione scritta a mano: titolo e uno o piu' paragrafi. */
    public StaffGuide section(String title, String... paragraphs) {
        title = substitute(title);
        html.append("<h4>").append(escapeHtml(title)).append("</h4>\n");
        md.append("## ").append(title).append("\n\n");
        for (String raw : paragraphs) {
            String p = substitute(raw);
            html.append("<p>").append(ric(p)).append("</p>\n");
            md.append(p).append("\n\n");
        }
        return this;
    }

    /**
     * L'elenco COMPLETO dei sottocomandi, preso da {@code messages.yml} — la stessa fonte che
     * alimenta {@code /<comando> help} in gioco.
     *
     * <p>Serve perche' il plugin.yml dichiara un comando solo ({@code /f}, {@code /mtime}) mentre
     * il lavoro vero lo fanno le decine di sottocomandi: senza questo, la guida direbbe che
     * MagixFactions ha un comando e basta.</p>
     */
    public StaffGuide detailedCommands() {
        ConfigurationSection sections = readHelpSections();
        if (sections == null) {
            return this;
        }
        html.append("<h4>Tutti i comandi</h4>\n<p class=\"guida-nota\">Sono gli stessi che si vedono "
                + "con il comando di aiuto in gioco: escono da quel file, quindi non possono restare indietro.</p>\n");
        md.append("## Tutti i comandi\n\nSono gli stessi che si vedono con il comando di aiuto in gioco.\n\n");

        for (String key : sections.getKeys(false)) {
            ConfigurationSection s = sections.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            List<String> entries = s.getStringList("entries");
            if (entries.isEmpty()) {
                continue;
            }
            boolean staffOnly = s.getBoolean("staff", false);
            String name = s.getString("title", key);

            html.append("<h5>").append(escapeHtml(name))
                .append(staffOnly ? " <span class=\"guida-tag\">solo staff</span>" : "").append("</h5>\n");
            md.append("### ").append(name).append(staffOnly ? " (solo staff)" : "").append("\n\n");

            openTable("Comando", "Cosa fa");
            for (String row : entries) {
                int cut = row.indexOf("::");
                String command = cut < 0 ? row.trim() : row.substring(0, cut).trim();
                String whatItDoes = cut < 0 ? "" : row.substring(cut + 2).trim();
                row(code(command), whatItDoes);
            }
            closeTable();
        }
        return this;
    }

    /**
     * Sottocomandi elencati a mano, per i plugin che non hanno un aiuto in gioco da cui leggerli.
     *
     * @param titolo              il titolo del gruppo (es. "I comandi di /mg")
     * @param comandoESpiegazione coppie: il comando com'e' da scrivere, poi cosa fa
     */
    public StaffGuide subcommands(String title, String... commandAndExplanation) {
        if (commandAndExplanation.length < 2) {
            return this;
        }
        html.append("<h4>").append(escapeHtml(title)).append("</h4>\n");
        md.append("## ").append(title).append("\n\n");
        openTable("Comando", "Cosa fa");
        for (int i = 0; i + 1 < commandAndExplanation.length; i += 2) {
            row(code(commandAndExplanation[i]), commandAndExplanation[i + 1]);
        }
        closeTable();
        return this;
    }

    /** Tabella dei comandi dichiarati nel plugin.yml: comando, cosa fa, permesso. */
    public StaffGuide commands() {
        ConfigurationSection section = pluginYml == null ? null : pluginYml.getConfigurationSection("commands");
        if (section == null) {
            return this;
        }
        html.append("<h4>Comandi registrati</h4>\n");
        md.append("## Comandi registrati\n\n");
        openTable("Comando", "Cosa fa", "Permesso");
        for (String name : section.getKeys(false)) {
            ConfigurationSection c = section.getConfigurationSection(name);
            if (c == null) {
                continue;
            }
            List<String> alias = c.getStringList("aliases");
            String label = "/" + name + (alias.isEmpty() ? "" : " (" + String.join(", ", alias) + ")");
            row(code(label), c.getString("description", "—"), code(c.getString("permission", "—")));
        }
        closeTable();
        return this;
    }

    /** Tabella dei permessi, presa dal plugin.yml: chi li ha di serie e a cosa servono. */
    public StaffGuide permissions() {
        ConfigurationSection section = pluginYml == null ? null : pluginYml.getConfigurationSection("permissions");
        if (section == null) {
            return this;
        }
        html.append("<h4>Chi può fare cosa</h4>\n");
        md.append("## Chi può fare cosa\n\n");
        openTable("Permesso", "Significato", "Di serie");
        for (String name : section.getKeys(false)) {
            ConfigurationSection p = section.getConfigurationSection(name);
            if (p == null) {
                continue;
            }
            row(code(name), p.getString("description", "—"), describeDefault(p.getString("default", "op")));
        }
        closeTable();
        return this;
    }

    /**
     * <b>Tutte</b> le chiavi del {@code config.yml}, col valore <b>attualmente in uso</b> letto dal
     * config vivo (non quello di esempio: e' la differenza fra una guida e un depliant) e la
     * spiegazione presa dal <b>commento scritto nel config stesso</b>.
     *
     * <p>Prima era un elenco scelto a mano: comparivano le cinque o sei chiavi che qualcuno si era
     * ricordato di citare, e le altre cento non esistevano per chi leggeva la guida. Ora l'elenco e'
     * COMPLETO e si mantiene da solo — una chiave nuova nel config compare qui senza che nessuno tocchi
     * il codice, e la sua spiegazione e' il commento che quella chiave deve avere comunque (regola gia'
     * imposta da {@code controlla-config.py}).</p>
     *
     * @param chiaveESpiegazione coppie FACOLTATIVE: chiave del config, poi cosa cambia. Servono solo a
     *                           dare una spiegazione migliore di quella del commento alle chiavi che lo
     *                           staff tocca ogni giorno; tutte le altre restano comunque in elenco.
     */
    public StaffGuide settings(String... keyAndExplanation) {
        html.append("<h4>Impostazioni</h4>\n");
        md.append("## Impostazioni\n\n");
        String note = "Sono TUTTE le chiavi del config.yml, col valore in uso adesso e la spiegazione "
                + "presa dal commento del file. L'elenco si genera da solo: una chiave nuova compare "
                + "qui da sola, e nessuna puo' restare fuori.";
        html.append("<p class=\"guida-nota\">").append(escapeHtml(note)).append("</p>\n");
        md.append(note).append("\n\n");
        settingsTable(plugin.getConfig(), byHand(keyAndExplanation));
        return this;
    }

    /** Le coppie passate a mano, come mappa chiave -> spiegazione migliore. */
    private static java.util.Map<String, String> byHand(String... keyAndExplanation) {
        java.util.Map<String, String> fuori = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < keyAndExplanation.length; i += 2) {
            fuori.put(keyAndExplanation[i], keyAndExplanation[i + 1]);
        }
        return fuori;
    }

    /** La tabella vera: una riga per ogni chiave che ha un valore (le sezioni non ne hanno uno). */
    private void settingsTable(org.bukkit.configuration.Configuration conf,
                                     java.util.Map<String, String> byHand) {
        if (conf == null) {
            return;
        }
        openTable("Chiave", "Ora vale", "Cosa cambia");
        for (String key : conf.getKeys(true)) {
            if (conf.isConfigurationSection(key)) {
                continue;   // una sezione non ha un valore: parlano le chiavi che contiene
            }
            String explanation = byHand.get(key);
            if (explanation == null) {
                explanation = comment(conf, key);
            }
            row(code(key), code(readableValue(conf.get(key))), explanation);
        }
        closeTable();
    }

    /**
     * Il commento che sta nel config sopra (o accanto) alla chiave: e' gia' la spiegazione giusta,
     * scritta dove la legge chi configura, e non va riscritta da un'altra parte. Se la chiave non ha
     * un commento suo si sale alla sezione che la contiene, che di norma spiega il blocco intero.
     */
    private static String comment(ConfigurationSection conf, String key) {
        java.util.List<String> rows = new java.util.ArrayList<>(conf.getComments(key));
        rows.addAll(conf.getInlineComments(key));
        String bubbleUp = key;
        while (rows.isEmpty() && bubbleUp.contains(".")) {
            bubbleUp = bubbleUp.substring(0, bubbleUp.lastIndexOf('.'));
            rows = new java.util.ArrayList<>(conf.getComments(bubbleUp));
        }
        StringBuilder sb = new StringBuilder();
        for (String r : rows) {
            String t = r == null ? "" : r.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        // Finisce in una cella: le barre verticali spaccherebbero la tabella del markdown.
        return sb.length() == 0 ? "—" : sb.toString().replace("|", "\\|");
    }

    /** Il valore in una cella: niente a capo, e le sfilze lunghe (messaggi, liste) si accorciano. */
    private static String readableValue(Object value) {
        if (value == null) {
            return "—";
        }
        String t = String.valueOf(value).replace("\n", " ").replace("|", "\\|").trim();
        return t.length() <= 140 ? t : t.substring(0, 139) + "…";
    }

    /**
     * Come {@link #impostazioni(String...)}, ma su un ALTRO file di configurazione: serve ai
     * plugin che ne hanno piu' di uno (MagixGuard tiene le sanzioni in {@code sanzioni.yml},
     * separate dalla profilazione). Anche qui l'elenco e' COMPLETO.
     *
     * @param conf   il file gia' caricato
     * @param titolo il titolo della sezione, es. "Impostazioni delle sanzioni"
     */
    public StaffGuide settingsFrom(org.bukkit.configuration.Configuration conf, String title,
                                     String... keyAndExplanation) {
        if (conf == null) {
            return this;
        }
        html.append("<h4>").append(escapeHtml(title)).append("</h4>\n");
        md.append("## ").append(title).append("\n\n");
        settingsTable(conf, byHand(keyAndExplanation));
        return this;
    }

    /** Un guasto tipico di questo plugin e cosa fare. Vanno in fondo, tutti insieme. */
    public StaffGuide issue(String problem, String whatToDo) {
        issues.add(new String[] { problem, whatToDo });
        return this;
    }

    /** Una trappola vera: cose che sembrano innocue e non lo sono. */
    public StaffGuide never(String what) {
        never.add(what);
        return this;
    }

    /**
     * Scrive il capitolo per il sito e il README nella cartella del plugin.
     * Se qualcosa va storto lo dice nel log e basta: una guida mancante non deve mai
     * impedire a un plugin di partire.
     */
    public void write() {
        if (!issues.isEmpty()) {
            html.append("<h4>Quando qualcosa non va</h4>\n<div class=\"guida-casi\">\n");
            md.append("## Quando qualcosa non va\n\n");
            for (String[] g : issues) {
                html.append("<div class=\"guida-caso\"><p class=\"guida-caso-tit\">").append(ric(g[0]))
                    .append("</p><p>").append(ric(g[1])).append("</p></div>\n");
                md.append("**").append(g[0]).append("** — ").append(g[1]).append("\n\n");
            }
            html.append("</div>\n");
        }
        if (!never.isEmpty()) {
            html.append("<h4>Cosa non fare</h4>\n<ul class=\"guida-mai\">\n");
            md.append("## Cosa non fare\n\n");
            for (String m : never) {
                html.append("<li>").append(ric(m)).append("</li>\n");
                md.append("- ").append(m).append("\n");
            }
            html.append("</ul>\n");
            md.append("\n");
        }

        String version = pluginYml == null ? "" : pluginYml.getString("version", "");
        String document = "<!--guida\n"
                + "title: " + title + "\n"
                + "version: " + version + "\n"
                + "sort_order: " + order + "\n"
                + "-->\n" + html;

        String readme = "# " + title + "\n\n"
                + "_Versione " + version + ". Questo file e la Guida per amministratori del sito "
                + "sono generati insieme dal plugin: non si modificano a mano, e non possono divergere._\n\n"
                + md;

        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                return;
            }
            Files.writeString(plugin.getDataFolder().toPath().resolve("guida-staff.html"),
                    document, StandardCharsets.UTF_8);
            Files.writeString(plugin.getDataFolder().toPath().resolve("README.md"),
                    readme, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Guida per amministratori non scritta: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ tabelle

    private void openTable(String... headers) {
        html.append("<table><thead><tr>");
        for (String t : headers) {
            html.append("<th>").append(escapeHtml(t)).append("</th>");
        }
        html.append("</tr></thead><tbody>\n");

        md.append("| ").append(String.join(" | ", headers)).append(" |\n|");
        for (int i = 0; i < headers.length; i++) {
            md.append("---|");
        }
        md.append("\n");
    }

    private void row(String... cells) {
        html.append("<tr>");
        for (String c : cells) {
            html.append("<td>").append(ric(c)).append("</td>");
        }
        html.append("</tr>\n");
        md.append("| ").append(String.join(" | ", cells)).append(" |\n");
    }

    private void closeTable() {
        html.append("</tbody></table>\n");
        md.append("\n");
    }

    /** Marca un pezzo come codice: nel markdown coi backtick, nell'HTML con &lt;code&gt;. */
    private static String code(String s) {
        return "`" + (s == null ? "" : s) + "`";
    }

    // ------------------------------------------------------------------ lettura

    /** Il plugin.yml dentro il jar: e' la fonte di comandi, permessi e versione. */
    private static YamlConfiguration readPluginYml(JavaPlugin plugin) {
        try (InputStream in = plugin.getResource("plugin.yml")) {
            if (in == null) {
                return null;
            }
            // Il separatore di percorso passa da '.' a '/': i nomi dei permessi contengono il
            // punto (magixtime.use) e con quello di serie YAML li spezzerebbe in sezioni
            // annidate, facendoli comparire nella guida come un troncone senza descrizione.
            YamlConfiguration yml = new YamlConfiguration();
            yml.options().pathSeparator('/');
            yml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yml;
        } catch (IOException | InvalidConfigurationException e) {
            return null;
        }
    }

    /**
     * Le sezioni dell'aiuto in gioco, da {@code messages.yml} nella cartella del plugin.
     * Si legge il file SUL DISCO, non quello nel jar: e' quello che il server sta usando davvero.
     */
    private ConfigurationSection readHelpSections() {
        java.io.File f = new java.io.File(plugin.getDataFolder(), "messages.yml");
        if (!f.isFile()) {
            return null;
        }
        return YamlConfiguration.loadConfiguration(f).getConfigurationSection("help.sections");
    }

    /** "op" e "true" non dicono niente a chi non e' un programmatore. */
    private static String describeDefault(String value) {
        return switch (String.valueOf(value).toLowerCase()) {
            case "true" -> "tutti";
            case "false" -> "nessuno";
            case "not op" -> "chi non è operatore";
            default -> "operatori";
        };
    }

    // ------------------------------------------------------------------ testo

    /**
     * Testo arricchito: prima si mette in sicurezza (niente HTML dall'esterno), poi si
     * riconoscono <code>**grassetto**</code> e <code>`codice`</code>. In quest'ordine, sempre:
     * al contrario, un testo che contenesse un tag lo vedremmo comparire nella pagina.
     */
    private static String ric(String s) {
        if (s == null) {
            return "";
        }
        String out = escapeHtml(s);
        out = replacePairs(out, "**", "<strong>", "</strong>");
        out = replacePairs(out, "`", "<code>", "</code>");
        return out;
    }

    /** Sostituisce le coppie di delimitatori con i due tag, lasciando in pace i delimitatori spaiati. */
    private static String replacePairs(String text, String sign, String opens, String closes) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        boolean opened = false;
        while (i < text.length()) {
            int p = text.indexOf(sign, i);
            if (p < 0) {
                out.append(text, i, text.length());
                break;
            }
            // Un delimitatore che non ha il suo compagno resta com'e': meglio un asterisco
            // visibile che mezza pagina in grassetto.
            if (!opened && text.indexOf(sign, p + sign.length()) < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, p).append(opened ? closes : opens);
            opened = !opened;
            i = p + sign.length();
        }
        return out.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
