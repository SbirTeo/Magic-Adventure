package com.teolo.magixscoreboard.board;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Chi ha nascosto la scoreboard con /mscoreboard toggle: una manciata di UUID, salvata in un file
 * a parte (non e' un config del plugin: e' una scelta del giocatore, e ConfigAlign non deve
 * toccarla). Caricato una volta all'avvio, riscritto (in modo asincrono) solo quando qualcuno
 * cambia la propria scelta.
 */
final class HiddenPlayers {

    private final JavaPlugin plugin;
    private final File file;
    private final Set<UUID> hidden = new HashSet<>();

    HiddenPlayers(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "hidden.yml");
        load();
    }

    boolean isHidden(UUID id) { return hidden.contains(id); }

    /** Cambia la scelta del giocatore e la salva. @return il nuovo stato (true = nascosta). */
    boolean toggle(UUID id) {
        boolean nowHidden;
        if (hidden.contains(id)) {
            hidden.remove(id);
            nowHidden = false;
        } else {
            hidden.add(id);
            nowHidden = true;
        }
        save();
        return nowHidden;
    }

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        for (String raw : cfg.getStringList("hidden")) {
            try {
                hidden.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void save() {
        Set<UUID> snapshot = Set.copyOf(hidden);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            YamlConfiguration cfg = new YamlConfiguration();
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (UUID id : snapshot) ids.add(id.toString());
            cfg.set("hidden", ids);
            try {
                plugin.getDataFolder().mkdirs();
                cfg.save(file);
            } catch (Exception e) {
                plugin.getLogger().warning("Non sono riuscito a salvare hidden.yml: " + e.getMessage());
            }
        });
    }
}
