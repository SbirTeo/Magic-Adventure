package com.teolo.magixmenus;

import com.teolo.magixmenus.util.ConfigAlign;
import com.teolo.magixmenus.command.MagixMenusCommand;
import com.teolo.magixmenus.dialog.DialogManager;
import com.teolo.magixmenus.hook.EconomyHook;
import com.teolo.magixmenus.hook.Placeholders;
import com.teolo.magixmenus.lang.Messages;
import com.teolo.magixmenus.listener.MenuListener;
import com.teolo.magixmenus.menu.MenuManager;
import com.teolo.magixmenus.util.StaffGuide;
import com.teolo.magixmenus.util.Text;
import com.teolo.magixmenus.util.ConfigValues;
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

    private Messages messages;
    private MenuManager menu;
    private DialogManager dialogs;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // I file di configurazione SUL SERVER allineati a quelli del jar: le chiavi nuove
        // compaiono da sole, al loro posto e col loro commento, senza toccare i valori
        // gia' scelti. Il deploy porta solo il jar, quindi senza questo il file del server
        // resterebbe indietro in silenzio (vedi util/ConfigAlign).
        ConfigAlign.alignAll(this);
        reloadConfig();
        getDataFolder().mkdirs();

        messages = new Messages(this);
        Text.rilevaPlaceholderApi();
        EconomyHook.collega();

        menu = new MenuManager(this);
        dialogs = new DialogManager(this);
        menu.load();

        PluginCommand command = getCommand("magixmenus");
        if (command != null) {
            MagixMenusCommand esecutore = new MagixMenusCommand(this);
            command.setExecutor(esecutore);
            command.setTabCompleter(esecutore);
        }
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        registerPlaceholder();

        // Puro lavoro su file: non deve rallentare l'avvio. Il README nella cartella del plugin
        // non si copia dal jar, lo genera StaffGuide insieme al capitolo per il sito, cosi' i due
        // non possono divergere (vedi plugins-src/GUIDA-STAFF.md).
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);

        getLogger().info("Avviato: " + menu.quanti() + " menu"
                + (Text.placeholderApiPresente() ? ", PlaceholderAPI collegato" : ", senza PlaceholderAPI")
                + ", economia: " + EconomyHook.name() + ".");
    }

    @Override
    public void onDisable() {
        if (menu != null) {
            // I menu aperti vanno chiusi a mano: senza il plugin, i loro clic non verrebbero piu'
            // annullati da nessuno e diventerebbero inventari da cui prendere item.
            menu.closeAll();
        }
    }

    /** Ricarica config, messaggi e tutti i menu (/menus reload). */
    public void reloadAll() {
        // Come all'avvio: prima si allineano i file del server a quelli del jar, poi si
        // rilegge. Cosi' un reload dopo un deploy vede anche le chiavi nuove.
        ConfigAlign.alignAll(this);
        reloadConfig();
        messages.reload();
        Text.rilevaPlaceholderApi();
        EconomyHook.collega();
        menu.load();
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);
    }

    private void registerPlaceholder() {
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

    public Messages messages() {
        return messages;
    }

    public MenuManager menu() {
        return menu;
    }

    public DialogManager dialogs() {
        return dialogs;
    }

    // ------------------------------------------------- GUIDA PER LO STAFF

    /**
     * Il capitolo di MagixMenus nella guida del gestionale. Comandi, permessi e chiavi di config
     * non si ricopiano: li legge da solo. Vedi plugins-src/GUIDA-STAFF.md.
     */
    private void writeStaffGuide() {
        StaffGuide.create(this, "MagixMenus — i menu del server", 75)
                .values(new ConfigValues(this))
                .intro("Ogni menu del server e' un file in plugins/MagixMenus/menus/. Dentro c'e' che "
                        + "finestra si apre, quali item ci stanno, chi li vede e cosa succede quando si "
                        + "clicca. Per aggiungere un menu si aggiunge un file; per toglierlo si cancella.")

                .section("Un file, un menu",
                        "Il nome del menu e' il nome del file senza .yml. Non c'e' nessun elenco da tenere "
                                + "aggiornato: quello che c'e' nella cartella e' quello che esiste.",
                        "Se nel file si scrivono dei comandi, quei comandi vengono creati all'avvio e a ogni "
                                + "reload. Per questo non li trovi nel plugin.yml: nascono dal file del menu.")

                .section("Le chiavi si scrivono in inglese",
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

                .section("Gli errori non spengono i menu",
                        "Un file scritto male non impedisce l'avvio e non chiude il menu: quello che non si "
                                + "capisce finisce nel log all'avvio, e al posto dell'item che non si e' potuto "
                                + "costruire compare una barriera rossa con scritto sopra cosa non va.",
                        "/menus lista dice al volo quali menu hanno problemi; /menus info <menu> li elenca uno "
                                + "per uno. E' il primo posto da guardare quando qualcuno segnala che un menu "
                                + "\"e' strano\".")

                .section("Mostrare o negare",
                        "Un item ha due gruppi di condizioni e non vanno confusi. show_requirements decide se "
                                + "l'item si VEDE: se non e' soddisfatto, la casella resta vuota. "
                                + "click_requirements decide se il "
                                + "clic ha EFFETTO: l'item si vede lo stesso e chi clicca riceve la spiegazione "
                                + "scritta in deny_actions.",
                        "Quasi sempre si vuole il secondo. Un bottone che sparisce non insegna niente; un "
                                + "bottone che dice \"ti servono ancora 500 monete\" si', ed evita anche la "
                                + "segnalazione \"il menu e' rotto\".")

                .section("Quanto costa un menu",
                        "Il campo update dice ogni quanti tick il menu si ridisegna. Non ridisegna "
                                + "tutto: solo gli item che contengono placeholder o requisiti, e solo per chi "
                                + "sta guardando. Un menu di 54 caselle in cui tre cambiano fa tre item di "
                                + "lavoro per giro.",
                        "Un menu senza niente di dinamico non fa nessun lavoro, qualunque numero ci sia "
                                + "scritto: viene disegnato all'apertura e poi lasciato stare.")

                .section("Il negozio, senza scrivere condizioni",
                        "Un articolo di negozio sono tre chiavi sull'item: price (quanto costa), give "
                                + "(cosa riceve chi compra) e sell (a quanto glielo ricompra il server col "
                                + "clic destro). Nient'altro: controllare i soldi, controllare che ci sia "
                                + "posto in inventario, togliere le monete, dare l'oggetto e spiegare cosa "
                                + "manca lo fa il plugin.",
                        "Il prezzo compare da solo in fondo alla descrizione: NON va scritto anche a mano, "
                                + "se no un domani lo cambi in un posto e non nell'altro. Le righe si "
                                + "compongono in messages.yml sotto \"negozio\" e si spengono dal config.",
                        "I prezzi si scrivono **interi**: l'economia del server non tiene i centesimi. Un "
                                + "price o un sell con la virgola verrebbe arrotondato per difetto (10.5 pagato "
                                + "10) e la descrizione direbbe un numero diverso da quello che si paga, quindi "
                                + "dalla v0.1.4 il caricamento del menu lo SEGNALA nel log dicendo a quanto "
                                + "verrebbe arrotondato. Il menu si apre lo stesso: e' un avviso, non un errore. "
                                + "I valori con un %placeholder% dentro non vengono controllati, perche' il loro "
                                + "numero si conosce solo in gioco.",
                        "L'ordine dei controlli e' voluto: prima il posto in inventario, poi i soldi. Al "
                                + "contrario, chi ha l'inventario pieno pagherebbe senza ricevere niente.",
                        "Se all'articolo servono anche delle azioni (dare un permesso, annunciare in chat), "
                                + "si scrivono normalmente: partono DOPO che il pagamento e' riuscito, e non "
                                + "partono affatto se il giocatore non poteva permetterselo.",
                        "Serve Vault e un plugin che fornisca l'economia. Senza, gli articoli lo dicono "
                                + "invece di regalare la merce.")

                .section("I comandi da console",
                        "L'azione console: esegue il comando come se lo scrivesse il server, ed e' quella "
                                + "giusta per tutto cio' che il giocatore non potrebbe fare da solo.",
                        "Esiste anche op_command:, che da' l'op al giocatore per la durata di un comando. E' "
                                + "spenta nel config e va lasciata spenta: quasi sempre console: fa la stessa "
                                + "cosa senza dare niente a nessuno.")

                .section("Il testo dei menu si traduce da solo (con MagixLanguage installato)",
                        "Titolo del menu, nome e descrizione di ogni item, corpo/bottoni/campi delle finestre "
                                + "di dialogo, e il testo scritto dentro message:/broadcast:/title:/actionbar: "
                                + "(comprese le versioni negate di show_requirements/click_requirements/"
                                + "open_requirements) vengono tradotti in automatico per chi gioca in un'altra "
                                + "lingua — senza scrivere niente in piu' nel file del menu.",
                        "A differenza di messages.yml qui non c'e' una chiave: la ricerca avviene sulla "
                                + "FRASE italiana esatta (placeholder %tipo_questo% compresi). Per correggere "
                                + "una traduzione, o per tradurre a mano una frase che l'automatismo non trova da "
                                + "solo (es. dentro un blocco if/then/else di un'azione), si aggiunge la frase "
                                + "italiana esatta in plugins/MagixLanguage/translations/MagixMenus/"
                                + "menu-phrases-<lingua>-overrides.yml.",
                        "Materiali, permessi, equazioni, nomi di suono, di menu e di comando NON vengono mai "
                                + "toccati: solo il testo che un giocatore legge davvero passa dalla traduzione.")

                .detailedCommands()
                .commands()
                .permissions()
                .settings(
                        "actions.allow-op-commands", "Consente l'azione op_command. Da tenere spenta.",
                        "min-update-ticks", "Il minimo intervallo fra due ridisegni di un menu: "
                                + "un menu che chiedesse di aggiornarsi piu' spesso viene riportato qui.",
                        "price-in-lore", "Il prezzo di un articolo si scrive da solo nella sua "
                                + "descrizione. Spegnendolo, le righe le scrivi a mano.")

                .issue("Ho cambiato una chiave del config nel repo e sul server non succede niente",
                        "Il deploy porta il jar, non i config: il file nella cartella del plugin sul server non viene toccato, ed e' quello che il plugin legge. Il valore nel jar vale solo per le chiavi che li' MANCANO. Quindi un valore gia' presente si cambia sul server (a mano, o col workflow deploy-plugin-config.yml), non nel repo. Del resto si occupa il plugin, a ogni avvio e a ogni reload: aggiunge le chiavi nuove al loro posto col loro commento, applica le rinomine portandosi dietro il valore che avevi scelto, e toglie le righe morte che il codice non legge piu' dai file a schema fisso, cioe' tutti tranne i cataloghi (i menu e le sanzioni no: li' le voci in piu' sono tue). Prima di ogni modifica fa una copia del file accanto all'originale, col nome che finisce in .bak-<data>, e nel log scrive che cosa ha cambiato.")
                .issue("Un menu non si apre",
                        "Controlla il permesso del menu e le sue condizioni di apertura con /menus info <menu>. "
                                + "Se il file ha errori, sono elencati li'.")
                .issue("Il comando del menu non esiste",
                        "I comandi nascono dal file: dopo averlo modificato serve /menus reload. Se due menu "
                                + "chiedono lo stesso comando, lo prende il primo caricato.")
                .issue("Ho tolto un comando da un menu ma risponde ancora",
                        "I comandi, una volta registrati, non si possono togliere mentre il server gira: e' un "
                                + "limite del server, non del plugin. Il comando resta li' fino al riavvio, ma "
                                + "dice che non apre piu' niente. Stessa cosa per le SCORCIATOIE di un comando "
                                + "che esiste gia': cambiarle richiede un riavvio. Aggiungere comandi nuovi, "
                                + "invece, funziona subito con /menus reload.")
                .issue("Al posto di un item c'e' una barriera rossa",
                        "E' voluto: la barriera dice nella sua descrizione cosa non ha funzionato. Correggi il "
                                + "file e ricarica.")
                .issue("Nel menu si legge %qualcosa% invece di un valore",
                        "Quel placeholder non esiste o PlaceholderAPI non ha l'espansione che lo fornisce. "
                                + "Verifica con /papi parse me %quel_placeholder%.")
                .issue("Un giocatore straniero vede ancora il menu in italiano",
                        "MagixLanguage deve essere installato e quel testo gia' tradotto: la prima volta puo' "
                                + "volerci fino al prossimo /language sync o riavvio. Con /language status si vede "
                                + "quante frasi di questo plugin sono ancora mancanti.")
                .issue("Una traduzione di un menu non convince",
                        "Si corregge SENZA toccare i file del menu: si aggiunge la STESSA frase italiana (esatta, "
                                + "placeholder compresi) in plugins/MagixLanguage/translations/MagixMenus/"
                                + "menu-phrases-<lingua>-overrides.yml. Vince sempre lei, anche se il testo "
                                + "italiano del menu cambia di nuovo in seguito.")
                .issue("Un articolo del negozio non fa niente quando lo clicco",
                        "Guarda in chat: il plugin dice sempre perche' (soldi insufficienti, inventario "
                                + "pieno, niente economia sul server). Se non dice niente, l'item non ha "
                                + "ne' price ne' azioni.")
                .issue("Ho comprato ma l'oggetto ha scritto sopra il prezzo",
                        "Succede con \"give: self\", che consegna una copia dell'item del menu con la "
                                + "sua descrizione. Per un oggetto vero conviene scrivere per esteso cosa si "
                                + "da' (give: DIAMOND_SWORD 1).")
                .issue("Un giocatore e' riuscito a prendere un item dal menu",
                        "Non dovrebbe poter succedere: ogni clic viene annullato. Se capita, segnalalo con il "
                                + "nome del menu: e' un difetto del plugin, non della configurazione.")

                .never("Non modificare i file dei menu mentre il server e' spento pensando che si ricarichino "
                        + "da soli: si leggono all'avvio e a /menus reload.")
                .never("Non usare op_command per comodita': se serve un permesso in piu', si da' il permesso.")
                .write();
    }
}
