package com.teolo.magixmenus;

import com.teolo.magixmenus.command.ComandoMagixMenus;
import com.teolo.magixmenus.dialogo.GestoreDialoghi;
import com.teolo.magixmenus.hook.Economia;
import com.teolo.magixmenus.hook.Placeholders;
import com.teolo.magixmenus.lang.Messages;
import com.teolo.magixmenus.listener.ListenerMenu;
import com.teolo.magixmenus.menu.GestoreMenu;
import com.teolo.magixmenus.util.GuidaStaff;
import com.teolo.magixmenus.util.Testo;
import com.teolo.magixmenus.util.ValoriConfig;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MagixMenus - menu interattivi scritti in YAML.
 *
 * Un file per menu in {@code plugins/MagixMenus/menus/}: dentro c'e' che finestra si apre, cosa
 * c'e' scritto sopra, quali item ci stanno, chi li vede e cosa succede se li si clicca. Aggiungere
 * un menu e' aggiungere un file; toglierlo e' cancellarlo.
 *
 * <h2>Le tre idee che tengono in piedi tutto</h2>
 * <ol>
 *   <li><b>Un errore non spegne un menu.</b> Quello che non si capisce diventa una riga di
 *       spiegazione — nel log, e dentro il menu stesso al posto dell'item che non si e' potuto
 *       costruire. Il menu si apre lo stesso.</li>
 *   <li><b>Si ridisegna solo cio' che cambia.</b> Un item senza placeholder e senza requisiti
 *       viene costruito una volta sola: e' cio' che rende sostenibile un aggiornamento a ogni
 *       tick.</li>
 *   <li><b>Le azioni non sanno cosa sia un menu.</b> Le stesse azioni valgono per un item di un
 *       baule e per il bottone di una finestra di dialogo, e domani per qualunque altra cosa che
 *       si possa premere.</li>
 * </ol>
 */
public final class MagixMenus extends JavaPlugin {

    private Messages messaggi;
    private GestoreMenu menu;
    private GestoreDialoghi dialoghi;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();

        messaggi = new Messages(this);
        Testo.rilevaPlaceholderApi();
        Economia.collega();

        menu = new GestoreMenu(this);
        dialoghi = new GestoreDialoghi(this);
        menu.carica();

        PluginCommand comando = getCommand("magixmenus");
        if (comando != null) {
            ComandoMagixMenus esecutore = new ComandoMagixMenus(this);
            comando.setExecutor(esecutore);
            comando.setTabCompleter(esecutore);
        }
        getServer().getPluginManager().registerEvents(new ListenerMenu(this), this);
        registraPlaceholder();

        // Puro lavoro su file: non deve rallentare l'avvio. Il README nella cartella del plugin
        // non si copia dal jar, lo genera GuidaStaff insieme al capitolo per il sito, cosi' i due
        // non possono divergere (vedi plugins-src/GUIDA-STAFF.md).
        Bukkit.getScheduler().runTaskAsynchronously(this, this::scriviGuidaStaff);

        getLogger().info("Avviato: " + menu.quanti() + " menu"
                + (Testo.placeholderApiPresente() ? ", PlaceholderAPI collegato" : ", senza PlaceholderAPI")
                + ", economia: " + Economia.nome() + ".");
    }

    @Override
    public void onDisable() {
        if (menu != null) {
            // I menu aperti vanno chiusi a mano: senza il plugin, i loro clic non verrebbero piu'
            // annullati da nessuno e diventerebbero inventari da cui prendere item.
            menu.chiudiTutti();
        }
    }

    /** Ricarica config, messaggi e tutti i menu (/menus reload). */
    public void ricaricaTutto() {
        reloadConfig();
        messaggi.reload();
        Testo.rilevaPlaceholderApi();
        Economia.collega();
        menu.carica();
        Bukkit.getScheduler().runTaskAsynchronously(this, this::scriviGuidaStaff);
    }

    private void registraPlaceholder() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().warning("PlaceholderAPI non e' installato: i menu funzionano, ma tutto quello "
                    + "che sta fra %...% restera' scritto per esteso.");
            return;
        }
        try {
            new Placeholders(this).register();
            getLogger().info("Placeholder %magixmenus_...% registrati su PlaceholderAPI.");
        } catch (Throwable t) {
            getLogger().warning("Registrazione dei placeholder fallita: " + t.getMessage());
        }
    }

    public Messages messaggi() {
        return messaggi;
    }

    public GestoreMenu menu() {
        return menu;
    }

    public GestoreDialoghi dialoghi() {
        return dialoghi;
    }

    // ------------------------------------------------- GUIDA PER LO STAFF

    /**
     * Il capitolo di MagixMenus nella guida del gestionale. Comandi, permessi e chiavi di config
     * non si ricopiano: li legge da solo. Vedi plugins-src/GUIDA-STAFF.md.
     */
    private void scriviGuidaStaff() {
        GuidaStaff.crea(this, "MagixMenus — i menu del server", 70)
                .valori(new ValoriConfig(this))
                .intro("Ogni menu del server e' un file in plugins/MagixMenus/menus/. Dentro c'e' che "
                        + "finestra si apre, quali item ci stanno, chi li vede e cosa succede quando si "
                        + "clicca. Per aggiungere un menu si aggiunge un file; per toglierlo si cancella.")

                .sezione("Un file, un menu",
                        "Il nome del menu e' il nome del file senza .yml. Non c'e' nessun elenco da tenere "
                                + "aggiornato: quello che c'e' nella cartella e' quello che esiste.",
                        "Se nel file si scrivono dei comandi, quei comandi vengono creati all'avvio e a ogni "
                                + "reload. Per questo non li trovi nel plugin.yml: nascono dal file del menu.")

                .sezione("Le chiavi si scrivono in inglese",
                        "Vale per tutti i plugin Magix: le chiavi dei config e dei formati di file sono "
                                + "in inglese (slot, display_name, lore, show_requirements, actions, price). "
                                + "I testi che si leggono restano in italiano — cambiano solo i nomi delle "
                                + "chiavi.",
                        "I vecchi nomi italiani (nome, descrizione, mostra_se, azioni, prezzo...) restano "
                                + "accettati per sempre come sinonimi: i menu gia' scritti continuano a "
                                + "funzionare senza toccarli. Ma i file nuovi, e tutto quello che scrive "
                                + "l'editor del sito, usano l'inglese.",
                        "Il vantaggio pratico: un menu copiato da una guida di un altro plugin si apre "
                                + "senza doverlo tradurre riga per riga.")

                .sezione("Gli errori non spengono i menu",
                        "Un file scritto male non impedisce l'avvio e non chiude il menu: quello che non si "
                                + "capisce finisce nel log all'avvio, e al posto dell'item che non si e' potuto "
                                + "costruire compare una barriera rossa con scritto sopra cosa non va.",
                        "/menus lista dice al volo quali menu hanno problemi; /menus info <menu> li elenca uno "
                                + "per uno. E' il primo posto da guardare quando qualcuno segnala che un menu "
                                + "\"e' strano\".")

                .sezione("Mostrare o negare",
                        "Un item ha due gruppi di condizioni e non vanno confusi. show_requirements decide se "
                                + "l'item si VEDE: se non e' soddisfatto, la casella resta vuota. "
                                + "click_requirements decide se il "
                                + "clic ha EFFETTO: l'item si vede lo stesso e chi clicca riceve la spiegazione "
                                + "scritta in deny_actions.",
                        "Quasi sempre si vuole il secondo. Un bottone che sparisce non insegna niente; un "
                                + "bottone che dice \"ti servono ancora 500 monete\" si', ed evita anche la "
                                + "segnalazione \"il menu e' rotto\".")

                .sezione("Quanto costa un menu",
                        "Il campo update dice ogni quanti tick il menu si ridisegna. Non ridisegna "
                                + "tutto: solo gli item che contengono placeholder o requisiti, e solo per chi "
                                + "sta guardando. Un menu di 54 caselle in cui tre cambiano fa tre item di "
                                + "lavoro per giro.",
                        "Un menu senza niente di dinamico non fa nessun lavoro, qualunque numero ci sia "
                                + "scritto: viene disegnato all'apertura e poi lasciato stare.")

                .sezione("Il negozio, senza scrivere condizioni",
                        "Un articolo di negozio sono tre chiavi sull'item: price (quanto costa), give "
                                + "(cosa riceve chi compra) e sell (a quanto glielo ricompra il server col "
                                + "clic destro). Nient'altro: controllare i soldi, controllare che ci sia "
                                + "posto in inventario, togliere le monete, dare l'oggetto e spiegare cosa "
                                + "manca lo fa il plugin.",
                        "Il prezzo compare da solo in fondo alla descrizione: NON va scritto anche a mano, "
                                + "se no un domani lo cambi in un posto e non nell'altro. Le righe si "
                                + "compongono in messages.yml sotto \"negozio\" e si spengono dal config.",
                        "L'ordine dei controlli e' voluto: prima il posto in inventario, poi i soldi. Al "
                                + "contrario, chi ha l'inventario pieno pagherebbe senza ricevere niente.",
                        "Se all'articolo servono anche delle azioni (dare un permesso, annunciare in chat), "
                                + "si scrivono normalmente: partono DOPO che il pagamento e' riuscito, e non "
                                + "partono affatto se il giocatore non poteva permetterselo.",
                        "Serve Vault e un plugin che fornisca l'economia. Senza, gli articoli lo dicono "
                                + "invece di regalare la merce.")

                .sezione("I comandi da console",
                        "L'azione console: esegue il comando come se lo scrivesse il server, ed e' quella "
                                + "giusta per tutto cio' che il giocatore non potrebbe fare da solo.",
                        "Esiste anche op_command:, che da' l'op al giocatore per la durata di un comando. E' "
                                + "spenta nel config e va lasciata spenta: quasi sempre console: fa la stessa "
                                + "cosa senza dare niente a nessuno.")

                .comandiDettagliati()
                .comandi()
                .permessi()
                .impostazioni(
                        "actions.allow-op-commands", "Consente l'azione op_command. Da tenere spenta.",
                        "min-update-ticks", "Il minimo intervallo fra due ridisegni di un menu: "
                                + "un menu che chiedesse di aggiornarsi piu' spesso viene riportato qui.",
                        "price-in-lore", "Il prezzo di un articolo si scrive da solo nella sua "
                                + "descrizione. Spegnendolo, le righe le scrivi a mano.")

                .guasto("Un menu non si apre",
                        "Controlla il permesso del menu e le sue condizioni di apertura con /menus info <menu>. "
                                + "Se il file ha errori, sono elencati li'.")
                .guasto("Il comando del menu non esiste",
                        "I comandi nascono dal file: dopo averlo modificato serve /menus reload. Se due menu "
                                + "chiedono lo stesso comando, lo prende il primo caricato.")
                .guasto("Ho tolto un comando da un menu ma risponde ancora",
                        "I comandi, una volta registrati, non si possono togliere mentre il server gira: e' un "
                                + "limite del server, non del plugin. Il comando resta li' fino al riavvio, ma "
                                + "dice che non apre piu' niente. Stessa cosa per le SCORCIATOIE di un comando "
                                + "che esiste gia': cambiarle richiede un riavvio. Aggiungere comandi nuovi, "
                                + "invece, funziona subito con /menus reload.")
                .guasto("Al posto di un item c'e' una barriera rossa",
                        "E' voluto: la barriera dice nella sua descrizione cosa non ha funzionato. Correggi il "
                                + "file e ricarica.")
                .guasto("Nel menu si legge %qualcosa% invece di un valore",
                        "Quel placeholder non esiste o PlaceholderAPI non ha l'espansione che lo fornisce. "
                                + "Verifica con /papi parse me %quel_placeholder%.")
                .guasto("Un articolo del negozio non fa niente quando lo clicco",
                        "Guarda in chat: il plugin dice sempre perche' (soldi insufficienti, inventario "
                                + "pieno, niente economia sul server). Se non dice niente, l'item non ha "
                                + "ne' price ne' azioni.")
                .guasto("Ho comprato ma l'oggetto ha scritto sopra il prezzo",
                        "Succede con \"give: self\", che consegna una copia dell'item del menu con la "
                                + "sua descrizione. Per un oggetto vero conviene scrivere per esteso cosa si "
                                + "da' (give: DIAMOND_SWORD 1).")
                .guasto("Un giocatore e' riuscito a prendere un item dal menu",
                        "Non dovrebbe poter succedere: ogni clic viene annullato. Se capita, segnalalo con il "
                                + "nome del menu: e' un difetto del plugin, non della configurazione.")

                .mai("Non modificare i file dei menu mentre il server e' spento pensando che si ricarichino "
                        + "da soli: si leggono all'avvio e a /menus reload.")
                .mai("Non usare op_command per comodita': se serve un permesso in piu', si da' il permesso.")
                .scrivi();
    }
}
