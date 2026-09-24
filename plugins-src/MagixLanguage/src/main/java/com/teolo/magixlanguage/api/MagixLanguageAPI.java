package com.teolo.magixlanguage.api;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Punto di ingresso per gli ALTRI plugin: chi vuole mandare un messaggio gia' tradotto nella
 * lingua del giocatore, senza reimplementare rilevazione o caricamento dei cataloghi, chiede
 * questo servizio a {@code Bukkit.getServicesManager()} (softdepend su MagixLanguage).
 *
 * <pre>
 * RegisteredServiceProvider&lt;MagixLanguageAPI&gt; rsp =
 *         Bukkit.getServicesManager().getRegistration(MagixLanguageAPI.class);
 * if (rsp != null) {
 *     String testo = rsp.getProvider().translate("MagixTime", player, "info-paused", Map.of());
 * }
 * </pre>
 */
public interface MagixLanguageAPI {

    /** Codice lingua (it/en/es/de) attualmente in uso dal giocatore, o default-language se non l'ha mai avuta. */
    String language(UUID playerId);

    /** Comodo per chi ha gia' in mano il {@link Player}. */
    default String language(Player player) {
        return language(player.getUniqueId());
    }

    /**
     * Imposta la lingua a mano (es. un menu di preferenze di un altro plugin). Sovrascrive quella
     * rilevata da GeoIP: da questo momento un nuovo ingresso non la cambia piu' in automatico.
     *
     * @param lang una delle lingue elencate in {@code supported-languages}; un valore non
     *             supportato non ha effetto.
     */
    void setLanguage(OfflinePlayer player, String lang);

    /**
     * Il testo tradotto per il giocatore, cercando la chiave nel catalogo del plugin indicato
     * (quello sincronizzato in {@code plugins/MagixLanguage/translations/<pluginName>/}).
     *
     * <p>Ripiego se manca: la lingua di default. Se anche li' la chiave non c'e' — catalogo mai
     * sincronizzato, chiave nuova non ancora arrivata li' — restituisce {@code null}: <b>non</b> la
     * chiave stessa, apposta, cosi' chi chiama puo' ricadere sul proprio testo italiano locale
     * (sempre presente e sempre corretto) invece di mostrare un nome di chiave grezzo al giocatore.
     * Non lancia mai un'eccezione.
     *
     * @param pluginName    nome della cartella dati dell'altro plugin (es. "MagixTime")
     * @param key           percorso della chiave cosi' com'e' nel suo messages.yml (es. "info-paused")
     * @param placeholders  sostituzioni {chiave} -> valore, come in {@code Messages.get(path, kv...)}
     */
    String translate(String pluginName, Player player, String key, Map<String, String> placeholders);

    /** Come {@link #translate(String, Player, String, Map)}, ma per una lingua scelta a mano. */
    String translate(String pluginName, String lang, String key, Map<String, String> placeholders);

    /**
     * Come {@link #translate(String, Player, String, Map)}, ma per una chiave il cui valore e' una
     * LISTA di righe (es. un pannello tipo {@code info}), non una singola stringa. {@code null} se
     * la chiave manca ovunque o non e' una lista.
     */
    List<String> translateList(String pluginName, Player player, String key, Map<String, String> placeholders);

    /** Come {@link #translateList(String, Player, String, Map)}, ma per una lingua scelta a mano. */
    List<String> translateList(String pluginName, String lang, String key, Map<String, String> placeholders);

    /**
     * Come {@link #translate}, ma per contenuto SENZA una chiave stabile — i menu di MagixMenus,
     * dove il testo (nome di un item, una riga di lore, un messaggio scritto dentro un'azione)
     * vive dentro un file YAML libero, non un catalogo chiave-valore come messages.yml.
     *
     * <p>La ricerca avviene sul TESTO ITALIANO stesso (placeholder come {@code %player_name%}
     * compresi, non ancora sostituiti): chi chiama traduce PRIMA di sostituire i placeholder,
     * esattamente come con {@link #translate}. {@code null} se il catalogo non ha ancora quella
     * frase (mai vista, o non ancora tradotta): chi chiama ricade sul proprio testo italiano.
     *
     * @param pluginName  nome della cartella dati dell'altro plugin (es. "MagixMenus")
     * @param italianText il testo italiano esatto, cosi' com'e' scritto nel file del menu
     */
    String translatePhrase(String pluginName, Player player, String italianText);

    /**
     * Traduce un LOTTO di frasi qualsiasi (non del catalogo di un plugin, non passate da un
     * giocatore) verso una lingua target — pensato per il sito, che accoda frasi in
     * {@code site_translations} e chiede a MagixLanguage di smaltirle un tanto alla volta, con
     * lo stesso servizio (MyMemory) e la stessa configurazione ({@code translations.auto-translate})
     * gia' usata per i plugin.
     *
     * <p>Una sola chiamata per tutto il lotto condivide un solo {@link
     * com.teolo.magixlanguage.translate.Translator}, quindi un solo "circuit breaker": se il
     * servizio comincia a rifiutare le richieste (quota finita, rete giu') ci si ferma per il
     * resto del lotto invece di ritentare una volta per frase.
     *
     * @param italianTexts  le frasi italiane da tradurre, cosi' come sono (nessun placeholder da
     *                      preservare: sono testo del sito, non messaggi di gioco)
     * @param targetLanguage lingua di destinazione (es. "en"); "it" o un valore vuoto/non
     *                       supportato restituiscono una mappa vuota senza chiamare il servizio
     * @return  frase italiana -> traduzione, presente SOLO per le frasi tradotte con successo;
     *          chi chiama ricade sul testo italiano per quelle mancanti
     */
    Map<String, String> translateRawBatch(List<String> italianTexts, String targetLanguage);
}
