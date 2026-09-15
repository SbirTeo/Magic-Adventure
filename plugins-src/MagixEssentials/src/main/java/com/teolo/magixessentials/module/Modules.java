package com.teolo.magixessentials.module;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * {@code modules.yml} e si vede l'elenco completo, acceso o spento. La configurazione di base
 * (righe, intervalli, formati) resta nel {@code config.yml}, che nessuno deve piu' aprire per
 * sapere se una funzione gira.
 *
 * <h2>Cosa vuol dire "spento"</h2>
 * Non parte affatto: niente task, niente aggancio agli eventi. Le sue chiavi nel {@code config.yml}
 * restano dove sono — spegnere una funzione non e' buttarne via la configurazione.
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

    /** Il tablist: la lista giocatori del tasto Tab. */
    public static final String TABLIST = "tablist";

    private final JavaPlugin plugin;
    private YamlConfiguration file;

    public Modules(JavaPlugin plugin) {
        this.plugin = plugin;
        ricarica();
    }

    /**
     * Rilegge {@code modules.yml} dal disco, creandolo se non c'e'. Da chiamare all'avvio e a ogni
     * reload: e' il file SUL SERVER a comandare, non quello dentro al jar.
     */
    public void ricarica() {
        File f = new File(plugin.getDataFolder(), FILE);
        if (!f.isFile()) plugin.saveResource(FILE, false);
        YamlConfiguration letto = YamlConfiguration.loadConfiguration(f);
        // Il file del jar fa da ripiego: un modulo aggiunto oggi vale il suo default anche nel
        // momento in cui il file del server non ha ancora la riga (ConfigAlign gliela mette, ma
        // cosi' non c'e' un avvio in cui una funzione nuova non si sa se e' accesa).
        try (InputStream in = plugin.getResource(FILE)) {
            if (in != null) {
                letto.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[Moduli] non sono riuscito a leggere il " + FILE
                    + " del jar: valgono solo i valori del file sul server (" + e.getMessage() + ").");
        }
        this.file = letto;
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

    /** Il file caricato, per chi deve leggerlo tutto (la guida per lo staff). */
    public YamlConfiguration configurazione() {
        return file;
    }

    /** Una riga per il log d'avvio: {@code "tablist: attivo"}. */
    public String riepilogo() {
        StringBuilder sb = new StringBuilder();
        for (String modulo : elenco()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(modulo).append(": ").append(attivo(modulo) ? "attivo" : "spento");
        }
        return sb.length() == 0 ? "nessuno" : sb.toString();
    }
}
