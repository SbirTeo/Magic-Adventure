package com.teolo.magixweb;

import com.teolo.magixweb.util.ConfigAlign;
import com.teolo.magixweb.chat.ChatBridge;
import com.teolo.magixweb.db.Database;
import com.teolo.magixweb.guide.GuideSync;
import com.teolo.magixweb.util.StaffGuide;
import com.teolo.magixweb.rank.RankPlaceholders;
import com.teolo.magixweb.store.StoreDelivery;
import com.teolo.magixweb.rank.RankSync;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class MagixWeb extends JavaPlugin {

    private Database database;

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

        // Neither /link nor two-factor lives here any more: MagixAuth owns both, and it is the
        // only thing deciding who gets into the game. Two plugins freezing the same player would
        // mean two timers, two releases, and neither one in charge.
        // /link in particular must NOT come back: on an offline-mode server, walking in under
        // someone else's name was enough to take over their account on the site.
        setupRankSync();
        setupStoreDelivery();
        setupChatBridge();
        setupGuideSync();
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);

        getLogger().info("MagixWeb abilitato.");
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
        getLogger().info("MagixWeb: guida per amministratori attiva (controllo ogni " + minutes + " min).");
    }


    /** MagixWeb's own chapter in the admin panel's guide (plugins-src/GUIDA-STAFF.md). */
    private void writeStaffGuide() {
        StaffGuide.create(this, "MagixWeb — il ponte con il sito", 40)
                // The numbers come from the real config: change a key and this chapter in the
                // admin panel follows along on its own (see util/ConfigValues).
                .values(new com.teolo.magixweb.util.ConfigValues(this))
                .intro("Tiene insieme gioco e sito: gradi, chat live, consegna degli acquisti e la guida che "
                        + "stai leggendo. Non ha comandi in gioco: lavora da solo, in sottofondo. È l'unico "
                        + "plugin che conosce le credenziali del sito.")

                .section("I gradi",
                        "Quando LuckPerms cambia il gruppo o il prefisso di qualcuno, il sito lo sa subito: il "
                                + "plugin ascolta l'evento invece di aspettare il giro periodico. Il giro periodico "
                                + "resta come rete di sicurezza, e riallinea anche chi è online da prima.",
                        "Sul sito arrivano gruppo, prefisso completo con i colori, peso e colore del nome: sono "
                                + "quelli che fanno comparire il tag accanto al nickname nelle pagine e nella chat.")

                .section("La chat live",
                        "La chat **PUBBLICA** del gioco si vede nella home, e quello che si scrive nella home "
                                + "ricompare in partita. Le chat di fazione e alleati non escono mai dal gioco: "
                                + "quella è una scelta, non una dimenticanza.",
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

                .section("La guida per amministratori",
                        "Ogni plugin nostro scrive il proprio capitolo in plugins/<Nome>/guida-staff.html; "
                                + "MagixWeb passa a raccoglierli e li porta in questa pagina. Il primo giro parte "
                                + "una decina di secondi dopo l'avvio, poi si ripete ogni "
                                + "{{cfg:guide.check-interval-minutes}} minuti.",
                        "Se un capitolo non compare, il file sul disco dice da che parte sta il problema: se c'è, "
                                + "non è arrivato al sito; se non c'è, non l'ha scritto il plugin.")

                .commands()
                .permissions()
                .settings(
                        "chat.enabled", "Spegne la chat live in home senza toccare il resto.",
                        "chat.mirror-game-chat", "Se la chat del gioco si vede sul sito.",
                        "chat.skip-older-than-minutes", "Oltre quanti minuti un messaggio del sito non viene più ripubblicato in gioco.",
                        "store.check-interval-seconds", "Ogni quanto il server guarda se ci sono acquisti da consegnare.",
                        "guide.check-interval-minutes", "Ogni quanto si rileggono i capitoli della guida.")

                .issue("Ho cambiato una chiave del config nel repo e sul server non succede niente",
                        "Il deploy porta il jar, non i config: il file nella cartella del plugin sul server non viene toccato, ed e' quello che il plugin legge. Il valore nel jar vale solo per le chiavi che li' MANCANO. Quindi un valore gia' presente si cambia sul server (a mano, o col workflow deploy-plugin-config.yml), non nel repo. Del resto si occupa il plugin, a ogni avvio e a ogni reload: aggiunge le chiavi nuove al loro posto col loro commento, applica le rinomine portandosi dietro il valore che avevi scelto, e toglie le righe morte che il codice non legge piu' dai file a schema fisso, cioe' tutti tranne i cataloghi (i menu e le sanzioni no: li' le voci in piu' sono tue). Prima di ogni modifica fa una copia del file accanto all'originale, col nome che finisce in .bak-<data>, e nel log scrive che cosa ha cambiato.")
                .issue("Sul sito i gradi sono vecchi",
                        "Il giro periodico li rimette in pari da solo. Se non succede, il database del sito non è "
                                + "raggiungibile: il log lo dice all'avvio.")
                .issue("Un acquisto pagato non è arrivato",
                        "Nel gestionale, in Store, si vede lo stato di ogni consegna e la si può rilanciare. Se "
                                + "il comando è sbagliato, correggilo nel pacchetto prima di ritentare.")
                .issue("La chat del sito non arriva in gioco (o viceversa)",
                        "Controlla che il modulo chat sia acceso. Se il server è appena ripartito, i messaggi "
                                + "vecchi vengono scartati apposta.")
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
                getConfig().getInt("chat.keep-hours", 48));

        Bukkit.getPluginManager().registerEvents(bridge, this);

        // What the site collected while the server was down must not all be dumped into chat.
        bridge.dropBacklog(getConfig().getInt("chat.skip-older-than-minutes", 5));

        int seconds = Math.max(1, getConfig().getInt("chat.check-interval-seconds", 2));
        Bukkit.getScheduler().runTaskTimer(this, bridge::deliverToGame, 100L, seconds * 20L);
        // Trim the history once an hour, the first pass a minute after startup.
        Bukkit.getScheduler().runTaskTimer(this, bridge::trimHistory, 1200L, 20L * 3600L);

        getLogger().info("MagixWeb: chat live del sito attiva (controllo ogni " + seconds + "s).");
    }

    /** Store delivery: runs the commands the site queues up after a confirmed payment. */
    private void setupStoreDelivery() {
        int seconds = Math.max(3, getConfig().getInt("store.check-interval-seconds", 10));
        int batchSize = getConfig().getInt("store.batch-size", 20);
        StoreDelivery delivery = new StoreDelivery(this, database, batchSize);

        long ticks = seconds * 20L;
        Bukkit.getScheduler().runTaskTimer(this, delivery::processQueue, 200L, ticks);
        getLogger().info("MagixWeb: consegna acquisti store attiva (ogni " + seconds + "s).");
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
                rankSync.syncGroups();
            }, ticks, ticks);
        }

        // After a plugin reload the players are already online, so no PlayerJoinEvent is coming.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            rankSync.syncGroups();
            rankSync.syncOnlinePlayers();
        }, 100L);

        // First join for people who already played: read it out of Bukkit's own data and fill in
        // the rows that were left empty. Off the main thread, since it is a database round trip.
        Bukkit.getScheduler().runTaskAsynchronously(this, rankSync::backfillFirstJoins);
        getLogger().info("MagixWeb: sincronizzazione gradi LuckPerms attiva.");

        // %magixweb_namecolor% for chat formats: the same name colour the site uses
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new RankPlaceholders(rankSync, getPluginMeta().getVersion()).register();
            getLogger().info("MagixWeb: placeholder %magixweb_namecolor% registrato.");
        }
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
    }
}
