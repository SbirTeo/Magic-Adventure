package com.teolo.magixscoreboard.board;

import com.teolo.magixscoreboard.hook.Papi;
import com.teolo.magixscoreboard.hook.WorldGuardHook;
import com.teolo.magixscoreboard.model.BoardDefinition;
import com.teolo.magixscoreboard.model.BoardLine;
import com.teolo.magixscoreboard.util.Colors;
import com.teolo.magixscoreboard.util.Marquee;
import com.teolo.magixscoreboard.util.SidebarMark;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Il cuore del plugin: legge le scoreboard dal config, sceglie quella giusta per ogni giocatore
 * online (peso, poi specificita' a parita' di peso, vedi {@link BoardDefinition#specificity}) e la
 * ridisegna a ritmo regolare, facendo avanzare le animazioni.
 */
public final class BoardManager {

    private final JavaPlugin plugin;
    private final WorldGuardHook worldGuard;
    private final Map<UUID, PlayerBoard> boards = new ConcurrentHashMap<>();
    private final HiddenPlayers hiddenPlayers;

    private List<BoardDefinition> definitions = List.of();
    private List<String> priorityOrder = List.of("region", "permission", "world", "placeholder");
    private int updateIntervalTicks = 10;
    private boolean enabled = true;
    /** sidebar-position.offset-y != 0: le scritte vanno marcate (SidebarMark) perche' lo shader le sposti. */
    private boolean markText = false;
    private boolean warnedRegionsWithoutWorldGuard = false;

    private BukkitTask task;
    private long tick = 0;
    /** Passo dell'orologio: MCD fra update-interval-ticks e gli interval-ticks delle righe animate
     *  (vedi {@link #clockPeriod}). */
    private int clockPeriod = 10;

    public BoardManager(JavaPlugin plugin, WorldGuardHook worldGuard) {
        this.plugin = plugin;
        this.worldGuard = worldGuard;
        this.hiddenPlayers = new HiddenPlayers(plugin);
        load();
    }

    /** Rilegge config.yml (chiamare DOPO ConfigAlign.alignAll + reloadConfig) e riparte pulito. */
    public void reload() {
        stop();
        load();
        start();
    }

    private void load() {
        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfig();
        enabled = cfg.getBoolean("enabled", true);
        markText = cfg.getDouble("sidebar-position.offset-y", 0) != 0;
        updateIntervalTicks = Math.max(1, cfg.getInt("update-interval-ticks", 10));
        List<String> order = cfg.getStringList("priority-order");
        priorityOrder = order.isEmpty() ? List.of("region", "permission", "world", "placeholder") : List.copyOf(order);

        List<BoardDefinition> out = new ArrayList<>();
        ConfigurationSection root = cfg.getConfigurationSection("scoreboards");
        boolean anyRegionCondition = false;
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) continue;
                BoardDefinition def = readBoard(id, section);
                out.add(def);
                if (!def.regions().isEmpty()) anyRegionCondition = true;
            }
        }
        definitions = List.copyOf(out);
        clockPeriod = clockPeriod(updateIntervalTicks, definitions);

        if (anyRegionCondition && !worldGuard.enabled() && !warnedRegionsWithoutWorldGuard) {
            warnedRegionsWithoutWorldGuard = true;
            plugin.getLogger().warning("Una o più scoreboard hanno condizioni \"regions\" ma WorldGuard"
                    + " non è installato: quelle condizioni non corrisponderanno mai.");
        }
    }

    private static BoardDefinition readBoard(String id, ConfigurationSection section) {
        boolean sectionEnabled = section.getBoolean("enabled", true);
        int weight = section.getInt("weight", 0);
        String permission = section.getString("permission", "");
        Set<String> worlds = lowercaseSet(section.getStringList("worlds"));
        Set<String> regions = lowercaseSet(section.getStringList("regions"));
        List<String> placeholders = section.getStringList("placeholders");
        boolean placeholdersMatchAny = section.getString("placeholders-mode", "all").equalsIgnoreCase("any");
        BoardLine title = readLine(section.getConfigurationSection("title"));
        List<BoardLine> lines = new ArrayList<>();
        List<?> raw = section.getList("lines");
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof Map<?, ?> m) lines.add(readLine(m));
            }
        }
        return new BoardDefinition(id, sectionEnabled, weight, permission, worlds, regions, placeholders,
                placeholdersMatchAny, title, lines);
    }

    private static BoardLine readLine(ConfigurationSection section) {
        if (section == null) return new BoardLine(20, List.of(""));
        int interval = Math.max(1, section.getInt("interval-ticks", 20));
        List<String> frames = section.getStringList("frames");
        ConfigurationSection sc = section.getConfigurationSection("scroll");
        BoardLine.Scroll scroll = sc == null ? null
                : scroll(sc.getInt("width", 24), sc.getInt("gap", 6), sc.getInt("speed-ticks", 2));
        return new BoardLine(interval, frames.isEmpty() ? List.of("") : frames, scroll);
    }

    /** Uno scorrimento valido, o null se la larghezza e' 0 (= niente scorrimento). */
    private static BoardLine.Scroll scroll(int width, int gap, int speedTicks) {
        if (width <= 0) return null;
        return new BoardLine.Scroll(width, Math.max(0, gap), Math.max(1, speedTicks));
    }

    private static int intOr(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    /** Una riga dentro l'elenco "lines" (letta come mappa: YAML non la vede come sotto-sezione). */
    private static BoardLine readLine(Map<?, ?> map) {
        int interval = 20;
        Object rawInterval = map.get("interval-ticks");
        if (rawInterval instanceof Number n) interval = Math.max(1, n.intValue());
        List<String> frames = new ArrayList<>();
        Object rawFrames = map.get("frames");
        if (rawFrames instanceof List<?> list) {
            for (Object f : list) frames.add(String.valueOf(f));
        }
        BoardLine.Scroll scroll = null;
        if (map.get("scroll") instanceof Map<?, ?> sc) {
            scroll = scroll(intOr(sc.get("width"), 24), intOr(sc.get("gap"), 6), intOr(sc.get("speed-ticks"), 2));
        }
        return new BoardLine(interval, frames.isEmpty() ? List.of("") : frames, scroll);
    }

    private static Set<String> lowercaseSet(List<String> values) {
        Set<String> out = new HashSet<>();
        for (String v : values) out.add(v.toLowerCase(Locale.ROOT));
        return out;
    }

    // ------------------------------------------------------------- ciclo di aggiornamento

    /**
     * Il passo dell'orologio. Prima era update-interval-ticks secco: con una riga animata piu' veloce
     * (es. interval-ticks 5 con update 10) il contatore saltava 10, 20, 30... e (tick / 5) % 2 valeva
     * sempre 0 — l'animazione restava ferma sul primo frame. Con l'MCD di tutti gli intervalli ogni
     * cambio di frame cade esattamente su un giro dell'orologio.
     */
    private static int clockPeriod(int updateTicks, List<BoardDefinition> defs) {
        int g = updateTicks;
        for (BoardDefinition def : defs) {
            if (!def.enabled()) continue;
            g = gcdIfAnimated(g, def.title());
            for (BoardLine line : def.lines()) g = gcdIfAnimated(g, line);
        }
        return Math.max(1, g);
    }

    private static int gcdIfAnimated(int g, BoardLine line) {
        if (line.scroll() != null) g = gcd(g, line.scroll().speedTicks());
        if (line.frames().size() < 2) return g;
        return gcd(g, line.intervalTicks());
    }

    private static int gcd(int g, int other) {
        int a = g, b = other;
        while (b != 0) { int t = a % b; a = b; b = t; }
        return a;
    }

    public void start() {
        int period = clockPeriod;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Ferma tutto e sgancia la scoreboard di ogni giocatore ancora online (/onDisable). */
    public void shutdown() {
        stop();
        for (Player player : Bukkit.getOnlinePlayers()) forget(player);
    }

    private void tick() {
        tick += clockPeriod;
        // Il giro "pieno" (condizioni + placeholder) resta a update-interval-ticks; nei giri in mezzo
        // si ridisegna solo chi ha davanti una riga il cui frame cambia proprio adesso.
        boolean full = tick % updateIntervalTicks == 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (full) {
                refresh(player);
                continue;
            }
            PlayerBoard board = boards.get(player.getUniqueId());
            BoardDefinition current = board == null ? null : byId(board.currentBoardId());
            if (current != null && frameChangesNow(current)) refresh(player);
        }
    }

    private BoardDefinition byId(String id) {
        if (id == null) return null;
        for (BoardDefinition def : definitions) if (def.id().equals(id)) return def;
        return null;
    }

    private boolean frameChangesNow(BoardDefinition def) {
        if (changesNow(def.title())) return true;
        for (BoardLine line : def.lines()) if (changesNow(line)) return true;
        return false;
    }

    private boolean changesNow(BoardLine line) {
        if (line.scroll() != null && tick % line.scroll().speedTicks() == 0) return true;
        return line.frames().size() > 1 && tick % line.intervalTicks() == 0;
    }

    private void refresh(Player player) {
        PlayerBoard board = boards.computeIfAbsent(player.getUniqueId(), id -> new PlayerBoard(player));
        board.ensureAttached(player);

        if (!enabled || hiddenPlayers.isHidden(player.getUniqueId())) {
            board.setVisible(false);
            return;
        }
        BoardDefinition selected = select(player);
        if (selected == null) {
            board.setVisible(false);
            return;
        }
        board.setVisible(true);
        Component title = renderFrame(selected.title(), player);
        List<Component> lines = new ArrayList<>(selected.lines().size());
        for (BoardLine line : selected.lines()) lines.add(renderFrame(line, player));
        board.render(selected.id(), title, lines);
    }

    private Component renderFrame(BoardLine line, Player player) {
        String raw = line.frameAt(tick);
        String resolved = Papi.resolve(player, raw);
        BoardLine.Scroll scroll = line.scroll();
        if (scroll != null) {
            // Si fa scorrere il testo GIA' risolto e colorato: i placeholder dentro scorrono col resto,
            // e ogni carattere si porta dietro il suo colore. Il passo segue l'orologio comune, quindi
            // due giocatori vedono la scritta allo stesso punto.
            resolved = Marquee.window(Colors.translate(resolved), scroll.width(), scroll.gap(),
                    tick / scroll.speedTicks());
        }
        Component text = Colors.component(resolved);
        return markText ? SidebarMark.apply(text) : text;
    }

    /** La scoreboard che dovrebbe vedere questo giocatore adesso, o null se nessuna corrisponde. */
    public BoardDefinition select(Player player) {
        BoardDefinition best = null;
        int bestWeight = Integer.MIN_VALUE;
        int bestScore = Integer.MIN_VALUE;
        for (BoardDefinition def : definitions) {
            if (!def.matches(player, worldGuard)) continue;
            int score = def.specificity(priorityOrder);
            if (def.weight() > bestWeight || (def.weight() == bestWeight && score > bestScore)) {
                best = def;
                bestWeight = def.weight();
                bestScore = score;
            }
        }
        return best;
    }

    // ------------------------------------------------------------- giocatori

    public void forget(Player player) {
        PlayerBoard board = boards.remove(player.getUniqueId());
        if (board != null) board.destroy(player);
    }

    /** /mscoreboard toggle. @return il nuovo stato (true = ora nascosta). */
    public boolean toggleHidden(Player player) {
        return hiddenPlayers.toggle(player.getUniqueId());
    }

    public boolean isHidden(Player player) {
        return hiddenPlayers.isHidden(player.getUniqueId());
    }

    // ------------------------------------------------------------- diagnostica (/mscoreboard list, debug)

    public List<BoardDefinition> definitions() { return definitions; }

    public List<String> priorityOrder() { return priorityOrder; }

    public boolean worldGuardEnabled() { return worldGuard.enabled(); }
}
