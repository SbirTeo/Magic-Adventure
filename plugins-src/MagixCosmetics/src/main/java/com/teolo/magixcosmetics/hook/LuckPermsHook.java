package com.teolo.magixcosmetics.hook;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lettura dei permessi di un giocatore <b>anche da OFFLINE</b>, tramite l'API di LuckPerms: Bukkit
 * sa rispondere solo per chi e' collegato, ma la statua di un giocatore (MagixEntities) deve sapere
 * che aureola mostrare anche quando lui non c'e'. Stesso schema di {@code hook/LuckPermsHook} in
 * MagixFactions.
 *
 * <p>Degrada in silenzio: senza LuckPerms vale solo il colore ricordato l'ultima volta che il
 * giocatore era online.</p>
 */
public final class LuckPermsHook {

    private final JavaPlugin plugin;
    private LuckPerms api;

    public LuckPermsHook(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** @return true se LuckPerms e' presente e pronto: solo allora i permessi offline sono leggibili. */
    public boolean setup() {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) return false;
        try {
            api = LuckPermsProvider.get();
            return true;
        } catch (IllegalStateException e) { // LuckPerms c'e' ma non ha ancora finito di partire
            plugin.getLogger().warning("LuckPerms non ancora pronto: aureola delle statue solo da online ("
                    + e.getMessage() + ").");
            return false;
        }
    }

    public boolean available() {
        return api != null;
    }

    /**
     * Il valore di ciascun permesso di {@code nodes} per quel giocatore, gruppi e jolly compresi,
     * anche se e' offline.
     *
     * <p><b>Va chiamato FUORI dal main thread:</b> per un utente non in cache LuckPerms legge dal
     * proprio storage, e {@code join()} aspetta quella lettura.</p>
     *
     * @return permesso -&gt; valore, o {@code null} se la lettura non e' riuscita.
     */
    public Map<String, Boolean> check(UUID uuid, List<String> nodes) {
        if (api == null) return null;
        try {
            User user = api.getUserManager().getUser(uuid);
            boolean loadedByUs = false;
            if (user == null) {
                user = api.getUserManager().loadUser(uuid).join();
                loadedByUs = true;
            }
            if (user == null) return null;
            CachedPermissionData data = user.getCachedData()
                    .getPermissionData(api.getContextManager().getStaticQueryOptions());
            Map<String, Boolean> out = new HashMap<>();
            for (String node : nodes) out.put(node, data.checkPermission(node).asBoolean());
            // Un utente caricato da noi lo scarichiamo noi: senza, LuckPerms terrebbe in memoria
            // tutti i giocatori che hanno una statua.
            if (loadedByUs) api.getUserManager().cleanupUser(user);
            return out;
        } catch (Exception e) {
            plugin.getLogger().warning("Lettura permessi offline di " + uuid + ": " + e.getMessage());
            return null;
        }
    }
}
