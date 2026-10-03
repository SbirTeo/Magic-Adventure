package com.teolo.magixpack.lang;

import com.teolo.magixpack.hook.Papi;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        String text = translated != null ? Colors.translate(translated) : get(path);
        if (to instanceof Player player && Papi.enabled() && text.indexOf('%') >= 0) {
            text = Papi.resolve(player, text);
        }
        return text;
    }

    /** A section of messages.yml (e.g. help.sections), or null. */
    public org.bukkit.configuration.ConfigurationSection section(String path) {
        return cfg.getConfigurationSection(path);
    }

    /** The text of {@code path} for this receiver, with {key} replaced by the kv pairs: for panels
     *  such as the command list (util/Help). Translated like {@link #get(CommandSender, String)}. */
    public String forPlayer(CommandSender to, String path, String... kv) {
        String translated = to instanceof Player player ? translated(player, path, kv) : null;
        String text = translated != null ? Colors.translate(translated) : apply(get(path), kv);
        if (to instanceof Player player && Papi.enabled() && text.indexOf('%') >= 0) {
            text = Papi.resolve(player, text);
        }
        return text;
    }

    /** Like {@link #forPlayer}, for a key whose value is a list of lines. */
    public List<String> listForPlayer(CommandSender to, String path) {
        List<String> translated = null;
        if (to instanceof Player player) {
            MagixLanguageAPI api = magixLanguage();
            if (api != null && !"it".equals(api.language(player))) {
                try {
                    translated = api.translateList(PLUGIN_NAME, player, path, Map.of());
                } catch (Throwable t) {
                    translated = null;
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (String line : translated != null ? translated : cfg.getStringList(path)) {
            String text = Colors.translate(line);
            if (to instanceof Player player && Papi.enabled() && text.indexOf('%') >= 0) {
                text = Papi.resolve(player, text);
            }
            out.add(text);
        }
        return out;
    }

    private static String apply(String s, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s;
    }

    private static Map<String, String> toMap(String... kv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) out.put(kv[i], kv[i + 1]);
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

    /**
     * Un testo per i giocatori che sta FUORI da messages.yml (dichiarato in translatable.yml: righe
     * di config, titoli, pannelli...), nella lingua del giocatore. Si passa il testo ITALIANO cosi'
     * com'e' scritto nel file, prima di sostituire segnaposti o placeholder: MagixLanguage lo cerca
     * per frase. Senza MagixLanguage, per chi parla italiano o se la frase non e' ancora tradotta,
     * torna il testo stesso. Mai un'eccezione.
     */
    public static String phrase(Player player, String italian) {
        if (player == null || italian == null || italian.isEmpty()) return italian;
        MagixLanguageAPI api = magixLanguage();
        if (api == null) return italian;
        try {
            if ("it".equals(api.language(player))) return italian;
            String t = api.translatePhrase(PLUGIN_NAME, player, italian);
            return t != null ? t : italian;
        } catch (Throwable t) {
            return italian;
        }
    }

    /** Come {@link #phrase}, riga per riga. */
    public static List<String> phrases(Player player, List<String> italian) {
        if (player == null || italian == null) return italian;
        List<String> out = new ArrayList<>(italian.size());
        for (String line : italian) out.add(phrase(player, line));
        return out;
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
