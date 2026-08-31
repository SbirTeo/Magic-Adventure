package com.teolo.magixentities;

import com.teolo.magixentities.command.MeCommand;
import com.teolo.magixentities.lang.Messages;
import com.teolo.magixentities.listener.NpcListener;
import com.teolo.magixentities.manage.ActionRunner;
import com.teolo.magixentities.manage.EquipMenu;
import com.teolo.magixentities.manage.LookManager;
import com.teolo.magixentities.manage.MirrorManager;
import com.teolo.magixentities.manage.NpcManager;
import com.teolo.magixentities.util.GuidaStaff;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * MagixEntities - entita' statiche create da comando (stile Citizens).
 * Il tipo "player" usa l'entita' vanilla Mannequin: skin da profilo giocatore,
 * displayname sopra la testa, nessun pacchetto ne' dipendenza esterna.
 */
public final class MagixEntities extends JavaPlugin {

    private NpcManager npcs;
    private MirrorManager mirror;
    private Messages messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();
        // Puro I/O su file: non deve bloccare il tick di avvio.
        // Il README nella cartella del plugin non si copia piu' dal jar: lo genera
        // GuidaStaff insieme al capitolo per il sito, cosi' i due non possono divergere.
        // Capitolo della guida per amministratori sul sito (vedi plugins-src/GUIDA-STAFF.md).
        Bukkit.getScheduler().runTaskAsynchronously(this, this::scriviGuidaStaff);

        messages = new Messages(this);
        npcs = new NpcManager(this);
        mirror = new MirrorManager(this, npcs);
        npcs.setMirror(mirror);
        npcs.load();
        getLogger().info("Entita' caricate da entities.yml: " + npcs.all().size());

        ActionRunner actions = new ActionRunner(this);
        EquipMenu equipMenu = new EquipMenu(this, npcs, mirror, messages);
        PluginCommand cmd = getCommand("magixentities");
        if (cmd != null) cmd.setExecutor(new MeCommand(this, npcs, mirror, equipMenu, messages));
        getServer().getPluginManager().registerEvents(new NpcListener(this, npcs, mirror, actions), this);
        getServer().getPluginManager().registerEvents(equipMenu, this);

        // Primo controllo al tick successivo: i chunk di spawn sono gia' caricati e
        // gli altri plugin hanno finito di avviarsi.
        Bukkit.getScheduler().runTask(this, () -> {
            mirror.purgeStray();
            npcs.ensureAll();
        });
        mirror.start();
        new LookManager(this, npcs, mirror).start();

        int seconds = getConfig().getInt("check-interval-seconds", 20);
        if (seconds > 0) {
            long ticks = seconds * 20L;
            Bukkit.getScheduler().runTaskTimer(this, npcs::ensureAll, ticks, ticks);
        }
    }

    @Override
    public void onDisable() {
        // Le copie mirror non sono persistenti, ma vanno tolte subito: durante un reload dei
        // plugin resterebbero nel mondo senza nessuno che le gestisce.
        if (mirror != null) mirror.clearAll();
        if (npcs != null) npcs.save();
    }

    /** Riscrive plugins/MagixEntities/README.md ad ogni avvio (README = unica fonte, dentro il jar). */
    private void writeReadme() {
        try (InputStream in = getResource("README.md")) {
            if (in == null) {
                getLogger().warning("README.md non incluso nel jar: README runtime non generato.");
                return;
            }
            Path out = new File(getDataFolder(), "README.md").toPath();
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            getLogger().info("README aggiornato (v" + getPluginMeta().getVersion() + ").");
        } catch (Exception e) {
            getLogger().warning("Impossibile scrivere il README: " + e.getMessage());
        }
    }

    // ------------------------------------------------- GUIDA PER LO STAFF

    /** Capitolo di MagixEntities nella guida del gestionale: si riscrive a ogni avvio. */
    private void scriviGuidaStaff() {
        GuidaStaff.crea(this, "MagixEntities — NPC ed entità da comando", 70)
                // Numeri presi dal config vero: cambiando una chiave, questo capitolo
                // sulla guida del gestionale cambia da solo (vedi util/ValoriConfig).
                .valori(new com.teolo.magixentities.util.ValoriConfig(this))
                .intro("Crea entità ferme dove servono: guide allo spawn, mercanti, statue di giocatori. Fa il "
                        + "lavoro che di solito fa Citizens, ma con le entità vanilla e senza ProtocolLib.")

                .sezione("Dove vivono le entità",
                        "Sono scritte in entities.yml e ricreate a ogni avvio: se qualcuno ne uccide una, o un "
                                + "chunk fa i capricci, al riavvio torna dov'era. Il file è la verità, il mondo è "
                                + "solo la copia.",
                        "All'avvio il plugin fa due cose in fila: toglie le copie che non riconosce e ricrea "
                                + "quelle che mancano. Per questo non serve intervenire a mano quasi mai.")

                .sezione("Il tipo «player»",
                        "Usa il Mannequin vanilla con la skin presa dal profilo del giocatore indicato: niente "
                                + "pacchetti finti, niente ProtocolLib, niente da riscrivere a ogni versione di "
                                + "Minecraft.",
                        "Se la skin non compare, il profilo non è stato ancora risolto: succede con nomi mai "
                                + "visti dal server. Ricrea l'entità e riprova.",
                        "Una skin trovata resta in memoria: non viene richiesta di nuovo a ogni controllo. Se il "
                                + "nome non esiste su minecraft.net il plugin lo scrive in console una volta sola e "
                                + "riprova ogni mezz'ora (skin.retry-minutes).")

                .sezione("Aspetto, equipaggiamento e sguardo",
                        "Nome visibile, equipaggiamento e posa si cambiano dai comandi o dal menu in gioco, senza "
                                + "toccare il file.",
                        "Le entità possono seguire con lo sguardo chi passa: è quello che le fa sembrare vive. "
                                + "Si accende per singola entità.")

                .sezione("Azioni al clic",
                        "A un'entità si può attaccare un'azione: eseguire un comando, aprire un menu, mandare un "
                                + "messaggio. È il modo con cui una guida allo spawn può portare un nuovo giocatore "
                                + "dove serve senza che debba sapere nessun comando.")

                .comandiDettagliati()
                .comandi()
                .permessi()

                .guasto("/me apre l'emote invece del plugin",
                        "È l'emote di Minecraft, e CMI ne registra una sua. Usa /mentities, /ment oppure la forma "
                                + "esplicita /magixentities:me.")
                .guasto("Un'entità è sparita",
                        "/ment respawn <nome> la ricrea. Se ne mancano molte, /ment reload rilegge tutto il file.")
                .guasto("Ne sono comparse due uguali",
                        "È un doppione rimasto nel mondo: /ment purge toglie le copie che il plugin non riconosce.")
                .guasto("In console torna «Texture skin non trovate per ...»",
                        "Quel nome non ha una skin su minecraft.net (di solito è un nome inventato). L'entità "
                                + "resta con la skin standard: cambiale nome con /ment skin <entità> <nick> oppure "
                                + "ignora il messaggio, che compare una volta sola per nome.")
                .guasto("La skin di un'entità player è quella sbagliata",
                        "Il profilo viene risolto dal nome: se il nome è stato cambiato su minecraft.net, ricrea "
                                + "l'entità.")

                // Tabella con la colonna "Ora vale": i valori li legge dal config al momento di
                // pubblicare, quindi restano allineati da soli a quello che il server fa davvero.
                .impostazioni(
                        "check-interval-seconds", "Ogni quanti secondi il plugin controlla che le entità esistano ancora e le ricrea se sono sparite (0 = mai).",
                        "defaults.invulnerable", "Le entità appena create non subiscono danni (fuoco, mob, cadute, giocatori).",
                        "defaults.nametag", "Mostra il nome sopra la testa delle entità appena create.",
                        "defaults.ai", "Lascia attiva l'intelligenza artificiale: se true l'entità cammina e insegue.",
                        "defaults.gravity", "Le entità appena create subiscono la gravità.",
                        "defaults.collidable", "Le entità appena create bloccano il passaggio dei giocatori.")

                .mai("Non modificare entities.yml mentre il server gira: al primo salvataggio del plugin le tue "
                        + "modifiche vengono sovrascritte.")
                .mai("Non piazzare entità in chunk che nessuno tiene caricati aspettandoti che facciano qualcosa: "
                        + "fuori dai chunk caricati non esistono.")
                .scrivi();
    }
}
