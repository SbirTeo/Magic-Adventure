package com.teolo.magixmenus.menu;

import com.teolo.magixmenus.MagixMenus;
import com.teolo.magixmenus.azioni.Azioni;
import com.teolo.magixmenus.command.ComandoMenu;
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
public final class GestoreMenu {

    private final MagixMenus plugin;
    private final Map<String, MenuDef> menu = new LinkedHashMap<>();
    private final Map<UUID, MenuAperto> aperti = new HashMap<>();
    /** I comandi registrati, per nome. Non si svuota mai: vedi registraComandi(). */
    private final Map<String, ComandoMenu> comandi = new LinkedHashMap<>();

    public GestoreMenu(MagixMenus plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ caricamento

    public void carica() {
        chiudiTutti();
        menu.clear();

        File cartella = new File(plugin.getDataFolder(), "menus");
        if (!cartella.exists()) {
            cartella.mkdirs();
            copiaEsempio(cartella);
        }

        File[] file = cartella.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (file == null || file.length == 0) {
            plugin.getLogger().warning("Nessun menu in " + cartella.getPath() + ".");
            return;
        }

        int conErrori = 0;
        for (File f : file) {
            MenuDef def = CaricatoreMenu.daFile(f);
            if (menu.containsKey(def.nome())) {
                plugin.getLogger().warning("Due menu si chiamano \"" + def.nome() + "\": tengo il primo.");
                continue;
            }
            menu.put(def.nome(), def);
            if (!def.errori().isEmpty()) {
                conErrori++;
                plugin.getLogger().warning("Il menu \"" + def.nome() + "\" ha " + def.errori().size()
                        + " problemi:");
                for (String e : def.errori()) {
                    plugin.getLogger().warning("  - " + e);
                }
            }
        }
        registraComandi();

        // Quello che il plugin ha capito, per l'editor del sito. Fuori dal filo principale:
        // e' scrittura su disco, e il catalogo degli item del gioco non e' piccolo.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> EsportaJson.scrivi(plugin, this));

        plugin.getLogger().info("Menu caricati: " + menu.size()
                + (conErrori > 0 ? " (" + conErrori + " con errori, vedi sopra)" : "")
                + ", comandi registrati: " + comandi.size() + ".");
    }

    /**
     * Alla prima installazione si mettono in cartella i due menu di prova.
     *
     * Si copiano solo se la cartella non c'era: sono file da modificare, e riscriverli a ogni
     * avvio cancellerebbe le prove di chi li sta usando per imparare.
     */
    private void copiaEsempio(File cartella) {
        for (String nome : List.of("test.yml", "test-dialogo.yml")) {
            try (InputStream in = plugin.getResource("menus/" + nome)) {
                if (in != null) {
                    Files.copy(in, new File(cartella, nome).toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Non sono riuscito a scrivere " + nome + ": " + e.getMessage());
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
    private void registraComandi() {
        CommandMap mappa = Bukkit.getCommandMap();
        Set<String> serviti = new HashSet<>();

        for (MenuDef def : menu.values()) {
            if (def.comandi().isEmpty()) {
                continue;
            }
            String principale = def.comandi().get(0);
            serviti.add(principale);

            ComandoMenu gia = comandi.get(principale);
            if (gia != null) {
                gia.punta(def.nome(), def.permesso());
                continue;
            }
            List<String> alias = def.comandi().size() > 1
                    ? def.comandi().subList(1, def.comandi().size())
                    : List.of();
            ComandoMenu c = new ComandoMenu(plugin, principale, alias, def.nome(),
                    def.permesso(), "Apre il menu " + def.nome());
            if (mappa.register("magixmenus", c)) {
                comandi.put(principale, c);
            } else {
                // Il nome era gia' di qualcun altro: il comando esiste ma con il prefisso
                // (/magixmenus:nome). Meglio dirlo che lasciar credere che funzioni.
                plugin.getLogger().warning("Il comando /" + principale + " del menu \"" + def.nome()
                        + "\" era gia' di un altro plugin: si apre con /magixmenus:" + principale + ".");
                comandi.put(principale, c);
            }
        }

        for (Map.Entry<String, ComandoMenu> e : comandi.entrySet()) {
            if (!serviti.contains(e.getKey()) && e.getValue().menu() != null) {
                e.getValue().punta(null, null);
                plugin.getLogger().info("Il comando /" + e.getKey() + " non apre piu' nessun menu "
                        + "(sparira' del tutto al prossimo riavvio).");
            }
        }
        aggiornaClient();
    }

    /** I client tengono una copia dell'elenco comandi: dopo un reload va rimandata. */
    private void aggiornaClient() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.updateCommands();
        }
    }

    // ------------------------------------------------------------------ apertura

    public void apriPerNome(Player p, String nome, List<String> argomenti, MenuAperto provenienza) {
        MenuDef def = trova(nome);
        if (def == null) {
            plugin.messaggi().send(p, "menu-not-found", "menu", nome);
            return;
        }
        apri(p, def, argomenti, provenienza);
    }

    public void apri(Player p, MenuDef def, List<String> argomenti, MenuAperto provenienza) {
        if (def.permesso() != null && !def.permesso().isBlank() && !p.hasPermission(def.permesso())) {
            plugin.messaggi().send(p, "no-permission");
            return;
        }
        if (def.tipo().dialogo()) {
            plugin.dialoghi().apri(p, def, argomenti, provenienza);
            return;
        }

        MenuAperto nuovo = new MenuAperto(plugin, p, def, argomenti, provenienza);
        if (!def.apriSe().vuoto() && !def.apriSe().soddisfatti(p, nuovo.variabili())) {
            if (!def.apriSe().azioniNegate().isEmpty()) {
                Azioni.esegui(plugin, nuovo, def.apriSe().azioniNegate());
            } else {
                plugin.messaggi().send(p, "requirements-not-met");
            }
            return;
        }

        MenuAperto vecchio = aperti.get(p.getUniqueId());
        if (vecchio != null) {
            // Si sta passando da un menu all'altro: la chiusura del primo non deve far scattare
            // le sue azioni di chiusura, altrimenti aprire un sottomenu manderebbe il messaggio
            // "hai chiuso il menu" ogni volta.
            vecchio.chiusuraVoluta(true);
            vecchio.ferma();
        }
        aperti.put(p.getUniqueId(), nuovo);
        nuovo.apri();
    }

    public MenuDef trova(String nome) {
        if (nome == null) {
            return null;
        }
        return menu.get(nome.toLowerCase(Locale.ROOT).trim());
    }

    public Collection<MenuDef> tutti() {
        return menu.values();
    }

    public MenuAperto apertoDi(Player p) {
        return aperti.get(p.getUniqueId());
    }

    public void dimentica(Player p) {
        MenuAperto m = aperti.remove(p.getUniqueId());
        if (m != null) {
            m.ferma();
        }
    }

    public void chiudiTutti() {
        for (MenuAperto m : new ArrayList<>(aperti.values())) {
            m.ferma();
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
