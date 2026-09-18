package com.teolo.magixpack.pack;

import com.teolo.magixpack.util.Colors;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Rende OBBLIGATORIO il resource pack del server: lo invia a ogni giocatore al join e, se il client
 * non lo carica, lo espelle con un messaggio configurabile.
 *
 * <p>Il flag "force" del pacchetto (vedi {@link PackService#sendTo}) da solo NON basta: e' il
 * client a decidere se rispettarlo, e ci sono casi in cui il pack non arriva comunque (client
 * modificati, mod che disattivano i pack, download fallito, hash non combaciante, il giocatore che
 * rimuove il pack dalle impostazioni). Qui si chiude il cerchio lato server, sull'ESITO reale
 * riportato dal client ({@link PlayerResourcePackStatusEvent}):
 * <ul>
 *   <li>{@code SUCCESSFULLY_LOADED} → tutto a posto, nessuna azione;</li>
 *   <li>{@code DECLINED} → rifiutato dal giocatore → espulso (messaggio "declined");</li>
 *   <li>{@code FAILED_DOWNLOAD / INVALID_URL / FAILED_RELOAD / DISCARDED} → non caricato per un
 *       problema tecnico → espulso (messaggio "failed");</li>
 *   <li>{@code ACCEPTED / DOWNLOADED} → in corso: il conto alla rovescia RIPARTE (scaricare richiede
 *       tempo, e il pack puo' crescere in futuro);</li>
 *   <li>nessuna risposta entro {@code timeout-seconds} → espulso (messaggio "timeout"). E' il caso
 *       "strano" in cui il client non risponde affatto e altrimenti resterebbe dentro senza pack.</li>
 * </ul>
 *
 * <p><b>Salvaguardie contro il lockout</b> (un pack non raggiungibile espellerebbe l'INTERO server):
 * chi ha il permesso {@code magixpack.bypass} (default op) riceve il pack ma non viene mai espulso,
 * e se il servizio HTTP non e' partito nessuno viene espulso (il pack semplicemente non e'
 * obbligatorio finche' non e' servibile). In piu', al ripetersi di fallimenti di DOWNLOAD la
 * console avvisa con l'URL da verificare (porta chiusa sul firewall = sintomo tipico).
 */
public final class PackListener implements Listener {

    private final JavaPlugin plugin;
    private final PackService service;
    /** Giocatori a cui il pack e' stato inviato e di cui si aspetta ancora l'esito: uuid → task di timeout. */
    private final Map<UUID, BukkitTask> pending = new HashMap<>();
    /** Fallimenti di download consecutivi (azzerati da ogni caricamento riuscito): spia di pack irraggiungibile. */
    private int consecutiveDownloadFailures;

    public PackListener(JavaPlugin plugin, PackService service) {
        this.plugin = plugin;
        this.service = service;
    }

    // --------------------------------------------------------------------------------------------
    // Invio al join
    // --------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!service.isRequired()) return;
        if (!service.isAvailable()) {
            plugin.getLogger().warning("[ResourcePack] Pacchetto obbligatorio ma non servibile "
                    + "(public-host non configurato o server HTTP non avviato): nessuna espulsione, "
                    + "i giocatori entrano senza pack.");
            return;
        }
        Player p = e.getPlayer();
        int delay = Math.max(0, plugin.getConfig().getInt("send-delay-ticks", 20));
        // Piccolo ritardo di default (1s): al primo tick del join il client sta ancora ricevendo
        // chunk/inventario, e alcune build rispondono al pack piu' pigramente proprio li'.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            service.sendTo(p);
            if (!isExempt(p)) armTimeout(p);
        }, delay);
    }

    // --------------------------------------------------------------------------------------------
    // Esito riportato dal client
    // --------------------------------------------------------------------------------------------

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        Player p = e.getPlayer();
        plugin.getLogger().info("[ResourcePack] " + p.getName() + ": " + e.getStatus());

        if (!service.isRequired() || isExempt(p)) { cancelTimeout(p.getUniqueId()); return; }

        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                cancelTimeout(p.getUniqueId());
                consecutiveDownloadFailures = 0;
            }
            // In corso: il client ha accettato / sta applicando. Non e' un esito, ma e' un segno di
            // vita: si riparte col conto alla rovescia invece di espellere chi ha solo una linea lenta.
            case ACCEPTED, DOWNLOADED -> armTimeout(p);
            case DECLINED -> kick(p, "declined");
            case FAILED_DOWNLOAD, INVALID_URL -> {
                consecutiveDownloadFailures++;
                if (consecutiveDownloadFailures % 3 == 0) {
                    plugin.getLogger().severe("[ResourcePack] " + consecutiveDownloadFailures
                            + " download falliti di fila: verifica che " + service.publicUrl()
                            + " sia raggiungibile dai client (porta aperta sul firewall del server). "
                            + "Finche' non lo e', ogni giocatore viene espulso al join.");
                }
                kick(p, "failed");
            }
            case FAILED_RELOAD, DISCARDED -> kick(p, "failed");
        }
    }

    /** Il timer non deve sopravvivere al giocatore (ne' espellere chi e' gia' uscito). */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancelTimeout(e.getPlayer().getUniqueId());
    }

    // --------------------------------------------------------------------------------------------
    // Timeout ed espulsione
    // --------------------------------------------------------------------------------------------

    /** (Ri)avvia il conto alla rovescia per questo giocatore. 0 o meno = timeout disattivato. */
    private void armTimeout(Player p) {
        cancelTimeout(p.getUniqueId());
        int seconds = plugin.getConfig().getInt("timeout-seconds", 30);
        if (seconds <= 0) return;
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(p.getUniqueId());
            if (p.isOnline()) kick(p, "timeout");
        }, seconds * 20L);
        pending.put(p.getUniqueId(), task);
    }

    private void cancelTimeout(UUID uuid) {
        BukkitTask task = pending.remove(uuid);
        if (task != null) task.cancel();
    }

    /**
     * Espelle il giocatore col messaggio configurato per il motivo dato ({@code declined}, {@code failed},
     * {@code timeout}). Sempre sul thread principale: l'esito del pack puo' arrivare da un thread di rete.
     */
    private void kick(Player p, String reason) {
        cancelTimeout(p.getUniqueId());
        String raw = plugin.getConfig().getString("kick-messages." + reason);
        if (raw == null || raw.isBlank()) {
            raw = plugin.getConfig().getString("kick-messages.declined",
                    "&cIl pacchetto risorse e' OBBLIGATORIO su questo server.");
        }
        var message = LegacyComponentSerializer.legacySection().deserialize(Colors.translate(raw));
        Runnable doKick = () -> {
            if (!p.isOnline()) return;
            plugin.getLogger().info("[ResourcePack] " + p.getName() + " espulso: pacchetto non caricato ("
                    + reason + ").");
            p.kick(message);
        };
        if (Bukkit.isPrimaryThread()) doKick.run();
        else Bukkit.getScheduler().runTask(plugin, doKick);
    }

    /** Chi non viene mai espulso (riceve comunque il pack): serve a non restare chiusi fuori dal
     *  proprio server se il pack diventa irraggiungibile. */
    private boolean isExempt(Player p) {
        return p.hasPermission("magixpack.bypass");
    }
}
