package com.teolo.magixweb.store;

import com.teolo.magixweb.MagixWeb;
import com.teolo.magixweb.db.Database;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Hands out what people buy in the store: when PayPal confirms a payment the site writes the
 * commands into store_command_queue, and this reads them, runs them from the console and marks
 * them done.
 *
 * Reading happens off the main thread so it never holds up a tick; running happens on the main
 * thread, because Bukkit commands are not thread-safe.
 */
public class StoreDelivery {

    /** Past this many failed tries the command is given up on, rather than retried forever. */
    private static final int MAX_ATTEMPTS = 5;

    private final MagixWeb plugin;
    private final Database database;
    private final int batchSize;

    public StoreDelivery(MagixWeb plugin, Database database, int batchSize) {
        this.plugin = plugin;
        this.database = database;
        this.batchSize = Math.max(1, batchSize);
    }

    /** Call this on a timer: reads the queue off-thread, then runs it on the main thread. */
    public void processQueue() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<QueuedCommand> pending = read();
            if (pending.isEmpty()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> run(pending));
        });
    }

    private List<QueuedCommand> read() {
        List<QueuedCommand> out = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, mc_username, command FROM store_command_queue "
                             + "WHERE executed_at IS NULL AND attempts < ? ORDER BY id LIMIT ?")) {
            ps.setInt(1, MAX_ATTEMPTS);
            ps.setInt(2, batchSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new QueuedCommand(rs.getInt("id"), rs.getString("mc_username"), rs.getString("command")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixWeb: errore leggendo la coda dello store: " + e.getMessage());
        }
        return out;
    }

    private void run(List<QueuedCommand> commands) {
        for (QueuedCommand cmd : commands) {
            boolean ok;
            String error = null;
            try {
                ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.command);
                if (!ok) {
                    error = "comando rifiutato o inesistente";
                }
            } catch (Exception e) {
                ok = false;
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            if (ok) {
                plugin.getLogger().info("Store: eseguito per " + cmd.player + " -> /" + cmd.command);
            } else {
                plugin.getLogger().warning("Store: comando FALLITO per " + cmd.player + " -> /" + cmd.command
                        + " (" + error + ")");
            }
            mark(cmd.id, ok, error);
        }
    }

    /** On success, mark it done. On failure, count the attempt so the next round retries it. */
    private void mark(int id, boolean ok, String error) {
        final String message = error == null ? null
                : (error.length() > 255 ? error.substring(0, 255) : error);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = ok
                    ? "UPDATE store_command_queue SET executed_at = NOW(), attempts = attempts + 1, last_error = NULL WHERE id = ?"
                    : "UPDATE store_command_queue SET attempts = attempts + 1, last_error = ? WHERE id = ?";
            try (Connection c = database.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                if (ok) {
                    ps.setInt(1, id);
                } else {
                    ps.setString(1, message);
                    ps.setInt(2, id);
                }
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("MagixWeb: errore aggiornando la coda dello store: " + e.getMessage());
            }
        });
    }

    private static final class QueuedCommand {
        final int id;
        final String player;
        final String command;

        QueuedCommand(int id, String player, String command) {
            this.id = id;
            this.player = player;
            this.command = command;
        }
    }
}
