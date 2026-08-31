package com.teolo.magixfactions.hook;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lettura dei permessi di un giocatore <b>anche da OFFLINE</b>, tramite l'API di LuckPerms.
 * <p>
 * Bukkit sa rispondere solo per chi e' collegato ({@code Player.getEffectivePermissions()}), ma due
 * valori del sistema Potenza devono restare giusti anche per chi non c'e':
 * <ul>
 *   <li>il <b>tetto</b> di Potenza, perche' entra nella somma del maxpower di fazione — senza questo, un
 *       VIP scaduto su un account abbandonato continuerebbe a regalare tetto alla sua fazione per
 *       sempre;</li>
 *   <li>la <b>velocita' di perdita</b> da offline, che per definizione si applica a chi e' scollegato.</li>
 * </ul>
 * Degrada in silenzio: senza LuckPerms il plugin continua a funzionare, semplicemente quei due valori
 * restano quelli normali per chi e' offline (vedi {@code PowerManager.tickOffline}).
 */
public final class LuckPermsHook {

    private final JavaPlugin plugin;
    private LuckPerms api;

    public LuckPermsHook(JavaPlugin plugin) { this.plugin = plugin; }

    /** @return true se LuckPerms e' presente e pronto: solo allora i permessi offline sono leggibili. */
    public boolean setup() {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            plugin.getLogger().info("[Potenza] LuckPerms non trovato: i permessi dei giocatori OFFLINE "
                    + "non sono leggibili, tetto e perdita di Potenza restano quelli normali finche' non rientrano.");
            return false;
        }
        try {
            this.api = LuckPermsProvider.get();
            return true;
        } catch (IllegalStateException e) { // LuckPerms c'e' ma non ha ancora finito di partire
            plugin.getLogger().warning("[Potenza] LuckPerms non ancora pronto: " + e.getMessage());
            return false;
        }
    }

    public boolean available() { return api != null; }

    /**
     * Tutti i permessi <b>attivi</b> di un giocatore, quelli ereditati dai gruppi compresi, anche se e'
     * offline.
     * <p>
     * <b>Va chiamato FUORI dal main thread:</b> per un utente non in cache LuckPerms legge dal proprio
     * storage (qui un file H2), e {@code join()} aspetta quella lettura.
     *
     * @return la mappa permesso -&gt; valore, o {@code null} se la lettura non e' riuscita.
     */
    public Map<String, Boolean> permessi(UUID uuid) {
        if (api == null) return null;
        try {
            User user = api.getUserManager().getUser(uuid);
            boolean caricatoDaNoi = false;
            if (user == null) {
                user = api.getUserManager().loadUser(uuid).join();
                caricatoDaNoi = true;
            }
            if (user == null) return null;
            QueryOptions opzioni = api.getContextManager().getStaticQueryOptions();
            // Copia: la mappa di LuckPerms appartiene alla sua cache, che puo' essere ricalcolata sotto
            // di noi mentre la stiamo leggendo dal nostro thread.
            Map<String, Boolean> copia = new HashMap<>(user.getCachedData().getPermissionData(opzioni).getPermissionMap());
            // Un utente caricato da noi lo scarichiamo noi: senza, ogni giro lascerebbe in memoria a
            // LuckPerms tutti i giocatori mai passati dal server.
            if (caricatoDaNoi) api.getUserManager().cleanupUser(user);
            return copia;
        } catch (Exception e) {
            plugin.getLogger().warning("[Potenza] lettura permessi offline di " + uuid + ": " + e.getMessage());
            return null;
        }
    }
}
