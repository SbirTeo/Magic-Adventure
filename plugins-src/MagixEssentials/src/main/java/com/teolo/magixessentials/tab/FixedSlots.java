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
 *   <li><b>niente testa</b>: il profilo porta una texture di skin <i>trasparente</i> — verificata
 *       davvero, non solo scritta: scaricata e controllata pixel per pixel, la zona della faccia
 *       e' alpha zero al 100%. La prima versione di questa skin aveva un hash MORTO (404 su
 *       Mojang): per mesi le caselle vuote hanno mostrato teste Steve/Alex a caso invece di
 *       essere invisibili, senza un solo errore nel log — un link che smette di rispondere non
 *       lancia un'eccezione, restituisce solo la skin di serie;</li>
 *   <li><b>niente tacchette</b>: latenza <b>-1</b>, negativa apposta — nel protocollo vuol dire
 *       "connessione non ancora nota", ed e' semanticamente quello che una casella finta e': una
 *       connessione che non esiste. Il client la disegna con l'icona "connessione sconosciuta"
 *       (assets/minecraft/textures/gui/sprites/icon/ping_unknown.png), che il resource pack di
 *       MagixFactions sostituisce con una trasparente — l'unica delle sei icone di ping che un
 *       giocatore VERO non puo' mai avere davvero, quindi l'unica spegnibile senza spegnere anche
 *       la barra di qualcun altro. Le 5 barre vere restano quelle vere: non si toccano.</li>
 * </ul>
 *
 * <p>Se qualcosa non torna (ProtocolLib assente, struttura del pacchetto diversa da quella che ci
 * aspettiamo) non si rompe niente: si spegne da sola e il tablist resta quello dinamico. Un tab
 * di misura variabile e' un difetto estetico; un tab che sparisce e' un guasto.</p>
 */
public final class FixedSlots {

    /**
     * Skin interamente trasparente: e' cosi' che la casella vuota resta senza testa.
     *
     * <p>{@code {"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/b8de64f..."}}}}
     * in base64, senza firma (il client non la controlla per rendere la testa di UN ALTRO
     * giocatore in tab — solo per la propria, al login). L'hash e' VERO e VERIFICATO: risolve
     * ancora su Mojang, ed e' stato scaricato e controllato pixel per pixel (4031 pixel su 4096
     * ad alpha zero, la faccia frontale — quella che il tab disegna — al 100%). I 65 pixel non
     * trasparenti che restano sono un marchio scritto nell'angolo in alto a sinistra della skin,
     * una zona che il modello 3D non disegna mai: non si vedono, ne' in tab ne' sul personaggio.
     *
     * <p>La skin di prima aveva un hash che sul serio non esisteva piu' (404 su Mojang): per chi
     * sa quanto tempo, le caselle vuote hanno mostrato la skin di serie (Steve/Alex a caso) invece
     * di essere invisibili — e nel log non c'era NESSUN errore, perche' un link morto non lancia
     * un'eccezione, fa solo apparire la testa che questa funzione doveva nascondere. Se un giorno
     * anche questo hash dovesse sparire, il sintomo e' lo stesso (teste che tornano visibili senza
     * un rigo nel log) e il rimedio e' lo stesso: verificarlo — {@code curl -I} sull'URL sopra, o
     * scaricarlo e controllarne l'alpha — non indovinarne un altro a memoria.
     */
    private static final String TRANSPARENT_TEXTURE =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjhkZTY0"
            + "ZmFlOGE2NWUwNzk5Yzc4NzA1ZTgyZjZjNjAzZDkxYWFmZDQzZjdiNTJhYmZkNmJmZDUyNTE2NzhlMyJ9fX0=";

    /**
     * Latenza mostrata nelle caselle vuote: NEGATIVA, apposta. Il protocollo lo dice esplicito
     * (pagina "Player Info Update" del wiki del protocollo) — una latenza negativa vuol dire
     * "non ancora nota", ed e' semanticamente quello che una casella finta E': una connessione
     * che non esiste. Il client la disegna con un'icona a parte (assets/minecraft/textures/gui/
     * sprites/icon/ping_unknown.png — verificato scaricando il client vanilla reale di questa
     * versione), diversa dalle cinque barre che vedono i giocatori VERI.
     *
     * <p>Per un giro (v0.8.5) qui c'era 0: cinque barre piene invece della X, la meno vistosa fra
     * le sei icone del protocollo — ma pur sempre un'icona, visibile. La soluzione buona non era
     * scegliere fra le sei: era rendersi conto che l'icona di "connessione sconosciuta" e' l'UNICA,
     * fra le sei, che un giocatore VERO non puo' mai avere davvero (un ping negativo non esiste per
     * una connessione stabilita) — quindi e' l'unica che si puo' rendere trasparente nel resource
     * pack di MagixFactions (gia' obbligatorio per la minimap) senza spegnere anche l'icona di
     * qualcun altro. Le cinque barre restano quelle vere: quelle NON si toccano.</p>
     */
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
