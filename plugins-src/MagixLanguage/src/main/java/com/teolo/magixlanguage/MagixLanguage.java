package com.teolo.magixlanguage;

import com.teolo.magixlanguage.api.MagixLanguageAPI;
import com.teolo.magixlanguage.command.MagixLanguageCommand;
import com.teolo.magixlanguage.geo.GeoLookup;
import com.teolo.magixlanguage.hook.MagixLanguagePlaceholders;
import com.teolo.magixlanguage.lang.Messages;
import com.teolo.magixlanguage.listener.LoginListener;
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
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> lastSyncResult = new TranslationSync(this).run());
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
        catalogCache.clear();
        getLogger().info("Configurazione ricaricata.");
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
                        "Probabile limite giornaliero del servizio di traduzione (MyMemory, gratuito): con "
                                + "/language status si vede subito quante ne mancano per ogni plugin, e nel log "
                                + "compare 'rifiutata (risposta HTTP 429)' o 'interrotta la traduzione automatica'. "
                                + "Non serve intervenire: si riprova da sola ai prossimi riavvii/sync, quando la "
                                + "quota si libera. Per alzare il limite (5000 -> 10000 parole/giorno) si puo' "
                                + "impostare translations.auto-translate.contact-email nel config.")

                .never("Non modificare it.yml dentro translations/: viene riscritto ad ogni sincronizzazione. "
                        + "Il testo italiano si cambia nel messages.yml del plugin originale.")
                .never("Non affidarsi al GeoIP per decisioni diverse dalla lingua (es. restrizioni per paese): "
                        + "un servizio esterno gratuito puo' sbagliare o non rispondere, ed e' pensato solo per "
                        + "questo.")
                .write();
    }
}
