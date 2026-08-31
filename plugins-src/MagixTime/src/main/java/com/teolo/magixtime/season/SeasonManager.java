package com.teolo.magixtime.season;

import com.teolo.magixtime.MagixTime;
import com.teolo.magixtime.util.Colors;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Calcola la stagione a partire dalla data reale.
 *
 * Le date d'inizio arrivano dal preset scelto (astronomico/meteorologico) oppure,
 * in modalita' "custom", dal campo "start" di ogni stagione. Nell'emisfero sud le
 * date vengono spostate di sei mesi, cosi' a dicembre e' estate.
 */
public final class SeasonManager {

    /** Date d'inizio dei preset, per chiave di stagione. */
    private static final Map<String, MonthDay> ASTRONOMICAL = Map.of(
            "spring", MonthDay.of(3, 20),
            "summer", MonthDay.of(6, 21),
            "autumn", MonthDay.of(9, 22),
            "winter", MonthDay.of(12, 21));
    private static final Map<String, MonthDay> METEOROLOGICAL = Map.of(
            "spring", MonthDay.of(3, 1),
            "summer", MonthDay.of(6, 1),
            "autumn", MonthDay.of(9, 1),
            "winter", MonthDay.of(12, 1));

    private final MagixTime plugin;
    private final Consumer<SeasonDef> onChange;

    private final Map<String, SeasonDef> seasons = new LinkedHashMap<>();
    /** Stagioni ordinate per data d'inizio (gia' corrette per l'emisfero). */
    private final List<SeasonDef> ordered = new ArrayList<>();
    private final Map<String, MonthDay> starts = new LinkedHashMap<>();

    private boolean enabled;
    private boolean south;
    private SeasonDef current;
    private String forcedKey;
    private BukkitTask task;

    public SeasonManager(MagixTime plugin, Consumer<SeasonDef> onChange) {
        this.plugin = plugin;
        this.onChange = onChange;
        load();
    }

    // ------------------------------------------------------------- caricamento

    public void load() {
        seasons.clear();
        ordered.clear();
        starts.clear();

        ConfigurationSection root = plugin.getConfig().getConfigurationSection("seasons");
        enabled = root != null && root.getBoolean("enabled", true);
        south = root != null && "south".equalsIgnoreCase(root.getString("hemisphere", "north"));
        String mode = root == null ? "astronomical" : root.getString("mode", "astronomical").toLowerCase(Locale.ROOT);

        ConfigurationSection list = root == null ? null : root.getConfigurationSection("list");
        if (list == null) {
            plugin.getLogger().warning("seasons.list assente in config.yml: stagioni disattivate.");
            enabled = false;
            return;
        }

        Map<String, MonthDay> preset = switch (mode) {
            case "meteorological" -> METEOROLOGICAL;
            case "custom" -> Map.of();
            default -> ASTRONOMICAL;
        };

        for (String key : list.getKeys(false)) {
            ConfigurationSection sec = list.getConfigurationSection(key);
            if (sec == null) continue;
            SeasonDef def = new SeasonDef(key, sec);
            seasons.put(key.toLowerCase(Locale.ROOT), def);
            // In modalita' preset le stagioni non riconosciute (aggiunte a mano)
            // ricadono comunque sul loro "start": cosi' il config resta valido.
            MonthDay start = preset.getOrDefault(key.toLowerCase(Locale.ROOT), def.customStart());
            starts.put(key.toLowerCase(Locale.ROOT), south ? shiftSixMonths(start) : start);
        }

        ordered.addAll(seasons.values());
        ordered.sort((a, b) -> starts.get(a.key().toLowerCase(Locale.ROOT))
                .compareTo(starts.get(b.key().toLowerCase(Locale.ROOT))));

        if (ordered.isEmpty()) {
            plugin.getLogger().warning("Nessuna stagione valida in seasons.list: stagioni disattivate.");
            enabled = false;
            return;
        }
        current = computeFor(plugin.today());
    }

    /** L'emisfero australe vive le stesse date sfasate di sei mesi. */
    private static MonthDay shiftSixMonths(MonthDay md) {
        // Anno bisestile come riferimento, cosi' il 29 febbraio resta valido.
        return MonthDay.from(md.atYear(2024).plusMonths(6));
    }

    // ------------------------------------------------------------- ciclo

    public void start() {
        stop();
        if (!enabled) return;
        long ticks = Math.max(1, plugin.getConfig().getInt("seasons.check-interval-seconds", 60)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, ticks, ticks);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    private void check() {
        if (!enabled || forcedKey != null) return;
        SeasonDef now = computeFor(plugin.today());
        if (now == null || now.equals(current)) return;
        SeasonDef previous = current;
        current = now;
        announce(now, previous);
        onChange.accept(now);
    }

    private void announce(SeasonDef now, SeasonDef previous) {
        String prev = previous == null ? "" : previous.display();
        if (plugin.getConfig().getBoolean("seasons.announce", true)) {
            Bukkit.broadcast(Colors.component(
                    plugin.messages().prefix() + plugin.messages().get("season-change",
                            "season", now.display(), "prev", prev)));
        }
        boolean title = plugin.getConfig().getBoolean("seasons.announce-title", true);
        // Chiave del suono in forma namespaced ("block.note_block.chime"): l'overload a
        // stringa non dipende dall'elenco dei suoni compilato nell'API.
        String sound = plugin.getConfig().getString("seasons.announce-sound", "");
        boolean hasSound = sound != null && !sound.isBlank();
        if (!title && !hasSound) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (title) {
                p.showTitle(Title.title(
                        plugin.messages().component("season-change-title", "season", now.display(), "prev", prev),
                        plugin.messages().component("season-change-subtitle", "season", now.display(), "prev", prev),
                        Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1))));
            }
            if (hasSound) p.playSound(p.getLocation(), sound.trim(), 1f, 1f);
        }
    }

    // ------------------------------------------------------------- calcolo

    /** Stagione corrispondente a una data reale, secondo le date d'inizio caricate. */
    public SeasonDef computeFor(LocalDate date) {
        if (ordered.isEmpty()) return null;
        MonthDay md = MonthDay.from(date);
        SeasonDef found = null;
        for (SeasonDef def : ordered) {
            if (startOf(def).compareTo(md) <= 0) found = def;
        }
        // Prima della prima data dell'anno si e' ancora nell'ultima stagione (a cavallo di capodanno).
        return found != null ? found : ordered.get(ordered.size() - 1);
    }

    private MonthDay startOf(SeasonDef def) {
        return starts.get(def.key().toLowerCase(Locale.ROOT));
    }

    /** Stagione attiva: quella forzata dallo staff, altrimenti quella della data odierna. */
    public SeasonDef current() {
        if (forcedKey != null) {
            SeasonDef forced = seasons.get(forcedKey);
            if (forced != null) return forced;
        }
        if (current == null) current = computeFor(plugin.today());
        return current;
    }

    /** Stagione che seguira' quella attiva secondo il calendario reale. */
    public SeasonDef next() {
        if (ordered.isEmpty()) return null;
        SeasonDef now = computeFor(plugin.today());
        int i = ordered.indexOf(now);
        return ordered.get((i + 1) % ordered.size());
    }

    /** Giorni reali che mancano all'inizio della prossima stagione. */
    public long daysToNext() {
        SeasonDef next = next();
        if (next == null) return 0;
        LocalDate today = plugin.today();
        MonthDay start = startOf(next);
        LocalDate target = atYearSafe(start, today.getYear());
        if (!target.isAfter(today)) target = atYearSafe(start, today.getYear() + 1);
        return ChronoUnit.DAYS.between(today, target);
    }

    /** 29 febbraio in un anno non bisestile diventa 28: evita l'eccezione. */
    private static LocalDate atYearSafe(MonthDay md, int year) {
        if (md.getMonthValue() == 2 && md.getDayOfMonth() == 29 && !LocalDate.of(year, 1, 1).isLeapYear()) {
            return LocalDate.of(year, 2, 28);
        }
        return md.atYear(year);
    }

    // ------------------------------------------------------------- override staff

    /** Forza una stagione (null = torna automatica). Ritorna false se la chiave non esiste. */
    public boolean force(String key) {
        if (key == null) {
            forcedKey = null;
            current = computeFor(plugin.today());
            onChange.accept(current);
            return true;
        }
        String k = key.toLowerCase(Locale.ROOT);
        if (!seasons.containsKey(k)) return false;
        forcedKey = k;
        current = seasons.get(k);
        onChange.accept(current);
        return true;
    }

    public boolean isForced() { return forcedKey != null; }
    public boolean isEnabled() { return enabled; }
    public List<String> keys() { return new ArrayList<>(seasons.keySet()); }

    /** Serve al comando /mtime info: la data reale usata per il calcolo. */
    public ZonedDateTime nowInZone() { return plugin.nowInZone(); }
}
