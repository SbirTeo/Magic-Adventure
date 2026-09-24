package com.teolo.magixfactions.lang;

import com.teolo.magixfactions.util.Colors;
import com.teolo.magixlanguage.api.MagixLanguageAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Carica e fornisce i messaggi da messages.yml (modificabile dall'utente). */
public final class Messages {

    /** Nome con cui MagixLanguage riconosce questo plugin nei suoi cataloghi tradotti. */
    private static final String PLUGIN_NAME = "MagixFactions";

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

    /** Messaggio colorato con sostituzione placeholder {chiave}, sempre in italiano. */
    public String get(String path, String... kv) {
        String s = cfg.getString(path);
        if (s == null) return ChatColor.RED + "missing message: " + path;
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        // Colors.translate e non solo translateAlternateColorCodes: i messaggi usano anche gli
        // esadecimali &#RRGGBB (il viola e il verde del logo), che la traduzione classica si
        // lascerebbe dietro stampandoli a schermo per esteso.
        return Colors.translate(s);
    }

    /**
     * Come {@link #get(String, String...)}, ma tradotto nella lingua del destinatario se e' un
     * giocatore con MagixLanguage installato e non italofono; altrimenti l'italiano di sempre.
     * E' quello che usano {@code msgKey}/{@code panelKey} per la stragrande maggioranza delle
     * risposte del plugin.
     */
    public String get(CommandSender to, String path, String... kv) {
        String translated = to instanceof Player player ? translated(player, path, kv) : null;
        return translated != null ? Colors.translate(translated) : get(path, kv);
    }

    /** Sezione grezza di messages.yml (la usa l'aiuto, che e' strutturato a sezioni). */
    public ConfigurationSection section(String path) {
        return cfg.getConfigurationSection(path);
    }

    /** Lista di righe colorate, sempre in italiano. */
    public List<String> getList(String path) {
        List<String> out = new ArrayList<>();
        for (String s : cfg.getStringList(path)) {
            out.add(Colors.translate(s));
        }
        return out;
    }

    /** Come {@link #getList(String)}, ma tradotta per il destinatario (es. /f help). */
    public List<String> getList(CommandSender to, String path) {
        List<String> translated = to instanceof Player player ? translatedList(player, path) : null;
        if (translated == null) return getList(path);
        List<String> out = new ArrayList<>(translated.size());
        for (String s : translated) out.add(Colors.translate(s));
        return out;
    }

    // ------------------------------------------------------------- MagixLanguage (opzionale)

    /** Null se MagixLanguage non c'e', il giocatore parla gia' italiano, o la chiave non e' (ancora) tradotta. */
    private String translated(Player player, String path, String... kv) {
        MagixLanguageAPI api = magixLanguage();
        if (api == null || "it".equals(api.language(player))) return null;
        try {
            return api.translate(PLUGIN_NAME, player, path, toMap(kv));
        } catch (Throwable t) {
            return null;
        }
    }

    private List<String> translatedList(Player player, String path) {
        MagixLanguageAPI api = magixLanguage();
        if (api == null || "it".equals(api.language(player))) return null;
        try {
            return api.translateList(PLUGIN_NAME, player, path, Map.of());
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
