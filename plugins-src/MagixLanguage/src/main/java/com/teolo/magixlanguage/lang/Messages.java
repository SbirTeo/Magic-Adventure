package com.teolo.magixlanguage.lang;

import com.teolo.magixlanguage.api.MagixLanguageAPI;
import com.teolo.magixlanguage.hook.Papi;
import com.teolo.magixlanguage.util.Colors;
import net.kyori.adventure.text.Component;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
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

    /** Nome con cui questo plugin compare nei propri cataloghi tradotti (translations/MagixLanguage/). */
    private static final String PLUGIN_NAME = "MagixLanguage";

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

    /** Invia il messaggio, nella lingua del destinatario se e' un giocatore. */
    public void send(CommandSender to, String path, String... kv) {
        to.sendMessage(forPlayer(to, path, kv));
    }

    /** Invia una lista di righe (pannelli tipo /language), nella lingua del destinatario. */
    public void sendList(CommandSender to, String path, String... kv) {
        for (String line : listForPlayer(to, path, kv)) to.sendMessage(line);
    }

    /**
     * Il testo di "path" per questo destinatario: tradotto dai cataloghi di MagixLanguage stesso
     * (anche i suoi messaggi passano dalla sincronizzazione, come quelli degli altri plugin), con i
     * placeholder di PlaceholderAPI risolti. Il testo italiano locale se la chiave non e' tradotta.
     */
    public String forPlayer(CommandSender to, String path, String... kv) {
        String translated = null;
        if (to instanceof Player player && plugin instanceof MagixLanguageAPI api && !"it".equals(api.language(player))) {
            translated = api.translate(PLUGIN_NAME, player, path, toMap(kv));
        }
        String text = translated != null ? Colors.translate(translated) : get(path, kv);
        if (to instanceof Player player && Papi.enabled() && text.indexOf('%') >= 0) {
            text = Papi.resolve(player, text);
        }
        return text;
    }

    /** Come {@link #forPlayer}, ma per una chiave il cui valore e' una lista di righe. */
    public List<String> listForPlayer(CommandSender to, String path, String... kv) {
        List<String> translated = null;
        if (to instanceof Player player && plugin instanceof MagixLanguageAPI api && !"it".equals(api.language(player))) {
            translated = api.translateList(PLUGIN_NAME, player, path, toMap(kv));
        }
        List<String> lines = new ArrayList<>();
        if (translated == null) {
            lines.addAll(getList(path, kv));
        } else {
            for (String s : translated) lines.add(Colors.translate(s));
        }
        if (!(to instanceof Player player) || !Papi.enabled()) return lines;
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) out.add(line.indexOf('%') >= 0 ? Papi.resolve(player, line) : line);
        return out;
    }

    private static Map<String, String> toMap(String... kv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) out.put(kv[i], kv[i + 1]);
        return out;
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

    private static String apply(String s, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s;
    }
}
