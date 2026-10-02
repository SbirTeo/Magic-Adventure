package com.teolo.magixbridge.language;

import com.teolo.magixlanguage.api.MagixLanguageAPI;
import com.teolo.magixbridge.MagixBridge;
import com.teolo.magixbridge.db.Database;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
    public static final int MAX_ATTEMPTS = 5;

    private final MagixBridge plugin;
    private final Database database;
    private final int batchSize;

    public SiteTranslationWorker(MagixBridge plugin, Database database, int batchSize) {
        this.plugin = plugin;
        this.database = database;
        this.batchSize = Math.max(1, batchSize);
    }

    public boolean hook() {
        if (Bukkit.getPluginManager().getPlugin("MagixLanguage") == null) {
            plugin.getLogger().warning("MagixLanguage non trovato: il sito resterà in italiano per chi visita in un'altra lingua.");
            return false;
        }
        return true;
    }

    /** Un giro alla volta: vedi {@link #run()}. */
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * Un giro: legge le frasi in attesa, le raggruppa per lingua e le traduce un lotto alla volta.
     * Il timer lo richiama ogni 30 secondi anche se il giro prima non e' finito: con MyMemory lento
     * (4 secondi per frase in timeout) i giri si accavallavano, visto succedere davvero con sei
     * thread insieme, e ognuno rileggeva le STESSE frasi in attesa: stessa frase tradotta piu'
     * volte (quota sprecata) e piu' tentativi falliti contati per un solo timeout.
     */
    public void run() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            runOnce();
        } finally {
            running.set(false);
        }
    }

    private void runOnce() {
        MagixLanguageAPI api = magixLanguage();
        if (api == null) {
            return;
        }
        // Durante una pausa di MyMemory translateRawBatch non chiama nessuno e torna vuoto: contarlo
        // come tentativo fallito bruciava ogni frase in quattro giri (due minuti) e la segnava
        // "failed" per sempre, senza che MyMemory l'avesse mai vista.
        if (api.autoTranslationAvailable()) {
            Map<String, Map<String, String>> pendingByLang = readPending();
            for (Map.Entry<String, Map<String, String>> e : pendingByLang.entrySet()) {
                String lang = e.getKey();
                Map<String, String> hashToText = e.getValue();
                Map<String, String> translated = api.translateRawBatch(new ArrayList<>(hashToText.values()), lang);
                // Bloccato a meta' lotto: le frasi rimaste non sono state provate, non e' colpa loro.
                boolean stillAvailable = api.autoTranslationAvailable();
                for (Map.Entry<String, String> entry : hashToText.entrySet()) {
                    String hash = entry.getKey();
                    String result = translated.get(entry.getValue());
                    if (result != null) {
                        markDone(lang, hash, result);
                    } else if (stillAvailable) {
                        markFailedAttempt(lang, hash);
                    }
                }
                if (!stillAvailable) {
                    break;
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
            plugin.getLogger().warning("MagixBridge: impossibile leggere lo stato delle traduzioni del sito (" + e.getMessage() + ").");
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
            plugin.getLogger().warning("MagixBridge: impossibile leggere le frasi del sito da tradurre (" + e.getMessage() + ").");
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
            plugin.getLogger().warning("MagixBridge: impossibile salvare una traduzione del sito (" + e.getMessage() + ").");
        }
    }

    private void markFailedAttempt(String lang, String hash) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     // MySQL/MariaDB valutano le assegnazioni da sinistra: qui "attempts" e' GIA'
                     // quello incrementato (con "attempts + 1" diventava "failed" al quarto, non al quinto).
                     "UPDATE site_translations SET attempts = attempts + 1, "
                             + "status = IF(attempts >= ?, 'failed', 'pending') "
                             + "WHERE lang = ? AND phrase_hash = ?")) {
            ps.setInt(1, MAX_ATTEMPTS);
            ps.setString(2, lang);
            ps.setString(3, hash);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixBridge: impossibile registrare il fallimento di una traduzione del sito (" + e.getMessage() + ").");
        }
    }
}
