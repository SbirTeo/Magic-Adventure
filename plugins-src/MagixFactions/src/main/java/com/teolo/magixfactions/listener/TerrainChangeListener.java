package com.teolo.magixfactions.listener;

import com.teolo.magixfactions.map.MapService;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Invalida la cache del terreno ({@code TerrainCache}, condivisa dall'item Mappa Fazioni e dalla
 * minimap HUD) appena un blocco viene piazzato o rotto, cosi' la mappa riflette la modifica al
 * prossimo redraw invece di aspettare la TTL della cache (3 minuti, pensata per il caso normale di
 * terreno che non cambia da solo) — altrimenti costruire/scavare non si vedeva sulla mappa per minuti.
 */
public final class TerrainChangeListener implements Listener {

    private final MapService maps;

    public TerrainChangeListener(MapService maps) { this.maps = maps; }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { invalidate(e.getBlock()); }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { invalidate(e.getBlock()); }

    // Flusso dei LIQUIDI (acqua/lava che si espande, es. dopo aver scavato una spiaggia): non e' un
    // place/break, quindi senza questo la mappa non "vedeva" l'acqua arrivata per inerzia. Invalida il
    // blocco di DESTINAZIONE del flusso.
    @EventHandler(ignoreCancelled = true)
    public void onLiquidFlow(BlockFromToEvent e) { invalidate(e.getToBlock()); }

    // Cattura OPPORTUNISTICA (spunto da Cartographer2): ogni chunk che il gioco carica DA SOLO (giocatori
    // che si muovono) viene offerto alla cache mappa — lo snapshot di un chunk gia' caldo costa poco ed
    // e' messo in coda con budget per tick. Con molti giocatori online la mappa si riempie cosi' quasi
    // gratis, e la rivelazione forzata resta solo per le zone che nessuno visita.
    @EventHandler
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent e) { maps.offerLoadedChunk(e.getChunk()); }

    private void invalidate(Block b) {
        maps.invalidateTerrain(b.getWorld(), b.getX(), b.getZ());
    }
}
