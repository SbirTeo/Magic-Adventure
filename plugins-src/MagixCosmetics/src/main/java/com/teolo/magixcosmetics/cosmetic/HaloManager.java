package com.teolo.magixcosmetics.cosmetic;

import com.teolo.magixcosmetics.MagixCosmetics;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * L'aureola che gira sopra la testa dei VIP.
 *
 * <p>E' un <b>solo puntino</b> di particella {@code DUST} che orbita in cerchio appena sopra la
 * testa: a ogni giro del task l'angolo avanza di un passo e la particella si sposta lungo il
 * cerchio. Non un anello intero ridisegnato ogni volta — quello, da fermo, sembra pulsare; qui
 * si vede un punto che ruota, con la breve scia lasciata dalle particelle che sfumano.</p>
 *
 * <p>Un task unico passa in rassegna i giocatori online e disegna solo su quelli a cui l'aureola
 * spetta: con un centinaio di persone collegate un task per ciascuno si sentirebbe.</p>
 *
 * <p>Il COLORE non e' fisso: {@code halo.colors} nel config elenca una tavolozza di nomi (giallo,
 * rosso, blu...), ognuno legato a un permesso {@code magixcosmetics.halo.color.<nome>}. Un
 * giocatore con il permesso base {@code magixcosmetics.halo} ma <b>nessun</b> permesso colore non
 * ha nessun colore assegnato e l'aureola semplicemente non si disegna; con un permesso colore la
 * mostra in automatico in quel colore, e se ne ha piu' di uno sceglie con {@code /halo setcolor}.</p>
 *
 * <p>L'aureola sparisce anche durante un combattimento PvP e per qualche secondo dopo (vedi
 * {@link HaloCombatListener}), non e' in spettatore e — se il config lo chiede — non e' in vanish
 * o invisibile: un'aureola su uno staff invisibile lo tradirebbe.</p>
 */
public final class HaloManager {

    /** Chi ha questo permesso e' un VIP e puo' vedere l'aureola sopra la testa. */
    public static final String HALO_PERMISSION = "magixcosmetics.halo";

    /**
     * La tavolozza predefinita: i nomi validi per halo.colors nel config, per il permesso
     * magixcosmetics.halo.color.<nome> e per /halo setcolor <nome>. Fissa (non letta a giro libero
     * dal config) cosi' ogni nome ha anche il suo permesso dichiarato in plugin.yml.
     */
    private static final List<String> COLOR_NAMES = List.of(
            "yellow", "red", "orange", "lime", "aqua", "blue", "purple", "pink", "white");

    private final MagixCosmetics plugin;

    // Valori letti dal config a ogni load()/reload.
    private boolean enabled;
    /** Tavolozza: nome del colore (minuscolo, es. "yellow") -> colore vero. Ordine del config. */
    private final Map<String, Color> colors = new LinkedHashMap<>();
    private double radius;
    private double height;
    private float size;
    private long interval;
    private double spinSpeed;
    private boolean hideWhenVanished;
    private boolean hideWhenInvisible;
    private boolean hideWhileFighting;
    private long combatCooldownMs;

    /** Chi ha spento la PROPRIA aureola con /halo off. In memoria: default accesa. */
    private final Set<UUID> disabled = new HashSet<>();
    /** Il colore scelto da ognuno con /halo setcolor. In memoria: senza scelta si usa il primo colore permesso. */
    private final Map<UUID, String> chosenColor = new HashMap<>();
    /** Istante (ms) dell'ultimo colpo dato o subito in PvP: serve al cooldown post-combattimento. */
    private final Map<UUID, Long> lastCombatAt = new HashMap<>();

    private BukkitTask task;
    /** Posizione del puntino lungo il cerchio: avanza di spinSpeed a ogni giro, cosi' orbita. */
    private double angle;

    public HaloManager(MagixCosmetics plugin) {
        this.plugin = plugin;
    }

    /** Rilegge i valori dal config.yml. */
    public void load() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("halo.enabled", true);

        colors.clear();
        for (String name : COLOR_NAMES) {
            String hex = c.getString("halo.colors." + name, null);
            if (hex != null) colors.put(name, parseColor(hex));
        }

        radius = c.getDouble("halo.radius", 0.4);
        height = c.getDouble("halo.height", 2.2);
        size = (float) c.getDouble("halo.particle-size", 0.8);
        interval = Math.max(1L, c.getLong("halo.update-interval-ticks", 1));
        spinSpeed = c.getDouble("halo.spin-speed", 0.25);
        hideWhenVanished = c.getBoolean("halo.hide-when-vanished", true);
        hideWhenInvisible = c.getBoolean("halo.hide-when-invisible", true);
        hideWhileFighting = c.getBoolean("halo.combat.hide-while-fighting", true);
        combatCooldownMs = Math.max(0, c.getLong("halo.combat.cooldown-after-combat-seconds", 30)) * 1000L;
    }

    /** Fa ripartire il task col nuovo intervallo (o non parte affatto se l'aureola e' spenta). */
    public void start() {
        stop();
        if (!enabled) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        angle += spinSpeed;
        if (angle > Math.PI * 2) angle -= Math.PI * 2;   // niente overflow su uptime lunghi
        for (Player p : Bukkit.getOnlinePlayers()) {
            Color color = effectiveColor(p);
            if (color != null) draw(p, new Particle.DustOptions(color, size));
        }
    }

    /**
     * Il colore con cui disegnare l'aureola di questo giocatore ADESSO, o {@code null} se non deve
     * vedersi affatto (permesso mancante, personalmente spenta, spettatore/vanish/invisibile, in
     * combattimento, o nessun colore assegnato).
     */
    public Color effectiveColor(Player p) {
        if (!enabled) return null;
        if (!p.hasPermission(HALO_PERMISSION)) return null;
        if (disabled.contains(p.getUniqueId())) return null;
        if (p.getGameMode() == GameMode.SPECTATOR) return null;
        if (hideWhenVanished && isVanished(p)) return null;
        if (hideWhenInvisible && p.hasPotionEffect(PotionEffectType.INVISIBILITY)) return null;
        if (isInCombat(p.getUniqueId())) return null;
        String name = activeColorName(p);
        return name == null ? null : colors.get(name);
    }

    private void draw(Player p, Particle.DustOptions dust) {
        // Un solo punto, alla posizione corrente lungo il cerchio: il prossimo tick sara' poco
        // piu' avanti, e cosi' orbita. Le particelle vecchie sfumano da sole e lasciano una breve scia.
        double x = Math.cos(angle) * radius;
        double z = Math.sin(angle) * radius;
        Location at = p.getLocation().add(x, height, z);
        // Sul MONDO, non solo a lui: cosi' l'aureola la vedono tutti, il VIP compreso.
        // Con DUST il "count" 1 e gli offset a zero mettono la particella esattamente li'.
        p.getWorld().spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust);
    }

    // ------------------------------------------------------------- toggle personale

    public boolean isDisabled(UUID id) {
        return disabled.contains(id);
    }

    /** on=true: torna a mostrare l'aureola; on=false: la spegne solo per lui. */
    public void toggle(UUID id, boolean on) {
        if (on) disabled.remove(id);
        else disabled.add(id);
    }

    public boolean enabled() {
        return enabled;
    }

    // ------------------------------------------------------------- colore

    /** Tutti i nomi di colore della tavolozza, nell'ordine del config. */
    public List<String> colorNames() {
        return new ArrayList<>(colors.keySet());
    }

    /**
     * Il colore che questo giocatore vede adesso: quello scelto con /halo setcolor, se il permesso
     * ce l'ha ancora; altrimenti il primo colore della tavolozza per cui ha il permesso; se non ne
     * ha nessuno, {@code null} — niente colore, niente aureola, anche con il permesso base.
     */
    public String activeColorName(Player p) {
        String chosen = chosenColor.get(p.getUniqueId());
        if (chosen != null && colors.containsKey(chosen) && p.hasPermission(colorPermission(chosen))) {
            return chosen;
        }
        for (String name : colors.keySet()) {
            if (p.hasPermission(colorPermission(name))) return name;
        }
        return null;
    }

    /** Ricorda la scelta di colore del giocatore (il comando controlla gia' il permesso prima di chiamarlo). */
    public void setColor(UUID id, String name) {
        chosenColor.put(id, name);
    }

    /** Il nodo di permesso che sblocca un colore, es. "magixcosmetics.halo.color.yellow". */
    public static String colorPermission(String name) {
        return HALO_PERMISSION + ".color." + name;
    }

    // ------------------------------------------------------------- combattimento

    /** true se ha dato o subito un colpo PvP da meno di halo.combat.cooldown-after-combat-seconds. */
    public boolean isInCombat(UUID id) {
        if (!hideWhileFighting) return false;
        Long last = lastCombatAt.get(id);
        return last != null && System.currentTimeMillis() - last < combatCooldownMs;
    }

    /** Segna il momento del colpo: lo chiama {@link HaloCombatListener} su chi colpisce e su chi viene colpito. */
    public void markCombat(UUID id) {
        lastCombatAt.put(id, System.currentTimeMillis());
    }

    // ------------------------------------------------------------- utilita'

    /** Vanish alla maniera degli altri plugin Magix: metadata "vanished" a true (lo mette CMI). */
    private static boolean isVanished(Player p) {
        for (var meta : p.getMetadata("vanished")) {
            if (meta.asBoolean()) return true;
        }
        return false;
    }

    /** "#RRGGBB" (o "&#RRGGBB") -> Color; se il testo e' scritto male, ripiega sul giallo. */
    private static Color parseColor(String hex) {
        try {
            String h = hex == null ? "" : hex.trim();
            if (h.startsWith("&#")) h = h.substring(2);
            else if (h.startsWith("#")) h = h.substring(1);
            int rgb = Integer.parseInt(h, 16);
            return Color.fromRGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        } catch (Exception e) {
            return Color.fromRGB(0xFF, 0xDD, 0x33);
        }
    }
}
