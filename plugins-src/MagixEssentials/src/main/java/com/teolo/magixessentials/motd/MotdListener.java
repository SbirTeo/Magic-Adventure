package com.teolo.magixessentials.motd;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.CachedServerIcon;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Il lato Paper della MOTD: prende il ping che arriva dalla lista server e ci mette dentro quello
 * che {@link MotdText} ha composto e {@link MotdRotation} ha scelto. Qui non si decide niente su
 * come e' fatta la MOTD — solo dove va a finire, piu' le due cose che vivono solo qui: l'icona e
 * il conto dei giocatori.
 *
 * <p>Sono classi separate perche' la MOTD, il giorno che davanti al server ci sara' Velocity, la
 * comporra' il proxy: il testo e le regole si portano di la' cosi' come sono, questo file no.
 * Vedi il perche' per esteso in {@link MotdText}.</p>
 *
 * <p><b>Priorita' HIGH</b>: come per il tablist, l'ultimo che scrive e' quello che si vede. Se un
 * altro plugin tocca il ping, questo arriva dopo.</p>
 */
public final class MotdListener implements Listener {

    /** Un profilo della tendina non e' un giocatore vero: l'id non deve somigliare a nessuno. */
    private static final UUID NO_ONE = new UUID(0L, 0L);

    /**
     * Il numero di protocollo che nessun client puo' avere: il gioco lo legge come "versione
     * incompatibile" e al posto del conto dei giocatori scrive il testo della versione. E' l'unico
     * modo che il protocollo offre per far vedere quel testo a tutti.
     */
    private static final int PROTOCOL_MISMATCH = -1;

    private final JavaPlugin plugin;
    /** Le impostazioni della MOTD: il {@code motd.yml} della cartella dati. */
    private final ConfigurationSection cfg;
    /** Quale MOTD adesso, e quale icona: due rotazioni distinte, con la stessa regola. */
    private final MotdRotation rotazioneTesti;
    private final MotdRotation rotazioneIcone;
    /** Le icone gia' caricate, nell'ordine del file. Vuota = si lascia quella del server. */
    private final List<CachedServerIcon> icone = new ArrayList<>();
    private boolean registrato;

    public MotdListener(JavaPlugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
        String modo = cfg.getString("selection", MotdRotation.RANDOM);
        int ogni = cfg.getInt("change-every-seconds", 0);
        boolean evita = cfg.getBoolean("avoid-repeat", true);
        this.rotazioneTesti = new MotdRotation(modo, ogni, evita);
        this.rotazioneIcone = new MotdRotation(modo, ogni, evita);
    }

    public void start() {
        caricaIcone();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registrato = true;
    }

    public void stop() {
        if (!registrato) return;
        PaperServerListPingEvent.getHandlerList().unregister(this);
        registrato = false;
        icone.clear();
    }

    /**
     * Le icone si leggono una volta sola, all'avvio: convertire un PNG a ogni ping sarebbe lavoro
     * inutile fatto spesso. Un file che manca o che non e' 64x64 viene saltato e detto nel log —
     * le altre continuano a funzionare, e chi ha sbagliato il file sa quale.
     */
    private void caricaIcone() {
        icone.clear();
        if (!cfg.getBoolean("icons.enabled", false)) return;
        for (String nome : cfg.getStringList("icons.files")) {
            File f = new File(plugin.getDataFolder(), nome);
            if (!f.isFile()) {
                plugin.getLogger().warning("[MOTD] icona '" + nome + "' non trovata in "
                        + plugin.getDataFolder().getName() + ": la salto.");
                continue;
            }
            try {
                icone.add(Bukkit.loadServerIcon(f));
            } catch (Exception e) {
                plugin.getLogger().warning("[MOTD] icona '" + nome + "' non caricata ("
                        + e.getMessage() + "): dev'essere un PNG di 64x64. La salto.");
            }
        }
        if (!icone.isEmpty()) plugin.getLogger().info("[MOTD] icone caricate: " + icone.size() + ".");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(PaperServerListPingEvent e) {
        // Il conto si decide PRIMA di comporre il testo: {max} deve dire quello che il giocatore
        // legge accanto al numero, non un altro numero.
        int online = e.getNumPlayers();
        int extra = cfg.getInt("player-count.extra", 0);
        int max = cfg.getInt("player-count.max", -1);
        if (extra > 0) e.setMaxPlayers(online + extra);     // "c'e' sempre un posto libero"
        else if (max > 0) e.setMaxPlayers(max);
        int mostrato = e.getMaxPlayers();

        List<String> messaggi = cfg.getStringList("messages");
        int quale = rotazioneTesti.indice(messaggi.size());
        // Lista vuota: non si tocca niente e vale la riga 'motd' di server.properties. Una MOTD
        // vuota sarebbe peggio di nessuna MOTD.
        if (quale >= 0) {
            e.motd(MotdText.component(messaggi.get(quale), online, mostrato, e.getVersion()));
        }

        // La versione: il testo si vede solo quando il client e' incompatibile, e 'always-show'
        // rende incompatibili tutti apposta. Vedi il commento nel config: e' vistoso.
        String versione = cfg.getString("version.text", "");
        if (versione != null && !versione.isEmpty()) {
            e.setVersion(MotdText.semplice(versione));
            if (cfg.getBoolean("version.always-show", false)) e.setProtocolVersion(PROTOCOL_MISMATCH);
        }

        int qualeIcona = rotazioneIcone.indice(icone.size());
        if (qualeIcona >= 0) e.setServerIcon(icone.get(qualeIcona));

        if (cfg.getBoolean("player-count.hide", false)) {
            // Al posto di "3/80" il client disegna le due frecce dei server irraggiungibili: e'
            // il modo che il protocollo ha per dire "il conto non si mostra".
            e.setHidePlayers(true);
            return;   // niente tendina: senza il conto, non c'e' dove appoggiarla
        }

        // La tendina: le righe del file al posto dell'elenco dei giocatori online. Chi c'e'
        // dentro un server lo sa solo chi entra; qui si scrive quello che vogliamo far leggere.
        List<String> tendina = MotdText.tendina(cfg.getStringList("hover"), online, mostrato, e.getVersion());
        if (tendina.isEmpty()) return;   // lista vuota = si lascia l'elenco vero dei giocatori
        List<PaperServerListPingEvent.ListedPlayerInfo> voci = e.getListedPlayers();
        voci.clear();
        for (String riga : tendina) voci.add(new PaperServerListPingEvent.ListedPlayerInfo(riga, NO_ONE));
    }
}
