package com.teolo.magixscoreboard.hook;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * I file che MagixScoreboard mette nel resource pack: lo shader "gui" di Minecraft 26.2 con in piu'
 * il riconoscimento dei due riquadri di sfondo della sidebar (vedi i commenti in gui.vsh), che
 * vengono ricolorati e bordati secondo {@code sidebar-background.*} del config. I segnaposto
 * {@code __SB_*__} di gui.fsh si risolvono qui, dal config vivo.
 */
public final class SidebarPack {

    private static final String[] FILES = {
            "assets/minecraft/shaders/core/gui.vsh",
            "assets/minecraft/shaders/core/gui.fsh",
    };

    private SidebarPack() {}

    public static Map<String, byte[]> build(JavaPlugin plugin) throws IOException {
        FileConfiguration c = plugin.getConfig();
        boolean enabled = c.getBoolean("sidebar-background.enabled", true);
        float[] bg = rgb(c.getString("sidebar-background.color", "#000000"), 0f, 0f, 0f);
        double opacity = percent(c.getDouble("sidebar-background.opacity", 30));
        double border = c.getBoolean("sidebar-background.border.enabled", true)
                ? Math.max(0, Math.min(8, c.getDouble("sidebar-background.border.width", 1))) : 0;
        float[] start = rgb(c.getString("sidebar-background.border.color-start", "#A3E635"), 0.64f, 0.9f, 0.21f);
        float[] end = rgb(c.getString("sidebar-background.border.color-end", "#C04FF0"), 0.75f, 0.31f, 0.94f);
        double borderAlpha = percent(c.getDouble("sidebar-background.border.opacity", 100));

        Map<String, String> values = new LinkedHashMap<>();
        values.put("__SB_R__", f(bg[0]));
        values.put("__SB_G__", f(bg[1]));
        values.put("__SB_B__", f(bg[2]));
        values.put("__SB_A__", f(enabled ? opacity : 0));
        values.put("__SB_BORDER__", f(border));
        values.put("__SB_C1_R__", f(start[0]));
        values.put("__SB_C1_G__", f(start[1]));
        values.put("__SB_C1_B__", f(start[2]));
        values.put("__SB_C2_R__", f(end[0]));
        values.put("__SB_C2_G__", f(end[1]));
        values.put("__SB_C2_B__", f(end[2]));
        values.put("__SB_BORDER_A__", f(borderAlpha));
        values.put("__SB_SHIFT__", f(Math.max(-200, Math.min(200, c.getDouble("sidebar-position.offset-y", 0)))));
        values.put("__SB_SCALE_MODE__", String.valueOf(scaleMode(c)));
        values.put("__SB_SCALE_FACTOR__", String.format(Locale.ROOT, "%.8f", scaleFactor(c)));

        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String path : FILES) {
            try (InputStream in = SidebarPack.class.getClassLoader().getResourceAsStream("resourcepack/" + path)) {
                if (in == null) throw new IOException("Risorsa mancante nel jar: resourcepack/" + path);
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                for (Map.Entry<String, String> v : values.entrySet()) text = text.replace(v.getKey(), v.getValue());
                out.put(path, text.getBytes(StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /**
     * sidebar-scale.mode -> codice dello shader (SB_SCALE_MODE in gui.vsh e nel text.vsh di MagixFactions):
     * 0 = off, 1 = in proporzione all'altezza dello schermo (minimap e screen), 2 = integer. Un valore
     * sconosciuto vale "minimap", quello di serie. MagixFactions legge la stessa chiave con la stessa
     * regola (ResourcePackContent): i due shader devono ridimensionare scritte e sfondo insieme.
     */
    public static int scaleMode(FileConfiguration c) {
        String mode = c.getString("sidebar-scale.mode", "minimap");
        if ("off".equalsIgnoreCase(mode)) return 0;
        if ("integer".equalsIgnoreCase(mode)) return 2;
        return 1;
    }

    /**
     * SB_SCALE_FACTOR degli shader: grandezza di un pixel della sidebar per pixel di altezza dello schermo.
     * Col mode "minimap" e' il pixel del testo del pannello info di MagixFactions: quel testo e' disegnato
     * col font della mappa (1 pixel del font = 1 pixel della mappa) e la minimap e' larga
     * screen-size * (16/9) / 2 dell'altezza dello schermo per 128 pixel (vedi hudClip nel suo text.vsh),
     * quindi screen-size * 16 / 9 / 256. Con "screen" e "integer" e' size / 1080. Stessa regola in
     * ResourcePackContent di MagixFactions.
     */
    public static double scaleFactor(FileConfiguration c) {
        String mode = c.getString("sidebar-scale.mode", "minimap");
        boolean byMinimap = !"screen".equalsIgnoreCase(mode) && !"integer".equalsIgnoreCase(mode);
        Plugin mf = Bukkit.getPluginManager().getPlugin("MagixFactions");
        if (byMinimap && mf instanceof JavaPlugin factions) {
            double size = Math.max(0.05, Math.min(0.6, factions.getConfig().getDouble("map.minimap.screen-size", 0.22)));
            return size * 16.0 / 9.0 / 256.0;
        }
        return Math.max(1, Math.min(6, c.getDouble("sidebar-scale.size", 3))) / 1080.0;
    }

    /** Percentuale 0-100 del config -> 0..1 per lo shader. */
    private static double percent(double value) {
        return Math.max(0, Math.min(100, value)) / 100.0;
    }

    /** "#RRGGBB" (o "RRGGBB") -> componenti 0..1; un valore illeggibile torna al colore di riserva. */
    private static float[] rgb(String hex, float r, float g, float b) {
        String t = hex == null ? "" : hex.trim();
        if (t.startsWith("&")) t = t.substring(1);
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() == 6 && t.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) {
            return new float[] {
                    Integer.parseInt(t.substring(0, 2), 16) / 255f,
                    Integer.parseInt(t.substring(2, 4), 16) / 255f,
                    Integer.parseInt(t.substring(4, 6), 16) / 255f };
        }
        return new float[] { r, g, b };
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
