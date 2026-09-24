package com.teolo.magixguard;

import com.teolo.magixguard.util.ConfigAlign;
import com.teolo.magixguard.alert.AlertService;
import com.teolo.magixguard.analyze.CorrelationEngine;
import com.teolo.magixguard.analyze.LinkScorer;
import com.teolo.magixguard.collect.CookieService;
import com.teolo.magixguard.collect.SignalCollector;
import com.teolo.magixguard.command.GuardCommand;
import com.teolo.magixguard.db.Database;
import com.teolo.magixguard.db.DbExecutor;
import com.teolo.magixguard.db.GuardDao;
import com.teolo.magixguard.sanctions.SanctionCommands;
import com.teolo.magixguard.sanctions.ReportCommand;
import com.teolo.magixguard.sanctions.ViolationCommand;
import com.teolo.magixguard.sanctions.Detector;
import com.teolo.magixguard.sanctions.ViolationsDao;
import com.teolo.magixguard.chat.ChatFilter;
import com.teolo.magixguard.xray.MiningAnalysis;
import com.teolo.magixguard.afk.AfkGuard;
import com.teolo.magixguard.sanctions.Policy;
import com.teolo.magixguard.sanctions.PointsLog;
import com.teolo.magixguard.sanctions.Rulebook;
import com.teolo.magixguard.sanctions.SanctionsConfig;
import com.teolo.magixguard.sanctions.SanctionsDao;
import com.teolo.magixguard.sanctions.SanctionsListener;
import com.teolo.magixguard.sanctions.SanctionsService;
import com.teolo.magixguard.sanctions.SiteSync;
import com.teolo.magixguard.sanctions.SiteDb;
import com.teolo.magixguard.lang.Messages;
import com.teolo.magixguard.util.StaffGuide;
import com.teolo.magixguard.dossier.DossierBuilder;
import com.teolo.magixguard.util.Hashing;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * MagixGuard - profilazione silenziosa dei giocatori e rilevamento multi-account.
 *
 * Fase 1: il plugin osserva, correla e documenta. Non blocca nessuno, non manda messaggi ai
 * giocatori, non limita niente. Produce collegamenti fra account, segnalazioni per lo staff e
 * dossier firmati, pensati per reggere a un ricorso sul forum.
 */
public final class MagixGuard extends JavaPlugin {

    private Database database;
    private DbExecutor dbExecutor;
    private SignalCollector collector;
    private Messages messages;

    // Modulo sanzioni (0.2.0): vive sul database del SITO, separato da quello della
    // profilazione. Gli IP restano di qua, i provvedimenti pubblici di la'.
    private SiteDb siteDb;
    private SanctionsService sanctions;
    // Letto dal dossier: e' un campo, non una variabile locale di startSanctions(), perche'
    // wire() (che costruisce il DossierBuilder) gira PRIMA di startSanctions() al primo avvio.
    // Il dossier lo legge tramite un supplier, cosi' vede il valore vero anche se al momento
    // in cui e' stato costruito era ancora null.
    private ViolationsDao violationsDao;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // I file di configurazione SUL SERVER allineati a quelli del jar: le chiavi nuove
        // compaiono da sole, al loro posto e col loro commento, senza toccare i valori
        // gia' scelti. Il deploy porta solo il jar, quindi senza questo il file del server
        // resterebbe indietro in silenzio (vedi util/ConfigAlign).
        ConfigAlign.alignAll(this);
        reloadConfig();
        getDataFolder().mkdirs();
        messages = new Messages(this);
        // Solo I/O su file: fuori dal tick di avvio (stessa convenzione degli altri plugin Magix).
        // Il README nella cartella del plugin non si copia piu' dal jar: lo genera
        // StaffGuide insieme al capitolo per il sito, cosi' i due non possono divergere.
        // Capitolo della guida per amministratori sul sito (vedi plugins-src/GUIDA-STAFF.md).
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);

        GuardConfig config = new GuardConfig(getConfig());
        if (config.pepperIsDefault()) {
            getLogger().warning("privacy.pepper e' ancora quello di esempio: cambialo in config.yml e riavvia. "
                    + "Finche' resta quello, chiunque ottenga una copia del database puo' risalire agli IP.");
        }

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

        dbExecutor = new DbExecutor(getLogger());
        wire(config);

        // Sessioni rimaste aperte da un arresto brusco: chiudile prima di aprirne di nuove.
        dbExecutor.submit("chiusura sessioni pendenti", () -> {
            int closed = database == null ? 0 : new GuardDao(database).closeDanglingSessions(System.currentTimeMillis());
            if (closed > 0) getLogger().info("Chiuse " + closed + " sessioni rimaste aperte dall'avvio precedente.");
        });

        // Anonimizzazione periodica: gli IP in chiaro non restano oltre la retention configurata.
        if (config.retentionDays > 0) {
            long period = 20L * 60 * 60 * 6; // ogni 6 ore
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                long cutoff = System.currentTimeMillis() - config.retentionDays * 86_400_000L;
                dbExecutor.submit("anonimizzazione sessioni", () -> {
                    int rows = new GuardDao(database).anonymizeOlderThan(cutoff);
                    if (rows > 0) getLogger().info("Anonimizzate " + rows + " sessioni oltre i "
                            + config.retentionDays + " giorni.");
                });
            }, 20L * 30, period);
        }

        startSanctions();

        getLogger().info("MagixGuard " + getPluginMeta().getVersion()
                + " attivo: profilazione in osservazione, sanzioni "
                + (sanctions != null ? "operative." : "NON attive (database del sito non raggiungibile)."));
    }

    // ------------------------------------------------------------- SANZIONI

    /**
     * Accende il modulo sanzioni. Se il database del sito non risponde il modulo resta spento,
     * ma il resto del plugin continua a funzionare: la profilazione non deve fermarsi perche'
     * il sito e' giu', e un sistema sanzionatorio a meta' e' peggio di nessuno.
     */
    private void startSanctions() {
        java.io.File file = new java.io.File(getDataFolder(), "sanctions.yml");
        if (!file.exists()) {
            saveResource("sanctions.yml", false);
        }
        SanctionsConfig cfg = new SanctionsConfig(SanctionsConfig.load(file));

        siteDb = new SiteDb(cfg);
        if (!siteDb.raggiungibile()) {
            getLogger().severe("Sanzioni NON attive: il database del sito non risponde. "
                    + "Controlla la sezione 'sito' in sanctions.yml. Ban e mute non funzioneranno.");
            siteDb.close();
            siteDb = null;
            return;
        }

        SanctionsDao dao = new SanctionsDao(siteDb);
        ViolationsDao violations = new ViolationsDao(siteDb);
        try {
            violations.assicuraTabella();
        } catch (java.sql.SQLException e) {
            getLogger().warning("Tabella delle violazioni non pronta: " + e.getMessage()
                    + ". I punti non verranno registrati.");
        }
        this.violationsDao = violations;
        PointsLog log = new PointsLog(violations, cfg);
        Policy policy = new Policy(cfg);
        sanctions = new SanctionsService(this, cfg, dao, log, policy, violations);

        // Il registro delle violazioni: TUTTO quello che il server nota passa di qui, e da qui
        // escono i punti. I rilevatori qui sotto non sanno niente di soglie e provvedimenti.
        Detector detector = new Detector(this, cfg, sanctions, violations, log);
        startDetectors(cfg, detector, dao);

        getServer().getPluginManager().registerEvents(
                new SanctionsListener(this, cfg, sanctions, dao), this);

        SanctionCommands commands = new SanctionCommands(this, cfg, sanctions, dao);
        String[] names = { "ban", "tempban", "mute", "tempmute", "kick", "warn",
                          "unban", "unmute", "history", "sanctions" };
        for (String name : names) {
            org.bukkit.command.PluginCommand c = getCommand(name);
            if (c != null) {
                c.setExecutor(commands);
                c.setTabCompleter(commands);
            }
        }
        // /report lo usano i giocatori, non lo staff: sta a parte anche nel codice.
        org.bukkit.command.PluginCommand cmdReport = getCommand("report");
        if (cmdReport != null) {
            ReportCommand report = new ReportCommand(this, cfg, sanctions, dao);
            cmdReport.setExecutor(report);
            cmdReport.setTabCompleter(report);
        }

        grabCommands(names);
        grabCommands(new String[] { "report" });

        // Le decisioni prese sul sito (revoche, proposte confermate) arrivano di qui.
        SiteSync sync = new SiteSync(this, dao, sanctions);
        long ticks = cfg.controlloSecondi * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, sync::pass, 200L, ticks);

        // Il regolamento pubblico si rigenera da questa configurazione: la pagina del sito
        // non descrive le sanzioni, le rispecchia.
        if (cfg.generateRules) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    dao.writeRules(Rulebook.generate(cfg), getPluginMeta().getVersion());
                    getLogger().info("Regolamento: tabella delle sanzioni rigenerata sul sito.");
                } catch (java.sql.SQLException e) {
                    getLogger().warning("Regolamento non aggiornato: " + e.getMessage());
                }
            });
        }

        getLogger().info("Sanzioni attive (modo " + cfg.mode + ", controllo sito ogni "
                + cfg.controlloSecondi + "s).");
    }

    /** Costruisce (o ricostruisce, dopo un reload) tutta la catena di componenti. */
    private void wire(GuardConfig config) {
        GuardDao dao = new GuardDao(database);
        Hashing hashing = new Hashing(config.pepper);
        LinkScorer scorer = new LinkScorer(config);
        AlertService alerts = new AlertService(this, config, dao);
        CorrelationEngine engine = new CorrelationEngine(dao, config, scorer, alerts);
        CookieService cookies = new CookieService(this, config.cookieKey, config.cookieEnabled);
        DossierBuilder dossier = new DossierBuilder(dao, config, scorer, getDataFolder(),
                getPluginMeta().getVersion(), () -> violationsDao);

        collector = new SignalCollector(this, config, dao, dbExecutor, hashing, engine, cookies);
        getServer().getPluginManager().registerEvents(collector, this);

        GuardCommand command = new GuardCommand(this, config, dao, dbExecutor, scorer, dossier, messages);
        var registered = getCommand("magixguard");
        if (registered != null) {
            registered.setExecutor(command);
            registered.setTabCompleter(command);
        }

        // Chi e' gia' collegato (avvio a caldo) va profilato subito: non arrivera' nessun join.
        Bukkit.getScheduler().runTaskLater(this, () -> collector.trackOnlinePlayers(), 40L);
    }

    /** Ricarica config.yml e ricostruisce i componenti senza riavviare il server. */
    public void reloadGuard() {
        // Come all'avvio: prima si allineano i file del server a quelli del jar, poi si
        // rilegge. Cosi' un reload dopo un deploy vede anche le chiavi nuove.
        ConfigAlign.alignAll(this);
        reloadConfig();
        messages.reload();
        if (collector != null) {
            collector.closeAllOpenSessions();
            HandlerList.unregisterAll(collector);
        }
        wire(new GuardConfig(getConfig()));
        getLogger().info("Configurazione ricaricata.");
    }

    @Override
    public void onDisable() {
        if (siteDb != null) {
            siteDb.close();
        }
        if (collector != null) collector.closeAllOpenSessions();
        if (dbExecutor != null) dbExecutor.shutdown();
        if (database != null) database.close();
    }


    /**
     * Accende i rilevatori: chat, scavo, AFK e l'ingresso per l'anticheat. Ognuno e' spegnibile
     * dalla sua sezione in sanctions.yml, e nessuno di loro decide niente — si limitano a dire
     * cosa hanno visto.
     */
    private void startDetectors(SanctionsConfig cfg, Detector detector, SanctionsDao dao) {
        org.bukkit.configuration.file.FileConfiguration conf =
                SanctionsConfig.load(new java.io.File(getDataFolder(), "sanctions.yml"));

        org.bukkit.configuration.ConfigurationSection sezChat = conf.getConfigurationSection("chat");
        if (sezChat == null || sezChat.getBoolean("attivo", true)) {
            getServer().getPluginManager().registerEvents(new ChatFilter(detector, sezChat), this);
            getLogger().info("Filtro chat attivo (spam, insulti, pubblicita', dati personali).");
        }

        org.bukkit.configuration.ConfigurationSection sezXray = conf.getConfigurationSection("xray");
        if (sezXray == null || sezXray.getBoolean("attivo", true)) {
            getServer().getPluginManager().registerEvents(new MiningAnalysis(this, detector, sezXray), this);
            String mode = sezXray == null ? "osservazione" : sezXray.getString("modo", "osservazione");
            getLogger().info("Anti-xray statistico attivo (modo " + mode + ").");
        }

        org.bukkit.configuration.ConfigurationSection sezAfk = conf.getConfigurationSection("afk");
        if (sezAfk == null || sezAfk.getBoolean("attivo", true)) {
            AfkGuard afk = new AfkGuard(this, detector, sezAfk);
            getServer().getPluginManager().registerEvents(afk, this);
            afk.start();
            getLogger().info("Anti-AFK attivo (niente guadagni da fermo + caccia ai dispositivi).");
        }

        org.bukkit.command.PluginCommand cmd = getCommand("mgviolation");
        if (cmd != null) {
            cmd.setExecutor(new ViolationCommand(this, detector, dao));
        }
    }

    /**
     * Si riprende i nomi brevi dei comandi di moderazione.
     *
     * <p>Serve perche' /ban, /kick e /mute esistono gia': li ha Minecraft e li ha CMI. Quando
     * il nome e' occupato, Bukkit lascia al plugin solo la forma lunga (magixguard:ban) e il
     * comando corto continua ad andare all'altro. E' successo davvero: /tempmute arrivava qui,
     * /unmute finiva a CMI, e uno staff si ritrovava a leggere "non e' mutato" su un giocatore
     * che era stato appena silenziato.</p>
     *
     * <p>Il guaio non e' l'inconveniente: e' che due sistemi di sanzioni vogliono dire due
     * archivi, e un provvedimento che non passa di qui non esiste per il sito ne' per il
     * ricorso. Per questo il nome se lo prende MagixGuard, e scrive nel log cosa ha scavalcato.</p>
     */
    private void grabCommands(String[] names) {
        try {
            org.bukkit.command.CommandMap mappa = getServer().getCommandMap();
            java.util.Map<String, org.bukkit.command.Command> note =
                    ((org.bukkit.command.SimpleCommandMap) mappa).getKnownCommands();

            java.util.List<String> presi = new java.util.ArrayList<>();
            for (String name : names) {
                org.bukkit.command.PluginCommand nostro = getCommand(name);
                if (nostro == null) {
                    continue;
                }
                org.bukkit.command.Command chiCera = note.get(name);
                if (chiCera == nostro) {
                    continue;   // il nome era libero: Bukkit ce l'aveva gia' dato
                }
                note.put(name, nostro);
                presi.add(name + (chiCera == null ? "" : " (era di " + describe(chiCera) + ")"));
            }

            if (!presi.isEmpty()) {
                getLogger().info("Comandi di moderazione presi in carico: " + String.join(", ", presi) + ".");
                // Senza questo i client continuano a proporre il completamento del vecchio comando.
                for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                    p.updateCommands();
                }
            }
        } catch (Throwable t) {
            // Se un giorno l'API cambia, i comandi lunghi (/mgban, /mgmute...) continuano a
            // funzionare: si perde la comodita', non la funzione.
            getLogger().warning("Non sono riuscito a prendermi i nomi brevi dei comandi ("
                    + t.getMessage() + "). Usa /mgban, /mgmute e simili.");
        }
    }

    /** Di chi era un comando, detto in modo leggibile. */
    private static String describe(org.bukkit.command.Command c) {
        if (c instanceof org.bukkit.command.PluginCommand pc) {
            return pc.getPlugin().getName();
        }
        return c.getClass().getSimpleName().contains("Vanilla") ? "Minecraft" : "un altro plugin";
    }

    /** Capitolo di MagixGuard nella guida del gestionale (plugins-src/GUIDA-STAFF.md). */
    private void writeStaffGuide() {
        org.bukkit.configuration.file.FileConfiguration sanz =
                SanctionsConfig.load(new java.io.File(getDataFolder(), "sanctions.yml"));

        StaffGuide.create(this, "MagixGuard — sanzioni, multi-account e prove", 10)
                // Numeri presi dal config VERO (config.yml + sanctions.yml): cambiando una soglia o i
                // punti di una categoria, questo capitolo sul sito cambia da solo.
                .values(new com.teolo.magixguard.util.ConfigValues(this).also(sanz))
                .intro("Il plugin che tiene l'ordine: decide e registra **ogni** provvedimento del "
                        + "server, e in parallelo guarda chi entra per capire quando due nickname "
                        + "sono la stessa persona. È l'unico posto da cui si sanziona.")

                // ---------------------------------------------------------- sanzioni
                .section("Come si sanziona",
                        "I comandi sono quelli standard — **/ban**, **/mute**, **/kick**, **/warn** — "
                                + "di proposito: non si devono imparare comandi nuovi, e soprattutto non "
                                + "deve esistere una seconda strada che sfugga all'archivio. Per questo i "
                                + "comandi di moderazione di CMI vanno tenuti disabilitati.",
                        "Il **motivo non è facoltativo**: lo legge il giocatore nel messaggio di "
                                + "espulsione e finisce nell'elenco pubblico su magicadventure.it. "
                                + "Scrivilo come lo scriveresti sapendo che lo leggeranno tutti, perché è "
                                + "esattamente quello che succede.",
                        "Ogni provvedimento ha un **ambito**: vale in gioco, sul sito, o su entrambi. "
                                + "Un ban con ambito 'entrambi' chiude anche il login al sito e il forum; "
                                + "la pagina del ricorso però resta sempre raggiungibile, altrimenti la "
                                + "sanzione diventerebbe inappellabile di fatto.")

                .section("I punti, e perché una cosa vecchia pesa meno",
                        "Ogni violazione vale dei punti. I punti si sommano e **dimezzano** ogni "
                                + "{{cfg:points/halving-days}} giorni: chi ha sbagliato una volta a "
                                + "marzo non se lo porta dietro per sempre, chi insiste paga di più. "
                                + "Superata una soglia scatta il provvedimento previsto.",
                        "**/sanctions <nome>** mostra i punti attuali e quanti ne mancano al prossimo "
                                + "scatto: è la risposta pronta a «quanto sono messo male». Le sanzioni "
                                + "revocate non contano: se un ricorso è stato accolto, quei punti non "
                                + "sono mai esistiti.",
                        "Le soglie e i punti di ogni categoria stanno in **sanctions.yml**, ed è lo "
                                + "stesso file che genera la tabella del regolamento sul sito: cambiare "
                                + "una soglia riscrive il regolamento pubblico da solo. Non esiste il caso "
                                + "in cui il server punisce in un modo e promette un altro.")

                .section("Cosa può fare da solo il plugin, e cosa no",
                        "Il modo predefinito è **misto**: il plugin applica da solo solo quello di cui "
                                + "è ragionevolmente certo e che sta entro un tetto di durata; tutto il "
                                + "resto diventa una **proposta** nella coda del gestionale, con le prove "
                                + "già allegate.",
                        "Il **ban permanente non è mai automatico**, qualunque cosa dica la "
                                + "configurazione: è la decisione più grave che esista e la prende sempre "
                                + "una persona. Non è un valore regolabile, è una scelta.",
                        "Anche i **dati personali** non sanzionano mai da soli: il messaggio viene "
                                + "bloccato e parte l'avviso allo staff, ma decide un umano. Il pubblico "
                                + "è in larga parte minorenne, e qui un falso positivo pesa molto meno di "
                                + "uno staff che non viene avvisato.")

                .section("Il tetto del tuo grado",
                        "Ogni grado dello staff ha un limite di durata. Se provi a dare una sanzione "
                                + "che lo supera **non viene rifiutata**: diventa una proposta motivata "
                                + "per il grado superiore, con tutto già pronto. Chi vede il problema non "
                                + "perde il lavoro fatto, e chi ha il potere di decidere non deve "
                                + "ricostruire niente.",
                        "I tetti si impostano in sanctions.yml, sezione **poteri**, per gruppo LuckPerms.")

                .section("Il sito e il server si parlano",
                        "Le sanzioni le **scrive solo il plugin**: il sito le mostra e basta. Quello che "
                                + "nasce sul sito sono i ricorsi e le decisioni dello staff nel gestionale "
                                + "— revoche e conferme dalla coda — che il plugin rilegge ed esegue in "
                                + "partita entro pochi secondi.",
                        "Se nel gestionale una revoca resta ferma su «in attesa del server», vuol dire "
                                + "che il server è spento o che il plugin non gira: in gioco quel ban c'è "
                                + "ancora, ed è giusto che il gestionale lo dica invece di far finta.")

                .section("Il filtro della chat",
                        "Quattro cose diverse, con quattro reazioni diverse — e la differenza non e' "
                                + "un dettaglio. **Pubblicita'** di altri server e **dati personali**: "
                                + "messaggio **bloccato**, non lo vede nessuno. **Insulti**: passa "
                                + "**censurato**, cosi' lo staff puo' ancora leggere di cosa stavano "
                                + "discutendo. **Spam**: bloccato **in silenzio**, cioe' lo vede solo chi "
                                + "l'ha scritto — chi provoca non deve vedere l'effetto che fa.",
                        "In tutti e quattro i casi il messaggio **originale integro** finisce nelle prove "
                                + "insieme alle righe di chat intorno. E' quello che rende una violazione "
                                + "utilizzabile in un ricorso: un insulto isolato, senza contesto, non "
                                + "dimostra niente.",
                        "Il confronto avviene sulla forma **nuda** del messaggio: c4zz0, c a z z o e "
                                + "cazzzzo arrivano tutti al dizionario come la stessa parola. Per evitare "
                                + "il rovescio — trovare una parola vietata dentro una innocente — il "
                                + "controllo e' su parola intera, e la forma compattata si usa solo per le "
                                + "parole lunghe.",
                        "Lo staff con **magixguard.chat.bypass** non passa dal filtro: deve poter citare "
                                + "un indirizzo o una parola per spiegare perche' e' vietata, senza "
                                + "sanzionarsi da solo.")

                .section("Anti-xray: perche' e' statistico",
                        "L'xray **non e' un problema di pacchetti**: il client non fa niente di strano, "
                                + "guarda soltanto dei blocchi che il server gli ha gia' mandato. Nessun "
                                + "anticheat lo rileva bene, e non e' colpa loro. La prima difesa e' "
                                + "l'offuscamento nativo di Paper (**engine-mode 2**), che i minerali non "
                                + "li manda proprio.",
                        "Il secondo strato e' qui, e guarda **cosa scava** una persona invece di come si "
                                + "muove: quanti minerali preziosi ogni mille blocchi di roccia, e quanti "
                                + "ne rompe mentre erano completamente circondati — cioe' invisibili fino "
                                + "all'istante prima.",
                        "Non si giudica mai prima di **blocchi-minimi** blocchi scavati: sui primi cento "
                                + "qualunque numero e' rumore. E il modulo parte in **sola osservazione**: "
                                + "avvisa e basta, senza punti e senza provvedimenti, finche' le soglie non "
                                + "sono state tarate sui dati veri di questo server. Metterlo in "
                                + "`modo: attivo` prima di aver guardato i numeri vuol dire accusare "
                                + "qualcuno con una soglia inventata.")

                .section("Anti-AFK: due misure che rispondono a due problemi",
                        "**Niente guadagni da fermo.** Dopo i minuti indicati nel config, attorno a chi "
                                + "e' immobile i mostri non nascono piu', e oggetti ed esperienza non gli "
                                + "arrivano addosso. Non viene espulso e non viene punito: semplicemente il "
                                + "gioco smette di premiare il fatto di essere collegato invece che di "
                                + "giocare. Chi resta per chiacchierare non se ne accorge nemmeno.",
                        "Lo spawn si blocca solo se nel raggio **non c'e' nessun giocatore sveglio**: "
                                + "altrimenti basterebbe un AFK di passaggio per rovinare la serata a chi "
                                + "sta giocando li' accanto.",
                        "**Caccia ai dispositivi.** Chi mette un peso sul mouse, gira in barca o si fa "
                                + "spingere da un pistone non e' fermo: sta **aggirando** la misura di "
                                + "sopra, e questo si sanziona. Ma solo su segnali che una mano umana non "
                                + "puo' produrre: click a intervallo costante al millisecondo per minuti "
                                + "interi. Nelle prove finisce la serie: quanti click, per quanto tempo, "
                                + "con che scarto.")

                .section("L'aggancio all'anticheat",
                        "Grim non parla con MagixGuard attraverso un'API: gli si fa **eseguire un "
                                + "comando** quando un giocatore supera una sua soglia. Nel suo "
                                + "`punishments.yml` la riga e' `\"40:40 mgviolation %player% "
                                + "cheat.movimento %check_name% (vl %vl%)\"`.",
                        "Sembra rozzo ed e' invece la parte piu' solida del disegno: **non dipendiamo "
                                + "dalla versione di nessun anticheat**, ne' dal fatto che continui a "
                                + "esistere. Se domani Grim viene sostituito, si riscrivono quattro righe "
                                + "di configurazione e il sistema sanzioni non se ne accorge.",
                        "Chi decide **quando** chiamare e' l'anticheat; chi decide **cosa succede** e' il "
                                + "registro punti. Si tarano separatamente.")

                .section("«Da controllare»: da dove cominciare quando hai dieci minuti",
                        "Nel gestionale c'e' una scheda che mette i giocatori **in ordine di quanto "
                                + "conviene andarli a guardare** — non di quanto sono colpevoli. Il "
                                + "punteggio somma tre cose: i punti delle violazioni (col decadimento, "
                                + "quindi una cosa di sei mesi fa quasi non conta), le segnalazioni dei "
                                + "giocatori ancora aperte, e i provvedimenti gia' attivi.",
                        "Le segnalazioni pesano per **quanti giocatori diversi** le hanno mandate: tre "
                                + "segnalazioni della stessa persona valgono molto meno di tre persone che "
                                + "segnalano lo stesso nome. E' la differenza fra un sospetto e una "
                                + "ripicca.",
                        "Accanto al punteggio c'e' sempre **da cosa e' fatto**. Non e' un dettaglio "
                                + "estetico: un numero senza il suo perche' finirebbe per essere usato "
                                + "come prova, e non lo e'. La classifica dice dove guardare, mai cosa "
                                + "decidere.",
                        "Quando ne hai guardato uno, segnalo come **controllato**: esce dalla lista per "
                                + "una settimana, cosi' non lo ricontrolli tu domani e un altro dello "
                                + "staff dopodomani. Se e' rimasto sospetto c'e' il pulsante apposta, e "
                                + "resta in cima.")

                .section("Come i punti diventano un provvedimento",
                        "Ogni fatto rilevato — un messaggio bloccato, un verdetto dell'anticheat, uno "
                                + "scavo fuori scala — viene registrato come **violazione** con i punti "
                                + "della sua categoria. La maggior parte non produce nessuna sanzione, e "
                                + "deve restare comunque agli atti: senza, il terzo spam di un giocatore "
                                + "sarebbe identico al primo.",
                        "Il provvedimento scatta solo quando una soglia viene **appena superata**: chi "
                                + "resta sopra i 50 punti non si becca un ban a ogni sciocchezza "
                                + "successiva. E se un ricorso viene accolto, le violazioni che avevano "
                                + "fatto scattare quel provvedimento **smettono di contare** — altrimenti "
                                + "il giocatore resterebbe a un passo dalla soglia dopo, cioe' punito lo "
                                + "stesso, a meta'.")

                .section("Le segnalazioni dei giocatori",
                        "**/report <giocatore> <motivo>** e' aperto a tutti ed e' il canale che fa "
                                + "emergere quello che nessun algoritmo vede: truffe, molestie, accordi "
                                + "fra due account, comportamenti che stanno nelle intenzioni e non nei "
                                + "pacchetti.",
                        "Una segnalazione **non e' una sanzione** e non ne fa scattare nessuna da sola: "
                                + "apre un caso nella stessa coda dei rilevamenti automatici, cosi' avete "
                                + "un posto solo da guardare. Nel gestionale compare marcata come "
                                + "«Segnalazione», e chi la chiude **sceglie il provvedimento**: il caso "
                                + "arriva senza una pena gia' proposta, perche' a proporla era un giocatore, "
                                + "non il sistema.",
                        "Al caso vengono allegati da soli il nome di chi segnala, l'ora, la posizione di "
                                + "tutti e due e la distanza fra loro: sono le prime tre cose che servono "
                                + "per decidere se andare a guardare subito o con calma.",
                        "Contro l'abuso ci sono tre freni: una pausa fra una segnalazione e l'altra, un "
                                + "numero massimo di casi aperti a testa, e un motivo che deve essere scritto "
                                + "davvero — «barare» non dice niente a chi dovra' controllare. Chi apre "
                                + "segnalazioni false lo si vede dalla coda, e resta comunque sanzionabile.")

                .subcommands("I comandi di moderazione",
                        "/ban <nome> <motivo>", "Bandisce per sempre. Il giocatore vede il motivo e il collegamento al ricorso.",
                        "/mute <nome> <motivo>", "Gli impedisce di scrivere in chat, finche' non lo togli.",
                        "/tempban <nome> <durata> <motivo>", "Ban a tempo: 30m, 6h, 3d, 2w.",
                        "/tempmute <nome> <durata> <motivo>", "Silenzio a tempo, stesse durate.",
                        "/kick <nome> <motivo>", "Lo butta fuori adesso; può rientrare subito.",
                        "/warn <nome> <motivo>", "Richiamo: non impedisce niente ma resta agli atti.",
                        "/unban <nome> [motivo]", "Revoca il ban attivo.",
                        "/unmute <nome> [motivo]", "Revoca il silenzio.",
                        "/history <nome>", "Tutti i provvedimenti di quel giocatore, con i punti attuali.",
                        "/sanctions [nome]", "Provvedimenti in corso e punti. Senza nome, i tuoi.",
                        "/report <nome> <motivo>", "Aperto a TUTTI i giocatori: apre un caso per lo staff, senza proporre nessuna pena.",
                        "/mgviolation <nome> <categoria> [dettaglio]", "Ingresso per i verdetti dell'anticheat: lo chiama Grim, non una persona.")

                // ---------------------------------------------------------- multi-account
                .section("L'altra metà: i multi-account",
                        "Su un server non premium il nome non prova niente e un account nuovo costa "
                                + "zero. MagixGuard guarda ogni accesso e mette insieme indizi deboli "
                                + "finché non diventano un quadro.",
                        "Della **rete**: indirizzo, sottorete, nome di rete inverso — che identifica la "
                                + "linea anche quando l'indirizzo cambia. Del **client**: marca, elenco "
                                + "delle mod dichiarate (spesso unico come un'impronta digitale), lingua, "
                                + "distanza visiva, latenza. E un **token** scritto nel client: due "
                                + "nickname che presentano lo stesso token girano sulla stessa copia del "
                                + "gioco. Attenzione al suo limite: nel client normale vive in memoria, "
                                + "quindi si perde quando il giocatore chiude Minecraft. La sua assenza "
                                + "non prova niente; la sua presenza sì.")

                .section("Le tre correzioni che rendono onesto il punteggio",
                        "Un indirizzo usato da trenta account vale quasi zero: sono le reti mobili "
                                + "italiane, dove centinaia di estranei escono dallo stesso indirizzo.",
                        "Una prova di sei mesi fa pesa la metà di una di oggi, perché gli indirizzi "
                                + "domestici vengono riassegnati.",
                        "Due account visti online **nello stesso momento** si scagionano a vicenda: è "
                                + "il controllo che tiene fuori fratelli e coinquilini, ed è anche il più "
                                + "efficace. Le occorrenze non moltiplicano il punteggio: descrivono il "
                                + "fenomeno, non lo aggravano, altrimenti chi gioca molto risulterebbe più "
                                + "colpevole di chi gioca poco.")

                .section("Il dossier e la firma",
                        "Esce in due versioni: quella **interna** contiene tutto, indirizzi compresi, e "
                                + "non si pubblica mai; quella **pubblica** li maschera ed è quella da "
                                + "allegare alla risposta a un ricorso.",
                        "Dentro ci sono gli indizi a carico **e** quelli a discolpa, con il peso "
                                + "applicato e il perché di ogni correzione, più una sezione finale che "
                                + "dichiara apertamente i limiti del metodo. Serve a reggere un ricorso, "
                                + "non a vincere una discussione.",
                        "Ogni dossier è firmato e la firma entra in un registro a catena: dimostra che "
                                + "il documento esisteva già in quella forma **prima** della decisione.")

                .subcommands("I comandi delle indagini (/mg, /guard, /alts)",
                        "/mg help [pagina]", "L'elenco dei comandi, a pagine e cliccabile.",
                        "/mg alts <nome>", "Gli account collegati a quel giocatore, col dettaglio degli indizi.",
                        "/mg dossier <nome> [nome2] [pubblico]", "Genera il documento completo. Con «pubblico» gli indirizzi sono mascherati: è quello da allegare al ricorso.",
                        "/mg sessions <nome> [n]", "Gli ultimi accessi con tutti i dati tecnici.",
                        "/mg alerts [n]", "Le ultime segnalazioni allo staff.",
                        "/mg link <a> <b> [motivo]", "Collega due account a mano, quando lo sai per certo.",
                        "/mg unlink <a> <b> [motivo]", "Dichiara che la coppia è legittima: niente più segnalazioni. È la risposta al caso dei fratelli.",
                        "/mg exempt <nome> <on|off>", "Esclude un account dalla profilazione.",
                        "/mg verify", "Ricontrolla la catena delle firme: se non torna, il database è stato toccato a mano.",
                        "/mg stats", "I numeri generali.",
                        "/mg reload", "Ricarica la configurazione.")

                .commands()
                .permissions()
                .settingsFrom(sanz, "Impostazioni delle sanzioni",
                        "applicazione/modo", "misto, automatico o proposta: quanto può decidere il plugin da solo.",
                        "applicazione/durata-massima-automatica", "Oltre questa durata nessun automatismo procede: si passa dalla coda.",
                        "punti/dimezzamento-giorni", "Ogni quanti giorni i punti valgono la metà.",
                        "sito/controllo-secondi", "Ogni quanto si rileggono le decisioni prese sul sito.",
                        "report/attivo", "Accende o spegne /report per i giocatori.",
                        "report/pausa-secondi", "Quanto deve aspettare un giocatore fra una segnalazione e l'altra.",
                        "report/massimo-aperte", "Quante segnalazioni ancora aperte puo' avere una persona alla volta.",
                        "report/motivo-minimo", "Lunghezza minima del motivo di una segnalazione.",
                        "chat/attivo", "Accende o spegne tutto il filtro della chat.",
                        "chat/insulti/censura", "Se gli insulti passano censurati o vengono bloccati del tutto.",
                        "xray/modo", "osservazione = avvisa e basta; attivo = registra e fa punti.",
                        "xray/blocchi-minimi", "Sotto questi blocchi scavati non si giudica: sarebbe rumore.",
                        "xray/estremo-per-mille", "Minerali preziosi ogni 1000 blocchi oltre i quali scatta il provvedimento.",
                        "afk/minuti-inattivo", "Dopo quanti minuti fermo il gioco smette di produrre intorno a lui.",
                        "afk/dispositivi/scarto-massimo-ms", "Quanto puo' essere regolare la cadenza dei click prima di essere disumana.")
                .settings(
                        "analysis.link-threshold", "Punteggio oltre il quale due account risultano collegati.",
                        "analysis.alert-threshold", "Punteggio oltre il quale parte la segnalazione allo staff.",
                        "privacy.session-retention-days", "Per quanti giorni si tengono gli indirizzi in chiaro.")

                .issue("Ho cambiato una chiave del config nel repo e sul server non succede niente",
                        "Il deploy porta il jar, non i config: il file nella cartella del plugin sul server non viene toccato, ed e' quello che il plugin legge. Il valore nel jar vale solo per le chiavi che li' MANCANO. Quindi un valore gia' presente si cambia sul server (a mano, o col workflow deploy-plugin-config.yml), non nel repo. Del resto si occupa il plugin, a ogni avvio e a ogni reload: aggiunge le chiavi nuove al loro posto col loro commento, applica le rinomine portandosi dietro il valore che avevi scelto, e toglie le righe morte che il codice non legge piu' dai file a schema fisso, cioe' tutti tranne i cataloghi (i menu e le sanzioni no: li' le voci in piu' sono tue). Prima di ogni modifica fa una copia del file in .bak/ (fuori da plugins/ sul server), col nome che finisce in .bak-<data>, e nel log scrive che cosa ha cambiato.")
                .issue("«Ho bannato ma il giocatore è ancora dentro»",
                        "Il ban vale all'ingresso: se era già collegato viene espulso subito, ma solo se "
                                + "l'ambito comprende il gioco. Un provvedimento con ambito «sito» in "
                                + "partita non fa niente, ed è voluto.")
                .issue("«Il comando /ban non risponde, o risponde un altro plugin»",
                        "Qualcun altro si è preso il nome. Usa /mgban, /mgmute e compagnia: funzionano "
                                + "sempre. Poi disabilita quel comando nell'altro plugin, perché due "
                                + "sistemi di ban vuol dire due archivi e nessuna verità.")
                .issue("La revoca fatta sul sito non ha effetto in gioco",
                        "Il server la esegue entro pochi secondi. Se resta ferma, il server è spento o "
                                + "il plugin non gira: nel gestionale la vedi nell'elenco «revoche in "
                                + "attesa del server».")
                .issue("«Ho scritto una parola normale e me l'ha censurata»",
                        "Guarda quale parola ha fatto scattare il filtro: e' scritta nelle prove della "
                                + "violazione. Se e' un falso positivo, si toglie dal dizionario in "
                                + "sanctions.yml — e la violazione si annulla revocando il provvedimento.")
                .issue("L'anti-xray segnala un minatore che sembra onesto",
                        "Puo' succedere: e' una statistica, non una prova. Per questo di serie il modulo "
                                + "e' in sola osservazione e il provvedimento automatico scatta solo oltre "
                                + "la soglia estrema. Guarda i numeri nelle prove prima di decidere.")
                .issue("«Non mi nascono piu' i mostri nella mia farm»",
                        "E' l'anti-AFK: da fermo il gioco non produce piu' nulla intorno a lui. Basta "
                                + "muoversi. Se c'e' un altro giocatore sveglio nel raggio, gli spawn "
                                + "riprendono comunque.")
                .issue("Grim segnala ma non arriva nessuna violazione",
                        "Controlla che nel suo punishments.yml ci sia la riga con mgviolation, e che il "
                                + "comando non sia stato preso da un altro plugin: /mgviolation da "
                                + "console deve rispondere.")
                .issue("«Ho segnalato uno e non e' successo niente»",
                        "Una segnalazione apre un caso, non applica una pena: la trovi in coda nel "
                                + "gestionale finche' qualcuno non la chiude. Se ne arrivano molte e "
                                + "restano ferme, il problema non e' il comando.")
                .issue("Due fratelli risultano collegati",
                        "Guarda le sessioni sovrapposte nel dossier: se hanno giocato insieme il "
                                + "punteggio scende già da solo. Con /mg unlink si chiude il caso.")
                .issue("Un collegamento sembra sbagliato",
                        "/mg dossier <nome1> <nome2> stampa tutti gli indizi con il peso applicato: si "
                                + "vede quale ha fatto la differenza. Se è un indirizzo affollato, il "
                                + "dossier lo dice da sé.")
                .issue("Il registro non passa il controllo",
                        "/mg verify dice che la catena delle firme non torna: qualcuno ha toccato il "
                                + "database a mano. È esattamente il caso per cui la catena esiste.")

                .never("Non sanzionare da un altro plugin: quello che non passa di qui non esiste per "
                        + "l'archivio, per il sito e per il ricorso.")
                .never("Non scrivere motivi che non vorresti vedere pubblicati: finiscono nell'elenco "
                        + "pubblico, con il tuo nome accanto.")
                .never("Non pubblicare mai il dossier interno: contiene gli indirizzi in chiaro. Per il "
                        + "forum c'è la versione pubblica.")
                .never("Non cambiare il segreto usato per gli hash a cuor leggero: gli indizi già "
                        + "raccolti smettono di combaciare con quelli nuovi.")
                .never("Non dire a un giocatore quale indizio lo ha tradito: se lo sa, la volta dopo "
                        + "cambia proprio quello.")
                .never("Non mettere l'anti-xray in modo attivo prima di aver guardato i numeri veri di "
                        + "questo server: le soglie di partenza sono una stima, non una misura.")
                .never("Non alzare l'anti-AFK a pochi minuti per «liberare slot»: colpirebbe chi sta "
                        + "leggendo la chat, non chi ha una farm.")
                .write();
    }

    /**
     * Riscrive il README nella cartella del plugin a ogni avvio, cosi' la documentazione
     * corrisponde sempre alla versione del jar installato (convenzione di progetto).
     */
    private void writeReadme() {
        try (InputStream in = getResource("README.md")) {
            if (in == null) {
                getLogger().warning("README.md non incluso nel jar: README runtime non generato.");
                return;
            }
            Path out = new java.io.File(getDataFolder(), "README.md").toPath();
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            getLogger().info("README aggiornato (v" + getPluginMeta().getVersion() + ").");
        } catch (Exception e) {
            getLogger().warning("Impossibile generare il README: " + e.getMessage());
        }
    }
}
