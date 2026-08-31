package com.teolo.magixentities.manage;

import com.teolo.magixentities.model.NpcDef;
import com.teolo.magixentities.util.Colors;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Esegue i comandi associati a un'entita' quando viene cliccata.
 *
 * Funziona anche sulle copie mirror: l'entita' cliccata viene risolta tramite il tag
 * MagixEntities, quindi copia e originale portano agli stessi comandi.
 */
public final class ActionRunner {

    private final JavaPlugin plugin;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public ActionRunner(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Esegue i comandi dell'entita' per quel giocatore.
     *
     * @return true se qualcosa e' stato eseguito (false se l'entita' non ha comandi o se il
     *         giocatore e' ancora in cooldown)
     */
    public boolean run(NpcDef d, Player player) {
        if (d.commands.isEmpty()) return false;

        long cooldown = plugin.getConfig().getLong("commands.cooldown-ms", 500L);
        long now = System.currentTimeMillis();
        Long previous = lastClick.get(player.getUniqueId());
        if (previous != null && now - previous < cooldown) return false;
        lastClick.put(player.getUniqueId(), now);

        for (String raw : d.commands) {
            String line = raw.replace("{player}", player.getName()).replace("{name}", d.name);
            try {
                if (line.regionMatches(true, 0, "console:", 0, 8)) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), strip(line.substring(8)));
                } else if (line.regionMatches(true, 0, "msg:", 0, 4)) {
                    player.sendMessage(Colors.translate(line.substring(4).trim()));
                } else {
                    player.performCommand(strip(line));
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Comando '" + raw + "' di '" + d.name + "' fallito: " + ex.getMessage());
            }
        }
        return true;
    }

    /** Toglie spazi e la barra iniziale: dispatchCommand/performCommand vogliono il comando nudo. */
    private String strip(String s) {
        String out = s.trim();
        return out.startsWith("/") ? out.substring(1) : out;
    }

    public void forget(Player p) {
        lastClick.remove(p.getUniqueId());
    }
}
