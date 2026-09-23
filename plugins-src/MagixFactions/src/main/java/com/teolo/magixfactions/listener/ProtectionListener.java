package com.teolo.magixfactions.listener;

import com.teolo.magixfactions.lang.Messages;
import com.teolo.magixfactions.manage.ClaimManager;
import com.teolo.magixfactions.manage.FactionManager;
import com.teolo.magixfactions.model.Faction;
import com.teolo.magixfactions.model.RelationType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
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
 *
 * <p>Oltre alla rottura/posa "a mano", protegge la land anche dai vettori di distruzione che NON passano
 * da un giocatore diretto e quindi aggiravano la protezione: esplosioni (TNT/creeper/end crystal), fuoco
 * (propagazione e bruciatura), pistoni che spingono/rubano blocchi oltre il confine, e flusso di liquidi
 * (acqua/lava) che entra in un claim da fuori. Ognuno ha un suo interruttore sotto {@code protection.*}.
 */
public final class ProtectionListener implements Listener {

    private final JavaPlugin plugin;
    private final FactionManager fm;
    private final ClaimManager claims;
    private final Messages M;
    private final Map<UUID, Long> lastDenyMsg = new HashMap<>();
    /**
     * Chi ha il permesso di bypass ma lo ha SPENTO di sua scelta ({@code /mf admin bypass off}), per
     * poter testare la protezione come un giocatore normale senza doversi togliere il permesso.
     * Chi non e' in questo insieme bypassa (se ha il permesso), come sempre di fabbrica.
     */
    private final java.util.Set<UUID> bypassOff = new java.util.HashSet<>();

    public ProtectionListener(JavaPlugin plugin, FactionManager fm, ClaimManager claims, Messages messages) {
        this.plugin = plugin; this.fm = fm; this.claims = claims; this.M = messages;
    }

    private boolean enabled() { return plugin.getConfig().getBoolean("protection.enabled", true); }

    /** true se {@code p} bypassa la protezione adesso: ha il permesso E non l'ha spento lui stesso. */
    private boolean bypass(Player p) {
        return (p.hasPermission("magixfactions.bypass") || p.hasPermission("magixfactions.admin"))
                && !bypassOff.contains(p.getUniqueId());
    }

    /** Se {@code id} bypassa adesso, a prescindere dal permesso (per il messaggio di /mf admin bypass). */
    public boolean isBypassOn(UUID id) {
        return !bypassOff.contains(id);
    }

    /**
     * Attiva/disattiva il bypass per {@code id}. Non da' ne' toglie il permesso: chi non ha
     * {@code magixfactions.bypass}/{@code .admin} resta comunque protetto come chiunque altro.
     */
    public void setBypass(UUID id, boolean enabled) {
        if (enabled) bypassOff.remove(id); else bypassOff.add(id);
    }

    /** Inverte lo stato attuale e torna quello NUOVO (true = ora bypassa). */
    public boolean toggleBypass(UUID id) {
        if (bypassOff.remove(id)) return true;   // era spento (era nell'insieme): ora acceso
        bypassOff.add(id);
        return false;                            // era acceso: ora spento
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
        if (throttled(p)) return;                               // anti-spam
        p.sendMessage(M.prefix() + M.get(p, "protection.denied", "name", owner != null ? owner.getName() : "?"));
    }

    /** Rifiuto specifico: la land ha un PROPRIETARIO (/f owner) e chi agisce non e' lui ne' il leader. */
    private void denyOwner(Player p, UUID ownerUuid) {
        if (throttled(p)) return;
        String name = Bukkit.getOfflinePlayer(ownerUuid).getName();
        p.sendMessage(M.prefix() + M.get(p, "protection.owner-denied", "owner", name != null ? name : "?"));
    }

    /** true se il messaggio di rifiuto e' gia' stato mostrato da meno di 1,5s (tenere premuto non spamma). */
    private boolean throttled(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastDenyMsg.get(p.getUniqueId());
        if (last != null && now - last < 1500) return true;
        lastDenyMsg.put(p.getUniqueId(), now);
        return false;
    }

    /** true se {@code p} puo' agire in {@code loc}; altrimenti avvisa e ritorna false. */
    private boolean allow(Player p, Location loc) {
        if (!enabled() || bypass(p)) return true;
        Faction owner = blockingOwner(p, loc);
        if (owner != null) { deny(p, owner); return false; }
        // Dentro il PROPRIO territorio: se il chunk ha un proprietario (/f owner), solo lui e il leader agiscono.
        UUID lockOwner = ownerLock(p, loc);
        if (lockOwner != null) { denyOwner(p, lockOwner); return false; }
        return true;
    }

    /**
     * UUID del proprietario della land se {@code p} e' un compagno di fazione BLOCCATO dal proprietario
     * per-chunk (/f owner); null se puo' agire (nessun proprietario impostato, oppure e' lui o il leader,
     * oppure non e' terreno della sua fazione — quel caso lo copre gia' {@link #blockingOwner}).
     */
    private UUID ownerLock(Player p, Location loc) {
        if (loc.getWorld() == null) return null;
        String world = loc.getWorld().getName();
        int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4;
        Long ownerId = claims.owner(world, cx, cz);
        if (ownerId == null) return null;
        Faction own = fm.getFaction(p.getUniqueId());
        if (own == null || own.getId() != ownerId) return null;     // non e' terra sua: lo gestisce blockingOwner
        String co = claims.chunkOwnerUuid(world, cx, cz);
        if (co == null) return null;                                // nessun proprietario: liberi tutti i membri
        UUID ownerUuid;
        try { ownerUuid = UUID.fromString(co); } catch (IllegalArgumentException e) { return null; }
        if (p.getUniqueId().equals(ownerUuid)) return null;         // e' il proprietario
        if (own.getLeader() != null && p.getUniqueId().equals(own.getLeader())) return null; // il leader sempre
        return ownerUuid;                                           // compagno senza titolo: bloccato
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

    // ============================ PROTEZIONE AMBIENTALE ============================
    // Vettori di distruzione che NON hanno un giocatore diretto (o lo aggirano): senza questi handler
    // chiunque poteva far saltare/bruciare/svuotare col pistone la land altrui pur non potendo rompere
    // a mano. Tutti rispettano protection.enabled + il proprio interruttore, e proteggono QUALSIASI land
    // claimata (l'esplosione non ha una "fazione autrice" affidabile: la terra dentro i confini e' safe).

    /** Fazione proprietaria del chunk del blocco, o null se terreno neutrale. */
    private Long ownerAt(Block b) {
        return claims.owner(b.getWorld().getName(), b.getX() >> 4, b.getZ() >> 4);
    }

    /** true se il blocco sta dentro un chunk claimato (da chiunque). */
    private boolean isClaimed(Block b) { return ownerAt(b) != null; }

    /** Esplosioni da entita' (TNT, creeper, end crystal, ghast...): togli i blocchi dentro i claim. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!enabled() || !plugin.getConfig().getBoolean("protection.block-explosions", true)) return;
        e.blockList().removeIf(this::isClaimed);
    }

    /** Esplosioni da blocco (letto/anchor nel mondo sbagliato, ecc.): togli i blocchi dentro i claim. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!enabled() || !plugin.getConfig().getBoolean("protection.block-explosions", true)) return;
        e.blockList().removeIf(this::isClaimed);
    }

    /** Fuoco che CONSUMA un blocco dentro un claim: annullato. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent e) {
        if (!enabled() || !plugin.getConfig().getBoolean("protection.block-fire", true)) return;
        if (isClaimed(e.getBlock())) e.setCancelled(true);
    }

    /** Accensione del fuoco: un giocatore segue le regole normali (acciarino nel proprio territorio ok);
     *  la propagazione o la lava che appicca dentro un claim viene bloccata. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent e) {
        if (!enabled() || !plugin.getConfig().getBoolean("protection.block-fire", true)) return;
        Player p = e.getPlayer();
        if (p != null) {
            if (!allow(p, e.getBlock().getLocation())) e.setCancelled(true);
            return;
        }
        if (isClaimed(e.getBlock())) e.setCancelled(true);
    }

    /** Pistone che spinge blocchi: bloccato se tocca (muove o invade) un claim di un'ALTRA fazione. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (!enabled() || !plugin.getConfig().getBoolean("protection.block-pistons", true)) return;
        if (pistonViolates(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    /** Pistone appiccicoso che tira blocchi: bloccato se strappa blocchi da un claim di un'ALTRA fazione. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (!enabled() || !plugin.getConfig().getBoolean("protection.block-pistons", true)) return;
        if (pistonViolates(e.getBlock(), e.getBlocks(), e.getDirection().getOppositeFace())) e.setCancelled(true);
    }

    /**
     * true se un pistone la cui base sta in {@code base} (fazione o neutrale) muove blocchi che si trovano
     * — o che finirebbero — in un claim di una fazione DIVERSA. Cosi' non si puo' piazzare un pistone
     * appena oltre il confine per rubare/spingere blocchi dentro la land altrui, ne' invadere un claim da
     * terreno neutrale. I pistoni dentro il proprio territorio (o verso terreno neutrale) restano liberi.
     */
    private boolean pistonViolates(Block piston, List<Block> moved, BlockFace travelDir) {
        Long base = ownerAt(piston);
        for (Block b : moved) {
            Long from = ownerAt(b);
            if (from != null && !from.equals(base)) return true;      // muove un blocco di un altro claim
            Long dest = ownerAt(b.getRelative(travelDir));
            if (dest != null && !dest.equals(base)) return true;      // lo spinge dentro un altro claim
        }
        return false;
    }

    /** Flusso di liquidi (acqua/lava): bloccato se entra in un claim provenendo da fuori o da un'altra land. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLiquidFlow(BlockFromToEvent e) {
        if (!enabled() || !plugin.getConfig().getBoolean("protection.block-liquid-flow", true)) return;
        Long to = ownerAt(e.getToBlock());
        if (to == null) return;                                       // scorre verso terreno neutrale: libero
        Long from = ownerAt(e.getBlock());
        if (!to.equals(from)) e.setCancelled(true);                   // entra in un claim da fuori/altra land
    }
}
