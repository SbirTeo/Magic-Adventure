package com.teolo.magixfactions.map;

import com.teolo.magixfactions.manage.ClaimManager;
import com.teolo.magixfactions.manage.FactionManager;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * Renderer della {@link MapView} "headless" usata dalla minimap HUD (mai consegnata come item, vedi
 * {@link MapService#createHeadless}): stesso identico contenuto pixel dell'item mappa, tramite
 * {@link MapContentBuilder} — nessuna logica di disegno duplicata, throttling analogo a
 * {@link FactionMapRenderer} ma con la propria chiave di intervallo ({@code map.minimap.render-interval-ticks}).
 *
 * <p>A differenza di {@code FactionMapRenderer}, l'owner qui non e' "nullable per compatibilita' vecchie
 * mappe": ogni MapView headless viene creata gia' con un owner noto ({@link MapService#createHeadless}
 * riceve sempre un giocatore reale), quindi il controllo owner e' solo una sicurezza difensiva, non una
 * feature di compatibilita' come nell'item mappa.
 */
public final class MinimapRenderer extends MapRenderer {

    private static final long TICK_MS = 50L;

    private final JavaPlugin plugin;
    private final FactionManager fm;
    private final ClaimManager claims;
    private final TerrainCache terrain;
    private final AvatarCache avatars;
    private final UUID owner;

    private long lastRender = 0L;

    public MinimapRenderer(JavaPlugin plugin, FactionManager fm, ClaimManager claims, TerrainCache terrain,
                            AvatarCache avatars, UUID owner) {
        super(false); // la MapView e' gia' 1:1 con questo giocatore, non serve dispatch per-player
        this.plugin = plugin; this.fm = fm; this.claims = claims; this.terrain = terrain; this.avatars = avatars;
        this.owner = owner;
    }

    @Override
    public void render(MapView view, MapCanvas canvas, Player player) {
        if (!player.getUniqueId().equals(owner)) return;

        int intervalTicks = Math.max(1, plugin.getConfig().getInt("map.render-interval-ticks", 1));
        long intervalMs = intervalTicks * TICK_MS - 5L;
        long now = System.currentTimeMillis();
        if (now - lastRender < intervalMs) return;
        lastRender = now;

        int cX = player.getLocation().getBlockX(), cZ = player.getLocation().getBlockZ();
        view.setCenterX(cX); view.setCenterZ(cZ);

        // Minimap: solo terreno/territori/cardinali/home nei pixel — le frecce-giocatore le disegna lo
        // shader dall'header, nessun cursore nativo sul canvas HUD.
        MapContentBuilder.paint(canvas, player, fm, claims, terrain, 1 << view.getScale().getValue(), plugin, cX, cZ, 1, avatars);
    }
}
