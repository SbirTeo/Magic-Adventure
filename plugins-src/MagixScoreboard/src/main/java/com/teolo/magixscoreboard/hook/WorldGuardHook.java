package com.teolo.magixscoreboard.hook;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Accesso opzionale a WorldGuard: dice in quali regioni si trova una posizione, per le condizioni
 * "regions" delle scoreboard. Se WorldGuard non e' installato le condizioni sulle regioni non
 * corrispondono mai (vedi model/BoardDefinition), invece di rompere l'avvio del plugin.
 */
public final class WorldGuardHook {

    private final boolean available;

    public WorldGuardHook() {
        this.available = Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
    }

    public boolean enabled() { return available; }

    /** Gli ID (minuscoli) delle regioni WorldGuard che contengono questa posizione. */
    public Set<String> regionsAt(Location location) {
        if (!available) return Set.of();
        try {
            RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            ApplicableRegionSet applicable = query.getApplicableRegions(BukkitAdapter.adapt(location));
            Set<String> out = new HashSet<>();
            for (ProtectedRegion region : applicable) {
                out.add(region.getId().toLowerCase(Locale.ROOT));
            }
            return out;
        } catch (Throwable t) {
            return Set.of();
        }
    }
}
