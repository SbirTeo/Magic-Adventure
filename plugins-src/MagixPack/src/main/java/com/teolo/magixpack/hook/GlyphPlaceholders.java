package com.teolo.magixpack.hook;

import com.teolo.magixpack.glyph.GlyphCatalog;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@code %magixpack_glyph_<id>%}: the text of a glyph of glyphs.yml, for every plugin that takes
 * PlaceholderAPI placeholders (chat, tablist, scoreboard, menus, CMI...). The character of a glyph
 * can change when the catalog changes; the placeholder never does.
 *
 * <ul>
 *   <li>an icon: its character, white (a bitmap glyph takes the color of the text before it);</li>
 *   <li>a {@code player-avatar} voice: the avatar of the player the placeholder is resolved for,
 *       or of another one with {@code %magixpack_glyph_<id>:<player>%}. Empty while the skin is
 *       being downloaded (it is done at every join, so usually it is already there).</li>
 * </ul>
 */
public final class GlyphPlaceholders extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final GlyphCatalog glyphs;

    public GlyphPlaceholders(JavaPlugin plugin, GlyphCatalog glyphs) {
        this.plugin = plugin;
        this.glyphs = glyphs;
    }

    @Override
    public String getIdentifier() {
        return "magixpack";
    }

    @Override
    public String getAuthor() {
        return "teolo";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer who, String params) {
        if (!params.startsWith("glyph_")) return null;
        String rest = params.substring("glyph_".length());
        Player player = who != null ? who.getPlayer() : null;
        int colon = rest.indexOf(':');
        if (colon >= 0) {
            player = Bukkit.getPlayerExact(rest.substring(colon + 1));
            rest = rest.substring(0, colon);
        }
        if (glyphs.entry(rest) == null) return null;
        String text = glyphs.legacy(rest, player);
        return text != null ? text : "";
    }
}
