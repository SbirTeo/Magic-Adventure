package com.teolo.magixessentials.tab;

import com.teolo.magixessentials.util.CmiModules;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

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

    /** &-codes + &#RRGGBB (il formato che produce %magixweb_namecolor%) + il vecchio &x&r&r... di Bungee. */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private final JavaPlugin plugin;
    /** Le impostazioni del tablist: il {@code tablist.yml} della cartella dati. */
    private final ConfigurationSection cfg;
    private final boolean papi;
    private BukkitTask task;
    /** Riscrivere una seconda volta poco dopo, per arrivare DOPO chi scrive nello stesso tick. */
    private boolean riasserisci = true;
    private long ritardoRiassersione = 2L;
    private final FixedSlots slotFisse;

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
        PlayerJoinEvent.getHandlerList().unregister(this);
    }

    /**
     * Ultimo a scrivere, non primo: {@code MONITOR} fa arrivare questo handler DOPO quelli degli
     * altri plugin sullo stesso evento. Da solo non basta (CMI riscrive anche a intervalli suoi),
     * ma toglie di mezzo il caso piu' visibile: il tablist sbagliato nei primi istanti dopo il join.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent e) {
        // Un tick dopo il join: cosi' i placeholder di mondo/posizione sono gia' pronti.
        Bukkit.getScheduler().runTaskLater(plugin, () -> update(e.getPlayer()), 1L);
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

    private void updateAll() {
        for (Player p : Bukkit.getOnlinePlayers()) update(p);
    }

    /**
     * Scrive il tablist di un giocatore e, se la priorita' e' accesa, lo riscrive una seconda
     * volta poco dopo. Il tablist non ha un "proprietario": ce l'ha chi ha scritto per ultimo.
     * Quando un altro plugin (CMI) scrive nello stesso tick, la prima passata puo' perdere; la
     * seconda, qualche tick piu' in la', arriva dopo di lui. Vale per intestazione, fondo e nomi
     * — non per le voci FINTE che un altro plugin inietta via pacchetto, che restano sue.
     */
    private void update(Player p) {
        scrivi(p);
        if (riasserisci) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> scrivi(p), ritardoRiassersione);
        }
    }

    private void scrivi(Player p) {
        if (!p.isOnline()) return;
        // Prima le caselle finte, poi intestazione e fondo: cosi' la misura del tab e' gia' quella
        // definitiva quando il client disegna il resto, e non si vede la finestra allargarsi.
        slotFisse.inviaA(p);
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

    /** Una riga -> Component: sostituisce {logo}, risolve i placeholder (se c'e' PAPI), applica i colori. */
    private Component render(Player p, String line) {
        String s = line.replace(LOGO_TOKEN, LOGO_CHAR);
        if (papi) s = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, s);
        return LEGACY.deserialize(s);
    }
}
