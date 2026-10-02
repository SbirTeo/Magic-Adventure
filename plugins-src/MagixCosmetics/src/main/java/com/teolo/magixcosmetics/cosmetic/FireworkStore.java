package com.teolo.magixcosmetics.cosmetic;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistenza delle scelte personali del firework (players.yml, sezione "firework"). */
final class FireworkStore {

    private final JavaPlugin plugin;
    private final File file;

    FireworkStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
    }

    void load(Map<UUID, FireworkLook> looks, Set<UUID> off) {
        looks.clear();
        off.clear();
        if (!file.exists()) return;
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("firework");
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
            FireworkLook look = new FireworkLook();
            if (s.isList("colors")) look.colors = s.getStringList("colors");
            if (s.isList("fade")) look.fade = s.getStringList("fade");
            look.shape = s.getString("shape");
            if (s.isBoolean("flicker")) look.flicker = s.getBoolean("flicker");
            if (s.isBoolean("trail")) look.trail = s.getBoolean("trail");
            if (!look.isEmpty()) looks.put(id, look);
            if (s.getBoolean("off", false)) off.add(id);
        }
    }

    /** Riscrive la sezione "firework" lasciando intatto il resto del file (l'aureola). */
    void save(Map<UUID, FireworkLook> looks, Set<UUID> off) {
        YamlConfiguration cfg = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        cfg.set("firework", null);
        Set<UUID> all = new HashSet<>(looks.keySet());
        all.addAll(off);
        for (UUID id : all) {
            String base = "firework." + id + ".";
            FireworkLook l = looks.get(id);
            if (l != null) {
                if (l.colors != null) cfg.set(base + "colors", l.colors);
                if (l.fade != null) cfg.set(base + "fade", l.fade);
                if (l.shape != null) cfg.set(base + "shape", l.shape);
                if (l.flicker != null) cfg.set(base + "flicker", l.flicker);
                if (l.trail != null) cfg.set(base + "trail", l.trail);
            }
            if (off.contains(id)) cfg.set(base + "off", true);
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().severe("Impossibile salvare players.yml: " + e.getMessage());
        }
    }
}
