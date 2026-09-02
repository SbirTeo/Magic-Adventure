package com.teolo.magixmenus.menu;

import com.teolo.magixmenus.MagixMenus;
import com.teolo.magixmenus.actions.Actions;
import com.teolo.magixmenus.actions.Context;
import com.teolo.magixmenus.item.ItemBuilder;
import com.teolo.magixmenus.util.Colors;
import com.teolo.magixmenus.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Un menu aperto davanti a un giocatore: la parte che cambia da persona a persona.
 *
 * Il {@link MenuDef} e' uno solo per tutti; qui dentro c'e' quello che vale per questa persona in
 * questo momento — la pagina, gli argomenti del comando, quale item e' finito in quale casella, da
 * dove e' arrivata.
 *
 * <h2>Perche' e' anche l'InventoryHolder</h2>
 * L'ascoltatore dei clic deve sapere se l'inventario cliccato e' nostro, e quale menu e'. Guardare
 * il titolo sarebbe fragile (due menu possono avere lo stesso titolo, e i placeholder lo cambiano
 * di continuo); tenere una mappa a parte vuol dire ricordarsi di svuotarla. L'inventario stesso
 * porta con se' il suo menu, e quando sparisce sparisce anche il collegamento.
 *
 * <h2>Il disegno parziale</h2>
 * A ogni aggiornamento si ridisegnano solo le caselle vive — quelle che ospitano un item con dei
 * placeholder o dei requisiti, e quelle del contenuto. Un menu di cinquantaquattro caselle in cui
 * solo tre cambiano fa tre item di lavoro per giro, non cinquantaquattro: e' la differenza fra
 * potersi permettere {@code aggiornamento: 1} e non potersi permettere niente.
 */
public final class OpenMenu implements InventoryHolder, Context {

    private final MagixMenus plugin;
    private final Player player;
    private final MenuDef def;
    private final List<String> arguments;
    private final OpenMenu provenienza;

    private final Map<String, String> variabili = new LinkedHashMap<>();
    private final ItemDef[] disegnati;
    private final String[] vociDisegnate;
    private final boolean[] liveSlot;
    private final Map<String, Long> lastClick = new HashMap<>();

    private Inventory inventario;
    private BukkitTask aggiornamento;
    private int page = 1;
    private int totalPages = 1;
    private boolean chiusuraVoluta;

    OpenMenu(MagixMenus plugin, Player player, MenuDef def, List<String> arguments,
               OpenMenu provenienza) {
        this.plugin = plugin;
        this.player = player;
        this.def = def;
        this.arguments = List.copyOf(arguments);
        this.provenienza = provenienza;

        int dimensione = def.dimensione();
        this.disegnati = new ItemDef[dimensione];
        this.vociDisegnate = new String[dimensione];
        this.liveSlot = new boolean[dimensione];
        computeLiveSlots();
        refreshVariables();
    }

    // ------------------------------------------------------------------ apertura

    void open() {
        net.kyori.adventure.text.Component title =
                Colors.component(Text.raw(player, variabili, def.title()));
        try {
            inventario = def.type().customRows()
                    ? Bukkit.createInventory(this, def.dimensione(), title)
                    : Bukkit.createInventory(this, def.type().inventario(), title);
        } catch (Exception e) {
            // Non tutti i tipi di finestra si lasciano creare fuori dal loro blocco: meglio dirlo
            // con il nome del menu che lasciare il giocatore davanti a niente.
            plugin.getLogger().warning("Il menu \"" + def.name() + "\" e' di tipo "
                    + def.type().name().toLowerCase(java.util.Locale.ROOT)
                    + ", che questo server non permette di aprire cosi': " + e.getMessage());
            plugin.messages().send(player, "type-not-openable",
                    "type", def.type().name().toLowerCase(java.util.Locale.ROOT));
            return;
        }

        draw(true);
        player.openInventory(inventario);

        if (!def.openActions().isEmpty()) {
            Actions.esegui(plugin, this, def.openActions());
        }
        startRefresh();
    }

    private void startRefresh() {
        int ogni = def.aggiornamentoTick();
        if (ogni <= 0 || !def.dinamico()) {
            // Un menu senza niente di dinamico non si ridisegna nemmeno se lo chiede: sarebbe
            // lavoro per riscrivere le stesse identiche caselle.
            return;
        }
        ogni = Math.max(plugin.getConfig().getInt("min-update-ticks", 1), ogni);
        aggiornamento = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || inventario.getViewers().isEmpty()) {
                stop();
                return;
            }
            draw(false);
        }, ogni, ogni);
    }

    public void stop() {
        if (aggiornamento != null) {
            aggiornamento.cancel();
            aggiornamento = null;
        }
    }

    // ------------------------------------------------------------------ disegno

    /**
     * Riempie le caselle.
     *
     * @param tutte al primo disegno si fa tutto; dopo, solo quello che puo' essere cambiato.
     */
    void draw(boolean tutte) {
        refreshVariables();
        segnati.clear();

        for (ItemDef item : def.item()) {
            boolean visible = item.showIf().vuoto()
                    || item.showIf().soddisfatti(player, variabili);
            for (int slot : item.slots()) {
                if (!tutte && !liveSlot[slot]) {
                    continue;
                }
                if (segnati.contains(slot)) {
                    continue;   // una casella la prende il primo item scritto che ha diritto a starci
                }
                if (!visible) {
                    continue;
                }
                metti(slot, item, null, variabili);
            }
        }
        // Le caselle rimaste senza padrone vanno svuotate: un item che smette di essere visibile
        // deve sparire, non restare li' dal giro precedente.
        for (int slot = 0; slot < disegnati.length; slot++) {
            if ((tutte || liveSlot[slot]) && !segnati.contains(slot) && disegnati[slot] != null) {
                disegnati[slot] = null;
                vociDisegnate[slot] = null;
                inventario.setItem(slot, null);
            }
        }
        content(tutte);
        liveTitle();
    }

    /** Le caselle gia' assegnate in questo giro di disegno (la priorita' fra item sovrapposti). */
    private final java.util.Set<Integer> segnati = new java.util.HashSet<>();

    private void metti(int slot, ItemDef item, String entry, Map<String, String> locali) {
        segnati.add(slot);
        ItemStack stack = ItemBuilder.costruisci(plugin, player, locali, item);
        disegnati[slot] = item;
        vociDisegnate[slot] = entry;
        inventario.setItem(slot, stack);
    }

    private void content(boolean tutte) {
        Content c = def.content();
        if (c == null) {
            return;
        }
        List<String> entries = c.entries(player, variabili);
        int perPage = c.perPage();
        totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) perPage));
        if (page > totalPages) {
            page = totalPages;
        }
        refreshVariables();

        int primo = (page - 1) * perPage;
        List<Integer> slots = c.slots();
        for (int i = 0; i < slots.size(); i++) {
            int slot = slots.get(i);
            int indice = primo + i;
            if (indice >= entries.size()) {
                if (segnati.contains(slot)) {
                    continue;   // niente da mostrare qui: resta lo sfondo, se c'era
                }
                disegnati[slot] = null;
                vociDisegnate[slot] = null;
                inventario.setItem(slot, null);
                continue;
            }
            Map<String, String> locali = new LinkedHashMap<>(variabili);
            locali.put("entry", entries.get(indice));
            locali.put("voce", entries.get(indice));
            locali.put("entry_index", String.valueOf(indice + 1));
            locali.put("voce_numero", String.valueOf(indice + 1));
            metti(slot, c.entry(), entries.get(indice), locali);
        }
    }

    /** Il titolo puo' contenere placeholder: se cambia, va riscritto sulla finestra aperta. */
    private void liveTitle() {
        if (!Text.dinamico(def.title()) || inventario == null) {
            return;
        }
        // Fra un giro di aggiornamento e l'altro il giocatore puo' aver aperto altro: senza
        // questo controllo si riscriverebbe il titolo della finestra di qualcun altro.
        if (player.getOpenInventory().getTopInventory() != inventario) {
            return;
        }
        String nuovo = Colors.translate(Text.raw(player, variabili, def.title()));
        try {
            if (!nuovo.equals(player.getOpenInventory().getTitle())) {
                player.getOpenInventory().setTitle(nuovo);
            }
        } catch (Throwable ignored) {
            // Non tutte le finestre accettano di cambiare titolo mentre sono aperte: se non si
            // puo', il menu resta con il titolo di quando e' stato aperto. Non e' un errore.
        }
    }

    private void computeLiveSlots() {
        for (ItemDef item : def.item()) {
            if (!item.dinamico()) {
                continue;
            }
            for (int slot : item.slots()) {
                if (slot < liveSlot.length) {
                    liveSlot[slot] = true;
                }
            }
        }
        // Se due item si contendono una casella, quella casella e' viva comunque: il secondo deve
        // poter prendere il posto del primo quando il primo smette di avere diritto a starci.
        for (int slot = 0; slot < liveSlot.length; slot++) {
            if (def.candidatiPer(slot).size() > 1) {
                liveSlot[slot] = true;
            }
        }
        if (def.content() != null) {
            for (int slot : def.content().slots()) {
                if (slot < liveSlot.length) {
                    liveSlot[slot] = true;
                }
            }
        }
    }

    // ---------------------------------------------------------------- variabili

    private void refreshVariables() {
        variabili.put("menu", def.name());
        // Ogni variabile sta nella mappa DUE volte, col nome inglese e con quello vecchio
        // italiano: i menu gia' scritti con %pagina% continuano a funzionare, e non serve
        // decidere quale delle due forme "vince".
        variabili.put("page", String.valueOf(page));
        variabili.put("pagina", String.valueOf(page));
        variabili.put("pages", String.valueOf(totalPages));
        variabili.put("pagine", String.valueOf(totalPages));
        for (int i = 0; i < arguments.size(); i++) {
            variabili.put("arg_" + (i + 1), arguments.get(i));
            if (i < def.arguments().size()) {
                variabili.put("arg_" + def.arguments().get(i), arguments.get(i));
            }
        }
        // Gli argomenti dichiarati ma non passati devono comunque sparire dal testo, altrimenti
        // in un menu si leggerebbe "%arg_categoria%" invece di niente.
        for (int i = arguments.size(); i < def.arguments().size(); i++) {
            variabili.putIfAbsent("arg_" + def.arguments().get(i), "");
            variabili.putIfAbsent("arg_" + (i + 1), "");
        }
    }

    // ------------------------------------------------------------------ contesto

    @Override
    public Player player() {
        return player;
    }

    @Override
    public Map<String, String> variabili() {
        return variabili;
    }

    @Override
    public void close() {
        chiusuraVoluta = true;
        // Fuori dal giro di eventi: chiudere un inventario mentre si sta gestendo un clic su
        // quello stesso inventario e' il modo classico per ritrovarsi con l'item sul cursore.
        Bukkit.getScheduler().runTask(plugin, () -> player.closeInventory());
    }

    @Override
    public void refresh() {
        draw(true);
    }

    @Override
    public void page(String where) {
        int prima = page;
        if (where.equalsIgnoreCase("avanti") || where.equalsIgnoreCase("next")) {
            page = Math.min(page + 1, totalPages);
        } else if (where.equalsIgnoreCase("indietro") || where.equalsIgnoreCase("prev")) {
            page = Math.max(1, page - 1);
        } else {
            Double n = Text.number(where);
            if (n != null) {
                page = Math.max(1, Math.min((int) (double) n, totalPages));
            }
        }
        if (page != prima) {
            draw(true);
        }
    }

    @Override
    public void back() {
        if (provenienza == null) {
            close();
            return;
        }
        plugin.menu().open(player, provenienza.def, provenienza.arguments, provenienza.provenienza);
    }

    @Override
    public void openMenu(String nameAndArgs) {
        String[] pieces = nameAndArgs.trim().split("\\s+");
        List<String> args = pieces.length > 1
                ? List.of(java.util.Arrays.copyOfRange(pieces, 1, pieces.length))
                : List.of();
        plugin.menu().openByName(player, pieces[0], args, this);
    }

    // ------------------------------------------------------------------ lettura

    @Override
    public Inventory getInventory() {
        return inventario;
    }

    public MenuDef definizione() {
        return def;
    }

    public Player proprietario() {
        return player;
    }

    public boolean chiusuraVoluta() {
        return chiusuraVoluta;
    }

    public void chiusuraVoluta(boolean v) {
        this.chiusuraVoluta = v;
    }

    public ItemDef itemIn(int slot) {
        return slot >= 0 && slot < disegnati.length ? disegnati[slot] : null;
    }

    public String voceIn(int slot) {
        return slot >= 0 && slot < vociDisegnate.length ? vociDisegnate[slot] : null;
    }

    /** Le variabili per un clic su questa casella: quelle del menu piu' la voce, se c'era. */
    public Map<String, String> variabiliPer(int slot) {
        String entry = voceIn(slot);
        if (entry == null) {
            return variabili;
        }
        Map<String, String> locali = new LinkedHashMap<>(variabili);
        locali.put("entry", entry);
        locali.put("voce", entry);
        return locali;
    }

    /**
     * Lo stesso menu, ma con delle variabili in piu' valide solo per questo clic: la voce
     * dell'elenco su cui si e' cliccato, il testo scritto in un'incudine.
     *
     * Serve un involucro invece di cambiare le variabili del menu perche' un clic puo' avviare
     * azioni con delle attese dentro: se nel frattempo si scrivesse sulle variabili condivise, la
     * ripresa dell'azione userebbe la voce di un clic successivo.
     */
    public Context contextWith(Map<String, String> locali) {
        if (locali == variabili) {
            return this;
        }
        OpenMenu menu = this;
        return new Context() {
            @Override
            public Player player() {
                return menu.player;
            }

            @Override
            public Map<String, String> variabili() {
                return locali;
            }

            @Override
            public void close() {
                menu.close();
            }

            @Override
            public void refresh() {
                menu.refresh();
            }

            @Override
            public void page(String where) {
                menu.page(where);
            }

            @Override
            public void back() {
                menu.back();
            }

            @Override
            public void openMenu(String nameAndArgs) {
                menu.openMenu(nameAndArgs);
            }
        };
    }

    /**
     * La pausa fra un clic e l'altro sullo stesso item.
     *
     * @return i secondi che mancano, 0 se si puo' cliccare
     */
    public long attesaRimasta(ItemDef item) {
        if (item.clickDelay() <= 0) {
            return 0;
        }
        Long ultimo = lastClick.get(item.name());
        if (ultimo == null) {
            return 0;
        }
        long passati = (System.currentTimeMillis() - ultimo) / 1000L;
        return Math.max(0, item.clickDelay() - passati);
    }

    public void markClick(ItemDef item) {
        if (item.clickDelay() > 0) {
            lastClick.put(item.name(), System.currentTimeMillis());
        }
    }
}
