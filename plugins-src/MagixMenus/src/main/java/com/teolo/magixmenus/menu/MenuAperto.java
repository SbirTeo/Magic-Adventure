package com.teolo.magixmenus.menu;

import com.teolo.magixmenus.MagixMenus;
import com.teolo.magixmenus.azioni.Azioni;
import com.teolo.magixmenus.azioni.Contesto;
import com.teolo.magixmenus.item.CostruttoreItem;
import com.teolo.magixmenus.util.Colors;
import com.teolo.magixmenus.util.Testo;
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
public final class MenuAperto implements InventoryHolder, Contesto {

    private final MagixMenus plugin;
    private final Player giocatore;
    private final MenuDef def;
    private final List<String> argomenti;
    private final MenuAperto provenienza;

    private final Map<String, String> variabili = new LinkedHashMap<>();
    private final ItemDef[] disegnati;
    private final String[] vociDisegnate;
    private final boolean[] casellaViva;
    private final Map<String, Long> ultimoClic = new HashMap<>();

    private Inventory inventario;
    private BukkitTask aggiornamento;
    private int pagina = 1;
    private int pagineTotali = 1;
    private boolean chiusuraVoluta;

    MenuAperto(MagixMenus plugin, Player giocatore, MenuDef def, List<String> argomenti,
               MenuAperto provenienza) {
        this.plugin = plugin;
        this.giocatore = giocatore;
        this.def = def;
        this.argomenti = List.copyOf(argomenti);
        this.provenienza = provenienza;

        int dimensione = def.dimensione();
        this.disegnati = new ItemDef[dimensione];
        this.vociDisegnate = new String[dimensione];
        this.casellaViva = new boolean[dimensione];
        calcolaCaselleVive();
        aggiornaVariabili();
    }

    // ------------------------------------------------------------------ apertura

    void apri() {
        net.kyori.adventure.text.Component titolo =
                Colors.component(Testo.grezzo(giocatore, variabili, def.titolo()));
        try {
            inventario = def.tipo().righeSuMisura()
                    ? Bukkit.createInventory(this, def.dimensione(), titolo)
                    : Bukkit.createInventory(this, def.tipo().inventario(), titolo);
        } catch (Exception e) {
            // Non tutti i tipi di finestra si lasciano creare fuori dal loro blocco: meglio dirlo
            // con il nome del menu che lasciare il giocatore davanti a niente.
            plugin.getLogger().warning("Il menu \"" + def.nome() + "\" e' di tipo "
                    + def.tipo().name().toLowerCase(java.util.Locale.ROOT)
                    + ", che questo server non permette di aprire cosi': " + e.getMessage());
            plugin.messaggi().send(giocatore, "type-not-openable",
                    "type", def.tipo().name().toLowerCase(java.util.Locale.ROOT));
            return;
        }

        disegna(true);
        giocatore.openInventory(inventario);

        if (!def.azioniApertura().isEmpty()) {
            Azioni.esegui(plugin, this, def.azioniApertura());
        }
        avviaAggiornamento();
    }

    private void avviaAggiornamento() {
        int ogni = def.aggiornamentoTick();
        if (ogni <= 0 || !def.dinamico()) {
            // Un menu senza niente di dinamico non si ridisegna nemmeno se lo chiede: sarebbe
            // lavoro per riscrivere le stesse identiche caselle.
            return;
        }
        ogni = Math.max(plugin.getConfig().getInt("min-update-ticks", 1), ogni);
        aggiornamento = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!giocatore.isOnline() || inventario.getViewers().isEmpty()) {
                ferma();
                return;
            }
            disegna(false);
        }, ogni, ogni);
    }

    public void ferma() {
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
    void disegna(boolean tutte) {
        aggiornaVariabili();
        segnati.clear();

        for (ItemDef item : def.item()) {
            boolean visibile = item.mostraSe().vuoto()
                    || item.mostraSe().soddisfatti(giocatore, variabili);
            for (int casella : item.caselle()) {
                if (!tutte && !casellaViva[casella]) {
                    continue;
                }
                if (segnati.contains(casella)) {
                    continue;   // una casella la prende il primo item scritto che ha diritto a starci
                }
                if (!visibile) {
                    continue;
                }
                metti(casella, item, null, variabili);
            }
        }
        // Le caselle rimaste senza padrone vanno svuotate: un item che smette di essere visibile
        // deve sparire, non restare li' dal giro precedente.
        for (int casella = 0; casella < disegnati.length; casella++) {
            if ((tutte || casellaViva[casella]) && !segnati.contains(casella) && disegnati[casella] != null) {
                disegnati[casella] = null;
                vociDisegnate[casella] = null;
                inventario.setItem(casella, null);
            }
        }
        contenuto(tutte);
        titoloVivo();
    }

    /** Le caselle gia' assegnate in questo giro di disegno (la priorita' fra item sovrapposti). */
    private final java.util.Set<Integer> segnati = new java.util.HashSet<>();

    private void metti(int casella, ItemDef item, String voce, Map<String, String> locali) {
        segnati.add(casella);
        ItemStack stack = CostruttoreItem.costruisci(plugin, giocatore, locali, item);
        disegnati[casella] = item;
        vociDisegnate[casella] = voce;
        inventario.setItem(casella, stack);
    }

    private void contenuto(boolean tutte) {
        Contenuto c = def.contenuto();
        if (c == null) {
            return;
        }
        List<String> voci = c.voci(giocatore, variabili);
        int perPagina = c.perPagina();
        pagineTotali = Math.max(1, (int) Math.ceil(voci.size() / (double) perPagina));
        if (pagina > pagineTotali) {
            pagina = pagineTotali;
        }
        aggiornaVariabili();

        int primo = (pagina - 1) * perPagina;
        List<Integer> caselle = c.caselle();
        for (int i = 0; i < caselle.size(); i++) {
            int casella = caselle.get(i);
            int indice = primo + i;
            if (indice >= voci.size()) {
                if (segnati.contains(casella)) {
                    continue;   // niente da mostrare qui: resta lo sfondo, se c'era
                }
                disegnati[casella] = null;
                vociDisegnate[casella] = null;
                inventario.setItem(casella, null);
                continue;
            }
            Map<String, String> locali = new LinkedHashMap<>(variabili);
            locali.put("entry", voci.get(indice));
            locali.put("voce", voci.get(indice));
            locali.put("entry_index", String.valueOf(indice + 1));
            locali.put("voce_numero", String.valueOf(indice + 1));
            metti(casella, c.voce(), voci.get(indice), locali);
        }
    }

    /** Il titolo puo' contenere placeholder: se cambia, va riscritto sulla finestra aperta. */
    private void titoloVivo() {
        if (!Testo.dinamico(def.titolo()) || inventario == null) {
            return;
        }
        // Fra un giro di aggiornamento e l'altro il giocatore puo' aver aperto altro: senza
        // questo controllo si riscriverebbe il titolo della finestra di qualcun altro.
        if (giocatore.getOpenInventory().getTopInventory() != inventario) {
            return;
        }
        String nuovo = Colors.translate(Testo.grezzo(giocatore, variabili, def.titolo()));
        try {
            if (!nuovo.equals(giocatore.getOpenInventory().getTitle())) {
                giocatore.getOpenInventory().setTitle(nuovo);
            }
        } catch (Throwable ignored) {
            // Non tutte le finestre accettano di cambiare titolo mentre sono aperte: se non si
            // puo', il menu resta con il titolo di quando e' stato aperto. Non e' un errore.
        }
    }

    private void calcolaCaselleVive() {
        for (ItemDef item : def.item()) {
            if (!item.dinamico()) {
                continue;
            }
            for (int casella : item.caselle()) {
                if (casella < casellaViva.length) {
                    casellaViva[casella] = true;
                }
            }
        }
        // Se due item si contendono una casella, quella casella e' viva comunque: il secondo deve
        // poter prendere il posto del primo quando il primo smette di avere diritto a starci.
        for (int casella = 0; casella < casellaViva.length; casella++) {
            if (def.candidatiPer(casella).size() > 1) {
                casellaViva[casella] = true;
            }
        }
        if (def.contenuto() != null) {
            for (int casella : def.contenuto().caselle()) {
                if (casella < casellaViva.length) {
                    casellaViva[casella] = true;
                }
            }
        }
    }

    // ---------------------------------------------------------------- variabili

    private void aggiornaVariabili() {
        variabili.put("menu", def.nome());
        // Ogni variabile sta nella mappa DUE volte, col nome inglese e con quello vecchio
        // italiano: i menu gia' scritti con %pagina% continuano a funzionare, e non serve
        // decidere quale delle due forme "vince".
        variabili.put("page", String.valueOf(pagina));
        variabili.put("pagina", String.valueOf(pagina));
        variabili.put("pages", String.valueOf(pagineTotali));
        variabili.put("pagine", String.valueOf(pagineTotali));
        for (int i = 0; i < argomenti.size(); i++) {
            variabili.put("arg_" + (i + 1), argomenti.get(i));
            if (i < def.argomenti().size()) {
                variabili.put("arg_" + def.argomenti().get(i), argomenti.get(i));
            }
        }
        // Gli argomenti dichiarati ma non passati devono comunque sparire dal testo, altrimenti
        // in un menu si leggerebbe "%arg_categoria%" invece di niente.
        for (int i = argomenti.size(); i < def.argomenti().size(); i++) {
            variabili.putIfAbsent("arg_" + def.argomenti().get(i), "");
            variabili.putIfAbsent("arg_" + (i + 1), "");
        }
    }

    // ------------------------------------------------------------------ contesto

    @Override
    public Player giocatore() {
        return giocatore;
    }

    @Override
    public Map<String, String> variabili() {
        return variabili;
    }

    @Override
    public void chiudi() {
        chiusuraVoluta = true;
        // Fuori dal giro di eventi: chiudere un inventario mentre si sta gestendo un clic su
        // quello stesso inventario e' il modo classico per ritrovarsi con l'item sul cursore.
        Bukkit.getScheduler().runTask(plugin, () -> giocatore.closeInventory());
    }

    @Override
    public void aggiorna() {
        disegna(true);
    }

    @Override
    public void pagina(String dove) {
        int prima = pagina;
        if (dove.equalsIgnoreCase("avanti") || dove.equalsIgnoreCase("next")) {
            pagina = Math.min(pagina + 1, pagineTotali);
        } else if (dove.equalsIgnoreCase("indietro") || dove.equalsIgnoreCase("prev")) {
            pagina = Math.max(1, pagina - 1);
        } else {
            Double n = Testo.numero(dove);
            if (n != null) {
                pagina = Math.max(1, Math.min((int) (double) n, pagineTotali));
            }
        }
        if (pagina != prima) {
            disegna(true);
        }
    }

    @Override
    public void indietro() {
        if (provenienza == null) {
            chiudi();
            return;
        }
        plugin.menu().apri(giocatore, provenienza.def, provenienza.argomenti, provenienza.provenienza);
    }

    @Override
    public void apriMenu(String nomeEArgomenti) {
        String[] pezzi = nomeEArgomenti.trim().split("\\s+");
        List<String> args = pezzi.length > 1
                ? List.of(java.util.Arrays.copyOfRange(pezzi, 1, pezzi.length))
                : List.of();
        plugin.menu().apriPerNome(giocatore, pezzi[0], args, this);
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
        return giocatore;
    }

    public boolean chiusuraVoluta() {
        return chiusuraVoluta;
    }

    public void chiusuraVoluta(boolean v) {
        this.chiusuraVoluta = v;
    }

    public ItemDef itemIn(int casella) {
        return casella >= 0 && casella < disegnati.length ? disegnati[casella] : null;
    }

    public String voceIn(int casella) {
        return casella >= 0 && casella < vociDisegnate.length ? vociDisegnate[casella] : null;
    }

    /** Le variabili per un clic su questa casella: quelle del menu piu' la voce, se c'era. */
    public Map<String, String> variabiliPer(int casella) {
        String voce = voceIn(casella);
        if (voce == null) {
            return variabili;
        }
        Map<String, String> locali = new LinkedHashMap<>(variabili);
        locali.put("entry", voce);
        locali.put("voce", voce);
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
    public Contesto contestoCon(Map<String, String> locali) {
        if (locali == variabili) {
            return this;
        }
        MenuAperto menu = this;
        return new Contesto() {
            @Override
            public Player giocatore() {
                return menu.giocatore;
            }

            @Override
            public Map<String, String> variabili() {
                return locali;
            }

            @Override
            public void chiudi() {
                menu.chiudi();
            }

            @Override
            public void aggiorna() {
                menu.aggiorna();
            }

            @Override
            public void pagina(String dove) {
                menu.pagina(dove);
            }

            @Override
            public void indietro() {
                menu.indietro();
            }

            @Override
            public void apriMenu(String nomeEArgomenti) {
                menu.apriMenu(nomeEArgomenti);
            }
        };
    }

    /**
     * La pausa fra un clic e l'altro sullo stesso item.
     *
     * @return i secondi che mancano, 0 se si puo' cliccare
     */
    public long attesaRimasta(ItemDef item) {
        if (item.attesaFraClic() <= 0) {
            return 0;
        }
        Long ultimo = ultimoClic.get(item.nome());
        if (ultimo == null) {
            return 0;
        }
        long passati = (System.currentTimeMillis() - ultimo) / 1000L;
        return Math.max(0, item.attesaFraClic() - passati);
    }

    public void segnaClic(ItemDef item) {
        if (item.attesaFraClic() > 0) {
            ultimoClic.put(item.nome(), System.currentTimeMillis());
        }
    }
}
