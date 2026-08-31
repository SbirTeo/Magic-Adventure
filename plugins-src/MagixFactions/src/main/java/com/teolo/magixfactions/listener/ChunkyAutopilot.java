package com.teolo.magixfactions.listener;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * "Autopilota" del pregen di Chunky: il pregen a piena velocita' SATURA il server (misurato: TPS da
 * 20 a ~2 con un task attivo e un giocatore online), quindi deve girare SOLO a server vuoto. Questo
 * listener lo gestisce da solo:
 * <ul>
 *   <li>entra il PRIMO giocatore -> {@code chunky pause} (il task viene salvato, nessun progresso perso);</li>
 *   <li>esce l'ULTIMO giocatore -> dopo una piccola attesa anti-relog, {@code chunky continue};</li>
 *   <li>all'avvio del server, se e' vuoto -> {@code chunky continue} (riprende anche dopo un riavvio).</li>
 * </ul>
 * Cosi' il mondo si pre-genera nelle ore morte e il lag da "primo accesso" (generazione dei chunk al
 * join in zone vergini) sparisce col tempo, senza mai disturbare chi gioca. Quando il pregen finisce,
 * {@code chunky continue} diventa un no-op innocuo ("no task da riprendere"). Disattivabile da config
 * ({@code chunky-autopilot}); inerte se Chunky non e' installato.
 */
public final class ChunkyAutopilot implements Listener {

    private static final long RESUME_DELAY_TICKS = 20L * 60; // 1 minuto dopo l'ultimo quit (anti-relog rapido)

    private final JavaPlugin plugin;

    public ChunkyAutopilot(JavaPlugin plugin) {
        this.plugin = plugin;
        // Avvio: se il server parte vuoto (riavvio notturno, ecc.), riprende il pregen da solo dopo che
        // lo startup si e' assestato. Se qualcuno entra prima del kick, onJoin lo ri-pausa comunque.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (enabled() && Bukkit.getOnlinePlayers().isEmpty()) resume();
        }, RESUME_DELAY_TICKS);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("chunky-autopilot", true)
                && plugin.getServer().getPluginManager().getPlugin("Chunky") != null;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled()) return;
        if (Bukkit.getOnlinePlayers().size() == 1) { // primo giocatore dentro: pregen in pausa SUBITO
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chunky pause");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (!enabled()) return;
        // Durante il Quit il giocatore e' ANCORA nella lista online: size 1 = era l'ultimo.
        if (Bukkit.getOnlinePlayers().size() <= 1) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (enabled() && Bukkit.getOnlinePlayers().isEmpty()) resume();
            }, RESUME_DELAY_TICKS);
        }
    }

    private void resume() {
        plugin.getLogger().info("[ChunkyAutopilot] Server vuoto: riprendo il pregen (chunky continue).");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chunky continue");
    }
}
