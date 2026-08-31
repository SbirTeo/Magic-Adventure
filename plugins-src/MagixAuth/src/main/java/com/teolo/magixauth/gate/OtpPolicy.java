package com.teolo.magixauth.gate;

import com.teolo.magixauth.AuthConfig;
import com.teolo.magixauth.model.Account;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.track.Track;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Chi deve passare dalla verifica in due passaggi.
 *
 * Tre categorie, in ordine di certezza:
 *
 *  1. i web-admin del sito — lo dice la colonna `is_admin`, che leggiamo comunque;
 *  2. tutto lo staff — chiunque stia in un gruppo della track di LuckPerms indicata in
 *     configurazione (di norma "staff"), a qualunque livello;
 *  3. i giocatori normali che l'hanno attivata di loro volonta' dal sito.
 *
 * Per i primi due e' obbligatoria: sono gli account che qualcuno avrebbe interesse a
 * impersonare, ed e' proprio per loro che questo meccanismo esiste.
 */
public final class OtpPolicy {

    private final JavaPlugin plugin;
    private final AuthConfig config;

    /** I gruppi che compongono la track dello staff, riletti a intervalli. */
    private volatile Set<String> gruppiStaff = Collections.emptySet();

    private LuckPerms luckPerms;

    public OtpPolicy(JavaPlugin plugin, AuthConfig config) {
        this.plugin = plugin;
        this.config = config;
        aggancia();
    }

    /** LuckPerms e' facoltativo: se non c'e', resta la regola dei soli web-admin. */
    private void aggancia() {
        try {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
                luckPerms = Bukkit.getServicesManager().load(LuckPerms.class);
            }
        } catch (Throwable t) {
            luckPerms = null;
            plugin.getLogger().warning("MagixAuth: LuckPerms non disponibile, "
                    + "la verifica obbligatoria vale solo per i web-admin del sito.");
        }
    }

    /**
     * Rilegge quali gruppi compongono la track dello staff.
     *
     * Si fa a intervalli e non a ogni ingresso: la composizione di una track cambia una
     * volta ogni mai, e chiedere a LuckPerms nel mezzo del login e' lavoro sprecato.
     */
    public void aggiornaGruppi() {
        if (luckPerms == null) {
            return;
        }
        try {
            Track track = luckPerms.getTrackManager().getTrack(config.trackStaff);
            if (track == null) {
                // Nome sbagliato in configurazione: va detto, o si scoprirebbe solo il
                // giorno in cui un amministratore entra senza che gli venga chiesto nulla.
                plugin.getLogger().warning("MagixAuth: la track \"" + config.trackStaff
                        + "\" non esiste in LuckPerms. Controlla /lp listtracks e la voce "
                        + "otp.track_staff nella configurazione.");
                gruppiStaff = Collections.emptySet();
                return;
            }
            gruppiStaff = new HashSet<>(track.getGroups());
        } catch (Throwable t) {
            plugin.getLogger().warning("MagixAuth: track dello staff non aggiornata ("
                    + t.getMessage() + "). Resta valido l'elenco di prima.");
        }
    }

    /**
     * A questo account serve il codice?
     *
     * Gira nel pre-login, quindi fuori dal thread principale: l'utente di LuckPerms si
     * carica in modo asincrono, che e' esattamente cio' che si puo' fare qui.
     */
    public boolean serve(Account account, UUID uuid) {
        if (account == null) {
            // Nome mai registrato: si sta registrando adesso, non ha ancora un segreto.
            return false;
        }
        if (account.webAdmin) {
            return true;
        }
        if (staff(uuid)) {
            return true;
        }
        // Giocatore normale: solo se l'ha attivata lui dal sito.
        return config.otpFacoltativoPerGiocatori && account.haOtp();
    }

    /** Sta in un gruppo della track dello staff? */
    public boolean staff(UUID uuid) {
        if (luckPerms == null || gruppiStaff.isEmpty()) {
            return false;
        }
        try {
            User user = luckPerms.getUserManager().getUser(uuid);
            if (user == null) {
                user = luckPerms.getUserManager().loadUser(uuid).join();
            }
            if (user == null) {
                return false;
            }
            String primario = user.getPrimaryGroup();
            if (primario != null && gruppiStaff.contains(primario)) {
                return true;
            }
            return user.getNodes().stream()
                    .filter(net.luckperms.api.node.NodeType.INHERITANCE::matches)
                    .map(net.luckperms.api.node.NodeType.INHERITANCE::cast)
                    .anyMatch(n -> gruppiStaff.contains(n.getGroupName()));
        } catch (Throwable t) {
            // Nel dubbio su un account che potrebbe essere di staff, si chiede il codice:
            // e' il verso prudente in cui sbagliare.
            return true;
        }
    }

    /** Solo per i messaggi di diagnostica di /mauth info. */
    public int quantiGruppiStaff() {
        return gruppiStaff.size();
    }

    public boolean luckPermsPresente() {
        return luckPerms != null;
    }
}
