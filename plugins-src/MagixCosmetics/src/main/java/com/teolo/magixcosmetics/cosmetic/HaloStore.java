package com.teolo.magixcosmetics.cosmetic;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistenza delle scelte personali dell'aureola (colore, spenta con /halo off) su
 * players.yml. Senza questo restano solo in memoria: a ogni riavvio il colore scelto
 * tornerebbe al primo permesso e /halo off tornerebbe accesa per tutti.
 */
final class HaloStore {

    private final JavaPlugin plugin;
    private final File file;

    HaloStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
    }

    /** Rilegge le scelte salvate, sovrascrivendo le mappe passate. */
    void load(Map<UUID, String> chosenColor, Set<UUID> disabled, Map<UUID, String> entitledColor) {
        chosenColor.clear();
        disabled.clear();
        entitledColor.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("halo");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            String color = s.getString("color");
            if (color != null) chosenColor.put(id, color);
            if (s.getBoolean("disabled", false)) disabled.add(id);
            String entitled = s.getString("entitled");
            if (entitled != null) entitledColor.put(id, entitled);
        }
    }

    /** Scrive lo stato attuale delle mappe su file. */
    void save(Map<UUID, String> chosenColor, Set<UUID> disabled, Map<UUID, String> entitledColor) {
        YamlConfiguration cfg = new YamlConfiguration();
        Set<UUID> all = new HashSet<>(chosenColor.keySet());
        all.addAll(disabled);
        all.addAll(entitledColor.keySet());
        for (UUID id : all) {
            String base = "halo." + id + ".";
            String color = chosenColor.get(id);
            if (color != null) cfg.set(base + "color", color);
            if (disabled.contains(id)) cfg.set(base + "disabled", true);
            String entitled = entitledColor.get(id);
            if (entitled != null) cfg.set(base + "entitled", entitled);
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().severe("Impossibile salvare players.yml: " + e.getMessage());
        }
    }
}
