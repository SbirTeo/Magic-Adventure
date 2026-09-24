package com.teolo.magixtime.weather;

import com.teolo.magixtime.MagixTime;
import com.teolo.magixtime.season.SeasonDef;
import com.teolo.magixtime.util.Colors;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decide il meteo al posto di Minecraft, con probabilita' e durate che dipendono
 * dalla stagione in corso: piu' pioggia in autunno, temporali brevi d'estate, ecc.
 *
 * Il ciclo vanilla viene spento (doWeatherCycle) e ogni fase - sereno, pioggia,
 * temporale - dura un numero di minuti REALI estratto dai valori della stagione.
 */
public final class WeatherManager {

    /** Fase meteo in corso su un mondo. */
    public record Phase(Type type, long endMillis) {
        public enum Type { CLEAR, RAIN, STORM }
    }

    private final MagixTime plugin;
    private final Map<String, Phase> phases = new HashMap<>();
    /** Fine dell'override esterno (millis), per mondo. */
    private final Map<String, Long> overrideUntil = new HashMap<>();
    private BukkitTask task;

    public WeatherManager(MagixTime plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------- ciclo

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("weather.enabled", true)) return;
        applyGamerules();
        if (plugin.getConfig().getBoolean("weather.persist", true)) loadState();
        long ticks = Math.max(1, plugin.getConfig().getInt("weather.check-interval-seconds", 20)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, ticks);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        overrideUntil.clear();
    }

    public void applyGamerules() {
        if (!plugin.getConfig().getBoolean("weather.manage-gamerule", true)) return;
        for (World w : plugin.managedWorlds()) {
            w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (World w : plugin.managedWorlds()) handleWorld(w, now);
    }

    /**
     * Come per l'ora: un meteo cambiato dall'esterno non viene strappato via
     * all'istante, ma lasciato valere per weather.override-seconds. Scaduto quello,
     * il plugin rimette la fase della stagione.
     */
    private void handleWorld(World w, long now) {
        Phase phase = phases.get(w.getName());
        if (phase == null || now >= phase.endMillis()) {
            roll(w);
            return;
        }
        Long until = overrideUntil.get(w.getName());
        if (until != null) {
            if (now < until) return;           // il cambio esterno vale ancora
            overrideUntil.remove(w.getName());
            apply(w, phase, false);            // ritorno alla normalita'
            return;
        }
        boolean mismatch = w.hasStorm() != (phase.type() != Phase.Type.CLEAR)
                || w.isThundering() != (phase.type() == Phase.Type.STORM);
        if (!mismatch) return;

        int seconds = Math.max(0, plugin.getConfig().getInt("weather.override-seconds", 120));
        if (seconds == 0) {
            apply(w, phase, false);            // 0 = riprendo il controllo subito
            return;
        }
        overrideUntil.put(w.getName(), now + seconds * 1000L);
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("Meteo cambiato dall'esterno nel mondo '" + w.getName()
                    + "': lo lascio valere per " + seconds + "s.");
        }
    }

    /** Estrae la fase successiva secondo le probabilita' della stagione attiva. */
    private void roll(World w) {
        SeasonDef s = plugin.seasons().current();
        Phase previous = phases.get(w.getName());
        Phase.Type type;
        int minutes;

        boolean wasWet = previous != null && previous.type() != Phase.Type.CLEAR;
        if (wasWet) {
            // Dopo la pioggia si torna sempre al sereno: e' il sereno a "tirare i dadi".
            type = Phase.Type.CLEAR;
            minutes = between(s.clearMinMinutes(), s.clearMaxMinutes());
        } else if (s != null && ThreadLocalRandom.current().nextInt(100) < s.rainChance()) {
            type = ThreadLocalRandom.current().nextInt(100) < s.thunderChance()
                    ? Phase.Type.STORM : Phase.Type.RAIN;
            minutes = between(s.rainMinMinutes(), s.rainMaxMinutes());
        } else {
            type = Phase.Type.CLEAR;
            minutes = s == null ? 60 : between(s.clearMinMinutes(), s.clearMaxMinutes());
        }

        Phase phase = new Phase(type, System.currentTimeMillis() + minutes * 60_000L);
        phases.put(w.getName(), phase);
        apply(w, phase, previous == null || previous.type() != type);
    }

    private static int between(int min, int max) {
        return max <= min ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private void apply(World w, Phase phase, boolean announce) {
        boolean storm = phase.type() != Phase.Type.CLEAR;
        boolean thunder = phase.type() == Phase.Type.STORM;
        if (w.hasStorm() != storm) w.setStorm(storm);
        if (w.isThundering() != thunder) w.setThundering(thunder);
        // Con doWeatherCycle spenta il contatore non scorre, ma tenerlo coerente
        // evita numeri assurdi in /weather e nei plugin che lo leggono.
        int remaining = (int) Math.max(20L, (phase.endMillis() - System.currentTimeMillis()) / 50L);
        w.setWeatherDuration(remaining);
        w.setThunderDuration(remaining);

        if (announce && plugin.getConfig().getBoolean("weather.announce", false)) {
            String key = switch (phase.type()) {
                case RAIN -> "announce-rain";
                case STORM -> "announce-storm";
                case CLEAR -> "announce-clear";
            };
            Bukkit.broadcast(Colors.component(plugin.messages().get(key)));
        }
    }

    // ------------------------------------------------------------- comandi / stato

    /** Forza una fase su tutti i mondi gestiti (comando /mtime weather). */
    public void force(Phase.Type type, int minutes) {
        Phase phase = new Phase(type, System.currentTimeMillis() + Math.max(1, minutes) * 60_000L);
        for (World w : plugin.managedWorlds()) {
            phases.put(w.getName(), phase);
            overrideUntil.remove(w.getName()); // il comando del plugin ha sempre la precedenza
            apply(w, phase, false);
        }
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
        for (World w : plugin.managedWorlds()) {
            overrideUntil.remove(w.getName());
            roll(w);
        }
    }

    public Phase phaseOf(World w) {
        return w == null ? null : phases.get(w.getName());
    }

    /** Minuti mancanti al prossimo cambio di fase (0 se non c'e' una fase attiva). */
    public long minutesLeft(World w) {
        Phase p = phaseOf(w);
        if (p == null) return 0;
        return Math.max(0, (p.endMillis() - System.currentTimeMillis()) / 60_000L);
    }

    /** Nome leggibile del meteo attuale, preso da messages.yml. */
    public String describe(World w) {
        Phase p = phaseOf(w);
        if (p == null) {
            if (w != null && w.isThundering()) return plugin.messages().get("weather-storm");
            if (w != null && w.hasStorm()) return plugin.messages().get("weather-rain");
            return plugin.messages().get("weather-clear");
        }
        return switch (p.type()) {
            case RAIN -> plugin.messages().get("weather-rain");
            case STORM -> plugin.messages().get("weather-storm");
            case CLEAR -> plugin.messages().get("weather-clear");
        };
    }

    // ------------------------------------------------------------- persistenza

    private File stateFile() {
        return new File(plugin.getDataFolder(), "data.yml");
    }

    public void saveState() {
        if (!plugin.getConfig().getBoolean("weather.persist", true)) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<String, Phase> e : phases.entrySet()) {
            cfg.set("weather." + e.getKey() + ".type", e.getValue().type().name());
            cfg.set("weather." + e.getKey() + ".end", e.getValue().endMillis());
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
        long now = System.currentTimeMillis();
        for (String world : sec.getKeys(false)) {
            long end = sec.getLong(world + ".end", 0L);
            if (end <= now) continue; // fase gia' scaduta durante lo spegnimento
            try {
                phases.put(world, new Phase(Phase.Type.valueOf(sec.getString(world + ".type", "CLEAR")), end));
            } catch (IllegalArgumentException ignored) {}
        }
    }
}
