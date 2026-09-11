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

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * L'aureola gialla che gira sopra la testa dei VIP.
 *
 * <p>E' un <b>solo puntino</b> di particella {@code DUST} che orbita in cerchio appena sopra la
 * testa: a ogni giro del task l'angolo avanza di un passo e la particella si sposta lungo il
 * cerchio. Non un anello intero ridisegnato ogni volta — quello, da fermo, sembra pulsare; qui
 * si vede un punto che ruota, con la breve scia lasciata dalle particelle che sfumano.</p>
 *
 * <p>Un task unico passa in rassegna i giocatori online e disegna solo su quelli a cui l'aureola
 * spetta: con un centinaio di persone collegate un task per ciascuno si sentirebbe.</p>
 *
 * <p>L'aureola spetta a chi ha il permesso {@code magixcosmetics.halo} (i VIP), non l'ha spenta
 * col suo comando, non e' in spettatore e — se il config lo chiede — non e' in vanish o invisibile:
 * un'aureola su uno staff invisibile lo tradirebbe.</p>
 */
public final class HaloManager {

    /** Chi ha questo permesso e' un VIP e vede l'aureola sopra la testa. */
    public static final String HALO_PERMISSION = "magixcosmetics.halo";

    private final MagixCosmetics plugin;

    // Valori letti dal config a ogni load()/reload.
    private boolean enabled;
    private Color color;
    private double radius;
    private double height;
    private float size;
    private long interval;
    private double spinSpeed;
    private boolean hideWhenVanished;
    private boolean hideWhenInvisible;

    /** Chi ha spento la PROPRIA aureola con /cosmetics halo off. In memoria: default accesa. */
    private final Set<UUID> disabled = new HashSet<>();

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
        color = parseColor(c.getString("halo.color", "#FFDD33"));
        radius = c.getDouble("halo.radius", 0.4);
        height = c.getDouble("halo.height", 2.2);
        size = (float) c.getDouble("halo.particle-size", 0.8);
        interval = Math.max(1L, c.getLong("halo.update-interval-ticks", 1));
        spinSpeed = c.getDouble("halo.spin-speed", 0.25);
        hideWhenVanished = c.getBoolean("halo.hide-when-vanished", true);
        hideWhenInvisible = c.getBoolean("halo.hide-when-invisible", true);
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
        Particle.DustOptions dust = new Particle.DustOptions(color, size);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (shows(p)) draw(p, dust);
        }
    }

    /** L'aureola si vede su di lui adesso? Permesso, toggle personale e stato (spettatore/vanish/invisibile). */
    public boolean shows(Player p) {
        if (!enabled) return false;
        if (!p.hasPermission(HALO_PERMISSION)) return false;
        if (disabled.contains(p.getUniqueId())) return false;
        if (p.getGameMode() == GameMode.SPECTATOR) return false;
        if (hideWhenVanished && isVanished(p)) return false;
        if (hideWhenInvisible && p.hasPotionEffect(PotionEffectType.INVISIBILITY)) return false;
        return true;
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
