package com.teolo.magixpack.glyph;

import com.teolo.magixpack.avatar.AvatarService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Legge {@code glyphs.yml} (la riempie lo staff) e mette ogni icona nel font NORMALE di Minecraft
 * ({@code minecraft:default}), come fa Oraxen: cosi' il carattere di un'icona funziona in QUALUNQUE
 * testo — incollato in un messaggio, in un config di CMI, in un placeholder di PlaceholderAPI — e
 * non solo in un Component Adventure che sappia scegliere un font.
 *
 * <h2>Il file default.json non si sostituisce, si fonde</h2>
 * Il client prende {@code assets/minecraft/font/default.json} dal pacchetto per intero, non lo unisce
 * al suo: un file che contenesse solo le icone cancellerebbe tutte le lettere. Per questo il font
 * generato qui richiama anche i font vanilla ({@code include/space}, {@code include/default},
 * {@code include/unifont}), e {@code pack.PackService} fonde i default.json di tutti i plugin (il
 * logo del tablist di MagixFactions vive nello stesso file) invece di tenerne uno solo.
 *
 * <h2>Il carattere lo sceglie il plugin, a ogni avvio</h2>
 * Lo staff non lo scrive mai in glyphs.yml: {@link #reload()} lo assegna da solo in ordine
 * ALFABETICO sugli id presenti, nell'area privata Unicode da U+E800 (mai un carattere vero di
 * nessuna lingua; sotto, U+E000-E7FF, restano liberi per gli altri plugin — il logo di MagixFactions
 * e' U+E010). Stesso catalogo, stessi caratteri; ma se il catalogo cambia possono spostarsi: il
 * carattere attuale di ognuna si vede con {@code /mpack glyph list}, e da un altro plugin o da un
 * config si richiama per NOME, col placeholder {@code %magixpack_glyph_<id>%}, che non cambia mai.
 */
public final class GlyphCatalog {

    /** Namespace of MagixPack's own files in the pack (textures of the icons). */
    public static final String NAMESPACE = "magixpack";

    /** The font every glyph lives in: the vanilla one, so that plain text can show them. */
    public static final String FONT_PATH = "assets/minecraft/font/default.json";

    /** First character assigned (Basic Multilingual Plane private use area: one char, not a
     *  surrogate pair, so it can be pasted and typed like a letter). */
    private static final int FIRST_CODEPOINT = 0xE800;
    private static final int LAST_CODEPOINT = 0xF8FF;

    /** Characters an icon takes: itself, then the invisible space before and after it (offset-x). */
    public static final int ICON_CHARS = 3;

    /** {@code type} of a glyphs.yml entry that is the avatar of a player (the face of the skin). */
    public static final String TYPE_PLAYER_AVATAR = "player-avatar";

    private final JavaPlugin plugin;
    private final AvatarService avatars;
    private final Map<String, GlyphEntry> entries = new LinkedHashMap<>();

    public GlyphCatalog(JavaPlugin plugin, AvatarService avatars) {
        this.plugin = plugin;
        this.avatars = avatars;
    }

    public void reload() {
        entries.clear();
        File file = new File(plugin.getDataFolder(), "glyphs.yml");
        if (!file.exists()) plugin.saveResource("glyphs.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        // Alphabetical order of the ids: the characters come from this order, not from the file's.
        int codepoint = FIRST_CODEPOINT;
        for (String id : new TreeSet<>(cfg.getKeys(false))) {
            ConfigurationSection sec = cfg.getConfigurationSection(id);
            if (sec == null) continue;
            double scale = scale(id, sec);
            double offsetX = sec.getDouble("offset-x", 0);
            int offsetY = sec.getInt("offset-y", 0);
            boolean avatar = TYPE_PLAYER_AVATAR.equalsIgnoreCase(sec.getString("type", ""));
            int rows;
            BitmapFit fit;
            if (avatar) {
                // Drawn per player by AvatarService. Base size is the one of a letter (8 tall, 7
                // above the baseline), then scale and offsets.
                rows = AvatarService.HEIGHT;
                fit = BitmapFit.of(rows, rows * scale, (int) Math.round(7 * scale) + offsetY);
            } else {
                if (!textureFile(id).isFile()) {
                    plugin.getLogger().warning("[Glyphs] '" + id + "' in glyphs.yml: manca la texture glyphs/"
                            + id + ".png, icona ignorata.");
                    continue;
                }
                try {
                    BufferedImage img = ImageIO.read(textureFile(id));
                    if (img == null) throw new IOException("non e' un'immagine");
                    rows = img.getHeight();
                } catch (IOException ex) {
                    plugin.getLogger().warning("[Glyphs] glyphs/" + id + ".png illeggibile (" + ex.getMessage()
                            + "): icona ignorata.");
                    continue;
                }
                int height = sec.getInt("height", 8);
                int ascent = sec.getInt("ascent", 7);
                fit = BitmapFit.of(rows, height * scale, (int) Math.round(ascent * scale) + offsetY);
            }
            int size = avatar ? AvatarService.CHARS : ICON_CHARS;
            if (codepoint + size - 1 > LAST_CODEPOINT) {
                plugin.getLogger().warning("[Glyphs] Finiti i caratteri disponibili: '" + id + "' e le icone "
                        + "dopo di lei in ordine alfabetico sono ignorate.");
                break;
            }
            entries.put(id, new GlyphEntry(id, codepoint, avatar, scale, offsetX, offsetY, rows, fit));
            codepoint += size;
        }
        if (!entries.isEmpty()) {
            plugin.getLogger().info("[Glyphs] " + entries.size() + " icona/e custom caricate da glyphs.yml.");
        }
    }

    /** {@code scale} of a voice (1 = as drawn; 2 = twice as big; 0.8 = a fifth smaller). */
    private double scale(String id, ConfigurationSection sec) {
        double scale = sec.getDouble("scale", 1.0);
        if (scale > 0 && scale <= 16) return scale;
        plugin.getLogger().warning("[Glyphs] '" + id + "' in glyphs.yml: scale " + scale
                + " non valido (deve stare fra 0 e 16), uso 1.");
        return 1.0;
    }

    private File textureFile(String id) {
        return new File(new File(plugin.getDataFolder(), "glyphs"), id + ".png");
    }

    public List<String> ids() {
        return new ArrayList<>(entries.keySet());
    }

    public GlyphEntry entry(String id) {
        return entries.get(id);
    }

    // ---------------------------------------------------------------------------------- the text

    /** The text of an icon (not an avatar): the character, wrapped in its offset-x spaces when it
     *  has one. Plain text in the default font: it works anywhere. */
    public String text(GlyphEntry e) {
        StringBuilder sb = new StringBuilder();
        if (e.offsetX() != 0) sb.append((char) (e.codepoint() + 1));
        sb.append((char) e.codepoint());
        if (e.offsetX() != 0) sb.append((char) (e.codepoint() + 2));
        return sb.toString();
    }

    /** Il Component pronto per essere concatenato in un messaggio Adventure (chat, tablist...):
     *  null se l'id non e' nel catalogo (o se e' un avatar: serve il giocatore). */
    public Component component(String id) {
        return component(id, null);
    }

    /** Like {@link #component(String)}; {@code player} is whose avatar a {@code player-avatar}
     *  entry shows (null if not downloaded yet: the download starts now). */
    public Component component(String id, Player player) {
        GlyphEntry e = entries.get(id);
        if (e == null) return null;
        if (e.playerAvatar()) {
            int[][] face = player != null ? avatars.cachedFace(player) : null;
            return face != null ? avatars.render(e, face) : null;
        }
        // White: a bitmap glyph is tinted by the text color, white keeps the colors of the image.
        // Wrapped in a plain parent, so that what is appended after it keeps its own color.
        return Component.text().append(Component.text(text(e)).color(NamedTextColor.WHITE)).build();
    }

    /** Same as {@link #component(String, Player)} as a legacy string (section-sign colors), for
     *  PlaceholderAPI and every plugin that takes plain text; null when not available. */
    public String legacy(String id, Player player) {
        GlyphEntry e = entries.get(id);
        if (e == null) return null;
        if (e.playerAvatar()) {
            int[][] face = player != null ? avatars.cachedFace(player) : null;
            return face != null ? avatars.legacy(e, face) : null;
        }
        return "§f" + text(e);
    }

    // -------------------------------------------------------------------------------------- pack

    /** Pack content: the textures and ONE default.json with every glyph (icons and avatars), their
     *  spaces and the references to the vanilla fonts (see the class Javadoc). */
    public Map<String, byte[]> packFiles() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (entries.isEmpty()) return out;
        List<String> providers = new ArrayList<>();
        Map<Integer, Double> spaces = new LinkedHashMap<>();
        for (GlyphEntry e : entries.values()) {
            BitmapFit fit = e.fit();
            if (e.playerAvatar()) {
                out.put("assets/" + NAMESPACE + "/textures/font/avatar/" + fileName(e.id()) + ".png",
                        avatars.atlas(e));
                providers.add(bitmap("font/avatar/" + fileName(e.id()) + ".png", fit, avatars.rowChars(e)));
                spaces.putAll(avatars.spaces(e));
                continue;
            }
            try {
                byte[] texture = Files.readAllBytes(textureFile(e.id()).toPath());
                if (fit.padRows() > 0) texture = padBottom(texture, fit.padRows());
                out.put("assets/" + NAMESPACE + "/textures/font/icons/" + fileName(e.id()) + ".png", texture);
            } catch (IOException ex) {
                plugin.getLogger().warning("[Glyphs] Impossibile leggere glyphs/" + e.id() + ".png ("
                        + ex.getMessage() + "): icona esclusa da questo pacchetto.");
                continue;
            }
            providers.add(bitmap("font/icons/" + fileName(e.id()) + ".png", fit, esc(e.codepoint())));
            if (e.offsetX() != 0) {
                spaces.put(e.codepoint() + 1, e.offsetX());
                spaces.put(e.codepoint() + 2, -e.offsetX());
            }
        }
        if (!spaces.isEmpty()) {
            StringBuilder sb = new StringBuilder("{\"type\":\"space\",\"advances\":{");
            boolean first = true;
            for (Map.Entry<Integer, Double> s : spaces.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(esc(s.getKey())).append("\":").append(s.getValue());
            }
            providers.add(sb.append("}}").toString());
        }
        // The vanilla default.json, verbatim: without these the client would lose every letter.
        providers.add("{\"type\":\"reference\",\"id\":\"minecraft:include/space\"}");
        providers.add("{\"type\":\"reference\",\"id\":\"minecraft:include/default\",\"filter\":{\"uniform\":false}}");
        providers.add("{\"type\":\"reference\",\"id\":\"minecraft:include/unifont\"}");
        String font = "{\"providers\":[" + String.join(",", providers) + "]}";
        out.put(FONT_PATH, font.getBytes(StandardCharsets.UTF_8));
        return out;
    }

    private static String bitmap(String file, BitmapFit fit, String chars) {
        return "{\"type\":\"bitmap\",\"file\":\"" + NAMESPACE + ":" + file + "\",\"height\":" + fit.height()
                + ",\"ascent\":" + fit.ascent() + ",\"chars\":[\"" + chars + "\"]}";
    }

    /** JSON escape of a BMP character. */
    public static String esc(int codepoint) {
        return String.format("\\u%04X", codepoint);
    }

    /** Resource pack paths are lower case, [a-z0-9_.-]: anything else in an id becomes '_'. */
    public static String fileName(String id) {
        return id.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    /** The same PNG with {@code rows} transparent rows added at the bottom (see {@link BitmapFit}). */
    private static byte[] padBottom(byte[] png, int rows) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight() + rows, BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(src, 0, 0, null);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bos);
        return bos.toByteArray();
    }
}
