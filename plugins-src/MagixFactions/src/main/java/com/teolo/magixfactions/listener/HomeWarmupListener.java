package com.teolo.magixfactions.listener;

import com.teolo.magixfactions.hook.Papi;
import com.teolo.magixfactions.lang.Messages;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attesa immobile prima del teletrasporto di {@code /f home} (config {@code home-warmup.seconds}):
 * muoversi (cambiare blocco, non solo direzione dello sguardo) annulla il teletrasporto in corso.
 * Un giocatore puo' avere la sua attesa personale col permesso VIP
 * {@code magixfactions.warmup.home.<secondi>} (es. {@code ...home.0} = istantaneo): fra piu'
 * permessi di questo tipo posseduti vince il piu' BASSO (il piu' favorevole), stesso principio dei
 * permessi numerici di {@link com.teolo.magixfactions.manage.PowerManager}. Chi ha
 * {@code magixfactions.admin} salta il warmup a prescindere: e' lo staff, non ha senso farlo aspettare.
 * Subire QUALSIASI danno (mob, caduta, fuoco, PvP...) annulla il teletrasporto in corso per chi lo
 * subisce, come muoversi: non deve diventare una via di fuga. Attaccare un altro giocatore annulla il
 * PROPRIO warmup anche per l'attaccante, pur senza subire danno. Il fuoco amico gia' bloccato da
 * {@link CombatListener} (priorita' LOW, prima di questo listener) non conta: un colpo annullato non
 * e' un vero danno subito. Un solo handler su {@code EntityDamageEvent} basta per tutti i casi:
 * {@code EntityDamageByEntityEvent} (mob, giocatori, proiettili) non ha una sua HandlerList separata
 * in Bukkit, condivide quella della superclasse.
 */
public final class HomeWarmupListener implements Listener {

    private static final String PERM_WARMUP = "magixfactions.warmup.home.";

    private record Warmup(BukkitTask task, Location from) {}

    private final JavaPlugin plugin;
    private final Messages M;
    private final Map<UUID, Warmup> pending = new ConcurrentHashMap<>();

    public HomeWarmupListener(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.M = messages;
    }

    private void msgKey(Player p, String path, String... kv) {
        p.sendMessage(Papi.resolve(p, M.get(p, path, kv)));
    }

    /**
     * Attesa in secondi per QUEL giocatore: 0 per lo staff ({@code magixfactions.admin}), altrimenti
     * il suo permesso VIP piu' favorevole, o il valore di config.
     */
    private int warmupSeconds(Player p) {
        if (p.hasPermission("magixfactions.admin")) return 0;
        Integer perm = null;
        for (org.bukkit.permissions.PermissionAttachmentInfo pi : p.getEffectivePermissions()) {
            if (!pi.getValue()) continue;
            String n = pi.getPermission();
            if (!n.startsWith(PERM_WARMUP)) continue;
            try {
                int v = Integer.parseInt(n.substring(PERM_WARMUP.length()));
                if (v >= 0) perm = (perm == null ? v : Math.min(perm, v));
            } catch (NumberFormatException ignored) {}
        }
        return perm != null ? perm : Math.max(0, plugin.getConfig().getInt("home-warmup.seconds", 5));
    }

    /**
     * Avvia il warmup per il giocatore {@code p}: se la sua attesa (config o permesso, vedi
     * {@link #warmupSeconds(Player)}) e' 0 esegue {@code onComplete} subito, senza attesa. Un
     * warmup gia' in corso per lo stesso giocatore viene rimpiazzato (si riparte da capo).
     */
    public void start(Player p, Runnable onComplete) {
        int seconds = warmupSeconds(p);
        cancel(p.getUniqueId());
        if (seconds <= 0) { onComplete.run(); return; }
        msgKey(p, "home.warmup-start", "seconds", String.valueOf(seconds));
        UUID uuid = p.getUniqueId();
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pending.remove(uuid);
            if (p.isOnline()) onComplete.run();
        }, seconds * 20L);
        pending.put(uuid, new Warmup(task, p.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Warmup w = pending.get(e.getPlayer().getUniqueId());
        if (w == null) return;
        Location to = e.getTo();
        if (to == null || sameBlock(w.from(), to)) return;
        cancel(e.getPlayer().getUniqueId());
        msgKey(e.getPlayer(), "home.warmup-cancelled");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancel(e.getPlayer().getUniqueId());
    }

    /**
     * Qualsiasi danno subito (mob, caduta, fuoco, PvP...) annulla il warmup di chi lo subisce; se il
     * danno viene da un altro giocatore, annulla anche il warmup DI CHI ATTACCA. {@code ignoreCancelled}
     * salta il fuoco amico gia' bloccato da {@link CombatListener}.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player victim) {
            cancelForReason(victim, "home.warmup-cancelled-damage");
        }
        if (e instanceof EntityDamageByEntityEvent ee) {
            Player attacker = resolvePlayer(ee.getDamager());
            if (attacker != null && !attacker.equals(e.getEntity())) {
                cancelForReason(attacker, "home.warmup-cancelled-pvp");
            }
        }
    }

    /** Il giocatore responsabile del danno: diretto, o il tiratore di un proiettile. */
    private static Player resolvePlayer(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
        }
        return null;
    }

    private void cancelForReason(Player p, String messageKey) {
        if (!pending.containsKey(p.getUniqueId())) return;
        cancel(p.getUniqueId());
        msgKey(p, messageKey);
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getWorld() == b.getWorld()
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private void cancel(UUID uuid) {
        Warmup w = pending.remove(uuid);
        if (w != null) w.task().cancel();
    }
}
