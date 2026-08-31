package com.teolo.magixfactions.map;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerTextures;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Icona 8x8 del volto del giocatore (ritagliata dalla sua skin), usata come marcatore su ENTRAMBE le
 * mappe (cartacea e minimap HUD) al posto della freccia generica. Salvata come {@link Color}[] RGB (non
 * come byte-palette) cosi' entrambe le pipeline la usano: la cartacea disegna Color direttamente sul
 * canvas, la minimap li converte in byte-palette al momento. Scaricare/decodificare la skin e' I/O di
 * rete: va SEMPRE fatto fuori dal main thread ({@link #ensureLoaded}); il risultato viene riportato sul
 * main thread prima di entrare in cache — la {@link HashMap} resta acceduta da un solo thread.
 */
final class AvatarCache {

    private final JavaPlugin plugin;
    private final Map<UUID, Color[]> icons = new HashMap<>();
    private final Set<UUID> loading = new HashSet<>();

    AvatarCache(JavaPlugin plugin) { this.plugin = plugin; }

    /** Volto 8x8 (64 {@link Color} RGB, ordine riga per riga) del giocatore, o null se non ancora pronto
     *  — il chiamante deve avere gia' invocato (o invocare ora) {@link #ensureLoaded}. */
    Color[] get(UUID uuid) { return icons.get(uuid); }

    /** Avvia (se non gia' fatto/in corso) il download asincrono della skin e la mette in cache al termine. */
    void ensureLoaded(Player p) {
        UUID u = p.getUniqueId();
        if (icons.containsKey(u) || loading.contains(u)) return;
        URL skinUrl;
        try {
            PlayerTextures textures = p.getPlayerProfile().getTextures();
            skinUrl = textures.getSkin();
        } catch (Exception e) {
            return; // niente profilo/texture ancora pronti: ritenta al prossimo render
        }
        if (skinUrl == null) return;
        loading.add(u);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Color[] icon = null;
            try {
                BufferedImage skin = ImageIO.read(skinUrl);
                icon = buildFaceIcon(skin);
            } catch (Exception e) {
                plugin.getLogger().warning("[Minimap] Avatar non scaricabile per " + p.getName() + ": " + e.getMessage());
            }
            Color[] result = icon;
            Bukkit.getScheduler().runTask(plugin, () -> {
                loading.remove(u);
                if (result != null) icons.put(u, result);
            });
        });
    }

    /** Ritaglia il volto 8x8 (layer base a 8,8 + overlay "hat" a 40,8, se opaco li' vince lui) come Color RGB. */
    private static Color[] buildFaceIcon(BufferedImage skin) {
        Color[] out = new Color[8 * 8];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int base = skin.getRGB(8 + x, 8 + y);
                int hat = skin.getRGB(40 + x, 8 + y);
                int rgb = isOpaque(hat) ? hat : base;
                out[y * 8 + x] = new Color(rgb, false);
            }
        }
        return out;
    }

    private static boolean isOpaque(int argb) { return (argb >>> 24) >= 128; }
}
