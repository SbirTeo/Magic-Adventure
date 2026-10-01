package com.teolo.magixpack.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Models of {@link ModelCatalog} placed in the world: one item display per piece, all at the same
 * spot, each moved/turned/sized by its transformation. They are ordinary saved entities, so a model
 * stays where it was put across restarts; the plugin finds them again from their data tags when
 * their chunk loads, and plays the chosen animation (in a loop) while a player is near.
 */
public final class ModelDisplays implements Listener {

    /** Ticks between two animation steps: the client interpolates in between. */
    private static final int STEP = 2;
    /** Animations play only when a player is within this distance (blocks). */
    private static final double ACTIVE_RANGE = 96;

    private final JavaPlugin plugin;
    private final ModelCatalog catalog;
    private final NamespacedKey keyModel, keyPiece, keyPlacement, keyScale, keyAnimation;
    private final Map<UUID, Placement> placements = new HashMap<>();
    private BukkitTask task;
    private long tick;

    private static final class Placement {
        final UUID id;
        final String model;
        double scale;
        final String animation;
        final Map<Integer, ItemDisplay> pieces = new HashMap<>();
        /** The rest pose (or the current model file) still has to be sent. */
        boolean dirty = true;

        Placement(UUID id, String model, double scale, String animation) {
            this.id = id;
            this.model = model;
            this.scale = scale;
            this.animation = animation;
        }

        Location location() {
            for (ItemDisplay d : pieces.values()) if (d.isValid()) return d.getLocation();
            return null;
        }
    }

    public ModelDisplays(JavaPlugin plugin, ModelCatalog catalog) {
        this.plugin = plugin;
        this.catalog = catalog;
        keyModel = new NamespacedKey(plugin, "model");
        keyPiece = new NamespacedKey(plugin, "model-piece");
        keyPlacement = new NamespacedKey(plugin, "model-placement");
        keyScale = new NamespacedKey(plugin, "model-scale");
        keyAnimation = new NamespacedKey(plugin, "model-animation");
    }

    public void start() {
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) track(e);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::step, STEP, STEP);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    /** The model files changed (/mpack reload): send every piece again. */
    public void refresh() {
        for (Placement p : placements.values()) p.dirty = true;
    }

    // ---------------------------------------------------------------------------------- placing

    /** Puts a model at {@code at} (yaw included). @return the number of pieces, 0 if unknown. */
    public int spawn(String id, Location at, double scale, String animation) {
        BbModel m = catalog.get(id);
        if (m == null) return 0;
        UUID placement = UUID.randomUUID();
        Location loc = at.clone();
        loc.setPitch(0);
        for (int i = 0; i < m.pieces.size(); i++) {
            final int index = i;
            ItemDisplay d = loc.getWorld().spawn(loc, ItemDisplay.class, display -> {
                ItemStack stack = new ItemStack(Material.PAPER);
                ItemMeta meta = stack.getItemMeta();
                meta.setItemModel(ModelCatalog.itemModel(id, index));
                stack.setItemMeta(meta);
                display.setItemStack(stack);
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                display.setTransformationMatrix(m.pieceMatrix(index, null, 0, scale));
                display.setPersistent(true);
                PersistentDataContainer pdc = display.getPersistentDataContainer();
                pdc.set(keyModel, PersistentDataType.STRING, id);
                pdc.set(keyPiece, PersistentDataType.INTEGER, index);
                pdc.set(keyPlacement, PersistentDataType.STRING, placement.toString());
                pdc.set(keyScale, PersistentDataType.DOUBLE, scale);
                pdc.set(keyAnimation, PersistentDataType.STRING, animation == null ? "" : animation);
            });
            track(d);
        }
        return m.pieces.size();
    }

    /** Removes the placed model closest to {@code near}, within {@code radius}. @return its model
     *  id, or null if none is that close. */
    public String removeNearest(Location near, double radius) {
        Placement p = nearest(near, radius);
        if (p == null) return null;
        for (ItemDisplay d : p.pieces.values()) if (d.isValid()) d.remove();
        placements.remove(p.id);
        return p.model;
    }

    /** Resizes the placed model closest to {@code near} (1 = Blockbench size). @return its id or null. */
    public String scaleNearest(Location near, double radius, double scale) {
        Placement p = nearest(near, radius);
        if (p == null) return null;
        p.scale = scale;
        for (ItemDisplay d : p.pieces.values()) {
            if (d.isValid()) d.getPersistentDataContainer().set(keyScale, PersistentDataType.DOUBLE, scale);
        }
        p.dirty = true;
        return p.model;
    }

    /** Turns the placed model closest to {@code near} by {@code degrees}. @return its id or null. */
    public String rotateNearest(Location near, double radius, float degrees) {
        Placement p = nearest(near, radius);
        if (p == null) return null;
        for (ItemDisplay d : p.pieces.values()) {
            if (d.isValid()) d.setRotation(d.getLocation().getYaw() + degrees, 0);
        }
        return p.model;
    }

    private Placement nearest(Location near, double radius) {
        Placement best = null;
        double bestDist = radius * radius;
        for (Placement p : placements.values()) {
            Location l = p.location();
            if (l == null || l.getWorld() != near.getWorld()) continue;
            double dist = l.distanceSquared(near);
            if (dist <= bestDist) {
                bestDist = dist;
                best = p;
            }
        }
        return best;
    }

    // --------------------------------------------------------------------------------- tracking

    private void track(Entity e) {
        if (!(e instanceof ItemDisplay d)) return;
        PersistentDataContainer pdc = d.getPersistentDataContainer();
        String id = pdc.get(keyPlacement, PersistentDataType.STRING);
        String model = pdc.get(keyModel, PersistentDataType.STRING);
        Integer piece = pdc.get(keyPiece, PersistentDataType.INTEGER);
        if (id == null || model == null || piece == null) return;
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return;
        }
        Double scale = pdc.get(keyScale, PersistentDataType.DOUBLE);
        String anim = pdc.get(keyAnimation, PersistentDataType.STRING);
        Placement p = placements.computeIfAbsent(uuid, u -> new Placement(u, model, scale == null ? 1 : scale,
                anim == null || anim.isEmpty() ? null : anim));
        p.pieces.put(piece, d);
        p.dirty = true;
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity entity : e.getEntities()) track(entity);
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent e) {
        for (Entity entity : e.getEntities()) {
            if (!(entity instanceof ItemDisplay)) continue;
            String id = entity.getPersistentDataContainer().get(keyPlacement, PersistentDataType.STRING);
            if (id == null) continue;
            try {
                Placement p = placements.get(UUID.fromString(id));
                if (p == null) continue;
                p.pieces.values().removeIf(d -> d.getUniqueId().equals(entity.getUniqueId()));
                if (p.pieces.isEmpty()) placements.remove(p.id);
            } catch (IllegalArgumentException ignored) {
                // not ours
            }
        }
    }

    // -------------------------------------------------------------------------------- animation

    private void step() {
        tick += STEP;
        List<UUID> gone = new ArrayList<>();
        for (Placement p : placements.values()) {
            p.pieces.values().removeIf(d -> !d.isValid());
            if (p.pieces.isEmpty()) {
                gone.add(p.id);
                continue;
            }
            BbModel m = catalog.get(p.model);
            if (m == null) continue;
            BbModel.Animation anim = p.animation == null ? null : m.animations.get(p.animation);
            if (anim != null && anim.length() > 0 && playerNear(p)) {
                double t = tick / 20.0;
                t = anim.loop() ? t % anim.length() : Math.min(t, anim.length());
                apply(p, m, anim, t, STEP);
            } else if (p.dirty) {
                apply(p, m, null, 0, 0);
            }
            p.dirty = false;
        }
        gone.forEach(placements::remove);
    }

    private void apply(Placement p, BbModel m, BbModel.Animation anim, double t, int interpolation) {
        for (Map.Entry<Integer, ItemDisplay> en : p.pieces.entrySet()) {
            int i = en.getKey();
            ItemDisplay d = en.getValue();
            if (i >= m.pieces.size()) { // the model file now has fewer pieces: hide the extra ones
                d.setTransformationMatrix(new org.joml.Matrix4f().scale(0));
                continue;
            }
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(interpolation);
            d.setTransformationMatrix(m.pieceMatrix(i, anim, t, p.scale));
        }
    }

    private boolean playerNear(Placement p) {
        Location l = p.location();
        if (l == null) return false;
        for (Player pl : l.getWorld().getPlayers()) {
            if (pl.getLocation().distanceSquared(l) <= ACTIVE_RANGE * ACTIVE_RANGE) return true;
        }
        return false;
    }

    /** How many models are placed in the loaded chunks (for /mpack model list). */
    public Map<String, Integer> placedCounts() {
        Map<String, Integer> out = new HashMap<>();
        for (Placement p : placements.values()) out.merge(p.model, 1, Integer::sum);
        return out;
    }
}
