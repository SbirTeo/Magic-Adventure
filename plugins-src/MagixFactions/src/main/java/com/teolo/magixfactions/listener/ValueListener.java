package com.teolo.magixfactions.listener;

import com.teolo.magixfactions.manage.ClaimManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VALORE di una fazione: la somma del valore dei blocchi di minerale PIAZZATI dentro le sue land.
 * Ogni blocco configurato in {@code value-blocks} vale un tot; piazzarlo in un chunk claimato aggiunge il
 * suo valore a quel chunk (e alla fazione proprietaria), romperlo lo toglie. Il valore viaggia col
 * territorio quando viene conquistato (vedi {@link ClaimManager}).
 *
 * <p>Gira a priorita' MONITOR e {@code ignoreCancelled=true}: cosi' conta solo i piazzamenti/rotture
 * ANDATI A BUON FINE (un'azione bloccata dalla protezione territori non muove il valore). Fuori dai claim
 * non succede nulla: il valore esiste solo dentro le land.
 */
public final class ValueListener implements Listener {

    private final JavaPlugin plugin;
    private final ClaimManager claims;

    public ValueListener(JavaPlugin plugin, ClaimManager claims) {
        this.plugin = plugin; this.claims = claims;
    }

    /** Valore configurato per un materiale ({@code value-blocks.<MATERIAL>}), 0 se non elencato. */
    private double worthOf(Material m) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("value-blocks");
        return sec == null ? 0 : sec.getDouble(m.name(), 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        double w = worthOf(e.getBlock().getType());
        if (w != 0) apply(e.getBlock(), w);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        double w = worthOf(e.getBlock().getType());
        if (w != 0) apply(e.getBlock(), -w);
    }

    private void apply(Block b, double delta) {
        Location l = b.getLocation();
        if (l.getWorld() == null) return;
        claims.addValue(l.getWorld().getName(), l.getBlockX() >> 4, l.getBlockZ() >> 4, delta);
    }
}
