package com.teolo.magixtime;

import com.teolo.magixtime.command.MagixTimeCommand;
import com.teolo.magixtime.hook.MagixTimePlaceholders;
import com.teolo.magixtime.lang.Messages;
import com.teolo.magixtime.listener.CommandBlockListener;
import com.teolo.magixtime.listener.SleepListener;
import com.teolo.magixtime.season.SeasonManager;
import com.teolo.magixtime.snow.SnowManager;
import com.teolo.magixtime.time.TimeSync;
import com.teolo.magixtime.util.GuidaStaff;
import com.teolo.magixtime.weather.WeatherManager;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MagixTime - l'ora e la stagione di Minecraft seguono il mondo reale.
 *
 * Tre moduli indipendenti, tutti disattivabili dal config:
 *   - ora:      l'orologio del mondo segue l'ora reale del fuso configurato;
 *   - stagioni: la stagione deriva dalla data reale (equinozi o date scelte);
 *   - meteo:    pioggia, temporali e accumulo di neve dipendono dalla stagione.
 */
public final class MagixTime extends JavaPlugin {

    private Messages messages;
    private TimeSync time;
    private SeasonManager seasons;
    private WeatherManager weather;
    private SnowManager snow;
    private CommandBlockListener commandBlock;

    private ZoneId zone;
    private final Set<String> excluded = new HashSet<>();
    private List<String> worldNames = List.of("*");
    private boolean allNormalWorlds = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();
        // Puro I/O su file: non deve bloccare il tick di avvio.
        // Il README nella cartella del plugin non si copia piu' dal jar: lo genera
        // GuidaStaff insieme al capitolo per il sito, cosi' i due non possono divergere.
        // Il capitolo della guida per amministratori sul sito. Va scritto DOPO che il config
        // e' stato caricato, perche' ci mette dentro i valori davvero in uso.
        Bukkit.getScheduler().runTaskAsynchronously(this, this::scriviGuidaStaff);

        messages = new Messages(this);
        readWorldSettings();

        time = new TimeSync(this);
        weather = new WeatherManager(this);
        snow = new SnowManager(this);
        // Al cambio di stagione il meteo va ritirato subito, altrimenti la fase in
        // corso continuerebbe con le probabilita' della stagione precedente.
        seasons = new SeasonManager(this, season -> weather.rerollAll());

        PluginCommand cmd = getCommand("magixtime");
        if (cmd != null) {
            MagixTimeCommand executor = new MagixTimeCommand(this, messages);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
        getServer().getPluginManager().registerEvents(new SleepListener(this), this);
        commandBlock = new CommandBlockListener(this);
        getServer().getPluginManager().registerEvents(commandBlock, this);

        startModules();
        registerPlaceholders();

        getLogger().info("Avviato: fuso " + zone.getId() + ", stagione "
                + (seasons.current() != null ? seasons.current().key() : "n/d")
                + ", mondi gestiti: " + managedWorlds().size() + ".");
    }

    @Override
    public void onDisable() {
        if (weather != null) { weather.saveState(); weather.stop(); }
        if (time != null) time.stop();
        if (seasons != null) seasons.stop();
        if (snow != null) snow.stop();
        // Senza questo un server avviato senza MagixTime resterebbe con il tempo
        // e il meteo congelati, perche' le gamerule vivono nel level.dat.
        if (getConfig().getBoolean("restore-gamerules-on-disable", true)) {
            for (World w : managedWorlds()) {
                w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
                w.setGameRule(GameRule.DO_WEATHER_CYCLE, true);
            }
        }
    }

    // ------------------------------------------------------------- avvio moduli

    private void startModules() {
        time.start();
        seasons.start();
        weather.start();
        snow.start();
    }

    /** Ricarica config.yml e messages.yml e fa ripartire tutti i moduli (/mtime reload). */
    public void reloadEverything() {
        reloadConfig();
        messages.reload();
        readWorldSettings();
        if (commandBlock != null) commandBlock.load();

        time.stop();
        seasons.stop();
        weather.stop();
        snow.stop();

        seasons.load();
        startModules();
        time.sync();
        getLogger().info("Configurazione ricaricata: fuso " + zone.getId()
                + ", mondi gestiti: " + managedWorlds().size() + ".");
    }

    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        try {
            new MagixTimePlaceholders(this).register();
            getLogger().info("Placeholder %magixtime_...% registrati su PlaceholderAPI.");
        } catch (Throwable t) {
            getLogger().warning("Registrazione dei placeholder fallita: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------- mondi e fuso

    private void readWorldSettings() {
        String tz = getConfig().getString("time.timezone", "system");
        if (tz == null || tz.isBlank() || tz.equalsIgnoreCase("system")) {
            zone = ZoneId.systemDefault();
        } else {
            try {
                zone = ZoneId.of(tz.trim());
            } catch (Exception e) {
                getLogger().warning("Fuso orario non valido in time.timezone: '" + tz
                        + "'. Uso quello di sistema (" + ZoneId.systemDefault().getId() + ").");
                zone = ZoneId.systemDefault();
            }
        }

        worldNames = getConfig().getStringList("worlds");
        if (worldNames.isEmpty()) worldNames = List.of("*");
        allNormalWorlds = worldNames.stream().anyMatch(s -> s.equals("*"));

        excluded.clear();
        for (String s : getConfig().getStringList("excluded-worlds")) {
            excluded.add(s.toLowerCase(Locale.ROOT));
        }
    }

    /** Mondi su cui il plugin agisce, ricalcolati ogni volta (i mondi possono essere caricati a runtime). */
    public List<World> managedWorlds() {
        List<World> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            if (isManaged(w)) out.add(w);
        }
        return out;
    }

    public boolean isManaged(World w) {
        if (w == null) return false;
        if (excluded.contains(w.getName().toLowerCase(Locale.ROOT))) return false;
        // Nether ed End non hanno ciclo giorno/notte ne' meteo: non c'e' niente da sincronizzare.
        if (w.getEnvironment() != World.Environment.NORMAL) return false;
        if (allNormalWorlds) return true;
        return worldNames.stream().anyMatch(n -> n.equalsIgnoreCase(w.getName()));
    }

    /** Adesso, nel fuso configurato e con l'offset di time.offset-minutes gia' applicato. */
    public ZonedDateTime nowInZone() {
        return ZonedDateTime.now(zone).plusMinutes(getConfig().getInt("time.offset-minutes", 0));
    }

    /** Data reale usata per il calcolo della stagione. */
    public LocalDate today() {
        return nowInZone().toLocalDate();
    }

    public ZoneId zone() { return zone; }
    public Messages messages() { return messages; }
    public TimeSync time() { return time; }
    public SeasonManager seasons() { return seasons; }
    public WeatherManager weather() { return weather; }
    public SnowManager snow() { return snow; }

    // ------------------------------------------------- GUIDA PER LO STAFF

    /**
     * Capitolo di MagixTime nella guida del gestionale. Comandi, permessi e valori di
     * configurazione non si ricopiano: li legge da solo. Vedi plugins-src/GUIDA-STAFF.md.
     */
    private void scriviGuidaStaff() {
        GuidaStaff.crea(this, "MagixTime — ora, stagioni e meteo reali", 60)
                // Numeri presi dal config vero: cambiando una chiave, questo capitolo
                // sulla guida del gestionale cambia da solo (vedi util/ValoriConfig).
                .valori(new com.teolo.magixtime.util.ValoriConfig(this))
                .intro("Fa scorrere l'ora di Minecraft insieme a quella vera e cambia le stagioni col "
                        + "calendario: d'inverno nevica, d'estate no. Chi gioca la sera trova notte anche "
                        + "nel gioco.")

                .sezione("L'ora",
                        "L'orologio dei mondi gestiti viene riscritto di continuo a partire dall'ora vera del "
                                + "fuso indicato nel config. Il VPS lavora in UTC: se il fuso non è Europe/Rome il "
                                + "server è indietro di un'ora o due rispetto a chi gioca, ed è l'errore più facile "
                                + "da fare.",
                        "Per riuscirci il plugin tiene la gamerule del ciclo giorno/notte sotto controllo: quando "
                                + "viene spento, la rimette com'era, altrimenti un server avviato senza MagixTime "
                                + "resterebbe con il tempo congelato — la gamerule vive nel level.dat, non nel plugin.",
                        "Il Nether e l'End non hanno ciclo giorno/notte né meteo: sono esclusi in partenza, non è "
                                + "un difetto.")

                .sezione("Le stagioni",
                        "La stagione si ricava dalla data reale. In modo astronomico segue gli equinozi e i "
                                + "solstizi veri; l'emisfero decide quali mesi sono estate e quali inverno.",
                        "Al cambio di stagione, se configurato, parte un annuncio con titolo e suono. E il meteo "
                                + "in corso viene ritirato subito: altrimenti continuerebbe con le probabilità della "
                                + "stagione appena finita, e si vedrebbe un temporale estivo il primo giorno "
                                + "d'inverno.")

                .sezione("Meteo e neve",
                        "Pioggia, temporali e durata delle fasi dipendono dalla stagione in corso. La neve segue "
                                + "la stagione E il bioma: in un bioma caldo non nevica nemmeno a gennaio, ed è "
                                + "corretto così.",
                        "Lo stato del meteo viene salvato allo spegnimento, quindi un riavvio non azzera la fase "
                                + "in corso.")

                .sezione("Perché i comandi di CMI «non funzionano»",
                        "/time set e /weather di CMI funzionano eccome, ma dopo {{cfg:time.update-interval-ticks}} tick "
                                + "(pochi secondi) MagixTime rimette "
                                + "le cose a posto: è il suo mestiere. Per un cambio che resta serve il permesso "
                                + "magixtime.bypass, oppure /mtime pause per fermare l'allineamento e /mtime resume "
                                + "per farlo ripartire.",
                        "È la segnalazione che arriva più spesso dallo staff nuovo: non è un guasto, è la ragione "
                                + "per cui il plugin esiste.")

                .comandiDettagliati()
                .comandi()
                .permessi()
                .impostazioni(
                        "time.timezone", "Il fuso su cui si allinea l'ora. Il VPS è in UTC: qui ci va Europe/Rome.",
                        "time.enabled", "Spegnendolo, l'ora torna a scorrere come in un server normale.",
                        "time.update-interval-ticks", "Ogni quanti tick si riscrive l'ora dei mondi.",
                        "seasons.mode", "Come si decidono le stagioni: astronomical segue gli equinozi veri.",
                        "seasons.hemisphere", "Emisfero: cambia quali mesi sono estate e quali inverno.",
                        "weather.enabled", "Spegnendolo, pioggia e neve tornano quelle di Minecraft.")

                .guasto("L'ora in gioco non segue quella vera",
                        "Controlla che il mondo non sia fra quelli esclusi e che il modulo ora sia acceso. "
                                + "/mtime worlds mostra lo stato reale di ogni mondo gestito.")
                .guasto("/time e /weather sembrano non fare effetto",
                        "È voluto: il plugin riallinea tutto dopo pochi secondi. Serve magixtime.bypass, "
                                + "oppure /mtime pause.")
                .guasto("Non nevica dove dovrebbe",
                        "La neve segue stagione E bioma. In un bioma caldo non nevica in nessuna stagione.")
                .guasto("Dopo aver tolto il plugin il tempo resta fermo",
                        "Le gamerule vivono nel mondo: vanno rimesse a mano se il plugin non è stato spento "
                                + "in modo pulito.")

                .mai("Non toccare la gamerule doDaylightCycle a mano mentre il plugin gira: la rimette come vuole lui.")
                .mai("Non cambiare il fuso pensando che il VPS sia in Italia: il VPS è in UTC.")
                .scrivi();
    }

    // ------------------------------------------------------------- README

    /** Riscrive plugins/MagixTime/README.md ad ogni avvio (README = unica fonte, dentro il jar). */
    private void writeReadme() {
        try (InputStream in = getResource("README.md")) {
            if (in == null) {
                getLogger().warning("README.md non incluso nel jar: README runtime non generato.");
                return;
            }
            Path out = new File(getDataFolder(), "README.md").toPath();
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            getLogger().info("README aggiornato (v" + getPluginMeta().getVersion() + ").");
        } catch (Exception e) {
            getLogger().warning("Impossibile scrivere il README: " + e.getMessage());
        }
    }
}
