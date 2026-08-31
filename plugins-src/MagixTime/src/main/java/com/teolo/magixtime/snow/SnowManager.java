package com.teolo.magixtime.snow;

import com.teolo.magixtime.MagixTime;
import com.teolo.magixtime.season.SeasonDef;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Snow;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Accumulo e scioglimento stagionale di neve e ghiaccio.
 *
 * Nota importante: se la precipitazione appaia come pioggia o come neve lo decide
 * il client in base alla temperatura del bioma, e nessuna API permette di cambiarlo.
 * Quello che si puo' governare e' il DEPOSITO: d'inverno la neve si accumula (e piu'
 * in alto del singolo strato vanilla) e l'acqua ferma ghiaccia; nelle stagioni calde
 * gli strati si sciolgono e il ghiaccio torna acqua.
 *
 * Il lavoro e' campionato: a ogni passaggio vengono valutati pochi punti a caso
 * attorno a ciascun giocatore, senza mai caricare chunk nuovi.
 */
public final class SnowManager {

    /** Sotto questa temperatura del blocco (bioma + altitudine) Minecraft fa nevicare. */
    private static final double SNOW_TEMPERATURE = 0.15;

    private final MagixTime plugin;
    private BukkitTask task;
    private final Set<Material> skip = EnumSet.noneOf(Material.class);

    public SnowManager(MagixTime plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("snow.enabled", true)) return;
        loadSkipList();
        long ticks = Math.max(1, plugin.getConfig().getInt("snow.interval-seconds", 15)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, ticks, ticks);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    private void loadSkipList() {
        skip.clear();
        for (String name : plugin.getConfig().getStringList("snow.skip-blocks")) {
            Material m = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
            if (m != null) skip.add(m);
            else plugin.getLogger().warning("Blocco sconosciuto in snow.skip-blocks: " + name);
        }
    }

    private void tick() {
        SeasonDef season = plugin.seasons().current();
        if (season == null) return;

        int radius = Math.max(4, plugin.getConfig().getInt("snow.radius", 48));
        int samples = Math.max(1, plugin.getConfig().getInt("snow.blocks-per-player", 40));
        int maxLayers = Math.max(1, Math.min(8, plugin.getConfig().getInt("snow.max-layers", 3)));
        boolean anywhere = plugin.getConfig().getBoolean("snow.force-anywhere", false);
        boolean freezeWater = plugin.getConfig().getBoolean("snow.freeze-water", true);
        boolean meltIce = plugin.getConfig().getBoolean("snow.melt-ice", true);
        boolean meltCold = plugin.getConfig().getBoolean("snow.melt-cold-biomes", false);

        for (Player p : Bukkit.getOnlinePlayers()) {
            World w = p.getWorld();
            if (!plugin.isManaged(w)) continue;
            boolean precipitating = w.hasStorm();
            boolean accumulate = precipitating && season.snowAccumulate();
            boolean melt = !precipitating && season.snowMelt();
            if (!accumulate && !melt) continue;

            for (int i = 0; i < samples; i++) {
                int x = p.getLocation().getBlockX() + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
                int z = p.getLocation().getBlockZ() + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                // Il blocco piu' alto e' anche l'unico esposto al cielo: niente neve in grotta o al chiuso.
                Block top = w.getHighestBlockAt(x, z);
                if (accumulate) accumulate(w, top, maxLayers, anywhere, freezeWater);
                else meltAt(top, meltIce, meltCold);
            }
        }
    }

    // ------------------------------------------------------------- accumulo

    private void accumulate(World w, Block top, int maxLayers, boolean anywhere, boolean freezeWater) {
        if (!anywhere && top.getTemperature() >= SNOW_TEMPERATURE) return;

        if (top.getType() == Material.SNOW) {
            BlockData data = top.getBlockData();
            if (data instanceof Snow snow && snow.getLayers() < Math.min(maxLayers, snow.getMaximumLayers())) {
                snow.setLayers(snow.getLayers() + 1);
                top.setBlockData(snow, false);
            }
            return;
        }
        if (freezeWater && top.getType() == Material.WATER) {
            // Solo l'acqua ferma di superficie: le correnti in vanilla non ghiacciano.
            if (top.getBlockData() instanceof Levelled lv && lv.getLevel() == 0) {
                top.setType(Material.ICE, false);
            }
            return;
        }
        if (!top.getType().isSolid() || skip.contains(top.getType())) return;
        Block above = top.getRelative(0, 1, 0);
        if (above.getY() >= w.getMaxHeight()) return;
        if (above.getType() != Material.AIR) return;
        above.setType(Material.SNOW, false);
    }

    // ------------------------------------------------------------- scioglimento

    private void meltAt(Block top, boolean meltIce, boolean meltCold) {
        // Nei biomi gelidi (taiga innevata, picchi...) la neve e' parte del paesaggio:
        // scioglierla d'estate spoglierebbe il bioma per sempre, visto che vanilla la
        // rimette solo mentre nevica.
        if (!meltCold && top.getTemperature() < SNOW_TEMPERATURE) return;
        if (top.getType() == Material.SNOW) {
            BlockData data = top.getBlockData();
            if (data instanceof Snow snow && snow.getLayers() > 1) {
                snow.setLayers(snow.getLayers() - 1);
                top.setBlockData(snow, false);
            } else {
                top.setType(Material.AIR, false);
            }
            return;
        }
        // Solo il ghiaccio semplice: SNOW_BLOCK, PACKED_ICE e BLUE_ICE restano intatti
        // perche' quasi sempre sono costruzioni dei giocatori.
        if (meltIce && top.getType() == Material.ICE) top.setType(Material.WATER, false);
    }
}
