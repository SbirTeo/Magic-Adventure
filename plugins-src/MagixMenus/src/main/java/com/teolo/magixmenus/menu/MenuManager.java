package com.teolo.magixmenus.menu;

import com.teolo.magixmenus.MagixMenus;
import com.teolo.magixmenus.actions.Actions;
import com.teolo.magixmenus.command.MenuCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * L'anagrafe dei menu: quali esistono, chi ne ha uno aperto, quali comandi li aprono.
 *
 * <h2>Il reload</h2>
 * Ricaricare vuol dire tre cose in fila: chiudere i menu aperti, rileggere la cartella, rimettere
 * in riga i comandi. I menu aperti si chiudono apposta — tenerli aperti su una definizione che
 * non esiste piu' significherebbe che il clic successivo esegue un'azione cancellata, e chi ha
 * appena corretto un file si aspetta che il vecchio menu sparisca. Sui comandi c'e' un vincolo del
 * server di cui vale la pena sapere: vedi {@link #registraComandi()}.
 *
 * <h2>Un file, un menu</h2>
 * Il nome del menu e' il nome del file senza estensione. Non c'e' un indice da tenere aggiornato:
 * si aggiunge un file e c'e', si cancella e non c'e' piu'. E' anche cio' che permette all'editor
 * sul sito di scrivere un menu nuovo senza toccare nient'altro.
 */
public final class MenuManager {

    private final MagixMenus plugin;
    private final Map<String, MenuDef> menu = new LinkedHashMap<>();
    private final Map<UUID, OpenMenu> aperti = new HashMap<>();
    /** I comandi registrati, per nome. Non si svuota mai: vedi registraComandi(). */
    private final Map<String, MenuCommand> commands = new LinkedHashMap<>();

    public MenuManager(MagixMenus plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ caricamento

    public void load() {
        closeAll();
        menu.clear();

        File folder = new File(plugin.getDataFolder(), "menus");
        if (!folder.exists()) {
            folder.mkdirs();
            copyExample(folder);
        }

        File[] file = folder.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (file == null || file.length == 0) {
            plugin.getLogger().warning("Nessun menu in " + folder.getPath() + ".");
            return;
        }

        int conErrori = 0;
        for (File f : file) {
            MenuDef def = MenuLoader.daFile(f);
            if (menu.containsKey(def.name())) {
                plugin.getLogger().warning("Due menu si chiamano \"" + def.name() + "\": tengo il primo.");
                continue;
            }
            menu.put(def.name(), def);
            if (!def.errori().isEmpty()) {
                conErrori++;
                plugin.getLogger().warning("Il menu \"" + def.name() + "\" ha " + def.errori().size()
                        + " problemi:");
                for (String e : def.errori()) {
                    plugin.getLogger().warning("  - " + e);
                }
            }
        }
        registerCommands();

        // Quello che il plugin ha capito, per l'editor del sito. Fuori dal filo principale:
        // e' scrittura su disco, e il catalogo degli item del gioco non e' piccolo.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> JsonExport.write(plugin, this));

        plugin.getLogger().info("Menu caricati: " + menu.size()
                + (conErrori > 0 ? " (" + conErrori + " con errori, vedi sopra)" : "")
                + ", comandi registrati: " + commands.size() + ".");
    }

    /**
     * Alla prima installazione si mettono in cartella i due menu di prova.
     *
     * Si copiano solo se la cartella non c'era: sono file da modificare, e riscriverli a ogni
     * avvio cancellerebbe le prove di chi li sta usando per imparare.
     */
    private void copyExample(File folder) {
        for (String name : List.of("test.yml", "test-dialogo.yml")) {
            try (InputStream in = plugin.getResource("menus/" + name)) {
                if (in != null) {
                    Files.copy(in, new File(folder, name).toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Non sono riuscito a scrivere " + name + ": " + e.getMessage());
            }
        }
        plugin.getLogger().info("Creati i menu di prova: /test e /testdialogo.");
    }

    // ------------------------------------------------------------------ comandi

    /**
     * Mette in piedi i comandi dei menu, e al reload li rimette in riga.
     *
     * <h3>Perche' non si tolgono e si rifanno</h3>
     * Sarebbe l'ordine naturale delle cose, ed e' il primo modo in cui era scritto: togli i
     * vecchi, registra i nuovi. Solo che la mappa dei comandi del server, su Paper, e' di sola
     * lettura mentre il server gira: il tentativo di togliere una voce fa saltare per aria
     * l'intero {@code /menus reload}, e il server risponde "unexpected error" senza ricaricare
     * niente. Succedeva davvero.
     *
     * Quindi un comando registrato una volta resta registrato per sempre, e a ogni reload gli si
     * dice soltanto a quale menu punta adesso. Le conseguenze, che vanno sapute:
     * <ul>
     *   <li>aggiungere un comando a un menu funziona subito;</li>
     *   <li>toglierlo lo lascia esistere fino al riavvio, ma risponde che quel menu non c'e' piu';</li>
     *   <li>cambiare le SCORCIATOIE di un comando gia' esistente richiede un riavvio, perche' gli
     *       alias si dichiarano al momento della registrazione.</li>
     * </ul>
     */
    private void registerCommands() {
        CommandMap mappa = Bukkit.getCommandMap();
        Set<String> serviti = new HashSet<>();

        for (MenuDef def : menu.values()) {
            if (def.commands().isEmpty()) {
                continue;
            }
            String principale = def.commands().get(0);
            serviti.add(principale);

            MenuCommand gia = commands.get(principale);
            if (gia != null) {
                gia.punta(def.name(), def.permesso());
                continue;
            }
            List<String> alias = def.commands().size() > 1
                    ? def.commands().subList(1, def.commands().size())
                    : List.of();
            MenuCommand c = new MenuCommand(plugin, principale, alias, def.name(),
                    def.permesso(), "Apre il menu " + def.name());
            if (mappa.register("magixmenus", c)) {
                commands.put(principale, c);
            } else {
                // Il nome era gia' di qualcun altro: il comando esiste ma con il prefisso
                // (/magixmenus:nome). Meglio dirlo che lasciar credere che funzioni.
                plugin.getLogger().warning("Il comando /" + principale + " del menu \"" + def.name()
                        + "\" era gia' di un altro plugin: si apre con /magixmenus:" + principale + ".");
                commands.put(principale, c);
            }
        }

        for (Map.Entry<String, MenuCommand> e : commands.entrySet()) {
            if (!serviti.contains(e.getKey()) && e.getValue().menu() != null) {
                e.getValue().punta(null, null);
                plugin.getLogger().info("Il comando /" + e.getKey() + " non apre piu' nessun menu "
                        + "(sparira' del tutto al prossimo riavvio).");
            }
        }
        refreshClient();
    }

    /** I client tengono una copia dell'elenco comandi: dopo un reload va rimandata. */
    private void refreshClient() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.updateCommands();
        }
    }

    // ------------------------------------------------------------------ apertura

    public void openByName(Player p, String name, List<String> arguments, OpenMenu provenienza) {
        MenuDef def = find(name);
        if (def == null) {
            plugin.messages().send(p, "menu-not-found", "menu", name);
            return;
        }
        open(p, def, arguments, provenienza);
    }

    public void open(Player p, MenuDef def, List<String> arguments, OpenMenu provenienza) {
        if (def.permesso() != null && !def.permesso().isBlank() && !p.hasPermission(def.permesso())) {
            plugin.messages().send(p, "no-permission");
            return;
        }
        if (def.type().dialog()) {
            plugin.dialogs().open(p, def, arguments, provenienza);
            return;
        }

        OpenMenu nuovo = new OpenMenu(plugin, p, def, arguments, provenienza);
        if (!def.openIf().vuoto() && !def.openIf().soddisfatti(p, nuovo.variabili())) {
            if (!def.openIf().deniedActions().isEmpty()) {
                Actions.esegui(plugin, nuovo, def.openIf().deniedActions());
            } else {
                plugin.messages().send(p, "requirements-not-met");
            }
            return;
        }

        OpenMenu vecchio = aperti.get(p.getUniqueId());
        if (vecchio != null) {
            // Si sta passando da un menu all'altro: la chiusura del primo non deve far scattare
            // le sue azioni di chiusura, altrimenti aprire un sottomenu manderebbe il messaggio
            // "hai chiuso il menu" ogni volta.
            vecchio.chiusuraVoluta(true);
            vecchio.stop();
        }
        aperti.put(p.getUniqueId(), nuovo);
        nuovo.open();
    }

    public MenuDef find(String name) {
        if (name == null) {
            return null;
        }
        return menu.get(name.toLowerCase(Locale.ROOT).trim());
    }

    public Collection<MenuDef> tutti() {
        return menu.values();
    }

    public OpenMenu openedBy(Player p) {
        return aperti.get(p.getUniqueId());
    }

    public void forget(Player p) {
        OpenMenu m = aperti.remove(p.getUniqueId());
        if (m != null) {
            m.stop();
        }
    }

    public void closeAll() {
        for (OpenMenu m : new ArrayList<>(aperti.values())) {
            m.stop();
            m.chiusuraVoluta(true);
            if (m.proprietario().isOnline()) {
                m.proprietario().closeInventory();
            }
        }
        aperti.clear();
    }

    /** Quanti menu, quanti aperti: serve a /menus e alla guida. */
    public int quanti() {
        return menu.size();
    }

    public int quantiAperti() {
        return aperti.size();
    }
}
