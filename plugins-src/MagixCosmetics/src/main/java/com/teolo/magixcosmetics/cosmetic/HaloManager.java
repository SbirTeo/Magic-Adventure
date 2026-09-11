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
 * <p>Un solo task ripetuto passa in rassegna i giocatori online e, per quelli a cui l'aureola
 * spetta, disegna un anello di particelle {@code DUST} appena sopra la testa. Un task unico per
 * tutti e non uno per giocatore: con un centinaio di persone collegate la differenza si sente.</p>
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
    private int points;
    private float size;
    private long interval;
    private boolean spin;
    private double spinSpeed;
    private boolean hideWhenVanished;
    private boolean hideWhenInvisible;

    /** Chi ha spento la PROPRIA aureola con /cosmetics halo off. In memoria: default accesa. */
    private final Set<UUID> disabled = new HashSet<>();

    private BukkitTask task;
    /** Angolo di partenza dell'anello: avanza a ogni giro per farlo ruotare. */
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
        points = Math.max(3, c.getInt("halo.points", 16));
        size = (float) c.getDouble("halo.particle-size", 0.9);
        interval = Math.max(1L, c.getLong("halo.update-interval-ticks", 4));
        spin = c.getBoolean("halo.spin", true);
        spinSpeed = c.getDouble("halo.spin-speed", 0.15);
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
        if (spin) {
            angle += spinSpeed;
            if (angle > Math.PI * 2) angle -= Math.PI * 2;   // niente overflow su uptime lunghi
        }
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
        Location center = p.getLocation().add(0, height, 0);
        for (int i = 0; i < points; i++) {
            double a = angle + (2 * Math.PI * i / points);
            double x = Math.cos(a) * radius;
            double z = Math.sin(a) * radius;
            Location at = center.clone().add(x, 0, z);
            // Sul MONDO, non solo a lui: cosi' l'aureola la vedono tutti, il VIP compreso.
            // Con DUST il "count" 1 e gli offset a zero mettono la particella esattamente li'.
            p.getWorld().spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust);
        }
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
