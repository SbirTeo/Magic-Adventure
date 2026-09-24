package com.teolo.magixtime.lang;

import com.teolo.magixtime.util.Colors;
import com.teolo.magixlanguage.api.MagixLanguageAPI;
import net.kyori.adventure.text.Component;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Carica e fornisce i messaggi da messages.yml (modificabile dall'utente). */
public final class Messages {

    /** Nome con cui MagixLanguage riconosce questo plugin nei suoi cataloghi tradotti. */
    private static final String PLUGIN_NAME = "MagixTime";

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

    /** Messaggio colorato con sostituzione placeholder {chiave}. */
    public String get(String path, String... kv) {
        String s = cfg.getString(path);
        if (s == null) return ChatColor.RED + "missing message: " + path;
        return Colors.translate(apply(s, kv));
    }

    public String prefix() {
        return Colors.translate(cfg.getString("prefix", ""));
    }

    /** Invia il messaggio con il prefisso del plugin, tradotto nella lingua del destinatario se e' un giocatore. */
    public void send(CommandSender to, String path, String... kv) {
        to.sendMessage(prefix() + textFor(to, path, kv));
    }

    /** Invia una lista di righe SENZA prefisso (pannelli tipo /mtime info). */
    public void sendList(CommandSender to, String path, String... kv) {
        for (String line : linesFor(to, path, kv)) to.sendMessage(line);
    }

    /** Lista di righe colorate, con sostituzione placeholder {chiave}. */
    public List<String> getList(String path, String... kv) {
        List<String> out = new ArrayList<>();
        for (String s : cfg.getStringList(path)) out.add(Colors.translate(apply(s, kv)));
        return out;
    }

    /** Sezione grezza di messages.yml (la usa l'aiuto, che e' strutturato a sezioni). */
    public ConfigurationSection section(String path) {
        return cfg.getConfigurationSection(path);
    }

    /** Componente Adventure gia' colorato: serve per titoli e action bar. */
    public Component component(String path, String... kv) {
        return Colors.component(get(path, kv));
    }

    /** Il testo di "path" per questo destinatario, SENZA prefisso: per i pannelli come l'aiuto. */
    public String forPlayer(CommandSender to, String path, String... kv) {
        return textFor(to, path, kv);
    }

    /** Come {@link #forPlayer}, ma per una chiave il cui valore e' una lista di righe. */
    public List<String> listForPlayer(CommandSender to, String path, String... kv) {
        return linesFor(to, path, kv);
    }

    private static String apply(String s, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s;
    }

    // ------------------------------------------------------------- MagixLanguage (opzionale)

    /** Il testo di "path" per questo destinatario: tradotto se e' un giocatore con MagixLanguage
     *  installato e non italofono, altrimenti quello italiano locale. */
    private String textFor(CommandSender to, String path, String... kv) {
        String translated = to instanceof Player player ? translated(player, path, kv) : null;
        return translated != null ? Colors.translate(translated) : get(path, kv);
    }

    /** Come {@link #textFor}, ma per una chiave il cui valore e' una lista di righe. */
    private List<String> linesFor(CommandSender to, String path, String... kv) {
        List<String> translated = to instanceof Player player ? translatedList(player, path, kv) : null;
        if (translated == null) return getList(path, kv);
        List<String> out = new ArrayList<>(translated.size());
        for (String s : translated) out.add(Colors.translate(s));
        return out;
    }

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

    private List<String> translatedList(Player player, String path, String... kv) {
        MagixLanguageAPI api = magixLanguage();
        if (api == null || "it".equals(api.language(player))) return null;
        try {
            return api.translateList(PLUGIN_NAME, player, path, toMap(kv));
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
