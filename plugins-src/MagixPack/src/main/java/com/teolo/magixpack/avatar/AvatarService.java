package com.teolo.magixpack.avatar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * The player's full-body avatar (front view of the skin, second layer included) as a single line
 * of text: a "pixel font" glyph, not a head and not an item.
 *
 * <h2>Why a pixel font</h2>
 * A resource pack is the same for everyone, so it cannot contain one image per player. Instead the
 * pack carries a tiny font ({@code magixpack:avatar}) with 32 "pixel" characters — character
 * {@code i} is a single white pixel on row {@code i} of a 32 px tall cell — plus two space
 * characters (one steps back, one steps forward). The avatar is then drawn at runtime, column by
 * column: for every non-transparent pixel of the skin the right row character, COLORED with that
 * pixel's color, followed by the step-back space; at the end of the column the step-forward space.
 * The client draws every colored pixel exactly where it belongs, and the whole 16x32 figure takes a
 * single text line (it sticks out above it, like a tall icon).
 *
 * <h2>Where the skin comes from</h2>
 * The server runs in offline mode, so the profile usually has no textures: first the profile is
 * tried (a skin plugin such as SkinsRestorer puts it there), then — if {@code avatar.mojang-lookup}
 * is on — Mojang is asked by name (the skin of the premium account with that name). Everything
 * runs off the main thread and the result is cached per name, refreshed at every join.
 */
public final class AvatarService {

    public static final Key FONT = Key.key("magixpack", "avatar");

    /** Avatar size in skin pixels (front view: head 8, body 12, legs 12; arms + body 16). */
    public static final int WIDTH = 16;
    public static final int HEIGHT = 32;

    /** Row characters U+E000..U+E01F, then the two spaces. Own font: no clash with any other one. */
    private static final int FIRST_ROW = 0xE000;
    private static final char BACK = '';
    private static final char STEP = '';

    private final JavaPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
    /** Lower-case name -> finished avatar (Component.empty() never stored: a miss is retried). */
    private final Map<String, Component> cache = new ConcurrentHashMap<>();
    /** Lower-case name -> the skin the cached avatar was drawn from (for heads in offline mode). */
    private final Map<String, Skin> skins = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Component>> inFlight = new ConcurrentHashMap<>();
    /** Lower-case name -> when no skin was found: not retried before {@link #MISS_RETRY_MS}, so a
     *  caller asking every tick (tablist, scoreboard) does not hammer Mojang for a skinless name. */
    private final Map<String, Long> misses = new ConcurrentHashMap<>();
    private static final long MISS_RETRY_MS = 10 * 60 * 1000L;
    private static final java.util.regex.Pattern VALID_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");

    private int pixelSize;
    private int ascent;
    private boolean mojangLookup;

    public AvatarService(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        pixelSize = Math.max(1, Math.min(4, plugin.getConfig().getInt("avatar.pixel-size", 1)));
        int offset = Math.max(0, plugin.getConfig().getInt("avatar.baseline-offset", 1));
        // Vanilla refuses a bitmap glyph whose ascent is higher than its height.
        ascent = Math.min(HEIGHT * pixelSize, HEIGHT * pixelSize - offset);
        mojangLookup = plugin.getConfig().getBoolean("avatar.mojang-lookup", true);
        cache.clear();
        skins.clear();
        misses.clear();
    }

    /** How many empty lines to leave above the avatar so that it does not cover the text before
     *  it: it sticks out {@code ascent - 7} pixels above a normal glyph (ascent 7). */
    public int emptyLinesAbove(int lineHeight) {
        return (Math.max(0, ascent - 7) + lineHeight - 1) / lineHeight;
    }

    /** The skin found for {@code name} (null if none, or not downloaded yet). */
    public Skin skin(String name) {
        return skins.get(key(name));
    }

    // -------------------------------------------------------------------------------------- pack

    public Map<String, byte[]> packFiles() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        BufferedImage atlas = new BufferedImage(HEIGHT, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < HEIGHT; i++) atlas.setRGB(i, i, 0xFFFFFFFF);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(atlas, "png", bos);
            out.put("assets/magixpack/textures/font/avatar/pixels.png", bos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().warning("[Avatar] Impossibile generare la texture dei pixel: " + e.getMessage());
            return Map.of();
        }
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < HEIGHT; i++) rows.append(String.format("\\u%04X", FIRST_ROW + i));
        // A bitmap glyph advances by (width * scale) + 1: the step-back space cancels exactly that.
        int advance = pixelSize + 1;
        String font = "{\"providers\":["
                + "{\"type\":\"bitmap\",\"file\":\"magixpack:font/avatar/pixels.png\",\"height\":" + (HEIGHT * pixelSize)
                + ",\"ascent\":" + ascent + ",\"chars\":[\"" + rows + "\"]},"
                + "{\"type\":\"space\",\"advances\":{\"" + String.format("\\u%04X", (int) BACK) + "\":" + (-advance)
                + ",\"" + String.format("\\u%04X", (int) STEP) + "\":" + pixelSize + "}}"
                + "]}";
        out.put("assets/magixpack/font/avatar.json", font.getBytes(StandardCharsets.UTF_8));
        return out;
    }

    // --------------------------------------------------------------------------------------- API

    /** The cached avatar, or null if not ready yet (a fetch is started in the background). */
    public Component cached(Player player) {
        Component c = cache.get(key(player.getName()));
        if (c == null) fetch(player);
        return c;
    }

    /** Drops the cached avatar and downloads it again (at join: the skin may have changed). */
    public void refresh(Player player) {
        cache.remove(key(player.getName()));
        skins.remove(key(player.getName()));
        misses.remove(key(player.getName()));
        fetch(player);
    }

    /** Completes (on a background thread) with the avatar, or with null when no skin is found. */
    public CompletableFuture<Component> fetch(Player player) {
        PlayerTextures textures = player.getPlayerProfile().getTextures();
        URL skin = textures.getSkin();
        boolean slim = textures.getSkinModel() == PlayerTextures.SkinModel.SLIM;
        return fetch(player.getName(), skin, slim);
    }

    /** Same as {@link #fetch(Player)} for a name (online or not): only the Mojang lookup applies. */
    public CompletableFuture<Component> fetch(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? fetch(online) : fetch(name, null, false);
    }

    private CompletableFuture<Component> fetch(String name, URL profileSkin, boolean profileSlim) {
        String key = key(name);
        Component done = cache.get(key);
        if (done != null) return CompletableFuture.completedFuture(done);
        Long miss = misses.get(key);
        if (miss != null && System.currentTimeMillis() - miss < MISS_RETRY_MS) return CompletableFuture.completedFuture(null);
        CompletableFuture<Component> future = inFlight.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            try {
                Skin skin = profileSkin != null ? new Skin(profileSkin.toString(), profileSlim) : null;
                if (skin == null && mojangLookup && VALID_NAME.matcher(name).matches()) skin = mojangSkin(name);
                BufferedImage img = skin == null ? null : ImageIO.read(new ByteArrayInputStream(get(skin.url)));
                if (img == null) {
                    misses.put(k, System.currentTimeMillis());
                    return null;
                }
                Component c = render(img, skin.slim);
                skins.put(k, skin);
                cache.put(k, c);
                return c;
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

    /** Front view of the skin, 16x32 ARGB (0 = transparent), second layer composited on top. */
    static int[][] frontView(BufferedImage skin, boolean slim) {
        int s = Math.max(1, skin.getWidth() / 64);
        boolean legacy = skin.getHeight() * 2 == skin.getWidth(); // 64x32: no left limbs, only the hat
        int arm = slim ? 3 : 4;
        int[][] out = new int[HEIGHT][WIDTH];
        // head, hat
        copy(skin, s, out, 8, 8, 8, 8, 4, 0, false, true);
        if (!legacy || !hatFullyOpaque(skin, s)) copy(skin, s, out, 40, 8, 8, 8, 4, 0, false, false);
        // body, jacket
        copy(skin, s, out, 20, 20, 8, 12, 4, 8, false, true);
        if (!legacy) copy(skin, s, out, 20, 36, 8, 12, 4, 8, false, false);
        // right arm (viewer's left), sleeve
        copy(skin, s, out, 44, 20, arm, 12, 4 - arm, 8, false, true);
        if (!legacy) copy(skin, s, out, 44, 36, arm, 12, 4 - arm, 8, false, false);
        // left arm (viewer's right): legacy skins mirror the right one
        if (legacy) copy(skin, s, out, 44, 20, arm, 12, 12, 8, true, true);
        else {
            copy(skin, s, out, 36, 52, arm, 12, 12, 8, false, true);
            copy(skin, s, out, 52, 52, arm, 12, 12, 8, false, false);
        }
        // right leg, pants
        copy(skin, s, out, 4, 20, 4, 12, 4, 20, false, true);
        if (!legacy) copy(skin, s, out, 4, 36, 4, 12, 4, 20, false, false);
        // left leg
        if (legacy) copy(skin, s, out, 4, 20, 4, 12, 8, 20, true, true);
        else {
            copy(skin, s, out, 20, 52, 4, 12, 8, 20, false, true);
            copy(skin, s, out, 4, 52, 4, 12, 8, 20, false, false);
        }
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
                             int dx, int dy, boolean mirror, boolean base) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int px = (sx + (mirror ? w - 1 - x : x)) * s, py = (sy + y) * s;
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

    private static Component render(BufferedImage skin, boolean slim) {
        int[][] px = frontView(skin, slim);
        TextComponent.Builder root = Component.text().font(FONT).shadowColor(ShadowColor.none());
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                int argb = px[y][x];
                if (argb == 0) continue;
                root.append(Component.text(new String(new char[]{(char) (FIRST_ROW + y), BACK}))
                        .color(TextColor.color(argb & 0xFFFFFF)));
            }
            root.append(Component.text(String.valueOf(STEP)));
        }
        return root.build();
    }
}
