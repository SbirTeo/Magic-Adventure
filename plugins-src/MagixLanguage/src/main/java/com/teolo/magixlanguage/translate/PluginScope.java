package com.teolo.magixlanguage.translate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Which plugins MagixLanguage translates on the server where it runs: the ones listed in
 * {@code translations.plugins}, plus (with {@code translations.auto-discover}) every Magix plugin
 * installed and loaded there, so a new Magix plugin is translated without touching the config.
 * Folders left behind by a plugin that was removed or renamed do not count: they would only use
 * translation quota. Shared by Paper and Velocity (no platform types here).
 */
public final class PluginScope {

    private PluginScope() {
    }

    /**
     * @param installed    the data-folder names of the plugins loaded on this server
     * @param listed       translations.plugins
     * @param autoDiscover translations.auto-discover
     * @param self         MagixLanguage's own data-folder name (never translated as a source)
     */
    public static List<String> toTranslate(Collection<String> installed, List<String> listed, boolean autoDiscover,
                                           String self) {
        List<String> out = new ArrayList<>(listed);
        if (autoDiscover) {
            installed.stream().sorted(String.CASE_INSENSITIVE_ORDER).forEach(name -> {
                if (name.regionMatches(true, 0, "Magix", 0, 5) && !name.equalsIgnoreCase(self)
                        && out.stream().noneMatch(name::equalsIgnoreCase)) {
                    out.add(name);
                }
            });
        }
        return out;
    }
}
