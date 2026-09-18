package com.teolo.magixpack;

import com.teolo.magixpack.command.MagixPackCommand;
import com.teolo.magixpack.pack.PackListener;
import com.teolo.magixpack.pack.PackService;
import com.teolo.magixpack.util.ConfigAlign;
import com.teolo.magixpack.util.StaffGuide;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/**
 * Server UNICO del resource pack: un client Minecraft ne applica solo uno alla volta, quindi
 * invece che ogni plugin ne spedisca uno proprio (che il client scaricherebbe e scarterebbe in
 * silenzio, lasciando l'utente davanti a texture mancanti), tutti registrano qui il loro contenuto
 * — vedi {@link #registerPack} — e questo plugin li fonde in un unico zip, lo serve via un piccolo
 * server HTTP integrato e lo rende obbligatorio al join.
 *
 * <h2>Come registrarsi (da un altro plugin Magix)</h2>
 * Niente dipendenza Maven: ogni plugin del repository si compila per conto suo (vedi
 * {@code .github/workflows/deploy-plugin.yml}), quindi due plugin non possono condividere
 * un'interfaccia a compile-time senza che uno dipenda dal jar dell'altro. Ci si parla per
 * riflessione, con un piccolo hook per-plugin (vedi {@code hook.MagixPackHook} in MagixFactions e
 * MagixAuth, stesso schema gia' usato per Vault/{@code hook.Econ}):
 * <pre>
 * Plugin mp = Bukkit.getPluginManager().getPlugin("MagixPack");
 * mp.getClass().getMethod("registerPack", Plugin.class, Map.class).invoke(mp, this, files);
 * </pre>
 * dove {@code files} e' una {@code Map<String, byte[]>}: percorso dentro lo zip (es.
 * {@code "assets/miamod/textures/item/cosa.png"}) -> contenuto gia' pronto (segnaposto propri gia'
 * risolti dal chiamante, se ne aveva). Il momento giusto per registrarsi e' il proprio
 * {@code onEnable} — con {@code softdepend: [MagixPack]} nel proprio {@code plugin.yml} MagixPack
 * e' gia' abilitato, quindi il metodo esiste ed e' pronto a ricevere la chiamata; lo zip vero e
 * proprio viene costruito solo dopo che TUTTI i plugin hanno finito il loro {@code onEnable} (vedi
 * {@link #onServerLoad}), quindi non serve badare all'ordine fra i vari chiamanti.
 */
public final class MagixPack extends JavaPlugin implements Listener {

    private PackService packService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // I file di configurazione SUL SERVER allineati a quelli del jar: le chiavi nuove
        // compaiono da sole, al loro posto e col loro commento, senza toccare i valori
        // gia' scelti. Il deploy porta solo il jar, quindi senza questo il file del server
        // resterebbe indietro in silenzio (vedi util/ConfigAlign).
        ConfigAlign.alignAll(this);
        reloadConfig();

        packService = new PackService(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new PackListener(this, packService), this);

        MagixPackCommand cmd = new MagixPackCommand(this);
        getCommand("magixpack").setExecutor(cmd);

        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);

        getLogger().info("MagixPack pronto: in attesa delle registrazioni degli altri plugin "
                + "(il pacchetto si costruisce a fine avvio, quando tutti hanno avuto modo di registrarsi).");
    }

    /**
     * Fired ONCE quando il server ha finito di caricare TUTTI i plugin (avvio o {@code /reload}):
     * e' il momento giusto per costruire davvero lo zip, perche' ogni plugin con
     * {@code softdepend: [MagixPack]} ha gia' fatto il suo {@code onEnable} e quindi la sua
     * registrazione (se ne aveva una).
     */
    @EventHandler
    public void onServerLoad(ServerLoadEvent e) {
        packService.start();
    }

    @Override
    public void onDisable() {
        if (packService != null) packService.stop();
    }

    /** Rilegge config.yml e ricostruisce subito il pacchetto (porta/host cambiati richiedono
     *  comunque un riavvio del servizio HTTP, che questo fa da solo). Le registrazioni dei plugin
     *  contributori NON vengono richieste di nuovo: restano quelle gia' in mano. */
    public void reload() {
        ConfigAlign.alignAll(this);
        reloadConfig();
        packService.reloadConfig();
    }

    // --------------------------------------------------------------------------------------------
    // API per gli altri plugin (chiamata via riflessione: vedi Javadoc della classe)
    // --------------------------------------------------------------------------------------------

    /** Registra (o sostituisce) il contenuto di {@code owner} nel pacchetto. */
    public void registerPack(Plugin owner, Map<String, byte[]> files) {
        packService.register(owner, files);
    }

    /** Toglie il contenuto registrato da {@code owner} (va chiamato dal suo {@code onDisable}). */
    public void unregisterPack(Plugin owner) {
        packService.unregister(owner);
    }

    /** true se il pacchetto e' pronto e scaricabile. */
    public boolean isPackAvailable() {
        return packService.isAvailable();
    }

    /** true se il pacchetto e' obbligatorio per giocare (config {@code required}). */
    public boolean isPackRequired() {
        return packService.isRequired();
    }

    /** Manda il pacchetto al giocatore (join, o un comando che lo rimanda a mano). */
    public void sendPackTo(Player p) {
        packService.sendTo(p);
    }

    /** URL pubblico da cui i client scaricano lo zip (null finche' il servizio non e' partito). */
    public String packPublicUrl() {
        return packService.publicUrl();
    }

    // --------------------------------------------------------------------------------------------
    // Guida per lo staff / README
    // --------------------------------------------------------------------------------------------

    private void writeStaffGuide() {
        StaffGuide.create(this, "MagixPack — il pacchetto risorse unico", 5)
                .intro("Un client Minecraft applica UN SOLO pacchetto risorse alla volta. MagixPack esiste "
                        + "per questo: invece che ogni plugin (MagixFactions per la minimap e il logo del "
                        + "tablist, MagixAuth per le schermate di accesso...) spedisca il proprio, tutti "
                        + "registrano qui il loro contenuto e questo plugin li fonde in un unico zip, lo "
                        + "serve da solo via un piccolo server HTTP integrato e lo rende obbligatorio al join. "
                        + "Non ha comandi per i giocatori: e' infrastruttura.")

                .section("Chi ci mette cosa",
                        "MagixFactions registra lo shader della minimap/mappa e il logo del tablist "
                                + "(gia' con i propri segnaposto risolti dal SUO config, es. dimensione della "
                                + "minimap o altezza del logo). MagixAuth registra le schermate di accesso e i "
                                + "tasti del tastierino OTP. Ogni plugin nuovo che vuole aggiungere texture/font "
                                + "propri si registra allo stesso modo, senza toccare questo plugin.",
                        "Il pacchetto vero e proprio si costruisce solo a fine avvio (quando TUTTI i plugin "
                                + "hanno gia' fatto il loro onEnable e quindi la loro registrazione), non subito: "
                                + "cosi' l'ordine di caricamento fra i vari plugin contributori non conta.")

                .section("Comunicazione fra plugin senza dipendenze",
                        "Ogni plugin di questo repository si compila per conto suo (vedi "
                                + "deploy-plugin.yml): due plugin non possono quindi condividere un'interfaccia "
                                + "a compile-time senza che uno dipenda dal jar dell'altro, cosa che romperebbe "
                                + "la build indipendente. La registrazione passa per RIFLESSIONE (stesso schema "
                                + "gia' usato per la chat live del sito verso MagixFactions): l'altro plugin cerca "
                                + "il plugin \"MagixPack\" e ne chiama i metodi pubblici per nome.")

                .section("Pacchetto obbligatorio ed espulsione",
                        "Con required: true (default) il pacchetto viene mandato a TUTTI al join e chi non "
                                + "lo carica — lo rifiuta, il download fallisce, non risponde affatto entro "
                                + "timeout-seconds — viene espulso con un messaggio che glielo spiega. Il "
                                + "permesso magixpack.bypass serve a chi deve entrare senza: riceve comunque il "
                                + "pacchetto, ma non viene mai espulso se non lo carica.",
                        "Se il servizio HTTP non e' raggiungibile (public-host vuoto, porta chiusa sul "
                                + "firewall, server appena riavviato) nessuno viene espulso: il pacchetto "
                                + "semplicemente non e' ancora obbligatorio finche' non torna servibile. E' una "
                                + "rete di sicurezza voluta, contro il rischio di chiudere fuori l'intero server "
                                + "per un problema di rete.")

                .section("Il watchdog",
                        "Ogni watchdog-seconds il plugin scarica da solo il pacchetto in loopback "
                                + "(127.0.0.1): se non risponde piu' come dovrebbe, riavvia il server HTTP da "
                                + "solo, senza bisogno di intervento. Nasce da un guasto vero: dopo ore di uptime "
                                + "il server HTTP smetteva di rispondere e nessuno riusciva piu' a scaricare il "
                                + "pacchetto finche' non si riavviava l'intero server Minecraft.")

                .subcommands("I comandi (/mpack)",
                        "/mpack reload", "Rilegge config.yml (porta, host, messaggi, scadenze) e ricostruisce "
                                + "subito il pacchetto con le registrazioni gia' in mano. Non richiede di nuovo "
                                + "il contenuto agli altri plugin: se e' cambiato un LORO segnaposto, serve "
                                + "ricaricare (o riavviare) quel plugin, non questo.")

                .commands()
                .permissions()
                .settings(
                        "public-host", "IP pubblico da cui i client scaricano il pacchetto. Vuoto = pacchetto disabilitato.",
                        "port", "Porta del server HTTP che serve lo zip: va aperta sul firewall del VPS.",
                        "required", "Se il pacchetto e' obbligatorio (true, default) o facoltativo (false, nessuna espulsione).",
                        "watchdog-seconds", "Ogni quanti secondi si verifica che il pacchetto sia ancora scaricabile.")

                .issue("Un giocatore e' stato espulso appena entrato",
                        "Ha rifiutato il pacchetto, o il download e' fallito, o il client non ha risposto in "
                                + "tempo. Il messaggio che vede gia' spiega il motivo. Se deve entrare comunque "
                                + "(prove, riprese, ospiti), serve il permesso magixpack.bypass.")
                .issue("Mancano texture/font di un plugin dopo un suo aggiornamento",
                        "Quel plugin si registra al proprio onEnable: se e' stato ricaricato a caldo (senza "
                                + "riavviare tutto il server) con softdepend rotto, o se MagixPack e' partito DOPO "
                                + "di lui, la registrazione puo' non essere arrivata. Un riavvio completo del "
                                + "server risolve sempre: rifà tutte le registrazioni nell'ordine giusto.")
                .issue("Il pacchetto non si scarica per nessuno",
                        "Quasi sempre la porta configurata non e' aperta sul firewall del VPS verso "
                                + "l'esterno, oppure public-host e' vuoto/sbagliato. Il log segnala i download "
                                + "falliti a ripetizione con l'URL esatto da verificare.")

                .never("Non registrare nello stesso percorso di zip usato da un altro plugin: MagixPack "
                        + "scarta il secondo con un avviso nel log invece di sovrascrivere, ma il contenuto di "
                        + "chi arriva dopo semplicemente non finisce nel pacchetto.")
                .never("Non disattivare required senza sapere perche': i plugin che si aspettano il loro "
                        + "shader/font (es. la minimap di MagixFactions) restano senza, in silenzio, per chi non "
                        + "carica il pacchetto facoltativo.")
                .write();
    }
}
