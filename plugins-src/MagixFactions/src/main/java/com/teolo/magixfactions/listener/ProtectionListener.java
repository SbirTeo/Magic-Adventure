package com.teolo.magixfactions.listener;

import com.teolo.magixfactions.lang.Messages;
import com.teolo.magixfactions.manage.ClaimManager;
import com.teolo.magixfactions.manage.FactionManager;
import com.teolo.magixfactions.model.Faction;
import com.teolo.magixfactions.model.RelationType;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Protezione dei territori: impedisce a chi NON e' della fazione proprietaria di rompere/posare blocchi
 * e di interagire (casse, porte, leve, secchi, item frame, armor stand...) dentro un chunk claimato.
 * Terreno neutrale (non claimato) resta libero per tutti. Il proprio territorio e' sempre modificabile;
 * gli alleati solo se {@code protection.ally-can-build} e' true (default false).
 *
 * <p>Bypass per staff: {@code magixfactions.bypass} oppure {@code magixfactions.admin} (di default OP),
 * cosi' gli admin possono sempre costruire ovunque. Il messaggio di rifiuto ha un piccolo cooldown
 * per-giocatore, cosi' tenendo premuto per rompere non spamma la chat.
 */
public final class ProtectionListener implements Listener {

    private final JavaPlugin plugin;
    private final FactionManager fm;
    private final ClaimManager claims;
    private final Messages M;
    private final Map<UUID, Long> lastDenyMsg = new HashMap<>();

    public ProtectionListener(JavaPlugin plugin, FactionManager fm, ClaimManager claims, Messages messages) {
        this.plugin = plugin; this.fm = fm; this.claims = claims; this.M = messages;
    }

    private boolean enabled() { return plugin.getConfig().getBoolean("protection.enabled", true); }

    private boolean bypass(Player p) {
        return p.hasPermission("magixfactions.bypass") || p.hasPermission("magixfactions.admin");
    }

    /**
     * Fazione proprietaria del chunk che BLOCCA l'azione di {@code p} in {@code loc}, oppure null se
     * {@code p} puo' agire (terreno neutrale, proprio territorio, o alleato con permesso).
     */
    private Faction blockingOwner(Player p, Location loc) {
        if (loc.getWorld() == null) return null;
        Long owner = claims.owner(loc.getWorld().getName(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        if (owner == null) return null;                         // terreno neutrale: libero
        Faction own = fm.getFaction(p.getUniqueId());
        if (own != null && own.getId() == owner) return null;   // proprio territorio
        if (own != null && plugin.getConfig().getBoolean("protection.ally-can-build", false)
                && fm.effectiveRelation(own.getId(), owner) == RelationType.ALLY) return null;
        return fm.getById(owner);
    }

    private void deny(Player p, Faction owner) {
        long now = System.currentTimeMillis();
        Long last = lastDenyMsg.get(p.getUniqueId());
        if (last != null && now - last < 1500) return;          // anti-spam
        lastDenyMsg.put(p.getUniqueId(), now);
        p.sendMessage(M.prefix() + M.get("protection.denied", "name", owner != null ? owner.getName() : "?"));
    }

    /** true se {@code p} puo' agire in {@code loc}; altrimenti avvisa e ritorna false. */
    private boolean allow(Player p, Location loc) {
        if (!enabled() || bypass(p)) return true;
        Faction owner = blockingOwner(p, loc);
        if (owner == null) return true;
        deny(p, owner);
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!allow(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!allow(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || !b.getType().isInteractable()) return; // solo blocchi "interagibili" (casse, porte, leve...)
        if (!allow(e.getPlayer(), b.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Block target = e.getBlockClicked().getRelative(e.getBlockFace());
        if (!allow(e.getPlayer(), target.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (!allow(e.getPlayer(), e.getBlockClicked().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if (!protectedEntity(t)) return;
        if (!allow(e.getPlayer(), t.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (!allow(e.getPlayer(), e.getRightClicked().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Player p = resolvePlayer(e.getRemover());
        if (p == null) return;
        if (!allow(p, e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        if (!protectedEntity(e.getEntity())) return;
        Player p = resolvePlayer(e.getDamager());
        if (p == null) return;
        if (!allow(p, e.getEntity().getLocation())) e.setCancelled(true);
    }

    /** Entita' "arredo" protette come i blocchi (item frame, quadri, armor stand). */
    private boolean protectedEntity(Entity t) {
        return switch (t.getType()) {
            case ITEM_FRAME, GLOW_ITEM_FRAME, PAINTING, ARMOR_STAND -> true;
            default -> false;
        };
    }

    /** Il giocatore responsabile del danno: diretto, o il tiratore di un proiettile. */
    private Player resolvePlayer(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
        }
        return null;
    }
}
