package com.teolo.magixweb.language;

import com.teolo.magixlanguage.api.MagixLanguageAPI;
import com.teolo.magixlanguage.api.PlayerLanguageChangeEvent;
import com.teolo.magixweb.MagixWeb;
import com.teolo.magixweb.db.Database;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Copia nel database del sito la lingua di ogni giocatore (MagixLanguage), nella colonna
 * {@code language} di {@code mc_ranks} — la stessa tabella del rank di LuckPerms — cosi' il sito
 * sa in che lingua parlare con chi e' collegato senza dover interrogare il plugin di gioco.
 *
 * Stesso schema di {@code rank.RankSync}: si aggiorna al join e a ogni cambio vero (qui un
 * evento di MagixLanguage, li' gli eventi di LuckPerms), mai un giro a intervalli fissi.
 */
public final class LanguageSync implements Listener {

    private final MagixWeb plugin;
    private final Database database;

    public LanguageSync(MagixWeb plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    /** @return true se MagixLanguage e' presente e la sincronizzazione puo' partire. */
    public boolean hook() {
        if (Bukkit.getPluginManager().getPlugin("MagixLanguage") == null) {
            plugin.getLogger().warning("MagixLanguage non trovato: la lingua non verra' sincronizzata col sito.");
            return false;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        // Stesso ritardo di RankSync: al momento del join la lingua e' gia' decisa (MagixLanguage
        // la sceglie durante AsyncPlayerPreLoginEvent, prima ancora che il giocatore entri), ma
        // si aspetta comunque un attimo per restare coerenti col resto della riga in mc_ranks.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = event.getPlayer();
            if (p.isOnline()) {
                sync(p.getUniqueId(), language(p.getUniqueId()));
            }
        }, 40L);
    }

    /** Un cambio vero (comando /language set, o una rilevazione GeoIP nuova): sync immediato. */
    @EventHandler
    public void onLanguageChange(PlayerLanguageChangeEvent event) {
        sync(event.getPlayerId(), event.getLanguage());
    }

    /** Dopo un reload di MagixWeb chi e' gia' in gioco non genera un PlayerJoinEvent. */
    public void syncOnlinePlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            sync(p.getUniqueId(), language(p.getUniqueId()));
        }
    }

    private String language(UUID uuid) {
        MagixLanguageAPI api = magixLanguage();
        return api == null ? null : api.language(uuid);
    }

    private static MagixLanguageAPI magixLanguage() {
        try {
            RegisteredServiceProvider<MagixLanguageAPI> rsp =
                    Bukkit.getServicesManager().getRegistration(MagixLanguageAPI.class);
            return rsp != null ? rsp.getProvider() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void sync(UUID uuid, String lang) {
        if (lang == null || lang.isBlank()) {
            return;
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        String username = op.getName();
        if (username == null) {
            return; // non dovrebbe succedere: senza nome non c'e' riga da abbinare
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(uuid, username, lang));
    }

    private void write(UUID uuid, String username, String lang) {
        // INSERT ... ON DUPLICATE, con valori segnaposto per le colonne obbligatorie del rank:
        // la riga deve poter esistere anche se RankSync non e' ancora passato di li' (es. LuckPerms
        // assente, o semplicemente non e' ancora il suo turno) - senza dipendere dall'ordine.
        // RankSync la corregge da solo appena arriva, con lo stesso ON DUPLICATE KEY UPDATE.
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO mc_ranks (mc_uuid, mc_username, group_name, group_display, weight, language, updated_at) "
                             + "VALUES (?, ?, 'default', 'Player', 0, ?, NOW()) "
                             + "ON DUPLICATE KEY UPDATE language = VALUES(language), mc_username = VALUES(mc_username)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            ps.setString(3, lang);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixWeb: impossibile salvare la lingua di " + username + " (" + e.getMessage() + ").");
        }
    }
}
