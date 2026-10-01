package com.teolo.magixguard.sanctions;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Le sanzioni valgono su tutta la rete, non solo sul server dove sono state date.
 *
 * Con hub e faction un ban dato sull'hub trova il giocatore sul faction, un mute confermato dal
 * gestionale (che lo registra il server dei lavori col sito) deve zittirlo anche sull'hub, e una
 * revoca fatta altrove non deve lasciarlo zittito qui. Ogni server, ogni pochi secondi:
 *
 *  1. legge le sanzioni registrate dopo l'ultima vista, da chiunque, e fa valere quelle dei suoi
 *     giocatori (ban e kick: fuori; mute: silenziato; avviso: il messaggio);
 *  2. riallinea i mute di chi e' collegato con quelli attivi nel database.
 *
 * Chi entra dopo e' coperto dal controllo all'ingresso e da loadOnJoin, come prima.
 */
public final class NetworkSync {

    private final JavaPlugin plugin;
    private final SanctionsService service;
    private final SanctionsDao dao;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile int lastId = -1;

    public NetworkSync(JavaPlugin plugin, SanctionsService service, SanctionsDao dao) {
        this.plugin = plugin;
        this.service = service;
        this.dao = dao;
    }

    public void start(int seconds) {
        long ticks = Math.max(1, seconds) * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, ticks);
    }

    /** Thread principale: si prende l'elenco di chi e' collegato, il database lo si interroga fuori. */
    private void tick() {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        List<UUID> online = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            online.add(p.getUniqueId());
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (lastId < 0) {
                    // Primo giro: si parte da adesso. Le sanzioni di prima le fa gia' valere
                    // l'ingresso (ban) e loadOnJoin (mute).
                    lastId = dao.lastId();
                    return;
                }
                int[] seen = {lastId};
                List<Sanction> fresh = dao.after(lastId, seen);
                Map<UUID, Sanction> mutes = dao.activeMutes(online);
                lastId = seen[0];
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (Sanction s : fresh) {
                        service.enforceFromNetwork(s);
                    }
                    service.refreshMutes(online, mutes, seen[0]);
                });
            } catch (SQLException e) {
                plugin.getLogger().warning("Sanzioni di rete non lette: " + e.getMessage());
            } finally {
                busy.set(false);
            }
        });
    }
}
