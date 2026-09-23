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
}
