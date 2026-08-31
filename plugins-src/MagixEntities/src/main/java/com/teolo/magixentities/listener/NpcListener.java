package com.teolo.magixentities.listener;

import com.teolo.magixentities.manage.ActionRunner;
import com.teolo.magixentities.manage.MirrorManager;
import com.teolo.magixentities.manage.NpcManager;
import com.teolo.magixentities.model.NpcDef;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

/** Protegge le entita' del plugin e le ricrea se vengono uccise. */
public final class NpcListener implements Listener {

    private final JavaPlugin plugin;
    private final NpcManager npcs;
    private final MirrorManager mirror;
    private final ActionRunner actions;

    public NpcListener(JavaPlugin plugin, NpcManager npcs, MirrorManager mirror, ActionRunner actions) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.mirror = mirror;
        this.actions = actions;
    }

    /** Vale sia per l'entita' vera sia per le copie mirror: le protezioni valgono per entrambe. */
    private boolean isNpc(Entity e) {
        return npcs.anyTagOf(e) != null;
    }

    private boolean flag(String path) {
        return plugin.getConfig().getBoolean("protezioni." + path, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        NpcDef d = npcs.defOf(e.getEntity());
        if (d != null && d.opt("invulnerable", true)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (flag("no-combust") && isNpc(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (!flag("no-target")) return;
        if (isNpc(e.getEntity()) || isNpc(e.getTarget())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) {
        if (flag("no-transform") && isNpc(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (flag("no-pickup") && isNpc(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(EntityPortalEvent e) {
        if (flag("no-portal") && isNpc(e.getEntity())) e.setCancelled(true);
    }

    /**
     * Clic destro sull'entita': esegue i comandi configurati e blocca l'interazione vanilla
     * (es. GUI di scambio del villager). Con l'opzione &quot;interact&quot; attiva l'evento NON viene
     * annullato, cosi' altri plugin (CMI e simili) possono agganciarsi al clic.
     */
    // Priorita' LOWEST e ignoreCancelled=false di proposito: i comandi dell'entita' devono partire
    // anche se qualcun altro (WorldGuard in una regione protetta, CMI, ...) annulla il clic.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        // PlayerInteractAtEntityEvent estende questo evento e condivide la stessa HandlerList:
        // senza questo filtro arriverebbe a entrambi i metodi.
        if (e instanceof PlayerInteractAtEntityEvent) return;
        NpcDef d = npcs.defOf(e.getRightClicked());
        if (d == null) return;
        if (e.getHand() == EquipmentSlot.HAND) actions.run(d, e.getPlayer());
        if (flag("no-interact") && !d.opt("interact", false)) e.setCancelled(true);
    }

    /**
     * Il clic destro puo' arrivare come "interact" o come "interact at" a seconda dell'entita'
     * (i Mannequin, come gli armor stand, usano il secondo) e sono eventi Bukkit distinti: li
     * gestiamo entrambi. La doppia esecuzione la evita il cooldown di ActionRunner.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        NpcDef d = npcs.defOf(e.getRightClicked());
        if (d == null) return;
        if (e.getHand() == EquipmentSlot.HAND) actions.run(d, e.getPlayer());
        if (flag("no-interact") && !d.opt("interact", false)) e.setCancelled(true);
    }

    /** Se qualcuno riesce comunque a ucciderla (es. /kill), la ricreiamo al tick dopo. */
    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        // solo l'entita' vera: le copie mirror le ricrea da se' MirrorManager
        String id = npcs.tagOf(e.getEntity());
        NpcDef d = id == null ? null : npcs.get(id);
        if (d == null) {
            if (npcs.cloneTagOf(e.getEntity()) != null) {
                e.getDrops().clear();
                e.setDroppedExp(0);
            }
            return;
        }
        e.getDrops().clear();
        e.setDroppedExp(0);
        d.uuid = null;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (npcs.get(d.id) != null) {
                npcs.ensure(d);
                npcs.save();
            }
        });
    }

    /** Giocatore uscito: la sua copia mirror non serve piu'. */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        mirror.forget(e.getPlayer());
        actions.forget(e.getPlayer());
    }

    /** Al caricamento del chunk riagganciamo le entita' e ripuliamo doppioni/orfani. */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (e.getChunk().isLoaded()) npcs.onChunkLoad(e.getChunk());
        });
    }
}
