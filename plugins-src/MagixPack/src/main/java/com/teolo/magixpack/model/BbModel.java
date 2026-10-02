package com.teolo.magixpack.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix3d;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Blockbench project ({@code .bbmodel}) read for display in game: textures, bones (groups), the
 * pieces to draw and the animations.
 *
 * <p>Every element becomes ONE piece, drawn by its own item display as a single textured box:
 * <ul>
 *   <li>a cube is taken as it is (any rotation: the display entity rotates freely, unlike the
 *       elements of an item model);</li>
 *   <li>a mesh (which Minecraft cannot draw) becomes the box that fits it best, oriented along its
 *       main axes; each side of the box takes the texture of the mesh face that looks the same way.
 *       Round shapes come out as blocks, but in their place, with their size and their colours.</li>
 * </ul>
 * Coordinates are Blockbench pixels (16 = one block), rotations follow Blockbench (Euler order
 * Z, Y, X; animated X and Y rotations count the other way round, as Blockbench draws them).
 */
public final class BbModel {

    /** One thing to draw, with ONE item display: unit cube [-0.5, 0.5] scaled by {@link #size},
     *  turned by {@link #rotation}, moved to {@link #center}; {@code faces} holds the face JSON of
     *  its item model. When {@code parts} is not null this is several boxes of the same bone merged
     *  into one item model (see {@link #MERGE_SPAN}): the unit cube then stands for the model space. */
    public record Piece(String name, String bone, Vector3d center, Matrix3d rotation, Vector3d size,
                        Map<String, JsonObject> faces, List<Piece> parts, double modelScale) {
        Piece(String name, String bone, Vector3d center, Matrix3d rotation, Vector3d size,
              Map<String, JsonObject> faces) {
            this(name, bone, center, rotation, size, faces, null, 1);
        }
    }

    /** Item model elements must stay in -16..32: a merged piece is shrunk to fit this span (around
     *  8, the centre of the model) and the display scales it back up. */
    static final double MERGE_SPAN = 46;

    /** Content hash of the file: placed models made from another version are rebuilt. */
    public String hash = "";

    public record Bone(String uuid, String name, String parent, Vector3d pivot, Vector3d rotation) {}

    public record Keyframe(double time, double x, double y, double z, boolean step) {}

    public record Animation(String name, boolean loop, double length,
                            Map<String, List<Keyframe>> rotation, Map<String, List<Keyframe>> position) {}

    public record Texture(byte[] png, double uvWidth, double uvHeight) {}

    private static final String[] FACE_NAMES = {"north", "south", "east", "west", "up", "down"};
    private static final double[][] FACE_DIRS = {{0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};

    public final List<Texture> textures = new ArrayList<>();
    public final Map<String, Bone> bones = new LinkedHashMap<>();
    public final List<Piece> pieces = new ArrayList<>();
    public final Map<String, Animation> animations = new LinkedHashMap<>();
    /** Mesh elements turned into boxes (for the log). */
    public int convertedMeshes;

    public static BbModel parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        BbModel m = new BbModel();
        double resW = 16, resH = 16;
        if (root.has("resolution")) {
            resW = root.getAsJsonObject("resolution").get("width").getAsDouble();
            resH = root.getAsJsonObject("resolution").get("height").getAsDouble();
        }
        for (JsonElement t : arr(root, "textures")) {
            JsonObject o = t.getAsJsonObject();
            String src = str(o, "source", "");
            int comma = src.indexOf(',');
            byte[] png = comma >= 0 ? Base64.getDecoder().decode(src.substring(comma + 1)) : new byte[0];
            double w = o.has("uv_width") ? o.get("uv_width").getAsDouble() : resW;
            double h = o.has("uv_height") ? o.get("uv_height").getAsDouble() : resH;
            m.textures.add(new Texture(png, w, h));
        }

        Map<String, JsonObject> groups = new HashMap<>();
        for (JsonElement g : arr(root, "groups")) groups.put(str(g.getAsJsonObject(), "uuid", ""), g.getAsJsonObject());
        Map<String, String> elementBone = new HashMap<>();
        walk(arr(root, "outliner"), null, groups, m, elementBone);

        for (JsonElement el : arr(root, "elements")) {
            JsonObject e = el.getAsJsonObject();
            if (e.has("export") && !e.get("export").getAsBoolean()) continue;
            String type = str(e, "type", "cube");
            String bone = elementBone.get(str(e, "uuid", ""));
            Piece p = switch (type) {
                case "cube" -> cube(e, bone);
                case "mesh" -> mesh(e, bone);
                default -> null;
            };
            if (p == null) continue;
            if ("mesh".equals(type)) m.convertedMeshes++;
            m.pieces.add(p);
        }

        for (JsonElement a : arr(root, "animations")) {
            JsonObject o = a.getAsJsonObject();
            Map<String, List<Keyframe>> rot = new HashMap<>(), pos = new HashMap<>();
            if (o.has("animators")) {
                for (Map.Entry<String, JsonElement> an : o.getAsJsonObject("animators").entrySet()) {
                    for (JsonElement k : arr(an.getValue().getAsJsonObject(), "keyframes")) {
                        JsonObject kf = k.getAsJsonObject();
                        String channel = str(kf, "channel", "");
                        if (!channel.equals("rotation") && !channel.equals("position")) continue;
                        JsonObject dp = kf.getAsJsonArray("data_points").get(0).getAsJsonObject();
                        Keyframe f = new Keyframe(kf.get("time").getAsDouble(), num(dp, "x"), num(dp, "y"),
                                num(dp, "z"), "step".equals(str(kf, "interpolation", "linear")));
                        (channel.equals("rotation") ? rot : pos).computeIfAbsent(an.getKey(), x -> new ArrayList<>()).add(f);
                    }
                }
            }
            rot.values().forEach(l -> l.sort((x, y) -> Double.compare(x.time(), y.time())));
            pos.values().forEach(l -> l.sort((x, y) -> Double.compare(x.time(), y.time())));
            String name = str(o, "name", "animation");
            m.animations.put(name, new Animation(name, "loop".equals(str(o, "loop", "once")),
                    o.has("length") ? o.get("length").getAsDouble() : 0, rot, pos));
        }
        m.mergeStraightPieces();
        return m;
    }

    /**
     * Every box that is NOT turned goes, together with the others of its bone, into ONE item model
     * with many elements: one item display instead of one per box (a logo of hundreds of cubes is
     * one entity). Turned boxes keep a display each (an item model element cannot turn freely).
     */
    private void mergeStraightPieces() {
        Map<String, List<Piece>> byBone = new LinkedHashMap<>();
        List<Piece> keep = new ArrayList<>();
        for (Piece p : pieces) {
            if (p.rotation().equals(new Matrix3d(), 1e-6)) byBone.computeIfAbsent(String.valueOf(p.bone()), k -> new ArrayList<>()).add(p);
            else keep.add(p);
        }
        List<Piece> out = new ArrayList<>();
        for (List<Piece> group : byBone.values()) {
            if (group.size() == 1) {
                out.add(group.get(0));
                continue;
            }
            Vector3d lo = new Vector3d(Double.MAX_VALUE), hi = new Vector3d(-Double.MAX_VALUE);
            for (Piece p : group) {
                Vector3d half = new Vector3d(p.size()).mul(0.5);
                lo.min(new Vector3d(p.center()).sub(half));
                hi.max(new Vector3d(p.center()).add(half));
            }
            Vector3d center = new Vector3d(lo).add(hi).mul(0.5);
            Vector3d ext = new Vector3d(hi).sub(lo);
            double span = Math.max(ext.x, Math.max(ext.y, ext.z));
            double f = Math.min(1.0, MERGE_SPAN / Math.max(span, 1e-6));
            double s = 16.0 / f;
            out.add(new Piece("merged", group.get(0).bone(), center, new Matrix3d(), new Vector3d(s, s, s),
                    Map.of(), group, f));
        }
        out.addAll(keep);
        pieces.clear();
        pieces.addAll(out);
    }

    private static void walk(JsonArray nodes, String parent, Map<String, JsonObject> groups, BbModel m,
                             Map<String, String> elementBone) {
        for (JsonElement n : nodes) {
            if (n.isJsonPrimitive()) {
                if (parent != null) elementBone.put(n.getAsString(), parent);
                continue;
            }
            JsonObject node = n.getAsJsonObject();
            String uuid = str(node, "uuid", "");
            // format 5 keeps the group data in "groups", older files inline it in the outliner
            JsonObject g = groups.getOrDefault(uuid, node);
            m.bones.put(uuid, new Bone(uuid, str(g, "name", "bone"), parent, vec(g, "origin"), vec(g, "rotation")));
            walk(arr(node, "children"), uuid, groups, m, elementBone);
        }
    }

    private static Piece cube(JsonObject e, String bone) {
        Vector3d from = vec(e, "from"), to = vec(e, "to"), origin = vec(e, "origin");
        double inflate = e.has("inflate") ? e.get("inflate").getAsDouble() : 0;
        Vector3d size = new Vector3d(to).sub(from).absolute().add(2 * inflate, 2 * inflate, 2 * inflate);
        Matrix3d r = euler(vec(e, "rotation"));
        Vector3d mid = new Vector3d(from).add(to).mul(0.5);
        Vector3d center = new Vector3d(mid).sub(origin);
        r.transform(center).add(origin);
        Map<String, JsonObject> faces = new LinkedHashMap<>();
        JsonObject f = e.has("faces") ? e.getAsJsonObject("faces") : new JsonObject();
        for (String name : FACE_NAMES) {
            if (!f.has(name)) continue;
            JsonObject face = f.getAsJsonObject(name);
            if (!face.has("texture") || face.get("texture").isJsonNull()) continue;
            JsonObject out = new JsonObject();
            out.add("uv", face.get("uv"));
            out.addProperty("texture", face.get("texture").getAsInt());
            if (face.has("rotation") && face.get("rotation").getAsInt() != 0) out.add("rotation", face.get("rotation"));
            faces.put(name, out);
        }
        return new Piece(str(e, "name", "cube"), bone, center, r, size, faces);
    }

    private static Piece mesh(JsonObject e, String bone) {
        if (!e.has("vertices")) return null;
        Vector3d origin = vec(e, "origin");
        Matrix3d meshRot = euler(vec(e, "rotation"));
        Map<String, Vector3d> v = new HashMap<>();
        for (Map.Entry<String, JsonElement> en : e.getAsJsonObject("vertices").entrySet()) {
            JsonArray a = en.getValue().getAsJsonArray();
            Vector3d p = new Vector3d(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
            meshRot.transform(p).add(origin);
            v.put(en.getKey(), p);
        }
        if (v.size() < 4) return null;
        Vector3d c = new Vector3d();
        v.values().forEach(c::add);
        c.div(v.size());

        // main axes (principal components) of the vertex cloud
        double[][] cov = new double[3][3];
        for (Vector3d p : v.values()) {
            double[] d = {p.x - c.x, p.y - c.y, p.z - c.z};
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) cov[i][j] += d[i] * d[j];
        }
        double[] eig = new double[3];
        double[][] vec = jacobi(cov, eig);
        Matrix3d r = new Matrix3d(vec[0][0], vec[1][0], vec[2][0], vec[0][1], vec[1][1], vec[2][1],
                vec[0][2], vec[1][2], vec[2][2]); // columns = eigenvectors
        double emax = Math.max(eig[0], Math.max(eig[1], eig[2])), emin = Math.min(eig[0], Math.min(eig[1], eig[2]));
        // a round blob has no main axis: keep it straight instead of turning it at random
        if (emax <= 0 || emin / emax > 0.8) r.identity();
        r = smallestTurn(r);

        Vector3d lo = new Vector3d(Double.MAX_VALUE), hi = new Vector3d(-Double.MAX_VALUE);
        Matrix3d inv = new Matrix3d(r).transpose();
        for (Vector3d p : v.values()) {
            Vector3d q = inv.transform(new Vector3d(p).sub(c));
            lo.min(q);
            hi.max(q);
        }
        Vector3d size = new Vector3d(hi).sub(lo);
        Vector3d center = r.transform(new Vector3d(lo).add(hi).mul(0.5)).add(c);
        // a round shape (sphere, dome, many vertices) fills its box too much: a box about 0.82 of
        // its size has the same volume as the ellipsoid inside it
        if (v.size() >= 32) size.mul(0.82);
        size.max(new Vector3d(0.5));

        // outward normal, area and UV rectangle of every mesh face
        record MFace(Vector3d n, double area, double[] uv, int texture) {}
        List<MFace> mfaces = new ArrayList<>();
        JsonObject fs = e.has("faces") ? e.getAsJsonObject("faces") : new JsonObject();
        for (Map.Entry<String, JsonElement> en : fs.entrySet()) {
            JsonObject f = en.getValue().getAsJsonObject();
            if (!f.has("texture") || f.get("texture").isJsonNull()) continue;
            JsonArray ids = f.getAsJsonArray("vertices");
            if (ids == null || ids.size() < 3) continue;
            List<Vector3d> pts = new ArrayList<>();
            double u0 = Double.MAX_VALUE, v0 = Double.MAX_VALUE, u1 = -Double.MAX_VALUE, v1 = -Double.MAX_VALUE;
            JsonObject uvs = f.has("uv") ? f.getAsJsonObject("uv") : new JsonObject();
            for (JsonElement id : ids) {
                Vector3d p = v.get(id.getAsString());
                if (p == null) continue;
                pts.add(p);
                if (uvs.has(id.getAsString())) {
                    JsonArray uv = uvs.getAsJsonArray(id.getAsString());
                    u0 = Math.min(u0, uv.get(0).getAsDouble());
                    u1 = Math.max(u1, uv.get(0).getAsDouble());
                    v0 = Math.min(v0, uv.get(1).getAsDouble());
                    v1 = Math.max(v1, uv.get(1).getAsDouble());
                }
            }
            if (pts.size() < 3 || u0 == Double.MAX_VALUE) continue;
            Vector3d n = new Vector3d(), mid = new Vector3d();
            for (int i = 0; i < pts.size(); i++) { // Newell's normal
                Vector3d a = pts.get(i), b = pts.get((i + 1) % pts.size());
                n.add((a.y - b.y) * (a.z + b.z), (a.z - b.z) * (a.x + b.x), (a.x - b.x) * (a.y + b.y));
                mid.add(a);
            }
            double area = n.length() / 2;
            if (area < 1e-9) continue;
            n.normalize();
            mid.div(pts.size());
            if (n.dot(new Vector3d(mid).sub(c)) < 0) n.negate();
            if (u1 - u0 < 1) u1 = u0 + 1;
            if (v1 - v0 < 1) v1 = v0 + 1;
            mfaces.add(new MFace(n, area, new double[] {u0, v0, u1, v1}, f.get("texture").getAsInt()));
        }
        if (mfaces.isEmpty()) return null;

        Map<String, JsonObject> faces = new LinkedHashMap<>();
        for (int i = 0; i < FACE_NAMES.length; i++) {
            Vector3d dir = r.transform(new Vector3d(FACE_DIRS[i][0], FACE_DIRS[i][1], FACE_DIRS[i][2]));
            MFace best = null;
            double bestScore = -Double.MAX_VALUE;
            for (MFace f : mfaces) {
                double score = f.n().dot(dir) + 1e-3 * f.area();
                if (score > bestScore) {
                    bestScore = score;
                    best = f;
                }
            }
            JsonObject out = new JsonObject();
            JsonArray uv = new JsonArray();
            for (double d : best.uv()) uv.add(d);
            out.add("uv", uv);
            out.addProperty("texture", best.texture());
            faces.put(FACE_NAMES[i], out);
        }
        return new Piece(str(e, "name", "mesh"), bone, center, r, size, faces);
    }

    // ------------------------------------------------------------------------------- transforms

    /** Matrix of a piece at time {@code t} of an animation (null = rest pose), in BLOCKS. */
    public Matrix4f pieceMatrix(int index, Animation anim, double t, double scale) {
        Piece p = pieces.get(index);
        Matrix4f m = new Matrix4f().scale((float) (scale / 16.0));
        m.mul(boneMatrix(p.bone(), anim, t));
        m.translate((float) p.center().x, (float) p.center().y, (float) p.center().z);
        m.mul(rot4(p.rotation()));
        m.scale((float) p.size().x, (float) p.size().y, (float) p.size().z);
        return m;
    }

    private Matrix4f boneMatrix(String uuid, Animation anim, double t) {
        if (uuid == null) return new Matrix4f();
        Bone b = bones.get(uuid);
        if (b == null) return new Matrix4f();
        Matrix4f m = boneMatrix(b.parent(), anim, t);
        Vector3d rot = new Vector3d(b.rotation());
        if (anim != null) {
            double[] r = sample(anim.rotation().get(uuid), t);
            if (r != null) rot.add(-r[0], -r[1], r[2]);
            double[] pos = sample(anim.position().get(uuid), t);
            if (pos != null) m.translate((float) -pos[0], (float) pos[1], (float) pos[2]);
        }
        Vector3d p = b.pivot();
        m.translate((float) p.x, (float) p.y, (float) p.z);
        m.mul(rot4(euler(rot)));
        m.translate((float) -p.x, (float) -p.y, (float) -p.z);
        return m;
    }

    private static double[] sample(List<Keyframe> kfs, double t) {
        if (kfs == null || kfs.isEmpty()) return null;
        Keyframe prev = kfs.get(0);
        if (t <= prev.time()) return new double[] {prev.x(), prev.y(), prev.z()};
        for (int i = 1; i < kfs.size(); i++) {
            Keyframe next = kfs.get(i);
            if (t <= next.time()) {
                if (prev.step() || next.time() <= prev.time()) return new double[] {prev.x(), prev.y(), prev.z()};
                double k = (t - prev.time()) / (next.time() - prev.time());
                return new double[] {prev.x() + (next.x() - prev.x()) * k, prev.y() + (next.y() - prev.y()) * k,
                        prev.z() + (next.z() - prev.z()) * k};
            }
            prev = next;
        }
        return new double[] {prev.x(), prev.y(), prev.z()};
    }

    private static Matrix4f rot4(Matrix3d r) {
        return new Matrix4f().set3x3(new org.joml.Matrix3f((float) r.m00, (float) r.m01, (float) r.m02, (float) r.m10, (float) r.m11,
                (float) r.m12, (float) r.m20, (float) r.m21, (float) r.m22));
    }

    /** Blockbench rotation (degrees, applied Z then Y then X on the parent): Rz * Ry * Rx. */
    static Matrix3d euler(Vector3d deg) {
        return new Matrix3d().rotateZ(Math.toRadians(deg.z)).rotateY(Math.toRadians(deg.y))
                .rotateX(Math.toRadians(deg.x));
    }

    /** Among the 24 ways to name the same box axes, the one closest to no rotation at all. */
    private static Matrix3d smallestTurn(Matrix3d r) {
        if (r.determinant() < 0) r.scale(-1, 1, 1);
        Matrix3d best = new Matrix3d(r);
        double bestTrace = -Double.MAX_VALUE;
        int[][] perms = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        for (int[] perm : perms) {
            for (int s = 0; s < 8; s++) {
                Matrix3d flip = new Matrix3d().zero();
                for (int i = 0; i < 3; i++) flip.set(i, perm[i], ((s >> i) & 1) == 1 ? -1 : 1);
                if (flip.determinant() < 0) continue;
                Matrix3d cand = new Matrix3d(r).mul(flip);
                double trace = cand.m00 + cand.m11 + cand.m22;
                if (trace > bestTrace) {
                    bestTrace = trace;
                    best = cand;
                }
            }
        }
        return best;
    }

    /** Eigen-decomposition of a symmetric 3x3 matrix (Jacobi): eigenvalues in {@code eig}, the
     *  returned matrix holds the eigenvectors as COLUMNS. */
    private static double[][] jacobi(double[][] a0, double[] eig) {
        double[][] a = {a0[0].clone(), a0[1].clone(), a0[2].clone()};
        double[][] v = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int sweep = 0; sweep < 50; sweep++) {
            double off = Math.abs(a[0][1]) + Math.abs(a[0][2]) + Math.abs(a[1][2]);
            if (off < 1e-12) break;
            for (int p = 0; p < 2; p++) {
                for (int q = p + 1; q < 3; q++) {
                    if (Math.abs(a[p][q]) < 1e-15) continue;
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) t = 1;
                    double cs = 1 / Math.sqrt(t * t + 1), sn = t * cs;
                    for (int k = 0; k < 3; k++) {
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = cs * akp - sn * akq;
                        a[k][q] = sn * akp + cs * akq;
                    }
                    for (int k = 0; k < 3; k++) {
                        double apk = a[p][k], aqk = a[q][k];
                        a[p][k] = cs * apk - sn * aqk;
                        a[q][k] = sn * apk + cs * aqk;
                    }
                    for (int k = 0; k < 3; k++) {
                        double vkp = v[k][p], vkq = v[k][q];
                        v[k][p] = cs * vkp - sn * vkq;
                        v[k][q] = sn * vkp + cs * vkq;
                    }
                }
            }
        }
        for (int i = 0; i < 3; i++) eig[i] = a[i][i];
        return v;
    }

    // ---------------------------------------------------------------------------------- JSON bits

    private static JsonArray arr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    private static Vector3d vec(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return new Vector3d();
        JsonArray a = o.getAsJsonArray(key);
        return new Vector3d(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }

    /** A keyframe value: a number (often written as a string); anything else (Molang) counts 0. */
    private static double num(JsonObject o, String key) {
        if (!o.has(key)) return 0;
        try {
            return Double.parseDouble(o.get(key).getAsString().trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
