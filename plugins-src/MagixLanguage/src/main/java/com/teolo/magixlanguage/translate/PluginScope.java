package com.teolo.magixlanguage.translate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Which plugins MagixLanguage translates on the Velocity proxy: every Magix plugin installed and
 * loaded there (its id, which is also its data folder), plus the ones listed in
 * {@code translations.plugins} - the same rule as TranslationSync on the game servers.
 */
public final class PluginScope {

    private PluginScope() {
    }

    /**
     * @param installed    the data-folder names of the plugins loaded on this server
     * @param listed       translations.plugins
     * @param autoDiscover true to add the installed Magix plugins
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
