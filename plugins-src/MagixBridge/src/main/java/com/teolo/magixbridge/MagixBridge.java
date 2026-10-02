package com.teolo.magixbridge;

import com.teolo.magixbridge.bridge.NetworkExpansion;
import com.teolo.magixbridge.bridge.PlaceholderBridge;
import com.teolo.magixbridge.util.ConfigAlign;
import com.teolo.magixbridge.chat.ChatBridge;
import com.teolo.magixbridge.cosmetics.HaloSync;
import com.teolo.magixbridge.db.Database;
import com.teolo.magixbridge.guide.GuideSync;
import com.teolo.magixbridge.util.StaffGuide;
import com.teolo.magixbridge.language.LanguageSync;
import com.teolo.magixbridge.language.SiteTranslationWorker;
import com.teolo.magixbridge.rank.RankPlaceholders;
import com.teolo.magixbridge.store.StoreDelivery;
import com.teolo.magixbridge.rank.RankSync;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class MagixBridge extends JavaPlugin {

    private Database database;
    /** This server's name in the network (network.server-name): faction, hub... */
    private String serverName;
    /**
     * The jobs that must run on ONE server of the network (network.site-jobs): store delivery,
     * site translation, the site's group list, the staff guide, the chat's maintenance. Done on
     * two servers, a purchase would be delivered twice.
     */
    private boolean siteJobs;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // I file di configurazione SUL SERVER allineati a quelli del jar: le chiavi nuove
        // compaiono da sole, al loro posto e col loro commento, senza toccare i valori
        // gia' scelti. Il deploy porta solo il jar, quindi senza questo il file del server
        // resterebbe indietro in silenzio (vedi util/ConfigAlign).
        ConfigAlign.alignAll(this);
        reloadConfig();
        database = new Database(this);
        serverName = getConfig().getString("network.server-name", "faction").trim().toLowerCase(java.util.Locale.ROOT);
        siteJobs = getConfig().getBoolean("network.site-jobs", true);
        getLogger().info("MagixBridge: server '" + serverName + "' della rete, lavori del sito "
                + (siteJobs ? "QUI." : "su un altro server."));

        // Neither /link nor two-factor lives here any more: MagixAuth owns both, and it is the
        // only thing deciding who gets into the game. Two plugins freezing the same player would
        // mean two timers, two releases, and neither one in charge.
        // /link in particular must NOT come back: on an offline-mode server, walking in under
        // someone else's name was enough to take over their account on the site.
        setupRankSync();
        setupLanguageSync();
        if (siteJobs) {
            setupSiteTranslation();
            setupStoreDelivery();
            setupGuideSync();
        }
        setupChatBridge();
        setupHaloSync();
        setupPlaceholderBridge();
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);

        getLogger().info("MagixBridge abilitato.");
    }

    /**
     * The administrators' guide: collects the chapters the other plugins leave in their own
     * folders and carries them to the site. It starts a few seconds late so every plugin has
     * already written its own, then checks again now and then, because a plugin reloaded on the
     * fly rewrites its file without the server restarting.
     */
    private void setupGuideSync() {
        if (!getConfig().getBoolean("guide.enabled", true)) {
            return;
        }
        GuideSync guide = new GuideSync(this, database);
        int minutes = Math.max(1, getConfig().getInt("guide.check-interval-minutes", 15));
        Bukkit.getScheduler().runTaskTimer(this, guide::sync, 200L, minutes * 60L * 20L);
        getLogger().info("MagixBridge: guida per amministratori attiva (controllo ogni " + minutes + " min).");
    }


    /**
     * The placeholders of one game mode readable on the others (bridge/PlaceholderBridge): this
     * server publishes the ones in bridge.*-placeholders it can compute, and reads everybody's
     * as %network_<server>_<placeholder>%.
     */
    private void setupPlaceholderBridge() {
        if (!getConfig().getBoolean("bridge.enabled", true)) {
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("MagixBridge: PlaceholderAPI assente, ponte dei placeholder spento.");
            return;
        }
        PlaceholderBridge bridge = new PlaceholderBridge(this, database, serverName,
                getConfig().getStringList("bridge.player-placeholders"),
                getConfig().getStringList("bridge.global-placeholders"));
        Bukkit.getPluginManager().registerEvents(bridge, this);
        new NetworkExpansion(bridge, getPluginMeta().getVersion()).register();

        int read = Math.max(2, getConfig().getInt("bridge.read-interval-seconds", 5));
        Bukkit.getScheduler().runTaskTimer(this, bridge::readTick, 60L, read * 20L);
        int publish = Math.max(5, getConfig().getInt("bridge.publish-interval-seconds", 10));
        // The first pass once every plugin has registered its placeholders.
        Bukkit.getScheduler().runTaskTimer(this, bridge::publishTick, 200L, publish * 20L);
        getLogger().info("MagixBridge: ponte dei placeholder attivo (%network_<server>_<placeholder>%, "
                + "pubblica ogni " + publish + "s, legge ogni " + read + "s).");
    }

    /** The VIP halo (MagixCosmetics) on the site's faces, like the top supporter's crown. */
    private void setupHaloSync() {
        int minutes = getConfig().getInt("halo.sync-interval-minutes", 2);
        if (minutes <= 0) {
            return;
        }
        HaloSync halo = new HaloSync(this, database);
        if (!halo.hook()) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(halo, this);
        // First pass after the other plugins are up (players already online get covered too).
        Bukkit.getScheduler().runTaskTimer(this, halo::syncAll, 200L, minutes * 60L * 20L);
        getLogger().info("MagixBridge: aureola sul sito attiva (giro ogni " + minutes + " min).");
    }

    /** MagixBridge's own chapter in the admin panel's guide (plugins-src/GUIDA-STAFF.md). */
    private void writeStaffGuide() {
        StaffGuide.create(this, "MagixBridge — il ponte con il sito", 40)
                // The numbers come from the real config: change a key and this chapter in the
                // admin panel follows along on its own (see util/ConfigValues).
                .values(new com.teolo.magixbridge.util.ConfigValues(this))
                .intro("Tiene insieme gioco e sito: gradi, chat live, consegna degli acquisti e la guida che "
                        + "stai leggendo. Non ha comandi in gioco: lavora da solo, in sottofondo. È l'unico "
                        + "plugin che conosce le credenziali del sito.")

                .section("I gradi",
                        "Quando LuckPerms cambia il gruppo o il prefisso di qualcuno, il sito lo sa subito: il "
                                + "plugin ascolta l'evento invece di aspettare il giro periodico. Il giro periodico "
                                + "resta come rete di sicurezza, e riallinea anche chi è online da prima.",
                        "Sul sito arrivano gruppo, prefisso completo con i colori, peso e colore del nome: sono "
                                + "quelli che fanno comparire il tag accanto al nickname nelle pagine e nella chat.")

                .section("La lingua",
                        "Ogni cambio di lingua (rilevazione GeoIP al primo ingresso, o /language set) arriva subito "
                                + "sul sito: MagixLanguage lancia un evento e MagixBridge lo scrive nella stessa riga "
                                + "mc_ranks del grado. Se MagixLanguage manca, la colonna resta vuota e il sito "
                                + "parla nella lingua di chi lo visita, non in quella del giocatore.",
                        "Come per i gradi, chi è già online quando MagixBridge riparte non genera un nuovo ingresso: "
                                + "un giro dopo l'avvio sincronizza anche loro.")

                .section("L'aureola sul sito",
                        "L'aureola VIP di MagixCosmetics compare anche sul sito, sopra la faccia del giocatore "
                                + "ovunque ci sia (forum, chat, classifiche, elenco utenti...), come la corona del "
                                + "miglior sostenitore. Stessa regola della statua in gioco: permesso, colore scelto, "
                                + "/halo off, anche per chi è offline (i permessi si leggono da LuckPerms).",
                        "Chi entra in gioco si aggiorna subito; per tutti gli altri c'è un giro ogni "
                                + "{{cfg:halo.sync-interval-minutes}} minuti, che scrive nel database solo chi ha "
                                + "ottenuto, perso o cambiato l'aureola. Il colore sta nella colonna halo_color di "
                                + "mc_ranks.")

                .section("La traduzione del sito",
                        "Il sito traduce da solo tutte le sue pagine, guide comprese, nella lingua di chi le "
                                + "visita: ogni pagina accoda in site_translations le frasi che incontra e non ha "
                                + "ancora, e questo plugin le smalta un lotto alla volta chiedendole a "
                                + "MagixLanguage. Nessuna traduzione avviene DURANTE il caricamento di una pagina: "
                                + "chi visita vede il testo italiano finché il lotto successivo non arriva, poi lo "
                                + "trova già pronto.",
                        "Stessa quota giornaliera di MyMemory dei messaggi di gioco (vedi il capitolo di "
                                + "MagixLanguage): tradurre molto testo del sito in un giorno lascia meno margine "
                                + "per le chiavi dei plugin, e viceversa.")

                .section("La chat live",
                        "La chat **PUBBLICA** del gioco si vede nella home, e quello che si scrive nella home "
                                + "ricompare in partita. Le chat di fazione e alleati non escono mai dal gioco: "
                                + "quella è una scelta, non una dimenticanza.",
                        "Nella home c'è una scheda per ogni server della rete (Hub, Factions...): ogni "
                                + "scheda è la chat pubblica di quel server, e un messaggio scritto in una scheda "
                                + "lo ripubblica in gioco solo quel server. Questo server è la scheda "
                                + "{{cfg:network.server-name}}. Le schede le elenca il sito (GAME_SERVERS in "
                                + "includes/helpers.php): una modalità nuova va aggiunta anche lì.",
                        "Quando il server è rimasto spento a lungo, i messaggi scritti sul sito nel frattempo non "
                                + "vengono riversati tutti in chat all'avvio: oltre una certa età si scartano, "
                                + "altrimenti chi entra troverebbe un muro di righe vecchie.")

                .section("La consegna degli acquisti",
                        "Quando PayPal conferma un pagamento, il sito accoda i comandi da eseguire e il server li "
                                + "esegue dalla console entro pochi secondi. Funziona anche se il giocatore è "
                                + "offline: i comandi partono lo stesso.",
                        "Se un comando fallisce, la coda ritenta alcune volte e poi si arrende, per non restare "
                                + "in un ciclo infinito. Lo stato di ogni consegna si vede nel gestionale, nella "
                                + "scheda Store.")

                .section("Su tutti i server della rete",
                        "MagixBridge (fino alla 0.12 si chiamava MagixWeb) gira su ogni modalità: faction, hub e "
                                + "quelle che verranno. Ognuna ha il suo network.server-name. I lavori che vanno "
                                + "fatti una volta sola (consegna degli acquisti, traduzione del sito, elenco dei "
                                + "gruppi, guida per amministratori, pulizia della chat) li fa solo il server con "
                                + "network.site-jobs: true, che è {{cfg:network.server-name}} su questo. Tutti gli altri "
                                + "fanno il resto: gradi e lingua di chi è lì, la propria chat pubblica verso il "
                                + "sito e i messaggi scritti nella propria scheda della chat del sito.",
                        "Il ponte dei placeholder porta i valori di una modalità sulle altre, passando dal "
                                + "database del sito: ogni server scrive chi ha online e calcola i placeholder "
                                + "elencati in bridge.player-placeholders (per i suoi giocatori e per quelli "
                                + "connessi altrove) e in bridge.global-placeholders (classifiche, totali). Gli "
                                + "altri li leggono come %network_<server>_<placeholder>%: sull'hub, dove "
                                + "MagixFactions non c'è, %network_faction_magixfactions_faction% è la fazione "
                                + "del giocatore. Un placeholder che un server non sa calcolare (il suo plugin lì "
                                + "non c'è) non viene pubblicato, quindi lo stesso elenco va bene ovunque.",
                        "I valori arrivano con qualche secondo di ritardo (bridge.publish-interval-seconds "
                                + "e bridge.read-interval-seconds). Chi non è connesso a quel server tiene "
                                + "l'ultimo valore che aveva, se il plugin che lo calcola non lo sa da offline.")

                .section("La guida per amministratori",
                        "Ogni plugin nostro scrive il proprio capitolo in plugins/<Nome>/guida-staff.html; "
                                + "MagixBridge passa a raccoglierli e li porta in questa pagina. Il primo giro parte "
                                + "una decina di secondi dopo l'avvio, poi si ripete ogni "
                                + "{{cfg:guide.check-interval-minutes}} minuti.",
                        "Se un capitolo non compare, il file sul disco dice da che parte sta il problema: se c'è, "
                                + "non è arrivato al sito; se non c'è, non l'ha scritto il plugin.")

                .commands()
                .permissions()
                .placeholders(both(com.teolo.magixbridge.rank.RankPlaceholders.DOCS, NetworkExpansion.DOCS))
                .settings(
                        "chat.enabled", "Spegne la chat live in home senza toccare il resto.",
                        "chat.mirror-game-chat", "Se la chat del gioco si vede sul sito.",
                        "chat.skip-older-than-minutes", "Oltre quanti minuti un messaggio del sito non viene più ripubblicato in gioco.",
                        "store.check-interval-seconds", "Ogni quanto il server guarda se ci sono acquisti da consegnare.",
                        "guide.check-interval-minutes", "Ogni quanto si rileggono i capitoli della guida.",
                        "network.server-name", "Il nome di questo server nella rete (faction, hub...): è quello "
                                + "che gli altri scrivono in %network_<server>_...%. Senza trattini bassi.",
                        "network.site-jobs", "true su UN solo server: consegna acquisti, traduzione del sito, "
                                + "gruppi, guida, pulizia della chat. Su due server un acquisto arriverebbe due volte.",
                        "bridge.player-placeholders", "I placeholder di ogni giocatore che questo server pubblica per gli altri.",
                        "bridge.global-placeholders", "I placeholder senza giocatore (classifiche, totali) che pubblica.")

                .issue("Ho cambiato una chiave del config nel repo e sul server non succede niente",
                        "Il deploy porta il jar, non i config: il file nella cartella del plugin sul server non viene toccato, ed è quello che il plugin legge. Il valore nel jar vale solo per le chiavi che lì MANCANO. Quindi un valore già presente si cambia sul server (a mano, o col workflow deploy-plugin-config.yml), non nel repo. Del resto si occupa il plugin, a ogni avvio e a ogni reload: aggiunge le chiavi nuove al loro posto col loro commento, applica le rinomine portandosi dietro il valore che avevi scelto, e toglie le righe morte che il codice non legge più dai file a schema fisso, cioè tutti tranne i cataloghi (i menu e le sanzioni no: lì le voci in più sono tue). Prima di ogni modifica fa una copia del file in .bak/ (fuori da plugins/ sul server), col nome che finisce in .bak-<data>, e nel log scrive che cosa ha cambiato.")
                .issue("Il sito resta in italiano anche per chi ha scelto un'altra lingua",
                        "Normale nei primi minuti dopo che una frase compare per la prima volta: viene "
                                + "accodata e tradotta al giro successivo (site-translation.check-interval-seconds), "
                                + "non sul momento. Se dura da ore, guarda /language status: se MyMemory è in pausa "
                                + "dopo un blocco (quota del giorno finita), le frasi aspettano in coda e ripartono "
                                + "da sole a fine pausa, senza perdere tentativi. Controlla anche che MagixLanguage sia "
                                + "presente e che translations.auto-translate.enabled sia acceso nel SUO config: "
                                + "senza, il lotto resta 'pending' per sempre. Una riga in site_translations con "
                                + "status 'failed' ha esaurito i tentativi (" + com.teolo.magixbridge.language.SiteTranslationWorker.MAX_ATTEMPTS
                                + ") con MyMemory disponibile: il servizio "
                                + "l'ha rifiutata o rovinata ogni volta. Fino alla 0.12.10 contava come tentativo "
                                + "anche un giro in pausa, e ogni frase finiva 'failed' in due minuti senza essere "
                                + "mai provata: la migrazione 2026-09-26-riprova-traduzioni-sito.sql le rimette in coda.")
                .issue("Sul sito la lingua di un giocatore è vecchia o mancante",
                        "Controlla che MagixLanguage sia installato e attivo: senza, MagixBridge lo scrive nel log "
                                + "all'avvio e non tenta nessuna sincronizzazione. Con MagixLanguage presente, un "
                                + "/language set o un nuovo ingresso in gioco aggiornano la colonna subito.")
                .issue("Sul sito i gradi sono vecchi",
                        "Il giro periodico li rimette in pari da solo. Se non succede, il database del sito non è "
                                + "raggiungibile: il log lo dice all'avvio.")
                .issue("Un acquisto pagato non è arrivato",
                        "Nel gestionale, in Store, si vede lo stato di ogni consegna e la si può rilanciare. Se "
                                + "il comando è sbagliato, correggilo nel pacchetto prima di ritentare.")
                .issue("La chat del sito non arriva in gioco (o viceversa)",
                        "Controlla che il modulo chat sia acceso. Se il server è appena ripartito, i messaggi "
                                + "vecchi vengono scartati apposta.")
                .issue("Sull'hub %network_faction_...% resta vuoto",
                        "Il faction lo pubblica solo se è nel SUO bridge.player-placeholders (o "
                                + "global-placeholders), scritto come si scriverebbe lì (es. magixfactions_faction, "
                                + "con o senza %). Dopo averlo aggiunto serve un reload del faction o un riavvio. "
                                + "Il valore arriva la prima volta che il faction lo calcola, cioè entro "
                                + "{{cfg:bridge.publish-interval-seconds}} secondi se il giocatore è connesso da "
                                + "qualche parte.")
                .issue("Un acquisto è arrivato due volte",
                        "Due server hanno network.site-jobs: true. Deve essere true su uno solo: il log di ogni "
                                + "server lo dice all'avvio (lavori del sito QUI / su un altro server).")
                .issue("La guida per amministratori è vuota",
                        "Nessun plugin ha ancora scritto il suo capitolo: succede finché il server non viene "
                                + "riavviato con le versioni che lo generano.")

                .never("Non spostare le credenziali del sito dentro un altro plugin: stanno qui per un motivo, "
                        + "così una falla altrove non arriva al database del sito.")
                .never("Non cancellare a mano righe dalla coda degli acquisti: un pagamento incassato resterebbe "
                        + "senza consegna e senza traccia.")
                .write();
    }

    /** The site's live chat: mirrors public chat onto the site, and speaks in game what is written there. */
    private void setupChatBridge() {
        if (!getConfig().getBoolean("chat.enabled", true)) {
            return;
        }

        ChatBridge bridge = new ChatBridge(
                this,
                database,
                getConfig().getBoolean("chat.mirror-game-chat", true),
                getConfig().getString("chat.game-format", "&b☁ &f{name}&7: &f{message}"),
                getConfig().getInt("chat.batch-size", 20),
                getConfig().getInt("chat.keep-hours", 48),
                serverName,
                siteJobs);

        Bukkit.getPluginManager().registerEvents(bridge, this);

        // What the site collected while the server was down must not all be dumped into chat.
        bridge.dropBacklog(getConfig().getInt("chat.skip-older-than-minutes", 5));

        int seconds = Math.max(1, getConfig().getInt("chat.check-interval-seconds", 2));
        Bukkit.getScheduler().runTaskTimer(this, bridge::deliverToGame, 100L, seconds * 20L);
        // Trim the history once an hour, the first pass a minute after startup.
        Bukkit.getScheduler().runTaskTimer(this, bridge::trimHistory, 1200L, 20L * 3600L);

        getLogger().info("MagixBridge: chat live del sito attiva (controllo ogni " + seconds + "s).");
    }

    /** Store delivery: runs the commands the site queues up after a confirmed payment. */
    private void setupStoreDelivery() {
        int seconds = Math.max(3, getConfig().getInt("store.check-interval-seconds", 10));
        int batchSize = getConfig().getInt("store.batch-size", 20);
        StoreDelivery delivery = new StoreDelivery(this, database, batchSize);

        long ticks = seconds * 20L;
        Bukkit.getScheduler().runTaskTimer(this, delivery::processQueue, 200L, ticks);
        getLogger().info("MagixBridge: consegna acquisti store attiva (ogni " + seconds + "s).");
    }

    /** Group tags on the site: syncs LuckPerms into the mc_ranks table, on join and on a timer. */
    private void setupRankSync() {
        RankSync rankSync = new RankSync(this, database);
        if (!rankSync.hook()) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(rankSync, this);
        rankSync.subscribeToChanges(); // update the moment a group or prefix changes

        int intervalMinutes = getConfig().getInt("ranks.sync-interval-minutes", 5);
        if (intervalMinutes > 0) {
            long ticks = intervalMinutes * 60L * 20L;
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                rankSync.syncOnlinePlayers();
                if (siteJobs) rankSync.syncGroups();
            }, ticks, ticks);
        }

        // After a plugin reload the players are already online, so no PlayerJoinEvent is coming.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (siteJobs) rankSync.syncGroups();
            rankSync.syncOnlinePlayers();
        }, 100L);

        // First join for people who already played: read it out of Bukkit's own data and fill in
        // the rows that were left empty. Off the main thread, since it is a database round trip.
        // Only on the site-jobs server: the first join on the network is the one there, not the
        // first time someone opened the hub.
        if (siteJobs) {
            Bukkit.getScheduler().runTaskAsynchronously(this, rankSync::backfillFirstJoins);
        }
        getLogger().info("MagixBridge: sincronizzazione gradi LuckPerms attiva.");

        // %magixweb_namecolor% for chat formats: the same name colour the site uses
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new RankPlaceholders(rankSync, getPluginMeta().getVersion()).register();
            getLogger().info("MagixBridge: placeholder %magixweb_namecolor% registrato.");
        }
    }

    /** La lingua di ogni giocatore sul sito: stesso schema di setupRankSync, per MagixLanguage. */
    private void setupLanguageSync() {
        LanguageSync languageSync = new LanguageSync(this, database);
        if (!languageSync.hook()) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(languageSync, this);

        // After a plugin reload the players are already online, so no PlayerJoinEvent is coming.
        Bukkit.getScheduler().runTaskLater(this, languageSync::syncOnlinePlayers, 100L);
        getLogger().info("MagixBridge: sincronizzazione lingua giocatori attiva.");
    }

    /** Traduzione automatica del sito: smaltisce le frasi che le pagine hanno accodato in attesa. */
    private void setupSiteTranslation() {
        if (!getConfig().getBoolean("site-translation.enabled", true)) {
            return;
        }
        int batchSize = getConfig().getInt("site-translation.batch-size", 25);
        SiteTranslationWorker worker = new SiteTranslationWorker(this, database, batchSize);
        if (!worker.hook()) {
            return;
        }
        int seconds = Math.max(10, getConfig().getInt("site-translation.check-interval-seconds", 30));
        long ticks = seconds * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, worker::run, 200L, ticks);
        getLogger().info("MagixBridge: traduzione automatica del sito attiva (ogni " + seconds + "s).");
    }

    private static String[] both(String[] a, String[] b) {
        String[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
    }
}
