package com.teolo.magixentities.manage;

import com.teolo.magixentities.model.NpcDef;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Modalita' "mirror": ogni giocatore vede l'entita' con la PROPRIA skin
 * ({@code /mentities skin <nome> mirror}) e/o con il PROPRIO nome sopra la testa
 * ({@code /mentities displayname <nome> mirror}).
 *
 * Skin e nome di un'entita' sono unici per tutti i client, quindi non basta cambiarli: per ogni
 * giocatore vicino creiamo una COPIA dell'entita', personalizzata, visibile solo a lui
 * ({@code setVisibleByDefault(false)} + {@code showEntity}). L'entita' "vera" (quella salvata in
 * entities.yml) resta al suo posto ma nascosta a tutti: serve da ancora per posizione e opzioni.
 *
 * Le copie non sono persistenti (non finiscono nel salvataggio del mondo) e vivono solo finche'
 * il proprietario e' online e nel raggio configurato.
 */
public final class MirrorManager {

    private final JavaPlugin plugin;
    private final NpcManager npcs;
    /** id entita' -> (uuid giocatore -> uuid copia). */
    private final Map<String, Map<UUID, UUID>> clones = new HashMap<>();

    public MirrorManager(JavaPlugin plugin, NpcManager npcs) {
        this.plugin = plugin;
        this.npcs = npcs;
    }

    public void start() {
        long interval = Math.max(5L, plugin.getConfig().getLong("mirror.interval-ticks", 20L));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    private double radiusSq() {
        double r = plugin.getConfig().getDouble("mirror.radius", 48.0);
        return r * r;
    }

    /** Allinea le copie: ne crea una per ogni giocatore vicino, elimina quelle non piu' valide. */
    public void tick() {
        for (NpcDef d : npcs.all()) {
            if (!d.needsClones()) {
                clear(d);
                continue;
            }
            Location loc = d.location();
            if (loc == null || !d.chunkLoaded()) {
                clear(d);
                continue;
            }
            Map<UUID, UUID> owners = clones.computeIfAbsent(d.id, k -> new HashMap<>());
            double maxSq = radiusSq();

            Iterator<Map.Entry<UUID, UUID>> it = owners.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, UUID> entry = it.next();
                Player owner = Bukkit.getPlayer(entry.getKey());
                Entity clone = Bukkit.getEntity(entry.getValue());
                boolean near = owner != null && owner.isOnline()
                        && owner.getWorld().equals(loc.getWorld())
                        && owner.getLocation().distanceSquared(loc) <= maxSq;
                if (!near) {
                    if (clone != null) clone.remove();
                    it.remove();
                } else if (clone == null || clone.isDead()) {
                    it.remove(); // ricreata sotto
                }
            }

            for (Player p : loc.getWorld().getPlayers()) {
                if (owners.containsKey(p.getUniqueId())) continue;
                if (p.getLocation().distanceSquared(loc) > maxSq) continue;
                Entity clone = spawnClone(d, loc, p);
                if (clone != null) owners.put(p.getUniqueId(), clone.getUniqueId());
            }
        }
    }

    private Entity spawnClone(NpcDef d, Location loc, Player owner) {
        Class<? extends Entity> cls = d.type.getEntityClass();
        if (cls == null) return null;
        java.util.function.Consumer<Entity> pre = e -> npcs.applyClone(d, e, owner);
        try {
            Entity clone = loc.getWorld().spawn(loc, cls, pre, CreatureSpawnEvent.SpawnReason.CUSTOM);
            owner.showEntity(plugin, clone);
            return clone;
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Copia mirror di '" + d.name + "' non creata: " + ex.getMessage());
            return null;
        }
    }

    /** Elimina le copie di una entita' (dopo uno spostamento, un cambio skin o la rimozione). */
    public void clear(NpcDef d) {
        Map<UUID, UUID> owners = clones.remove(d.id);
        if (owners == null) return;
        for (UUID cloneId : owners.values()) {
            Entity e = Bukkit.getEntity(cloneId);
            if (e != null) e.remove();
        }
    }

    /** Elimina la copia di un giocatore (uscita dal server). */
    public void forget(Player p) {
        for (Map<UUID, UUID> owners : clones.values()) {
            UUID cloneId = owners.remove(p.getUniqueId());
            if (cloneId == null) continue;
            Entity e = Bukkit.getEntity(cloneId);
            if (e != null) e.remove();
        }
    }

    /** Elimina tutte le copie (spegnimento del plugin). */
    public void clearAll() {
        for (Map<UUID, UUID> owners : clones.values()) {
            for (UUID cloneId : owners.values()) {
                Entity e = Bukkit.getEntity(cloneId);
                if (e != null) e.remove();
            }
        }
        clones.clear();
    }

    /** Esegue un'azione su ogni copia viva di una entita', col suo proprietario. */
    public void forEachClone(NpcDef d, java.util.function.BiConsumer<Player, Entity> action) {
        Map<UUID, UUID> owners = clones.get(d.id);
        if (owners == null) return;
        for (Map.Entry<UUID, UUID> entry : owners.entrySet()) {
            Player owner = Bukkit.getPlayer(entry.getKey());
            Entity clone = Bukkit.getEntity(entry.getValue());
            if (owner != null && owner.isOnline() && clone != null && !clone.isDead()) {
                action.accept(owner, clone);
            }
        }
    }

    /** Numero di copie attive di una entita' (per /mentities info). */
    public int cloneCount(NpcDef d) {
        Map<UUID, UUID> owners = clones.get(d.id);
        return owners == null ? 0 : owners.size();
    }

    /**
     * Rimuove eventuali copie rimaste nei mondi (es. dopo un crash o un /reload del server):
     * non sono persistenti, ma nei chunk caricati possono sopravvivere a un reload dei plugin.
     */
    public void purgeStray() {
        List<Entity> toRemove = new ArrayList<>();
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (npcs.cloneTagOf(e) != null) toRemove.add(e);
            }
        }
        for (Entity e : toRemove) e.remove();
        if (!toRemove.isEmpty()) {
            plugin.getLogger().info("Copie mirror residue rimosse: " + toRemove.size());
        }
    }
}
