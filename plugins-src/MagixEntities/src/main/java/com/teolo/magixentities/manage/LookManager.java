package com.teolo.magixentities.manage;

import com.teolo.magixentities.model.NpcDef;
import io.papermc.paper.entity.LookAnchor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Opzione "follow": ognuno, entro un raggio, vede l'entita' girata verso di SE' — non verso il
 * giocatore piu' vicino. Funziona con una copia per giocatore (come lo specchio): ogni copia segue
 * il proprio proprietario.
 */
public final class LookManager {

    private final JavaPlugin plugin;
    private final NpcManager npcs;
    private final MirrorManager mirror;

    public LookManager(JavaPlugin plugin, NpcManager npcs, MirrorManager mirror) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.mirror = mirror;
    }

    public void start() {
        long interval = Math.max(1L, plugin.getConfig().getLong("follow.interval-ticks", 5L));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    private void tick() {
        double defaultRadius = plugin.getConfig().getDouble("follow.radius", 12.0);

        for (NpcDef d : npcs.all()) {
            if (!d.opt("follow", false)) continue;
            double radius = d.followRadiusOr(defaultRadius);
            double maxSq = radius * radius;
            Location loc = d.location();
            if (loc == null || !d.chunkLoaded()) continue;

            // Col follow ogni giocatore vicino ha la sua copia (vedi NpcDef#needsClones): ogni copia
            // guarda il suo proprietario, o torna dritta se si e' allontanato.
            mirror.forEachClone(d, (owner, clone) -> {
                if (owner.getWorld().equals(clone.getWorld())
                        && owner.getLocation().distanceSquared(clone.getLocation()) <= maxSq) {
                    look(clone, owner);
                } else {
                    reset(clone, d);
                }
            });
            // L'entita' vera (quella che si vede da lontano) non guarda nessuno: una testa sola non
            // puo' guardare due persone. Resta com'e' stata salvata.
            Entity e = npcs.entityOf(d);
            if (e != null) reset(e, d);
        }
    }

    /** Testa (e corpo) verso gli occhi del giocatore. */
    private void look(Entity e, Player target) {
        Location eyes = target.getEyeLocation();
        e.lookAt(eyes.getX(), eyes.getY(), eyes.getZ(), LookAnchor.EYES);
        if (e instanceof LivingEntity le) {
            // senza questo la testa gira ma il corpo resta storto
            le.setBodyYaw(yawTowards(e.getLocation(), eyes));
        }
    }

    /** Torna all'orientamento salvato quando non c'e' nessuno nel raggio. */
    private void reset(Entity e, NpcDef d) {
        if (Math.abs(e.getLocation().getYaw() - d.yaw) < 0.5f
                && Math.abs(e.getLocation().getPitch() - d.pitch) < 0.5f) {
            return; // gia' a posto: non sprechiamo pacchetti ogni tick
        }
        e.setRotation(d.yaw, d.pitch);
        if (e instanceof LivingEntity le) le.setBodyYaw(d.yaw);
    }

    private float yawTowards(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
