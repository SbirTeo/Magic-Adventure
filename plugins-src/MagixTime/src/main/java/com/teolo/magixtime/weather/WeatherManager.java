package com.teolo.magixtime.weather;

import com.teolo.magixtime.MagixTime;
import com.teolo.magixtime.season.SeasonDef;
import com.teolo.magixtime.util.Colors;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decide il meteo al posto di Minecraft, con probabilita' e durate che dipendono
 * dalla stagione in corso: piu' pioggia in autunno, temporali brevi d'estate, ecc.
 *
 * Il ciclo vanilla viene spento (doWeatherCycle) e ogni fase - sereno, pioggia,
 * temporale - dura un numero di minuti REALI estratto dai valori della stagione.
 *
 * La fase e' UNA sola per tutto il server: se piove, piove in tutti i mondi gestiti.
 * Prima ogni mondo tirava i dadi per conto suo, e le dimensioni che condividono il
 * meteo con l'overworld (es. "spawn") si contendevano lo stesso cielo.
 */
public final class WeatherManager {

    /** Fase meteo in corso, uguale per tutti i mondi gestiti. */
    public record Phase(Type type, long endMillis) {
        public enum Type { CLEAR, RAIN, STORM }
    }

    private final MagixTime plugin;
    private Phase phase;
    /** Fine dell'override esterno (millis), per mondo: un /weather esterno tocca un mondo solo. */
    private final Map<String, Long> overrideUntil = new HashMap<>();
    /** Mondi che hanno gia' ricevuto la fase: uno caricato dopo (Multiverse) la prende subito. */
    private final Set<String> applied = new HashSet<>();
    private BukkitTask task;

    public WeatherManager(MagixTime plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------- ciclo

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("weather.enabled", true)) return;
        applyGamerules();
        // Al reload la fase in memoria vale piu' di data.yml, che e' quella dell'ultimo spegnimento.
        if (phase == null && plugin.getConfig().getBoolean("weather.persist", true)) loadState();
        long ticks = Math.max(1, plugin.getConfig().getInt("weather.check-interval-seconds", 20)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, ticks);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        overrideUntil.clear();
        applied.clear();
    }

    public void applyGamerules() {
        if (!plugin.getConfig().getBoolean("weather.manage-gamerule", true)) return;
        for (World w : plugin.managedWorlds()) {
            w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (phase == null || now >= phase.endMillis()) {
            roll();
            return;
        }
        for (World w : plugin.managedWorlds()) handleWorld(w, now);
    }

    /**
     * Come per l'ora: un meteo cambiato dall'esterno non viene strappato via
     * all'istante, ma lasciato valere per weather.override-seconds. Scaduto quello,
     * il plugin rimette la fase della stagione.
     */
    private void handleWorld(World w, long now) {
        if (!applied.contains(w.getName())) {
            apply(w, phase);                   // mondo appena caricato: non e' un cambio esterno
            return;
        }
        Long until = overrideUntil.get(w.getName());
        if (until != null) {
            if (now < until) return;           // il cambio esterno vale ancora
            overrideUntil.remove(w.getName());
            apply(w, phase);                   // ritorno alla normalita'
            return;
        }
        boolean mismatch = w.hasStorm() != (phase.type() != Phase.Type.CLEAR)
                || w.isThundering() != (phase.type() == Phase.Type.STORM);
        if (!mismatch) return;

        int seconds = Math.max(0, plugin.getConfig().getInt("weather.override-seconds", 120));
        if (seconds == 0) {
            apply(w, phase);                   // 0 = riprendo il controllo subito
            return;
        }
        overrideUntil.put(w.getName(), now + seconds * 1000L);
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("Meteo cambiato dall'esterno nel mondo '" + w.getName()
                    + "': lo lascio valere per " + seconds + "s.");
        }
    }

    /** Estrae la fase successiva secondo le probabilita' della stagione attiva e la mette in tutti i mondi. */
    private void roll() {
        SeasonDef s = plugin.seasons().current();
        Phase previous = phase;
        Phase.Type type;
        int minutes;

        boolean wasWet = previous != null && previous.type() != Phase.Type.CLEAR;
        if (wasWet) {
            // Dopo la pioggia si torna sempre al sereno: e' il sereno a "tirare i dadi".
            type = Phase.Type.CLEAR;
            minutes = s == null ? 60 : between(s.clearMinMinutes(), s.clearMaxMinutes());
        } else if (s != null && ThreadLocalRandom.current().nextInt(100) < s.rainChance()) {
            type = ThreadLocalRandom.current().nextInt(100) < s.thunderChance()
                    ? Phase.Type.STORM : Phase.Type.RAIN;
            minutes = between(s.rainMinMinutes(), s.rainMaxMinutes());
        } else {
            type = Phase.Type.CLEAR;
            minutes = s == null ? 60 : between(s.clearMinMinutes(), s.clearMaxMinutes());
        }

        phase = new Phase(type, System.currentTimeMillis() + minutes * 60_000L);
        overrideUntil.clear(); // la fase nuova vale ovunque, anche dove c'era un cambio esterno
        applyAll();
        if (previous == null || previous.type() != type) announce(type);
    }

    private void applyAll() {
        for (World w : plugin.managedWorlds()) apply(w, phase);
    }

    private static int between(int min, int max) {
        return max <= min ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private void apply(World w, Phase phase) {
        applied.add(w.getName());
        boolean storm = phase.type() != Phase.Type.CLEAR;
        boolean thunder = phase.type() == Phase.Type.STORM;
        if (w.hasStorm() != storm) w.setStorm(storm);
        if (w.isThundering() != thunder) w.setThundering(thunder);
        // Con doWeatherCycle spenta il contatore non scorre, ma tenerlo coerente
        // evita numeri assurdi in /weather e nei plugin che lo leggono.
        int remaining = (int) Math.max(20L, (phase.endMillis() - System.currentTimeMillis()) / 50L);
        w.setWeatherDuration(remaining);
        w.setThunderDuration(remaining);
    }

    /** Annuncio del cambio di fase: uno solo, non uno per mondo. */
    private void announce(Phase.Type type) {
        if (plugin.getConfig().getBoolean("weather.announce", false)) {
            String key = switch (type) {
                case RAIN -> "announce-rain";
                case STORM -> "announce-storm";
                case CLEAR -> "announce-clear";
            };
            // a ciascuno nella sua lingua
            for (Player p : Bukkit.getOnlinePlayers()) plugin.messages().send(p, key);
            plugin.messages().send(Bukkit.getConsoleSender(), key);
        }
    }

    // ------------------------------------------------------------- comandi / stato

    /** Forza una fase su tutti i mondi gestiti (comando /mtime weather). */
    public void force(Phase.Type type, int minutes) {
        phase = new Phase(type, System.currentTimeMillis() + Math.max(1, minutes) * 60_000L);
        overrideUntil.clear(); // il comando del plugin ha sempre la precedenza
        applyAll();
    }

    /** Secondi mancanti alla fine dell'override esterno sul mondo (0 = nessun override). */
    public long overrideLeft(World w) {
        if (w == null) return 0;
        Long until = overrideUntil.get(w.getName());
        if (until == null) return 0;
        return Math.max(0, (until - System.currentTimeMillis()) / 1000L);
    }

    /** Ricalcola subito il meteo: usato quando cambia la stagione. */
    public void rerollAll() {
        if (!plugin.getConfig().getBoolean("weather.enabled", true)) return;
        roll();
    }

    /** Fase del mondo: quella del server se il mondo e' gestito, altrimenti nessuna. */
    public Phase phaseOf(World w) {
        return plugin.isManaged(w) ? phase : null;
    }

    /** Minuti mancanti al prossimo cambio di fase (0 se non c'e' una fase attiva). */
    public long minutesLeft(World w) {
        Phase p = phaseOf(w);
        if (p == null) return 0;
        return Math.max(0, (p.endMillis() - System.currentTimeMillis()) / 60_000L);
    }

    /** Nome leggibile del meteo attuale, preso da messages.yml. */
    public String describe(World w) {
        return describe(w, null);
    }

    /** Come {@link #describe(World)}, nella lingua di chi legge (null = italiano). */
    public String describe(World w, org.bukkit.command.CommandSender to) {
        Phase p = phaseOf(w);
        if (p == null) {
            if (w != null && w.isThundering()) return plugin.messages().forPlayer(to, "weather-storm");
            if (w != null && w.hasStorm()) return plugin.messages().forPlayer(to, "weather-rain");
            return plugin.messages().forPlayer(to, "weather-clear");
        }
        return switch (p.type()) {
            case RAIN -> plugin.messages().forPlayer(to, "weather-rain");
            case STORM -> plugin.messages().forPlayer(to, "weather-storm");
            case CLEAR -> plugin.messages().forPlayer(to, "weather-clear");
        };
    }

    // ------------------------------------------------------------- persistenza

    private File stateFile() {
        return new File(plugin.getDataFolder(), "data.yml");
    }

    public void saveState() {
        if (!plugin.getConfig().getBoolean("weather.persist", true)) return;
        YamlConfiguration cfg = new YamlConfiguration();
        if (phase != null) {
            cfg.set("weather.type", phase.type().name());
            cfg.set("weather.end", phase.endMillis());
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(stateFile());
        } catch (Exception e) {
            plugin.getLogger().warning("Impossibile salvare data.yml: " + e.getMessage());
        }
    }

    private void loadState() {
        File f = stateFile();
        if (!f.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        var sec = cfg.getConfigurationSection("weather");
        if (sec == null) return;
        if (sec.contains("type")) {
            phase = readPhase(sec);
            return;
        }
        // Formato vecchio (fino alla 0.4.14): una fase per mondo. Si riprende quella
        // del mondo principale, o la prima ancora valida.
        String main = Bukkit.getWorlds().isEmpty() ? "" : Bukkit.getWorlds().get(0).getName();
        if (sec.isConfigurationSection(main)) phase = readPhase(sec.getConfigurationSection(main));
        for (String world : sec.getKeys(false)) {
            if (phase != null) break;
            if (sec.isConfigurationSection(world)) phase = readPhase(sec.getConfigurationSection(world));
        }
    }

    /** Fase salvata, o null se e' gia' scaduta durante lo spegnimento o illeggibile. */
    private static Phase readPhase(org.bukkit.configuration.ConfigurationSection sec) {
        long end = sec.getLong("end", 0L);
        if (end <= System.currentTimeMillis()) return null;
        try {
            return new Phase(Phase.Type.valueOf(sec.getString("type", "CLEAR")), end);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
