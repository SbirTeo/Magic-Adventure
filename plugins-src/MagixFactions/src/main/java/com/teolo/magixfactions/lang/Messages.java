package com.teolo.magixfactions.lang;

import com.teolo.magixfactions.util.Colors;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Carica e fornisce i messaggi da messages.yml (modificabile dall'utente). */
public final class Messages {

    private final JavaPlugin plugin;
    private FileConfiguration cfg;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        if (!f.exists()) plugin.saveResource("messages.yml", false);
        cfg = YamlConfiguration.loadConfiguration(f);
        // Fallback: le chiavi non presenti nel file dell'utente vengono prese dal
        // messages.yml incluso nel jar, cosi' i nuovi messaggi funzionano senza
        // dover aggiornare a mano il file gia' generato sul server.
        try (java.io.InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                YamlConfiguration def = YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                cfg.setDefaults(def);
                cfg.options().copyDefaults(true);
            }
        } catch (Exception ignored) {}
    }

    /** Messaggio colorato con sostituzione placeholder {chiave}. */
    public String get(String path, String... kv) {
        String s = cfg.getString(path);
        if (s == null) return ChatColor.RED + "missing message: " + path;
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        // Colors.translate e non solo translateAlternateColorCodes: i messaggi usano anche gli
        // esadecimali &#RRGGBB (il viola e il verde del logo), che la traduzione classica si
        // lascerebbe dietro stampandoli a schermo per esteso.
        return Colors.translate(s);
    }

    /** Il prefisso del plugin, gia' colorato: &#C046E8&lMagixFactions &8» &r */
    public String prefix() {
        return Colors.translate(cfg.getString("prefix", ""));
    }

    /** Sezione grezza di messages.yml (la usa l'aiuto, che e' strutturato a sezioni). */
    public ConfigurationSection section(String path) {
        return cfg.getConfigurationSection(path);
    }

    /** Lista di righe colorate. */
    public List<String> getList(String path) {
        List<String> out = new ArrayList<>();
        for (String s : cfg.getStringList(path)) {
            out.add(Colors.translate(s));
        }
        return out;
    }
}
