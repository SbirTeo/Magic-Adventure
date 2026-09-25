package com.teolo.magixentities.manage;

import com.teolo.magixentities.hook.MagixCosmeticsHook;
import com.teolo.magixentities.model.NpcDef;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

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
    /** uuid copia -> uuid del suo sedile invisibile (solo per le copie con posa "sitting"). */
    private final Map<UUID, UUID> seats = new HashMap<>();
    private BukkitTask haloTask;

    public MirrorManager(JavaPlugin plugin, NpcManager npcs) {
        this.plugin = plugin;
        this.npcs = npcs;
    }

    public void start() {
        long interval = Math.max(5L, plugin.getConfig().getLong("mirror.interval-ticks", 20L));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
        startHalo();
    }

    /**
     * Aureola VIP sulle statue (tipo player): stesso puntino di MagixCosmetics, disegnato per
     * riflessione (vedi {@link MagixCosmeticsHook}). Con skin "mirror" e' quella di chi guarda la
     * sua copia; con una skin fissa e' quella del giocatore della skin, per tutti. Non parte se
     * MagixCosmetics non e' installato/abilitato o se {@code mirror.halo.enabled} e' false.
     */
    private void startHalo() {
        if (!plugin.getConfig().getBoolean("mirror.halo.enabled", true)) return;
        if (!MagixCosmeticsHook.enabled()) return;
        long interval = Math.max(1L, plugin.getConfig().getLong("mirror.halo.interval-ticks", 1L));
        haloTask = Bukkit.getScheduler().runTaskTimer(plugin, this::haloTick, interval, interval);
    }

    private void haloTick() {
        for (NpcDef d : npcs.all()) {
            if (!d.isPlayerType() || !d.chunkLoaded()) continue;
            if (d.isSkinMirror()) {
                // ognuno vede la sua copia con la sua aureola, e solo lui
                forEachClone(d, (owner, clone) ->
                        MagixCosmeticsHook.drawViewerHalo(owner, clone.getLocation(), d.scale));
            } else {
                // Skin fissa: l'aureola e' la stessa per chiunque guardi. Le copie (nome a specchio,
                // follow personale) stanno nello stesso punto dell'entita' vera, quindi basta
                // disegnarla una volta li', per tutti.
                Entity e = npcs.entityOf(d);
                if (e != null) MagixCosmeticsHook.drawOwnerHalo(d.skinNick(), e.getLocation(), d.scale, null);
            }
        }
    }

    /**
     * Raggio entro cui un giocatore ha la sua copia. Con lo specchio e' mirror.radius (fuori non si
     * vede niente, l'entita' vera e' nascosta a tutti); col solo follow e' il raggio del follow di
     * quell'entita' (o follow.radius): oltre, la copia non guarderebbe comunque nessuno, e si vede
     * l'entita' vera.
     */
    private double radius(NpcDef d) {
        return d.hidesReal()
                ? plugin.getConfig().getDouble("mirror.radius", 48.0)
                : d.followRadiusOr(plugin.getConfig().getDouble("follow.radius", 12.0));
    }

    /**
     * Margine (blocchi) oltre il raggio prima che una copia venga tolta. Senza, chi sta sul bordo
     * vede la statua scattare di continuo fra la sua copia e l'entita' vera (o sparire, con lo
     * specchio) a ogni mezzo passo: la copia nasce entro il raggio e sparisce solo oltre il margine.
     */
    public static final double KEEP_MARGIN = 3.0;

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
            double r = radius(d);
            double maxSq = r * r;
            double keepSq = (r + KEEP_MARGIN) * (r + KEEP_MARGIN);

            Iterator<Map.Entry<UUID, UUID>> it = owners.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, UUID> entry = it.next();
                Player owner = Bukkit.getPlayer(entry.getKey());
                Entity clone = Bukkit.getEntity(entry.getValue());
                boolean near = owner != null && owner.isOnline()
                        && owner.getWorld().equals(loc.getWorld())
                        && owner.getLocation().distanceSquared(loc) <= keepSq;
                if (!near) {
                    removeClone(d, entry.getKey(), entry.getValue());
                    it.remove();
                } else if (clone == null || clone.isDead()) {
                    removeClone(d, entry.getKey(), entry.getValue()); // resta solo il sedile da togliere
                    it.remove(); // ricreata sotto
                }
            }

            for (Player p : loc.getWorld().getPlayers()) {
                if (owners.containsKey(p.getUniqueId())) continue;
                if (p.getLocation().distanceSquared(loc) > maxSq) continue;
                Entity clone = spawnClone(d, loc, p);
                if (clone != null) owners.put(p.getUniqueId(), clone.getUniqueId());
            }

            // Follow personale: l'entita' vera resta visibile a tutti, tranne a chi ha la sua copia
            // (ne vedrebbe due una dentro l'altra). Rifatto a ogni giro: se l'entita' vera viene
            // ricreata e' un'entita' nuova, visibile di nuovo a tutti.
            if (!d.hidesReal()) {
                Entity real = npcs.entityOf(d);
                if (real != null) {
                    for (UUID ownerId : owners.keySet()) {
                        Player owner = Bukkit.getPlayer(ownerId);
                        if (owner != null) owner.hideEntity(plugin, real);
                    }
                }
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
            // Il sedile resta visibile a tutti (e' comunque invisibile): il client del proprietario
            // deve conoscere il veicolo, altrimenti non sa che la copia e' seduta.
            Entity seat = npcs.mountCloneSeat(d, clone);
            if (seat != null) seats.put(clone.getUniqueId(), seat.getUniqueId());
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
        for (Map.Entry<UUID, UUID> entry : owners.entrySet()) removeClone(d, entry.getKey(), entry.getValue());
    }

    /** Elimina la copia di un giocatore (uscita dal server). */
    public void forget(Player p) {
        for (Map<UUID, UUID> owners : clones.values()) {
            UUID cloneId = owners.remove(p.getUniqueId());
            // Chi esce non deve rivedere niente: niente definizione, niente showEntity.
            if (cloneId != null) removeClone(null, p.getUniqueId(), cloneId);
        }
    }

    /** Elimina tutte le copie (spegnimento del plugin, o /mentities reload). */
    public void clearAll() {
        for (Map.Entry<String, Map<UUID, UUID>> byDef : clones.entrySet()) {
            NpcDef d = npcs.get(byDef.getKey());
            for (Map.Entry<UUID, UUID> entry : byDef.getValue().entrySet()) {
                removeClone(d, entry.getKey(), entry.getValue());
            }
        }
        clones.clear();
        seats.clear();
    }

    /**
     * Toglie una copia e il suo eventuale sedile (un sedile senza passeggero resterebbe li'). Col
     * follow personale l'entita' vera era nascosta al proprietario della copia: gli torna visibile.
     */
    private void removeClone(NpcDef d, UUID ownerId, UUID cloneId) {
        Entity e = Bukkit.getEntity(cloneId);
        if (e != null) e.remove();
        UUID seatId = seats.remove(cloneId);
        Entity seat = seatId == null ? null : Bukkit.getEntity(seatId);
        if (seat != null) seat.remove();
        if (d != null && !d.hidesReal()) {
            Player owner = Bukkit.getPlayer(ownerId);
            Entity real = npcs.entityOf(d);
            if (owner != null && real != null) owner.showEntity(plugin, real);
        }
    }

    /** Ferma il task dell'aureola (solo spegnimento del plugin: /mentities reload non lo tocca). */
    public void stopHalo() {
        if (haloTask != null) {
            haloTask.cancel();
            haloTask = null;
        }
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
                if (npcs.cloneTagOf(e) != null || npcs.isCloneSeat(e)) toRemove.add(e);
            }
        }
        for (Entity e : toRemove) e.remove();
        if (!toRemove.isEmpty()) {
            plugin.getLogger().info("Copie mirror residue rimosse: " + toRemove.size());
        }
    }
}
