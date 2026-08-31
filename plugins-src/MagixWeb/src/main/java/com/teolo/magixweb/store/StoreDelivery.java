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
 * Consegna degli acquisti dello store: il sito, quando PayPal conferma il pagamento,
 * scrive i comandi nella tabella store_command_queue; qui li leggiamo ed eseguiamo
 * dalla console, marcandoli come fatti.
 *
 * La lettura e' asincrona (non blocca il tick), l'esecuzione avviene sul thread principale
 * perche' i comandi Bukkit non sono thread-safe.
 */
public class StoreDelivery {

    /** Oltre questo numero di tentativi falliti il comando viene abbandonato, per non riprovare all'infinito. */
    private static final int MAX_TENTATIVI = 5;

    private final MagixWeb plugin;
    private final Database database;
    private final int lotto;

    public StoreDelivery(MagixWeb plugin, Database database, int lotto) {
        this.plugin = plugin;
        this.database = database;
        this.lotto = Math.max(1, lotto);
    }

    /** Da chiamare periodicamente: legge la coda (async) e passa l'esecuzione al main thread. */
    public void processaCoda() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Comando> daEseguire = leggi();
            if (daEseguire.isEmpty()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> esegui(daEseguire));
        });
    }

    private List<Comando> leggi() {
        List<Comando> out = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, mc_username, command FROM store_command_queue "
                             + "WHERE executed_at IS NULL AND attempts < ? ORDER BY id LIMIT ?")) {
            ps.setInt(1, MAX_TENTATIVI);
            ps.setInt(2, lotto);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Comando(rs.getInt("id"), rs.getString("mc_username"), rs.getString("command")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixWeb: errore leggendo la coda dello store: " + e.getMessage());
        }
        return out;
    }

    private void esegui(List<Comando> comandi) {
        for (Comando cmd : comandi) {
            boolean ok;
            String errore = null;
            try {
                ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.comando);
                if (!ok) {
                    errore = "comando rifiutato o inesistente";
                }
            } catch (Exception e) {
                ok = false;
                errore = e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            if (ok) {
                plugin.getLogger().info("Store: eseguito per " + cmd.giocatore + " -> /" + cmd.comando);
            } else {
                plugin.getLogger().warning("Store: comando FALLITO per " + cmd.giocatore + " -> /" + cmd.comando
                        + " (" + errore + ")");
            }
            segna(cmd.id, ok, errore);
        }
    }

    /** Successo: marca eseguito. Fallimento: conta il tentativo, cosi' riprova al giro dopo. */
    private void segna(int id, boolean ok, String errore) {
        final String messaggio = errore == null ? null
                : (errore.length() > 255 ? errore.substring(0, 255) : errore);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = ok
                    ? "UPDATE store_command_queue SET executed_at = NOW(), attempts = attempts + 1, last_error = NULL WHERE id = ?"
                    : "UPDATE store_command_queue SET attempts = attempts + 1, last_error = ? WHERE id = ?";
            try (Connection c = database.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                if (ok) {
                    ps.setInt(1, id);
                } else {
                    ps.setString(1, messaggio);
                    ps.setInt(2, id);
                }
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("MagixWeb: errore aggiornando la coda dello store: " + e.getMessage());
            }
        });
    }

    private static final class Comando {
        final int id;
        final String giocatore;
        final String comando;

        Comando(int id, String giocatore, String comando) {
            this.id = id;
            this.giocatore = giocatore;
            this.comando = comando;
        }
    }
}
