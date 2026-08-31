package com.teolo.magixauth.gate;

import com.teolo.magixauth.AuthConfig;
import com.teolo.magixauth.util.Texts;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * I tre momenti dell'ingresso, in ordine di apparizione.
 *
 * Sono tre eventi diversi perche' succedono in tre fasi diverse della connessione, e ognuno
 * puo' fare cose che gli altri non possono: al pre-login si puo' aspettare il database, alla
 * scelta dello spawn si puo' ancora cambiare dove comparira' il giocatore, all'ingresso c'e'
 * finalmente un Player con cui parlare.
 */
public final class ConnessioneListener implements Listener {

    private final AuthConfig config;
    private final AuthGate gate;
    private final Visibilita visibilita;

    public ConnessioneListener(AuthConfig config, AuthGate gate, Visibilita visibilita) {
        this.config = config;
        this.gate = gate;
        this.visibilita = visibilita;
    }

    /**
     * Prima che il giocatore esista.
     *
     * Gira gia' fuori dal thread principale, quindi qui — e solo qui — si puo' interrogare
     * il database senza rallentare il server. E' anche l'ultimo istante in cui si puo'
     * ancora decidere che UUID avra': dopo, e' scolpito.
     *
     * La priorita' e' LOWEST e non e' un dettaglio: deve girare PRIMA di ogni altro plugin.
     * LuckPerms, in questo stesso evento, si precarica i permessi dell'UUID con cui il client
     * sta bussando; se noi cambiassimo l'UUID dopo di lui, al momento del login andrebbe a
     * cercare i dati di un utente che non ha mai preparato e rifiuterebbe l'ingresso con
     * "permissions data was not loaded during the pre-login stage". E' esattamente quello che
     * e' successo al primo avvio in produzione: nessuno riusciva piu' a entrare.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void alPreLogin(AsyncPlayerPreLoginEvent e) {
        String ip = e.getAddress() == null ? "" : e.getAddress().getHostAddress();

        String rifiuto = gate.decidi(e.getUniqueId(), e.getName(), ip, e.getConnection(), (uuid, skin) -> {
            if (uuid == null && skin == null) {
                return;
            }
            PlayerProfile profilo = e.getPlayerProfile();
            if (uuid != null) {
                profilo.setId(uuid);
            }
            if (skin != null) {
                // La firma di Mojang va tenuta: senza, il client rifiuta una skin che non
                // ha chiesto lui e resta con quella di serie.
                profilo.setProperty(new ProfileProperty("textures", skin[0],
                        skin.length > 1 ? skin[1] : null));
            }
            e.setPlayerProfile(profilo);
        });

        if (rifiuto != null) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Texts.c(rifiuto));
        }
    }

    /**
     * Dove comparira'.
     *
     * Questo evento gira nella fase di configurazione, cioe' prima che il giocatore entri
     * nel mondo: cambiando la posizione qui, i chunk di dove si trovava davvero non vengono
     * caricati affatto. Per chi sbaglia la password e viene cacciato, non verranno caricati
     * mai — ed e' il motivo per cui questa scelta aiuta anche il carico del server, oltre a
     * non far leggere le coordinate altrui a chi entra col nome di un altro.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void alloSpawn(AsyncPlayerSpawnLocationEvent e) {
        UUID uuid = e.getConnection().getProfile().getId();
        if (uuid == null) {
            return;
        }
        Location dirottato = gate.dirottaSpawn(uuid, e.getSpawnLocation());
        if (dirottato != null) {
            e.setSpawnLocation(dirottato);
        }
    }

    /**
     * E' dentro, ma non e' ancora entrato.
     *
     * Il messaggio "e' entrato nel server" viene messo da parte e non mostrato: senza questo,
     * ogni tentativo di connettersi col nome di un altro lo annuncerebbe a tutti, e il server
     * si riempirebbe di arrivi e partenze di gente che non e' mai entrata davvero. Lo
     * pubblica il gate, a login riuscito.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void allIngresso(PlayerJoinEvent e) {
        Player p = e.getPlayer();

        // I giocatori gia' dentro non devono vedere chi sta al cancello, e viceversa: al
        // momento in cui gli altri sono stati nascosti, questo giocatore non c'era ancora.
        for (Player altro : Bukkit.getOnlinePlayers()) {
            if (!altro.equals(p) && gate.fermo(altro)) {
                visibilita.separa(p, altro);
            }
        }

        gate.accogli(p);

        if (gate.fermo(p) && config.ritardaMessaggioIngresso) {
            StatoIngresso stato = gate.stato(p);
            if (stato != null) {
                stato.messaggioIngresso = e.joinMessage();
            }
            e.joinMessage(null);
        }
    }

    /**
     * Se ne va.
     *
     * Chi non aveva ancora fatto il login non deve nemmeno salutare: nessuno sapeva che
     * fosse arrivato.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void allUscita(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (gate.fermo(p)) {
            e.quitMessage(null);
        }
        gate.abbandona(p);
    }
}
