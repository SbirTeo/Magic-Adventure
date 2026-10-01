package com.teolo.magixessentials.lang;

import com.teolo.magixessentials.hook.Papi;
import com.teolo.magixessentials.util.TextFormat;
import com.teolo.magixlanguage.api.MagixLanguageAPI;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Carica e fornisce i messaggi da {@code messages.yml} (modificabile dall'utente), tradotti
 * tramite MagixLanguage se il destinatario e' un giocatore che non parla italiano — stesso
 * pattern degli altri plugin Magix (vedi {@code lang.Messages} di MagixScoreboard), adattato ai
 * {@code Component} di Adventure che il resto di questo plugin gia' usa (vedi
 * {@code util.TextFormat}) invece dei codici legacy.
 */
public final class Messages {

    /** Nome con cui MagixLanguage riconosce questo plugin nei suoi cataloghi tradotti. */
    private static final String PLUGIN_NAME = "MagixEssentials";

    private final JavaPlugin plugin;
    private FileConfiguration cfg;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        if (!f.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        cfg = YamlConfiguration.loadConfiguration(f);
        // Ripiego sul messages.yml del jar: le chiavi nuove funzionano anche se il file gia'
        // presente sul server non e' stato riallineato (vedi util.ConfigAlign, che lo fa comunque
        // a ogni avvio e reload: questo e' solo una rete di sicurezza in piu').
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                YamlConfiguration def = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                cfg.setDefaults(def);
                cfg.options().copyDefaults(true);
            }
        } catch (Exception ignored) {
        }
    }

    /** Manda il messaggio, tradotto nella lingua del destinatario se e' un giocatore. */
    public void send(CommandSender to, String path, String... kv) {
        to.sendMessage(componentFor(to, path, kv));
    }

    private Component componentFor(CommandSender to, String path, String... kv) {
        String translated = to instanceof Player player ? translated(player, path, kv) : null;
        String text = translated != null ? translated : raw(path, kv);
        if (to instanceof Player player && Papi.enabled() && text.indexOf('%') >= 0) {
            text = Papi.resolve(player, text);
        }
        return TextFormat.component(text);
    }

    private String raw(String path, String... kv) {
        String s = cfg.getString(path);
        if (s == null) {
            return "&cmissing message: " + path;
        }
        return apply(s, kv);
    }

    private static String apply(String s, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return s;
    }

    // ------------------------------------------------------------- MagixLanguage (opzionale)

    /** Null se MagixLanguage non c'e', il giocatore parla gia' italiano, o la chiave non e' tradotta. */
    private String translated(Player player, String path, String... kv) {
        MagixLanguageAPI api = magixLanguage();
        if (api == null || "it".equals(api.language(player))) {
            return null;
        }
        try {
            return api.translate(PLUGIN_NAME, player, path, toMap(kv));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Il servizio di MagixLanguage se il plugin e' installato e attivo, altrimenti null: mai un'eccezione. */
    private static MagixLanguageAPI magixLanguage() {
        if (Bukkit.getPluginManager().getPlugin("MagixLanguage") == null) {
            return null;
        }
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
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(kv[i], kv[i + 1]);
        }
        return out;
    }
}
