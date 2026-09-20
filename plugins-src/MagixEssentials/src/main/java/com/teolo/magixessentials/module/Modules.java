package com.teolo.magixessentials.module;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gli interruttori delle funzioni del plugin: un {@code modules.yml} sul modello del
 * {@code Modules.yml} di CMI, dove ogni funzione si accende o si spegne per intero.
 *
 * <h2>Perche' e' un file a parte</h2>
 * Accendere una funzione e regolarla sono due gesti diversi, fatti in momenti diversi e spesso da
 * persone diverse. Tenendoli insieme, l'interruttore finisce sepolto in mezzo alle sue venti chiavi
 * di regolazione e non si sa piu' cosa il plugin stia facendo davvero senza leggere tutto il config.
 * Qui invece la risposta e' un file solo, lungo quanto le funzioni che ci sono: si apre
 * {@code modules.yml} e si vede l'elenco completo, acceso o spento. Le regolazioni (righe,
 * intervalli, formati) stanno altrove, e non c'e' piu' un file da leggere tutto per sapere se una
 * funzione gira.
 *
 * <h2>Un file di impostazioni per funzione</h2>
 * Le impostazioni di una funzione stanno nel file che porta il suo nome — {@code motd.yml},
 * {@code nametag.yml} — e il {@code config.yml} resta per cio' che vale per il
 * plugin intero. Cosi' per sapere che cosa sta facendo il plugin si apre {@code modules.yml}, e per
 * cambiare come lo fa si apre il file di quella funzione: nessun file cresce all'infinito.
 *
 * <h2>Cosa vuol dire "spento"</h2>
 * Non parte affatto: niente task, niente aggancio agli eventi. Il suo file di impostazioni resta
 * dov'e', intatto — spegnere una funzione non e' buttarne via la configurazione — e torna in uso
 * appena la si riaccende.
 *
 * <h2>Chi lo tiene aggiornato</h2>
 * Il file nella cartella del plugin viene creato al primo avvio e poi allineato a ogni avvio e a
 * ogni reload da {@code util.ConfigAlign}, come gli altri: una funzione aggiunta domani compare
 * qui da sola, col suo commento e col suo valore di partenza. Finche' quella riga non c'e', vale il
 * default del jar, che questa classe carica come ripiego.
 */
public final class Modules {

    /** Il file, come si chiama nel jar e nella cartella dati del plugin. */
    public static final String FILE = "modules.yml";

    /** La MOTD: le righe che si leggono nella lista server. */
    public static final String MOTD = "motd";

    /** Il nametag: la targhetta sopra la testa dei giocatori, in gioco. */
    public static final String NAMETAG = "nametag";

    private final JavaPlugin plugin;
    /** {@code modules.yml}: l'elenco delle funzioni, accese o spente. */
    private volatile YamlConfiguration file;
    /**
     * I file di impostazioni delle funzioni, riletti tutti insieme a ogni {@link #ricarica()}.
     * La mappa si sostituisce intera invece di modificarla: la guida per lo staff la legge da un
     * altro thread, e cosi' quel che legge e' sempre una fotografia coerente.
     */
    private volatile Map<String, YamlConfiguration> configs = Map.of();

    public Modules(JavaPlugin plugin) {
        this.plugin = plugin;
        ricarica();
    }

    /**
     * Rilegge dal disco {@code modules.yml} e i file di impostazioni delle funzioni, creandoli se
     * non ci sono. Da chiamare all'avvio e a ogni reload: e' il file SUL SERVER a comandare, non
     * quello dentro al jar.
     */
    public void ricarica() {
        this.file = carica(FILE);
        // I file delle funzioni si leggono tutti, anche quelli dei moduli spenti: il file di una
        // funzione spenta resta valido e va tenuto allineato lo stesso (ConfigAlign tocca solo i
        // file che nella cartella dati ESISTONO gia', e un file che non c'e' non si aggiorna).
        Map<String, YamlConfiguration> letti = new LinkedHashMap<>();
        for (String modulo : elenco()) {
            if (plugin.getResource(modulo + ".yml") == null) continue;   // funzione senza impostazioni
            letti.put(modulo, carica(modulo + ".yml"));
        }
        this.configs = Map.copyOf(letti);
    }

    /**
     * Legge un file della cartella dati, creandolo dal jar se manca, col file del jar come ripiego
     * per le chiavi che li' non ci sono ancora (ConfigAlign gliele mette, ma cosi' non c'e' un
     * avvio in cui una chiave nuova non si sa quanto vale).
     */
    private YamlConfiguration carica(String nomeFile) {
        File f = new File(plugin.getDataFolder(), nomeFile);
        if (!f.isFile() && plugin.getResource(nomeFile) != null) plugin.saveResource(nomeFile, false);
        YamlConfiguration letto = YamlConfiguration.loadConfiguration(f);
        try (InputStream in = plugin.getResource(nomeFile)) {
            if (in != null) {
                letto.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[Moduli] non sono riuscito a leggere il " + nomeFile
                    + " del jar: valgono solo i valori del file sul server (" + e.getMessage() + ").");
        }
        return letto;
    }

    /**
     * Se la funzione e' accesa. Una funzione che non compare in nessuno dei due file (ne' sul
     * server ne' nel jar) si considera ACCESA: e' il caso di un modulo appena aggiunto al codice e
     * non ancora al {@code modules.yml}, e una funzione nuova che non parte e non dice perche' e'
     * il guasto peggiore da cercare.
     */
    public boolean attivo(String modulo) {
        return file.getBoolean(modulo, true);
    }

    /** L'elenco dei moduli conosciuti, nell'ordine del file. */
    public List<String> elenco() {
        Set<String> nomi = new LinkedHashSet<>(file.getKeys(false));
        if (file.getDefaults() != null) nomi.addAll(file.getDefaults().getKeys(false));
        return new ArrayList<>(nomi);
    }

    /** Il {@code modules.yml} caricato, per chi deve leggerlo tutto (la guida per lo staff). */
    public YamlConfiguration configurazione() {
        return file;
    }

    /**
     * Le impostazioni di una funzione: il file {@code <modulo>.yml} della cartella dati, com'era
     * all'ultimo {@link #ricarica()}. Di una funzione senza impostazioni torna un file vuoto, non
     * null: chi legge trova i suoi default.
     */
    public YamlConfiguration configurazioneDi(String modulo) {
        YamlConfiguration letto = configs.get(modulo);
        return letto != null ? letto : carica(modulo + ".yml");
    }

    /** Una riga per il log d'avvio: {@code "motd: attivo"}. */
    public String riepilogo() {
        StringBuilder sb = new StringBuilder();
        for (String modulo : elenco()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(modulo).append(": ").append(attivo(modulo) ? "attivo" : "spento");
        }
        return sb.length() == 0 ? "nessuno" : sb.toString();
    }
}
