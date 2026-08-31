package com.teolo.magixguard.sanctions;

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
    private final SanctionsService servizio;
    private final SanctionsDao dao;

    public SanctionsListener(JavaPlugin plugin, SanctionsConfig cfg, SanctionsService servizio, SanctionsDao dao) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.servizio = servizio;
        this.dao = dao;
    }

    /** Chi ha un ban attivo non entra. */
    @EventHandler(priority = EventPriority.HIGH)
    public void suPreLogin(AsyncPlayerPreLoginEvent e) {
        try {
            List<Sanction> attive = dao.attiveInGioco(e.getUniqueId());
            for (Sanction s : attive) {
                if (s.tipo() == Type.BAN) {
                    e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                            Text.c(servizio.messaggioBan(s)));
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
                () -> servizio.caricaAllIngresso(e.getPlayer().getUniqueId()));
    }

    @EventHandler
    public void suUscita(PlayerQuitEvent e) {
        servizio.dimentica(e.getPlayer().getUniqueId());
    }

    /** Chi e' silenziato non scrive. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void suChat(AsyncChatEvent e) {
        Sanction s = servizio.muto(e.getPlayer().getUniqueId());
        if (s == null) {
            return;
        }
        e.setCancelled(true);
        String scadenza = s.fine() == Duration.PERMANENTE ? "non scade" : Duration.mancante(s.fine());
        e.getPlayer().sendMessage(Text.msg(Text.sostituisci(
                cfg.messaggioMute, "{motivo}", s.motivo(), "{scadenza}", scadenza)));
    }
}
