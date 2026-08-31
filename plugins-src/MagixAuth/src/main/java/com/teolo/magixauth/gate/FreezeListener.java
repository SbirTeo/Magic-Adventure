package com.teolo.magixauth.gate;

import com.teolo.magixauth.AuthConfig;
import com.teolo.magixauth.util.Texts;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Iterator;

/**
 * Il congelamento: tutto cio' che un giocatore non ancora entrato non puo' fare.
 *
 * E' volutamente noioso e ripetitivo. Ogni via d'uscita e' chiusa una per una, per iscritto,
 * perche' una sola dimenticata vale quanto non aver messo il cancello: su un server in
 * offline mode, chi si presenta col nome di un altro ha gia' il suo UUID e i suoi permessi
 * addosso — l'unica cosa che lo separa dal suo inventario e' questa lista.
 *
 * Non basta cancellare gli eventi: chi non ha fatto il login non deve nemmeno vedere. La
 * chat degli altri non gli arriva, e lui non compare a nessuno (vedi Visibilita).
 */
public final class FreezeListener implements Listener {

    /**
     * I comandi ammessi al cancello, con tutti i modi di scriverli.
     *
     * L'elenco e' chiuso di proposito: si aggiunge qualcosa qui solo se serve DAVVERO a
     * entrare, perche' ogni voce e' una porta aperta a chi non ha ancora dimostrato di
     * essere se stesso.
     */
    private static final java.util.Set<String> AMMESSI = java.util.Set.of(
            "/login", "/l", "/register", "/reg", "/registrati", "/otp", "/codice");

    private final AuthConfig config;
    private final AuthGate gate;

    public FreezeListener(AuthConfig config, AuthGate gate) {
        this.config = config;
        this.gate = gate;
    }

    // =================================================================================
    // Movimento
    // =================================================================================

    /**
     * Fermo dov'e'.
     *
     * Si confrontano i blocchi e non le coordinate esatte di proposito: girare la testa
     * cambia la posizione a ogni tick, e rimandare indietro il giocatore a ogni movimento
     * del mouse gli farebbe tremare lo schermo senza alcun guadagno.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void movimento(PlayerMoveEvent e) {
        if (!gate.fermo(e.getPlayer()) || e.getTo() == null) {
            return;
        }
        if (e.getFrom().getBlockX() != e.getTo().getBlockX()
                || e.getFrom().getBlockY() != e.getTo().getBlockY()
                || e.getFrom().getBlockZ() != e.getTo().getBlockZ()) {
            e.setCancelled(true);
        }
    }

    /** Nemmeno per mano di altri plugin. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void teletrasporto(PlayerTeleportEvent e) {
        if (gate.fermo(e.getPlayer())
                && e.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            e.setCancelled(true);
        }
    }

    // =================================================================================
    // Parola
    // =================================================================================

    /** Non parla. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void parla(AsyncChatEvent e) {
        if (gate.fermo(e.getPlayer())) {
            e.setCancelled(true);
            return;
        }
        if (!config.nascondiChat) {
            return;
        }
        // E non sente: chi e' fermo al cancello viene tolto dai destinatari. Serve a non
        // far leggere le conversazioni del server a chi sta provando il nome di un altro.
        for (Iterator<Audience> it = e.viewers().iterator(); it.hasNext(); ) {
            Audience chi = it.next();
            if (chi instanceof Player p && gate.fermo(p)) {
                it.remove();
            }
        }
    }

    /** I soli comandi che si possono usare prima di aver fatto l'accesso. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void comando(PlayerCommandPreprocessEvent e) {
        if (!gate.fermo(e.getPlayer())) {
            return;
        }
        // Passano SOLO i comandi che servono a entrare. Tutti gli altri no, nemmeno quelli
        // che sembrano innocui: su un account di staff, un solo comando eseguito prima del
        // login e' esattamente il danno che questo cancello esiste per impedire.
        String primo = e.getMessage().split(" ", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (!AMMESSI.contains(primo)) {
            e.setCancelled(true);
        }
    }

    // =================================================================================
    // Mani
    // =================================================================================

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void interagisce(PlayerInteractEvent e) {
        if (gate.fermo(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void rompe(BlockBreakEvent e) {
        if (gate.fermo(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void piazza(BlockPlaceEvent e) {
        if (gate.fermo(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void butta(PlayerDropItemEvent e) {
        if (gate.fermo(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void raccoglie(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && gate.fermo(p)) {
            e.setCancelled(true);
        }
    }

    // =================================================================================
    // Incolumita'
    // =================================================================================

    /**
     * Non prende danno e non ne fa.
     *
     * Un giocatore fermo allo spawn per due minuti sarebbe altrimenti un bersaglio comodo:
     * lo si potrebbe uccidere prima ancora che abbia potuto dimostrare di essere se stesso.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void danno(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && gate.fermo(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void dannoDaAltri(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && gate.fermo(p)) {
            e.setCancelled(true);
        }
    }

    /** I mostri non se ne accorgono nemmeno. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void bersaglio(EntityTargetEvent e) {
        if (e.getTarget() instanceof Player p && gate.fermo(p)) {
            e.setCancelled(true);
        }
    }

}
