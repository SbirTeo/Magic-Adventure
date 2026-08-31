package com.teolo.magixtime.util;

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
    private final String titolo;
    private final int ordine;
    private final YamlConfiguration pluginYml;

    private final StringBuilder html = new StringBuilder();
    private final StringBuilder md = new StringBuilder();
    private final List<String[]> guasti = new ArrayList<>();
    private final List<String> mai = new ArrayList<>();
    /** Se impostato, ogni testo scritto a mano passa di qui: i {{segnaposto}} diventano i valori veri
     *  del config (vedi {@link ConfigValues}). Va chiamato SUBITO dopo crea(), prima dei testi. */
    private ConfigValues valori;

    private StaffGuide(JavaPlugin plugin, String titolo, int ordine) {
        this.plugin = plugin;
        this.titolo = titolo;
        this.ordine = ordine;
        this.pluginYml = leggiPluginYml(plugin);
    }

    /**
     * @param titolo come compare nell'indice del gestionale (es. "MagixTime — ora e stagioni")
     * @param ordine posizione nell'elenco: piu' basso = piu' in alto. Chi lo staff tocca ogni
     *               giorno sta in cima.
     */
    public static StaffGuide crea(JavaPlugin plugin, String titolo, int ordine) {
        return new StaffGuide(plugin, titolo, ordine);
    }

    /**
     * Lega la guida al config: da qui in poi i testi possono contenere segnaposto tipo
     * {@code {{cfg:una.chiave}}} o {@code {{ore:altra.chiave}}}, sostituiti col valore vero al momento
     * della pubblicazione. Serve perche' una guida che cita numeri scritti a mano comincia a mentire
     * alla prima modifica del config, e nessuno se ne accorge.
     */
    public StaffGuide valori(ConfigValues valori) { this.valori = valori; return this; }

    /** Applica i segnaposto, se la guida e' stata legata al config con {@link #valori(ConfigValues)}. */
    private String sost(String testo) { return valori == null ? testo : valori.applica(testo); }

    /** Due righe oneste su cosa fa il plugin, senza gergo. Va per prima e si vede piu' grande. */
    public StaffGuide intro(String testo) {
        testo = sost(testo);
        html.append("<p class=\"guida-apertura\">").append(ric(testo)).append("</p>\n");
        md.append("> ").append(testo).append("\n\n");
        return this;
    }

    /** Una sezione scritta a mano: titolo e uno o piu' paragrafi. */
    public StaffGuide sezione(String titolo, String... paragrafi) {
        titolo = sost(titolo);
        html.append("<h4>").append(esc(titolo)).append("</h4>\n");
        md.append("## ").append(titolo).append("\n\n");
        for (String grezzo : paragrafi) {
            String p = sost(grezzo);
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
    public StaffGuide comandiDettagliati() {
        ConfigurationSection sezioni = leggiSezioniAiuto();
        if (sezioni == null) {
            return this;
        }
        html.append("<h4>Tutti i comandi</h4>\n<p class=\"guida-nota\">Sono gli stessi che si vedono "
                + "con il comando di aiuto in gioco: escono da quel file, quindi non possono restare indietro.</p>\n");
        md.append("## Tutti i comandi\n\nSono gli stessi che si vedono con il comando di aiuto in gioco.\n\n");

        for (String chiave : sezioni.getKeys(false)) {
            ConfigurationSection s = sezioni.getConfigurationSection(chiave);
            if (s == null) {
                continue;
            }
            List<String> voci = s.getStringList("entries");
            if (voci.isEmpty()) {
                continue;
            }
            boolean soloStaff = s.getBoolean("staff", false);
            String nome = s.getString("title", chiave);

            html.append("<h5>").append(esc(nome))
                .append(soloStaff ? " <span class=\"guida-tag\">solo staff</span>" : "").append("</h5>\n");
            md.append("### ").append(nome).append(soloStaff ? " (solo staff)" : "").append("\n\n");

            apriTabella("Comando", "Cosa fa");
            for (String riga : voci) {
                int taglio = riga.indexOf("::");
                String comando = taglio < 0 ? riga.trim() : riga.substring(0, taglio).trim();
                String cosaFa = taglio < 0 ? "" : riga.substring(taglio + 2).trim();
                riga(codice(comando), cosaFa);
            }
            chiudiTabella();
        }
        return this;
    }

    /**
     * Sottocomandi elencati a mano, per i plugin che non hanno un aiuto in gioco da cui leggerli.
     *
     * @param titolo              il titolo del gruppo (es. "I comandi di /mg")
     * @param comandoESpiegazione coppie: il comando com'e' da scrivere, poi cosa fa
     */
    public StaffGuide sottocomandi(String titolo, String... comandoESpiegazione) {
        if (comandoESpiegazione.length < 2) {
            return this;
        }
        html.append("<h4>").append(esc(titolo)).append("</h4>\n");
        md.append("## ").append(titolo).append("\n\n");
        apriTabella("Comando", "Cosa fa");
        for (int i = 0; i + 1 < comandoESpiegazione.length; i += 2) {
            riga(codice(comandoESpiegazione[i]), comandoESpiegazione[i + 1]);
        }
        chiudiTabella();
        return this;
    }

    /** Tabella dei comandi dichiarati nel plugin.yml: comando, cosa fa, permesso. */
    public StaffGuide comandi() {
        ConfigurationSection sezione = pluginYml == null ? null : pluginYml.getConfigurationSection("commands");
        if (sezione == null) {
            return this;
        }
        html.append("<h4>Comandi registrati</h4>\n");
        md.append("## Comandi registrati\n\n");
        apriTabella("Comando", "Cosa fa", "Permesso");
        for (String nome : sezione.getKeys(false)) {
            ConfigurationSection c = sezione.getConfigurationSection(nome);
            if (c == null) {
                continue;
            }
            List<String> alias = c.getStringList("aliases");
            String etichetta = "/" + nome + (alias.isEmpty() ? "" : " (" + String.join(", ", alias) + ")");
            riga(codice(etichetta), c.getString("description", "—"), codice(c.getString("permission", "—")));
        }
        chiudiTabella();
        return this;
    }

    /** Tabella dei permessi, presa dal plugin.yml: chi li ha di serie e a cosa servono. */
    public StaffGuide permessi() {
        ConfigurationSection sezione = pluginYml == null ? null : pluginYml.getConfigurationSection("permissions");
        if (sezione == null) {
            return this;
        }
        html.append("<h4>Chi può fare cosa</h4>\n");
        md.append("## Chi può fare cosa\n\n");
        apriTabella("Permesso", "Significato", "Di serie");
        for (String nome : sezione.getKeys(false)) {
            ConfigurationSection p = sezione.getConfigurationSection(nome);
            if (p == null) {
                continue;
            }
            riga(codice(nome), p.getString("description", "—"), descriviDefault(p.getString("default", "op")));
        }
        chiudiTabella();
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
    public StaffGuide impostazioni(String... chiaveESpiegazione) {
        html.append("<h4>Impostazioni</h4>\n");
        md.append("## Impostazioni\n\n");
        String nota = "Sono TUTTE le chiavi del config.yml, col valore in uso adesso e la spiegazione "
                + "presa dal commento del file. L'elenco si genera da solo: una chiave nuova compare "
                + "qui da sola, e nessuna puo' restare fuori.";
        html.append("<p class=\"guida-nota\">").append(esc(nota)).append("</p>\n");
        md.append(nota).append("\n\n");
        tabellaImpostazioni(plugin.getConfig(), aMano(chiaveESpiegazione));
        return this;
    }

    /** Le coppie passate a mano, come mappa chiave -> spiegazione migliore. */
    private static java.util.Map<String, String> aMano(String... chiaveESpiegazione) {
        java.util.Map<String, String> fuori = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < chiaveESpiegazione.length; i += 2) {
            fuori.put(chiaveESpiegazione[i], chiaveESpiegazione[i + 1]);
        }
        return fuori;
    }

    /** La tabella vera: una riga per ogni chiave che ha un valore (le sezioni non ne hanno uno). */
    private void tabellaImpostazioni(org.bukkit.configuration.Configuration conf,
                                     java.util.Map<String, String> aMano) {
        if (conf == null) {
            return;
        }
        apriTabella("Chiave", "Ora vale", "Cosa cambia");
        for (String chiave : conf.getKeys(true)) {
            if (conf.isConfigurationSection(chiave)) {
                continue;   // una sezione non ha un valore: parlano le chiavi che contiene
            }
            String spiegazione = aMano.get(chiave);
            if (spiegazione == null) {
                spiegazione = commento(conf, chiave);
            }
            riga(codice(chiave), codice(valoreLeggibile(conf.get(chiave))), spiegazione);
        }
        chiudiTabella();
    }

    /**
     * Il commento che sta nel config sopra (o accanto) alla chiave: e' gia' la spiegazione giusta,
     * scritta dove la legge chi configura, e non va riscritta da un'altra parte. Se la chiave non ha
     * un commento suo si sale alla sezione che la contiene, che di norma spiega il blocco intero.
     */
    private static String commento(ConfigurationSection conf, String chiave) {
        java.util.List<String> righe = new java.util.ArrayList<>(conf.getComments(chiave));
        righe.addAll(conf.getInlineComments(chiave));
        String risalita = chiave;
        while (righe.isEmpty() && risalita.contains(".")) {
            risalita = risalita.substring(0, risalita.lastIndexOf('.'));
            righe = new java.util.ArrayList<>(conf.getComments(risalita));
        }
        StringBuilder sb = new StringBuilder();
        for (String r : righe) {
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
    private static String valoreLeggibile(Object valore) {
        if (valore == null) {
            return "—";
        }
        String t = String.valueOf(valore).replace("\n", " ").replace("|", "\\|").trim();
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
    public StaffGuide impostazioniDa(org.bukkit.configuration.Configuration conf, String titolo,
                                     String... chiaveESpiegazione) {
        if (conf == null) {
            return this;
        }
        html.append("<h4>").append(esc(titolo)).append("</h4>\n");
        md.append("## ").append(titolo).append("\n\n");
        tabellaImpostazioni(conf, aMano(chiaveESpiegazione));
        return this;
    }

    /** Un guasto tipico di questo plugin e cosa fare. Vanno in fondo, tutti insieme. */
    public StaffGuide guasto(String problema, String cosaFare) {
        guasti.add(new String[] { problema, cosaFare });
        return this;
    }

    /** Una trappola vera: cose che sembrano innocue e non lo sono. */
    public StaffGuide mai(String cosa) {
        mai.add(cosa);
        return this;
    }

    /**
     * Scrive il capitolo per il sito e il README nella cartella del plugin.
     * Se qualcosa va storto lo dice nel log e basta: una guida mancante non deve mai
     * impedire a un plugin di partire.
     */
    public void scrivi() {
        if (!guasti.isEmpty()) {
            html.append("<h4>Quando qualcosa non va</h4>\n<div class=\"guida-casi\">\n");
            md.append("## Quando qualcosa non va\n\n");
            for (String[] g : guasti) {
                html.append("<div class=\"guida-caso\"><p class=\"guida-caso-tit\">").append(ric(g[0]))
                    .append("</p><p>").append(ric(g[1])).append("</p></div>\n");
                md.append("**").append(g[0]).append("** — ").append(g[1]).append("\n\n");
            }
            html.append("</div>\n");
        }
        if (!mai.isEmpty()) {
            html.append("<h4>Cosa non fare</h4>\n<ul class=\"guida-mai\">\n");
            md.append("## Cosa non fare\n\n");
            for (String m : mai) {
                html.append("<li>").append(ric(m)).append("</li>\n");
                md.append("- ").append(m).append("\n");
            }
            html.append("</ul>\n");
            md.append("\n");
        }

        String versione = pluginYml == null ? "" : pluginYml.getString("version", "");
        String documento = "<!--guida\n"
                + "title: " + titolo + "\n"
                + "version: " + versione + "\n"
                + "sort_order: " + ordine + "\n"
                + "-->\n" + html;

        String readme = "# " + titolo + "\n\n"
                + "_Versione " + versione + ". Questo file e la Guida per amministratori del sito "
                + "sono generati insieme dal plugin: non si modificano a mano, e non possono divergere._\n\n"
                + md;

        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                return;
            }
            Files.writeString(plugin.getDataFolder().toPath().resolve("guida-staff.html"),
                    documento, StandardCharsets.UTF_8);
            Files.writeString(plugin.getDataFolder().toPath().resolve("README.md"),
                    readme, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("Guida per amministratori non scritta: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ tabelle

    private void apriTabella(String... intestazioni) {
        html.append("<table><thead><tr>");
        for (String t : intestazioni) {
            html.append("<th>").append(esc(t)).append("</th>");
        }
        html.append("</tr></thead><tbody>\n");

        md.append("| ").append(String.join(" | ", intestazioni)).append(" |\n|");
        for (int i = 0; i < intestazioni.length; i++) {
            md.append("---|");
        }
        md.append("\n");
    }

    private void riga(String... celle) {
        html.append("<tr>");
        for (String c : celle) {
            html.append("<td>").append(ric(c)).append("</td>");
        }
        html.append("</tr>\n");
        md.append("| ").append(String.join(" | ", celle)).append(" |\n");
    }

    private void chiudiTabella() {
        html.append("</tbody></table>\n");
        md.append("\n");
    }

    /** Marca un pezzo come codice: nel markdown coi backtick, nell'HTML con &lt;code&gt;. */
    private static String codice(String s) {
        return "`" + (s == null ? "" : s) + "`";
    }

    // ------------------------------------------------------------------ lettura

    /** Il plugin.yml dentro il jar: e' la fonte di comandi, permessi e versione. */
    private static YamlConfiguration leggiPluginYml(JavaPlugin plugin) {
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
    private ConfigurationSection leggiSezioniAiuto() {
        java.io.File f = new java.io.File(plugin.getDataFolder(), "messages.yml");
        if (!f.isFile()) {
            return null;
        }
        return YamlConfiguration.loadConfiguration(f).getConfigurationSection("help.sections");
    }

    /** "op" e "true" non dicono niente a chi non e' un programmatore. */
    private static String descriviDefault(String valore) {
        return switch (String.valueOf(valore).toLowerCase()) {
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
        String out = esc(s);
        out = sostituisciCoppie(out, "**", "<strong>", "</strong>");
        out = sostituisciCoppie(out, "`", "<code>", "</code>");
        return out;
    }

    /** Sostituisce le coppie di delimitatori con i due tag, lasciando in pace i delimitatori spaiati. */
    private static String sostituisciCoppie(String testo, String segno, String apre, String chiude) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        boolean aperto = false;
        while (i < testo.length()) {
            int p = testo.indexOf(segno, i);
            if (p < 0) {
                out.append(testo, i, testo.length());
                break;
            }
            // Un delimitatore che non ha il suo compagno resta com'e': meglio un asterisco
            // visibile che mezza pagina in grassetto.
            if (!aperto && testo.indexOf(segno, p + segno.length()) < 0) {
                out.append(testo, i, testo.length());
                break;
            }
            out.append(testo, i, p).append(aperto ? chiude : apre);
            aperto = !aperto;
            i = p + segno.length();
        }
        return out.toString();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
