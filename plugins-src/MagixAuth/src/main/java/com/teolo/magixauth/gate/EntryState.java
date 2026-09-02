package com.teolo.magixauth.gate;

import com.teolo.magixauth.model.Account;
import com.teolo.magixauth.model.Phase;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;

import java.util.UUID;

/**
 * Un giocatore fermo al cancello, con tutto quello che serve a farlo passare.
 *
 * Nasce nel pre-login — dove si puo' ancora interrogare il database senza rallentare il
 * server — e muore quando il giocatore entra o se ne va. Finche' esiste, quel giocatore e'
 * congelato: non si muove, non parla, non vede e non e' visto.
 */
public final class EntryState {

    public final UUID uuid;
    public final String name;
    public final String ip;

    /** Null se il nome non e' mai stato registrato: allora la fase e' REGISTRAZIONE. */
    public final Account account;

    /** Il computer da cui sta entrando, riconosciuto dall'indirizzo di rete. */
    public final String deviceKey;

    /**
     * Il dispositivo riconosciuto dal GETTONE (vedi {@link Biscotto}), o null se non ne aveva
     * uno valido. Alla fine del login questa riga viene cancellata e sostituita da una nuova:
     * e' la rotazione, e serve a non lasciare in giro un gettone che vale per sempre.
     */
    public final String deviceCookie;

    /** Dove si trovava davvero: ci torna appena entra. Null se non aveva mai giocato. */
    public Location realPosition;

    /** Il "e' entrato nel server", tenuto da parte finche' non ha davvero fatto il login. */
    public Component savedJoinMessage;

    public Phase phase;

    /** In registrazione: la prima password digitata, in attesa della conferma. */
    public String passwordInAttesa;

    /** L'id del compito che lo caccia a tempo scaduto. */
    public int expiryTask = -1;

    /** L'id del compito che riapre il cartello se lo chiude. */
    public int signTask = -1;

    /** Una verifica e' gia' in corso: evita che due click facciano due giri di bcrypt. */
    public volatile boolean busy;

    public EntryState(UUID uuid, String name, String ip, Account account,
                         String deviceKey, String deviceCookie, Phase phase) {
        this.uuid = uuid;
        this.name = name;
        this.ip = ip;
        this.account = account;
        this.deviceKey = deviceKey;
        this.deviceCookie = deviceCookie;
        this.phase = phase;
    }

    public boolean inRegistration() {
        return phase == Phase.REGISTRAZIONE;
    }

    public boolean awaitsCode() {
        return phase == Phase.OTP;
    }
}
