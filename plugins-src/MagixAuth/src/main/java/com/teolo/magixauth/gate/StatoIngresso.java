package com.teolo.magixauth.gate;

import com.teolo.magixauth.model.Account;
import com.teolo.magixauth.model.Fase;
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
public final class StatoIngresso {

    public final UUID uuid;
    public final String nome;
    public final String ip;

    /** Null se il nome non e' mai stato registrato: allora la fase e' REGISTRAZIONE. */
    public final Account account;

    /** Il computer da cui sta entrando, riconosciuto dall'indirizzo di rete. */
    public final String dispositivo;

    /**
     * Il dispositivo riconosciuto dal GETTONE (vedi {@link Biscotto}), o null se non ne aveva
     * uno valido. Alla fine del login questa riga viene cancellata e sostituita da una nuova:
     * e' la rotazione, e serve a non lasciare in giro un gettone che vale per sempre.
     */
    public final String cookieDispositivo;

    /** Dove si trovava davvero: ci torna appena entra. Null se non aveva mai giocato. */
    public Location posizioneVera;

    /** Il "e' entrato nel server", tenuto da parte finche' non ha davvero fatto il login. */
    public Component messaggioIngresso;

    public Fase fase;

    /** In registrazione: la prima password digitata, in attesa della conferma. */
    public String passwordInAttesa;

    /** L'id del compito che lo caccia a tempo scaduto. */
    public int taskScadenza = -1;

    /** L'id del compito che riapre il cartello se lo chiude. */
    public int taskCartello = -1;

    /** Una verifica e' gia' in corso: evita che due click facciano due giri di bcrypt. */
    public volatile boolean occupato;

    public StatoIngresso(UUID uuid, String nome, String ip, Account account,
                         String dispositivo, String cookieDispositivo, Fase fase) {
        this.uuid = uuid;
        this.nome = nome;
        this.ip = ip;
        this.account = account;
        this.dispositivo = dispositivo;
        this.cookieDispositivo = cookieDispositivo;
        this.fase = fase;
    }

    public boolean inRegistrazione() {
        return fase == Fase.REGISTRAZIONE;
    }

    public boolean aspettaCodice() {
        return fase == Fase.OTP;
    }
}
