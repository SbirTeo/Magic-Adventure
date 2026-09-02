package com.teolo.magixmenus.lang;

import com.teolo.magixmenus.util.Colors;
import net.kyori.adventure.text.Component;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

    /** Invia il messaggio con il prefisso del plugin. */
    public void send(CommandSender to, String path, String... kv) {
        to.sendMessage(prefix() + get(path, kv));
    }

    /** Invia una lista di righe SENZA prefisso (pannelli tipo /mtime info). */
    public void sendList(CommandSender to, String path, String... kv) {
        for (String line : getList(path, kv)) to.sendMessage(line);
    }

    /** Lista di righe colorate, con sostituzione placeholder {chiave}. */
    public List<String> getList(String path, String... kv) {
        List<String> out = new ArrayList<>();
        for (String s : cfg.getStringList(path)) out.add(Colors.translate(apply(s, kv)));
        return out;
    }

    /**
     * Il valore cosi' com'e' scritto nel file, senza colori ne' prefisso.
     *
     * Serve per le chiavi che NON sono messaggi: il nome di un suono, un numero. Passarle da
     * get() le colorerebbe, e "BLOCK_NOTE_BLOCK_PLING" con dentro dei codici colore non e' piu'
     * il nome di un suono.
     */
    public String raw(String path) {
        return cfg.getString(path);
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
