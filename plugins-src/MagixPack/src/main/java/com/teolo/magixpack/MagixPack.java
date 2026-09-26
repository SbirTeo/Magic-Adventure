package com.teolo.magixpack;

import com.teolo.magixpack.command.MagixPackCommand;
import com.teolo.magixpack.furniture.FurnitureListener;
import com.teolo.magixpack.glyph.GlyphCatalog;
import com.teolo.magixpack.item.ItemCatalog;
import com.teolo.magixpack.lang.Messages;
import com.teolo.magixpack.pack.PackListener;
import com.teolo.magixpack.pack.PackService;
import com.teolo.magixpack.util.ConfigAlign;
import com.teolo.magixpack.util.StaffGuide;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server UNICO del resource pack: un client Minecraft ne applica solo uno alla volta, quindi
 * invece che ogni plugin ne spedisca uno proprio (che il client scaricherebbe e scarterebbe in
 * silenzio, lasciando l'utente davanti a texture mancanti), tutti registrano qui il loro contenuto
 * — vedi {@link #registerPack} — e questo plugin li fonde in un unico zip, lo serve via un piccolo
 * server HTTP integrato e lo rende obbligatorio al join.
 *
 * <p>Oltre a fondere, MagixPack genera anche DUE cataloghi propri (staff-editable, senza scrivere
 * codice): oggetti custom con texture/modello proprio ({@code items.yml}, vedi {@link ItemCatalog}
 * e {@link #customItem}) e icone custom via font per chat/tablist ({@code glyphs.yml}, vedi
 * {@link GlyphCatalog} e {@link #customGlyph}). Dettagli in README.md.
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
    private Messages messages;
    private ItemCatalog itemCatalog;
    private GlyphCatalog glyphCatalog;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // I file di configurazione SUL SERVER allineati a quelli del jar: le chiavi nuove
        // compaiono da sole, al loro posto e col loro commento, senza toccare i valori
        // gia' scelti. Il deploy porta solo il jar, quindi senza questo il file del server
        // resterebbe indietro in silenzio (vedi util/ConfigAlign).
        ConfigAlign.alignAll(this);
        reloadConfig();
        messages = new Messages(this);

        packService = new PackService(this);
        itemCatalog = new ItemCatalog(this);
        glyphCatalog = new GlyphCatalog(this);
        loadCatalogsAndRegister();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new PackListener(this, packService), this);
        getServer().getPluginManager().registerEvents(new FurnitureListener(this, itemCatalog), this);

        MagixPackCommand cmd = new MagixPackCommand(this, messages);
        getCommand("magixpack").setExecutor(cmd);
        getCommand("magixpack").setTabCompleter(cmd);

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
     *  contributori NON vengono richieste di nuovo: restano quelle gia' in mano.
     *
     * <p>Rimanda anche il pacchetto a chi e' GIA' online: senza, un reload cambierebbe lo zip sul
     * server ma nessun client gia' connesso lo saprebbe mai (il pacchetto si manda solo al join).
     * Cosi' invece {@code /mpack reload} basta davvero per vedere le modifiche, come in Oraxen —
     * F3+T dal client NON serve a questo: ricarica solo i pacchetti gia' scaricati sul disco, non
     * ricontatta il server. */
    public void reload() {
        ConfigAlign.alignAll(this);
        reloadConfig();
        messages.reload();
        loadCatalogsAndRegister();
        packService.reloadConfig();
        if (packService.isAvailable()) {
            for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) packService.sendTo(p);
        }
    }

    /** Rilegge items.yml/glyphs.yml (e le rispettive texture) e registra il risultato nel
     *  pacchetto sotto il PROPRIO nome (owner = MagixPack stesso, stessa API usata dagli altri
     *  plugin): un'unica {@code register()}, perche' due chiamate con lo stesso owner si
     *  sovrascriverebbero a vicenda invece di sommarsi (vedi {@link PackService#register}). */
    private void loadCatalogsAndRegister() {
        itemCatalog.reload();
        glyphCatalog.reload();
        Map<String, byte[]> files = new LinkedHashMap<>(itemCatalog.packFiles());
        files.putAll(glyphCatalog.packFiles());
        packService.register(this, files);
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

    /** Il catalogo degli oggetti custom (items.yml): usato dal comando {@code /mpack item}. */
    public ItemCatalog itemCatalog() {
        return itemCatalog;
    }

    /** Il catalogo delle icone custom via font (glyphs.yml): usato dal comando {@code /mpack glyph}. */
    public GlyphCatalog glyphCatalog() {
        return glyphCatalog;
    }

    /** L'oggetto custom di items.yml, pronto da dare a un giocatore; null se {@code id} non e' nel
     *  catalogo (texture mancante compresa: vedi {@link ItemCatalog#reload}). Tipo di ritorno
     *  Bukkit "vero": un chiamante per riflessione lo usa senza bisogno del jar di MagixPack sul
     *  proprio classpath, come per {@link #sendPackTo}. */
    public ItemStack customItem(String id) {
        return itemCatalog.build(id);
    }

    /** Il Component Adventure di un'icona custom di glyphs.yml (font gia' impostato), pronto da
     *  concatenare in un messaggio; null se {@code id} non e' nel catalogo. Non scrivere MAI il
     *  carattere a mano: il punto di codice puo' cambiare se cambia il catalogo (vedi
     *  {@link GlyphCatalog}). */
    public Component customGlyph(String id) {
        return glyphCatalog.component(id);
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

                .section("Personalizzare il pacchetto a mano (senza toccare codice)",
                        "La cartella plugins/MagixPack/overrides/ (creata vuota gia' al primo avvio) e' per "
                                + "chi vuole aggiungere o sostituire un file del pacchetto senza scrivere un "
                                + "plugin: ogni file li' dentro entra nello zip allo stesso percorso relativo a "
                                + "quella cartella — plugins/MagixPack/overrides/assets/minecraft/textures/gui/"
                                + "container/inventory.png diventa assets/minecraft/textures/gui/container/"
                                + "inventory.png nel pacchetto — e VINCE sempre su qualunque contenuto gia' "
                                + "presente (file propri di MagixPack o registrato da un plugin). Stesso "
                                + "principio di Oraxen: le risorse stanno nella cartella DATI del plugin, non nel "
                                + "jar, e basta un file + /mpack reload per vederle in gioco, senza ricompilare o "
                                + "ridistribuire niente.",
                        "E' il posto giusto per una texture vanilla d'atmosfera (es. lo sfondo dell'inventario) "
                                + "o per provare qualcosa prima di deciderne l'appartenenza definitiva a un "
                                + "plugin. Attenzione pero' alle texture vanilla con un LAYOUT fisso (l'inventario "
                                + "e' 176x166 px con le caselle in posizioni scritte nel client, non nell'immagine): "
                                + "un file con proporzioni diverse viene scalato lo stesso a quella dimensione, e "
                                + "il risultato puo' venire illeggibile se non e' stato disegnato apposta per "
                                + "quel formato.")

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
                        "/mpack reload", "Rilegge config.yml (porta, host, messaggi, scadenze), items.yml e "
                                + "glyphs.yml, ricostruisce subito il pacchetto con le registrazioni gia' in "
                                + "mano e lo RIMANDA a chi e' gia' online (senza, un client gia' connesso non "
                                + "saprebbe mai che lo zip e' cambiato: il pacchetto si manda da solo solo al "
                                + "join). F3+T dal client NON basta: ricarica solo i pacchetti gia' scaricati "
                                + "sul disco, non ricontatta il server. Non richiede di nuovo il contenuto agli "
                                + "altri plugin: se e' cambiato un LORO segnaposto, serve ricaricare (o "
                                + "riavviare) quel plugin, non questo.",
                        "/mpack item give <id> [giocatore]", "Da' un oggetto custom di items.yml (texture e "
                                + "modello propri, come Oraxen). Senza destinatario lo da' a chi lancia il "
                                + "comando.",
                        "/mpack item list", "Elenca gli oggetti custom caricati da items.yml in questo momento "
                                + "(quelli con la texture mancante in items/ non compaiono: vedi la console).",
                        "/mpack glyph list", "Elenca le icone custom di glyphs.yml col loro punto di codice "
                                + "attuale (font magixpack:icons) — utile per verificare cosa e' disponibile "
                                + "prima di usarle da un altro plugin.")

                .section("Oggetti custom (items.yml)",
                        "Catalogo staff-editable per oggetti con texture E MODELLO propri, non un semplice "
                                + "glifo: un vero modello 2D generato (parent item/generated), sopra un item "
                                + "base di Minecraft che decide solo le meccaniche (danno, durabilita', "
                                + "impilabilita'...), mai l'aspetto. Basta un file in plugins/MagixPack/items/"
                                + "<id>.png + una voce in items.yml (material, name, lore) + /mpack reload: il "
                                + "plugin genera da solo il JSON del modello, non serve scriverlo a mano. Per un "
                                + "modello 3D vero basta items/<id>-model.json. Con furniture: true l'oggetto si "
                                + "puo' anche piazzare per terra (tasto destro su un blocco, shift richiesto per "
                                + "default - furniture-shift-required: false lo toglie - si rompe attaccandolo) "
                                + "col suo aspetto vero; furniture-solid: true gli da' collisione vera. Vedi il "
                                + "README per i dettagli.")

                .section("Icone custom via font (glyphs.yml)",
                        "Per simboli dentro un messaggio di chat o nel tablist, MAI per gli oggetti (quelli "
                                + "hanno il loro modello vero, vedi sopra). Font PROPRIO (magixpack:icons), mai "
                                + "minecraft:default: quel file vanilla il client lo sostituisce per intero, non "
                                + "lo fonde, quindi toccarlo direttamente rischierebbe di cancellare tutti i "
                                + "provider vanilla (e' il motivo per cui l'esperimento della cornice, prima di "
                                + "questa funzione, e' stato tolto). Il punto di codice di ogni icona lo assegna "
                                + "il plugin da solo, in ordine alfabetico: puo' cambiare se il catalogo cambia, "
                                + "quindi un altro plugin la richiama sempre per NOME tramite l'API "
                                + "(MagixPack.customGlyph(\"id\")), mai scrivendo il carattere a mano.")

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
                .issue("Ho messo un file in overrides/ ma non lo vedo in gioco",
                        "Serve /mpack reload (o un riavvio) dopo aver aggiunto/modificato un file: la "
                                + "cartella viene riletta solo alla costruzione del pacchetto, non in "
                                + "automatico a ogni scrittura su disco.")
                .issue("Ho aggiunto un oggetto/icona in items.yml o glyphs.yml ma /mpack item list (o glyph "
                        + "list) non lo mostra",
                        "Quasi sempre manca la texture: un oggetto senza plugins/MagixPack/items/<id>.png (o "
                                + "un'icona senza glyphs/<id>.png) viene ignorato con un avviso in console, non "
                                + "un errore bloccante. Controlla che il nome del file combaci ESATTAMENTE con "
                                + "la chiave in items.yml/glyphs.yml (maiuscole comprese), poi /mpack reload.")
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
