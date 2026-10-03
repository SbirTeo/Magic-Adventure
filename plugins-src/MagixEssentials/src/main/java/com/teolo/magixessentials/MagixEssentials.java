package com.teolo.magixessentials;

import com.teolo.magixessentials.chat.ChatModule;
import com.teolo.magixessentials.currency.CurrencyManager;
import com.teolo.magixessentials.currency.CurrencyPlaceholders;
import com.teolo.magixessentials.customjoinitems.CustomJoinItems;
import com.teolo.magixessentials.hook.Papi;
import com.teolo.magixessentials.hublobby.HubLobby;
import com.teolo.magixessentials.lang.Messages;
import com.teolo.magixessentials.module.Modules;
import com.teolo.magixessentials.motd.MotdListener;
import com.teolo.magixessentials.nametag.NametagManager;
import com.teolo.magixessentials.tabcomplete.TabCompleteFilter;
import com.teolo.magixessentials.tab.TabManager;
import com.teolo.magixessentials.util.ConfigAlign;
import com.teolo.magixessentials.util.ConfigValues;
import com.teolo.magixessentials.util.Help;
import com.teolo.magixessentials.util.StaffGuide;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * MagixEssentials: raccoglie le utilita' "di base" del server — il <b>tablist</b> (la lista
 * giocatori del tasto Tab), la <b>MOTD</b> (le righe che si
 * leggono nella lista server), il <b>nametag</b> (la targhetta sopra la testa dei giocatori), il
 * <b>filtro dell'autocompletamento</b> (toglie dal TAB i comandi senza permesso), la <b>chat</b>
 * (il formato della chat pubblica e i messaggi che arrivano dal sito) e le <b>valute</b> (lo
 * staff crea monete di gioco come le gemme, ciascuna col suo comando); l'idea a lungo termine e'
 * che assorba cio' che oggi fa CMI.
 *
 * <p>Ogni funzione si accende e si spegne dal {@code modules.yml}, come nel Modules.yml di CMI, e
 * si regola nel file che porta il suo nome ({@code tablist.yml}, {@code motd.yml}, {@code nametag.yml},
 * {@code chat.yml}, {@code currencies.yml}) quando ne ha uno; il {@code config.yml} tiene solo
 * cio' che vale per il plugin intero. Vedi {@link Modules}.
 *
 * <p>Ogni funzione sta per conto suo ({@link TabManager}, {@link MotdListener}, {@link NametagManager},
 * {@link TabCompleteFilter}, {@link ChatModule}, {@link CurrencyManager}): questa classe si
 * limita ad accenderle e spegnerle e a offrire {@code /magixessentials reload}.
 */
public final class MagixEssentials extends JavaPlugin {

    private Modules modules;
    private TabManager tabManager;
    private MotdListener motd;
    private NametagManager nametag;
    private TabCompleteFilter tabComplete;
    private ChatModule chat;
    private CurrencyManager currencies;
    private CustomJoinItems joinItems;
    private HubLobby hubLobby;
    private Messages messages;

    @Override
    public void onEnable() {
        Papi.setup();
        saveDefaultConfig();
        // Gli interruttori delle funzioni e i file di impostazioni delle funzioni stesse PRIMA
        // dell'allineamento: ConfigAlign tocca solo i file che nella cartella dati esistono gia',
        // e a crearli e' questa riga (vedi module/Modules).
        modules = new Modules(this);
        // I file di configurazione SUL SERVER allineati a quelli del jar: le chiavi nuove
        // compaiono da sole, al loro posto e col loro commento, senza toccare i valori
        // gia' scelti. Il deploy porta solo il jar, quindi senza questo il file del server
        // resterebbe indietro in silenzio (vedi util/ConfigAlign).
        ConfigAlign.alignAll(this);
        reloadConfig();
        messages = new Messages(this);
        modules.ricarica();   // dopo l'allineamento: cosi' legge anche le chiavi appena aggiunte
        avviaModuli();
        // Il capitolo della guida per lo staff sul sito + il README nella cartella del plugin:
        // stessa scrittura, letta dal config vivo. Puro I/O, fuori dal tick d'avvio. Va DOPO
        // l'accensione dei moduli perche' racconta anche quello che i moduli hanno scelto (per
        // esempio quale stile di nametag va bene per questo server), non solo quello che c'e' scritto.
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);
        getLogger().info("MagixEssentials abilitato (moduli: " + modules.riepilogo() + ").");
    }

    @Override
    public void onDisable() {
        spegniModuli();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(java.util.Locale.ROOT);
        if (sub.matches("\\d+")) {
            help(sender, page(sub));
            return true;
        }
        switch (sub) {
            case "help", "?" -> help(sender, args.length >= 2 ? page(args[1]) : 1);
            case "reload" -> reload(sender);
            case "joinitems" -> joinItems(sender, args);
            default -> messages.send(sender, "unknown-subcommand");
        }
        return true;
    }

    @Override
    public java.util.List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        String prefix = args[args.length - 1].toLowerCase(java.util.Locale.ROOT);
        if (args.length == 1) {
            return java.util.List.of("help", "reload", "joinitems").stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args[0].equalsIgnoreCase("joinitems")) {
            if (args.length == 2) {
                return java.util.List.of("give", "remove").stream().filter(s -> s.startsWith(prefix)).toList();
            }
            if (args.length == 3) {
                java.util.List<String> names = new java.util.ArrayList<>(CustomJoinItems.onlineNames(prefix));
                if ("all".startsWith(prefix)) names.add("all");
                return names;
            }
        }
        return java.util.List.of();
    }

    /** /mess joinitems give|remove [giocatore|all]: senza nome vale per chi scrive. */
    private void joinItems(CommandSender sender, String[] args) {
        if (joinItems == null) {
            messages.send(sender, "customjoinitems.disabled");
            return;
        }
        boolean give = args.length >= 2 && args[1].equalsIgnoreCase("give");
        boolean remove = args.length >= 2 && args[1].equalsIgnoreCase("remove");
        if (!give && !remove) {
            messages.send(sender, "unknown-subcommand");
            return;
        }
        java.util.Collection<? extends org.bukkit.entity.Player> targets;
        if (args.length >= 3 && args[2].equalsIgnoreCase("all")) {
            targets = Bukkit.getOnlinePlayers();
        } else if (args.length >= 3) {
            org.bukkit.entity.Player one = CustomJoinItems.find(args[2]);
            if (one == null) {
                messages.send(sender, "customjoinitems.player-not-found", "player", args[2]);
                return;
            }
            targets = java.util.List.of(one);
        } else if (sender instanceof org.bukkit.entity.Player self) {
            targets = java.util.List.of(self);
        } else {
            messages.send(sender, "unknown-subcommand");
            return;
        }
        int n = give ? joinItems.giveNow(targets) : joinItems.removeNow(targets);
        messages.send(sender, give ? "customjoinitems.given" : "customjoinitems.removed", "count", String.valueOf(n));
    }

    private void reload(CommandSender sender) {
        // Come all'avvio: prima si allineano i file del server a quelli del jar, poi si
        // rilegge. Cosi' un reload dopo un deploy vede anche le chiavi nuove.
        ConfigAlign.alignAll(this);
        reloadConfig();
        messages.reload();
        modules.ricarica();
        // Sempre tutto spento e poi riacceso solo quello che il modules.yml dice adesso:
        // cosi' una funzione spenta un attimo fa sparisce davvero, invece di restare appesa
        // com'era prima del reload.
        spegniModuli();
        avviaModuli();
        // La guida riporta i valori VIVI del config: se non la riscrivessimo qui, dopo un
        // reload resterebbe indietro fino al prossimo riavvio.
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);
        messages.send(sender, "reloaded", "modules", modules.riepilogo());
    }

    /** /mess help [pagina]: l'elenco comandi con la pagina comune dei plugin Magix (util/Help). */
    private void help(CommandSender sender, int page) {
        Help.show(sender, messages::text, messages.text(sender, "help.title"), "/mess help",
                Help.fromConfig(messages.section("help.sections"), sender, messages::text, messages::list),
                page, sender.hasPermission("magixessentials.admin"));
    }

    private static int page(String s) {
        try {
            return Math.max(1, Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Accende le funzioni accese nel {@code modules.yml}, ciascuna col suo file di impostazioni
     * riletto adesso. Una funzione spenta non viene nemmeno costruita.
     */
    private void avviaModuli() {
        if (modules.attivo(Modules.TABLIST)) {
            tabManager = new TabManager(this, modules.configurazioneDi(Modules.TABLIST));
            tabManager.start();
        }
        if (modules.attivo(Modules.MOTD)) {
            motd = new MotdListener(this, modules.configurazioneDi(Modules.MOTD));
            motd.start();
        }
        if (modules.attivo(Modules.NAMETAG)) {
            nametag = new NametagManager(this, modules.configurazioneDi(Modules.NAMETAG));
            nametag.start();
        }
        if (modules.attivo(Modules.TABCOMPLETE)) {
            tabComplete = new TabCompleteFilter(this);
            tabComplete.start();
        }
        if (modules.attivo(Modules.CHAT)) {
            chat = new ChatModule(this, modules.configurazioneDi(Modules.CHAT));
            chat.start();
        }
        if (modules.attivo(Modules.CURRENCIES)) {
            currencies = new CurrencyManager(this, modules.configurazioneDi(Modules.CURRENCIES));
            currencies.start();
        }
        if (modules.attivo(Modules.CUSTOMJOINITEMS)) {
            joinItems = new CustomJoinItems(this, modules.configurazioneDi(Modules.CUSTOMJOINITEMS), messages);
            joinItems.start();
        }
        if (modules.attivo(Modules.HUB_LOBBY)) {
            hubLobby = new HubLobby(this, modules.configurazioneDi(Modules.HUB_LOBBY));
            hubLobby.start();
        }
    }

    /**
     * API per altri plugin — la usa <b>MagixBridge</b> (chat live del sito) per riflessione, cosi'
     * i plugin restano indipendenti: pubblica in chat un messaggio scritto dal SITO con lo stesso
     * formato della chat pubblica (chat.yml). Va chiamata dal main thread.
     *
     * @param prefix il grado di chi scrive gia' risolto: prende il posto di %luckperms_prefix%
     * @return false se il modulo chat e' spento (il chiamante usa il suo formato di ripiego)
     */
    public boolean broadcastWebChat(java.util.UUID senderUuid, String senderName, String message, String prefix) {
        if (chat == null || senderUuid == null || senderName == null || message == null) {
            return false;
        }
        chat.broadcastWeb(senderUuid, senderName, message, prefix);
        return true;
    }

    /** Spegne tutto: al reload si riparte da zero, allo spegnimento non si lascia niente appeso. */
    private void spegniModuli() {
        if (tabManager != null) { tabManager.stop(); tabManager = null; }
        if (motd != null) { motd.stop(); motd = null; }
        if (nametag != null) { nametag.stop(); nametag = null; }
        if (tabComplete != null) { tabComplete.stop(); tabComplete = null; }
        if (chat != null) { chat.stop(); chat = null; }
        if (currencies != null) { currencies.stop(); currencies = null; }
        if (joinItems != null) { joinItems.stop(); joinItems = null; }
        if (hubLobby != null) { hubLobby.stop(); hubLobby = null; }
    }

    // ------------------------------------------------- GUIDA PER LO STAFF

    /**
     * Capitolo di MagixEssentials nella guida del gestionale + README. Comandi, permessi e
     * valori di configurazione non si ricopiano: li legge da solo. Vedi plugins-src/GUIDA-STAFF.md.
     */
    private void writeStaffGuide() {
        StaffGuide.create(this, "MagixEssentials — tablist, MOTD, nametag e utilità del server", 90)
                // I numeri (intervallo, slot...) vengono dai file veri: cambiando una chiave,
                // questo capitolo cambia da solo (vedi util/ConfigValues).
                .values(new ConfigValues(this)
                        .also(modules.configurazioneDi(Modules.TABLIST))
                        .also(modules.configurazioneDi(Modules.MOTD))
                        .also(modules.configurazioneDi(Modules.NAMETAG))
                        .also(modules.configurazioneDi(Modules.CHAT))
                        // Lo stile dei nametag non e' un valore del config: e' una SCELTA fatta
                        // all'avvio guardando quali plugin ci sono. Chiederlo al modulo e' l'unico
                        // modo di non raccontarne uno sbagliato.
                        .extra("CHAT_STILE", chat == null
                                ? "il modulo è spento"
                                : chat.describe())
                        .extra("NAMETAG_STILE", nametag == null
                                ? "il modulo è spento, quindi nessuno"
                                : nametag.describe())
                        .also(currencies == null ? null : modules.configurazioneDi(Modules.CURRENCIES))
                        .also(modules.configurazioneDi(Modules.CUSTOMJOINITEMS))
                        .extra("JOINITEMS_NUMERO", joinItems == null
                                ? "il modulo è spento"
                                : String.valueOf(joinItems.itemCount()))
                        .also(modules.configurazioneDi(Modules.HUB_LOBBY))
                        .extra("LOBBY_SPAWN", hubLobby == null
                                ? "il modulo è spento"
                                : hubLobby.describe()))
                .intro("Raccoglie le utilità di base del server. Oggi ne fa sei: il **tablist**, cioè la lista giocatori che si apre col tasto Tab, la **MOTD**, le "
                        + "righe che si leggono nella lista server prima di entrare, il **nametag**, "
                        + "la targhetta sopra la testa dei giocatori, la **chat** pubblica (come si "
                        + "legge una riga, su ogni server della rete), il **filtro "
                        + "dell'autocompletamento**, che pulisce l'elenco dei comandi che il client "
                        + "suggerisce col TAB, e le **valute**, con cui lo staff crea da solo nuove "
                        + "monete di gioco (gemme, punti...) ciascuna col suo comando. A lungo andare "
                        + "dovrebbe assorbire ciò che oggi fa CMI.")

                .section("La chat",
                        "Su ogni server della rete la riga della chat pubblica la scrive questo plugin, col "
                                + "formato di **chat.yml**. Il formato lo sceglie da solo guardando quali plugin "
                                + "ci sono, come gli stili del nametag: con style: auto vince il primo stile i cui "
                                + "plugin (requires) sono tutti presenti. Su questo server: {{CHAT_STILE}}. "
                                + "Sul faction (c'è MagixFactions) esce lo stile factions, col tag [fazione] "
                                + "colorato secondo la relazione di chi LEGGE (verde la sua, magenta un'alleata, "
                                + "rosso le altre): ogni giocatore riceve la sua riga. Sull'hub esce plain: grado "
                                + "e nome. Per una modalità nuova basta aggiungere una voce in styles.",
                        "Un pezzo scritto fra [[ ]] sparisce da solo se tutto quello che contiene risulta "
                                + "vuoto: è così che chi non ha una fazione non si ritrova un [] davanti al nome. "
                                + "custom-format, se scritta, vince su tutto.",
                        "Passando il mouse su una riga si vedono ora e provenienza (gioco o sito). I link sono "
                                + "cliccabili per chi ha il permesso magixessentials.chat.links (vale anche il "
                                + "vecchio magixfactions.chat.links). I messaggi scritti nella chat del sito "
                                + "arrivano in gioco con lo stesso formato.",
                        "Chi fa cosa, in ordine: MagixGuard toglie i silenziati e filtra pubblicità, spam e "
                                + "insulti; MagixFactions porta via i messaggi dei canali fazione e alleati (/f "
                                + "chat, restano suoi); MagixBridge copia la chat pubblica sul sito; per ultimo "
                                + "questo modulo scrive la riga. La chat di **CMI** è spenta: con "
                                + "cmi.disable-module il modulo ne spegne formato, colori, filtri, menzioni e "
                                + "fumetti nei suoi file (vale dal riavvio dopo). I messaggi privati restano a CMI.")

                .section("I moduli: cosa è acceso e cosa no",
                        "Come in CMI, ogni funzione ha il suo interruttore in un file a parte: "
                                + "**plugins/MagixEssentials/modules.yml**. Lì si accende o si spegne una "
                                + "funzione INTERA (oggi ci sono **tablist**, **motd** e **nametag**, domani ce ne saranno "
                                + "altre). Le sue impostazioni stanno nel file che porta il suo nome — il tablist si "
                                + "regola in **tablist.yml** — e il **config.yml** tiene solo ciò che vale per "
                                + "il plugin intero.",
                        "La divisione serve a una domanda sola: «che cosa sta facendo il plugin adesso?». La "
                                + "risposta è un file lungo quanto le funzioni che esistono, non venti pagine di "
                                + "impostazioni da leggere per trovare un true. E per cambiare COME lo fa si apre "
                                + "il file di quella funzione: nessun file cresce all'infinito.",
                        "Spegnere una funzione non è buttarne via la configurazione: il suo file resta dov'e', "
                                + "intatto, e torna in uso appena la si riaccende. Dopo una "
                                + "modifica serve **/magixessentials reload** (o un riavvio): il reload risponde in "
                                + "chat con l'elenco dei moduli e com'e' andata, e lo stesso elenco finisce nel log "
                                + "a ogni avvio.",
                        "Una funzione nuova, aggiunta da un aggiornamento, compare qui da sola col suo valore di "
                                + "partenza, e col suo file di impostazioni accanto: ai file sul server ci pensa il "
                                + "plugin a ogni avvio.")

                .section("Chi comanda il tablist",
                        "Il tablist non ha un proprietario: **ce l'ha chi ha scritto per ultimo**. Se anche CMI lo "
                                + "gestisce, i due si sovrascrivono a vicenda e il risultato dipende dall'ordine, "
                                + "cioè dal caso. La via pulita resta spegnere il suo modulo "
                                + "(plugins/CMI/Settings/Modules.yml → **tablist: false**).",
                        "Finché resta acceso, **priority** (in tablist.yml) ci fa scrivere dopo di lui: ogni aggiornamento "
                                + "viene riscritto una seconda volta **{{cfg:priority.reassert-delay-ticks}}** "
                                + "tick più tardi, e l'aggancio al join è a priorità MONITOR, cioè dopo gli altri "
                                + "plugin. Se vedi ancora comparire per un istante il tablist di CMI, alza quel ritardo.",
                        "**Fin dove arriva la priorità:** intestazione, fondo e nomi. Le voci **FINTE** che un "
                                + "altro plugin inietta via pacchetto — le 80 slot di CMI, con la testa e le "
                                + "tacchette — quelle no: sono sue, e nessuna priorità le raggiunge. Per quelle "
                                + "l'unica via è spegnere il suo modulo. Le nostre slot fisse (sotto) fanno la "
                                + "stessa cosa ma **senza testa e senza tacchette**: se vedi ancora caselle con la "
                                + "faccia di Steve, stai guardando le sue, non le nostre.",
                        "All'avvio il plugin legge da solo il Modules.yml di CMI e mette un avviso nel log se il "
                                + "conflitto c'è, invece di lasciarti a indovinare perché il tab «torna come prima».",
                        "Intestazione (**header**) e fondo (**footer**) sono liste di righe nel config: una voce "
                                + "della lista, una riga a schermo. Il nome del giocatore nella lista si compone a "
                                + "parte con **player-name**, dove {name} è il suo nome.")

                .section("Colori, placeholder e il logo del server",
                        "Nelle righe valgono i codici colore **&** e **&#RRGGBB**, quindi anche "
                                + "%magixweb_namecolor% (il colore del grado, lo stesso che si vede sul sito) finisce "
                                + "dritto nel nome.",
                        "I **%placeholder%** li risolve PlaceholderAPI, che è softdepend: se PAPI non c'è il "
                                + "tablist funziona lo stesso, ma i %...% restano scritti così come sono. I "
                                + "placeholder per-giocatore (ping, fazione, coordinate) sono ricalcolati per "
                                + "ciascuno, non una volta sola per tutti.",
                        "Il segnaposto **{logo}** diventa il carattere del logo del server. Il logo NON è roba di "
                                + "questo plugin: è un glifo del resource pack di **MagixFactions**, e lì si "
                                + "regolano dimensione e altezza (tablist.logo.height / tablist.logo.ascent). Qui si "
                                + "decide solo dove metterlo — con le righe VUOTE dopo {logo} nell'header: il logo "
                                + "non è testo e la sua altezza non spinge giù le righe che seguono da sola, "
                                + "quindi poche righe vuote lo fanno finire SOPRA le informazioni invece che sopra "
                                + "di esse. Quante ce ne vogliono si calcola da height e ascent di MagixFactions — "
                                + "la formula è nel suo capitolo della guida — e va ricalcolato ogni volta che uno "
                                + "dei due cambia.")

                .section("L'avatar accanto al nome",
                        "Di serie **player-name** (in tablist.yml) comincia con **%magixpack_glyph_avatar%**, la "
                                + "faccia della skin del giocatore disegnata da MagixPack, seguita da "
                                + "%magixpack_shift_2%, 2 pixel d'aria prima del nome. La grandezza e la posizione "
                                + "si regolano in MagixPack (glyphs.yml → avatar: scale, offset-x, offset-y), non qui.",
                        "Si vede solo a chi ha il **resource pack di MagixPack**: senza, al posto della faccia "
                                + "restano dei quadratini. Se l'avatar non c'è e al suo posto si legge "
                                + "%magixpack_glyph_avatar%, manca PlaceholderAPI o MagixPack; se la riga usa i "
                                + "tag (<gradient>...), i colori dell'avatar vengono convertiti da soli.",
                        "Nome e intestazione si rimandano solo quando cambiano, più un ripasso al secondo: "
                                + "un update-interval-ticks basso fa scorrere le animazioni senza mandare a tutti "
                                + "lo stesso avatar a ogni tick.")

                .section("Quando si aggiorna",
                        "Ogni **{{cfg:update-interval-ticks}}** tick (20 tick = 1 secondo) e, in più, un "
                                + "tick dopo ogni ingresso — così mondo e coordinate sono già pronti e il nuovo "
                                + "arrivato non vede un tablist a metà.",
                        "Abbassare l'intervallo rende il ping più reattivo ma fa lavorare il server più spesso, "
                                + "una volta per giocatore online: sotto i 10 tick non serve a niente che si veda.")

                .section("Le 80 slot fisse",
                        "Il gioco decide da solo quante colonne disegnare in base a quante voci ci sono: con pochi "
                                + "giocatori il tab è una colonna sottile, con tanti si allarga. Se sotto ci deve "
                                + "stare una pergamena, quella misura non può ballare. Con **fixed-slots** (in tablist.yml) "
                                + "acceso il tab mostra sempre **{{cfg:fixed-slots.total}}** caselle, "
                                + "riempiendo con voci decorative quelle senza giocatore.",
                        "Le caselle vuote sono **senza testa** (portano una skin trasparente: un profilo senza "
                                + "texture non è invisibile, il gioco ci metterebbe ottanta teste di Steve) e "
                                + "**senza icona di connessione**: latenza **-1**, negativa apposta — nel protocollo "
                                + "vuol dire \"connessione non ancora nota\", che è esattamente cosa una casella "
                                + "finta È. Il client la disegna con l'icona \"connessione sconosciuta\" "
                                + "(ping_unknown.png), che il resource pack di MagixFactions sostituisce con una "
                                + "trasparente: è l'UNICA delle sei icone di ping che un giocatore vero non può mai "
                                + "avere per davvero, quindi l'unica spegnibile senza spegnere anche la barra di "
                                + "qualcun altro. Serve però che il client abbia scaricato quel resource pack: chi "
                                + "ha il permesso di bypassarlo vede ancora l'icona. Cosa c'è scritto dentro lo "
                                + "decide **empty-text**.",
                        "**La skin trasparente può marcire senza avvisare.** È un link a un file su Mojang, e se "
                                + "quel link smette di rispondere il client non dà NESSUN errore: mostra la skin di "
                                + "serie (Steve o Alex), silenziosamente. È successo davvero — l'hash di prima era "
                                + "morto da chissà quanto, e le caselle vuote hanno mostrato teste a caso per tutto "
                                + "quel tempo senza una riga nel log. Se ricapita, il sintomo è identico: teste "
                                + "visibili, log muto. Si verifica scaricando l'URL dentro TRANSPARENT_TEXTURE (è "
                                + "un base64, si decodifica in una riga) e controllando che risponda.",
                        "**Serve ProtocolLib**, perché una voce del tablist senza un giocatore vero dietro non "
                                + "esiste nell'API di Bukkit: va mandata al client come pacchetto. È l'unico punto "
                                + "di questo plugin che parla di pacchetti. Se ProtocolLib manca, o se la struttura "
                                + "del pacchetto non è quella attesa, la funzione **si spegne da sola** e resta il "
                                + "tablist dinamico: lo dice nel log. Un tab di misura variabile è un difetto "
                                + "estetico, un tab che sparisce è un guasto.",
                        "Oltre 80 non si va: il gioco disegna al massimo 4 colonne da 20, e il plugin taglia lì. "
                                + "E se i giocatori veri sono più del totale non si riempie niente — il tab è già "
                                + "pieno di gente vera, che è meglio.",
                        "**Le colonne sono larghe quanto un nickname vanilla può esserlo, non strette e non a "
                                + "tutto schermo.** Verificato decompilando PlayerTabOverlay.extractRenderState nel "
                                + "client vanilla reale di questa versione: il gioco sceglie UNA sola larghezza per "
                                + "TUTTE le colonne, quella del nome PIÙ LARGO fra le 80 voci (vere e finte insieme). "
                                + "Un nome finto corto (una casella vuota è quasi sempre solo uno spazio) terrebbe le "
                                + "colonne strette quanto il nome vero più corto in lista — non quanto un nome vero "
                                + "potrebbe davvero essere. Un nickname di Minecraft è lungo al massimo 16 caratteri, "
                                + "e nel font di gioco nessuna lettera/cifra/underscore valida in un nickname avanza "
                                + "più di 6 pixel (verificato decompilando BitmapProvider e rifacendo lo stesso "
                                + "calcolo sul vero ascii.png): il nickname vanilla più largo possibile è quindi "
                                + "16 x 6 = 96 pixel, mai di più. Il plugin aggiunge da solo 24 spazi invisibili in "
                                + "coda al testo di ogni casella vuota (FixedSlots.WIDTH_PADDING, 4 pixel l'uno = 96 "
                                + "in tutto): non si vedono, ma pareggiano esattamente quel massimo.",
                        "**Quello che non si può nascondere.** Dietro OGNI voce del tablist — vera o finta — il "
                                + "client disegna sempre un rettangolo semitrasparente largo quanto la colonna: non "
                                + "è una texture del resource pack, è scritto nel codice del client, quindi non "
                                + "dipende da niente che mandiamo nel pacchetto e non si può spegnere per le sole "
                                + "caselle finte senza spegnerlo anche per i giocatori veri. Il colore lo decide "
                                + "un'opzione DEL CLIENT di chi guarda (la stessa dello sfondo del testo in chat), non "
                                + "il server: chi lo vuole invisibile lo spegne da solo (Opzioni -> Chat -> "
                                + "Trasparenza sfondo chat a 0) — sparisce per tutte le voci, non solo per quelle "
                                + "finte.")

                .section("Sfumature e tag di MiniMessage",
                        "Intestazione, fondo e nome dei giocatori non leggono solo i codici **&**: passano dallo "
                                + "stesso motore di MOTD e nametag (util/TextFormat), quindi capiscono anche i tag di "
                                + "MiniMessage — <bold>, <color:#C046E8>, <gradient:#C046E8:#A8DC2C>Testo</gradient>, "
                                + "<rainbow>Testo</rainbow>. In una riga vale l'uno O l'altro: appena c'è un tag "
                                + "(un < seguito da una lettera) quella riga si legge come tag e le & restano scritte "
                                + "com'erano.",
                        "**Farle scorrere nel tempo.** <gradient:...> e <rainbow> accettano un ultimo numero, la "
                                + "fase: cambiandolo la sfumatura si muove invece di restare ferma. Il plugin lo "
                                + "calcola da solo — un giro ogni **{{cfg:animation-period-seconds}}** secondi — e lo "
                                + "mette al posto di due segnaposto, uno per tag perché i numeri che vogliono NON "
                                + "sono compatibili fra loro: {gradient-phase} (decimale) dentro <gradient:...:"
                                + "{gradient-phase}>, {rainbow-phase} (intero) dentro <rainbow:{rainbow-phase}>. "
                                + "Funzionano solo dentro quei due tag, scritti a mano dove servono — non c'è "
                                + "un'animazione automatica su tutto.",
                        "**Un giro sempre nello stesso verso, non un pendolo.** {gradient-phase} è un dente di sega "
                                + "da -1.0 a 1.0 che poi ricomincia da -1.0 — verificato non solo leggendo GradientTag "
                                + "ma facendo davvero disegnare a MiniMessage la sfumatura a fase -1.0 e a fase 1.0: "
                                + "il colore che esce è IDENTICO, quindi il punto di ripartenza è già continuo da "
                                + "solo, senza bisogno di andare avanti e indietro. Una versione precedente usava "
                                + "un'onda a triangolo (sale, poi scende) pensando di evitare un salto che in realtà "
                                + "non esisteva: il rimbalzo del triangolo era proprio quello che si vedeva come "
                                + "un'interruzione a ogni fine giro (segnalato dall'utente). {rainbow-phase} invece "
                                + "è già un intero che conta 0..9 e ricomincia: l'arcobaleno è un cerchio di "
                                + "tonalità, quindi il giro successivo è già il passo giusto, senza questo problema.",
                        "**Le caselle finte NON animano.** fixed-slots.empty-text diventa un profilo costruito UNA "
                                + "volta sola all'avvio (per non far lampeggiare il tab), non una riga ricalcolata a "
                                + "ogni giro: un gradiente ci sta, ma resta fermo. L'animazione vale solo per "
                                + "intestazione, fondo e nome — quelle si riscrivono davvero a ogni "
                                + "update-interval-ticks.",
                        "**Banda più stretta: <rainbow-xN> e <gradient-xN:colori>.** Un tag solo fa un giro largo "
                                + "quanto tutto il testo dentro; per ripeterlo (bande più strette) servirebbe "
                                + "spezzare il testo a mano in più tag identici, scomodo da scrivere (segnalato "
                                + "dall'utente). Scorciatoia: <rainbow-x3>Testo</rainbow-x3> oppure "
                                + "<gradient-x3:#C046E8:#A8DC2C>Testo</gradient-x3> — il plugin spezza \"Testo\" in "
                                + "altrettanti pezzi il più possibile uguali e genera da solo i tag veri, già con "
                                + "la fase dentro: non serve scrivere {gradient-phase}/{rainbow-phase} a mano in "
                                + "questo caso. Più alto il numero, più stretta la banda; oltre quante lettere ha "
                                + "il testo non ha effetto (il plugin non fa pezzi più piccoli di un carattere).",
                        "Un'animazione si vede scorrere solo se **update-interval-ticks** è più rapido di "
                                + "**animation-period-seconds**: abbassare la seconda sotto la prima non fa niente, "
                                + "l'aggiornamento resta il collo di bottiglia.",
                        "**Abbassare update-interval-ticks NON rimanda anche le 80 slot finte.** Sono due cadenze "
                                + "separate apposta: intestazione/fondo/nome seguono update-interval-ticks, le slot "
                                + "finte si rimandano al massimo una volta al secondo per conto loro, un valore fisso "
                                + "non legato al config. Prima erano la STESSA cosa: abbassare update-interval-ticks "
                                + "per un'animazione fluida rimandava anche il pacchetto ProtocolLib da 80 voci alla "
                                + "stessa velocità (10-20 volte al secondo per giocatore online) — la cosa più "
                                + "pesante di tutto questo modulo, ed era quello (non l'animazione in sé) a far "
                                + "scattare e bloccare il tablist. Segnalato dall'utente, corretto separando le due "
                                + "cadenze: ora un update-interval-ticks basso serve solo a far scorrere le "
                                + "animazioni, senza intasare le slot finte.")

                .section("L'ordine dei giocatori",
                        "Da solo il gioco mette avanti a tutto chi NON ha una squadra (scoreboard team) e poi "
                                + "ordina per nome — non per grado, e le caselle finte (senza squadra) finivano "
                                + "PRIMA dei giocatori veri quando questi ne avevano una. Con **sort-by-rank-weight** "
                                + "acceso (ora: **{{cfg:sort-by-rank-weight}}**) i giocatori VERI vengono messi "
                                + "davanti a tutto — caselle finte comprese, sempre in fondo — e ordinati fra loro "
                                + "dal **peso più alto al più basso** del loro gruppo LuckPerms: lo stesso peso "
                                + "che decide anche %magixweb_namecolor%, quindi chi ha il nome più in vista ha "
                                + "anche il posto più in vista.",
                        "Tecnicamente è il campo **Priority** del protocollo (Paper lo chiama "
                                + "*player list order*): vince SEMPRE, prima di ogni altro criterio del gioco "
                                + "(squadra, nome). Le caselle finte restano al valore di fabbrica e non vengono mai "
                                + "toccate: non serve fare niente perché restino in fondo.",
                        "**Richiede LuckPerms** (softdepend): se manca, la chiave non fa niente e resta l'ordine "
                                + "del gioco — lo dice nel log una volta all'avvio, non a ogni giro. Si aggiorna da "
                                + "solo quando cambia il grado di qualcuno (una promozione): si scrive un pacchetto "
                                + "nuovo solo se il numero è davvero cambiato da un giro all'altro.")

                .section("Il filtro dell'autocompletamento (tabcomplete)",
                        "Digitando **/** e premendo **TAB**, il client mostra un elenco di comandi da "
                                + "completare. Di suo il server lo compila con TUTTI i comandi registrati da OGNI "
                                + "plugin, permesso o no: uno staff member vede i propri comandi di sanzione, ma "
                                + "senza questo filtro li vedrebbe (e potrebbe completarli col TAB) anche chi non "
                                + "ha alcun permesso su di loro — non li potrebbe eseguire lo stesso, ma "
                                + "comparirebbero come se ci fossero.",
                        "Il modulo toglie dall'elenco, per ciascun giocatore, i comandi per cui non ha il "
                                + "permesso, di qualunque plugin del server (non solo dei Magix): l'esecuzione "
                                + "vera e propria non cambia in nulla, qui si pulisce solo il suggerimento. Non ha "
                                + "un file di impostazioni suo: l'interruttore in modules.yml è tutto quello che "
                                + "c'è da regolare.")

                .section("Le valute (currencies)",
                        "Una moneta di gioco creata dallo staff — gemme, punti, gettoni... — senza scrivere "
                                + "codice. Ogni voce di **currencies.yml** è una valuta, e la CHIAVE che le si dà "
                                + "è insieme l'ID, il nome del COMANDO che nasce da sola (**/<id>**) e il pezzo "
                                + "centrale dei suoi permessi: niente da dichiarare nel plugin.yml, il comando lo "
                                + "registra il plugin quando legge il config.",
                        "Ogni valuta porta con sé cinque sottocomandi: **add**, **take**, **set**, **reset** "
                                + "(per lo staff, permesso **magixessentials.currency.<id>.admin**, di serie solo "
                                + "operatori) e **give** (fra giocatori, permesso "
                                + "**magixessentials.currency.<id>.give**, di serie tutti). Scritto da solo (senza "
                                + "argomenti) il comando mostra il proprio saldo a chi lo scrive.",
                        "**Aggiungere o togliere una valuta vale subito con /magixessentials reload**, senza "
                                + "riavviare: il comando compare o sparisce davvero, anche dal TAB di chi è già "
                                + "online — non resta agganciato a vuoto.",
                        "**Locale o in rete.** Il server sta dietro Velocity con più backend (hub, factions...): "
                                + "ogni valuta sceglie da sé, con **shared** in currencies.yml, se il saldo è "
                                + "LOCALE a questo server (file **balances.yml**, un'economia per server — di "
                                + "fabbrica) o CONDIVISO su tutta la rete (stesso giocatore, stesso saldo ovunque, "
                                + "nel database descritto in **database** su config.yml). Una valuta condivisa va "
                                + "dichiarata con lo stesso ID su ogni server dove deve esistere.",
                        "Un ID scritto male (serve una parola di lettere minuscole, cifre e trattini bassi, che "
                                + "comincia per lettera) o già usato da un altro comando del server viene saltato, "
                                + "e il motivo finisce nel log all'avvio.",
                        "**I placeholder** (PlaceholderAPI) nascono da soli, uno per valuta: vedi "
                                + "%magixessentials_balance_<id>% e %magixessentials_name_<id>% più sotto. Per una "
                                + "valuta condivisa il saldo viene da una cache aggiornata in background (non blocca "
                                + "mai il server per una query al database), quindi può restare indietro di qualche "
                                + "secondo rispetto a un'operazione appena fatta su un altro server. Per farli "
                                + "funzionare anche sui server SENZA MagixEssentials, l'id della valuta va aggiunto "
                                + "a **bridge.player-placeholders** nel config di MagixBridge: lì si legge come "
                                + "%network_<server>_magixessentials_balance_<id>%.")

                .section("Gli oggetti fissi nell'inventario (customjoinitems)",
                        "Mette negli inventari dei giocatori gli oggetti che hai deciso tu — una bussola per "
                                + "scegliere il server, una stella nella barra rapida — e ti lascia scegliere cosa "
                                + "possono farci: spostarli, buttarli, usarli. Serve all'**hub** (inventario pulito, "
                                + "niente si tocca) e al **faction** (un solo oggetto fisso, il resto dell'inventario "
                                + "è dei giocatori). **Di serie è spento**: si accende con la riga customjoinitems "
                                + "in modules.yml, server per server. Adesso: {{JOINITEMS_NUMERO}} oggetti caricati.",
                        "Tutto sta in **customjoinitems.yml**: QUANDO e COME si danno, le regole generali e, "
                                + "nella sezione **items**, gli oggetti, una voce ciascuno: materiale, slot "
                                + "(0-8 barra rapida, 9-35 zaino, 36-39 armatura, 40 seconda mano), nome, descrizione, "
                                + "brillantezza, texture, permesso per riceverlo, comandi al clic. Gli oggetti si "
                                + "riconoscono da un marchio con l'id della voce, non dal nome: puoi rinominarli o "
                                + "cambiare materiale senza perderli di vista.",
                        "**Quando si danno:** al login (give-on.join, ora: **{{cfg:give-on.join}}**), alla "
                                + "rinascita (give-on.respawn, ora: **{{cfg:give-on.respawn}}**) e al cambio di mondo "
                                + "(give-on.world-change, ora: **{{cfg:give-on.world-change}}**). Se MagixAuth sta "
                                + "ancora chiedendo la password, si aspetta che il giocatore sia entrato davvero. Gli "
                                + "oggetti del modulo non cadono mai a terra quando si muore: tornano alla rinascita.",
                        "**Lo slot è occupato?** if-slot-occupied (ora: **{{cfg:if-slot-occupied}}**): *move* "
                                + "sposta la cosa del giocatore nel primo posto libero (e se lo zaino è pieno non "
                                + "dà l'oggetto: non si butta mai niente di suo), *replace* la sovrascrive, *keep* "
                                + "non dà l'oggetto. **clear-inventory** (ora: **{{cfg:clear-inventory}}**) svuota "
                                + "tutto prima di dare: è la scelta per l'hub, mai per il faction.",
                        "**Cosa possono farci, per oggetto:** *movable* (spostarlo nell'inventario, anche con "
                                + "i tasti numerici o la seconda mano), *droppable* (buttarlo), *vanilla-use* (usarlo "
                                + "come l'oggetto vero: mangiarlo, lanciarlo, piazzarlo). Di serie sono tutti e tre "
                                + "falsi: l'oggetto resta fisso nel suo slot e serve solo per i suoi comandi.",
                        "**Cosa possono fare in generale**, sezione **rules** di customjoinitems.yml: spostare "
                                + "oggetti, buttarli, raccoglierli, rompere e piazzare blocchi, scambiare le mani, "
                                + "consumare la durabilità. Ogni voce è *true* (si può, come in vanilla) o *false* "
                                + "(vietato) e vale per TUTTO, non solo per gli oggetti del modulo: sull'hub si "
                                + "spengono quasi tutte. Attenzione: **allow-move: false chiude ogni inventario**, "
                                + "casse e banchi da lavoro compresi. Chi ha il permesso "
                                + "**magixessentials.customjoinitems.bypass** (di serie gli operatori) salta tutte "
                                + "le regole, ma gli oggetti li riceve lo stesso.",
                        "**Le azioni al clic.** Ogni oggetto può avere una lista di azioni per clic: "
                                + "*left-click*, *right-click*, *shift-left-click*, *shift-right-click* (da "
                                + "accucciati; se mancano valgono quelle senza shift) e *commands* per qualunque "
                                + "clic (filtrata da *click*: any, left, right). Vince sempre la lista più precisa, "
                                + "mai due insieme. Con *run-in-inventory* le stesse azioni partono anche cliccando "
                                + "l'oggetto a inventario aperto.",
                        "Ogni azione ha un prefisso: *player:* (o niente) il giocatore esegue il comando coi "
                                + "suoi permessi, *console:* lo esegue la console, *server:* lo manda su un altro "
                                + "server della rete passando dal proxy (prima del login MagixProxy non lo fa "
                                + "passare), *message:* gli scrive in chat, *sound:* gli fa sentire un suono "
                                + "(nome, volume, tono). Segnaposto {player}, {uuid}, {world} e i placeholder di "
                                + "PlaceholderAPI. *cooldown-seconds* dice quanto aspettare fra un clic e l'altro.",
                        "**Dopo aver cambiato gli oggetti** basta /magixessentials reload per i comandi e le "
                                + "regole; per rimettere gli oggetti a chi è già online usa **/mess joinitems give** "
                                + "[giocatore|all], e **/mess joinitems remove** per toglierli (anche dopo aver "
                                + "spento il modulo: gli oggetti restano negli inventari finché non li togli). "
                                + "Una voce sbagliata (materiale o slot) si salta e il motivo finisce nel log.")

                .section("La lobby dell'hub (hub-lobby)",
                        "Le regole di un server che fa da **hub**, il punto di arrivo della rete. Si accende "
                                + "con la riga hub-lobby in modules.yml **solo sull'hub** (di serie è spento) e si "
                                + "regola in **hub-lobby.yml**, una sezione per funzione, ciascuna col suo "
                                + "interruttore enabled.",
                        "**Spawn a ogni ingresso** (spawn-on-join, ora: **{{cfg:spawn-on-join.enabled}}**): chi "
                                + "entra compare allo spawn, non dove era uscito, sia entrando nella rete sia "
                                + "arrivando da un'altra modalità. Adesso lo spawn è {{LOBBY_SPAWN}}. Con "
                                + "use-world-spawn: true è lo spawn del mondo, che si sposta in gioco con "
                                + "/setworldspawn (anche lo sguardo); con false vale il punto scritto nel file "
                                + "(x, y, z, yaw, pitch). Dopo una modifica al file basta /mess reload.",
                        "**Con MagixAuth:** il cancello del login resta dov'è; finito il login il giocatore "
                                + "viene portato allo spawn della lobby invece che alla sua vecchia posizione. "
                                + "Anche chi entra per la prima volta, dopo la registrazione, finisce allo spawn.")

                .section("La targhetta sopra la testa (nametag)",
                        "È quella che si legge **sopra la testa** dei giocatori, in gioco: non il tablist "
                                + "(quello del tasto Tab) e non il formato della chat. Si scrive tutto in "
                                + "**nametag.yml**, e l'interruttore — come per le altre funzioni — sta nel "
                                + "modules.yml.",
                        "**Le righe** stanno in **lines**, dall'alto verso il basso. L'ULTIMA è la riga del "
                                + "nome, quella che sta appena sopra la testa: è lì che va **{name}**. Le altre "
                                + "sono decorazioni che le stanno sopra. Valgono i codici **&** e **&#RRGGBB**, i "
                                + "tag (<bold>, <gradient:#C046E8:#A8DC2C>) e **tutti** i placeholder di "
                                + "PlaceholderAPI: quindi anche tutti quelli dei plugin Magix, che di PAPI sono "
                                + "espansioni — %magixfactions_faction%, %magixweb_namecolor%, %magixtime_*% — e "
                                + "quelli di chiunque altro.",
                        "**Una riga che non ha niente da dire sparisce.** Con **skip-empty-lines** acceso, una "
                                + "riga i cui segnaposto risolvono TUTTI a vuoto non viene disegnata: la riga "
                                + "della fazione non si vede sopra la testa di chi non ne ha nessuna, invece di "
                                + "lasciare appeso un «[]». Una riga senza segnaposto — una decorazione scritta a "
                                + "mano — si vede sempre, e la riga del nome non si salta mai.")

                .section("Una modalità, uno stile (e il network)",
                        "Lo stesso jar gira su server di modalità diverse, e una targhetta che parla "
                                + "di fazioni sarebbe sbagliata su tutti gli altri. Perciò di fabbrica "
                                + "**lines è vuota** e le righe le decide uno **stile**: il file ne porta un "
                                + "elenco, uno per modalità, e ogni stile dichiara in **requires** i plugin che "
                                + "gli servono.",
                        "Con **style: auto** si usa il primo stile dell'elenco i cui plugin ci sono TUTTI: è "
                                + "questa la rilevazione della modalità — non un indovinello sul nome del "
                                + "server, ma cosa c'è davvero installato. L'ordine conta (vince il primo che "
                                + "va bene) e l'ultimo non chiede niente, così una risposta c'è sempre. "
                                + "Scrivendo un nome invece di *auto* si impone quello stile, requisiti o no.",
                        "**Adesso, su questo server: {{NAMETAG_STILE}}.** La stessa riga la scrive nel log a "
                                + "ogni avvio: se sopra la testa non vedi quello che ti aspetti, la risposta è lì.",
                        "**Quello che scrivi in lines vince su tutto.** È il modo giusto di fare a modo proprio "
                                + "su UN server: gli stili servono a far partire bene un server nuovo, non a "
                                + "tenere insieme le decisioni di tutti.",
                        "**requires è una E, non una O.** Lo stile si usa solo se ci sono TUTTI i plugin "
                                + "elencati: [MagixFactions, BedWars] vuol dire «solo dove ci sono tutti e due "
                                + "insieme», e non serve a dire «fazioni oppure bedwars» — quelle sono due "
                                + "modalità, cioè due voci. E fra i requisiti vanno solo i plugin senza cui lo "
                                + "stile non ha senso, non tutti quelli che compaiono nei suoi segnaposto: MagixBridge "
                                + "(%magixweb_namecolor%) non ci va, perché se manca il nome si vede comunque, "
                                + "solo senza colore, mentre metterlo lì butterebbe via tutto lo stile — fazione "
                                + "compresa — per una questione di colore. I plugin si cercano fra quelli CARICATI "
                                + "e non fra quelli già accesi: l'ordine di accensione non è garantito, e uno "
                                + "stile scartato perché il suo plugin parte un istante dopo di noi sarebbe un "
                                + "guasto senza errore.",
                        "**Per una modalità nuova** (bedwars, per dirne una) si aggiunge una voce all'elenco "
                                + "styles, sopra quella senza requisiti: nome, i plugin che le servono, le sue "
                                + "righe. Gli stili sono un ELENCO e non delle chiavi, e la differenza conta: "
                                + "l'allineamento dei config toglie le chiavi che il jar non conosce, mentre le "
                                + "voci di un elenco le lascia stare. Quindi una modalità nuova non aspetta una "
                                + "versione del plugin. Il rovescio: uno stile aggiunto da un aggiornamento del "
                                + "plugin non compare da solo in un file che esiste già — su un server nuovo "
                                + "sì, perché il file nasce dal jar.",
                        "**E i segnaposto di un plugin che qui non c'è?** Restano **vuoti**, non scritti a "
                                + "schermo: %magixfactions_faction% su un server senza fazioni non diventa "
                                + "spazzatura sopra la testa della gente. E se la riga resta senza niente da "
                                + "leggere, con skip-empty-lines non viene nemmeno disegnata — cioè la "
                                + "decorazione di una modalità sparisce da sola dove quella modalità non "
                                + "esiste. Nel log si dice una volta per segnaposto.")

                .section("Le due maniere di disegnarla, e perché sono due",
                        "La targhetta **del gioco** è una riga sola. Il client la disegna da sé come "
                                + "prefisso + NOME VERO + suffisso, e il nome vero può avere solo i **16 colori "
                                + "storici**: nel protocollo la squadra porta un colore scelto fra quelli, un "
                                + "&#RRGGBB lì non esiste. In cambio costa quasi niente, sfuma con la distanza, "
                                + "sparisce da sola quando uno si accuccia — e **può essere diversa per chi "
                                + "guarda**.",
                        "Le righe **disegnate da noi** sono entità di testo agganciate al giocatore come un "
                                + "passeggero: più righe, colori esatti, sfumature anche sul nome, misura e "
                                + "altezza regolabili. Il nome del gioco viene nascosto, altrimenti si vedrebbe "
                                + "doppio. Una singola entità la vedono tutti uguale, ma per farla **diversa "
                                + "per chi guarda** (i %rel_) se ne disegna una per ogni testo, sullo stesso "
                                + "giocatore, nascondendo a ognuno quelle che non sono le sue — vedi la sezione "
                                + "apposta.",
                        "Lo decide **mode**: *vanilla* la targhetta del gioco, *display* le righe nostre, *auto* "
                                + "— quello che c'è adesso, **{{cfg:mode}}** — sceglie da sé: una riga sola la "
                                + "fa il gioco, da due in su la disegniamo noi. Con *vanilla* e più righe scritte, "
                                + "il gioco disegna l'ultima e le altre restano nel file: lo dice nel log all'avvio.",
                        "Le righe nostre si regolano nella sezione **display**: **height** quanto stanno in alto, "
                                + "**line-spacing** quanto sono distanti fra loro, **scale** quanto sono grandi, "
                                + "**background** lo sfondo dietro il testo (*default* il rettangolo scuro del "
                                + "gioco, *none* niente, oppure un #AARRGGBB), **view-range** da quanto lontano. "
                                + "Altezza e distanza si regolano guardando in gioco: sono blocchi, non pixel.",
                        "**see-through** decide i muri davanti, e di fabbrica è **vanilla**: come la targhetta del "
                                + "gioco, cioè ATTRAVERSO i muri da fermo e OCCLUSA dai blocchi appena il giocatore "
                                + "si accuccia (insieme alla sfumatura). Da fermo l'attraversamento evita anche un "
                                + "difetto delle entità di testo: con l'occlusione accesa, dietro a vetri, acqua o "
                                + "lava il testo \"sparirebbe\" per via del test di profondità. *true* le tiene "
                                + "sempre attraverso i muri, *false* sempre occluse (anche da fermo, col rischio "
                                + "detto dietro i blocchi trasparenti).")

                .section("Targhetta diversa per chi guarda",
                        "Il verde dell'alleato e il rosso del nemico non sono una proprietà del giocatore "
                                + "guardato: dipendono da **chi guarda**. In PlaceholderAPI sono i segnaposto "
                                + "**relazionali**, quelli che cominciano con %rel_ — da noi "
                                + "%rel_magixfactions_relation_color%, che è il colore della relazione fra le due "
                                + "fazioni ed è configurabile in MagixFactions.",
                        "**per-viewer** decide se usarli: *auto* (ora: **{{cfg:per-viewer}}**) si accende da sola "
                                + "se in lines c'è almeno un %rel_, *always* sempre, *never* mai. Funziona con "
                                + "**tutte e due** le modalità, per due strade diverse — su *never* invece un %rel_ "
                                + "vale come se il giocatore guardasse se stesso, e il log all'avvio te lo dice.",
                        "**In vanilla** il prezzo è una **lavagna** (scoreboard) per ciascuno, ed è la stessa su "
                                + "cui un altro plugin disegnerebbe il pannello laterale: se CMI tiene ancora il suo, "
                                + "i due se la strappano di mano — o per-viewer su *never*, o il pannello di CMI "
                                + "spento.",
                        "**In display** niente lavagne: si disegna un gruppo di entità di testo per ogni testo "
                                + "diverso, tutte sullo stesso giocatore, e a ognuno si nasconde quello che non è "
                                + "il suo. Chi vede lo stesso colore condivide un gruppo, quindi sono pochi finché "
                                + "i colori in gioco sono pochi. È così che due righe possono restare relazionali "
                                + "(tag e nome verdi per l'alleato, rossi per il nemico) senza tornare a una riga sola.")

                .section("Quando la targhetta non si vede",
                        "Chi è **invisibile** (pozione o /vanish dello staff) o chi è in **spettatore**: in "
                                + "entrambi i casi le righe nostre vengono tolte, perché un rettangolo di testo che "
                                + "galleggia da solo direbbe a tutti dov'e' chi non si dovrebbe vedere. Si regola con "
                                + "le due chiavi **display.hide-when-invisible**, **display.hide-in-spectator**. La "
                                + "targhetta del gioco queste cose le fa da sé, e quelle chiavi non la riguardano.",
                        "Chi si **accuccia** invece non sparisce: si sfuma, come fa la targhetta del gioco con la "
                                + "sua. **display.sneak-opacity** decide quanto (ora: **{{cfg:display.sneak-opacity}}**"
                                + "), da 1.0 (invariata) a 0.0 (invisibile).",
                        "**hide-self** nasconde a ciascuno la PROPRIA targhetta: in prima persona non si vedrebbe "
                                + "comunque, ma in terza si vedrebbe da dietro le spalle.",
                        "**disabled-worlds** sono i mondi in cui il modulo non tocca niente: lì resta la targhetta "
                                + "nuda del gioco. Serve per una lobby o un mondo-evento, dove le decorazioni danno "
                                + "solo fastidio.",
                        "**Non lascia niente in giro.** Le righe nascono col divieto di essere salvate nel mondo e "
                                + "con un marchio nostro: allo spegnimento del modulo si tolgono, e a ogni avvio si "
                                + "fa una passata a cercare quelle marchiate rimaste in piedi (un /reload a caldo, un "
                                + "crash) e si buttano, scrivendo nel log quante erano. Una targhetta orfana che "
                                + "galleggia in mezzo al mondo è il difetto peggiore di questo modo di fare le cose.")

                .section("Nametag e CMI",
                        "Anche la targhetta ce l'ha **chi scrive per ultimo**, come il tablist. Finché anche CMI "
                                + "la gestisce i due si sovrascrivono a vicenda e vince il caso. Qui però non ci "
                                + "limitiamo ad avvisare: con **cmi.disable-module** acceso, all'avvio spegniamo noi "
                                + "il suo modulo dei nametag nel suo **Settings/Modules.yml** — cambiando quella riga "
                                + "sola, con una copia di scorta del file accanto — e lo scriviamo nel log. Da lui "
                                + "quella riga si chiama **namePlates** (\"name plates\", non \"nametag\"), e il "
                                + "plugin ne cerca qualche grafia perché fra una versione e l'altra gli cambia: se "
                                + "nel log leggi che il suo modulo risulta **non leggibile**, vuol dire che nessuno "
                                + "dei nomi conosciuti è nel suo file, e va guardato a mano.",
                        "**Serve un riavvio** perché abbia effetto: quel file CMI lo legge all'avvio, quindi "
                                + "finché non riparte continua a scrivere anche lui. Se preferisci farlo a mano, "
                                + "spegni cmi.disable-module: in quel caso ci limitiamo all'avviso nel log, con la "
                                + "riga da cambiare.")

                .section("La MOTD della lista server",
                        "È quello che si legge nella lista server prima di entrare: **due righe** di testo "
                                + "(la terza il client non la disegna), l'icona, il numero dei giocatori e la "
                                + "**tendina** che esce passandoci sopra col mouse. Si scrive tutto in **motd.yml**.",
                        "Le MOTD stanno tutte in **messages**, una voce ciascuna. Quale si vede lo decide "
                                + "**selection**: *random* una a caso, *ordered* una dopo l'altra dalla prima "
                                + "all'ultima e poi daccapo, *fixed* sempre la prima (le altre restano lì, "
                                + "pronte). Adesso vale **{{cfg:selection}}**.",
                        "**Ogni quanto cambia:** con **change-every-seconds** a 0 cambia a ogni ping, cioè ogni "
                                + "volta che qualcuno apre la lista — vivace, ma chi tiene la lista aperta la vede "
                                + "ballare. Adesso è **{{cfg:change-every-seconds}}**: dentro quella finestra è "
                                + "la stessa per tutti. Con *random*, **avoid-repeat** impedisce che esca due volte "
                                + "di fila la stessa: su tre o quattro voci è la differenza fra «cambia» e «sembra "
                                + "rotto».",
                        "**Come si scrive una riga.** Due modi, uno O l'altro nella stessa riga: i **codici "
                                + "classici** (&a, &7, &l, e &#RRGGBB per l'esadecimale) oppure i **tag** "
                                + "(<bold>, <color:#C046E8>, <rainbow>, e soprattutto "
                                + "<gradient:#C046E8:#A8DC2C>TESTO</gradient> per le **sfumature**). Si riconoscono "
                                + "dai triangoli: se in una riga c'è un tag, quella riga viene letta come tag e le "
                                + "& restano scritte a schermo.",
                        "**Come si va a capo.** Con \\n dentro le virgolette doppie, oppure con un blocco "
                                + "«- |» e le righe sotto, che per le righe lunghe si legge molto meglio. Valgono "
                                + "tutti e due, e il risultato è lo stesso.",
                        "**Segnaposto:** {online}, {max} e {version}. Di segnaposto per-giocatore non ce ne sono e "
                                + "non possono essercene: al ping il server non sa CHI sta guardando, sa solo che "
                                + "qualcuno ha aperto la lista. Per lo stesso motivo qui PlaceholderAPI non c'entra.",
                        "**La tendina** (hover) prende il posto dell'elenco dei giocatori online. Lista vuota = "
                                + "resta l'elenco vero. Lì il gioco non accetta componenti ma nomi, quindi le righe "
                                + "vengono riscritte nei codici che il client capisce: funziona tutto, sfumature "
                                + "comprese, ma una sfumatura colora **una lettera alla volta** e fa righe "
                                + "lunghissime — tienila per una riga sola.",
                        "**Il numero dei giocatori.** **player-count.max** cambia il numero mostrato senza far "
                                + "entrare nessuno in più (-1 = quello vero). **player-count.extra** è il vecchio "
                                + "trucco del posto sempre libero: il massimo diventa online + quel numero, e il "
                                + "server non sembra mai pieno; se acceso vince su max. **player-count.hide** "
                                + "nasconde il conto: al suo posto il client disegna le due frecce rosse dei server "
                                + "irraggiungibili, e la tendina sparisce con lui.",
                        "**L'icona.** Di serie è server-icon.png del server. Con **icons** se ne possono mettere "
                                + "più d'una (PNG **64x64** nella cartella del plugin), e cambiano con la stessa "
                                + "regola delle MOTD. Un file che manca o che non è 64x64 viene saltato e il log "
                                + "dice quale: le altre continuano a funzionare.",
                        "**La versione.** **version.text** è il testo al posto del nome della versione, e si vede "
                                + "SOLO dai client non compatibili. **version.always-show** lo fa vedere a tutti, "
                                + "ma al prezzo di far apparire il server come non compatibile: il conto dei "
                                + "giocatori sparisce e la barra diventa rossa. Si entra lo stesso, ma spaventa — "
                                + "accendilo solo sapendo bene perché.",
                        "Questo modulo prende il posto del vecchio plugin **CustomMOTD**, che è stato tolto dal "
                                + "server: due plugin sulla stessa MOTD si sovrascrivono a vicenda, e vince chi "
                                + "scrive per ultimo.")

                .section("MOTD e Velocity (quando ci sarà il proxy)",
                        "La MOTD la scrive **chi risponde al ping**. Oggi risponde il server, perché il client "
                                + "ci parla diretto. Il giorno che davanti ci sarà **Velocity**, al ping "
                                + "risponderà il proxy: il server dietro non lo vedrà nemmeno, e questo modulo "
                                + "— che è un plugin del server — non potrà più farci niente.",
                        "Per quel giorno il plugin è già spaccato in due: **come si compone** la MOTD (scelta "
                                + "della variante, segnaposto, colori, le due righe) sta in una classe che non "
                                + "sa niente di Bukkit, e **chi ascolta il ping** è un file a parte di trenta "
                                + "righe. Sul proxy si riscrive solo il secondo, con lo stesso motd.yml e le "
                                + "stesse regole: la MOTD non va riscritta due volte né tenuta allineata a mano.")

                .commands()
                .permissions()
                .placeholders(CurrencyPlaceholders.DOCS)
                // Gli interruttori stanno in un file loro (modules.yml) e le impostazioni di ogni
                // funzione nel suo (tablist.yml): qui si vedono col valore che hanno adesso sul
                // server. Il config.yml non compare finche' non ha chiavi: sarebbe una tabella vuota.
                .settingsFrom(modules.configurazione(), "Moduli (modules.yml)",
                        "tablist", "Il tablist del tasto Tab. Spento, il tablist resta quello di CMI (o del gioco).",
                        "motd", "Le righe della lista server. Spento, vale la riga 'motd' di server.properties.",
                        "nametag", "La targhetta sopra la testa. Spento, resta quella di CMI (o il nome nudo del gioco).",
                        "tabcomplete", "Pulisce dal TAB i comandi senza permesso. Spento, il client suggerisce tutti i comandi registrati.",
                        "chat", "Il formato della chat pubblica e dei messaggi del sito. Spento, la chat pubblica resta quella nuda del gioco.",
                        "currencies", "I comandi delle valute (/magix, /gems...). Spento, quei comandi vengono tolti, anche dal TAB.",
                        "customjoinitems", "Gli oggetti fissi nell'inventario e le regole su cosa farci. Di serie spento: si accende sull'hub o sul faction dove serve.",
                        "hub-lobby", "Le regole della lobby dell'hub (per ora: a ogni ingresso si compare allo spawn). Di serie spento: si accende solo sull'hub.")

                .settingsFrom(modules.configurazioneDi(Modules.HUB_LOBBY), "Lobby dell'hub (hub-lobby.yml)",
                        "spawn-on-join.enabled", "A ogni ingresso si compare allo spawn, non dove si era usciti.",
                        "spawn-on-join.world", "Il mondo dello spawn. Vuoto = il mondo principale.",
                        "spawn-on-join.use-world-spawn", "true = lo spawn del mondo (/setworldspawn), false = il punto fisso qui sotto.",
                        "spawn-on-join.x", "Coordinata X del punto fisso.",
                        "spawn-on-join.y", "Coordinata Y del punto fisso.",
                        "spawn-on-join.z", "Coordinata Z del punto fisso.",
                        "spawn-on-join.yaw", "Dove guarda chi arriva al punto fisso (0 sud, 90 ovest, 180 nord, -90 est).",
                        "spawn-on-join.pitch", "Su/giù dello sguardo al punto fisso (0 dritto davanti).")

                .settingsFrom(modules.configurazioneDi(Modules.CUSTOMJOINITEMS), "Oggetti fissi (customjoinitems.yml)",
                        "worlds", "I mondi in cui il modulo lavora. Vuota = tutti.",
                        "give-on.join", "Dà gli oggetti al login (dopo MagixAuth, se c'è).",
                        "give-on.respawn", "Li rimette alla rinascita.",
                        "give-on.world-change", "Li rimette al cambio di mondo.",
                        "give-delay-ticks", "Quanti tick si aspetta dopo l'evento prima di darli.",
                        "wait-for-login", "Aspetta il login di MagixAuth prima di dare gli oggetti.",
                        "login-wait-seconds", "Al massimo quanti secondi si aspetta il login.",
                        "clear-inventory", "Svuota tutto l'inventario prima di dare gli oggetti (hub sì, faction no).",
                        "remove-orphans", "Toglie gli oggetti del modulo che non esistono più nella sezione items.",
                        "if-slot-occupied", "Slot occupato: move (sposta la cosa del giocatore), replace, keep.",
                        "rules.allow-move", "Spostare oggetti nell'inventario (false chiude TUTTI gli inventari).",
                        "rules.allow-drop", "Buttare oggetti a terra.",
                        "rules.allow-pickup", "Raccogliere oggetti da terra.",
                        "rules.allow-break-blocks", "Rompere blocchi.",
                        "rules.allow-place-blocks", "Piazzare blocchi.",
                        "rules.allow-swap-hands", "Scambiare gli oggetti fra le due mani.",
                        "rules.allow-item-damage", "Consumare la durabilità degli oggetti.")

                .settingsFrom(modules.configurazioneDi(Modules.CHAT), "Impostazioni della chat (chat.yml)",
                        "custom-format", "La riga scritta a mano per questo server: vince sugli stili. Vuota = decide lo stile.",
                        "style", "Quale stile: auto (il primo i cui plugin ci sono tutti) o il nome di uno.",
                        "styles", "Gli stili, uno per modalità: name, requires (i plugin che devono esserci), format. Pezzi fra [[ ]] facoltativi.",
                        "web-prefix", "Icona davanti ai messaggi scritti dal sito. Vuota = nessuna (lo dice il suggerimento).",
                        "tooltip.enabled", "Il suggerimento con ora e provenienza al passaggio del mouse.",
                        "tooltip.format", "Il testo del suggerimento: {ora} e {origine}.",
                        "tooltip.game", "Come si scrive «scritto in gioco» nel suggerimento.",
                        "tooltip.web", "Come si scrive «scritto dal sito» nel suggerimento.",
                        "cmi.disable-module", "Se all'avvio spegniamo noi i controlli della chat di CMI nei suoi file (serve un riavvio).")

                .settingsFrom(modules.configurazioneDi(Modules.TABLIST), "Impostazioni del tablist (tablist.yml)",
                        "update-interval-ticks", "Ogni quanti tick si riscrivono intestazione, fondo e nomi.",
                        "animation-period-seconds", "Durata di un giro completo di una sfumatura animata (gradient-phase/rainbow-phase).",
                        "player-name", "Come appare il nome nella lista: {name} è il nome, valgono colori e placeholder.",
                        "priority.enabled", "Riscrive una seconda volta per arrivare dopo CMI. Spegnila se il tablist è solo nostro.",
                        "priority.reassert-delay-ticks", "Quanti tick dopo arriva la seconda scrittura: alzalo se CMI si vede ancora per un istante.",
                        "sort-by-rank-weight", "Giocatori veri davanti a tutto, ordinati per peso del grado LuckPerms (più alto = più in alto).",
                        "fixed-slots.enabled", "Caselle fisse: il tab resta sempre della stessa misura. Serve ProtocolLib.",
                        "fixed-slots.total", "Quante caselle in tutto: il gioco ne disegna al massimo 80 (4 colonne x 20).",
                        "fixed-slots.empty-text", "Cosa c'è scritto in una casella vuota: uno spazio la lascia muta.")

                .settingsFrom(modules.configurazioneDi(Modules.MOTD), "Impostazioni della MOTD (motd.yml)",
                        "selection", "Quale MOTD si vede: random (a caso), ordered (una dopo l'altra), fixed (sempre la prima).",
                        "change-every-seconds", "Ogni quanti secondi cambia. 0 = a ogni ping, cioè a ogni apertura della lista.",
                        "avoid-repeat", "Con random: non esce due volte di fila la stessa. Ignorato dagli altri modi.",
                        "messages", "Le MOTD, una voce ciascuna. Due righe, \\n o blocco «- |». Vuota = vale server.properties.",
                        "hover", "Le righe della tendina sul numero giocatori. Vuota = resta l'elenco vero di chi è online.",
                        "player-count.max", "Il massimo mostrato accanto agli online. -1 = quello vero; alzarlo non fa entrare nessuno in più.",
                        "player-count.extra", "Massimo = online + questo: il server non sembra mai pieno. 0 = spento, e vince su max.",
                        "player-count.hide", "Nasconde il conto: il client disegna le frecce dei server irraggiungibili, e niente tendina.",
                        "version.text", "Il testo al posto del nome della versione. Vuoto = quello vero.",
                        "version.always-show", "Lo mostra a tutti, ma il server appare NON compatibile (barra rossa, niente conto).",
                        "icons.enabled", "Icone nostre al posto di server-icon.png, a rotazione come le MOTD.",
                        "icons.files", "I PNG 64x64 nella cartella del plugin. Uno sbagliato viene saltato e detto nel log.")

                .settingsFrom(modules.configurazioneDi(Modules.NAMETAG), "Impostazioni del nametag (nametag.yml)",
                        "update-interval-ticks", "Ogni quanti tick si ricontrollano le targhette. Si riscrive solo quello che cambia.",
                        "mode", "Chi disegna: vanilla (il gioco, una riga), display (noi, più righe), auto (una riga il gioco, due o più noi).",
                        "lines", "Le righe dall'alto in basso. L'ultima è quella del nome: mettici {name}. Vuota = decide lo stile.",
                        "style", "Quale stile quando lines è vuota: auto (il primo i cui plugin ci sono) o il nome di uno.",
                        "skip-empty-lines", "Una riga i cui segnaposto risolvono tutti a vuoto non si disegna. La riga del nome non si salta mai.",
                        "per-viewer", "Targhetta diversa per chi guarda (i %rel_...%): auto, always, never. Solo in modalità vanilla.",
                        "disabled-worlds", "I mondi in cui il modulo non tocca niente: lì resta la targhetta nuda del gioco.",
                        "vanilla.name-color", "Come si colora il nome VERO: auto = il più vicino fra i 16 colori, none = bianco.",
                        "display.height", "Quanto in alto sta la riga più bassa, in blocchi. Da regolare a occhio, in gioco.",
                        "display.line-spacing", "Distanza fra una riga e quella sopra, in blocchi.",
                        "display.scale", "Ingrandimento del testo: 1.0 è la misura di una targhetta normale.",
                        "display.see-through", "Se le righe si vedono attraverso i muri.",
                        "display.text-shadow", "L'ombra sotto le lettere. Con lo sfondo acceso sporca.",
                        "display.background", "Lo sfondo: default (il rettangolo del gioco), none (niente) o #AARRGGBB.",
                        "display.view-range", "Da quanto lontano si vedono, come moltiplicatore della distanza normale.",
                        "display.hide-self", "Nasconde a ciascuno la propria targhetta (in terza persona la vedrebbe da dietro).",
                        "display.hide-when-sneaking", "La toglie a chi si accuccia, come fa il gioco con la sua.",
                        "display.hide-when-invisible", "La toglie a chi è invisibile o in vanish: una riga sospesa direbbe dov'e'.",
                        "display.hide-in-spectator", "La toglie a chi è in spettatore.",
                        "cmi.disable-module", "Se all'avvio spegniamo noi il modulo nametag di CMI nel suo file (serve un riavvio).")

                .settingsFrom(modules.configurazioneDi(Modules.CURRENCIES), "Valute (currencies.yml)",
                        "currencies", "Una voce per valuta: la chiave è l'id (/<id>), name il nome mostrato, "
                                + "starting-balance il saldo di partenza, shared se il saldo è di rete.")

                .issue("Sull'hub i giocatori ricompaiono dove erano usciti",
                        "Controlla: hub-lobby è acceso nel modules.yml dell'hub (di serie è spento) e "
                                + "spawn-on-join.enabled è true in hub-lobby.yml? Il log all'avvio scrive "
                                + "[HubLobby] con lo spawn scelto. Se il mondo di spawn-on-join.world non esiste "
                                + "si usa il mondo principale, e il log lo dice.")
                .issue("Gli oggetti fissi non arrivano ai giocatori",
                        "Controlla in ordine: customjoinitems è acceso in modules.yml (di serie è spento)? "
                                + "Il mondo del giocatore è in worlds (vuota = tutti)? La sezione items ha almeno una voce, "
                                + "e il log all'avvio dice quanti oggetti ha caricato e quali ha saltato col motivo "
                                + "(materiale o slot sbagliati)? Se lo slot era occupato e lo zaino pieno, con "
                                + "if-slot-occupied: move l'oggetto non viene dato. Chi deve ancora fare il login "
                                + "li riceve solo dopo. Per rimetterli subito: /mess joinitems give <giocatore>.")
                .issue("Ho cambiato una chiave del config nel repo e sul server non succede niente",
                        "Il deploy porta il jar, non i config: il file nella cartella del plugin sul server non viene toccato, ed è quello che il plugin legge. Il valore nel jar vale solo per le chiavi che lì MANCANO. Quindi un valore già presente si cambia sul server (a mano, o col workflow deploy-plugin-config.yml), non nel repo. Del resto si occupa il plugin, a ogni avvio e a ogni reload: aggiunge le chiavi nuove al loro posto col loro commento, applica le rinomine portandosi dietro il valore che avevi scelto, e toglie le righe morte che il codice non legge più dai file a schema fisso, cioè tutti tranne i cataloghi (i menu e le sanzioni no: lì le voci in più sono tue). Prima di ogni modifica fa una copia del file in .bak/ (fuori da plugins/ sul server), col nome che finisce in .bak-<data>, e nel log scrive che cosa ha cambiato.")
                .issue("Le caselle vuote hanno una testa e le tacchette di connessione",
                        "Allora non sono le nostre, sono quelle di CMI: le nostre nascono senza testa e senza "
                                + "tacchette. Vuol dire che il suo modulo tablist è ancora acceso e sta riempiendo "
                                + "lui. Spegnilo (Modules.yml → tablist: false) e riavvia: il tab torna nostro.")
                .issue("Ho spento tablist nel modules.yml ma il tab si vede ancora",
                        "Quello che vedi non è più il nostro: col modulo spento questo plugin non scrive "
                                + "niente nel tablist, quindi resta quello di CMI (o, se anche il suo modulo è "
                                + "spento, la lista base del gioco). Controlla di aver fatto "
                                + "/magixessentials reload dopo la modifica: la risposta in chat elenca i moduli "
                                + "e dice se il tablist risulta spento.")
                .issue("Le slot fisse non compaiono",
                        "Nel log ci sono DUE righe, e dicono cose diverse. All'avvio «slot fisse **pronte**: N "
                                + "caselle» vuol dire solo che i profili finti esistono; «slot fisse **attive**: N "
                                + "caselle» arriva al primo invio andato a buon fine, ed è quella che conta. Se "
                                + "vedi solo la prima, il pacchetto non è partito e il motivo è scritto lì "
                                + "accanto (ProtocolLib assente, o un campo che in questa versione del gioco non "
                                + "c'è più). Se invece leggi «attive» ma a schermo non si vedono, il pacchetto "
                                + "è partito e il tablist lo sta riscrivendo qualcun altro: quasi sempre CMI col "
                                + "suo modulo tablist.")
                .issue("Le caselle vuote hanno di nuovo una testa (Steve o Alex), o si vede l'icona di connessione",
                        "Sono due difetti diversi, e nessuno dei due dà un errore nel log: non si notano da soli, "
                                + "si vedono solo guardando il tablist. La testa torna se il link della skin "
                                + "trasparente smette di rispondere (un file che sparisce da Mojang non lancia "
                                + "un'eccezione, fa solo apparire la skin di serie): si verifica scaricando l'URL "
                                + "dentro TRANSPARENT_TEXTURE. L'icona di connessione (\"?\" o una X, a seconda del "
                                + "client) torna se il resource pack di MagixFactions manca il file "
                                + "ping_unknown.png, o se il client di chi guarda non l'ha scaricato — un resource "
                                + "pack nuovo lo riprende solo un client che si ricollega dopo il riavvio del "
                                + "server, un reload a caldo non basta. NON è più un difetto di NO_PING: quel "
                                + "campo è -1 apposta, l'icona che ne esce è quella che il resource pack deve "
                                + "coprire.")
                .issue("Le colonne del tablist sono strette, o sfondano lo schermo",
                        "Il gioco sceglie UNA sola larghezza per tutte le colonne, quella del nome PIÙ LARGO fra le "
                                + "80 voci — vere e finte insieme (verificato decompilando PlayerTabOverlay nel client "
                                + "vanilla reale). Se le colonne sono strette, il nome più largo in tab è ancora "
                                + "corto: o WIDTH_PADDING è stato tolto o ridotto in FixedSlots.java, o fixed-slots "
                                + "è spento (niente voci finte a fare da nome largo, resta solo il nome vero più "
                                + "corto). Se invece le colonne sfondano quasi tutto lo schermo, WIDTH_PADDING è "
                                + "stato allargato oltre i 24 spazi (96 pixel, il nickname vanilla più lungo "
                                + "possibile): non serve andare oltre, e con troppo margine si arriva al tetto "
                                + "screenWidth - 50 di PlayerTabOverlay, colonne a tutto schermo comprese.")
                .issue("Dietro le caselle vuote si vede un rettangolo chiaro anche senza testa e senza icona",
                        "NON è un difetto di questo plugin, ed è verificato: dietro OGNI voce del tablist, vera o "
                                + "finta, il client disegna sempre un rettangolo semitrasparente largo quanto la "
                                + "colonna. È scritto nel codice del client (un fill(), non una texture), non "
                                + "dipende da niente che il pacchetto manda, quindi non esiste una chiave di config o "
                                + "un valore del pacchetto che lo spenga per le sole caselle finte senza spegnerlo "
                                + "anche per i giocatori veri. Il colore lo decide un'opzione DEL CLIENT di chi "
                                + "guarda (la stessa dello sfondo del testo in chat): solo LUI può spegnerla "
                                + "(Opzioni -> Chat -> Trasparenza sfondo chat a 0), e sparisce per tutte le voci, "
                                + "non solo per quelle finte. Non proporre una `fix` lato server: non esiste.")
                .issue("Il tablist scatta o si blocca con un'animazione (gradient/rainbow) attiva",
                        "Controlla PRIMA di tutto la versione: se update-interval-ticks basso rimandava anche le 80 "
                                + "slot finte alla stessa velocità (10-20 volte al secondo), quello era il colpevole "
                                + "vero, non l'animazione — un pacchetto ProtocolLib da 80 voci per ogni giocatore "
                                + "online, ripetuto così spesso, è la cosa più pesante di tutto questo modulo. "
                                + "Risolto separando le due cadenze: le slot finte si rimandano da sole al massimo "
                                + "una volta al secondo, qualunque sia update-interval-ticks. Se il problema resta "
                                + "DOPO questo fix, il sospetto si sposta altrove (un altro plugin che scrive nello "
                                + "stesso tick, TPS del server basso per altri motivi).")
                .issue("SbirTeo (o un altro giocatore) non è il primo nel tablist",
                        "Prima cosa da guardare: sort-by-rank-weight è acceso, e LuckPerms c'è davvero? Senza "
                                + "LuckPerms la chiave non fa niente (lo dice nel log all'avvio) e resta l'ordine "
                                + "del gioco, non quello per grado. Se LuckPerms c'è, il posto lo decide il PESO "
                                + "del gruppo, non il nome del gruppo né l'ordine in cui è scritto nel file di "
                                + "LuckPerms: un giocatore che sembra dovrebbe stare avanti ma non ci sta ha, "
                                + "probabilmente, un gruppo col peso più basso di quello che ti aspetti — si "
                                + "controlla con /lp group <nome> info.")
                .issue("Ho cambiato la MOTD e nella lista server si legge ancora quella vecchia",
                        "Il client si tiene in memoria l'ultima MOTD che ha visto: finché non ripinga, mostra "
                                + "quella. Togli il server dall'elenco e rimettilo, oppure aspetta. Se dopo un "
                                + "ping nuovo non è cambiata, allora è il file: hai fatto "
                                + "/magixessentials reload, e il modulo motd risulta acceso?")
                .issue("Nella MOTD si leggono i <triangoli> o le & invece dei colori",
                        "In una riga vale un formato solo. Se c'è anche un solo tag, tutta la riga viene letta "
                                + "come tag e le & restano scritte; al contrario, i tag in una riga senza triangoli "
                                + "non vengono cercati. Scegli: o &#RRGGBB o <color:#...>. E se un tag è scritto "
                                + "male — un colore che non esiste, una chiusura che manca — resta scritto così "
                                + "com'e': la MOTD non sparisce, ma quella riga te lo dice.")
                .issue("Le icone non cambiano (o non si vedono)",
                        "Devono essere PNG di **64x64** esatti, nella cartella plugins/MagixEssentials/, con "
                                + "icons.enabled acceso e il nome scritto in icons.files. Il log all'avvio dice "
                                + "quante ne ha caricate e quali ha saltato, col motivo. Si leggono una volta sola "
                                + "all'avvio: dopo aver aggiunto un file serve /magixessentials reload.")
                .issue("La MOTD non è quella di motd.yml ma una che non ho scritto io",
                        "Qualcun altro sta scrivendo sullo stesso ping. Il vecchio plugin CustomMOTD è stato "
                                + "tolto proprio per questo: se è tornato nella cartella dei plugin, toglilo di "
                                + "nuovo. Col modulo motd spento, invece, vale la riga 'motd' di server.properties.")
                .issue("Il plugin non c'è più e i nomi sopra la testa restano invisibili",
                        "Le squadre dello scoreboard il server le salva su disco, e in modalità display la "
                                + "targhetta del gioco viene nascosta proprio con una squadra. Spegnendosi come si "
                                + "deve il modulo disfa le sue (lo fa anche a ogni /magixessentials reload), ma dopo "
                                + "un crash possono restare lì. Si rimedia riaccendendo il modulo una volta, oppure "
                                + "a mano con /team modify <nome> nametagVisibility always.")
                .issue("Il prefisso della targhetta compare anche nei messaggi di morte",
                        "È il gioco, non un difetto: la targhetta in modalità vanilla si scrive con le squadre "
                                + "dello scoreboard, e il NOME VISUALIZZATO di un giocatore in squadra è "
                                + "prefisso + nome + suffisso — lo stesso che il gioco usa nei messaggi di morte. "
                                + "Se li vuoi puliti, la strada è mode: display: lì le righe sono nostre e il nome "
                                + "che il gioco conosce resta quello nudo.")
                .issue("Sopra la testa si vedono DUE targhette, una sopra l'altra",
                        "Una è nostra e l'altra è il nome del gioco che qualcuno ha rimesso visibile: succede "
                                + "quando un altro plugin (CMI, o un plugin di prefissi) riscrive le squadre dello "
                                + "scoreboard dopo di noi. Spegni il suo modulo dei nametag — cmi.disable-module lo "
                                + "fa da sé per CMI, ma serve un riavvio — e controlla che non ci sia un terzo "
                                + "plugin che scrive prefissi.")
                .issue("La targhetta è rimasta vecchia, o è sparita del tutto",
                        "Guarda il log all'avvio: il modulo dice se CMI gli sta scrivendo sopra e se nelle righe c'è "
                                + "un segnaposto che in quella modalità non può funzionare. Se hai cambiato "
                                + "nametag.yml, serve /magixessentials reload: la risposta in chat elenca i moduli e "
                                + "il loro stato. E ricorda che il deploy porta il jar, non i config.")
                .issue("Le righe stanno troppo in alto (o dentro la testa)",
                        "Sono display.height e display.line-spacing, e si misurano in blocchi: l'altezza si conta da "
                                + "dove siede un passeggero, cioè più o meno le spalle, non dai piedi. Si regola a "
                                + "occhio, un decimo alla volta, con /magixessentials reload dopo ogni prova.")
                .issue("Su un server di un'altra modalità la targhetta è solo il nome",
                        "È quello che deve succedere: nessuno stile dell'elenco ha trovato i suoi plugin, quindi "
                                + "vale l'ultimo, quello che non chiede niente. Per dare a quella modalità la sua "
                                + "targhetta: o si scrivono le righe in lines su quel server, o si aggiunge una voce "
                                + "a styles con i plugin che le servono. Il log all'avvio dice quale stile ha scelto.")
                .issue("Sopra la testa di chi non ha fazione resta appeso un «[]»",
                        "È skip-empty-lines spento: con quello acceso una riga i cui segnaposto risolvono tutti a "
                                + "vuoto non viene disegnata. Se invece la riga ha anche del testo FISSO oltre al "
                                + "segnaposto, quel testo conta come qualcosa da leggere: togli il testo fisso o "
                                + "spostalo su un'altra riga.")
                .issue("Il colore del nome non è quello giusto (modalità vanilla)",
                        "Il nome VERO nella targhetta del gioco può avere solo i 16 colori storici: %magixweb_namecolor% "
                                + "è un esadecimale, e il plugin usa il più vicino fra i 16. Per il colore esatto "
                                + "serve mode: display, dove il nome lo disegniamo noi.")
                .issue("Sono tutti dello stesso colore, invece di verde-alleato e rosso-nemico",
                        "I %rel_...% dipendono da chi guarda, e quello si può fare solo con la targhetta del gioco: "
                                + "con mode: display sono oggetti del mondo, uguali per tutti. Serve una riga sola "
                                + "(mode: vanilla) e per-viewer su auto o always. Il log all'avvio lo dice.")
                .issue("Dopo un /reload restano delle scritte che galleggiano in aria",
                        "Sono le nostre righe rimaste orfane: /reload a caldo non è un riavvio e lascia le entità "
                                + "dov'erano. Il plugin le ritrova dal marchio e le butta al primo avvio del modulo — "
                                + "basta /magixessentials reload, e nel log si legge quante ne ha tolte. In generale "
                                + "meglio riavviare che /reload.")
                .issue("Il tablist lampeggia o torna com'era",
                        "Lo sta riscrivendo anche CMI: spegni il suo modulo tablist "
                                + "(plugins/CMI/Settings/Modules.yml → tablist: false) e riavvia.")
                .issue("Nel tablist si legge %magixfactions_faction% invece della fazione",
                        "Manca PlaceholderAPI, oppure manca l'espansione di quel plugin: controlla che PAPI sia "
                                + "avviato e che il plugin che fornisce quel placeholder sia acceso.")
                .issue("Un giocatore continua a vedere col TAB un comando per cui non ha il permesso",
                        "Il client tiene in memoria l'elenco dei comandi ricevuto al login: un permesso tolto a "
                                + "caldo (plugin di permessi, /pex, LuckPerms) non lo aggiorna da solo. Un "
                                + "ri-login lo rifà pulito. Se invece manca anche dopo un ri-login, controlla che "
                                + "il modulo tabcomplete sia acceso in modules.yml e che il comando abbia davvero "
                                + "un permesso dichiarato: un comando senza permission (o con default: true) è "
                                + "visibile a chiunque, com'e' giusto che sia.")
                .issue("/magix (o un'altra valuta) non esiste, dice comando sconosciuto",
                        "O il modulo currencies è spento in modules.yml, o l'id non è valido (solo lettere "
                                + "minuscole, cifre e trattino basso, deve iniziare per lettera), o è già usato da "
                                + "un altro comando del server: in questi ultimi due casi il log all'avvio dice il "
                                + "motivo. Se il config è giusto, /magixessentials reload basta: non serve "
                                + "riavviare.")
                .issue("Le gemme guadagnate su un server non si vedono sull'altro",
                        "La valuta è \"shared: false\" (quella di fabbrica): ogni server tiene il proprio "
                                + "balances.yml, un'economia separata. Per un saldo uguale ovunque serve "
                                + "\"shared: true\" nella stessa voce su OGNI server dove la valuta esiste, con la "
                                + "sezione database di config.yml puntata allo stesso database su tutti.")
                .issue("Un comando di valuta risponde che il database non è raggiungibile",
                        "Vale solo per le valute \"shared: true\": controlla host/porta/utente/password in "
                                + "database (config.yml) e che quel database sia raggiungibile da questo server. Il "
                                + "log all'avvio dice l'errore esatto della connessione.")
                .issue("Al posto del logo c'è un quadratino bianco",
                        "Il giocatore non ha il resource pack di MagixFactions (rifiutato o non ancora scaricato). "
                                + "Il carattere del logo esiste solo dentro quel pacchetto.")
                .issue("Il logo copre le righe di informazioni",
                        "Non è un valore a caso: le righe vuote sotto {logo} servono (height - ascent) / 9 volte, "
                                + "9 pixel essendo l'altezza di una riga normale — height e ascent sono in "
                                + "MagixFactions (config.yml -> tablist.logo). Con quelli di ORA ne servono almeno "
                                + "10, e qui ce ne sono 11: se il conto è cambiato (height alzato, o ascent "
                                + "abbassato/reso più negativo) e la copertura è tornata, aggiungi righe fino a "
                                + "coprire il nuovo numero. In alternativa si abbassa tablist.logo.height in "
                                + "MagixFactions, che però rimpicciolisce anche il logo.")

                .never("Non rimettere CustomMOTD (o un altro plugin di MOTD) accanto a questo modulo: sulla "
                        + "stessa MOTD non si spartiscono il lavoro, vince chi scrive per ultimo e il risultato "
                        + "dipende dall'ordine di caricamento, cioè dal caso.")
                .never("Non mettere i segnaposto di una modalità nello stile senza requisiti (l'ultimo): "
                        + "quello vale su qualunque server, comprese le modalità dove quei placeholder non "
                        + "esistono. I requisiti sono il punto: dichiarali.")
                .never("Non lasciare acceso anche il modulo dei nametag di CMI: sulla stessa targhetta non si "
                        + "spartiscono il lavoro, e il giocatore finisce per vederne due. Se cmi.disable-module è "
                        + "acceso lo spegniamo noi, ma solo un riavvio lo rende vero.")
                .never("Non accendere per-viewer mentre un altro plugin disegna il pannello laterale: la lavagna "
                        + "(scoreboard) è una per giocatore, e la targhetta per spettatore se la prende tutta.")
                .never("Non cercare di far vedere il verde dell'alleato con mode: display. Un'entita' di testo è un "
                        + "oggetto del mondo: chi guarda non c'entra, e il colore che esce è sempre lo stesso.")
                .never("Non dichiarare la stessa valuta \"shared: true\" su due server puntando a database DIVERSI: "
                        + "sarebbero due saldi scollegati con lo stesso nome, non uno condiviso. Stesso id, stesso "
                        + "database, su ogni server dove quella valuta vive.")
                .never("Non lasciare acceso anche il tablist di CMI: due plugin sullo stesso tablist non si "
                        + "spartiscono il lavoro, se lo strappano di mano.")
                .never("Non scrivere i numeri del logo qui: dimensione e posizione stanno nel config di "
                        + "MagixFactions, che è anche il plugin che costruisce il resource pack.")
                .write();
    }
}
