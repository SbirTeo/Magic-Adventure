package com.teolo.magixauth.util;

import org.bukkit.configuration.Configuration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sostituisce i segnaposto {@code {{...}}} di un testo (tutorial HTML, capitolo della guida per lo
 * staff, README...) con i valori VERI del {@code config.yml} del plugin, letti al momento della
 * scrittura.
 *
 * <h2>Perche' esiste</h2>
 * I numeri che un giocatore o un membro dello staff legge nelle guide erano scritti a mano: bastava
 * cambiare una chiave del config perche' la documentazione cominciasse a mentire, e nessuno se ne
 * accorgeva finche' qualcuno non protestava in chat. Legandoli alla stessa configurazione che il plugin
 * applica davvero, non possono piu' divergere.
 *
 * <h2>Come si usa nel testo</h2>
 * <ul>
 *   <li>{@code {{cfg:una.chiave}}} — il valore cosi' com'e' ("10")</li>
 *   <li>{@code {{secondi:power.gain-interval-seconds}}} — durata leggibile ("10 minuti")</li>
 *   <li>{@code {{ore:decay.grace-hours}}} — durata leggibile ("48 ore", "7 giorni")</li>
 *   <li>{@code {{percento:claims.max-percent}}} — percentuale ("20%")</li>
 *   <li>{@code {{simbolo:map.chat.symbols.you}}} — il valore senza i codici colore ("&f&l+" -> "+")</li>
 * </ul>
 * Ogni forma accetta un ripiego dopo la barra verticale, usato se la chiave manca:
 * {@code {{cfg:una.chiave|10}}}.
 * <p>
 * E per i PEZZI DI TESTO che valgono solo in certe configurazioni (una modalita' accesa, una funzione
 * spenta) ci sono i blocchi condizionali, vedi {@link #blocchi(String)}:
 * <ul>
 *   <li>{@code {{se:map.mode=chat}}...{{/se}}} — resta solo se la chiave vale cosi'</li>
 *   <li>{@code {{se:map.mode!=chat}}...{{/se}}} — resta in tutti gli altri casi</li>
 * </ul>
 * <p>
 * Il separatore del percorso e' quello del file da cui si legge: di norma il punto, ma la BARRA per i
 * config caricati con un separatore diverso — il {@code sanzioni.yml} di MagixGuard, per esempio, vuole
 * {@code {{cfg:punti/dimezzamento-giorni}}}.
 *
 * <h2>Testi derivati</h2>
 * Quello che non e' un singolo valore — una frase che cambia forma, o un paragrafo che deve sparire se
 * la funzione e' spenta — si aggiunge con {@link #extra(String, String)} dal plugin, che conosce le
 * proprie regole. Il resto e' automatico e non va mantenuto chiave per chiave.
 *
 * <p><b>Questa classe e' COMUNE ai plugin Magix</b> (come {@code util.Help} e {@code util.StaffGuide}):
 * se la correggi qui, riportala anche negli altri plugin.
 */
public final class ConfigValues {

    /** {{forma:chiave.del.config|ripiego}} */
    private static final Pattern SEGNAPOSTO =
            Pattern.compile("\\{\\{(cfg|secondi|ore|percento|simbolo):([A-Za-z0-9_./\\-]+)(?:\\|([^}]*))?}}");
    /** {{se:chiave=valore}} ...pezzo di testo... {{/se}} — vedi #blocchi(String). */
    private static final Pattern BLOCCO = Pattern.compile(
            "\\{\\{se:([A-Za-z0-9_./\\-]+)(!?=)([^}]*)}}((?:(?!\\{\\{se:).)*?)\\{\\{/se}}", Pattern.DOTALL);
    /** Codici colore di Minecraft (&a, &#RRGGBB): in una guida scritta non hanno senso. */
    private static final Pattern CODICI_COLORE = Pattern.compile("(?i)&#[0-9a-f]{6}|&[0-9a-fk-or]");
    /** Un segnaposto qualunque, per accorgersi di quelli rimasti senza valore. */
    private static final Pattern RIMASTO = Pattern.compile("\\{\\{[^}]{1,80}}}");

    private final JavaPlugin plugin;
    private final Map<String, String> extra = new LinkedHashMap<>();
    /** Config in cui cercare le chiavi, in ordine: il config.yml del plugin e poi gli altri aggiunti. */
    private final List<Configuration> fonti = new ArrayList<>();

    public ConfigValues(JavaPlugin plugin) {
        this.plugin = plugin;
        this.fonti.add(plugin.getConfig());
    }

    /**
     * Aggiunge un altro file di configurazione in cui cercare le chiavi (es. il {@code sanzioni.yml} di
     * MagixGuard, che non e' il config.yml del plugin). Si cerca nell'ordine in cui sono stati aggiunti,
     * partendo dal config.yml.
     */
    public ConfigValues inoltre(Configuration conf) {
        if (conf != null) fonti.add(conf);
        return this;
    }

    /** Aggiunge un testo derivato: {@code extra("PERDITA_OFFLINE", "<li>...</li>")} -> {@code {{PERDITA_OFFLINE}}}. */
    public ConfigValues extra(String nome, String testo) {
        extra.put(nome, testo == null ? "" : testo);
        return this;
    }

    /** Applica tutte le sostituzioni al testo e segnala nel log i segnaposto rimasti senza valore. */
    public String applica(String testo) {
        if (testo == null || testo.isEmpty()) return testo;
        testo = blocchi(testo);   // prima i pezzi da tenere o buttare, poi i valori dentro a quel che resta

        StringBuilder sb = new StringBuilder();
        Matcher m = SEGNAPOSTO.matcher(testo);
        while (m.find()) {
            String forma = m.group(1), chiave = m.group(2), ripiego = m.group(3);
            Configuration c = null;
            for (Configuration f : fonti) { if (f != null && f.isSet(chiave)) { c = f; break; } }
            String valore;
            if (c == null) {
                valore = ripiego != null ? ripiego : "";
                if (ripiego == null) {
                    plugin.getLogger().warning("[Guide] chiave di config assente: " + chiave
                            + " (segnaposto " + m.group() + " lasciato vuoto).");
                }
            } else {
                valore = switch (forma) {
                    case "secondi" -> DurationText.daSecondi(c.getLong(chiave));
                    case "ore" -> DurationText.daOre(c.getDouble(chiave));
                    case "percento" -> DurationText.numero(c.getDouble(chiave)) + "%";
                    case "simbolo" -> CODICI_COLORE.matcher(String.valueOf(c.get(chiave))).replaceAll("");
                    default -> String.valueOf(c.get(chiave));
                };
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(valore));
        }
        m.appendTail(sb);
        String out = sb.toString();

        for (Map.Entry<String, String> e : extra.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        }

        // Rete di sicurezza: meglio accorgersene dal log all'avvio che da un giocatore che legge "{{...}}".
        Matcher rimasto = RIMASTO.matcher(out);
        if (rimasto.find()) {
            plugin.getLogger().warning("[Guide] segnaposto senza valore: " + rimasto.group()
                    + " — aggiungilo al config o passalo con ConfigValues.extra().");
        }
        return out;
    }

    /**
     * Risolve i blocchi condizionali {@code {{se:chiave=valore}} ... {{/se}}}: il pezzo di testo resta
     * solo se quella chiave del config vale davvero cosi', altrimenti sparisce (con {@code !=} il
     * contrario). Serve per le parti di guida che descrivono una MODALITA': cambiata la chiave nel
     * config, la guida smette da sola di raccontare la modalita' che non e' in uso — prima quelle
     * frasi erano scritte a mano e restavano indietro (caso reale: {@code map.mode} messo su "chat" e
     * il tutorial che continuava a spiegare la mappa-ITEM).
     * <p>
     * Il confronto ignora maiuscole e spazi ai lati. I blocchi <b>si annidano</b> (uno dentro l'altro):
     * si risolvono dal piu' interno verso il piu' esterno, un giro per livello. Serve: nel tutorial la
     * frase sulla forma della minimap sta dentro il blocco della mappa in chat, e con la prima versione
     * — che agganciava il primo {@code {{/se}}} incontrato — quel pezzo di guida usciva a pezzi.
     * Per il testo che non e' un semplice "c'e'/non c'e'" resta {@link #extra(String, String)}.
     */
    private String blocchi(String testo) {
        // Un giro per livello di annidamento: il pattern aggancia solo i blocchi PIU' INTERNI (quelli
        // che non ne contengono altri), quindi risolto un livello quello sopra diventa a sua volta il
        // piu' interno. Serve davvero: la frase sulla minimap sta DENTRO il blocco della mappa in chat.
        for (int giro = 0; giro < 20 && testo.contains("{{se:"); giro++) {
            Matcher m = BLOCCO.matcher(testo);
            StringBuilder sb = new StringBuilder();
            boolean trovato = false;
            while (m.find()) {
                trovato = true;
                String chiave = m.group(1), operatore = m.group(2), atteso = m.group(3).trim(), corpo = m.group(4);
                String valore = null;
                for (Configuration f : fonti) { if (f != null && f.isSet(chiave)) { valore = String.valueOf(f.get(chiave)); break; } }
                if (valore == null) {
                    plugin.getLogger().warning("[Guide] chiave di config assente: " + chiave
                            + " (blocco {{se:" + chiave + operatore + atteso + "}} trattato come falso).");
                }
                boolean uguale = valore != null && valore.trim().equalsIgnoreCase(atteso);
                boolean tieni = "=".equals(operatore) == uguale;   // "!=" ribalta
                m.appendReplacement(sb, Matcher.quoteReplacement(tieni ? corpo : ""));
            }
            m.appendTail(sb);
            if (!trovato) {
                break;      // restano solo {{se:}} senza chiusura: li segnala la rete di sicurezza
            }
            testo = sb.toString();
        }
        return testo;
    }
}
