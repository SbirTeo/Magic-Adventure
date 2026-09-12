package com.teolo.magixessentials.tab;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/**
 * Gestore del tablist: intestazione, fondo e nome dei giocatori, presi dal config e aggiornati a
 * intervalli regolari (e a ogni ingresso). Header/fondo possono contenere placeholder PER-GIOCATORE
 * (ping, fazione, coordinate...), quindi vengono ricalcolati per ciascuno.
 *
 * <p>Le 80 slot fisse e la pergamena di sfondo NON sono qui: richiedono l'invio di caselle "finte"
 * al client (via ProtocolLib) e arriveranno in un secondo momento. Vedi config {@code fixed-slots}.
 */
public final class TabManager implements Listener {

    /** Sostituito col carattere del LOGO del server (glifo bitmap nel resource pack di MagixFactions). */
    private static final String LOGO_TOKEN = "{logo}";
    private static final String LOGO_CHAR = "";

    /** &-codes + &#RRGGBB (il formato che produce %magixweb_namecolor%) + il vecchio &x&r&r... di Bungee. */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private final JavaPlugin plugin;
    private final boolean papi;
    private BukkitTask task;

    public TabManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.papi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("tablist.enabled", true)) return;

        // Predisposizione 80 slot fisse (non ancora implementate): letta qui cosi' la config e' completa.
        boolean fixedSlots = plugin.getConfig().getBoolean("tablist.fixed-slots.enabled", false);
        int fixedTotal = plugin.getConfig().getInt("tablist.fixed-slots.total", 80);
        if (fixedSlots) {
            plugin.getLogger().info("[Tab] fixed-slots richiesto (" + fixedTotal
                    + ") ma non ancora implementato: uso il tablist dinamico.");
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        long interval = Math.max(1, plugin.getConfig().getLong("tablist.update-interval-ticks", 20));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 20L, interval);
        updateAll();
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        PlayerJoinEvent.getHandlerList().unregister(this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Un tick dopo il join: cosi' i placeholder di mondo/posizione sono gia' pronti.
        Bukkit.getScheduler().runTaskLater(plugin, () -> update(e.getPlayer()), 1L);
    }

    private void updateAll() {
        for (Player p : Bukkit.getOnlinePlayers()) update(p);
    }

    private void update(Player p) {
        if (!p.isOnline()) return;
        Component header = buildLines(p, plugin.getConfig().getStringList("tablist.header"));
        Component footer = buildLines(p, plugin.getConfig().getStringList("tablist.footer"));
        p.sendPlayerListHeaderAndFooter(header, footer);

        String nameFmt = plugin.getConfig().getString("tablist.player-name", "&7{name}");
        p.playerListName(render(p, nameFmt.replace("{name}", p.getName())));
    }

    /** Unisce le righe (ognuna un Component) con un a-capo. Lista vuota -> Component vuoto. */
    private Component buildLines(Player p, List<String> lines) {
        Component out = Component.empty();
        boolean first = true;
        for (String line : lines) {
            if (!first) out = out.append(Component.newline());
            out = out.append(render(p, line));
            first = false;
        }
        return out;
    }

    /** Una riga -> Component: sostituisce {logo}, risolve i placeholder (se c'e' PAPI), applica i colori. */
    private Component render(Player p, String line) {
        String s = line.replace(LOGO_TOKEN, LOGO_CHAR);
        if (papi) s = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, s);
        return LEGACY.deserialize(s);
    }
}
