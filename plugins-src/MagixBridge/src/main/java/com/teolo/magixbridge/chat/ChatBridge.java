package com.teolo.magixbridge.chat;

import com.teolo.magixbridge.MagixBridge;
import com.teolo.magixbridge.db.Database;
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
 * <p><b>Una chat per server</b> (colonna {@code server}, = network.server-name): sul sito c'e' una
 * scheda per ogni server della rete. Quello che si scrive in gioco va nella scheda del server su
 * cui lo si scrive; quello che si scrive in una scheda del sito lo ripubblica in gioco solo quel
 * server, che lo marca consegnato. La pulizia dello storico la fa solo il server con i lavori del
 * sito (network.site-jobs).
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

    private final MagixBridge plugin;
    private final Database database;
    private final boolean specchiaGioco;
    private final String formato;
    private final int lotto;
    private final int oreDaTenere;
    /** Questo server nella rete (network.server-name): la sua scheda nella chat del sito. */
    private final String server;
    /** Il server che fa la pulizia dello storico (network.site-jobs). */
    private final boolean primario;

    /**
     * Id gia' presi in carico da un giro di consegna: la marcatura {@code delivered=1} avviene
     * dopo il broadcast (per non perdere messaggi se il server si spegne a meta'), quindi senza
     * questo insieme il giro successivo potrebbe rileggere gli stessi id e mandarli due volte.
     */
    private final Set<Long> inDelivery = Collections.synchronizedSet(new HashSet<>());

    public ChatBridge(MagixBridge plugin, Database database, boolean specchiaGioco,
                      String formato, int lotto, int oreDaTenere, String server, boolean primario) {
        this.plugin = plugin;
        this.database = database;
        this.specchiaGioco = specchiaGioco;
        this.formato = formato;
        this.lotto = Math.max(1, lotto);
        this.oreDaTenere = oreDaTenere;
        this.server = server;
        this.primario = primario;
    }

    // -----------------------------------------------------------------------------------------
    // gioco -> sito
    // -----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!specchiaGioco) return;

        Player p = e.getPlayer();
        if (!canalePubblico(p)) return;

        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        if (text.isEmpty()) return;
        if (text.length() > MAX_LUNGHEZZA) text = text.substring(0, MAX_LUNGHEZZA);

        final String message = text;
        final String uuid = p.getUniqueId().toString();
        final String name = p.getName();

        // L'evento e' asincrono di suo, ma non sempre (un messaggio inviato da un plugin puo'
        // arrivare sul main thread): il salvataggio va comunque fuori dal tick.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> save(uuid, name, message));
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

    private void save(String uuid, String name, String message) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO web_chat (source, server, mc_uuid, mc_username, message, delivered) "
                             + "VALUES ('game', ?, ?, ?, ?, 1)")) {
            ps.setString(1, server);
            ps.setString(2, uuid);
            ps.setString(3, name);
            ps.setString(4, message);
            ps.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().warning("MagixBridge: errore salvando un messaggio di chat per il sito: " + ex.getMessage());
        }
    }

    // -----------------------------------------------------------------------------------------
    // sito -> gioco
    // -----------------------------------------------------------------------------------------

    /** Da chiamare periodicamente: legge i messaggi del sito (async) e li manda in chat (main thread). */
    public void deliverToGame() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Message> nuovi = readToDeliver();
            if (nuovi.isEmpty()) return;
            Bukkit.getScheduler().runTask(plugin, () -> pubblica(nuovi));
        });
    }

    private List<Message> readToDeliver() {
        List<Message> out = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     // Il prefisso del grado arriva da mc_ranks (lo scrive RankSync): chi scrive
                     // dal sito di solito NON e' in partita, e per un giocatore offline i
                     // placeholder %luckperms_prefix% non si risolvono.
                     "SELECT c.id, c.mc_uuid, c.mc_username, c.message, r.prefix_raw "
                             + "FROM web_chat c LEFT JOIN mc_ranks r "
                             + "ON r.mc_uuid = c.mc_uuid COLLATE utf8mb4_unicode_ci "
                             + "WHERE c.server = ? AND c.source = 'web' AND c.delivered = 0 ORDER BY c.id LIMIT ?")) {
            ps.setString(1, server);
            ps.setInt(2, lotto);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    if (!inDelivery.add(id)) continue; // gia' in corso in questo momento
                    out.add(new Message(id, rs.getString("mc_uuid"),
                            rs.getString("mc_username"), rs.getString("message"),
                            rs.getString("prefix_raw")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixBridge: errore leggendo la chat del sito: " + e.getMessage());
        }
        return out;
    }

    private void pubblica(List<Message> messages) {
        List<Long> fatti = new ArrayList<>();
        for (Message m : messages) {
            if (!pubblicaConMagixFactions(m)) {
                // Ripiego (MagixFactions assente o troppo vecchio): formato semplice nostro.
                // {message} si sostituisce per ULTIMO, dopo la traduzione dei colori, cosi' un
                // messaggio scritto sul sito resta testo letterale e non puo' colorare la chat.
                String row = ChatColor.translateAlternateColorCodes('&', formato.replace("{name}", m.name))
                        .replace("{message}", m.text);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendMessage(row);
                }
                Bukkit.getConsoleSender().sendMessage(row);
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
    private boolean pubblicaConMagixFactions(Message m) {
        if (m.uuid == null || m.uuid.isEmpty()) {
            return false;
        }
        Object mf = Bukkit.getPluginManager().getPlugin("MagixFactions");
        if (mf == null) {
            return false;
        }
        try {
            java.util.UUID uuid = java.util.UUID.fromString(m.uuid);
            Object outcome = mf.getClass()
                    .getMethod("broadcastWebChat", java.util.UUID.class, String.class, String.class, String.class)
                    .invoke(mf, uuid, m.name, m.text, m.prefisso == null ? "" : m.prefisso);
            return Boolean.TRUE.equals(outcome);
        } catch (NoSuchMethodException e) {
            return false; // versione di MagixFactions precedente a questa API
        } catch (Exception e) {
            plugin.getLogger().warning("MagixBridge: MagixFactions non ha formattato il messaggio dal sito ("
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
                plugin.getLogger().warning("MagixBridge: errore marcando i messaggi del sito come consegnati: " + e.getMessage());
            } finally {
                inDelivery.removeAll(ids);
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
    public void dropBacklog(int minuti) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection c = database.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE web_chat SET delivered = 1 WHERE server = ? AND source = 'web' AND delivered = 0 "
                                 + "AND created_at < DATE_SUB(NOW(), INTERVAL ? MINUTE)")) {
                ps.setString(1, server);
                ps.setInt(2, minuti);
                int n = ps.executeUpdate();
                if (n > 0) {
                    plugin.getLogger().info("MagixBridge: " + n + " messaggi del sito troppo vecchi non ripubblicati in chat.");
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("MagixBridge: errore ripulendo gli arretrati della chat: " + e.getMessage());
            }
        });
    }

    /** Cancella lo storico oltre le ore configurate (0 = tieni tutto). */
    public void trimHistory() {
        if (!primario || oreDaTenere <= 0) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection c = database.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM web_chat WHERE created_at < DATE_SUB(NOW(), INTERVAL ? HOUR)")) {
                ps.setInt(1, oreDaTenere);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("MagixBridge: errore ripulendo lo storico della chat: " + e.getMessage());
            }
        });
    }

    private static final class Message {
        final long id;
        final String uuid;
        final String name;
        final String text;
        /** Prefisso del grado (da mc_ranks), passato a MagixFactions al posto di %luckperms_prefix%. */
        final String prefisso;

        Message(long id, String uuid, String name, String text, String prefisso) {
            this.id = id;
            this.uuid = uuid;
            this.name = name;
            this.text = text;
            this.prefisso = prefisso;
        }
    }
}
