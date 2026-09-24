package com.teolo.magixpack.lang;

import com.teolo.magixpack.util.Colors;
import com.teolo.magixlanguage.api.MagixLanguageAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Carica e fornisce i messaggi da messages.yml (modificabile dall'utente). */
public final class Messages {

    /** Nome con cui MagixLanguage riconosce questo plugin nei suoi cataloghi tradotti. */
    private static final String PLUGIN_NAME = "MagixPack";

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
        // Fallback sul messages.yml incluso nel jar: le chiavi nuove funzionano
        // anche se il file gia' presente sul server non e' stato aggiornato a mano.
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                YamlConfiguration def = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                cfg.setDefaults(def);
                cfg.options().copyDefaults(true);
            }
        } catch (Exception ignored) {}
    }

    /** Messaggio colorato, sempre in italiano. */
    public String get(String path) {
        String s = cfg.getString(path);
        return s == null ? ChatColor.RED + "missing message: " + path : Colors.translate(s);
    }

    /** Come {@link #get(String)}, ma tradotto nella lingua del destinatario se e' un giocatore
     *  con MagixLanguage installato e non italofono. */
    public String get(CommandSender to, String path) {
        String translated = to instanceof Player player ? translated(player, path) : null;
        return translated != null ? Colors.translate(translated) : get(path);
    }

    // ------------------------------------------------------------- MagixLanguage (opzionale)

    /** Null se MagixLanguage non c'e', il giocatore parla gia' italiano, o la chiave non e' (ancora) tradotta. */
    private String translated(Player player, String path) {
        MagixLanguageAPI api = magixLanguage();
        if (api == null || "it".equals(api.language(player))) return null;
        try {
            return api.translate(PLUGIN_NAME, player, path, Map.of());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Il servizio di MagixLanguage se il plugin e' installato e attivo, altrimenti null: mai un'eccezione. */
    private static MagixLanguageAPI magixLanguage() {
        if (Bukkit.getPluginManager().getPlugin("MagixLanguage") == null) return null;
        try {
            RegisteredServiceProvider<MagixLanguageAPI> rsp =
                    Bukkit.getServicesManager().getRegistration(MagixLanguageAPI.class);
            return rsp != null ? rsp.getProvider() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
