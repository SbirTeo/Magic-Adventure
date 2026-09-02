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
public final class Visibility {

    private final JavaPlugin plugin;
    private final AuthConfig config;

    public Visibility(JavaPlugin plugin, AuthConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /** Cala il sipario intorno a chi sta entrando. */
    public void hide(Player incoming) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(incoming)) {
                continue;
            }
            if (config.hideFromOthers) {
                other.hidePlayer(plugin, incoming);
            }
            if (config.hideOthers) {
                incoming.hidePlayer(plugin, other);
            }
        }
    }

    /** Rialza il sipario: da qui in poi e' un giocatore come gli altri. */
    public void show(Player entered) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(entered)) {
                continue;
            }
            other.showPlayer(plugin, entered);
            entered.showPlayer(plugin, other);
        }
    }

    /**
     * Un giocatore gia' dentro non deve accorgersi di chi sta al cancello, e viceversa.
     *
     * Serve per chi entra MENTRE qualcun altro sta ancora digitando la password: senza
     * questo, i due si vedrebbero, perche' nascondi() ha girato su una lista di giocatori
     * in cui il nuovo arrivato non c'era ancora.
     */
    public void separate(Player inside, Player alCancello) {
        if (config.hideFromOthers) {
            inside.hidePlayer(plugin, alCancello);
        }
        if (config.hideOthers) {
            alCancello.hidePlayer(plugin, inside);
        }
    }
}
