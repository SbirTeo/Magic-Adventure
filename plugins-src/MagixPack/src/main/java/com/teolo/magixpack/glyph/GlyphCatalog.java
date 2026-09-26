package com.teolo.magixpack.glyph;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

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

    private final JavaPlugin plugin;
    private final Map<String, GlyphEntry> entries = new LinkedHashMap<>();

    public GlyphCatalog(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        entries.clear();
        File file = new File(plugin.getDataFolder(), "glyphs.yml");
        if (!file.exists()) plugin.saveResource("glyphs.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        // Ordine alfabetico degli id con texture valida: e' da questo elenco che nasce il punto
        // di codice di ognuna, non dall'ordine in cui compaiono nel file.
        TreeSet<String> ready = new TreeSet<>();
        for (String id : cfg.getKeys(false)) {
            if (textureFile(id).isFile()) ready.add(id);
            else plugin.getLogger().warning("[Glyphs] '" + id + "' in glyphs.yml: manca la texture glyphs/"
                    + id + ".png, icona ignorata.");
        }

        int codepoint = FIRST_CODEPOINT;
        for (String id : ready) {
            ConfigurationSection sec = cfg.getConfigurationSection(id);
            int height = sec != null ? sec.getInt("height", 8) : 8;
            int ascent = sec != null ? sec.getInt("ascent", 7) : 7;
            entries.put(id, new GlyphEntry(id, height, ascent, codepoint));
            codepoint++;
        }
        if (!entries.isEmpty()) {
            plugin.getLogger().info("[Glyphs] " + entries.size() + " icona/e custom caricate da glyphs.yml.");
        }
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
        GlyphEntry e = entries.get(id);
        if (e == null) return null;
        return Component.text(new String(Character.toChars(e.codepoint()))).font(FONT);
    }

    /** Contenuto da registrare nel pacchetto: un font custom (mai minecraft:default, vedi Javadoc
     *  della classe) con un provider bitmap per icona, piu' le texture lette da glyphs/<id>.png. */
    public Map<String, byte[]> packFiles() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (entries.isEmpty()) return out;

        StringBuilder providers = new StringBuilder();
        boolean first = true;
        for (GlyphEntry e : entries.values()) {
            try {
                byte[] texture = Files.readAllBytes(textureFile(e.id()).toPath());
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
                    .append("\",\"height\":").append(e.height())
                    .append(",\"ascent\":").append(e.ascent())
                    .append(",\"chars\":[\"").append(glyph).append("\"]}");
        }
        String font = "{\"providers\":[" + providers + "]}";
        out.put("assets/" + NAMESPACE + "/font/" + FONT_NAME + ".json", font.getBytes(StandardCharsets.UTF_8));
        return out;
    }
}
