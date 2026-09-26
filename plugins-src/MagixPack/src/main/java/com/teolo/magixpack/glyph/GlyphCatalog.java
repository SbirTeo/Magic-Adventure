package com.teolo.magixpack.glyph;

import com.teolo.magixpack.avatar.AvatarService;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
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
 * Legge {@code glyphs.yml} (creata vuota al primo avvio, la riempie lo staff) e costruisce un font
 * custom — namespace proprio, MAI {@code minecraft:default} — con un provider bitmap per icona,
 * una immagine sola a fare da glifo (niente atlas da impacchettare a mano).
 *
 * <h2>Il punto di codice non si sceglie</h2>
 * Ogni icona vive nell'area privata Unicode (Supplementary Private Use Area-A, a partire da
 * U+F0000): lo staff non lo scrive mai in glyphs.yml, {@link #reload()} lo assegna da solo in
 * ordine ALFABETICO sugli id presenti in quel momento — deterministico (stesso catalogo, stesso
 * risultato, sempre), ma cambia se il catalogo cambia (un'icona tolta o aggiunta puo' spostare i
 * punti di codice di quelle dopo di lei in ordine alfabetico). Per questo un altro plugin la
 * richiama per NOME (vedi {@link #component}), mai scrivendo il carattere a mano: se lo facesse,
 * un catalogo che cambia gli romperebbe l'icona in silenzio.
 */
public final class GlyphCatalog {

    /** Namespace proprio del font (mai "minecraft", per non toccare assets/minecraft/font/default.json:
     *  quel file lo fonde il client per intero, non lo unisce — un override parziale li' sopra
     *  cancellerebbe tutti gli altri provider vanilla). */
    public static final String NAMESPACE = "magixpack";
    private static final String FONT_NAME = "icons";
    public static final Key FONT = Key.key(NAMESPACE, FONT_NAME);

    /** Prima codepoint dell'area privata Unicode supplementare-A: 65534 posizioni libere, mai in
     *  conflitto con un carattere vero di nessuna lingua. */
    private static final int FIRST_CODEPOINT = 0xF0000;

    /** Offsets from a glyph's codepoint to its two invisible spaces for {@code offset-x} (same
     *  private area, far enough apart never to collide with another glyph). */
    private static final int BEFORE_OFFSET = 0x4000;
    private static final int AFTER_OFFSET = 0x8000;

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

        // Ordine alfabetico degli id con texture valida: e' da questo elenco che nasce il punto
        // di codice di ognuna, non dall'ordine in cui compaiono nel file.
        TreeSet<String> ready = new TreeSet<>();
        for (String id : new TreeSet<>(cfg.getKeys(false))) {
            ConfigurationSection sec = cfg.getConfigurationSection(id);
            if (sec != null && TYPE_PLAYER_AVATAR.equalsIgnoreCase(sec.getString("type", ""))) {
                // Drawn per player by AvatarService: no texture, and a font of its own. Base size
                // is the one of a letter (8 tall, 7 above the baseline), then scale and offsets.
                double scale = scale(id, sec);
                int offsetY = sec.getInt("offset-y", 0);
                BitmapFit fit = BitmapFit.of(AvatarService.HEIGHT, AvatarService.HEIGHT * scale,
                        (int) Math.round(7 * scale) + offsetY);
                entries.put(id, new GlyphEntry(id, -1, true, scale, sec.getDouble("offset-x", 0),
                        offsetY, AvatarService.HEIGHT, fit));
                continue;
            }
            if (textureFile(id).isFile()) ready.add(id);
            else plugin.getLogger().warning("[Glyphs] '" + id + "' in glyphs.yml: manca la texture glyphs/"
                    + id + ".png, icona ignorata.");
        }

        int codepoint = FIRST_CODEPOINT;
        for (String id : ready) {
            ConfigurationSection sec = cfg.getConfigurationSection(id);
            int height = sec != null ? sec.getInt("height", 8) : 8;
            int ascent = sec != null ? sec.getInt("ascent", 7) : 7;
            double scale = scale(id, sec);
            double offsetX = sec != null ? sec.getDouble("offset-x", 0) : 0;
            int offsetY = sec != null ? sec.getInt("offset-y", 0) : 0;
            int rows;
            try {
                BufferedImage img = ImageIO.read(textureFile(id));
                if (img == null) throw new IOException("non e' un'immagine");
                rows = img.getHeight();
            } catch (IOException ex) {
                plugin.getLogger().warning("[Glyphs] glyphs/" + id + ".png illeggibile (" + ex.getMessage()
                        + "): icona ignorata.");
                continue;
            }
            BitmapFit fit = BitmapFit.of(rows, height * scale, (int) Math.round(ascent * scale) + offsetY);
            entries.put(id, new GlyphEntry(id, codepoint, false, scale, offsetX, offsetY, rows, fit));
            codepoint++;
        }
        if (!entries.isEmpty()) {
            plugin.getLogger().info("[Glyphs] " + entries.size() + " icona/e custom caricate da glyphs.yml.");
        }
    }

    /** {@code scale} of a voice (1 = as drawn; 2 = twice as big; 0.8 = a fifth smaller). */
    private double scale(String id, ConfigurationSection sec) {
        double scale = sec != null ? sec.getDouble("scale", 1.0) : 1.0;
        if (scale > 0 && scale <= 16) return scale;
        plugin.getLogger().warning("[Glyphs] '" + id + "' in glyphs.yml: scale " + scale
                + " non valido (deve stare fra 0 e 16), uso 1.");
        return 1.0;
    }

    /** The same PNG with {@code rows} transparent rows added at the bottom (see {@link BitmapFit}). */
    private static byte[] padBottom(byte[] png, int rows) throws IOException {
        BufferedImage src = ImageIO.read(new java.io.ByteArrayInputStream(png));
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight() + rows, BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(src, 0, 0, null);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        ImageIO.write(out, "png", bos);
        return bos.toByteArray();
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

    /** Il Component pronto per essere concatenato in un messaggio Adventure (chat, tablist...):
     *  null se l'id non e' nel catalogo. Chi lo usa non deve MAI scrivere il carattere a mano. */
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
        String glyph = new String(Character.toChars(e.codepoint()));
        if (e.offsetX() != 0) {
            // Invisible spaces around the icon: it moves, the text after it stays where it was.
            glyph = new String(Character.toChars(e.codepoint() + BEFORE_OFFSET)) + glyph
                    + new String(Character.toChars(e.codepoint() + AFTER_OFFSET));
        }
        return Component.text().append(Component.text(glyph).font(FONT)).build();
    }

    /** Contenuto da registrare nel pacchetto: un font custom (mai minecraft:default, vedi Javadoc
     *  della classe) con un provider bitmap per icona, piu' le texture lette da glyphs/<id>.png. */
    public Map<String, byte[]> packFiles() {
        Map<String, byte[]> out = new LinkedHashMap<>();

        StringBuilder providers = new StringBuilder();
        boolean first = true;
        for (GlyphEntry e : entries.values()) {
            if (e.playerAvatar()) continue;
            try {
                byte[] texture = Files.readAllBytes(textureFile(e.id()).toPath());
                if (e.fit().padRows() > 0) texture = padBottom(texture, e.fit().padRows());
                out.put("assets/" + NAMESPACE + "/textures/font/" + FONT_NAME + "/" + e.id() + ".png", texture);
            } catch (IOException ex) {
                plugin.getLogger().warning("[Glyphs] Impossibile leggere glyphs/" + e.id() + ".png ("
                        + ex.getMessage() + "): icona esclusa da questo pacchetto.");
                continue;
            }
            if (!first) providers.append(",");
            first = false;
            String glyph = new String(Character.toChars(e.codepoint()));
            providers.append("{\"type\":\"bitmap\",\"file\":\"").append(NAMESPACE).append(':')
                    .append("font/").append(FONT_NAME).append('/').append(e.id()).append(".png")
                    .append("\",\"height\":").append(e.fit().height())
                    .append(",\"ascent\":").append(e.fit().ascent())
                    .append(",\"chars\":[\"").append(glyph).append("\"]}");
        }
        StringBuilder spaces = new StringBuilder();
        for (GlyphEntry e : entries.values()) {
            if (e.playerAvatar() || e.offsetX() == 0) continue;
            if (!spaces.isEmpty()) spaces.append(',');
            spaces.append('"').append(new String(Character.toChars(e.codepoint() + BEFORE_OFFSET))).append("\":")
                    .append(e.offsetX()).append(",\"")
                    .append(new String(Character.toChars(e.codepoint() + AFTER_OFFSET))).append("\":")
                    .append(-e.offsetX());
        }
        if (!spaces.isEmpty()) {
            if (!first) providers.append(',');
            first = false;
            providers.append("{\"type\":\"space\",\"advances\":{").append(spaces).append("}}");
        }
        for (GlyphEntry e : entries.values()) {
            if (e.playerAvatar()) out.putAll(avatars.packFiles(e));
        }
        if (first) return out; // only avatar entries: their fonts are AvatarService's
        String font = "{\"providers\":[" + providers + "]}";
        out.put("assets/" + NAMESPACE + "/font/" + FONT_NAME + ".json", font.getBytes(StandardCharsets.UTF_8));
        return out;
    }
}
