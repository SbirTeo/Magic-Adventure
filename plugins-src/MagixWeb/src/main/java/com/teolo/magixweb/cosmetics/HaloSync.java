package com.teolo.magixweb.cosmetics;

import com.teolo.magixweb.MagixWeb;
import com.teolo.magixweb.db.Database;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Copia nel database del sito il colore dell'aureola VIP di ogni giocatore (colonna
 * {@code mc_ranks.halo_color}), cosi' il sito la disegna sulla sua faccia come la corona del
 * miglior sostenitore (vedi {@code avatar_top()} in website/includes/helpers.php).
 *
 * <p>La regola e' quella di MagixCosmetics per le statue ({@code HaloManager#statueColor}):
 * permesso, colore scelto, /halo off, anche da offline (letto da LuckPerms). Nessuna dipendenza
 * Maven: si parla per riflessione, come fa MagixEntities.</p>
 *
 * <p>Si scrive solo cio' che cambia: il giro periodico rilegge tutti, ma tocca il database solo
 * per chi ha ottenuto, perso o cambiato l'aureola.</p>
 */
public final class HaloSync implements Listener {

    private final MagixWeb plugin;
    private final Database database;
    private Object haloManager;
    private Method mStatueColor;
    /** Ultimo valore scritto per uuid ("" = nessuna aureola). Vuota all'avvio: il primo giro scrive tutti. */
    private final Map<String, String> written = new ConcurrentHashMap<>();

    public HaloSync(MagixWeb plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    /** @return true se MagixCosmetics c'e' e ha l'API attesa: solo allora ha senso sincronizzare. */
    public boolean hook() {
        Plugin mc = Bukkit.getPluginManager().getPlugin("MagixCosmetics");
        if (mc == null || !mc.isEnabled()) {
            plugin.getLogger().info("MagixCosmetics non trovato: sul sito nessuna aureola.");
            return false;
        }
        try {
            Object halo = mc.getClass().getMethod("halo").invoke(mc);
            mStatueColor = halo.getClass().getMethod("statueColor", UUID.class);
            haloManager = halo;
            return true;
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("MagixCosmetics con un'API diversa da quella attesa (" + e.getMessage()
                    + "): sul sito nessuna aureola. Aggiorna entrambi i plugin insieme.");
            return false;
        }
    }

    /** Chi entra si aggiorna subito, dopo che RankSync ha scritto la sua riga (lo fa a 2 secondi). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        String uuid = event.getPlayer().getUniqueId().toString();
        Bukkit.getScheduler().runTaskLater(plugin, () -> writeChanges(compute(List.of(uuid))), 60L);
    }

    /**
     * Giro completo su tutti i giocatori noti al sito: legge gli uuid (fuori dal main thread),
     * calcola i colori (sul main thread: MagixCosmetics non e' thread-safe) e scrive i cambi.
     * Per chi e' offline la prima lettura da LuckPerms arriva dopo: il giro successivo la trova.
     */
    public void syncAll() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> uuids = readKnownUuids();
            if (uuids == null || uuids.isEmpty()) return;
            Bukkit.getScheduler().runTask(plugin, () -> writeChanges(compute(uuids)));
        });
    }

    private List<String> readKnownUuids() {
        List<String> out = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT mc_uuid FROM mc_ranks");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
            return out;
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixWeb: lettura giocatori per l'aureola fallita: " + e.getMessage());
            return null;
        }
    }

    /** uuid -> "#RRGGBB" o "" per chi l'ha cambiata rispetto all'ultima scrittura. Main thread. */
    private Map<String, String> compute(Collection<String> uuids) {
        Map<String, String> changes = new HashMap<>();
        for (String uuid : uuids) {
            UUID id;
            try {
                id = UUID.fromString(uuid);
            } catch (IllegalArgumentException e) {
                continue;
            }
            String hex = hexOf(colorOf(id));
            if (!hex.equals(written.get(uuid))) changes.put(uuid, hex);
        }
        return changes;
    }

    private Color colorOf(UUID id) {
        try {
            return (Color) mStatueColor.invoke(haloManager, id);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    private static String hexOf(Color c) {
        return c == null ? "" : String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    private void writeChanges(Map<String, String> changes) {
        if (changes.isEmpty()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection c = database.getConnection();
                 PreparedStatement ps = c.prepareStatement("UPDATE mc_ranks SET halo_color = ? WHERE mc_uuid = ?")) {
                for (Map.Entry<String, String> e : changes.entrySet()) {
                    if (e.getValue().isEmpty()) ps.setNull(1, java.sql.Types.CHAR);
                    else ps.setString(1, e.getValue());
                    ps.setString(2, e.getKey());
                    ps.addBatch();
                }
                ps.executeBatch();
                written.putAll(changes);
            } catch (SQLException e) {
                // Niente in "written": il giro dopo riprova.
                plugin.getLogger().warning("MagixWeb: scrittura dell'aureola sul sito fallita: " + e.getMessage());
            }
        });
    }
}
