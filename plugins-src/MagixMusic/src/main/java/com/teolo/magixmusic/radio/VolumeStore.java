package com.teolo.magixmusic.radio;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Volume personale della radio per ogni giocatore (0-100, 0 = spenta), salvato in {@code volumes.yml}
 * nella cartella dati del plugin. Chi non compare nel file usa il volume di default del config: qui si
 * memorizza solo la scelta ESPLICITA di un giocatore (comando /radio), non lo stato di tutti.
 *
 * <p>Essendo MagixMusic un plugin a sé (non ha un database), questa è la sua persistenza: un file
 * piccolo, una riga per giocatore, letto all'avvio e riscritto quando qualcuno cambia il suo volume.
 */
public final class VolumeStore {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Integer> volumes = new HashMap<>();

    public VolumeStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "volumes.yml");
        load();
    }

    private void load() {
        volumes.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String key : y.getKeys(false)) {
            try { volumes.put(UUID.fromString(key), clamp(y.getInt(key))); }
            catch (IllegalArgumentException ignored) {} // riga con una chiave che non e' un UUID: si salta
        }
    }

    /** Il volume scelto dal giocatore (0-100), oppure null se non l'ha mai regolato (usa il default). */
    public Integer get(UUID u) { return volumes.get(u); }

    /** Salva la scelta del giocatore (0-100) e riscrive il file. */
    public void set(UUID u, int volume) {
        volumes.put(u, clamp(volume));
        save();
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> e : volumes.entrySet()) y.set(e.getKey().toString(), e.getValue());
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("[Radio] salvataggio volumi fallito: " + e.getMessage());
        }
    }

    private static int clamp(int v) { return Math.max(0, Math.min(100, v)); }
}
