package com.teolo.magixweb.chat;

import com.teolo.magixweb.MagixWeb;
import com.teolo.magixweb.db.Database;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.metadata.MetadataValue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ponte fra la chat del server e la chat live in home del sito (tabella {@code web_chat}).
 *
 * <p>Due direzioni indipendenti:
 * <ul>
 *   <li><b>gioco -&gt; sito</b>: ogni messaggio della chat PUBBLICA viene salvato con
 *       {@code source='game'} (gia' consegnato: il sito lo legge e basta).</li>
 *   <li><b>sito -&gt; gioco</b>: i messaggi scritti sul sito arrivano con {@code source='web'} e
 *       {@code delivered=0}; qui li leggiamo a intervalli, li mandiamo in chat e li marchiamo.</li>
 * </ul>
 *
 * <p><b>La chat di fazione/alleati NON deve finire sul sito.</b> MagixFactions cancella
 * {@code AsyncChatEvent} a priorita' LOW per QUALSIASI canale, quindi qui si ascolta a
 * {@code LOWEST} (prima di lui) e si distingue il canale leggendo il metadata
 * {@code magixfactions:chat-channel} che quel plugin pubblica sul giocatore. Nessun metadata
 * (MagixFactions assente, o giocatore che non ha mai cambiato canale) = chat pubblica.
 */
public class ChatBridge implements Listener {

    /** Stessa chiave di ChatService.META_CHANNEL in MagixFactions (di proposito non c'e' dipendenza fra i due plugin). */
    private static final String META_CANALE = "magixfactions:chat-channel";
    /** Stesso limite della colonna `message` e dell'API del sito. */
    private static final int MAX_LUNGHEZZA = 200;

    private final MagixWeb plugin;
    private final Database database;
    private final boolean specchiaGioco;
    private final String formato;
    private final int lotto;
    private final int oreDaTenere;

    /**
     * Id gia' presi in carico da un giro di consegna: la marcatura {@code delivered=1} avviene
     * dopo il broadcast (per non perdere messaggi se il server si spegne a meta'), quindi senza
     * questo insieme il giro successivo potrebbe rileggere gli stessi id e mandarli due volte.
     */
    private final Set<Long> inConsegna = Collections.synchronizedSet(new HashSet<>());

    public ChatBridge(MagixWeb plugin, Database database, boolean specchiaGioco,
                      String formato, int lotto, int oreDaTenere) {
        this.plugin = plugin;
        this.database = database;
        this.specchiaGioco = specchiaGioco;
        this.formato = formato;
        this.lotto = Math.max(1, lotto);
        this.oreDaTenere = oreDaTenere;
    }

    // -----------------------------------------------------------------------------------------
    // gioco -> sito
    // -----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!specchiaGioco) return;

        Player p = e.getPlayer();
        if (!canalePubblico(p)) return;

        String testo = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        if (testo.isEmpty()) return;
        if (testo.length() > MAX_LUNGHEZZA) testo = testo.substring(0, MAX_LUNGHEZZA);

        final String messaggio = testo;
        final String uuid = p.getUniqueId().toString();
        final String nome = p.getName();

        // L'evento e' asincrono di suo, ma non sempre (un messaggio inviato da un plugin puo'
        // arrivare sul main thread): il salvataggio va comunque fuori dal tick.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> salva(uuid, nome, messaggio));
    }

    /** Il messaggio e' sul canale pubblico? Senza metadata (nessun MagixFactions) si assume di si'. */
    private boolean canalePubblico(Player p) {
        for (MetadataValue v : p.getMetadata(META_CANALE)) {
            String canale = v.asString();
            if (canale != null && !canale.isEmpty() && !canale.equalsIgnoreCase("PUBLIC")) {
                return false;
            }
        }
        return true;
    }

    private void salva(String uuid, String nome, String messaggio) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO web_chat (source, mc_uuid, mc_username, message, delivered) "
                             + "VALUES ('game', ?, ?, ?, 1)")) {
            ps.setString(1, uuid);
            ps.setString(2, nome);
            ps.setString(3, messaggio);
            ps.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().warning("MagixWeb: errore salvando un messaggio di chat per il sito: " + ex.getMessage());
        }
    }

    // -----------------------------------------------------------------------------------------
    // sito -> gioco
    // -----------------------------------------------------------------------------------------

    /** Da chiamare periodicamente: legge i messaggi del sito (async) e li manda in chat (main thread). */
    public void consegnaAlGioco() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Messaggio> nuovi = leggiDaConsegnare();
            if (nuovi.isEmpty()) return;
            Bukkit.getScheduler().runTask(plugin, () -> pubblica(nuovi));
        });
    }

    private List<Messaggio> leggiDaConsegnare() {
        List<Messaggio> out = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     // Il prefisso del grado arriva da mc_ranks (lo scrive RankSync): chi scrive
                     // dal sito di solito NON e' in partita, e per un giocatore offline i
                     // placeholder %luckperms_prefix% non si risolvono.
                     "SELECT c.id, c.mc_uuid, c.mc_username, c.message, r.prefix_raw "
                             + "FROM web_chat c LEFT JOIN mc_ranks r "
                             + "ON r.mc_uuid = c.mc_uuid COLLATE utf8mb4_unicode_ci "
                             + "WHERE c.source = 'web' AND c.delivered = 0 ORDER BY c.id LIMIT ?")) {
            ps.setInt(1, lotto);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    if (!inConsegna.add(id)) continue; // gia' in corso in questo momento
                    out.add(new Messaggio(id, rs.getString("mc_uuid"),
                            rs.getString("mc_username"), rs.getString("message"),
                            rs.getString("prefix_raw")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixWeb: errore leggendo la chat del sito: " + e.getMessage());
        }
        return out;
    }

    private void pubblica(List<Messaggio> messaggi) {
        List<Long> fatti = new ArrayList<>();
        for (Messaggio m : messaggi) {
            if (!pubblicaConMagixFactions(m)) {
                // Ripiego (MagixFactions assente o troppo vecchio): formato semplice nostro.
                // {message} si sostituisce per ULTIMO, dopo la traduzione dei colori, cosi' un
                // messaggio scritto sul sito resta testo letterale e non puo' colorare la chat.
                String riga = ChatColor.translateAlternateColorCodes('&', formato.replace("{name}", m.nome))
                        .replace("{message}", m.testo);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendMessage(riga);
                }
                Bukkit.getConsoleSender().sendMessage(riga);
            }
            fatti.add(m.id);
        }
        marcaConsegnati(fatti);
    }

    /**
     * Fa formattare e mandare il messaggio a MagixFactions, cosi' in gioco un messaggio dal
     * sito appare ESATTAMENTE come uno normale (grado, {@code [fazione]} e nome col colore di
     * relazione di chi legge) — logica che vive li' e non va duplicata qui.
     *
     * <p>Chiamata via <b>reflection</b> di proposito: i due plugin restano indipendenti (nessuna
     * dipendenza di compilazione, nessun ordine di caricamento da garantire) e se il metodo non
     * c'e' si torna al formato semplice invece di rompersi.
     */
    private boolean pubblicaConMagixFactions(Messaggio m) {
        if (m.uuid == null || m.uuid.isEmpty()) {
            return false;
        }
        Object mf = Bukkit.getPluginManager().getPlugin("MagixFactions");
        if (mf == null) {
            return false;
        }
        try {
            java.util.UUID uuid = java.util.UUID.fromString(m.uuid);
            Object esito = mf.getClass()
                    .getMethod("broadcastWebChat", java.util.UUID.class, String.class, String.class, String.class)
                    .invoke(mf, uuid, m.nome, m.testo, m.prefisso == null ? "" : m.prefisso);
            return Boolean.TRUE.equals(esito);
        } catch (NoSuchMethodException e) {
            return false; // versione di MagixFactions precedente a questa API
        } catch (Exception e) {
            plugin.getLogger().warning("MagixWeb: MagixFactions non ha formattato il messaggio dal sito ("
                    + e.getClass().getSimpleName() + "), uso il formato di ripiego.");
            return false;
        }
    }

    private void marcaConsegnati(List<Long> ids) {
        if (ids.isEmpty()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection c = database.getConnection();
                 PreparedStatement ps = c.prepareStatement("UPDATE web_chat SET delivered = 1 WHERE id = ?")) {
                for (Long id : ids) {
                    ps.setLong(1, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                plugin.getLogger().warning("MagixWeb: errore marcando i messaggi del sito come consegnati: " + e.getMessage());
            } finally {
                inConsegna.removeAll(ids);
            }
        });
    }

    // -----------------------------------------------------------------------------------------
    // manutenzione
    // -----------------------------------------------------------------------------------------

    /**
     * All'avvio: i messaggi scritti sul sito mentre il server era spento NON vanno riversati
     * tutti in chat (dopo una notte di down sarebbero decine di righe di colpo). Si marcano
     * come consegnati quelli piu' vecchi di qualche minuto e si riparte da li'.
     */
    public void scartaArretrati(int minuti) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection c = database.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE web_chat SET delivered = 1 WHERE source = 'web' AND delivered = 0 "
                                 + "AND created_at < DATE_SUB(NOW(), INTERVAL ? MINUTE)")) {
                ps.setInt(1, minuti);
                int n = ps.executeUpdate();
                if (n > 0) {
                    plugin.getLogger().info("MagixWeb: " + n + " messaggi del sito troppo vecchi non ripubblicati in chat.");
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("MagixWeb: errore ripulendo gli arretrati della chat: " + e.getMessage());
            }
        });
    }

    /** Cancella lo storico oltre le ore configurate (0 = tieni tutto). */
    public void pulisciStorico() {
        if (oreDaTenere <= 0) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection c = database.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM web_chat WHERE created_at < DATE_SUB(NOW(), INTERVAL ? HOUR)")) {
                ps.setInt(1, oreDaTenere);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("MagixWeb: errore ripulendo lo storico della chat: " + e.getMessage());
            }
        });
    }

    private static final class Messaggio {
        final long id;
        final String uuid;
        final String nome;
        final String testo;
        /** Prefisso del grado (da mc_ranks), passato a MagixFactions al posto di %luckperms_prefix%. */
        final String prefisso;

        Messaggio(long id, String uuid, String nome, String testo, String prefisso) {
            this.id = id;
            this.uuid = uuid;
            this.nome = nome;
            this.testo = testo;
            this.prefisso = prefisso;
        }
    }
}
