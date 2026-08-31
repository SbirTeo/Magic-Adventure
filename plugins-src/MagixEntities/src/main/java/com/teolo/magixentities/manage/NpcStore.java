package com.teolo.magixentities.manage;

import com.teolo.magixentities.model.NpcDef;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Persistenza delle entita' su entities.yml. */
public final class NpcStore {

    private final JavaPlugin plugin;
    private final File file;

    public NpcStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "entities.yml");
    }

    public Map<String, NpcDef> load() {
        Map<String, NpcDef> out = new LinkedHashMap<>();
        if (!file.exists()) return out;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("entities");
        if (root == null) return out;

        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            String name = s.getString("name", key);
            EntityType type;
            try {
                type = EntityType.valueOf(s.getString("type", "MANNEQUIN").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Entita' '" + key + "': tipo sconosciuto, la salto.");
                continue;
            }
            NpcDef d = new NpcDef(name, type);
            d.skin = s.getString("skin");
            d.display = s.getString("display"); // null = il displayname segue il nome
            d.world = s.getString("world", "world");
            d.x = s.getDouble("x");
            d.y = s.getDouble("y");
            d.z = s.getDouble("z");
            d.yaw = (float) s.getDouble("yaw");
            d.pitch = (float) s.getDouble("pitch");
            d.pose = s.getString("pose");
            String u = s.getString("uuid");
            if (u != null && !u.isBlank()) {
                try { d.uuid = UUID.fromString(u); } catch (IllegalArgumentException ignored) {}
            }
            d.commands.addAll(s.getStringList("commands"));
            ConfigurationSection eq = s.getConfigurationSection("equipment");
            if (eq != null) {
                for (String slot : NpcDef.EQUIPMENT) {
                    ItemStack item = eq.getItemStack(slot);
                    if (item != null) d.equipment.put(slot, item);
                }
            }
            ConfigurationSection opts = s.getConfigurationSection("options");
            if (opts != null) {
                for (String o : opts.getKeys(false)) d.options.put(o, opts.getBoolean(o));
            }
            out.put(d.id, d);
        }
        return out;
    }

    public void save(Map<String, NpcDef> npcs) {
        YamlConfiguration cfg = new YamlConfiguration();
        for (NpcDef d : npcs.values()) {
            String base = "entities." + d.id + ".";
            cfg.set(base + "name", d.name);
            cfg.set(base + "type", d.type.name());
            cfg.set(base + "skin", d.skin);
            cfg.set(base + "display", d.display);
            cfg.set(base + "world", d.world);
            cfg.set(base + "x", d.x);
            cfg.set(base + "y", d.y);
            cfg.set(base + "z", d.z);
            cfg.set(base + "yaw", d.yaw);
            cfg.set(base + "pitch", d.pitch);
            cfg.set(base + "pose", d.pose);
            cfg.set(base + "uuid", d.uuid == null ? null : d.uuid.toString());
            cfg.set(base + "commands", d.commands.isEmpty() ? null : d.commands);
            for (String slot : NpcDef.EQUIPMENT) {
                cfg.set(base + "equipment." + slot, d.equipment.get(slot));
            }
            for (Map.Entry<String, Boolean> e : d.options.entrySet()) {
                cfg.set(base + "options." + e.getKey(), e.getValue());
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().severe("Impossibile salvare entities.yml: " + e.getMessage());
        }
    }
}
