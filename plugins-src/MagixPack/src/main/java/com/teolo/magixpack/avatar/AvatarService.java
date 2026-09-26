package com.teolo.magixpack.avatar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.teolo.magixpack.glyph.BitmapFit;
import com.teolo.magixpack.glyph.GlyphEntry;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerTextures;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The player's avatar — the flat FACE of the skin, hat layer included — as text: a "pixel font"
 * glyph that fits in a normal line of chat, like a letter.
 *
 * <h2>Why a pixel font</h2>
 * A resource pack is the same for everyone, so it cannot contain one image per player. Instead the
 * pack carries a tiny font (one per {@code player-avatar} voice of glyphs.yml, {@code
 * magixpack:avatar/<id>}: each has its own scale and offsets) with 8 "pixel" characters — character
 * {@code i} is a single white pixel on row {@code i} of an 8 px tall cell — plus two space
 * characters (one steps back, one steps forward). The avatar is then drawn at runtime, column by
 * column: for every non-transparent pixel of the skin the right row character, COLORED with that
 * pixel's color, followed by the step-back space; at the end of the column the step-forward space.
 * The client draws every colored pixel exactly where it belongs: at scale 1 the 8x8 face takes the
 * place of one character, as tall as a letter.
 *
 * <h2>Where the skin comes from</h2>
 * The server runs in offline mode, so the profile usually has no textures: first the profile is
 * tried (a skin plugin such as SkinsRestorer puts it there), then — if {@code avatar.mojang-lookup}
 * is on — Mojang is asked by name (the skin of the premium account with that name). Everything
 * runs off the main thread and the result is cached per name, refreshed at every join.
 */
public final class AvatarService {

    /** Avatar size in skin pixels: the face. */
    public static final int WIDTH = 8;
    public static final int HEIGHT = 8;

    /** Row characters U+E000..U+E007, then the spaces (back, step, offset-x before and after).
     *  Own fonts: no clash with any other one. */
    private static final int FIRST_ROW = 0xE000;
    private static final char BACK = '\uE100';
    private static final char STEP = '\uE101';
    private static final char SHIFT_BEFORE = '\uE102';
    private static final char SHIFT_AFTER = '\uE103';

    private final JavaPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
    /** Lower-case name -> the face (8x8 ARGB): the Component is built from it per glyph voice. */
    private final Map<String, int[][]> cache = new ConcurrentHashMap<>();
    /** Lower-case name -> the skin the cached avatar was drawn from (for heads in offline mode). */
    private final Map<String, Skin> skins = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<int[][]>> inFlight = new ConcurrentHashMap<>();
    /** Lower-case name -> when no skin was found: not retried before {@link #MISS_RETRY_MS}, so a
     *  caller asking every tick (tablist, scoreboard) does not hammer Mojang for a skinless name. */
    private final Map<String, Long> misses = new ConcurrentHashMap<>();
    private static final long MISS_RETRY_MS = 10 * 60 * 1000L;
    private static final java.util.regex.Pattern VALID_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");

    private boolean mojangLookup;

    public AvatarService(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        mojangLookup = plugin.getConfig().getBoolean("avatar.mojang-lookup", true);
        cache.clear();
        skins.clear();
        misses.clear();
    }

    /** The skin found for {@code name} (null if none, or not downloaded yet). */
    public Skin skin(String name) {
        return skins.get(key(name));
    }

    // -------------------------------------------------------------------------------------- pack

    /** The font of a {@code player-avatar} voice of glyphs.yml. */
    public static Key font(GlyphEntry e) {
        return Key.key("magixpack", "avatar/" + fileName(e.id()));
    }

    /** Resource pack paths are lower case, [a-z0-9_.-]: anything else in an id becomes '_'. */
    private static String fileName(String id) {
        return id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    /** Font and pixel texture of one {@code player-avatar} voice, with its scale and offsets. */
    public Map<String, byte[]> packFiles(GlyphEntry e) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        BitmapFit fit = e.fit();
        String name = fileName(e.id());
        // One column per row character, the pixel on its own row; padRows empty rows at the bottom
        // let the voice sit higher than the client would otherwise allow (see BitmapFit).
        BufferedImage atlas = new BufferedImage(HEIGHT, HEIGHT + fit.padRows(), BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < HEIGHT; i++) atlas.setRGB(i, i, 0xFFFFFFFF);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(atlas, "png", bos);
            out.put("assets/magixpack/textures/font/avatar/" + name + ".png", bos.toByteArray());
        } catch (IOException ex) {
            plugin.getLogger().warning("[Avatar] Impossibile generare la texture dei pixel: " + ex.getMessage());
            return Map.of();
        }
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < HEIGHT; i++) rows.append(String.format("\\u%04X", FIRST_ROW + i));
        // Vanilla gives a bitmap glyph an advance of round(width * scale) + 1 (width = 1 here): the
        // step-back space cancels exactly that, the step space moves on by one drawn pixel.
        double k = fit.pixelScale();
        int advance = (int) (0.5 + k) + 1;
        String font = "{\"providers\":["
                + "{\"type\":\"bitmap\",\"file\":\"magixpack:font/avatar/" + name + ".png\",\"height\":" + fit.height()
                + ",\"ascent\":" + fit.ascent() + ",\"chars\":[\"" + rows + "\"]},"
                + "{\"type\":\"space\",\"advances\":{"
                + "\"" + esc(BACK) + "\":" + (-advance)
                + ",\"" + esc(STEP) + "\":" + k
                + ",\"" + esc(SHIFT_BEFORE) + "\":" + e.offsetX()
                + ",\"" + esc(SHIFT_AFTER) + "\":" + (-e.offsetX()) + "}}"
                + "]}";
        out.put("assets/magixpack/font/avatar/" + name + ".json", font.getBytes(StandardCharsets.UTF_8));
        return out;
    }

    private static String esc(char c) {
        return String.format("\\u%04X", (int) c);
    }

    // --------------------------------------------------------------------------------------- API

    /** The cached face, or null if not ready yet (a fetch is started in the background). */
    public int[][] cachedFace(Player player) {
        int[][] c = cache.get(key(player.getName()));
        if (c == null) fetch(player);
        return c;
    }

    /** Drops the cached face and downloads it again (at join: the skin may have changed). */
    public void refresh(Player player) {
        cache.remove(key(player.getName()));
        skins.remove(key(player.getName()));
        misses.remove(key(player.getName()));
        fetch(player);
    }

    /** Completes (on a background thread) with the face, or with null when no skin is found. */
    public CompletableFuture<int[][]> fetch(Player player) {
        PlayerTextures textures = player.getPlayerProfile().getTextures();
        URL skin = textures.getSkin();
        boolean slim = textures.getSkinModel() == PlayerTextures.SkinModel.SLIM;
        return fetch(player.getName(), skin, slim);
    }

    /** Same as {@link #fetch(Player)} for a name (online or not): only the Mojang lookup applies. */
    public CompletableFuture<int[][]> fetch(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? fetch(online) : fetch(name, null, false);
    }

    private CompletableFuture<int[][]> fetch(String name, URL profileSkin, boolean profileSlim) {
        String key = key(name);
        int[][] done = cache.get(key);
        if (done != null) return CompletableFuture.completedFuture(done);
        Long miss = misses.get(key);
        if (miss != null && System.currentTimeMillis() - miss < MISS_RETRY_MS) return CompletableFuture.completedFuture(null);
        CompletableFuture<int[][]> future = inFlight.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            try {
                Skin skin = profileSkin != null ? new Skin(profileSkin.toString(), profileSlim) : null;
                if (skin == null && mojangLookup && VALID_NAME.matcher(name).matches()) skin = mojangSkin(name);
                BufferedImage img = skin == null ? null : ImageIO.read(new ByteArrayInputStream(get(skin.url)));
                if (img == null) {
                    misses.put(k, System.currentTimeMillis());
                    return null;
                }
                int[][] face = face(img);
                skins.put(k, skin);
                cache.put(k, face);
                return face;
            } catch (Exception e) {
                misses.put(k, System.currentTimeMillis());
                plugin.getLogger().warning("[Avatar] Skin di '" + name + "' non scaricata: " + e.getMessage());
                return null;
            }
        }, r -> Bukkit.getScheduler().runTaskAsynchronously(plugin, r)));
        future.whenComplete((c, t) -> inFlight.remove(key, future));
        return future;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------------------------- Mojang

    /** Where a skin lives (textures.minecraft.net) and whether it has the slim arms. */
    public record Skin(String url, boolean slim) {}

    private Skin mojangSkin(String name) throws IOException, InterruptedException {
        byte[] idJson = getOrNull("https://api.mojang.com/users/profiles/minecraft/" + name);
        if (idJson == null) return null;
        String id = JsonParser.parseString(new String(idJson, StandardCharsets.UTF_8)).getAsJsonObject().get("id").getAsString();
        byte[] profile = getOrNull("https://sessionserver.mojang.com/session/minecraft/profile/" + id);
        if (profile == null) return null;
        for (var prop : JsonParser.parseString(new String(profile, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("properties")) {
            JsonObject p = prop.getAsJsonObject();
            if (!"textures".equals(p.get("name").getAsString())) continue;
            String decoded = new String(Base64.getDecoder().decode(p.get("value").getAsString()), StandardCharsets.UTF_8);
            JsonObject tex = JsonParser.parseString(decoded).getAsJsonObject().getAsJsonObject("textures");
            if (tex == null || !tex.has("SKIN")) return null;
            JsonObject s = tex.getAsJsonObject("SKIN");
            boolean slim = s.has("metadata") && "slim".equals(s.getAsJsonObject("metadata").get("model").getAsString());
            return new Skin(s.get("url").getAsString(), slim);
        }
        return null;
    }

    private byte[] get(String url) throws IOException, InterruptedException {
        byte[] b = getOrNull(url);
        if (b == null) throw new IOException("HTTP non 200 da " + url);
        return b;
    }

    private byte[] getOrNull(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return r.statusCode() == 200 ? r.body() : null;
    }

    // ------------------------------------------------------------------------------------- render

    /** The face of the skin, 8x8 ARGB (0 = transparent), hat layer composited on top. */
    static int[][] face(BufferedImage skin) {
        int s = Math.max(1, skin.getWidth() / 64);
        boolean legacy = skin.getHeight() * 2 == skin.getWidth(); // old 64x32 skins
        int[][] out = new int[HEIGHT][WIDTH];
        copy(skin, s, out, 8, 8, 8, 8, 0, 0, true);
        if (!legacy || !hatFullyOpaque(skin, s)) copy(skin, s, out, 40, 8, 8, 8, 0, 0, false);
        return out;
    }

    /** Same rule as the vanilla client ("Notch transparency hack"): an old 64x32 skin whose hat
     *  area has no transparent pixel at all never meant to have a hat, and the layer is ignored. */
    private static boolean hatFullyOpaque(BufferedImage skin, int s) {
        for (int y = 0; y < 16 * s; y++) {
            for (int x = 32 * s; x < 64 * s; x++) {
                if ((skin.getRGB(x, y) >>> 24) < 128) return false;
            }
        }
        return true;
    }

    /** Copies a w x h face from the skin (at sx,sy in 64-px units, scale s) to out at dx,dy. The
     *  base layer is opaque; an overlay pixel replaces it only where it is not transparent. */
    private static void copy(BufferedImage skin, int s, int[][] out, int sx, int sy, int w, int h,
                             int dx, int dy, boolean base) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int px = (sx + x) * s, py = (sy + y) * s;
                if (px >= skin.getWidth() || py >= skin.getHeight()) continue;
                int argb = skin.getRGB(px, py);
                int alpha = argb >>> 24;
                if (base) out[dy + y][dx + x] = argb | 0xFF000000;
                else if (alpha > 0) out[dy + y][dx + x] = blend(out[dy + y][dx + x], argb, alpha);
            }
        }
    }

    private static int blend(int under, int over, int alpha) {
        if (alpha >= 255 || under == 0) return over | 0xFF000000;
        int r = (((over >> 16) & 0xFF) * alpha + ((under >> 16) & 0xFF) * (255 - alpha)) / 255;
        int g = (((over >> 8) & 0xFF) * alpha + ((under >> 8) & 0xFF) * (255 - alpha)) / 255;
        int b = ((over & 0xFF) * alpha + (under & 0xFF) * (255 - alpha)) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The face as text, in the font of the {@code player-avatar} voice {@code e}. */
    public Component render(GlyphEntry e, int[][] px) {
        TextComponent.Builder root = Component.text().font(font(e)).shadowColor(ShadowColor.none());
        if (e.offsetX() != 0) root.append(Component.text(String.valueOf(SHIFT_BEFORE)));
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                int argb = px[y][x];
                if (argb == 0) continue;
                root.append(Component.text(new String(new char[]{(char) (FIRST_ROW + y), BACK}))
                        .color(TextColor.color(argb & 0xFFFFFF)));
            }
            root.append(Component.text(String.valueOf(STEP)));
        }
        if (e.offsetX() != 0) root.append(Component.text(String.valueOf(SHIFT_AFTER)));
        // Wrapped in a plain parent: whatever a caller appends after the avatar (a name, a caption)
        // must not inherit the pixel font, which has no letters (they would show as empty boxes).
        return Component.text().append(root.build()).build();
    }
}
