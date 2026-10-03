package com.teolo.magixcosmetics.cosmetic;

import com.teolo.magixcosmetics.MagixCosmetics;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Il firework di benvenuto: quando un giocatore entra nel server esplode un firework sopra di lui,
 * di serie una sfera magenta e verde. L'effetto e' spento nel config di serie (sul faction) e acceso
 * dove lo si vuole (l'hub): {@code join-firework.enabled}.
 *
 * <p>Ognuno puo' personalizzarlo con {@code /firework} (colori, sfumatura, forma, sfarfallio, scia),
 * ma ogni scelta passa da un permesso: senza permessi non si cambia niente e vale il predefinito.
 * I permessi si controllano ANCHE al momento dell'esplosione, cosi' una scelta salvata che il
 * giocatore non ha piu' diritto di usare decade da sola sul predefinito.</p>
 */
public final class FireworkManager {

    public static final String USE = "magixcosmetics.firework";
    public static final String PERM_COLOR_CUSTOM = "magixcosmetics.firework.color.custom";
    public static final String PERM_FADE = "magixcosmetics.firework.fade";
    public static final String PERM_FLICKER = "magixcosmetics.firework.flicker";
    public static final String PERM_TRAIL = "magixcosmetics.firework.trail";
    public static final String PERM_PREVIEW = "magixcosmetics.firework.preview";
    public static final String PERM_TOGGLE = "magixcosmetics.firework.toggle";

    /** I nomi validi per la tavolozza: ognuno ha il suo permesso magixcosmetics.firework.color.<nome>. */
    public static final List<String> COLOR_NAMES = List.of(
            "red", "orange", "yellow", "lime", "green", "aqua", "blue", "purple", "magenta", "pink", "white");

    /** Le forme: nome per il giocatore e per il permesso magixcosmetics.firework.shape.<nome>. */
    public static final Map<String, FireworkEffect.Type> SHAPES = new LinkedHashMap<>();
    static {
        SHAPES.put("ball", FireworkEffect.Type.BALL);
        SHAPES.put("large", FireworkEffect.Type.BALL_LARGE);
        SHAPES.put("star", FireworkEffect.Type.STAR);
        SHAPES.put("burst", FireworkEffect.Type.BURST);
        SHAPES.put("creeper", FireworkEffect.Type.CREEPER);
    }

    /** Il firework scoppia almeno a questa altezza sopra i piedi del giocatore, qualunque sia il config. */
    public static final int MIN_BURST_HEIGHT = 10;
    /** Se non arriva all'altezza (un soffitto), scoppia comunque dopo questi tick. */
    private static final int MAX_FLIGHT_TICKS = 100;

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    private final MagixCosmetics plugin;
    private final FireworkStore store;
    /** Il segno sui nostri firework, per annullarne il danno. */
    private final NamespacedKey tag;

    private boolean enabled;
    private long delayTicks;
    private double burstHeight;
    private int maxColors;
    private long previewCooldownMs;
    private boolean hideWhenVanished;
    private final Map<String, Color> palette = new LinkedHashMap<>();
    private FireworkLook defaults = new FireworkLook();

    private final Map<UUID, FireworkLook> looks = new HashMap<>();
    private final Set<UUID> off = new HashSet<>();
    private final Map<UUID, Long> lastPreview = new HashMap<>();

    public FireworkManager(MagixCosmetics plugin) {
        this.plugin = plugin;
        this.store = new FireworkStore(plugin);
        this.tag = new NamespacedKey(plugin, "cosmetic_firework");
    }

    public static String colorPermission(String name) { return "magixcosmetics.firework.color." + name; }
    public static String shapePermission(String name) { return "magixcosmetics.firework.shape." + name; }

    // ------------------------------------------------------------- config

    public void load() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("join-firework");
        palette.clear();
        looks.clear();
        off.clear();
        lastPreview.clear();
        if (c == null) { enabled = false; return; }
        enabled = c.getBoolean("enabled", false);
        delayTicks = Math.max(0, c.getLong("delay-ticks", 20));
        burstHeight = Math.max(MIN_BURST_HEIGHT, c.getDouble("burst-height", 12));
        maxColors = Math.max(1, Math.min(8, c.getInt("max-colors", 4)));
        previewCooldownMs = Math.max(0, c.getLong("preview-cooldown-seconds", 15)) * 1000L;
        hideWhenVanished = c.getBoolean("hide-when-vanished", true);

        ConfigurationSection pal = c.getConfigurationSection("palette");
        for (String name : COLOR_NAMES) {
            Color col = pal == null ? null : parseHex(pal.getString(name));
            if (col != null) palette.put(name, col);
        }

        FireworkLook d = new FireworkLook();
        ConfigurationSection def = c.getConfigurationSection("default");
        d.colors = new ArrayList<>();
        d.fade = new ArrayList<>();
        d.shape = "ball";
        d.flicker = false;
        d.trail = false;
        if (def != null) {
            for (String t : def.getStringList("colors")) if (parse(t) != null) d.colors.add(norm(t));
            for (String t : def.getStringList("fade")) if (parse(t) != null) d.fade.add(norm(t));
            String shape = def.getString("shape", "ball").toLowerCase(Locale.ROOT);
            if (SHAPES.containsKey(shape)) d.shape = shape;
            d.flicker = def.getBoolean("flicker", false);
            d.trail = def.getBoolean("trail", false);
        }
        if (d.colors.isEmpty()) { d.colors.add("magenta"); d.colors.add("green"); }
        defaults = d;
        store.load(looks, off);
    }

    public boolean enabled() { return enabled; }
    public int maxColors() { return maxColors; }
    public boolean isOff(UUID id) { return off.contains(id); }

    // ------------------------------------------------------------- colori

    private static String norm(String token) { return token.trim().toLowerCase(Locale.ROOT); }

    private static Color parseHex(String s) {
        if (s == null || !HEX.matcher(s.trim()).matches()) return null;
        return Color.fromRGB(Integer.parseInt(s.trim().substring(1), 16));
    }

    /** Il colore di un token (nome della tavolozza o #RRGGBB), o null se non e' ne' l'uno ne' l'altro. */
    public Color parse(String token) {
        if (token == null) return null;
        String t = norm(token);
        Color named = palette.get(t);
        return named != null ? named : parseHex(t);
    }

    /** Il token e' un esadecimale (serve il permesso "custom") e non un nome della tavolozza? */
    public boolean isCustom(String token) { return !palette.containsKey(norm(token)); }

    /** Il token e' valido e il giocatore ha il permesso per usarlo. */
    public boolean mayUseColor(Player p, String token) {
        if (parse(token) == null) return false;
        return isCustom(token) ? p.hasPermission(PERM_COLOR_CUSTOM) : p.hasPermission(colorPermission(norm(token)));
    }

    public List<String> paletteNames() { return new ArrayList<>(palette.keySet()); }

    // ------------------------------------------------------------- scelte personali

    public FireworkLook defaults() { return defaults; }

    /** Cio' che il giocatore ha scelto (anche vuoto): mai null. */
    FireworkLook chosen(UUID id) { return looks.getOrDefault(id, new FireworkLook()); }

    private FireworkLook edit(UUID id) { return looks.computeIfAbsent(id, k -> new FireworkLook()); }

    private void saveAll() { store.save(looks, off); }

    public void setColors(UUID id, List<String> tokens) { edit(id).colors = normAll(tokens); saveAll(); }
    public void setFade(UUID id, List<String> tokens) { edit(id).fade = normAll(tokens); saveAll(); }
    public void setShape(UUID id, String shape) { edit(id).shape = shape; saveAll(); }
    public void setFlicker(UUID id, boolean on) { edit(id).flicker = on; saveAll(); }
    public void setTrail(UUID id, boolean on) { edit(id).trail = on; saveAll(); }

    public void setOff(UUID id, boolean value) {
        if (value) off.add(id); else off.remove(id);
        saveAll();
    }

    /** Cancella tutte le scelte personali: si torna al predefinito (resta l'interruttore on/off). */
    public void reset(UUID id) { looks.remove(id); saveAll(); }

    private static List<String> normAll(List<String> tokens) {
        List<String> out = new ArrayList<>();
        for (String t : tokens) out.add(norm(t));
        return out;
    }

    /**
     * Il look che il giocatore avrebbe adesso: le sue scelte, ma solo quelle che i permessi di oggi
     * gli consentono; il resto viene dal predefinito.
     */
    public FireworkLook effective(Player p) {
        FireworkLook mine = chosen(p.getUniqueId());
        FireworkLook out = defaults.copy();
        if (!p.hasPermission(USE)) return out;

        if (mine.colors != null) {
            List<String> ok = new ArrayList<>();
            for (String t : mine.colors) if (mayUseColor(p, t) && ok.size() < maxColors) ok.add(t);
            if (!ok.isEmpty()) out.colors = ok;
        }
        if (mine.fade != null && p.hasPermission(PERM_FADE)) {
            List<String> ok = new ArrayList<>();
            for (String t : mine.fade) if (mayUseColor(p, t) && ok.size() < maxColors) ok.add(t);
            out.fade = ok;   // vuota e' valida: nessuna sfumatura
        }
        if (mine.shape != null && SHAPES.containsKey(mine.shape) && p.hasPermission(shapePermission(mine.shape))) {
            out.shape = mine.shape;
        }
        if (mine.flicker != null && p.hasPermission(PERM_FLICKER)) out.flicker = mine.flicker;
        if (mine.trail != null && p.hasPermission(PERM_TRAIL)) out.trail = mine.trail;
        return out;
    }

    // ------------------------------------------------------------- esplosione

    /** Se serve, fa esplodere il firework d'ingresso per un giocatore appena entrato. */
    public void onJoin(Player p) {
        if (!enabled || off.contains(p.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || p.getGameMode() == GameMode.SPECTATOR) return;
            if (hideWhenVanished && isVanished(p)) return;
            explode(p.getLocation(), effective(p));
        }, delayTicks);
    }

    /**
     * Anteprima del PROPRIO firework per chi ha il permesso. Ritorna i secondi da aspettare se e'
     * troppo presto dall'ultima volta, altrimenti 0 (ed esplode).
     */
    public long preview(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastPreview.get(p.getUniqueId());
        if (last != null && now - last < previewCooldownMs) return (previewCooldownMs - (now - last) + 999) / 1000;
        lastPreview.put(p.getUniqueId(), now);
        explode(p.getLocation(), effective(p));
        return 0;
    }

    /**
     * Lancia il firework dai piedi del giocatore: sale dritto e scoppia appena e' salito di
     * {@code burstHeight} blocchi (mai meno di {@link #MIN_BURST_HEIGHT}), lontano dalla testa di
     * chiunque. Se qualcosa lo ferma prima (un soffitto), scoppia comunque dopo
     * {@link #MAX_FLIGHT_TICKS}. Il danno dell'esplosione lo annulla {@link FireworkListener}.
     */
    private void explode(Location from, FireworkLook look) {
        World world = from.getWorld();
        if (world == null) return;
        FireworkEffect.Builder b = FireworkEffect.builder()
                .with(SHAPES.getOrDefault(look.shape, FireworkEffect.Type.BALL))
                .flicker(Boolean.TRUE.equals(look.flicker))
                .trail(Boolean.TRUE.equals(look.trail));
        for (String t : look.colors) { Color c = parse(t); if (c != null) b.withColor(c); }
        for (String t : look.fade) { Color c = parse(t); if (c != null) b.withFade(c); }
        FireworkEffect effect;
        try {
            effect = b.build();
        } catch (IllegalStateException noColor) {
            return;   // nessun colore valido: niente da disegnare
        }
        double targetY = from.getY() + burstHeight;
        Firework fw = world.spawn(from, Firework.class, f -> {
            FireworkMeta meta = f.getFireworkMeta();
            meta.clearEffects();
            meta.addEffect(effect);
            meta.setPower(3);
            f.setFireworkMeta(meta);
            // Dopo setFireworkMeta, che ricalcola la durata del volo a caso.
            f.setTicksToDetonate(MAX_FLIGHT_TICKS);
            f.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        });
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!fw.isValid()) { task.cancel(); return; }
            if (fw.getLocation().getY() >= targetY) {
                fw.detonate();
                task.cancel();
            }
        }, 1L, 1L);
    }

    /** Il firework e' uno dei nostri (d'ingresso o anteprima)? Serve ad annullarne il danno. */
    public boolean isOurs(Firework fw) {
        return fw.getPersistentDataContainer().has(tag, PersistentDataType.BYTE);
    }

    /** Vanish alla maniera degli altri plugin Magix: metadata "vanished" a true (lo mette CMI). */
    private static boolean isVanished(Player p) {
        for (var meta : p.getMetadata("vanished")) if (meta.asBoolean()) return true;
        return false;
    }
}
