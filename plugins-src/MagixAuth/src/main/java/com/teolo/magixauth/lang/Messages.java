package com.teolo.magixauth.lang;

import com.teolo.magixlanguage.api.MagixLanguageAPI;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Carica e fornisce i testi del cancello da messages.yml (modificabile dall'utente), gia'
 * colorati con {@code &}, pronti per {@code Texts.c(...)}.
 */
public final class Messages {

    /** Nome con cui MagixLanguage riconosce questo plugin nei suoi cataloghi tradotti. */
    private static final String PLUGIN_NAME = "MagixAuth";

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

    /** Il testo cosi' com'e' nel file, sempre in italiano, con sostituzione placeholder {chiave}. */
    public String get(String path, String... kv) {
        String s = cfg.getString(path);
        if (s == null) return "missing message: " + path;
        return apply(s, kv);
    }

    /** Come {@link #get(String, String...)}, tradotto per questo giocatore se non e' italofono. */
    public String get(Player player, String path, String... kv) {
        return get(player.getUniqueId(), path, kv);
    }

    /**
     * Come {@link #get(String, String...)}, tradotto per questo destinatario se MagixLanguage e'
     * installato e lui non parla italiano. Si lavora per UUID (non {@code Player}) perche' al
     * cancello, durante il pre-login, non esiste ancora un {@code Player} a cui appoggiarsi.
     */
    public String get(UUID recipient, String path, String... kv) {
        String translated = translated(recipient, path, kv);
        return translated != null ? translated : get(path, kv);
    }

    /** Come {@link #get(String, String...)}, tradotto per questo destinatario (usato dai comandi,
     *  dove esiste sempre un CommandSender; la console resta in italiano, non ha una lingua). */
    public String get(CommandSender to, String path, String... kv) {
        return to instanceof Player player ? get(player, path, kv) : get(path, kv);
    }

    /** Lista di righe, sempre in italiano, con sostituzione placeholder {chiave}. */
    public List<String> getList(String path, String... kv) {
        List<String> out = new ArrayList<>();
        for (String s : cfg.getStringList(path)) {
            out.add(apply(s, kv));
        }
        return out;
    }

    /** Come {@link #getList(String, String...)}, ma tradotta per questo destinatario (es. l'aiuto). */
    public List<String> getList(CommandSender to, String path, String... kv) {
        List<String> translated = to instanceof Player player
                ? translatedList(player.getUniqueId(), path, kv) : null;
        return translated != null ? translated : getList(path, kv);
    }

    /** Sezione grezza di messages.yml (la usa l'aiuto, che e' strutturato a sezioni). */
    public ConfigurationSection section(String path) {
        return cfg.getConfigurationSection(path);
    }

    private static String apply(String s, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s;
    }

    // ------------------------------------------------------------- MagixLanguage (opzionale)

    /** Null se MagixLanguage non c'e', il destinatario parla gia' italiano, o la chiave non e' (ancora) tradotta. */
    private String translated(UUID recipient, String path, String... kv) {
        MagixLanguageAPI api = magixLanguage();
        if (api == null) return null;
        String lang = api.language(recipient);
        if ("it".equals(lang)) return null;
        try {
            return api.translate(PLUGIN_NAME, lang, path, toMap(kv));
        } catch (Throwable t) {
            return null;
        }
    }

    private List<String> translatedList(UUID recipient, String path, String... kv) {
        MagixLanguageAPI api = magixLanguage();
        if (api == null) return null;
        String lang = api.language(recipient);
        if ("it".equals(lang)) return null;
        try {
            return api.translateList(PLUGIN_NAME, lang, path, toMap(kv));
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

    private static Map<String, String> toMap(String... kv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) out.put(kv[i], kv[i + 1]);
        return out;
    }
}
