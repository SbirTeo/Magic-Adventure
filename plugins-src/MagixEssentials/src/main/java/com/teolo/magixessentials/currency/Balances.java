package com.teolo.magixessentials.currency;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * I saldi di ogni valuta, un giocatore alla volta, su {@code balances.yml}
 * ({@code <id valuta>.<uuid>: <numero>}). Non e' una risorsa del jar: nasce vuota al primo
 * salvataggio, come {@code players.yml} in MagixCosmetics, e {@code util.ConfigAlign} non la
 * tocca per lo stesso motivo (non ha un gemello dentro il jar da cui allinearsi).
 *
 * <p>Un salvataggio scrive l'intero file: i saldi cambiano un comando alla volta, mai per
 * centinaia di giocatori insieme, quindi non serve altro (stessa scelta di HaloStore).</p>
 */
final class Balances {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Map<UUID, Long>> data = new LinkedHashMap<>();

    Balances(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "balances.yml");
        load();
    }

    private synchronized void load() {
        data.clear();
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        for (String currencyId : cfg.getKeys(false)) {
            ConfigurationSection section = cfg.getConfigurationSection(currencyId);
            if (section == null) {
                continue;
            }
            Map<UUID, Long> byPlayer = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                try {
                    byPlayer.put(UUID.fromString(key), section.getLong(key));
                } catch (IllegalArgumentException ignored) {
                    // Riga scritta a mano male (non e' un UUID): si salta, non blocca il resto.
                }
            }
            data.put(currencyId, byPlayer);
        }
    }

    /** Il saldo del giocatore per questa valuta, o {@code startingBalance} se non l'ha mai vista. */
    synchronized long get(String currencyId, UUID player, long startingBalance) {
        Map<UUID, Long> byPlayer = data.get(currencyId);
        if (byPlayer == null) {
            return startingBalance;
        }
        Long value = byPlayer.get(player);
        return value == null ? startingBalance : value;
    }

    /** Fissa il saldo e salva subito: nessuna scrittura resta solo in memoria. */
    synchronized void set(String currencyId, UUID player, long amount) {
        data.computeIfAbsent(currencyId, k -> new LinkedHashMap<>()).put(player, amount);
        save();
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<String, Map<UUID, Long>> currency : data.entrySet()) {
            for (Map.Entry<UUID, Long> entry : currency.getValue().entrySet()) {
                cfg.set(currency.getKey() + "." + entry.getKey(), entry.getValue());
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().severe("[Valute] impossibile salvare balances.yml: " + e.getMessage());
        }
    }
}
