package com.teolo.magixlanguage.translate;

import com.teolo.magixlanguage.api.PlayerLanguageChangeEvent;
import com.teolo.magixlanguage.store.LanguageStore;
import org.bukkit.Bukkit;
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
 *
 * <p>Con il database ({@link LanguageStore}) la lingua vale per tutta la rete: ogni scelta va
 * anche nella tabella condivisa, e a ogni ingresso si rilegge da li' ({@link #refreshFromNetwork}),
 * cosi' una lingua cambiata sull'hub e' quella giusta anche sul faction e sul proxy. players.yml
 * resta la copia locale, usata se il database non risponde.</p>
 */
public final class PlayerLocales {

    /** Chi ha scelto la lingua, per sapere se un ingresso successivo puo' ancora cambiarla da solo. */
    public enum Source { GEOIP, MANUAL }

    public record Entry(String lang, Source source, String country) {}

    private final File file;
    private final Logger log;
    private final Map<UUID, Entry> byPlayer = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private volatile LanguageStore store;

    public PlayerLocales(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "players.yml");
        this.log = log;
        load();
    }

    public Entry get(UUID playerId) {
        return byPlayer.get(playerId);
    }

    /**
     * Collega il database condiviso e ci porta le lingue che questo server conosceva gia' (va
     * chiamato fuori dal thread principale).
     */
    public void useStore(LanguageStore store) {
        this.store = store;
        if (store == null || !store.createTable()) {
            return;
        }
        Map<UUID, LanguageStore.Row> rows = new java.util.HashMap<>();
        for (Map.Entry<UUID, Entry> e : byPlayer.entrySet()) {
            rows.put(e.getKey(), row(e.getValue()));
        }
        int n = store.importAll(rows);
        if (n > 0) {
            log.info("MagixLanguage: " + n + " lingue di players.yml confrontate con quelle del database.");
        }
    }

    /**
     * La lingua del giocatore com'e' nel database condiviso, copiata qui se diversa (scelta su un
     * altro server). Da chiamare fuori dal thread principale, all'ingresso.
     */
    public void refreshFromNetwork(UUID playerId) {
        LanguageStore s = store;
        if (s == null) {
            return;
        }
        LanguageStore.Row row = s.get(playerId);
        if (row == null) {
            Entry local = byPlayer.get(playerId);
            if (local != null) {
                s.put(playerId, row(local)); // conosciuta solo qui: la sappiano anche gli altri
            }
            return;
        }
        Source source = "manual".equalsIgnoreCase(row.source()) ? Source.MANUAL : Source.GEOIP;
        Entry fresh = new Entry(row.lang(), source, row.country());
        if (fresh.equals(byPlayer.get(playerId))) {
            return;
        }
        Entry previous = byPlayer.put(playerId, fresh);
        save();
        if (previous == null || !previous.lang().equals(fresh.lang())) {
            Bukkit.getPluginManager().callEvent(new PlayerLanguageChangeEvent(playerId, fresh.lang(),
                    source == Source.MANUAL));
        }
    }

    private void publish(UUID playerId, Entry entry) {
        LanguageStore s = store;
        if (s == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            s.putLater(playerId, row(entry));
        } else {
            s.put(playerId, row(entry)); // all'ingresso: scritta prima che il proxy la rilegga
        }
    }

    private static LanguageStore.Row row(Entry e) {
        return new LanguageStore.Row(e.lang(), e.source() == Source.MANUAL ? "manual" : "geoip", e.country());
    }

    /** Impostata da GeoIP al primo ingresso: un ingresso successivo puo' ancora correggerla da sola. */
    public void setDetected(UUID playerId, String lang, String country) {
        Entry entry = new Entry(lang, Source.GEOIP, country);
        byPlayer.put(playerId, entry);
        save();
        publish(playerId, entry);
        Bukkit.getPluginManager().callEvent(new PlayerLanguageChangeEvent(playerId, lang, false));
    }

    /** Impostata a mano (comando o API di un altro plugin): da qui in poi il GeoIP non la tocca piu'. */
    public void setManual(UUID playerId, String lang) {
        Entry existing = byPlayer.get(playerId);
        Entry entry = new Entry(lang, Source.MANUAL, existing != null ? existing.country() : null);
        byPlayer.put(playerId, entry);
        save();
        publish(playerId, entry);
        Bukkit.getPluginManager().callEvent(new PlayerLanguageChangeEvent(playerId, lang, true));
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
