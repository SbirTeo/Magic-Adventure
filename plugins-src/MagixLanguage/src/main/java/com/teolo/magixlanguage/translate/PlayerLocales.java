package com.teolo.magixlanguage.translate;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * La lingua scelta (o rilevata) per ogni giocatore, tenuta in {@code players.yml} nella cartella
 * dati del plugin. Sopravvive ai riavvii: chi e' gia' stato classificato non rifa' il giro del
 * GeoIP ad ogni ingresso.
 */
public final class PlayerLocales {

    /** Chi ha scelto la lingua, per sapere se un ingresso successivo puo' ancora cambiarla da solo. */
    public enum Source { GEOIP, MANUAL }

    public record Entry(String lang, Source source, String country) {}

    private final File file;
    private final Logger log;
    private final Map<UUID, Entry> byPlayer = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public PlayerLocales(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "players.yml");
        this.log = log;
        load();
    }

    public Entry get(UUID playerId) {
        return byPlayer.get(playerId);
    }

    /** Impostata da GeoIP al primo ingresso: un ingresso successivo puo' ancora correggerla da sola. */
    public void setDetected(UUID playerId, String lang, String country) {
        byPlayer.put(playerId, new Entry(lang, Source.GEOIP, country));
        save();
    }

    /** Impostata a mano (comando o API di un altro plugin): da qui in poi il GeoIP non la tocca piu'. */
    public void setManual(UUID playerId, String lang) {
        Entry existing = byPlayer.get(playerId);
        byPlayer.put(playerId, new Entry(lang, Source.MANUAL, existing != null ? existing.country() : null));
        save();
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        org.bukkit.configuration.ConfigurationSection players = cfg.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        int loaded = 0;
        for (String key : players.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            org.bukkit.configuration.ConfigurationSection p = players.getConfigurationSection(key);
            if (p == null) continue;
            String lang = p.getString("lang");
            if (lang == null) continue;
            Source source = "manual".equalsIgnoreCase(p.getString("source")) ? Source.MANUAL : Source.GEOIP;
            byPlayer.put(id, new Entry(lang, source, p.getString("country")));
            loaded++;
        }
        log.info("MagixLanguage: " + loaded + " lingue giocatore ricaricate da players.yml.");
    }

    private void save() {
        synchronized (lock) {
            YamlConfiguration cfg = new YamlConfiguration();
            for (Map.Entry<UUID, Entry> e : byPlayer.entrySet()) {
                String path = "players." + e.getKey();
                cfg.set(path + ".lang", e.getValue().lang());
                cfg.set(path + ".source", e.getValue().source() == Source.MANUAL ? "manual" : "geoip");
                if (e.getValue().country() != null) {
                    cfg.set(path + ".country", e.getValue().country());
                }
            }
            try {
                File dir = file.getParentFile();
                if (dir != null) dir.mkdirs();
                cfg.save(file);
            } catch (IOException e) {
                log.warning("MagixLanguage: impossibile salvare players.yml (" + e + ").");
            }
        }
    }
}
