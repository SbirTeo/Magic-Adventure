package com.teolo.magixlanguage;

import com.teolo.magixlanguage.api.MagixLanguageAPI;
import com.teolo.magixlanguage.command.MagixLanguageCommand;
import com.teolo.magixlanguage.geo.GeoLookup;
import com.teolo.magixlanguage.hook.MagixLanguagePlaceholders;
import com.teolo.magixlanguage.lang.Messages;
import com.teolo.magixlanguage.listener.LoginListener;
import com.teolo.magixlanguage.translate.MenuPhraseSync;
import com.teolo.magixlanguage.translate.PlayerLocales;
import com.teolo.magixlanguage.translate.TranslationSync;
import com.teolo.magixlanguage.util.ConfigAlign;
import com.teolo.magixlanguage.util.StaffGuide;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MagixLanguage - rileva la lingua di chi entra dal paese di provenienza (GeoIP sull'IP di
 * ingresso) e tiene sincronizzati, un file per lingua, i messaggi di tutti gli altri plugin
 * Magix elencati nel config, cosi' un altro plugin puo' chiedere un testo gia' tradotto invece
 * di mandarlo sempre e solo in italiano.
 *
 * <p>Tre pezzi indipendenti:</p>
 * <ul>
 *   <li><b>rilevazione</b>: {@link GeoLookup} + {@link LoginListener}, al momento dell'ingresso;</li>
 *   <li><b>memoria</b>: {@link PlayerLocales}, la lingua scelta per ogni giocatore, persistente;</li>
 *   <li><b>traduzione</b>: {@link TranslationSync} copia il testo, questa classe lo serve tramite
 *       {@link MagixLanguageAPI} agli altri plugin (softdepend, via {@code ServicesManager}).</li>
 * </ul>
 */
public final class MagixLanguage extends JavaPlugin implements MagixLanguageAPI {

    private Messages messages;
    private PlayerLocales locales;
    private GeoLookup geo;

    /** Risultato dell'ultima sincronizzazione (avvio o /language sync), per /language status. */
    private volatile TranslationSync.Result lastSyncResult;

    /** pluginName -> lingua -> chiave.puntata -> testo. Svuotata a ogni reload/sync: si ricarica da sola. */
    private final Map<String, Map<String, Map<String, Object>>> catalogCache = new ConcurrentHashMap<>();

    /** pluginName -> lingua -> hash della frase -> testo tradotto. Vedi {@link #translatePhrase}. */
    private final Map<String, Map<String, Map<String, String>>> menuPhraseCache = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // I file di configurazione SUL SERVER allineati a quelli del jar: le chiavi nuove
        // compaiono da sole, al loro posto e col loro commento, senza toccare i valori
        // gia' scelti (vedi util/ConfigAlign).
        ConfigAlign.alignAll(this);
        reloadConfig();
        getDataFolder().mkdirs();

        messages = new Messages(this);
        locales = new PlayerLocales(getDataFolder(), getLogger());
        geo = buildGeoLookup();

        PluginCommand cmd = getCommand("magixlanguage");
        if (cmd != null) {
            MagixLanguageCommand executor = new MagixLanguageCommand(this, messages);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
        getServer().getPluginManager().registerEvents(new LoginListener(this, locales, geo), this);

        registerPlaceholders();
        getServer().getServicesManager().register(MagixLanguageAPI.class, this, this, ServicePriority.Normal);

        // Puro I/O su file: non deve bloccare il tick di avvio.
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);
        if (getConfig().getBoolean("translations.sync-on-start", true)) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                lastSyncResult = new TranslationSync(this).run();
                clearCatalogCaches();
            });
        }

        getLogger().info("Avviato: lingua di default " + getConfig().getString("default-language", "it")
                + ", GeoIP " + (getConfig().getBoolean("geoip.enabled", true) ? "attivo" : "disattivo") + ".");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }

    /** Ricarica config.yml e messages.yml e svuota le cache derivate (/language reload). */
    public void reloadEverything() {
        ConfigAlign.alignAll(this);
        reloadConfig();
        messages.reload();
        geo = buildGeoLookup();
        clearCatalogCaches();
        getLogger().info("Configurazione ricaricata.");
    }

    /**
     * Svuota le cache dei cataloghi tradotti, cosi' la prossima {@link #translate} rilegge i file
     * appena scritti invece di continuare a servire quello che aveva in memoria da PRIMA della
     * sincronizzazione (visto succedere davvero: centinaia di chiavi tradotte su disco, ma i
     * giocatori online continuavano a vedere l'italiano perche' nessuno svuotava questa cache
     * dopo un /language sync - solo /language reload lo faceva). Va chiamato dopo OGNI
     * {@link TranslationSync#run()}, sia quello all'avvio che quello di /language sync.
     */
    public void clearCatalogCaches() {
        catalogCache.clear();
        menuPhraseCache.clear();
    }

    private GeoLookup buildGeoLookup() {
        return new GeoLookup(getLogger(),
                getConfig().getString("geoip.provider-url", "http://ip-api.com/json/{ip}?fields=status,countryCode"),
                getConfig().getInt("geoip.timeout-ms", 1500),
                getConfig().getInt("geoip.cache-days", 30),
                new File(getDataFolder(), "geoip-cache.tsv").toPath());
    }

    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        try {
            new MagixLanguagePlaceholders(this).register();
            getLogger().info("Placeholder %magixlanguage_...% registrati su PlaceholderAPI.");
        } catch (Throwable t) {
            getLogger().warning("Registrazione dei placeholder fallita: " + t.getMessage());
        }
    }

    public PlayerLocales locales() {
        return locales;
    }

    /** L'esito dell'ultima sincronizzazione (avvio o /language sync), o null se non e' mai girata. */
    public TranslationSync.Result lastSyncResult() {
        return lastSyncResult;
    }

    public void setLastSyncResult(TranslationSync.Result result) {
        this.lastSyncResult = result;
    }

    // ------------------------------------------------------------- MagixLanguageAPI

    @Override
    public String language(UUID playerId) {
        PlayerLocales.Entry entry = locales.get(playerId);
        return entry != null ? entry.lang() : getConfig().getString("default-language", "it");
    }

    @Override
    public void setLanguage(OfflinePlayer player, String lang) {
        if (lang == null || !getConfig().getStringList("supported-languages").contains(lang)) {
            return;
        }
        locales.setManual(player.getUniqueId(), lang);
    }

    @Override
    public String translate(String pluginName, Player player, String key, Map<String, String> placeholders) {
        return translate(pluginName, language(player), key, placeholders);
    }

    @Override
    public String translate(String pluginName, String lang, String key, Map<String, String> placeholders) {
        Object value = resolve(pluginName, lang, key);
        return value instanceof String s ? apply(s, placeholders) : null;
    }

    @Override
    public List<String> translateList(String pluginName, Player player, String key, Map<String, String> placeholders) {
        return translateList(pluginName, language(player), key, placeholders);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> translateList(String pluginName, String lang, String key, Map<String, String> placeholders) {
        Object value = resolve(pluginName, lang, key);
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<String> out = new java.util.ArrayList<>(list.size());
        for (Object line : list) {
            out.add(apply(String.valueOf(line), placeholders));
        }
        return out;
    }

    /** Il valore grezzo (String o List) della chiave, con ripiego sulla lingua di default. Null se manca ovunque. */
    private Object resolve(String pluginName, String lang, String key) {
        Object value = catalog(pluginName, lang).get(key);
        if (value == null) {
            String defaultLanguage = getConfig().getString("default-language", "it");
            if (!defaultLanguage.equals(lang)) {
                value = catalog(pluginName, defaultLanguage).get(key);
            }
        }
        return value;
    }

    private Map<String, Object> catalog(String pluginName, String lang) {
        return catalogCache
                .computeIfAbsent(pluginName, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(lang, l -> TranslationSync.loadFlatCatalog(
                        new File(getDataFolder(), "translations/" + pluginName + "/" + l + ".yml")));
    }

    @Override
    public String translatePhrase(String pluginName, Player player, String italianText) {
        if (italianText == null || italianText.isBlank()) {
            return null;
        }
        String lang = language(player);
        if (lang.equals("it")) {
            return null; // niente da tradurre verso l'italiano stesso
        }
        Map<String, String> catalog = menuPhraseCatalog(pluginName, lang);
        return catalog.get(MenuPhraseSync.hash(italianText));
    }

    @Override
    public Map<String, String> translateRawBatch(List<String> italianTexts, String targetLanguage) {
        Map<String, String> out = new java.util.HashMap<>();
        if (italianTexts == null || italianTexts.isEmpty() || targetLanguage == null
                || !getConfig().getStringList("supported-languages").contains(targetLanguage)
                || targetLanguage.equals("it")
                || !getConfig().getBoolean("translations.auto-translate.enabled", true)) {
            return out;
        }
        int timeoutMs = getConfig().getInt("translations.auto-translate.timeout-ms", 4000);
        int delayMs = getConfig().getInt("translations.auto-translate.delay-ms", 150);
        String contactEmail = getConfig().getString("translations.auto-translate.contact-email", "");
        com.teolo.magixlanguage.translate.Translator translator =
                new com.teolo.magixlanguage.translate.Translator(timeoutMs, getLogger(), contactEmail);
        for (String text : italianTexts) {
            if (!translator.isAvailable()) {
                break; // circuito aperto: il resto del lotto resta per il prossimo giro
            }
            String translated = translator.translate(text, targetLanguage);
            if (translated != null) {
                out.put(text, translated);
            }
            sleepQuietly(delayMs);
        }
        return out;
    }

    private static void sleepQuietly(int millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, String> menuPhraseCatalog(String pluginName, String lang) {
        return menuPhraseCache
                .computeIfAbsent(pluginName, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(lang, l -> loadMenuPhraseCatalog(
                        new File(getDataFolder(), "translations/" + pluginName + "/menu-phrases-" + l + ".yml")));
    }

    /** hash -> testo tradotto. Il testo sorgente e' li' solo per uso umano, qui non serve. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> loadMenuPhraseCatalog(File f) {
        if (!f.isFile()) {
            return Map.of();
        }
        Map<String, Object> raw = TranslationSync.loadFlatCatalog(f);
        Map<String, String> out = new java.util.HashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (e.getKey().endsWith(".translated") && e.getValue() instanceof String s) {
                String hash = e.getKey().substring(0, e.getKey().length() - ".translated".length());
                out.put(hash, s);
            }
        }
        return out;
    }

    private static String apply(String text, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return text;
        }
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            text = text.replace("{" + e.getKey() + "}", e.getValue());
        }
        return text;
    }

    // ------------------------------------------------------------- GUIDA PER LO STAFF

    private void writeStaffGuide() {
        StaffGuide.create(this, "MagixLanguage — lingua e traduzioni", 65)
                .intro("Decide in che lingua parlare con chi entra (dal paese rilevato tramite l'IP, oppure "
                        + "impostata a mano) e tiene una copia tradotta dei messaggi degli altri plugin Magix, "
                        + "cosi' chi lo desidera puo' mandare risposte gia' nella lingua giusta invece che "
                        + "sempre e solo in italiano.")

                .section("Come si sceglie la lingua",
                        "Al primo ingresso di un account si interroga un servizio GeoIP con l'IP di chi si "
                                + "sta collegando: dal paese restituito si ricava la lingua tramite la mappa "
                                + "country-language del config. Un IP privato, un servizio non raggiunto in "
                                + "tempo, o un paese non elencato: si usa {{cfg:default-language}}.",
                        "La scelta si salva per sempre in players.yml: un ingresso successivo NON la cambia "
                                + "piu' da solo. Per cambiarla c'e' /language set <lingua>, sia per se stessi sia, "
                                + "con magixlanguage.admin, per un altro giocatore.")

                .section("Come funziona la traduzione dei messaggi",
                        "Ad ogni avvio (se translations.sync-on-start e' true) e con /language sync, il plugin "
                                + "legge i file elencati in translations.files (di serie solo messages.yml) di ogni "
                                + "plugin elencato in translations.plugins e ne copia il testo in "
                                + "plugins/MagixLanguage/translations/&lt;Plugin&gt;/it.yml — uno SPECCHIO, non "
                                + "un originale: si cambia nel messages.yml del plugin, mai qui.",
                        "Per ogni altra lingua supportata, ogni chiave NUOVA o il cui testo italiano e' "
                                + "CAMBIATO (anche solo un colore) viene tradotta da SOLA, tramite l'API gratuita "
                                + "di MyMemory (se translations.auto-translate.enabled e' acceso), e scritta in "
                                + "en.yml/es.yml/de.yml: non serve alcun intervento dello staff per avere subito "
                                + "un testo in ogni lingua. Le chiavi rimaste invariate NON vengono ritradotte "
                                + "(una cache interna se ne ricorda).",
                        "Se una traduzione automatica non convince, si corregge mettendo la STESSA chiave in "
                                + "translations/&lt;Plugin&gt;/&lt;lingua&gt;-overrides.yml (creato gia' vuoto, con le "
                                + "istruzioni, al primo avvio): quel file non viene MAI letto ne' toccato dalla "
                                + "sincronizzazione, e vince sempre — anche se il testo italiano cambia di nuovo "
                                + "in seguito. Le chiavi che la traduzione automatica non riesce a tradurre (rete, "
                                + "quota giornaliera esaurita) restano temporaneamente in italiano e finiscono in "
                                + "translations/TRANSLATION-FAILED-&lt;lingua&gt;.txt, rigenerato ad ogni "
                                + "sincronizzazione: si riprova da sola al giro successivo.",
                        "Ad ogni sincronizzazione il log stampa una riga PER PLUGIN (quante chiavi in italiano, "
                                + "quante tradotte in questo giro, quante gia' in cache, quante ancora mancanti), "
                                + "oltre alla riga di riepilogo finale. Lo stesso dato dell'ultima sincronizzazione "
                                + "si vede in gioco con /language status, senza dover leggere la console — utile "
                                + "per sapere a che punto e' rimasto il servizio di traduzione quando ha un limite "
                                + "giornaliero (vedi issue sotto).",
                        "Un altro plugin (softdepend, tramite ServicesManager) chiede il testo gia' tradotto "
                                + "con MagixLanguageAPI.translate(nomePlugin, giocatore, chiave, segnaposti): senza "
                                + "quella chiamata, quel plugin continua a parlare solo in italiano come sempre — "
                                + "MagixLanguage non intercetta i messaggi di nessuno da solo.")

                .section("I menu di MagixMenus: traduzione per FRASE, non per chiave",
                        "plugins/MagixMenus/menus/*.yml non ha chiavi stabili come messages.yml (il nome di "
                                + "un item e' un identificatore tecnico, non una frase): il titolo del menu, il "
                                + "nome/descrizione di ogni item, il corpo e i bottoni delle finestre di dialogo, "
                                + "e il testo dentro message:/broadcast:/title:/actionbar: vengono scanditi e "
                                + "tradotti per il TESTO stesso, non per un percorso. MagixLanguageAPI.translatePhrase "
                                + "riceve la stessa frase italiana e la cerca. Materiali, permessi, equazioni, "
                                + "suoni e nomi di comando non vengono mai toccati.",
                        "I file sono translations/<Plugin>/menu-phrases-<lingua>.yml (generato) e "
                                + "menu-phrases-<lingua>-overrides.yml (correzioni: qui la chiave e' la frase "
                                + "italiana esatta, non un percorso — funziona anche per una frase che la scansione "
                                + "non trova da sola, es. dentro un blocco if/then/else). Le azioni condizionali "
                                + "non vengono scandite in automatico per non dover ricostruire qui la logica di "
                                + "lettura di MagixMenus: si aggiunge la frase a mano nel file overrides se serve.")

                .section("Il sito parla anche lui: MagixWeb ne condivide la quota",
                        "magicadventure.it traduce le proprie pagine (testo, guide comprese) con lo stesso "
                                + "servizio: MagixWeb accoda in un database le frasi che incontra e non ha ancora, "
                                + "e chiede a MagixLanguageAPI.translateRawBatch(...) di tradurle un lotto alla "
                                + "volta (vedi MagixWeb/language/SiteTranslationWorker). E' la STESSA "
                                + "translations.auto-translate.contact-email e la stessa quota giornaliera di "
                                + "MyMemory usata qui sopra per i plugin: se il sito traduce molto in un giorno, "
                                + "resta meno margine per le chiavi dei plugin, e viceversa.",
                        "Chi decide la lingua del visitatore e' il sito (includes/language.php): un giocatore "
                                + "collegato con l'account del sito parte dalla lingua scelta in gioco (sincronizzata "
                                + "in mc_ranks.language ad ogni /language set o rilevazione GeoIP, come il grado), "
                                + "un visitatore senza account sceglie da solo col selettore in pagina o riceve il "
                                + "tentativo migliore dal browser.")

                .detailedCommands()
                .commands()
                .permissions()
                .settings(
                        "default-language", "Lingua usata quando non se ne rileva nessuna.",
                        "geoip.enabled", "Spegnendolo, nessun IP esce verso il servizio GeoIP: tutti partono con default-language.",
                        "geoip.provider-url", "Servizio interrogato per risalire dall'IP al paese; {ip} e' sostituito con l'indirizzo vero.",
                        "geoip.cache-days", "Per quanto un IP gia' interrogato non viene richiesto di nuovo.",
                        "translations.plugins", "I plugin la cui cartella dati viene scandita in cerca di testo da tradurre.",
                        "translations.files", "I nomi dei file, dentro ciascuna di quelle cartelle, che contengono testo per i giocatori.",
                        "translations.auto-translate.enabled", "Se spento, le lingue diverse dall'italiano restano col testo italiano finche' non lo corregge lo staff con un file -overrides.yml.",
                        "translations.auto-translate.contact-email", "Email facoltativa mandata a MyMemory per una quota giornaliera di traduzioni piu' alta.")

                .issue("Un giocatore ha la lingua sbagliata",
                        "Se il paese rilevato dal GeoIP non e' quello vero (VPN, IP aziendale condiviso...) si "
                                + "corregge con /language set <lingua> <giocatore>: da quel momento resta manuale, "
                                + "un ingresso successivo non la tocca piu'.")
                .issue("Ho aggiunto/cambiato un messaggio in un altro plugin e la traduzione non lo vede",
                        "Serve /language sync (o aspettare il prossimo riavvio, se translations.sync-on-start "
                                + "e' acceso): la sincronizzazione non e' automatica ad ogni modifica, solo "
                                + "all'avvio e a comando.")
                .issue("Una traduzione automatica non mi convince",
                        "Si corregge SENZA toccare &lt;lingua&gt;.yml (verrebbe riscritto al prossimo sync): la "
                                + "stessa chiave va messa in translations/&lt;Plugin&gt;/&lt;lingua&gt;-overrides.yml, "
                                + "che vince sempre e non viene mai toccato dalla sincronizzazione.")
                .issue("Le traduzioni sparite dopo aver aggiunto un commento al file lingua",
                        "I file &lt;lingua&gt;.yml in translations/ sono riscritti per intero ad ogni "
                                + "sincronizzazione: eventuali commenti aggiunti a mano non sopravvivono. Per una "
                                + "correzione che resti, va usato il file -overrides.yml (vedi sopra), non "
                                + "&lt;lingua&gt;.yml direttamente.")
                .issue("Tante chiavi restano in italiano dopo una sincronizzazione",
                        "Probabile limite del servizio di traduzione (MyMemory, gratuito): con /language status "
                                + "si vede subito quante ne mancano per ogni plugin, e nel log compare 'rifiutata "
                                + "(risposta HTTP 429)' o 'interrotta la traduzione automatica'. Non serve "
                                + "intervenire: si riprova da sola al prossimo giorno. Per alzare il limite (5000 "
                                + "-> 10000 parole/giorno) si puo' impostare translations.auto-translate.contact-email "
                                + "nel config.")
                .issue("Perche' non riprova subito, a ogni riavvio",
                        "Per disegno: si tenta una traduzione vera al massimo UNA volta al giorno, qualunque cosa "
                                + "succeda nel frattempo (un riavvio dopo un deploy, un /language sync lanciato a "
                                + "mano...). MyMemory non ha solo una quota di parole al giorno: ha anche un limite "
                                + "di frequenza (HTTP 429) che un IP puo' far scattare ripetendo il tentativo troppo "
                                + "spesso, e quel blocco puo' restare attivo ben oltre un giorno — e' successo "
                                + "davvero con una decina di riavvii ravvicinati in poche ore. Lo specchio it.yml e "
                                + "le chiavi gia' tradotte in cache continuano comunque ad aggiornarsi a ogni "
                                + "riavvio: solo le chiamate di rete vere e proprie verso MyMemory si fermano dopo "
                                + "il primo tentativo del giorno (il file last-translation-attempt.txt nella "
                                + "cartella dati tiene la data). Per un tentativo vero prima di mezzanotte, tipico "
                                + "per verificare se un blocco si e' gia' liberato, c'e' /language sync force: "
                                + "ignora il segna-tentativo di oggi e riprova subito su MyMemory (equivale a "
                                + "cancellare a mano last-translation-attempt.txt e poi lanciare /language sync).")
                .issue("Un colore o un placeholder e' sparito da un messaggio tradotto",
                        "Il servizio di traduzione puo' alterare un segnaposto interno (successo davvero: ha "
                                + "tolto una coppia di parentesi da uno, lasciando un residuo tipo &quot;[2]&quot; al "
                                + "posto di un colore). MagixLanguage se ne accorge da solo — scarta quella "
                                + "traduzione invece di mostrarla rotta, e alla sincronizzazione successiva scarta "
                                + "anche una vecchia traduzione gia' in cache che avesse lo stesso problema, "
                                + "ritraducendola — ma serve un nuovo /language sync o riavvio perche' succeda.")
                .issue("Uno spazio manca vicino a &lt;argomento&gt; in una riga tradotta (es. &quot;/login&lt;password&gt;&quot; attaccato)",
                        "MyMemory scambiava &lt;password&gt; per un tag HTML e ne mangiava lo spazio intorno: "
                                + "successo davvero nell'aiuto (/help) di piu' plugin. Da quando Translator protegge "
                                + "anche questi argomenti (come gia' faceva per {player} e i colori) non ricapita "
                                + "nelle traduzioni nuove, e lo stesso rilevamento del problema sopra scarta da solo "
                                + "le vecchie traduzioni in cache con lo spazio mangiato — anche qui serve un nuovo "
                                + "/language sync o riavvio (o /language sync force, per non aspettare) perche' "
                                + "vengano rifatte.")
                .issue("Le frecce «indietro»/«avanti» sono sparite o le scritte sono attaccate al separatore",
                        "help.chrome.back e help.chrome.forward in italiano hanno degli spazi voluti a inizio/fine "
                                + "riga (es. &quot; « indietro &quot;) per staccare la scritta dal punto separatore "
                                + "nel piede dell'aiuto a pagine: un servizio di traduzione tende a togliere gli "
                                + "spazi ai bordi del testo (successo davvero, lasciando &quot;Back·Next&quot; "
                                + "attaccato). Translator ora protegge « e » come un colore, e rimette a posto gli "
                                + "spazi ai bordi da solo dopo aver tradotto il resto; il rilevamento delle cache "
                                + "corrotte confronta anche gli spazi a inizio/fine fra originale e tradotto, quindi "
                                + "una vecchia traduzione senza quel padding viene scartata e rifatta da sola (nuovo "
                                + "/language sync, riavvio, o /language sync force per non aspettare).")

                .never("Non modificare it.yml dentro translations/: viene riscritto ad ogni sincronizzazione. "
                        + "Il testo italiano si cambia nel messages.yml del plugin originale.")
                .never("Non affidarsi al GeoIP per decisioni diverse dalla lingua (es. restrizioni per paese): "
                        + "un servizio esterno gratuito puo' sbagliare o non rispondere, ed e' pensato solo per "
                        + "questo.")
                .write();
    }
}
