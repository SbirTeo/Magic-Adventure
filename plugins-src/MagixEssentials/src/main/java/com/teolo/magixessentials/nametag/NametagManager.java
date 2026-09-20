package com.teolo.magixessentials.nametag;

import com.teolo.magixessentials.util.CmiModules;
import com.teolo.magixessentials.util.TextFormat;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gestore dei <b>nametag</b>: la targhetta che si legge sopra la testa dei giocatori, in gioco.
 *
 * <p>Acceso e spento NON si decidono qui: il modulo {@code nametag} sta nel {@code modules.yml} e lo
 * legge la classe principale, che costruisce questa classe solo se e' attivo. Le impostazioni — righe,
 * modalita', altezze, quando sparisce — stanno nel file della funzione, {@code nametag.yml}, che arriva
 * gia' letto nel costruttore.</p>
 *
 * <h2>Due modi di disegnarla, e perche'</h2>
 * Il gioco ne sa fare una sola, di riga, e il nome vero che ci mette in mezzo accetta solo i 16 colori
 * storici: e' la targhetta di {@link NameTeams}, che in cambio costa quasi niente, sfuma con la
 * distanza e sparisce da sola quando uno si accuccia. Due righe, un esadecimale sul nome, una misura
 * diversa vogliono invece delle entita' di testo agganciate al giocatore: {@link DisplayLines}. Non
 * c'e' un modo che vinca sempre, quindi ci sono tutti e due e il config sceglie; con {@code mode: auto}
 * sceglie da se' guardando quante righe sono state scritte.
 *
 * <p><b>Diversa per ogni spettatore</b> (i placeholder {@code %rel_...%}: il verde dell'alleato, il
 * rosso del nemico) la sanno fare tutte e due, ma per due strade diverse. In vanilla e' una lavagna
 * (scoreboard) per ciascuno; in display si disegna un gruppo di entita' per ogni testo diverso, montato
 * sullo stesso giocatore, e a ognuno si nasconde quello che non e' il suo (vedi {@link #variants} e
 * {@link DisplayLines}). Il prezzo e' diverso: in vanilla una lavagna per giocatore (e allora salta il
 * pannello di un altro plugin, vedi {@link NameTeams}); in display piu' entita', poche finche' i colori
 * in gioco sono pochi.</p>
 *
 * <h2>Si lavora solo sulla differenza</h2>
 * A ogni giro si ricompone il testo di ciascuno e si confronta con quello di prima: si scrive — cioe'
 * si mandano pacchetti — solo dove e' cambiato qualcosa. Una targhetta cambia raramente (una fazione,
 * un grado), quindi il costo vero di un giro e' il conto dei placeholder, non la rete.
 */
public final class NametagManager implements Listener {

    /** Dove va il nome del giocatore in una riga. */
    private static final String NAME_TOKEN = "{name}";

    /**
     * Un placeholder di PlaceholderAPI: {@code %identificatore_qualcosa%}. Il trattino basso e'
     * richiesto di proposito — tutti i placeholder di PAPI hanno la forma
     * {@code %espansione_cosa%} — cosi' un "50% di sconto" scritto in una riga non viene preso per
     * un segnaposto. Serve a due cose: sapere se una riga, risolta, resta senza niente da leggere, e
     * cancellare quelli che sul server nessuno risolve (vedi {@link #blank(String)}).
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("%[A-Za-z][A-Za-z0-9]*_[^%\\s]*%");

    /** I placeholder RELAZIONALI di PlaceholderAPI: dipendono da chi guarda, non solo da chi e' guardato. */
    private static final String RELATIONAL = "%rel_";

    /**
     * Come si chiama, nel {@code Modules.yml} di CMI, l'interruttore delle targhette. Sul server e'
     * <b>namePlates</b> — non "nametag", che era la prima ipotesi e non esiste: CMI le chiama "name
     * plates". Gli altri nomi restano perche' fra una versione e l'altra la grafia gli cambia sotto
     * le mani, e {@code CmiModules} confronta senza badare a maiuscole e trattini; cercarne qualcuno
     * in piu' non costa niente, mentre non trovare la riga vuol dire non accorgersi del conflitto.
     */
    private static final String[] CMI_NAMETAG_KEYS =
            {"nameplates", "nameplate", "nametag", "nametags", "playernametag"};

    private final JavaPlugin plugin;
    /** Le impostazioni dei nametag: il {@code nametag.yml} della cartella dati. */
    private final ConfigurationSection cfg;
    private final boolean papi;
    private final NameTeams teams = new NameTeams();
    private final DisplayLines displays;

    private BukkitTask task;
    private List<String> lines = List.of(NAME_TOKEN);
    /** Se le righe le disegniamo noi (modalita' display) invece di lasciarle al gioco. */
    private boolean ourLines;
    /** Lavagne per-spettatore per la targhetta del gioco (relazionale in vanilla). */
    private boolean perViewer;
    /** Un gruppo di entita' per ogni testo diverso: il relazionale in modalita' display. */
    private boolean displayPerViewer;
    private boolean skipEmpty = true;
    private boolean nameColor = true;
    private Set<String> offWorlds = Set.of();
    /** Quanto resta visibile la targhetta di chi si accuccia: 1.0 = invariata, 0.0 = invisibile. */
    private float sneakOpacity = 0.3f;
    private boolean hideInvisible = true;
    private boolean hideSpectator = true;
    /** Una riga senza {name} e' un errore di configurazione: si dice una volta, non a ogni giro. */
    private boolean warnedNameless;
    /** I segnaposto che nessuno risolve, gia' segnalati: uno stesso errore si dice una volta sola. */
    private final Set<String> warnedTokens = new HashSet<>();
    /**
     * Da dove vengono le righe in uso: il nome dello stile, oppure "lines" se sono scritte a mano.
     * Lo legge anche la guida per lo staff, da un altro thread: da qui il volatile.
     */
    private volatile String style = "";

    public NametagManager(JavaPlugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.papi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        this.displays = new DisplayLines(plugin, cfg.getConfigurationSection("display"));
    }

    public void start() {
        readConfig();
        settleWithCmi();
        displays.load();
        teams.perViewer(perViewer);

        Bukkit.getPluginManager().registerEvents(this, plugin);
        long interval = Math.max(1, cfg.getLong("update-interval-ticks", 40));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 20L, interval);
        refresh();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        HandlerList.unregisterAll(this);
        // Prima le entita' (vivono nel mondo), poi le squadre (vivono nelle lavagne): dopo un reload
        // non deve restare in giro niente che nessuno aggiorna piu'.
        displays.clear();
        teams.clear();
    }

    /** Le impostazioni di questo giro di accensione, lette una volta sola. */
    private void readConfig() {
        // Le righe scritte a mano vincono su tutto: sono la scelta di CHI CONFIGURA QUESTO server.
        // Vuote, le decide lo stile della modalita' (vedi pickStyle).
        List<String> written = cfg.getStringList("lines");
        if (written.isEmpty()) {
            lines = pickStyle();
        } else {
            lines = List.copyOf(written);
            style = "lines";
        }

        String mode = String.valueOf(cfg.getString("mode", "auto")).trim();
        boolean vanilla = mode.equalsIgnoreCase("vanilla");
        // auto: il gioco quando basta (una riga sola), noi quando non basta piu'.
        ourLines = mode.equalsIgnoreCase("display") || (!vanilla && lines.size() > 1);
        if (vanilla && lines.size() > 1) {
            plugin.getLogger().info("[Nametag] mode: vanilla — il gioco disegna una riga sola: uso"
                    + " l'ultima (quella del nome) e lascio le altre nel file. Per vederle tutte serve"
                    + " mode: display (o auto).");
        }

        String viewers = String.valueOf(cfg.getString("per-viewer", "auto")).trim();
        boolean relational = false;
        for (String line : lines) {
            relational |= line.contains(RELATIONAL);
        }
        // Serve una targhetta diversa per chi guarda? Poi COME la si fa dipende dalla modalita': in
        // vanilla con una lavagna per giocatore, in display con un gruppo di entita' per ogni testo.
        boolean wantPerViewer = viewers.equalsIgnoreCase("always")
                || (!viewers.equalsIgnoreCase("never") && relational);
        perViewer = wantPerViewer && !ourLines;
        displayPerViewer = wantPerViewer && ourLines;
        if (relational && viewers.equalsIgnoreCase("never")) {
            plugin.getLogger().warning("[Nametag] nelle righe c'e' un placeholder relazionale (" + RELATIONAL
                    + "...), ma per-viewer e' su never: quei placeholder valgono come se il giocatore"
                    + " guardasse se stesso.");
        }

        skipEmpty = cfg.getBoolean("skip-empty-lines", true);
        nameColor = "auto".equalsIgnoreCase(cfg.getString("vanilla.name-color", "auto"));
        Set<String> worlds = new HashSet<>();
        for (String world : cfg.getStringList("disabled-worlds")) {
            worlds.add(world.toLowerCase(Locale.ROOT));
        }
        offWorlds = Set.copyOf(worlds);
        sneakOpacity = clamp01((float) cfg.getDouble("display.sneak-opacity", 0.3));
        hideInvisible = cfg.getBoolean("display.hide-when-invisible", true);
        hideSpectator = cfg.getBoolean("display.hide-in-spectator", true);
        // Una riga nel log che risponde da sola alla domanda "perche' sopra la testa vedo questo?".
        plugin.getLogger().info("[Nametag] " + describe() + ".");
        if (!papi) {
            plugin.getLogger().warning("[Nametag] PlaceholderAPI non c'e': i segnaposto nelle righe"
                    + " restano vuoti (non scritti a schermo), quindi le targhette si riducono al nome."
                    + " Le righe fisse funzionano lo stesso.");
        }
    }

    /**
     * Da dove vengono le righe e chi le disegna, in una riga di italiano: va nel log all'avvio e
     * nella guida per lo staff, che cosi' non deve indovinare cosa sta usando QUESTO server.
     */
    public String describe() {
        String from = "lines".equals(style)
                ? "righe scritte a mano in lines"
                : "stile " + (style.isEmpty() ? "nessuno" : style);
        return from + ", " + lines.size() + (lines.size() == 1 ? " riga" : " righe")
                + ", disegnate " + (ourLines ? "da noi (entita' di testo)" : "dal gioco (squadre)");
    }

    /**
     * Le righe dello stile giusto per <b>questo</b> server.
     *
     * <h2>Perche' non c'e' un default unico</h2>
     * Lo stesso jar gira su server di modalita' diverse: un default che parla di fazioni sarebbe
     * sbagliato su tutti gli altri, e scriverebbe %magixfactions_faction% sopra la testa della gente.
     * Percio' il file porta un elenco di stili, uno per modalita', ciascuno con i plugin che gli
     * servono; con {@code style: auto} si usa il primo i cui plugin ci sono tutti — ed e' questa la
     * "rilevazione della modalita'": non un indovinello sul nome del server, ma cosa c'e' installato.
     * L'ultimo dell'elenco non chiede niente, cosi' una risposta c'e' sempre.
     *
     * <p>Con un nome invece di {@code auto} si impone quello stile, requisiti o no: serve per
     * provarlo, o quando la rilevazione sceglierebbe un altro.</p>
     *
     * <p>Gli stili sono un <b>elenco</b>, non una sezione di chiavi, e non e' un dettaglio: lo
     * allineamento dei config toglie le chiavi che il jar non conosce, mentre le voci di un elenco le
     * lascia stare. Cosi' una modalita' nuova la puo' aggiungere lo staff, sul suo server, senza
     * aspettare una versione del plugin.</p>
     */
    private List<String> pickStyle() {
        String wanted = String.valueOf(cfg.getString("style", "auto")).trim();
        boolean auto = wanted.isEmpty() || wanted.equalsIgnoreCase("auto");
        for (Map<?, ?> entry : cfg.getMapList("styles")) {
            String name = text(entry.get("name"));
            if (auto ? !hasPlugins(entry.get("requires")) : !wanted.equalsIgnoreCase(name)) {
                continue;
            }
            List<String> rows = rows(entry.get("lines"));
            if (rows.isEmpty()) {
                continue;   // uno stile senza righe non e' una risposta: si guarda il prossimo
            }
            style = name.isEmpty() ? "senza nome" : name;
            return List.copyOf(rows);
        }
        style = auto ? "nessuno" : wanted + " (che non esiste)";
        plugin.getLogger().warning("[Nametag] " + (auto
                ? "nessuno stile va bene per questo server (e nemmeno l'ultimo, quello senza requisiti:"
                        + " manca dall'elenco?)"
                : "lo stile «" + wanted + "» non e' nell'elenco degli stili")
                + ": resta il nome e basta. Scrivi le righe che vuoi in nametag.yml -> lines, oppure"
                + " aggiungi la voce che manca in styles.");
        return List.of(NAME_TOKEN);
    }

    /**
     * Se i plugin che uno stile pretende ci sono <b>tutti</b>. E' una E, non una O: i requisiti sono
     * la domanda "questo stile ha senso su questo server?", e una risposta a meta' non e' una
     * risposta. Chi vuole due modalita' scrive due stili, non un requisito piu' largo. Nessuna
     * pretesa (elenco vuoto) va sempre bene, ed e' cosi' che l'ultimo stile fa da ultima spiaggia.
     *
     * <p><b>Si guarda se il plugin e' CARICATO, non se e' gia' acceso.</b> L'ordine in cui il server
     * accende i plugin non e' una promessa: se MagixEssentials parte prima di quello che fornisce la
     * modalita', un controllo "e' accesso?" risponderebbe no e lo stile giusto verrebbe scartato per
     * una questione di secondi — con la targhetta sbagliata fino al reload successivo, e nessun
     * errore da nessuna parte. Caricati invece lo sono tutti prima che il primo venga acceso, e il
     * caricamento e' la domanda vera: quel plugin fa parte di questo server o no. Se poi non si
     * accende (un suo guasto), lo stile resta scelto e i suoi segnaposto restano vuoti — vedi
     * {@link #blank(String)} — che e' meglio di cambiare la faccia del server perche' un plugin ha
     * avuto un problema.</p>
     */
    private boolean hasPlugins(Object requires) {
        for (String name : rows(requires)) {
            if (!name.isBlank() && Bukkit.getPluginManager().getPlugin(name.trim()) == null) {
                return false;
            }
        }
        return true;
    }

    /** Un valore del file che puo' essere una riga sola o un elenco, letto sempre come elenco. */
    private List<String> rows(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> many) {
            List<String> out = new ArrayList<>(many.size());
            for (Object one : many) {
                out.add(text(one));
            }
            return out;
        }
        return List.of(text(value));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /**
     * La targhetta ce l'ha chi scrive per ultimo: finche' anche CMI la gestisce, i due si sovrascrivono
     * a vicenda e vince il caso. Se il config lo permette gli spegniamo il modulo nel suo file — un
     * ritocco a una riga sola, con la copia di scorta accanto — altrimenti ci limitiamo ad avvisare.
     * In tutti e due i casi lo scriviamo nel log: lo staff non deve indovinare perche' la targhetta
     * "torna come prima".
     */
    private void settleWithCmi() {
        if (!CmiModules.installed()) {
            return;
        }
        Boolean on = CmiModules.enabled(plugin, CMI_NAMETAG_KEYS);
        if (Boolean.FALSE.equals(on)) {
            return;         // CMI c'e' ma le targhette non le tocca: nessun conflitto
        }
        String state = on == null ? "non leggibile" : "acceso";
        if (Boolean.TRUE.equals(on) && cfg.getBoolean("cmi.disable-module", true)
                && CmiModules.disable(plugin, CMI_NAMETAG_KEYS)) {
            plugin.getLogger().info("[Nametag] il modulo dei nametag di CMI era acceso: l'ho spento nel suo"
                    + " Settings/Modules.yml (copia di scorta accanto). CMI quel file lo legge all'avvio,"
                    + " quindi serve un RIAVVIO del server perche' smetta di scrivere anche lui.");
            return;
        }
        plugin.getLogger().warning("[Nametag] CMI e' installato e il suo modulo dei nametag risulta " + state
                + ": due plugin sulla stessa targhetta se la strappano di mano, e vince chi scrive per"
                + " ultimo. Spegnilo in plugins/CMI/Settings/Modules.yml (oggi quella riga si chiama"
                + " namePlates: mettila a false) e riavvia, oppure lascia fare a noi con"
                + " nametag.yml -> cmi.disable-module: true.");
    }

    // ------------------------------------------------- IL GIRO

    private void refresh() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        List<Scoreboard> boards = teams.boards(online);
        for (Player target : online) {
            apply(target, boards, online);
        }
    }

    /** La targhetta di un giocatore, scritta dove va scritta. */
    private void apply(Player target, List<Scoreboard> boards, Collection<? extends Player> online) {
        boolean off = offWorlds.contains(target.getWorld().getName().toLowerCase(Locale.ROOT));
        if (ourLines) {
            if (off || hidden(target)) {
                displays.update(target, List.of(), opacity(target));
            } else if (displayPerViewer) {
                // Una variante per ogni testo diverso (il colore relazionale cambia per chi guarda),
                // ciascuna coi suoi spettatori: DisplayLines nasconde a ognuno quelle che non sono le sue.
                displays.updateVariants(target, variants(target, online), opacity(target));
            } else {
                displays.update(target, rendered(target, target), opacity(target));
            }
            // Il nome del gioco si nasconde solo dove la targhetta la disegniamo noi: due targhette
            // sovrapposte sono peggio di una brutta. Nei mondi esclusi torna visibile.
            for (Scoreboard board : boards) {
                teams.write(board, target, "", "", !off, false);
            }
            return;
        }
        // Modalita' vanilla: il gioco disegna prefisso + nome + suffisso, e le righe in piu' (se ce ne
        // sono) restano nel file. Nei mondi esclusi si scrive il nome nudo.
        String format = off ? NAME_TOKEN : lines.get(lines.size() - 1);
        if (perViewer) {
            for (Player viewer : online) {
                String[] parts = parts(viewer, target, format);
                teams.write(teams.boardOf(viewer), target, parts[0], parts[1], false, nameColor);
            }
            return;
        }
        String[] parts = parts(target, target, format);
        for (Scoreboard board : boards) {
            teams.write(board, target, parts[0], parts[1], false, nameColor);
        }
    }

    /**
     * La riga del nome spezzata nei due pezzi che il gioco sa mettere intorno al nome vero: quello che
     * sta prima di {@code {name}} e quello che sta dopo, coi placeholder gia' risolti.
     */
    private String[] parts(Player viewer, Player target, String format) {
        int at = format.indexOf(NAME_TOKEN);
        if (at < 0 && !warnedNameless) {
            warnedNameless = true;
            plugin.getLogger().warning("[Nametag] nell'ultima riga di nametag.yml -> lines non c'e' {name}:"
                    + " il gioco il nome vero lo disegna comunque, quindi quella riga gli finisce davanti"
                    + " come prefisso. Mettici {name} dove vuoi che compaia il nome.");
        }
        String prefix = at < 0 ? format : format.substring(0, at);
        String suffix = at < 0 ? "" : format.substring(at + NAME_TOKEN.length());
        return new String[]{resolve(viewer, target, prefix), resolve(viewer, target, suffix)};
    }

    /**
     * Le varianti di una targhetta in modalita' display quando cambia da spettatore a spettatore: per
     * ogni giocatore online si risolvono le sue righe (i {@code %rel_...%} dipendono da chi guarda) e si
     * raggruppano quelli che ottengono lo <b>stesso testo</b>, cosi' due che vedono lo stesso colore
     * condividono un gruppo solo. Con {@code hide-self} il target non e' fra gli spettatori: la propria
     * targhetta non la vede comunque.
     */
    private List<DisplayLines.Variant> variants(Player target, Collection<? extends Player> online) {
        boolean skipSelf = displays.hideSelf();
        Map<List<String>, Set<UUID>> byText = new LinkedHashMap<>();
        for (Player viewer : online) {
            if (skipSelf && viewer.equals(target)) {
                continue;
            }
            byText.computeIfAbsent(rendered(viewer, target), k -> new HashSet<>()).add(viewer.getUniqueId());
        }
        List<DisplayLines.Variant> out = new ArrayList<>(byText.size());
        for (Map.Entry<List<String>, Set<UUID>> e : byText.entrySet()) {
            out.add(new DisplayLines.Variant(e.getKey(), e.getValue()));
        }
        return out;
    }

    /**
     * Le righe da disegnare, dall'alto verso il basso, coi placeholder risolti per lo spettatore
     * {@code viewer} che guarda {@code target} (i due coincidono quando la targhetta e' uguale per
     * tutti). Con {@code skip-empty-lines} le righe rimaste senza niente da leggere non vengono nemmeno
     * create — tutte tranne l'ultima, che e' quella del nome e non si salta mai.
     */
    private List<String> rendered(Player viewer, Player target) {
        List<String> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean last = i == lines.size() - 1;
            if (!last && skipEmpty && nothingLeft(viewer, target, line)) {
                continue;
            }
            out.add(resolve(viewer, target, line.replace(NAME_TOKEN, target.getName())));
        }
        return out;
    }

    /**
     * Se di una riga, risolta, non resta niente da leggere: cioe' se ha dei placeholder e sono
     * <b>tutti</b> vuoti. E' la riga della fazione sopra la testa di chi non ne ha nessuna: senza
     * questo controllo resterebbe appeso un {@code []}. Una riga senza placeholder — una decorazione
     * scritta a mano — non e' mai "vuota": quella l'ha voluta qualcuno.
     */
    private boolean nothingLeft(Player viewer, Player target, String line) {
        Matcher m = PLACEHOLDER.matcher(line);
        StringBuilder found = new StringBuilder();
        while (m.find()) {
            found.append(m.group());
        }
        if (found.length() == 0) {
            return false;
        }
        return TextFormat.plain(resolve(viewer, target, found.toString())).isBlank();
    }

    /**
     * I placeholder di un testo, risolti. Ci passa tutto: dentro valgono <b>tutti</b> quelli di
     * PlaceholderAPI — quindi anche tutti quelli dei plugin Magix, che sono espansioni di PAPI — e
     * quelli <b>relazionali</b> {@code %rel_...%}, che dipendono da chi guarda.
     *
     * <p>PAPI e' softdepend: senza di lui la targhetta funziona lo stesso, ma i {@code %...%} restano
     * scritti come sono.</p>
     */
    private String resolve(Player viewer, Player target, String text) {
        if (text.isEmpty()) {
            return text;
        }
        String out = text;
        if (papi) {
            // I relazionali si chiedono a parte (vogliono due giocatori) e prima: quello che tornano
            // sono di solito colori, che poi devono valere per il testo che segue.
            if (out.contains(RELATIONAL)) {
                out = me.clip.placeholderapi.PlaceholderAPI.setRelationalPlaceholders(viewer, target, out);
            }
            out = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(target, out);
        }
        return blank(out);
    }

    /**
     * I segnaposto che <b>nessuno ha risolto</b> restano vuoti invece di finire scritti a schermo.
     *
     * <p>PlaceholderAPI, di un %...% che non conosce, non sa che fare e lo lascia com'e': in una
     * pagina web si nota e si corregge, sopra la testa di un giocatore e' spazzatura. E' il caso che
     * capita da se' in un network: lo stesso stile su un server di un'altra modalita', dove il plugin
     * che forniva quel segnaposto non c'e'. Lasciandolo vuoto, la riga resta senza niente da leggere
     * e con {@code skip-empty-lines} non viene nemmeno disegnata — cioe' la decorazione di una
     * modalita' sparisce da sola dove quella modalita' non esiste.</p>
     *
     * <p>Nel log si dice una volta per segnaposto: sparire in silenzio sarebbe comodo oggi e un
     * mistero domani.</p>
     */
    private String blank(String text) {
        if (text.indexOf('%') < 0) {
            return text;
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = null;
        while (m.find()) {
            if (out == null) {
                out = new StringBuilder();
            }
            if (warnedTokens.add(m.group())) {
                plugin.getLogger().warning("[Nametag] " + m.group() + " non lo risolve nessuno"
                        + (papi ? " (manca l'espansione, o il plugin che la fornisce non e' ancora"
                                + " partito)" : " perche' PlaceholderAPI non c'e'")
                        + ": lo lascio vuoto invece di scriverlo sopra la testa dei giocatori.");
            }
            m.appendReplacement(out, "");
        }
        if (out == null) {
            return text;
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Quando le righe che disegniamo noi non si devono vedere per niente. La targhetta del gioco
     * queste cose le fa da se' — l'invisibilita' la nasconde — ma un'entita' di testo no: un
     * rettangolo che galleggia da solo direbbe a tutti dov'e' chi non si dovrebbe vedere.
     *
     * <p>Accucciarsi NON e' fra questi casi: il gioco, con la sua targhetta, non la fa sparire del
     * tutto mentre ci si accuccia, la sfuma soltanto — vedi {@link #opacity(Player)}.</p>
     */
    private boolean hidden(Player p) {
        if (p.isDead()) {
            return true;
        }
        if (hideSpectator && p.getGameMode() == GameMode.SPECTATOR) {
            return true;
        }
        return hideInvisible && (p.isInvisible() || p.hasPotionEffect(PotionEffectType.INVISIBILITY)
                || vanished(p));
    }

    /**
     * Quanto opaca deve essere la targhetta disegnata da noi: piena (byte -1, cioe' 255 senza segno)
     * di norma, sfumata a {@link #sneakOpacity} mentre il giocatore si accuccia — cosi' com'e' la
     * targhetta del gioco, che l'accucciata non la nasconde ma la sfuma.
     */
    private byte opacity(Player p) {
        if (sneakOpacity >= 1f || !p.isSneaking()) {
            return (byte) -1;
        }
        return (byte) Math.round(sneakOpacity * 255f);
    }

    /**
     * Se un altro plugin lo tiene nascosto. Il {@code /vanish} non e' roba del gioco: e' una
     * convenzione fra plugin, che marchiano il giocatore con {@code vanished} — la leggono cosi'
     * Essentials, CMI e chi viene dopo, e la leggiamo cosi' anche noi.
     */
    private boolean vanished(Player p) {
        for (MetadataValue value : p.getMetadata("vanished")) {
            if (value.asBoolean()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------- EVENTI

    /**
     * Un ingresso cambia le carte a tutti: il nuovo arrivato ha bisogno delle targhette di chi c'e'
     * gia', e gli altri della sua. Si rifa' il giro intero un tick dopo — cosi' mondo e placeholder
     * sono pronti — e non costa niente, perche' si riscrive solo quello che e' cambiato.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent e) {
        later();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        displays.remove(e.getPlayer());
        teams.forget(e.getPlayer());
    }

    /** Accucciarsi nasconde la targhetta: aspettare il prossimo giro si vedrebbe come un ritardo. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        later();
    }

    /** Cambiando mondo le entita' restano nel mondo di prima: vanno rifatte di la'. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        later();
    }

    /** Morendo il giocatore perde i passeggeri: al ritorno si rimontano. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        later();
    }

    /** Entrare o uscire da spettatore cambia chi si vede. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        later();
    }

    /**
     * Un giro un tick piu' tardi. Gli eventi arrivano PRIMA che lo stato cambi davvero (chi si
     * accuccia non e' ancora accucciato, chi rinasce non e' ancora rinato): leggerlo subito darebbe
     * la risposta di un attimo prima.
     */
    private void later() {
        Bukkit.getScheduler().runTaskLater(plugin, this::refresh, 1L);
    }
}
