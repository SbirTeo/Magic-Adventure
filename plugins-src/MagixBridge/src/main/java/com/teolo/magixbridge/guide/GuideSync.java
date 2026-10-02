package com.teolo.magixbridge.guide;

import com.teolo.magixbridge.MagixBridge;
import com.teolo.magixbridge.db.Database;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Gathers the chapters of the administrators' guide and carries them over to the site.
 *
 * Every one of our plugins writes its own chapter into {@code plugins/<Name>/guida-staff.html}
 * (the shared {@code util/StaffGuide} class does that); here we read them and pour them into the
 * {@code guide_staff} table, which the admin panel shows under /manage?section=guida.
 *
 * Why go through a FILE instead of a Bukkit service: this way the plugin startup order does not
 * matter, no plugin needs to know about any other, and if something never reaches the site the
 * chapter is still sitting there on disk to look at. The site's database credentials stay where
 * they have always been — in here and nowhere else.
 */
public class GuideSync {

    /** The file each plugin leaves in its own folder. */
    private static final String FILE_NAME = "guida-staff.html";

    private final MagixBridge plugin;
    private final Database database;

    public GuideSync(MagixBridge plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    /** Reads the chapters off disk and writes them to the site. All off the main thread. */
    public void sync() {
        // Main thread: which plugins are really running. A folder left behind by a plugin that is
        // gone (MagixWeb, renamed MagixBridge) still has its old chapter on disk.
        java.util.Set<String> loaded = new java.util.HashSet<>();
        for (org.bukkit.plugin.Plugin p : Bukkit.getPluginManager().getPlugins()) {
            loaded.add(p.getName());
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> stale = new ArrayList<>();
            List<Chapter> chapters = collect(loaded, stale);
            removeStale(stale);
            if (chapters.isEmpty()) {
                return;
            }
            int written = save(chapters);
            if (written > 0) {
                plugin.getLogger().info("MagixBridge: guida per amministratori aggiornata (" + written + " capitoli).");
            }
        });
    }

    /** Every guida-staff.html sitting in a plugin folder. */
    private List<Chapter> collect(java.util.Set<String> loaded, List<String> stale) {
        List<Chapter> out = new ArrayList<>();
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        File[] folders = pluginsFolder == null ? null : pluginsFolder.listFiles(File::isDirectory);
        if (folders == null) {
            return out;
        }
        for (File folder : folders) {
            File f = new File(folder, FILE_NAME);
            if (!f.isFile()) {
                continue;
            }
            if (!loaded.contains(folder.getName())) {
                stale.add(folder.getName());
                continue;
            }
            try {
                Chapter c = parse(folder.getName(), Files.readString(f.toPath(), StandardCharsets.UTF_8));
                if (c != null) {
                    out.add(c);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("MagixBridge: capitolo illeggibile in " + folder.getName() + ": " + e.getMessage());
            }
        }
        return out;
    }

    /**
     * The file opens with a header inside an HTML comment:
     * <pre>
     * &lt;!--guida
     * title: MagixTime — ora e stagioni
     * version: 0.3.4
     * sort_order: 60
     * --&gt;
     * </pre>
     * Everything after it is the body of the chapter. A file without a header is skipped:
     * better no chapter at all than a nameless one.
     */
    private Chapter parse(String pluginName, String content) {
        int start = content.indexOf("<!--guida");
        int end = content.indexOf("-->", start + 1);
        if (start < 0 || end < 0) {
            return null;
        }
        String header = content.substring(start + "<!--guida".length(), end);
        String body = content.substring(end + 3).trim();

        String title = pluginName;
        String version = "";
        int sortOrder = 100;
        for (String line : header.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "title" -> title = value;
                case "version" -> version = value;
                case "sort_order" -> {
                    try {
                        sortOrder = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        // not a number: keep the default, which puts it at the end of the list
                    }
                }
                default -> { }
            }
        }
        return body.isEmpty() ? null : new Chapter(pluginName, title, version, sortOrder, body);
    }

    /** The chapters of plugins no longer running come off the site: the guide would describe a ghost. */
    private void removeStale(List<String> stale) {
        if (stale.isEmpty()) {
            return;
        }
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM guide_staff WHERE plugin = ?")) {
            int removed = 0;
            for (String name : stale) {
                ps.setString(1, name);
                removed += ps.executeUpdate();
            }
            if (removed > 0) {
                plugin.getLogger().info("MagixBridge: tolti dalla guida i capitoli di plugin non più presenti: " + stale + ".");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixBridge: capitoli vecchi della guida non tolti: " + e.getMessage());
        }
    }

    /** Writes (or rewrites) the chapters on the site. One row per plugin: only the latest truth counts. */
    private int save(List<Chapter> chapters) {
        String sql = "INSERT INTO guide_staff (plugin, title, version, sort_order, body_html, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, NOW()) "
                + "ON DUPLICATE KEY UPDATE title = VALUES(title), version = VALUES(version), "
                + "sort_order = VALUES(sort_order), body_html = VALUES(body_html), updated_at = NOW()";

        int done = 0;
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (Chapter chapter : chapters) {
                ps.setString(1, chapter.plugin());
                ps.setString(2, chapter.title());
                ps.setString(3, chapter.version());
                ps.setInt(4, chapter.sortOrder());
                ps.setString(5, chapter.body());
                ps.addBatch();
                done++;
            }
            ps.executeBatch();
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixBridge: guida per amministratori non salvata: " + e.getMessage());
            return 0;
        }
        return done;
    }

    private record Chapter(String plugin, String title, String version, int sortOrder, String body) { }
}
