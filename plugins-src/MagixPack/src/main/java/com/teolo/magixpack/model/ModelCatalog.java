package com.teolo.magixpack.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The Blockbench models of {@code plugins/MagixPack/models/*.bbmodel}: no Animated Java, no export,
 * the project file as it comes out of Blockbench. The file name (lower case) is the model id.
 *
 * <p>For every model the pack gets its textures and ONE item model per piece (a single box, see
 * {@link BbModel}), under {@code magixpack:model/<id>/<n>}; {@link ModelDisplays} shows them in the
 * world with one item display per piece.
 */
public final class ModelCatalog {

    public static final String NAMESPACE = "magixpack";

    private final JavaPlugin plugin;
    private final File folder;
    private Map<String, BbModel> models = Map.of();

    public ModelCatalog(JavaPlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "models");
    }

    public void reload() {
        if (!folder.isDirectory() && !folder.mkdirs()) {
            plugin.getLogger().warning("[Models] Impossibile creare " + folder.getPath());
        }
        Map<String, BbModel> out = new TreeMap<>();
        File[] files = folder.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".bbmodel"));
        if (files != null) {
            for (File f : files) {
                String id = idOf(f.getName());
                try {
                    String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                    BbModel m = BbModel.parse(content);
                    m.hash = Integer.toHexString(content.hashCode()) + "-" + content.length();
                    if (m.pieces.isEmpty()) {
                        plugin.getLogger().warning("[Models] " + f.getName() + ": nessun pezzo da disegnare, saltato.");
                        continue;
                    }
                    out.put(id, m);
                    plugin.getLogger().info("[Models] " + id + ": " + m.pieces.size() + " pezzi"
                            + (m.convertedMeshes > 0 ? " (" + m.convertedMeshes + " mesh trasformate in blocchi)" : "")
                            + ", animazioni: " + (m.animations.isEmpty() ? "nessuna" : String.join(", ", m.animations.keySet())) + ".");
                } catch (IOException | RuntimeException e) {
                    plugin.getLogger().warning("[Models] " + f.getName() + " illeggibile: " + e);
                }
            }
        }
        models = out;
    }

    public BbModel get(String id) {
        return models.get(id);
    }

    public List<String> ids() {
        return Collections.unmodifiableList(new ArrayList<>(models.keySet()));
    }

    public static NamespacedKey itemModel(String id, int piece) {
        return new NamespacedKey(NAMESPACE, "model/" + id + "/" + piece);
    }

    /** Textures, piece models and item definitions of every model, for the pack. */
    public Map<String, byte[]> packFiles() {
        return packFiles(models);
    }

    /** The pack files of the given models (static, so a build tool can produce the same files).
     *  Textures go under {@code textures/item/}: the client only uses in a model the textures
     *  of its atlases, and {@code item/} is in the item atlas of every version (a folder of its
     *  own, like {@code model/}, is not, and came out black and purple). */
    public static Map<String, byte[]> packFiles(Map<String, BbModel> models) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, BbModel> en : models.entrySet()) {
            String id = en.getKey();
            BbModel m = en.getValue();
            for (int t = 0; t < m.textures.size(); t++) {
                out.put("assets/" + NAMESPACE + "/textures/item/model/" + id + "/" + t + ".png", m.textures.get(t).png());
            }
            for (int i = 0; i < m.pieces.size(); i++) {
                BbModel.Piece p = m.pieces.get(i);
                JsonObject textures = new JsonObject();
                JsonArray elements = new JsonArray();
                List<BbModel.Piece> parts = p.parts() != null ? p.parts() : List.of(p);
                for (BbModel.Piece part : parts) {
                JsonObject faces = new JsonObject();
                for (Map.Entry<String, JsonObject> f : part.faces().entrySet()) {
                    int tex = f.getValue().get("texture").getAsInt();
                    if (tex < 0 || tex >= m.textures.size()) continue;
                    BbModel.Texture texture = m.textures.get(tex);
                    textures.addProperty(String.valueOf(tex), NAMESPACE + ":item/model/" + id + "/" + tex);
                    if (!textures.has("particle")) textures.addProperty("particle", NAMESPACE + ":item/model/" + id + "/" + tex);
                    JsonArray src = f.getValue().getAsJsonArray("uv");
                    JsonArray uv = new JsonArray();
                    // item model UVs go 0-16 over the whole texture, Blockbench ones are in texture pixels
                    for (int k = 0; k < 4; k++) {
                        double size = k % 2 == 0 ? texture.uvWidth() : texture.uvHeight();
                        uv.add(Math.round(src.get(k).getAsDouble() * 16.0 / size * 10000.0) / 10000.0);
                    }
                    JsonObject face = new JsonObject();
                    face.add("uv", uv);
                    face.addProperty("texture", "#" + tex);
                    if (f.getValue().has("rotation")) face.add("rotation", f.getValue().get("rotation"));
                    faces.add(f.getKey(), face);
                }
                JsonObject element = new JsonObject();
                if (p.parts() == null) {
                    element.add("from", triple(0, 0, 0));
                    element.add("to", triple(16, 16, 16));
                } else {
                    // merged: the part's box in model space, shrunk by modelScale around the centre (8)
                    double f = p.modelScale();
                    double[] from = new double[3], to = new double[3];
                    for (int k = 0; k < 3; k++) {
                        double c = part.center().get(k) - p.center().get(k), h = part.size().get(k) / 2;
                        from[k] = round4(8 + (c - h) * f);
                        to[k] = round4(8 + (c + h) * f);
                    }
                    element.add("from", triple(from[0], from[1], from[2]));
                    element.add("to", triple(to[0], to[1], to[2]));
                }
                element.add("faces", faces);
                elements.add(element);
                }
                JsonObject model = new JsonObject();
                model.add("textures", textures);
                model.add("elements", elements);
                String path = "model/" + id + "/" + i;
                out.put("assets/" + NAMESPACE + "/models/" + path + ".json", model.toString().getBytes(StandardCharsets.UTF_8));
                String definition = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + NAMESPACE + ":" + path + "\"}}";
                out.put("assets/" + NAMESPACE + "/items/" + path + ".json", definition.getBytes(StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static JsonArray triple(double a, double b, double c) {
        JsonArray arr = new JsonArray();
        arr.add(a);
        arr.add(b);
        arr.add(c);
        return arr;
    }

    /** "MagicAdventure_IronScorpion_PRO.bbmodel" -> "magicadventure_ironscorpion_pro". */
    static String idOf(String fileName) {
        String base = fileName.substring(0, fileName.length() - ".bbmodel".length());
        return base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }
}
