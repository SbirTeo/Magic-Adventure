package com.teolo.magixessentials.motd;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

/**
 * Il lato Paper della MOTD: prende il ping che arriva dalla lista server e ci mette dentro quello
 * che {@link MotdText} ha composto. Qui non si decide niente su come e' fatta la MOTD — solo dove
 * va a finire.
 *
 * <p>Sono due classi e non una perche' la MOTD, il giorno che davanti al server ci sara' Velocity,
 * la comporra' il proxy: il testo e le regole si portano di la' cosi' come sono, questo file no.
 * Vedi il perche' per esteso in {@link MotdText}.</p>
 *
 * <p><b>Priorita' HIGH</b>: come per il tablist, l'ultimo che scrive e' quello che si vede. Se un
 * altro plugin tocca il ping, questo arriva dopo.</p>
 */
public final class MotdListener implements Listener {

    /** Un profilo della tendina non e' un giocatore vero: l'id non deve somigliare a nessuno. */
    private static final UUID NO_ONE = new UUID(0L, 0L);

    private final JavaPlugin plugin;
    /** Le impostazioni della MOTD: il {@code motd.yml} della cartella dati. */
    private final ConfigurationSection cfg;
    private boolean registrato;

    public MotdListener(JavaPlugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registrato = true;
    }

    public void stop() {
        if (!registrato) return;
        PaperServerListPingEvent.getHandlerList().unregister(this);
        registrato = false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(PaperServerListPingEvent e) {
        // Il massimo si decide PRIMA di comporre il testo: {max} deve dire quello che il
        // giocatore legge accanto al numero, non un altro numero.
        int max = cfg.getInt("player-count.max", -1);
        if (max > 0) e.setMaxPlayers(max);
        int online = e.getNumPlayers();
        int mostrato = e.getMaxPlayers();

        e.motd(MotdText.component(
                MotdText.scegli(cfg.getBoolean("random.enabled", false),
                        cfg.getStringList("random.messages"),
                        cfg.getString("first-line", ""),
                        cfg.getString("second-line", "")),
                online, mostrato));

        if (cfg.getBoolean("player-count.hide", false)) {
            // Al posto di "3/80" il client disegna le due frecce dei server irraggiungibili: e'
            // il modo che il protocollo ha per dire "il conto non si mostra".
            e.setHidePlayers(true);
            return;   // niente tendina: senza il conto, non c'e' dove appoggiarla
        }

        // La tendina: le righe del file al posto dell'elenco dei giocatori online. Chi c'e'
        // dentro un server lo sa solo chi entra; qui si scrive quello che vogliamo far leggere.
        List<String> tendina = MotdText.tendina(cfg.getStringList("hover"), online, mostrato);
        if (tendina.isEmpty()) return;   // lista vuota = si lascia l'elenco vero dei giocatori
        List<PaperServerListPingEvent.ListedPlayerInfo> voci = e.getListedPlayers();
        voci.clear();
        for (String riga : tendina) voci.add(new PaperServerListPingEvent.ListedPlayerInfo(riga, NO_ONE));
    }
}
