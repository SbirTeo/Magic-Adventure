package com.teolo.magixweb.language;

import com.teolo.magixlanguage.api.MagixLanguageAPI;
import com.teolo.magixweb.MagixWeb;
import com.teolo.magixweb.db.Database;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Smaltisce, un lotto alla volta, le frasi che il sito ha accodato in {@code site_translations}
 * (una pagina le mette li' quando incontra un testo per cui non ha ancora una traduzione, vedi
 * {@code includes/translate.php}) chiedendole a MagixLanguage — stesso servizio (MyMemory) e
 * stessa configurazione {@code translations.auto-translate} usata per i plugin, cosi' la quota
 * giornaliera resta una sola cosa da tenere d'occhio.
 *
 * <p>Non traduce mai sul momento, dentro la richiesta di un visitatore: sarebbe una chiamata di
 * rete nel mezzo del caricamento di una pagina di un sito che incassa pagamenti veri. Il
 * visitatore vede il testo italiano finche' questo lotto non passa a prendere la sua frase, poi
 * la pagina successiva la trova gia' in cache.</p>
 */
public final class SiteTranslationWorker {

    /** Oltre questo numero di tentativi falliti una frase smette di essere ritentata da sola. */
    private static final int MAX_ATTEMPTS = 5;

    private final MagixWeb plugin;
    private final Database database;
    private final int batchSize;

    public SiteTranslationWorker(MagixWeb plugin, Database database, int batchSize) {
        this.plugin = plugin;
        this.database = database;
        this.batchSize = Math.max(1, batchSize);
    }

    public boolean hook() {
        if (Bukkit.getPluginManager().getPlugin("MagixLanguage") == null) {
            plugin.getLogger().warning("MagixLanguage non trovato: il sito restera' in italiano per chi visita in un'altra lingua.");
            return false;
        }
        return true;
    }

    /** Un giro: legge le frasi in attesa, le raggruppa per lingua e le traduce un lotto alla volta. */
    public void run() {
        MagixLanguageAPI api = magixLanguage();
        if (api == null) {
            return;
        }
        Map<String, Map<String, String>> pendingByLang = readPending();
        for (Map.Entry<String, Map<String, String>> e : pendingByLang.entrySet()) {
            String lang = e.getKey();
            Map<String, String> hashToText = e.getValue();
            Map<String, String> translated = api.translateRawBatch(new ArrayList<>(hashToText.values()), lang);
            for (Map.Entry<String, String> entry : hashToText.entrySet()) {
                String hash = entry.getKey();
                String text = entry.getValue();
                String result = translated.get(text);
                if (result != null) {
                    markDone(lang, hash, result);
                } else {
                    markFailedAttempt(lang, hash);
                }
            }
        }
        reportStatus(api);
    }

    /** Riporta a MagixLanguage quante frasi sono pronte/in attesa/fallite per lingua, per
     *  /language status — indipendentemente da quante ne ha smaltite questo giro. */
    private void reportStatus(MagixLanguageAPI api) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT lang, status, COUNT(*) AS n FROM site_translations GROUP BY lang, status")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int[] byStatus = counts.computeIfAbsent(rs.getString("lang"), k -> new int[3]);
                    int n = rs.getInt("n");
                    switch (rs.getString("status")) {
                        case "done" -> byStatus[0] = n;
                        case "pending" -> byStatus[1] = n;
                        case "failed" -> byStatus[2] = n;
                        default -> { }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixWeb: impossibile leggere lo stato delle traduzioni del sito (" + e.getMessage() + ").");
            return;
        }
        api.reportSiteTranslationStatus(counts);
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

    /** lingua -> (hash -> testo italiano), per le frasi ancora in attesa di traduzione. */
    private Map<String, Map<String, String>> readPending() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT lang, phrase_hash, source_text FROM site_translations "
                             + "WHERE status = 'pending' AND attempts < ? ORDER BY updated_at ASC LIMIT ?")) {
            ps.setInt(1, MAX_ATTEMPTS);
            ps.setInt(2, batchSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString("lang"), k -> new LinkedHashMap<>())
                            .put(rs.getString("phrase_hash"), rs.getString("source_text"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixWeb: impossibile leggere le frasi del sito da tradurre (" + e.getMessage() + ").");
        }
        return out;
    }

    private void markDone(String lang, String hash, String translatedText) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE site_translations SET translated_text = ?, status = 'done', attempts = attempts + 1 "
                             + "WHERE lang = ? AND phrase_hash = ?")) {
            ps.setString(1, translatedText);
            ps.setString(2, lang);
            ps.setString(3, hash);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixWeb: impossibile salvare una traduzione del sito (" + e.getMessage() + ").");
        }
    }

    private void markFailedAttempt(String lang, String hash) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE site_translations SET attempts = attempts + 1, "
                             + "status = IF(attempts + 1 >= ?, 'failed', 'pending') "
                             + "WHERE lang = ? AND phrase_hash = ?")) {
            ps.setInt(1, MAX_ATTEMPTS);
            ps.setString(2, lang);
            ps.setString(3, hash);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixWeb: impossibile registrare il fallimento di una traduzione del sito (" + e.getMessage() + ").");
        }
    }
}
