package com.teolo.magixauth.gate;

import com.teolo.magixauth.AuthConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Chi vede chi, mentre qualcuno e' fermo al cancello.
 *
 * L'invisibilita' va in due versi, e servono a due cose diverse:
 *
 *  - il giocatore non ancora entrato e' invisibile agli altri, cosi' il server non si
 *    riempie di figure immobili allo spawn ogni volta che qualcuno prova un nick;
 *  - gli altri sono invisibili a lui, che e' la parte importante: chi entra col nome di un
 *    altro non deve poter guardare chi c'e' online, ne' leggerne i nomi nella lista.
 *
 * Non e' l'effetto "invisibilita'" della pozione: quella lascia comunque vedere l'armatura
 * e le particelle. Qui l'entita' non viene proprio inviata al client.
 */
public final class Visibilita {

    private final JavaPlugin plugin;
    private final AuthConfig config;

    public Visibilita(JavaPlugin plugin, AuthConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /** Cala il sipario intorno a chi sta entrando. */
    public void nascondi(Player entrante) {
        for (Player altro : Bukkit.getOnlinePlayers()) {
            if (altro.equals(entrante)) {
                continue;
            }
            if (config.invisibileAgliAltri) {
                altro.hidePlayer(plugin, entrante);
            }
            if (config.nascondiGliAltri) {
                entrante.hidePlayer(plugin, altro);
            }
        }
    }

    /** Rialza il sipario: da qui in poi e' un giocatore come gli altri. */
    public void mostra(Player entrato) {
        for (Player altro : Bukkit.getOnlinePlayers()) {
            if (altro.equals(entrato)) {
                continue;
            }
            altro.showPlayer(plugin, entrato);
            entrato.showPlayer(plugin, altro);
        }
    }

    /**
     * Un giocatore gia' dentro non deve accorgersi di chi sta al cancello, e viceversa.
     *
     * Serve per chi entra MENTRE qualcun altro sta ancora digitando la password: senza
     * questo, i due si vedrebbero, perche' nascondi() ha girato su una lista di giocatori
     * in cui il nuovo arrivato non c'era ancora.
     */
    public void separa(Player dentro, Player alCancello) {
        if (config.invisibileAgliAltri) {
            dentro.hidePlayer(plugin, alCancello);
        }
        if (config.nascondiGliAltri) {
            alCancello.hidePlayer(plugin, dentro);
        }
    }
}
