package com.teolo.magixessentials.tab;

import com.teolo.magixessentials.util.CmiModules;
import com.teolo.magixessentials.util.TextFormat;
import net.kyori.adventure.text.Component;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gestore del tablist: intestazione, fondo e nome dei giocatori, presi dal config e aggiornati a
 * intervalli regolari (e a ogni ingresso). Header/fondo possono contenere placeholder PER-GIOCATORE
 * (ping, fazione, coordinate...), quindi vengono ricalcolati per ciascuno.
 *
 * <p>Acceso e spento NON si decidono qui: il modulo {@code tablist} sta nel {@code modules.yml}
 * e lo legge la classe principale, che costruisce questa classe solo se e' attivo. Le impostazioni
 * — intervallo, righe, nomi, caselle fisse — stanno nel file della funzione, {@code tablist.yml},
 * che arriva gia' letto nel costruttore.
 *
 * <p>Le 80 slot fisse e la pergamena di sfondo NON sono qui: richiedono l'invio di caselle "finte"
 * al client (via ProtocolLib) e arriveranno in un secondo momento. Vedi config {@code fixed-slots}.
 */
public final class TabManager implements Listener {

    /** Sostituito col carattere del LOGO del server (glifo bitmap nel resource pack di MagixFactions). */
    private static final String LOGO_TOKEN = "{logo}";
    private static final String LOGO_CHAR = "";

    /**
     * Il numero da mettere come ultimo parametro di {@code <gradient:...:qui>}, cosi' la sfumatura
     * scorre nel tempo invece di restare ferma. Verificato decompilando GradientTag della vera
     * libreria Adventure/MiniMessage in uso (4.26.1): la fase e' un numero DECIMALE fra -1.0 e 1.0,
     * dove -1 e +1 producono la STESSA sfumatura ma con i colori scambiati — usare un'onda a
     * TRIANGOLO (0 -&gt; 1 -&gt; 0, non un dente di sega 0..1 che poi salta a -1) evita quel salto:
     * la posizione non fa mai un balzo, cambia solo il verso.
     */
    private static final String GRADIENT_PHASE_TOKEN = "{gradient-phase}";
    /**
     * Il numero da mettere in {@code <rainbow:qui>}. Verificato decompilando RainbowTag: qui la
     * fase e' un INTERO, diviso da Adventure per 10 e poi usato con un modulo — un intero che
     * cresce e ricomincia da 0 ogni 10 produce una rotazione di tonalita' continua, senza il
     * problema del gradiente (l'arcobaleno e' gia' ciclico, un giro completo non fa "salti").
     */
    private static final String RAINBOW_PHASE_TOKEN = "{rainbow-phase}";

    private final JavaPlugin plugin;
    /** Le impostazioni del tablist: il {@code tablist.yml} della cartella dati. */
    private final ConfigurationSection cfg;
    private final boolean papi;
    private BukkitTask task;
    /** Riscrivere una seconda volta poco dopo, per arrivare DOPO chi scrive nello stesso tick. */
    private boolean riasserisci = true;
    private long ritardoRiassersione = 2L;
    private final FixedSlots slotFisse;

    /** Se ordinare i giocatori veri per peso del grado (sort-by-rank-weight in tablist.yml). */
    private boolean ordinaPerGrado;
    private LuckPerms luckPerms;
    /**
     * L'ultimo ordine scritto per ciascun giocatore: si manda un pacchetto nuovo solo quando
     * cambia davvero (una promozione, un cambio di gruppo), non a ogni giro.
     */
    private final Map<UUID, Integer> ultimoOrdine = new HashMap<>();

    /** Quanto dura un giro completo di un'animazione (gradient-phase/rainbow-phase), in millisecondi. */
    private long periodoAnimazioneMs = 4000L;

    /**
     * Ogni quanto si rimandano le 80 slot finte, in millisecondi — SEPARATO da
     * update-interval-ticks. Le slot finte non cambiano mai contenuto da sole (stesso profilo,
     * stesso nome, stessa latenza finche' il config non cambia): rimandarle e' solo un rimedio a
     * un altro plugin che scrive nello stesso tick, e un pacchetto da 80 voci ProtocolLib per
     * giocatore online e' l'operazione piu' pesante di tutto questo modulo. Se lo si rimandasse
     * alla stessa velocita' dell'animazione (update-interval-ticks abbassato per far scorrere un
     * gradiente) lo si spedirebbe 10-20 volte al secondo per ogni giocatore invece che una volta
     * al secondo: e' quello — non l'animazione in se' — a far scattare e bloccare il tablist con
     * un update-interval-ticks basso. Un secondo basta e avanza per correggere CMI.
     */
    private static final long FIXED_SLOTS_RESEND_MS = 1000L;
    private long prossimoInvioSlotFisseMs = 0L;

    public TabManager(JavaPlugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.papi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        this.slotFisse = new FixedSlots(plugin, cfg);
    }

    public void start() {
        // Caselle finte che tengono il tab sempre della stessa misura. Si prepara qui una volta
        // sola; se non e' disponibile (ProtocolLib assente, o spenta) resta il tablist dinamico.
        slotFisse.load();

        riasserisci = cfg.getBoolean("priority.enabled", true);
        ritardoRiassersione = Math.max(1, cfg.getLong("priority.reassert-delay-ticks", 2));
        avvisaSeCmiScriveAncheLui();

        ordinaPerGrado = cfg.getBoolean("sort-by-rank-weight", true);
        agganciaLuckPerms();

        double secondi = cfg.getDouble("animation-period-seconds", 4.0);
        periodoAnimazioneMs = Math.max(100L, Math.round(secondi * 1000.0));

        Bukkit.getPluginManager().registerEvents(this, plugin);
        long interval = Math.max(1, cfg.getLong("update-interval-ticks", 20));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 20L, interval);
        updateAll();
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        // Le caselle finte vivono nel client: se non le togliamo qui, dopo un reload restano
        // appese insieme a quelle nuove e il tab si riempie di doppioni.
        for (Player p : Bukkit.getOnlinePlayers()) slotFisse.rimuoviDa(p);
        ultimoOrdine.clear();
        HandlerList.unregisterAll(this);
    }

    /**
     * Ultimo a scrivere, non primo: {@code MONITOR} fa arrivare questo handler DOPO quelli degli
     * altri plugin sullo stesso evento. Da solo non basta (CMI riscrive anche a intervalli suoi),
     * ma toglie di mezzo il caso piu' visibile: il tablist sbagliato nei primi istanti dopo il join.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent e) {
        // Un tick dopo il join: cosi' i placeholder di mondo/posizione sono gia' pronti.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = e.getPlayer();
            if (!p.isOnline()) return;
            // Le slot finte non aspettano il prossimo giro di updateAll() (al massimo un secondo):
            // chi si e' appena connesso le vuole subito, non con un ritardo casuale.
            slotFisse.inviaA(p);
            update(p);
        }, 1L);
    }

    /** Dimentica l'ordine di chi esce: non e' un errore se rientra e lo si riscrive uguale. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        ultimoOrdine.remove(e.getPlayer().getUniqueId());
    }

    /**
     * Se anche CMI gestisce il tablist, i due si sovrascrivono a vicenda e vince chi scrive per
     * ultimo. Lo diciamo all'avvio invece di lasciare lo staff a indovinare perche' il tablist
     * "torna come prima": il file di CMI e' li' sul disco e si puo' leggere.
     */
    private void avvisaSeCmiScriveAncheLui() {
        if (!CmiModules.installed()) return;
        // Dove CMI tiene i suoi interruttori e come sono scritti lo sa una classe sola (util/CmiModules),
        // che serve anche al modulo dei nametag: e' l'ultimo posto da cambiare il giorno che CMI va via.
        Boolean acceso = CmiModules.enabled(plugin, "tablist");
        if (Boolean.FALSE.equals(acceso)) return;   // CMI c'e' ma il suo tablist e' spento: nessun conflitto
        String stato = acceso == null ? "non leggibile" : "acceso";
        plugin.getLogger().warning("[Tab] CMI e' installato e il suo modulo tablist risulta " + stato
                + ": due plugin sullo stesso tablist se lo strappano di mano. Intestazione, fondo e nomi"
                + " li riscriviamo dopo di lui (tablist.yml -> priority), ma le sue caselle FINTE (le 80 slot,"
                + " con testa e tacchette di connessione) non possiamo toglierle: sono voci sue, inviate"
                + " via pacchetto. Per farle sparire si spegne il suo modulo in"
                + " plugins/CMI/Settings/Modules.yml -> tablist: false.");
    }

    /**
     * Si aggancia a LuckPerms, se {@code sort-by-rank-weight} e' acceso e il plugin c'e'. Softdepend
     * vero: senza LuckPerms l'ordinamento resta spento e il tablist funziona lo stesso, nell'ordine
     * del gioco.
     */
    private void agganciaLuckPerms() {
        if (!ordinaPerGrado) return;
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            plugin.getLogger().info("[Tab] sort-by-rank-weight e' acceso ma LuckPerms non c'e':"
                    + " resta l'ordine del gioco.");
            ordinaPerGrado = false;
            return;
        }
        try {
            luckPerms = LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            plugin.getLogger().warning("[Tab] LuckPerms non ancora pronto: l'ordinamento per grado"
                    + " parte al prossimo giro utile (" + e.getMessage() + ").");
        }
    }

    /**
     * Mette {@code p} davanti a tutto (caselle finte comprese, che restano a priorita' 0 di
     * fabbrica) e, fra i giocatori veri, in ordine di PESO del grado piu' alto che ha — lo stesso
     * peso che decide %magixweb_namecolor%. E' il campo "Priority" del protocollo (Paper lo
     * espone come {@code playerListOrder}): a parita' di squadra e di nome, vince chi ha il
     * numero piu' alto, prima di qualunque altro criterio di ordinamento del gioco.
     *
     * <p>Deve essere POSITIVO (lo richiede l'API): un grado di peso 0 avrebbe comunque priorita'
     * 1, che basta e avanza per stare sempre sopra le caselle finte, mai toccate.</p>
     *
     * <p>Si scrive un pacchetto nuovo solo se il numero e' CAMBIATO da un giro all'altro (una
     * promozione, un cambio di gruppo): a parita' di grado non si tocca niente.</p>
     */
    private void ordinaGiocatore(Player p) {
        if (!ordinaPerGrado || luckPerms == null) return;
        int peso = pesoDi(p);
        int ordine = Math.max(1, peso + 1);
        Integer prima = ultimoOrdine.put(p.getUniqueId(), ordine);
        if (prima == null || prima != ordine) {
            p.setPlayerListOrder(ordine);
        }
    }

    /**
     * Il peso piu' alto fra TUTTI i gruppi di {@code p} (non solo quello primario): stessa regola
     * di MagixWeb/RankSync per il colore del nome, cosi' chi vede il grado piu' alto nel nome lo
     * vede anche per primo nel tablist. Nessun gruppo utile (LuckPerms non ancora pronto per
     * questo giocatore) -> 0, che e' il valore piu' basso possibile e comunque va bene: sara' lui
     * il primo a scendere quando i dati arrivano.
     */
    private int pesoDi(Player p) {
        User user = luckPerms.getUserManager().getUser(p.getUniqueId());
        if (user == null) return 0;
        QueryOptions options = luckPerms.getContextManager().getQueryOptions(p);
        int massimo = 0;
        for (Group g : user.getInheritedGroups(options)) {
            massimo = Math.max(massimo, g.getWeight().orElse(0));
        }
        return massimo;
    }

    /**
     * Un giro di aggiornamento per tutti: intestazione/fondo/nome a ogni chiamata (e' la parte
     * veloce, quella che fa scorrere le animazioni), le 80 slot finte solo ogni
     * {@link #FIXED_SLOTS_RESEND_MS}, indipendentemente da quanto spesso questo metodo gira.
     */
    private void updateAll() {
        boolean reinviaSlotFisse = System.currentTimeMillis() >= prossimoInvioSlotFisseMs;
        if (reinviaSlotFisse) {
            prossimoInvioSlotFisseMs = System.currentTimeMillis() + FIXED_SLOTS_RESEND_MS;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (reinviaSlotFisse) slotFisse.inviaA(p);
            update(p);
        }
    }

    /**
     * Scrive il tablist di un giocatore e, se la priorita' e' accesa, lo riscrive una seconda
     * volta poco dopo. Il tablist non ha un "proprietario": ce l'ha chi ha scritto per ultimo.
     * Quando un altro plugin (CMI) scrive nello stesso tick, la prima passata puo' perdere; la
     * seconda, qualche tick piu' in la', arriva dopo di lui. Vale per intestazione, fondo e nomi
     * — non per le voci FINTE che un altro plugin inietta via pacchetto, che restano sue (e che
     * comunque non si rimandano da qui: vedi {@link #updateAll()} e {@link #onJoin}).
     */
    private void update(Player p) {
        scrivi(p);
        if (riasserisci) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> scrivi(p), ritardoRiassersione);
        }
    }

    /**
     * Intestazione, fondo e nome — la parte che si riscrive a ogni {@code update-interval-ticks},
     * comprese le animazioni. Le slot finte NON sono qui apposta: sono un pacchetto ProtocolLib da
     * 80 voci, la cosa piu' pesante di questo modulo, e rimandarlo alla stessa velocita' voluta per
     * un'animazione (update-interval-ticks basso) e' quello che fa scattare e bloccare il tablist —
     * segnalato dall'utente. Chi le rimanda: {@link #updateAll()} (al massimo una volta al secondo)
     * e {@link #onJoin} (subito, per chi si e' appena connesso).
     */
    private void scrivi(Player p) {
        if (!p.isOnline()) return;
        ordinaGiocatore(p);
        Component header = buildLines(p, cfg.getStringList("header"));
        Component footer = buildLines(p, cfg.getStringList("footer"));
        p.sendPlayerListHeaderAndFooter(header, footer);

        String nameFmt = cfg.getString("player-name", "&7{name}");
        p.playerListName(render(p, nameFmt.replace("{name}", p.getName())));
    }

    /** Unisce le righe (ognuna un Component) con un a-capo. Lista vuota -> Component vuoto. */
    private Component buildLines(Player p, List<String> lines) {
        Component out = Component.empty();
        boolean first = true;
        for (String line : lines) {
            if (!first) out = out.append(Component.newline());
            out = out.append(render(p, line));
            first = false;
        }
        return out;
    }

    /**
     * Una riga -> Component: sostituisce {logo} e le fasi delle animazioni, risolve i placeholder
     * (se c'e' PAPI), poi applica colori e tag — stesso motore di MOTD e nametag ({@link TextFormat}),
     * quindi qui funzionano anche {@code <gradient:...>} e {@code <rainbow>}, non solo i codici {@code &}.
     */
    private Component render(Player p, String line) {
        String s = line.replace(LOGO_TOKEN, LOGO_CHAR)
                .replace(GRADIENT_PHASE_TOKEN, gradientPhase())
                .replace(RAINBOW_PHASE_TOKEN, rainbowPhase());
        if (papi) s = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, s);
        return TextFormat.component(s);
    }

    /**
     * La fase di {@code <gradient:...:qui>} in questo istante: un'onda a triangolo fra -1.0 e 1.0,
     * un giro ogni {@code animation-period-seconds}. A triangolo (non a dente di sega) perche' il
     * gradiente NON e' ciclico come un arcobaleno: alla fase +1 la sfumatura torna quella della fase
     * -1 ma con i colori scambiati (verificato in GradientTag), quindi un salto diretto da +1 a -1
     * si vedrebbe come uno scatto. Con il triangolo la posizione cambia sempre con continuita'.
     */
    private String gradientPhase() {
        double ciclo = (System.currentTimeMillis() % periodoAnimazioneMs) / (double) periodoAnimazioneMs;
        double triangolo = ciclo < 0.5 ? ciclo * 2.0 : 2.0 - ciclo * 2.0;   // 0 -> 1 -> 0
        return String.valueOf(triangolo * 2.0 - 1.0);                       // -1 -> 1 -> -1
    }

    /**
     * La fase di {@code <rainbow:qui>} in questo istante: un intero 0-9 che scorre nel tempo. A
     * differenza del gradiente qui non serve il triangolo: l'arcobaleno e' gia' un cerchio di
     * tonalita' (verificato in RainbowTag, il valore lo si usa dopo un modulo), quindi ricominciare
     * da 0 dopo 9 e' esso stesso il prossimo passo naturale, non un salto.
     */
    private String rainbowPhase() {
        double ciclo = (System.currentTimeMillis() % periodoAnimazioneMs) / (double) periodoAnimazioneMs;
        return String.valueOf((int) (ciclo * 10.0));
    }
}
