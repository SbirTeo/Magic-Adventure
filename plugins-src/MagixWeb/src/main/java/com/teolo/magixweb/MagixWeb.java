package com.teolo.magixweb;

import com.teolo.magixweb.chat.ChatBridge;
import com.teolo.magixweb.db.Database;
import com.teolo.magixweb.guida.GuidaSync;
import com.teolo.magixweb.util.GuidaStaff;
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
        database = new Database(this);

        // Ne' /link ne' la verifica in due passaggi stanno piu' qui: se ne occupa MagixAuth,
        // che e' il solo a decidere chi entra in partita. Averne due che congelano lo stesso
        // giocatore vorrebbe dire due cronometri, due sblocchi e nessuno dei due padrone.
        // /link in particolare NON va reintrodotto: su un server in offline mode bastava
        // entrare col nome di un altro per prendersi il suo account sul sito.
        setupRankSync();
        setupStoreDelivery();
        setupChatBridge();
        setupGuidaSync();
        Bukkit.getScheduler().runTaskAsynchronously(this, this::scriviGuidaStaff);

        getLogger().info("MagixWeb abilitato.");
    }

    /**
     * Guida per amministratori: raccoglie i capitoli che gli altri plugin lasciano nella
     * propria cartella e li porta sul sito. Parte con qualche secondo di ritardo, cosi' tutti
     * i plugin hanno gia' scritto il loro; poi ricontrolla ogni tanto, perche' un plugin
     * ricaricato a caldo riscrive il file senza che il server riparta.
     */
    private void setupGuidaSync() {
        if (!getConfig().getBoolean("guida.enabled", true)) {
            return;
        }
        GuidaSync guida = new GuidaSync(this, database);
        int minuti = Math.max(1, getConfig().getInt("guida.check-interval-minutes", 15));
        Bukkit.getScheduler().runTaskTimer(this, guida::sincronizza, 200L, minuti * 60L * 20L);
        getLogger().info("MagixWeb: guida per amministratori attiva (controllo ogni " + minuti + " min).");
    }


    /** Capitolo di MagixWeb nella guida del gestionale (plugins-src/GUIDA-STAFF.md). */
    private void scriviGuidaStaff() {
        GuidaStaff.crea(this, "MagixWeb — il ponte con il sito", 40)
                // Numeri presi dal config vero: cambiando una chiave, questo capitolo
                // sulla guida del gestionale cambia da solo (vedi util/ValoriConfig).
                .valori(new com.teolo.magixweb.util.ValoriConfig(this))
                .intro("Tiene insieme gioco e sito: gradi, chat live, consegna degli acquisti e la guida che "
                        + "stai leggendo. Non ha comandi in gioco: lavora da solo, in sottofondo. È l'unico "
                        + "plugin che conosce le credenziali del sito.")

                .sezione("I gradi",
                        "Quando LuckPerms cambia il gruppo o il prefisso di qualcuno, il sito lo sa subito: il "
                                + "plugin ascolta l'evento invece di aspettare il giro periodico. Il giro periodico "
                                + "resta come rete di sicurezza, e riallinea anche chi è online da prima.",
                        "Sul sito arrivano gruppo, prefisso completo con i colori, peso e colore del nome: sono "
                                + "quelli che fanno comparire il tag accanto al nickname nelle pagine e nella chat.")

                .sezione("La chat live",
                        "La chat **PUBBLICA** del gioco si vede nella home, e quello che si scrive nella home "
                                + "ricompare in partita. Le chat di fazione e alleati non escono mai dal gioco: "
                                + "quella è una scelta, non una dimenticanza.",
                        "Quando il server è rimasto spento a lungo, i messaggi scritti sul sito nel frattempo non "
                                + "vengono riversati tutti in chat all'avvio: oltre una certa età si scartano, "
                                + "altrimenti chi entra troverebbe un muro di righe vecchie.")

                .sezione("La consegna degli acquisti",
                        "Quando PayPal conferma un pagamento, il sito accoda i comandi da eseguire e il server li "
                                + "esegue dalla console entro pochi secondi. Funziona anche se il giocatore è "
                                + "offline: i comandi partono lo stesso.",
                        "Se un comando fallisce, la coda ritenta alcune volte e poi si arrende, per non restare "
                                + "in un ciclo infinito. Lo stato di ogni consegna si vede nel gestionale, nella "
                                + "scheda Store.")

                .sezione("La guida per amministratori",
                        "Ogni plugin nostro scrive il proprio capitolo in plugins/<Nome>/guida-staff.html; "
                                + "MagixWeb passa a raccoglierli e li porta in questa pagina. Il primo giro parte "
                                + "una decina di secondi dopo l'avvio, poi si ripete ogni "
                                + "{{cfg:guida.check-interval-minutes}} minuti.",
                        "Se un capitolo non compare, il file sul disco dice da che parte sta il problema: se c'è, "
                                + "non è arrivato al sito; se non c'è, non l'ha scritto il plugin.")

                .comandi()
                .permessi()
                .impostazioni(
                        "chat.enabled", "Spegne la chat live in home senza toccare il resto.",
                        "chat.mirror-game-chat", "Se la chat del gioco si vede sul sito.",
                        "chat.skip-older-than-minutes", "Oltre quanti minuti un messaggio del sito non viene più ripubblicato in gioco.",
                        "store.check-interval-seconds", "Ogni quanto il server guarda se ci sono acquisti da consegnare.",
                        "guida.check-interval-minutes", "Ogni quanto si rileggono i capitoli della guida.")

                .guasto("Sul sito i gradi sono vecchi",
                        "Il giro periodico li rimette in pari da solo. Se non succede, il database del sito non è "
                                + "raggiungibile: il log lo dice all'avvio.")
                .guasto("Un acquisto pagato non è arrivato",
                        "Nel gestionale, in Store, si vede lo stato di ogni consegna e la si può rilanciare. Se "
                                + "il comando è sbagliato, correggilo nel pacchetto prima di ritentare.")
                .guasto("La chat del sito non arriva in gioco (o viceversa)",
                        "Controlla che il modulo chat sia acceso. Se il server è appena ripartito, i messaggi "
                                + "vecchi vengono scartati apposta.")
                .guasto("La guida per amministratori è vuota",
                        "Nessun plugin ha ancora scritto il suo capitolo: succede finché il server non viene "
                                + "riavviato con le versioni che lo generano.")

                .mai("Non spostare le credenziali del sito dentro un altro plugin: stanno qui per un motivo, "
                        + "così una falla altrove non arriva al database del sito.")
                .mai("Non cancellare a mano righe dalla coda degli acquisti: un pagamento incassato resterebbe "
                        + "senza consegna e senza traccia.")
                .scrivi();
    }

    /** Chat live del sito: specchia la chat pubblica sul sito e ripubblica in gioco cio' che si scrive li'. */
    private void setupChatBridge() {
        if (!getConfig().getBoolean("chat.enabled", true)) {
            return;
        }

        ChatBridge ponte = new ChatBridge(
                this,
                database,
                getConfig().getBoolean("chat.mirror-game-chat", true),
                getConfig().getString("chat.game-format", "&b☁ &f{name}&7: &f{message}"),
                getConfig().getInt("chat.batch-size", 20),
                getConfig().getInt("chat.keep-hours", 48));

        Bukkit.getPluginManager().registerEvents(ponte, this);

        // Quello che il sito ha ricevuto mentre il server era spento non va riversato tutto in chat.
        ponte.scartaArretrati(getConfig().getInt("chat.skip-older-than-minutes", 5));

        int secondi = Math.max(1, getConfig().getInt("chat.check-interval-seconds", 2));
        Bukkit.getScheduler().runTaskTimer(this, ponte::consegnaAlGioco, 100L, secondi * 20L);
        // Pulizia dello storico una volta all'ora (la prima dopo un minuto dall'avvio).
        Bukkit.getScheduler().runTaskTimer(this, ponte::pulisciStorico, 1200L, 20L * 3600L);

        getLogger().info("MagixWeb: chat live del sito attiva (controllo ogni " + secondi + "s).");
    }

    /** Consegna degli acquisti: esegue i comandi che il sito accoda dopo un pagamento confermato. */
    private void setupStoreDelivery() {
        int secondi = Math.max(3, getConfig().getInt("store.check-interval-seconds", 10));
        int lotto = getConfig().getInt("store.batch-size", 20);
        StoreDelivery delivery = new StoreDelivery(this, database, lotto);

        long ticks = secondi * 20L;
        Bukkit.getScheduler().runTaskTimer(this, delivery::processaCoda, 200L, ticks);
        getLogger().info("MagixWeb: consegna acquisti store attiva (ogni " + secondi + "s).");
    }

    /** Tag dei gruppi sul sito: sincronizza LuckPerms -> tabella mc_ranks (al join + a intervalli). */
    private void setupRankSync() {
        RankSync rankSync = new RankSync(this, database);
        if (!rankSync.hook()) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(rankSync, this);
        rankSync.subscribeToChanges(); // aggiornamento immediato a ogni cambio di grado/prefisso

        int intervalMinutes = getConfig().getInt("ranks.sync-interval-minutes", 5);
        if (intervalMinutes > 0) {
            long ticks = intervalMinutes * 60L * 20L;
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                rankSync.syncOnlinePlayers();
                rankSync.syncGroups();
            }, ticks, ticks);
        }

        // Al reload/riavvio del plugin i giocatori sono gia' online: nessun PlayerJoinEvent in arrivo.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            rankSync.syncGroups();
            rankSync.syncOnlinePlayers();
        }, 100L);

        // Primo accesso di chi ha gia' giocato: si legge dai dati di Bukkit e si scrive nelle
        // righe rimaste vuote. Fuori dal thread principale, e' un giro sul database.
        Bukkit.getScheduler().runTaskAsynchronously(this, rankSync::recuperaPrimiAccessi);
        getLogger().info("MagixWeb: sincronizzazione gradi LuckPerms attiva.");

        // %magixweb_namecolor% per i formati di chat: stesso colore-nome che usa il sito
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
