package com.teolo.magixbridge.bridge;

import com.teolo.magixbridge.MagixBridge;
import com.teolo.magixbridge.db.Database;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Placeholders of one game mode, readable on the others.
 *
 * Every mode (faction, hub...) runs its own plugins: MagixFactions exists only on the faction, so
 * on the hub %magixfactions_faction% means nothing. The bridge carries the values through the
 * site database, in three moves:
 *
 *  1. presence: every server writes who it has online right now (network_presence);
 *  2. publishing: a server computes the placeholders listed in bridge.publish (with PlaceholderAPI,
 *     where the plugin that owns them is installed) for its own players AND for the players online
 *     on the other servers, as offline players, and writes the values that changed
 *     (network_placeholders), plus the global ones (no player: top lists, totals);
 *  3. reading: %network_<server>_<placeholder>% (NetworkExpansion) answers from a copy in memory,
 *     refreshed every few seconds for the players online here.
 *
 * A placeholder the owning plugin cannot compute for someone not online there keeps the last
 * value it had when he was. A value PlaceholderAPI cannot resolve at all (the owning plugin is not
 * on this server) is never published: that is how the same config works on every server.
 */
public final class PlaceholderBridge implements Listener {

    /** How long a presence row counts as "online there": a few missed beats, then it is stale. */
    private static final int PRESENCE_SECONDS = 60;

    private final MagixBridge plugin;
    private final Database database;
    private final String server;
    private final List<String> playerKeys;
    private final List<String> globalKeys;

    /** What this server last wrote, per (uuid or "", placeholder): only changes go to the database. */
    private final Map<String, String> published = new ConcurrentHashMap<>();

    /** server -> uuid (or "") -> placeholder -> value: the copy the expansion answers from. */
    private volatile Map<String, Map<String, Map<String, String>>> cache = Map.of();

    /** server -> players online there now (presence seen in the last PRESENCE_SECONDS). */
    private volatile Map<String, Integer> online = Map.of();

    private final AtomicBoolean publishing = new AtomicBoolean();
    private final AtomicBoolean reading = new AtomicBoolean();

    public PlaceholderBridge(MagixBridge plugin, Database database, String server,
                             List<String> playerKeys, List<String> globalKeys) {
        this.plugin = plugin;
        this.database = database;
        this.server = server;
        this.playerKeys = clean(playerKeys);
        this.globalKeys = clean(globalKeys);
    }

    public boolean publishes() {
        return !playerKeys.isEmpty() || !globalKeys.isEmpty();
    }

    // ------------------------------------------------------------------ presence + reading

    /** Main thread: who is here now; then, off the main thread, presence and the fresh copy. */
    public void readTick() {
        if (!reading.compareAndSet(false, true)) {
            return;
        }
        List<String> online = onlineIds();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection c = database.getConnection()) {
                writePresence(c, online);
                cache = readValues(c, online);
                this.online = readOnline(c);
            } catch (SQLException e) {
                plugin.getLogger().warning("MagixBridge: ponte dei placeholder non aggiornato (" + e.getMessage() + ").");
            } finally {
                reading.set(false);
            }
        });
    }

    private void writePresence(Connection c, List<String> online) throws SQLException {
        if (!online.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO network_presence (mc_uuid, server, seen_at) VALUES (?, ?, NOW()) "
                            + "ON DUPLICATE KEY UPDATE server = VALUES(server), seen_at = NOW()")) {
                for (String id : online) {
                    ps.setString(1, id);
                    ps.setString(2, server);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    private Map<String, Integer> readOnline(Connection c) throws SQLException {
        Map<String, Integer> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT server, COUNT(*) FROM network_presence "
                        + "WHERE seen_at > DATE_SUB(NOW(), INTERVAL ? SECOND) GROUP BY server")) {
            ps.setInt(1, PRESENCE_SECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1).toLowerCase(java.util.Locale.ROOT), rs.getInt(2));
                }
            }
        }
        return out;
    }

    /** Players online on that server now (0 if it is off or unknown). */
    public int onlineOn(String fromServer) {
        return online.getOrDefault(fromServer.toLowerCase(java.util.Locale.ROOT), 0);
    }

    /** Players online on the whole network now. */
    public int onlineTotal() {
        int total = 0;
        for (int n : online.values()) {
            total += n;
        }
        return total;
    }

    private Map<String, Map<String, Map<String, String>>> readValues(Connection c, List<String> online)
            throws SQLException {
        Map<String, Map<String, Map<String, String>>> out = new HashMap<>();
        List<String> who = new ArrayList<>(online);
        who.add("");
        String in = String.join(",", Collections.nCopies(who.size(), "?"));
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT server, mc_uuid, placeholder, value FROM network_placeholders WHERE mc_uuid IN (" + in + ")")) {
            for (int i = 0; i < who.size(); i++) {
                ps.setString(i + 1, who.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString(1).toLowerCase(java.util.Locale.ROOT), k -> new HashMap<>())
                            .computeIfAbsent(rs.getString(2), k -> new HashMap<>())
                            .put(rs.getString(3).toLowerCase(java.util.Locale.ROOT), rs.getString(4));
                }
            }
        }
        return out;
    }

    /**
     * The value of a placeholder published by {@code fromServer}: the player's own if there is
     * one, otherwise the global one (top lists, totals). Null if that server never published it.
     */
    public String value(String fromServer, UUID player, String key) {
        Map<String, Map<String, String>> byPlayer = cache.get(fromServer.toLowerCase(java.util.Locale.ROOT));
        if (byPlayer == null) {
            return null;
        }
        String k = key.toLowerCase(java.util.Locale.ROOT);
        if (player != null) {
            Map<String, String> own = byPlayer.get(player.toString());
            if (own != null && own.containsKey(k)) {
                return own.get(k);
            }
        }
        Map<String, String> global = byPlayer.get("");
        return global == null ? null : global.get(k);
    }

    // ------------------------------------------------------------------ publishing

    /**
     * Main thread (PlaceholderAPI expansions expect it): the values for the players here, for
     * the ones online elsewhere (as offline players) and the global ones; only the changed ones
     * are written, off the main thread.
     */
    public void publishTick() {
        if (!publishes() || !publishing.compareAndSet(false, true)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<UUID> elsewhere;
            try {
                elsewhere = onlineElsewhere();
            } catch (SQLException e) {
                elsewhere = List.of();
            }
            List<UUID> others = elsewhere;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Map<String, String[]> changed = new HashMap<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    compute(p, p.getUniqueId().toString(), playerKeys, changed);
                }
                for (UUID id : others) {
                    if (Bukkit.getPlayer(id) == null) {
                        compute(Bukkit.getOfflinePlayer(id), id.toString(), playerKeys, changed);
                    }
                }
                compute(null, "", globalKeys, changed);
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        write(changed);
                    } catch (SQLException e) {
                        plugin.getLogger().warning("MagixBridge: placeholder non pubblicati (" + e.getMessage() + ").");
                        // Forget what was not written, so the next tick tries again.
                        for (String key : changed.keySet()) {
                            published.remove(key);
                        }
                    } finally {
                        publishing.set(false);
                    }
                });
            });
        });
    }

    /**
     * At quit, one last value while the player is still here: the others keep seeing it. And the
     * presence row goes, unless another server has already taken it (he moved over there).
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String id = event.getPlayer().getUniqueId().toString();
        Map<String, String[]> changed = new HashMap<>();
        compute(event.getPlayer(), id, playerKeys, changed);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                write(changed);
                try (Connection c = database.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                             "DELETE FROM network_presence WHERE mc_uuid = ? AND server = ?")) {
                    ps.setString(1, id);
                    ps.setString(2, server);
                    ps.executeUpdate();
                }
            } catch (SQLException ignored) {
                // the row goes stale on its own after PRESENCE_SECONDS
            }
        });
    }

    private void compute(OfflinePlayer player, String id, List<String> keys, Map<String, String[]> changed) {
        for (String key : keys) {
            String raw = "%" + key + "%";
            String value;
            try {
                value = PlaceholderAPI.setPlaceholders(player, raw);
            } catch (RuntimeException e) {
                continue;
            }
            if (value == null || value.equals(raw)) {
                continue;   // not resolvable here: the owning plugin is not on this server
            }
            String cacheKey = id + "|" + key;
            if (!value.equals(published.get(cacheKey))) {
                published.put(cacheKey, value);
                changed.put(cacheKey, new String[] {id, key, value});
            }
        }
    }

    private void write(Map<String, String[]> changed) throws SQLException {
        if (changed.isEmpty()) {
            return;
        }
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO network_placeholders (server, mc_uuid, placeholder, value) VALUES (?, ?, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE value = VALUES(value)")) {
            for (String[] row : changed.values()) {
                ps.setString(1, server);
                ps.setString(2, row[0]);
                ps.setString(3, row[1].toLowerCase(java.util.Locale.ROOT));
                ps.setString(4, row[2]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Players seen online on another server in the last minute. */
    private List<UUID> onlineElsewhere() throws SQLException {
        List<UUID> out = new ArrayList<>();
        if (playerKeys.isEmpty()) {
            return out;
        }
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT mc_uuid FROM network_presence WHERE server <> ? "
                             + "AND seen_at > DATE_SUB(NOW(), INTERVAL ? SECOND)")) {
            ps.setString(1, server);
            ps.setInt(2, PRESENCE_SECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        out.add(UUID.fromString(rs.getString(1)));
                    } catch (IllegalArgumentException ignored) {
                        // not a UUID: skip it
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> onlineIds() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            out.add(p.getUniqueId().toString());
        }
        return out;
    }

    /** Keys as written in the config, with or without the %, spaces trimmed, no duplicates. */
    private static List<String> clean(List<String> keys) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (keys != null) {
            for (String k : keys) {
                if (k == null) continue;
                String t = k.trim();
                if (t.startsWith("%")) t = t.substring(1);
                if (t.endsWith("%")) t = t.substring(0, t.length() - 1);
                if (!t.isEmpty()) out.add(t);
            }
        }
        return List.copyOf(out);
    }
}
