package com.teolo.magixessentials.tab;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Le caselle FINTE che tengono il tablist sempre della stessa misura.
 *
 * <p>Il gioco decide da solo quante colonne disegnare in base a quante voci ci sono: con pochi
 * giocatori il tab e' una colonna sottile, con tanti si allarga. Se sotto ci deve stare una
 * pergamena, quella misura non puo' ballare. Allora si riempie: tante voci decorative quante ne
 * mancano per arrivare a {@code total}, e la finestra resta sempre uguale.</p>
 *
 * <p><b>Perche' servono i pacchetti.</b> Una voce del tablist senza un giocatore vero dietro non
 * esiste nell'API di Bukkit: il tablist E' l'elenco dei giocatori connessi. Le voci in piu' si
 * mandano al client a mano, ed e' l'unico punto di questo plugin che parla di pacchetti.</p>
 *
 * <p><b>Come sono fatte le caselle vuote.</b> Due dettagli, ed erano il motivo della richiesta:
 * <ul>
 *   <li><b>niente testa</b>: il profilo porta una texture di skin <i>trasparente</i>. Un profilo
 *       senza texture non e' invisibile — il gioco ci mette la skin di serie, e si vedrebbero
 *       ottanta teste di Steve;</li>
 *   <li><b>niente tacchette</b>: latenza <b>-1</b>. E' il valore che il client disegna come barra
 *       vuota, quello che usa per chi non ha ancora risposto.</li>
 * </ul>
 *
 * <p>Se qualcosa non torna (ProtocolLib assente, struttura del pacchetto diversa da quella che ci
 * aspettiamo) non si rompe niente: si spegne da sola e il tablist resta quello dinamico. Un tab
 * di misura variabile e' un difetto estetico; un tab che sparisce e' un guasto.</p>
 */
public final class FixedSlots {

    /** Skin interamente trasparente: e' cosi' che la casella vuota resta senza testa. */
    private static final String TRANSPARENT_TEXTURE =
            "eyJ0aW1lc3RhbXAiOjE1ODY1MzYwNTA3NzcsInByb2ZpbGVJZCI6ImEwZjE3NTZlYzhmZDQ5MGJhNDczMGIxNDRlNzI0MmY0"
            + "IiwicHJvZmlsZU5hbWUiOiJfX19fX19fX19fX19fX18iLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOn"
            + "siU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzM2NmY3ZjhlMmYyNjc5NzQx"
            + "MzFlZjZlNTZmMzM1ZDNlYWY4MzJmYjMxNGVmYjNhNjU5Y2VmMjc5YTRlNGY0ZTgifX19";

    /** Latenza che il client disegna come barra vuota: nessuna tacchetta di connessione. */
    private static final int NO_PING = -1;

    private final JavaPlugin plugin;
    /** Le impostazioni del tablist: il {@code tablist.yml} della cartella dati. */
    private final ConfigurationSection cfg;
    /** I profili finti, creati una volta sola: ricrearli a ogni giro farebbe lampeggiare il tab. */
    private final List<PlayerInfoData> riempitivi = new ArrayList<>();
    private boolean disponibile;
    /** Il primo invio andato a buon fine: prima di quello "attive" e' solo una speranza. */
    private boolean confermate;
    private int totale;

    public FixedSlots(JavaPlugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    /** true se le slot fisse sono accese, ProtocolLib c'e' e i profili si sono costruiti. */
    public boolean attive() {
        return disponibile;
    }

    /**
     * Prepara i profili finti. Va chiamato una volta all'avvio (e a ogni reload): da qui in poi
     * {@link #inviaA(Player)} si limita a rispedirli.
     */
    public void load() {
        disponibile = false;
        riempitivi.clear();

        if (!cfg.getBoolean("fixed-slots.enabled", false)) {
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            plugin.getLogger().warning("[Tab] slot fisse richieste ma ProtocolLib non c'e': "
                    + "resta il tablist dinamico. Installa ProtocolLib o metti fixed-slots.enabled: false.");
            return;
        }

        // Il gioco disegna al massimo 4 colonne da 20: oltre 80 le voci in piu' non si vedrebbero.
        totale = Math.max(1, Math.min(80, cfg.getInt("fixed-slots.total", 80)));
        String testo = cfg.getString("fixed-slots.empty-text", " ");

        try {
            for (int i = 0; i < totale; i++) {
                // Nome unico e invisibile: il client lo usa per ordinare, il giocatore non lo legge
                // mai perche' a schermo compare il display name.
                WrappedGameProfile profilo = new WrappedGameProfile(UUID.randomUUID(), "!MAGIX" + i);
                profilo.getProperties().put("textures",
                        new com.comphenix.protocol.wrappers.WrappedSignedProperty("textures", TRANSPARENT_TEXTURE, null));
                riempitivi.add(new PlayerInfoData(
                        profilo,
                        NO_PING,
                        EnumWrappers.NativeGameMode.SURVIVAL,
                        WrappedChatComponent.fromLegacyText(testo)));
            }
            disponibile = true;
            confermate = false;
            // "Pronte", non "attive": qui i profili esistono, ma se il pacchetto e' fatto in un altro
            // modo il guasto si vede solo al primo invio. Dire "attive" adesso vorrebbe dire scriverlo
            // nel log anche quando poi non si vede niente — ed e' esattamente com'e' andata.
            plugin.getLogger().info("[Tab] slot fisse pronte: " + totale
                    + " caselle. Al primo invio riuscito lo scrivo qui.");
        } catch (Throwable t) {
            riempitivi.clear();
            plugin.getLogger().warning("[Tab] slot fisse non disponibili (" + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + "): resta il tablist dinamico.");
        }
    }

    /**
     * Manda a {@code viewer} le caselle finte che mancano per arrivare a {@code total}.
     *
     * <p>Quante ne servono dipende da quanti giocatori vede lui: si sottraggono gli online. Se sono
     * gia' piu' del totale non si manda niente — il tab e' gia' pieno di gente vera, che e' meglio.</p>
     */
    public void inviaA(Player viewer) {
        if (!disponibile || !viewer.isOnline()) return;

        int veri = Bukkit.getOnlinePlayers().size();
        int quante = totale - veri;
        if (quante <= 0) return;
        if (quante > riempitivi.size()) quante = riempitivi.size();

        try {
            ProtocolManager pm = ProtocolLibrary.getProtocolManager();
            PacketContainer pacchetto = pm.createPacket(PacketType.Play.Server.PLAYER_INFO);
            // Dalla 1.19.3 il pacchetto porta l'insieme delle AZIONI da applicare: aggiungere la voce,
            // renderla visibile in lista, e fissarne latenza e nome mostrato. Senza UPDATE_LISTED la
            // voce esiste ma il tab non la disegna.
            if (!scriviInFondo(pacchetto.getPlayerInfoActions(), EnumSet.of(
                    EnumWrappers.PlayerInfoAction.ADD_PLAYER,
                    EnumWrappers.PlayerInfoAction.UPDATE_LISTED,
                    EnumWrappers.PlayerInfoAction.UPDATE_LATENCY,
                    EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME))) {
                throw new IllegalStateException("nessun campo per l'insieme delle azioni (campi: "
                        + pacchetto.getPlayerInfoActions().size() + ")");
            }
            if (!scriviInFondo(pacchetto.getPlayerInfoDataLists(),
                    new ArrayList<>(riempitivi.subList(0, quante)))) {
                throw new IllegalStateException("nessun campo per l'elenco delle voci (campi elenco: "
                        + pacchetto.getPlayerInfoDataLists().size() + ")");
            }
            pm.sendServerPacket(viewer, pacchetto);
            if (!confermate) {
                confermate = true;
                plugin.getLogger().info("[Tab] slot fisse attive: " + totale + " caselle.");
            }
        } catch (Throwable t) {
            // Una struttura di pacchetto diversa da quella attesa spegne la funzione, non il tablist.
            disponibile = false;
            plugin.getLogger().warning("[Tab] invio delle slot fisse fallito (" + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + "): da ora resta il tablist dinamico.");
        }
    }

    /**
     * Scrive un valore nell'<b>ultimo</b> campo del pacchetto che lo accetta, invece che in un indice
     * deciso a tavolino.
     *
     * <p>Qui c'era un numero scritto a mano — l'indice 1 — e a un aggiornamento del gioco quel campo
     * non c'era piu': {@code Field index 1 is out of bounds for length 1}, e la funzione si spegneva
     * da sola tutti i giorni. L'indice giusto non e' una costante: dipende da quanti campi di quel
     * tipo ha il pacchetto nella versione che gira adesso. Quando ce n'e' piu' d'uno, quello nuovo e'
     * in fondo (il vecchio resta prima, per compatibilita'), quindi si parte dall'ultimo e si scende
     * finche' uno accetta il valore.</p>
     *
     * @return {@code false} se non l'ha accettato nessuno: allora il pacchetto e' fatto in un modo che
     *         non conosciamo, e chi chiama spegne la funzione dicendo quanti campi ha trovato.
     */
    private static <T> boolean scriviInFondo(StructureModifier<T> campi, T valore) {
        for (int i = campi.size() - 1; i >= 0; i--) {
            try {
                campi.write(i, valore);
                return true;
            } catch (RuntimeException e) {
                // Quel campo non fa per noi (tipo diverso, o non scrivibile): si prova quello prima.
            }
        }
        return false;
    }

    /** Toglie le caselle finte dal tab di chi sta guardando (allo spegnimento o a un reload). */
    public void rimuoviDa(Player viewer) {
        if (riempitivi.isEmpty() || !viewer.isOnline()) return;
        try {
            ProtocolManager pm = ProtocolLibrary.getProtocolManager();
            PacketContainer pacchetto = pm.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
            List<UUID> id = new ArrayList<>(riempitivi.size());
            for (PlayerInfoData d : riempitivi) id.add(d.getProfile().getUUID());
            scriviInFondo(pacchetto.getUUIDLists(), id);
            pm.sendServerPacket(viewer, pacchetto);
        } catch (Throwable ignored) {
            // Il client le butta comunque alla disconnessione: non vale un errore in console.
        }
    }
}
