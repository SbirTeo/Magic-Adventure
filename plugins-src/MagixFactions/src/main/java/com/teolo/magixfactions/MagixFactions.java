package com.teolo.magixfactions;

import com.teolo.magixfactions.chat.ChatListener;
import com.teolo.magixfactions.chat.ChatService;
import com.teolo.magixfactions.command.FCommand;
import com.teolo.magixfactions.config.Ranks;
import com.teolo.magixfactions.db.Database;
import com.teolo.magixfactions.db.DbExecutor;
import com.teolo.magixfactions.hook.Econ;
import com.teolo.magixfactions.hook.MagixPlaceholders;
import com.teolo.magixfactions.hook.Papi;
import com.teolo.magixfactions.lang.Messages;
import com.teolo.magixfactions.listener.PowerListener;
import com.teolo.magixfactions.manage.ClaimManager;
import com.teolo.magixfactions.manage.FactionManager;
import com.teolo.magixfactions.manage.DecayManager;
import com.teolo.magixfactions.manage.PowerManager;
import com.teolo.magixfactions.util.StaffGuide;
import com.teolo.magixfactions.util.DurationText;
import com.teolo.magixfactions.util.ConfigValues;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MagixFactions - plugin tipo Factions.
 * Modulo 1: fazioni, gradi configurabili, leader/successione, promote, chat, limite membri,
 * storage SQLite/MariaDB con migrazione. (GUI permessi e altri moduli in seguito.)
 */
public final class MagixFactions extends JavaPlugin {

    private Database database;
    private DbExecutor dbExecutor;
    private FactionManager factionManager;
    private PowerManager powerManager;
    private com.teolo.magixfactions.manage.ScoreManager scoreManager;
    private ChatService chatService;
    private com.teolo.magixfactions.resourcepack.ResourcePackService resourcePackService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();
        // Puro I/O su file, nessuna API Bukkit coinvolta: non deve bloccare il tick di avvio.
        // Il README nella cartella del plugin non si copia piu' dal jar: lo genera
        // StaffGuide insieme al capitolo per il sito, cosi' i due non possono divergere.
        // Capitolo della guida per amministratori sul sito (vedi plugins-src/GUIDA-STAFF.md).
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeTutorial);

        // Hook opzionali
        Econ.setup();
        Papi.setup();
        getLogger().info("Vault economia: " + (Econ.enabled() ? "attiva" : "non disponibile")
                + " | PlaceholderAPI: " + (Papi.enabled() ? "attivo" : "non disponibile"));

        // Database
        try {
            Database.Type type = Database.parseType(getConfig().getString("storage.type", "sqlite"));
            HikariDataSource ds = Database.buildDataSource(
                    type, getConfig().getConfigurationSection("storage"), getDataFolder().getAbsolutePath());
            database = new Database(type, ds);
            database.createSchema();
            getLogger().info("Storage attivo: " + type + " - schema pronto.");
        } catch (Exception e) {
            getLogger().severe("Errore inizializzazione database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Scritture di persistenza "specchio" (cache gia' aggiornata sul main thread) in un unico
        // thread seriale: non bloccano il tick e restano ordinate. Le query di lettura all'avvio
        // (loadAll) restano sincrone: servono prima che il plugin possa rispondere a eventi/comandi.
        dbExecutor = new DbExecutor(getLogger());

        // Gradi + manager + chat
        Ranks ranks = new Ranks();
        ranks.load(getConfig());
        factionManager = new FactionManager(this, database, dbExecutor, ranks);
        try {
            factionManager.loadAll();
        } catch (Exception e) {
            getLogger().severe("Errore caricamento fazioni: " + e.getMessage());
        }

        // Modulo 3: Potenza + Territori
        powerManager = new PowerManager(this, database, dbExecutor);
        ClaimManager claimManager = new ClaimManager(this, database, dbExecutor);
        try {
            powerManager.loadAll();
            claimManager.loadAll();
        } catch (Exception e) {
            getLogger().severe("Errore caricamento potenza/territori: " + e.getMessage());
        }
        factionManager.setClaimManager(claimManager);
        // Punteggio fazione + classifica (/f top): sintesi pesata di territori, membri, banca (giacenza
        // media), longevita' e potenza (media). Un campionatore periodico aggiorna le medie nel tempo e le
        // salva, cosi' un crash perde al massimo l'ultimo intervallo (vedi ScoreManager).
        scoreManager = new com.teolo.magixfactions.manage.ScoreManager(this, factionManager, powerManager, claimManager);
        long scoreInterval = 20L * scoreManager.sampleIntervalSeconds();
        Bukkit.getScheduler().runTaskTimer(this, scoreManager::sampleAll, scoreInterval, scoreInterval);
        getServer().getPluginManager().registerEvents(new PowerListener(powerManager), this);
        com.teolo.magixfactions.listener.TerritoryListener territory =
                new com.teolo.magixfactions.listener.TerritoryListener(this, factionManager, claimManager);
        getServer().getPluginManager().registerEvents(territory, this);
        // Riallinea periodicamente i titoli territorio: un chunk puo' cambiare proprietario/relazione
        // mentre un giocatore ci sta FERMO (claim/unclaim proprio, decadimento automatico, alleanza rotta),
        // senza un evento di movimento che aggiorni la cache — vedi Javadoc di TerritoryListener.
        long territoryInterval = 20L * Math.max(1, getConfig().getInt("territory-titles.recheck-interval-seconds", 1));
        Bukkit.getScheduler().runTaskTimer(this, territory::tick, territoryInterval, territoryInterval);
        // Accrescimento periodico della Potenza per i giocatori online. Il task gira a passo FITTO
        // (power.tick-seconds) e non piu' ogni gain-interval-seconds: la velocita' di recupero e'
        // personale (permesso magixfactions.power.speed.<%>), quindi ogni giocatore ha il suo conto e
        // serve una granularita' piu' fine dell'intervallo pieno. Lo stesso giro riallinea tetto di
        // Potenza e minimap ai permessi del momento.
        long interval = 20L * powerManager.tickSeconds();
        Bukkit.getScheduler().runTaskTimer(this, powerManager::tickOnline, interval, interval);
        // Gemello del giro qui sopra per i giocatori OFFLINE: riallinea il loro tetto ai permessi (letti
        // via LuckPerms, gli unici leggibili per chi non e' collegato) e applica il decadimento maturato,
        // cosi' la Potenza di una fazione di assenti cala in tempo reale invece che al loro prossimo
        // login. Il primo giro parte dopo 30s, per non pesare sull'avvio.
        com.teolo.magixfactions.hook.LuckPermsHook luckPerms = new com.teolo.magixfactions.hook.LuckPermsHook(this);
        luckPerms.setup();
        powerManager.setLuckPerms(luckPerms);
        long offlineInterval = 20L * 60L * powerManager.offlineRefreshMinutes();
        Bukkit.getScheduler().runTaskTimer(this, powerManager::tickOffline, 20L * 30L, offlineInterval);
        // Applica ai giocatori gia' online (es. dopo /reload)
        Bukkit.getOnlinePlayers().forEach(powerManager::onJoin);

        Messages messages = new Messages(this);
        powerManager.setMessages(messages); // avviso Potenza persa alla morte (solo al giocatore interessato)
        // Protezione territori: senza costruire/interagire nei chunk di ALTRE fazioni (proprio territorio
        // e terreno neutrale restano liberi). Staff con magixfactions.bypass/admin costruiscono ovunque.
        getServer().getPluginManager().registerEvents(
                new com.teolo.magixfactions.listener.ProtectionListener(this, factionManager, claimManager, messages), this);
        ChatService chat = new ChatService(this, factionManager, messages);
        this.chatService = chat; // usato da broadcastWebChat (API per MagixWeb)
        getServer().getPluginManager().registerEvents(new ChatListener(chat, messages), this);
        // Canale di chat nei metadata anche per chi e' gia' online (dopo un /reload nessun
        // PlayerJoinEvent arriva). Vedi ChatService.META_CHANNEL: lo legge MagixWeb.
        Bukkit.getOnlinePlayers().forEach(chat::publishChannelMeta);

        // Item Mappa Fazioni: servizio + listener che riaggancia il renderer dopo un riavvio
        com.teolo.magixfactions.map.MapService mapService =
                new com.teolo.magixfactions.map.MapService(this, factionManager, claimManager);
        powerManager.setMapService(mapService); // cosi' il refresh al login (onJoin) puo' aggiornare la mappa in mano
        getServer().getPluginManager().registerEvents(
                new com.teolo.magixfactions.listener.MapListener(this, mapService), this);
        Bukkit.getOnlinePlayers().forEach(mapService::reattachHands); // dopo /reload
        getServer().getPluginManager().registerEvents(
                new com.teolo.magixfactions.listener.TerrainChangeListener(mapService), this);

        // Autopilota pregen Chunky: pregen SOLO a server vuoto (pause al primo join, continue quando
        // esce l'ultimo, ripresa automatica dopo un riavvio a vuoto). Vedi ChunkyAutopilot.
        getServer().getPluginManager().registerEvents(
                new com.teolo.magixfactions.listener.ChunkyAutopilot(this), this);

        // Minimap HUD (sperimentale, riservata a chi ha il permesso magixfactions.minimap):
        // richiede ProtocolLib (softdepend), gestisce da sola l'assenza. Il listener rimonta la minimap
        // dopo teleport/cambio mondo/respawn (che resetterebbero i passeggeri lato client, facendola
        // sparire) e la ripulisce al quit.
        com.teolo.magixfactions.minimap.MinimapManager minimap =
                new com.teolo.magixfactions.minimap.MinimapManager(this, mapService, powerManager);
        getServer().getPluginManager().registerEvents(
                new com.teolo.magixfactions.minimap.MinimapListener(this, minimap), this);

        // Resource pack per lo shader HUD (v2 del piano minimap): server HTTP integrato, nessuna
        // dipendenza esterna. Se la configurazione manca (public-host vuoto) resta semplicemente
        // disabilitato — nessun errore bloccante, stessa filosofia di degradazione morbida gia' usata
        // per l'assenza di ProtocolLib.
        // Il listener lo rende OBBLIGATORIO (config map.minimap.resourcepack.required, default true):
        // lo invia a tutti al join ed espelle chi non lo carica, con messaggi configurabili.
        resourcePackService = new com.teolo.magixfactions.resourcepack.ResourcePackService(this);
        resourcePackService.start();
        getServer().getPluginManager().registerEvents(
                new com.teolo.magixfactions.resourcepack.ResourcePackListener(this, resourcePackService), this);

        // Collega la minimap a Potenza: chi ha il permesso magixfactions.minimap la riceve da solo al
        // login e dopo /reload (stesso principio gia' usato per l'item Mappa Fazioni sopra), e il giro
        // periodico di PowerManager.tickOnline la da'/toglie quando il permesso cambia a giocatore gia'
        // collegato.
        powerManager.setMinimapManager(minimap);
        powerManager.setResourcePack(resourcePackService);
        Bukkit.getOnlinePlayers().forEach(powerManager::reattachMinimap); // dopo /reload

        // Comando
        FCommand cmd = new FCommand(this, factionManager, ranks, chat, database, messages, powerManager, claimManager, scoreManager, mapService, minimap, resourcePackService);
        getCommand("magixfactions").setExecutor(cmd);
        getCommand("magixfactions").setTabCompleter(cmd); // suggerimenti contestuali filtrati sui permessi

        // Placeholder propri (registra l'espansione %magixfactions_...%)
        if (Papi.enabled()) {
            new MagixPlaceholders(this, factionManager, powerManager, claimManager, scoreManager).register();
            getLogger().info("Placeholder %magixfactions_...% registrati in PlaceholderAPI.");
        }

        // Modulo 3: decadimento territori per sovraccarico ("decay"; da non confondere con l'overclaim
        // di guerra, che e' la conquista di un chunk nemico in FCommand.claim). Avvisi + perdita graduale.
        try {
            DecayManager decay = new DecayManager(this, database, dbExecutor, factionManager, claimManager, powerManager, messages);
            decay.loadAll();
            factionManager.setDecayManager(decay); // timer parte esattamente al cambio membri
            long ov = 20L * decay.warnIntervalSeconds();
            Bukkit.getScheduler().runTaskTimer(this, decay::tick, ov, ov);
        } catch (Exception e) {
            getLogger().severe("Errore inizializzazione decadimento (decay): " + e.getMessage());
        }

        getLogger().info("MagixFactions abilitato.");
    }

    /**
     * API per altri plugin — la usa <b>MagixWeb</b> (chat live del sito) chiamandola via
     * reflection, cosi' i due plugin restano indipendenti e nessuno dei due smette di
     * funzionare se l'altro non c'e'.
     *
     * <p>Pubblica in chat un messaggio scritto dal SITO con lo STESSO formato della chat
     * pubblica (prefisso del grado, {@code [fazione]} e nome colorati con la relazione di chi
     * legge), preceduto dall'icona web di {@code chat.web-prefix}. Va chiamata dal main thread.
     *
     * @param prefix prefisso del grado gia' risolto da chi chiama (sostituisce
     *               {@code %luckperms_prefix%}): per un mittente offline PlaceholderAPI non
     *               riuscirebbe a risolverlo. Puo' essere vuoto/null.
     * @return false se la chat non e' ancora pronta (il chiamante puo' ripiegare sul suo formato)
     */
    public boolean broadcastWebChat(java.util.UUID senderUuid, String senderName, String message, String prefix) {
        if (chatService == null || senderUuid == null || senderName == null || message == null) {
            return false;
        }
        chatService.broadcastWeb(senderUuid, senderName, message, prefix);
        return true;
    }

    /** Variante senza prefisso, per chiamanti piu' vecchi. */
    public boolean broadcastWebChat(java.util.UUID senderUuid, String senderName, String message) {
        return broadcastWebChat(senderUuid, senderName, message, null);
    }

    @Override
    public void onDisable() {
        if (resourcePackService != null) resourcePackService.stop();
        if (powerManager != null) powerManager.saveAllOnline(); // accoda il salvataggio Potenza dei giocatori online
        // Ultimo campione delle medie prima di chiudere: le scritture vanno in coda al dbExecutor e
        // vengono flushate dallo shutdown ordinato piu' sotto, cosi' la giacenza/potenza media non perde
        // l'intervallo aperto dall'ultimo campionamento.
        if (scoreManager != null) scoreManager.sampleAll();
        // Le scritture sopra (e qualunque altra ancora in coda) sono asincrone: prima di chiudere il
        // pool Hikari bisogna aspettare che il thread seriale le abbia davvero eseguite, altrimenti si
        // perdono scritture pendenti o falliscono su una connessione gia' chiusa.
        if (dbExecutor != null) dbExecutor.shutdown();
        if (database != null) database.close();
    }

    /**
     * Rigenera (sovrascrive) il README.md nella cartella del plugin a ogni avvio,
     * cosi' la documentazione runtime resta sempre allineata alla versione del jar.
     */
    // ------------------------------------------------- GUIDA PER LO STAFF

    /**
     * Riscrive guida per lo staff e tutorial dei giocatori coi valori ATTUALI del config.
     * <p>
     * La chiama {@code /mf reload}: senza, cambiare un valore aggiornava il comportamento del server
     * ma le guide restavano ferme fino al riavvio successivo, cioe' esattamente il divario che i
     * segnaposto servono a evitare. Va in asincrono perche' e' solo I/O su file.
     */
    public void riscriviGuide() {
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeTutorial);
    }

    /** Capitolo di MagixFactions nella guida del gestionale (plugins-src/GUIDA-STAFF.md). */
    private void writeStaffGuide() {
        StaffGuide.create(this, "MagixFactions — fazioni, territori e potenza", 30)
                // Numeri presi dal config vero: se cambia una chiave, cambia anche questo capitolo sul
                // sito, senza che nessuno debba ricordarsi di riscriverlo.
                .values(guideValues())
                .intro("È la modalità del server: i giocatori si uniscono in fazioni, rivendicano terreno e "
                        + "se lo contendono. Il plugin più grosso che abbiamo, e quello su cui arriveranno "
                        + "quasi tutte le domande dei giocatori.")

                .section("La fazione",
                        "La crea chi scrive /f create: quel giocatore ne diventa il **LEADER**, e il leader è sempre "
                                + "uno solo. Il nome passa da un filtro di parole vietate e dalle regole di lunghezza e "
                                + "caratteri fissate nel config: un nome offensivo viene rifiutato subito, e se sfugge "
                                + "si rinomina d'ufficio.",
                        "I membri hanno un **GRADO**, e ogni grado porta con sé dei permessi interni: chi può "
                                + "rivendicare terreno, chi può invitare, chi può stringere alleanze. Il leader ha "
                                + "tutti i permessi. /f promote fa salire un membro di un gradino, ma mai oltre il "
                                + "gradino sotto il leader: al comando della fazione ci si arriva solo per cessione.",
                        "Il limite di membri parte dal valore nel config (adesso {{cfg:members.base}}) e si alza con i permessi VIP "
                                + "del **LEADER**: conta il suo, non quello dei membri. È la domanda che arriva sempre.")

                .section("Successione: cosa succede se il leader se ne va",
                        "Con /f transfer il comando passa a chi si sceglie, e il vecchio leader scende al grado "
                                + "più alto disponibile.",
                        "Se il leader esce con /f leave e ci sono altri membri, il comando passa DA **SOLO** al più "
                                + "alto in grado; a parità di grado, a chi è in quel grado da più tempo. Nessuna "
                                + "fazione resta senza guida per un abbandono.",
                        "Se invece il leader è rimasto solo e fa /f leave, la fazione si scioglie e i suoi "
                                + "territori tornano neutrali. È il caso in cui arriva la richiesta «rimettetemi la "
                                + "fazione»: ricrearla si può, ma il terreno è tornato libero per tutti.")

                .section("Potenza: il motore di tutto",
                        "Ogni giocatore ha una Potenza, che va dal negativo al positivo del tetto (adesso "
                                + "{{cfg:power.max}}). Si parte da {{cfg:power.start}} al primo ingresso, sale stando "
                                + "**ONLINE** (+{{cfg:power.gain-amount}} ogni {{secondi:power.gain-interval-seconds}}), "
                                + "scende alla morte (-{{cfg:power.death-loss}}) e {{PERDITA_OFFLINE_FRASE}}, senza mai "
                                + "andare sotto il negativo del tetto.",
                        "La perdita da assenza e' la domanda che arriverà più spesso: «sono tornato e avevo meno "
                                + "Potenza di quando sono uscito». È voluta, e cala davvero mentre il giocatore è via, "
                                + "non tutta in blocco al rientro — serve a impedire che una fazione di gente che non "
                                + "gioca più tenga terreno per sempre. Chi ha il permesso VIP la subisce più "
                                + "lentamente.",
                        "La Potenza della **FAZIONE** è la somma di quella dei membri, e il suo tetto la somma dei "
                                + "loro tetti. Da questi due numeri dipende quanto terreno può tenere e se è "
                                + "attaccabile. La regola che i giocatori devono capire è una sola: chi muore troppo "
                                + "perde terreno.",
                        "I vantaggi **VIP** sulla Potenza sono **PERMESSI**, non comandi: si danno al gruppo da "
                                + "LuckPerms e si tolgono quando l'abbonamento scade, senza lasciare valori appiccicati "
                                + "addosso al singolo giocatore. Sono due: magixfactions.power.powermax.<numero> alza il "
                                + "**TETTO** (powermax.20 = tetto 20), magixfactions.power.speed.<percentuale> alza la "
                                + "**VELOCITÀ** di recupero (speed.200 = doppia, cioè metà del tempo normale), "
                                + "magixfactions.power.speed.loss.<percentuale> la velocità con cui la Potenza si **PERDE** "
                                + "stando offline (loss.50 = ci mette il doppio a calare, loss.200 = cala il doppio, loss.0 = "
                                + "non cala mai). Fra più permessi dello stesso tipo vince il più **FAVOREVOLE** al giocatore: "
                                + "il numero più alto per tetto e recupero, il più basso per la perdita.",
                        "Il cambiamento si vede entro pochi secondi anche a giocatore già collegato: non serve farlo "
                                + "riconnettere. Chi vuole controllare se il suo VIP è attivo scrive /f power: a chi ha "
                                + "velocità diverse dal normale compaiono righe in più con le percentuali.",
                        "Vale anche per chi è **OFFLINE**: ogni {{cfg:power.offline-refresh-minutes}} minuti il plugin "
                                + "ripassa i giocatori scollegati, "
                                + "rilegge i loro permessi da LuckPerms e aggiorna tetto e Potenza. Due conseguenze da "
                                + "sapere quando ti chiedono spiegazioni: un VIP che scade su un account che non rientra "
                                + "più smette davvero di contare (prima avrebbe regalato tetto alla sua fazione per "
                                + "sempre), e la Potenza di una fazione di assenti cala **mentre sono via** — quindi la "
                                + "fazione diventa raidabile nel momento giusto, non al loro prossimo login.",
                        "Ogni giocatore ha un **CONTATORE** personale del recupero, e viene salvato: chi si "
                                + "scollega a sette minuti dai dieci riprende da sette, non da zero. Quindi la risposta "
                                + "a «sono uscito un attimo e ho perso il progresso» è no, non si perde. E il tempo non "
                                + "dipende più dal momento in cui ti colleghi: prima chi entrava un istante prima dello "
                                + "scoccare del conteggio si prendeva il punto in regalo.",
                        "Le manopole sono in config.yml, sezione power: gain-interval-seconds è il tempo base, "
                                + "tick-seconds ogni quanto si conta l'avanzamento (e si rileggono i permessi di chi è "
                                + "collegato), offline-refresh-minutes ogni quanto si ripassano i giocatori assenti, "
                                + "offline-decay quanta Potenza si perde stando via — amount è quanta, per è ogni "
                                + "quanto e accetta **hour, day, week, month**. Ogni chiave ha il suo commento sopra: "
                                + "quello è il posto dove guardare, non questa guida.",
                        "Se tocchi offline-decay a server acceso basta /mf reload, e il conto riparte dall'ultimo "
                                + "periodo già maturato: nessuno viene addebitato due volte e la Potenza già persa non "
                                + "torna indietro. Due avvertenze: se **ACCORCI** il periodo, chi è via da tanto può "
                                + "incassare più perdite tutte insieme al primo giro; e se scrivi in per un valore che "
                                + "non è fra quei quattro, il plugin usa il giorno e te lo scrive in console. E ricordati "
                                + "che il numero compare anche nel tutorial dei giocatori: se cambi la regola, va "
                                + "cambiato anche lì.",
                        "La Potenza **ATTUALE** di un singolo si corregge con /mf admin setpower (utile dopo una morte "
                                + "ingiusta). Il tetto invece non si tocca più da comando: se qualcuno chiede "
                                + "/mf admin setpowermax sta guardando una guida vecchia, ora è il permesso.")

                .section("Territori: quando si può rivendicare",
                        "/f claim prende il chunk in cui ti trovi. Su terreno **NEUTRALE** servono due condizioni "
                                + "insieme: la fazione deve tenere meno territori del suo tetto (una percentuale del "
                                + "maxpower, adesso il {{percento:claims.max-percent}}) e la sua Potenza attuale deve "
                                + "superare i territori già posseduti.",
                        "Su terreno **NEMICO** — la conquista di guerra — ne servono altre due: la fazione avversaria "
                                + "dev'essere raidabile, cioè avere Potenza inferiore ai propri territori, e il chunk "
                                + "dev'essere fra i più esterni. Non si conquista dal centro: si mangia dai bordi.",
                        "/f unclaim libera il chunk dove sei. /f unclaimall li libera **TUTTI** ed è irreversibile, "
                                + "quindi il primo tentativo mostra solo un avviso rosso con suono di pericolo: per "
                                + "farlo davvero il comando va ripetuto entro pochi secondi.")

                .section("Decadimento: il terreno che si perde da solo",
                        "Da non confondere con la conquista, e i giocatori le confondono. Il **DECADIMENTO** è quando "
                                + "una fazione tiene più terreno di quanto la sua Potenza regga — succede tipicamente "
                                + "quando un membro se ne va e il tetto scende. La **CONQUISTA** è quando è un'altra "
                                + "fazione a portarti via un chunk.",
                        "In sovraccarico i membri online ricevono un titolo con suono di pericolo, ripetuto. Passato "
                                + "il periodo di grazia (adesso {{ore:decay.grace-hours}}) la fazione perde un territorio ogni "
                                + "{{ore:decay.loss-interval-hours}} finché non rientra nel tetto. Si perdono sempre i chunk più **LONTANI** dalla home, "
                                + "restringendo verso il centro; il chunk della home non si perde mai.",
                        "Il rimedio per il giocatore è alzare la Potenza della fazione: far entrare qualcuno, o "
                                + "smettere di morire. Il conto alla rovescia sopravvive ai riavvii: non si azzera "
                                + "spegnendo il server.")

                .section("Protezioni dentro i territori",
                        "Dentro un claim gli estranei non rompono e non piazzano blocchi, non aprono contenitori e "
                                + "non innescano quello che il config protegge. Fuori dai claim vale il survival puro: "
                                + "lì il grief è parte del gioco e non è una violazione del regolamento.",
                        "È la distinzione da tenere a mente quando arriva una segnalazione: «mi hanno rubato» "
                                + "dentro un claim è un problema di permessi di grado o di fiducia mal riposta; fuori "
                                + "dal claim è semplicemente il gioco.")

                .section("Relazioni fra fazioni",
                        "Esistono due sole relazioni, **NEMICO** e **ALLEATO**, e di partenza ogni fazione è nemica di "
                                + "tutte le altre: nel database si registrano solo le alleanze.",
                        "L'alleanza si forma solo se **ENTRAMBE** le fazioni fanno /f ally sull'altra. /f enemy è "
                                + "immediato e serve a tre cose: sciogliere un'alleanza (basta una delle due parti), "
                                + "annullare una richiesta mandata, rifiutare una ricevuta.")

                .section("Chat",
                        "/f chat gira fra tre canali: pubblica, fazione, alleati. Il canale fazione resta dentro la "
                                + "fazione, quello alleati arriva anche alle fazioni alleate. Nessuno dei due esce "
                                + "verso il sito: nella chat live della home passa solo la chat pubblica.",
                        "Il nome del giocatore prende il colore del suo **GRADO**; il tag della fazione prende il colore "
                                + "della **RELAZIONE** con chi legge. Sono due colori diversi perché dicono due cose "
                                + "diverse, e non vanno uniformati.")

                .section("Punteggio e classifica",
                        "/f top ordina le fazioni per un **PUNTEGGIO** unico, non per un solo numero: una classifica "
                                + "basata solo sui territori (o solo sui soldi) premierebbe chi eccelle in una cosa sola. "
                                + "Il punteggio è la sintesi di cinque caratteristiche, con un metodo **RELATIVO**.",
                        "In ogni caratteristica la fazione col valore più **ALTO** vale 1, le altre valgono in "
                                + "proporzione a lei (proprio valore ÷ valore del migliore, quindi fra 0 e 1). Poi ogni "
                                + "frazione si moltiplica per il **PESO** della voce (score.weights): col peso 1 vale al "
                                + "massimo 1, col peso 2 il doppio. Il punteggio è la **SOMMA** dei contributi; il massimo "
                                + "possibile è la somma dei pesi. Un peso 0 esclude la voce.",
                        "Conseguenza da spiegare ai giocatori: il punteggio è **RELATIVO**, cioè può cambiare anche se una "
                                + "fazione non fa nulla, solo perché un'altra ha alzato il record di una voce. È voluto: "
                                + "misura quanto sei vicino al **MIGLIORE** del momento, non un valore assoluto.",
                        "Territori, membri e longevità (giorni dalla creazione) contano col valore attuale. Banca e "
                                + "potenza contano di default con la loro **MEDIA NEL TEMPO** (giacenza media, non il saldo "
                                + "di un attimo: un deposito lampo non scala la classifica) — con score.bank-value / "
                                + "score.power-value a `current` si usa invece il valore attuale. La media si campiona ogni "
                                + "{{secondi:score.sample-interval-seconds}} e si salva sul database; per le fazioni **già "
                                + "esistenti** parte dall'aggiornamento del plugin (niente storico passato), quindi "
                                + "all'inizio riflette il presente e si assesta col tempo. Il punteggio compare anche in "
                                + "/f info e sul sito, dagli stessi numeri.")

                .section("Mappa e minimap",
                        // Come risponde /f map lo dice il config: la frase cambia da sola con map.mode, cosi'
                        // questo capitolo non puo' descrivere una modalita' che non e' piu' quella in uso.
                        "/f map disegna i territori con i colori della relazione. "
                                + "{{se:map.mode=chat}}Ora risponde in **CHAT**: un quadrato di "
                                + "{{cfg:map.chat.rows}} caselle per lato, una lettera per fazione e la legenda "
                                + "sotto. La mappa-ITEM da tenere in mano c'è ancora e si riaccende con "
                                + "map.mode: item.{{/se}}"
                                + "{{se:map.mode=item}}Ora risponde con l'**ITEM** mappa da tenere in mano, che si "
                                + "ricentra mentre il giocatore cammina. La versione testuale in chat si "
                                + "rimette con map.mode: chat.{{/se}} "
                                + "La minimap a schermo è un extra **VIP** e spetta a chi ha il permesso "
                                + "magixfactions.minimap. Anche qui niente comando: dato il permesso la minimap compare "
                                + "da sola entro pochi secondi, tolto il permesso sparisce.",
                        "Mappa e minimap mostrano le **STESSE** cose, perché sono alimentate dallo stesso codice: se "
                                + "una mostra qualcosa e l'altra no, è un difetto, non una scelta.",
                        "I giocatori in vanish e quelli con la pozione di invisibilità non compaiono su nessuna "
                                + "delle due: sarebbe un modo troppo comodo per trovare chi non vuole essere trovato.")

                .section("Pacchetto risorse",
                        "È obbligatorio: senza, la mappa e diversi elementi grafici non si vedono. Chi lo rifiuta "
                                + "viene espulso con un messaggio che glielo spiega. Il permesso "
                                + "magixfactions.resourcepack.bypass serve a chi deve entrare senza — prove, riprese, "
                                + "ospiti di passaggio.")

                .detailedCommands()
                .commands()
                .permissions()
                .settings(
                        "power.max", "Tetto di Potenza di un giocatore.",
                        "power.death-loss", "Quanta Potenza si perde morendo.",
                        "power.gain-interval-seconds", "Ogni quanti secondi online si guadagna Potenza.",
                        "claims.max-percent", "Percentuale del maxpower che diventa tetto dei territori.",
                        "score.weights", "Peso (= massimo) di ogni caratteristica nel punteggio: col peso 1 vale al "
                                + "massimo 1, alzalo per farla contare di più, 0 la esclude.",
                        "decay.grace-hours", "Ore di grazia prima che il sovraccarico cominci a togliere territori.",
                        "map.mode", "Come risponde /f map: chat (mappa testuale, default) oppure item (mappa "
                                + "da tenere in mano). Cambiandola si aggiorna da sé anche la guida dei giocatori.")

                .issue("«Non riesco a fare claim»",
                        "Quasi sempre è Potenza insufficiente o tetto raggiunto, non un guasto. /f info sulla sua "
                                + "fazione mostra territori, Potenza e stato: se la riga è rossa la fazione è "
                                + "raidabile e non può espandersi.")
                .issue("«Mi stanno sparendo i territori»",
                        "È il decadimento, non un ladro: la fazione tiene più terreno di quanto la sua Potenza "
                                + "regga. Si ferma facendo entrare qualcuno o liberando terreno a mano.")
                .issue("I messaggi in chat compaiono due volte",
                        "Non è MagixFactions: sono i ClickHoverMessages di CMI, si spengono nel config di CMI.")
                .issue("La mappa è grigia o non si aggiorna",
                        "Manca il pacchetto risorse, oppure il giocatore è in un mondo che il plugin non gestisce.")
                .issue("Un giocatore è stato espulso appena entrato",
                        "Ha rifiutato il pacchetto risorse. Se deve entrare comunque, serve il permesso di bypass.")

                .never("Non modificare il config live a mano: al prossimo aggiornamento viene riportato a quello del "
                        + "sorgente. Se serve un valore diverso, va messo nel sorgente.")
                .never("Non cancellare righe dal database dei claim per «fare pulizia»: restano fazioni con "
                        + "territori fantasma e i conti della Potenza non tornano più.")
                .never("Non promettere a un giocatore che gli si «rimette» un territorio decaduto: si può "
                        + "ri-rivendicare solo se le condizioni di Potenza lo consentono.")
                .write();
    }

    private void writeReadme() {
        try (java.io.InputStream in = getResource("README.md")) {
            if (in == null) {
                getLogger().warning("README.md non incluso nel jar: README runtime non generato.");
                return;
            }
            java.nio.file.Path out = new java.io.File(getDataFolder(), "README.md").toPath();
            java.nio.file.Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            getLogger().info("README aggiornato (v" + getDescription().getVersion() + ").");
        } catch (Exception e) {
            getLogger().warning("Impossibile generare il README: " + e.getMessage());
        }
    }

    /**
     * I valori del {@code config.yml} da mettere nelle guide (tutorial dei giocatori e capitolo per lo
     * staff), pronti per {@link ConfigValues}.
     * <p>
     * Il grosso e' automatico: nel testo si scrive {@code {{cfg:power.max}}} o
     * {@code {{ore:decay.grace-hours}}} e non serve toccare niente qui. Qui stanno solo i testi
     * DERIVATI, quelli che non sono un singolo valore: una frase che cambia forma col periodo scelto, o
     * un pezzo che deve sparire del tutto quando la funzione e' spenta — meglio una guida che tace su
     * una regola che non c'e', che una guida che la descrive.
     */
    private ConfigValues guideValues() {
        var c = getConfig();
        ConfigValues v = new ConfigValues(this);

        int tetto = c.getInt("power.max", 10);
        v.extra("POWER_MAX_NEG", "−" + tetto);   // meno tipografico, come nel resto della pagina
        v.extra("POWER_MAX_POS", "+" + tetto);

        int perdita = c.getInt("power.offline-decay.amount", 0);
        if (perdita > 0) {
            String periodo = switch (c.getString("power.offline-decay.per", "day").toLowerCase(java.util.Locale.ROOT)) {
                case "hour" -> "ogni ora";
                case "week" -> "a settimana";
                case "month" -> "al mese";
                default -> "ogni 24 ore";
            };
            v.extra("PERDITA_OFFLINE_LI",
                    "<li><b>perdi " + perdita + " " + periodo + "</b> che passi senza collegarti;</li>");
            v.extra("PERDITA_OFFLINE_NOTA",
                    "<div class=\"warn\">La Potenza cala <b>mentre sei via</b>, non solo quando muori: se sparisci "
                    + "per una settimana la ritrovi più bassa, e la tua fazione nel frattempo può essere diventata "
                    + "attaccabile. È voluto — serve a impedire che chi ha smesso di giocare tenga terreno per "
                    + "sempre.</div>");
            v.extra("PERDITA_OFFLINE_FRASE",
                    "cala anche stando **VIA** (-" + perdita + " " + periodo + " di assenza)");
        } else { // funzione spenta: le guide non ne parlano affatto
            v.extra("PERDITA_OFFLINE_LI", "");
            v.extra("PERDITA_OFFLINE_NOTA", "");
            v.extra("PERDITA_OFFLINE_FRASE", "non cala stando via (la perdita da offline è spenta)");
        }
        return v;
    }

    /**
     * Riscrive (sovrascrive) tutorial.html nella cartella del plugin a ogni avvio: la guida illustrata
     * per i giocatori, sempre allineata alla versione del jar (come il README, ma per i giocatori) E ai
     * valori veri del config, sostituiti da {@link ConfigValues} (vedi {@link #valoriGuide()}).
     */
    private void writeTutorial() {
        try (java.io.InputStream in = getResource("tutorial.html")) {
            if (in == null) {
                getLogger().warning("tutorial.html non incluso nel jar: tutorial non generato.");
                return;
            }
            java.nio.file.Path out = new java.io.File(getDataFolder(), "tutorial.html").toPath();
            String html = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            html = guideValues().apply(html);
            java.nio.file.Files.writeString(out, html, java.nio.charset.StandardCharsets.UTF_8);
            getLogger().info("Tutorial HTML aggiornato (v" + getDescription().getVersion() + ").");
        } catch (Exception e) {
            getLogger().warning("Impossibile generare il tutorial: " + e.getMessage());
        }
    }
}
