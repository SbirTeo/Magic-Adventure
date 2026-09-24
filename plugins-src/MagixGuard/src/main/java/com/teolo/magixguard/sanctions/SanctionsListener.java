package com.teolo.magixguard.sanctions;

import com.teolo.magixguard.lang.Messages;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;

/**
 * Dove i provvedimenti diventano effettivi in partita: l'ingresso e la chat.
 *
 * <p>Il controllo del ban avviene <b>prima</b> del login vero e proprio, mentre siamo ancora
 * su un thread di rete: e' l'unico punto in cui si puo' interrogare il database senza far
 * aspettare il server, ed e' anche il momento giusto — chi e' bandito non deve nemmeno
 * comparire nel mondo.</p>
 */
public final class SanctionsListener implements Listener {

    private final JavaPlugin plugin;
    private final SanctionsConfig cfg;
    private final SanctionsService service;
    private final SanctionsDao dao;
    private final Messages messages;

    public SanctionsListener(JavaPlugin plugin, SanctionsConfig cfg, SanctionsService service, SanctionsDao dao,
                              Messages messages) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.service = service;
        this.dao = dao;
        this.messages = messages;
    }

    /** Chi ha un ban attivo non entra. */
    @EventHandler(priority = EventPriority.HIGH)
    public void suPreLogin(AsyncPlayerPreLoginEvent e) {
        try {
            List<Sanction> attive = dao.attiveInGioco(e.getUniqueId());
            for (Sanction s : attive) {
                if (s.type() == Type.BAN) {
                    e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                            Text.c(service.banMessage(s)));
                    return;
                }
            }
        } catch (SQLException ex) {
            // Il database del sito non risponde. NON si blocca l'ingresso: un guasto nostro
            // non deve trasformarsi in un server chiuso. Lo staff lo legge nel log.
            plugin.getLogger().warning("Controllo ban non riuscito per " + e.getName()
                    + " (" + ex.getMessage() + "): lo lascio entrare.");
        }
    }

    /** Appena entrato si rilegge il suo stato, cosi' la chat sa gia' se e' silenziato. */
    @EventHandler
    public void suIngresso(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> service.loadOnJoin(e.getPlayer().getUniqueId()));
    }

    @EventHandler
    public void suUscita(PlayerQuitEvent e) {
        service.forget(e.getPlayer().getUniqueId());
    }

    /** Chi e' silenziato non scrive. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void suChat(AsyncChatEvent e) {
        Sanction s = service.muto(e.getPlayer().getUniqueId());
        if (s == null) {
            return;
        }
        e.setCancelled(true);
        String scadenza = s.fine() == Duration.PERMANENTE
                ? messages.get(e.getPlayer(), "service.mute-expiry-never") : Duration.mancante(s.fine());
        e.getPlayer().sendMessage(Text.msg(Text.replace(
                cfg.muteMessage, "{motivo}", s.reason(), "{scadenza}", scadenza)));
    }
}
