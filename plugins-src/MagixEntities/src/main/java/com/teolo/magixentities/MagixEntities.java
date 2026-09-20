package com.teolo.magixentities;

import com.teolo.magixentities.util.ConfigAlign;
import com.teolo.magixentities.command.MeCommand;
import com.teolo.magixentities.hook.MagixCosmeticsHook;
import com.teolo.magixentities.lang.Messages;
import com.teolo.magixentities.listener.NpcListener;
import com.teolo.magixentities.manage.ActionRunner;
import com.teolo.magixentities.manage.EquipMenu;
import com.teolo.magixentities.manage.LookManager;
import com.teolo.magixentities.manage.MirrorManager;
import com.teolo.magixentities.manage.NpcManager;
import com.teolo.magixentities.util.StaffGuide;
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
        // I file di configurazione SUL SERVER allineati a quelli del jar: le chiavi nuove
        // compaiono da sole, al loro posto e col loro commento, senza toccare i valori
        // gia' scelti. Il deploy porta solo il jar, quindi senza questo il file del server
        // resterebbe indietro in silenzio (vedi util/ConfigAlign).
        ConfigAlign.alignAll(this);
        reloadConfig();
        getDataFolder().mkdirs();
        // Puro I/O su file: non deve bloccare il tick di avvio.
        // Il README nella cartella del plugin non si copia piu' dal jar: lo genera
        // StaffGuide insieme al capitolo per il sito, cosi' i due non possono divergere.
        // Capitolo della guida per amministratori sul sito (vedi plugins-src/GUIDA-STAFF.md).
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);

        messages = new Messages(this);
        npcs = new NpcManager(this);
        mirror = new MirrorManager(this, npcs);
        npcs.setMirror(mirror);
        npcs.load();
        getLogger().info("Entita' caricate da entities.yml: " + npcs.all().size());

        // Per riflessione, non e' una dipendenza Maven (vedi hook.MagixCosmeticsHook): se
        // MagixCosmetics manca o non e' abilitato, mirror.start() semplicemente non fa partire
        // il task dell'aureola sui mirror. Va fatto PRIMA di mirror.start().
        MagixCosmeticsHook.setup(this);

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
        if (mirror != null) {
            mirror.clearAll();
            mirror.stopHalo();
        }
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
    private void writeStaffGuide() {
        StaffGuide.create(this, "MagixEntities — NPC ed entità da comando", 70)
                // Numeri presi dal config vero: cambiando una chiave, questo capitolo
                // sulla guida del gestionale cambia da solo (vedi util/ConfigValues).
                .values(new com.teolo.magixentities.util.ConfigValues(this))
                .intro("Crea entità ferme dove servono: guide allo spawn, mercanti, statue di giocatori. Fa il "
                        + "lavoro che di solito fa Citizens, ma con le entità vanilla e senza ProtocolLib.")

                .section("Dove vivono le entità",
                        "Sono scritte in entities.yml e ricreate a ogni avvio: se qualcuno ne uccide una, o un "
                                + "chunk fa i capricci, al riavvio torna dov'era. Il file è la verità, il mondo è "
                                + "solo la copia.",
                        "All'avvio il plugin fa due cose in fila: toglie le copie che non riconosce e ricrea "
                                + "quelle che mancano. Per questo non serve intervenire a mano quasi mai.")

                .section("Il tipo «player»",
                        "Usa il Mannequin vanilla con la skin presa dal profilo del giocatore indicato: niente "
                                + "pacchetti finti, niente ProtocolLib, niente da riscrivere a ogni versione di "
                                + "Minecraft.",
                        "Se la skin non compare, il profilo non è stato ancora risolto: succede con nomi mai "
                                + "visti dal server. Ricrea l'entità e riprova.",
                        "Una skin trovata resta in memoria: non viene richiesta di nuovo a ogni controllo. Se il "
                                + "nome non esiste su minecraft.net il plugin lo scrive in console una volta sola e "
                                + "riprova ogni mezz'ora (skin.retry-minutes).")

                .section("Skin e nome a specchio",
                        "Con `/mentities skin <nome> mirror` (solo tipo player) ogni giocatore vede l'entità con la "
                                + "PROPRIA skin; con `/mentities displayname <nome> mirror` (qualsiasi tipo) ognuno "
                                + "vede il PROPRIO nome sopra la testa. Tecnicamente l'entità vera resta nascosta e per "
                                + "ogni giocatore vicino ne nasce una copia personalizzata, visibile solo a lui.",
                        "Se il plugin MagixCosmetics è installato e abilitato, la copia a specchio skin di un VIP "
                                + "riproduce sopra la testa anche la sua stessa aureola colorata — ma solo se in quel "
                                + "momento lui ce l'ha davvero attiva (permesso, colore scelto, non spenta, non in "
                                + "combattimento). Senza MagixCosmetics non succede nulla, senza bisogno di configurare "
                                + "niente in più.")

                .section("Aspetto, equipaggiamento e sguardo",
                        "Nome visibile, equipaggiamento e posa si cambiano dai comandi o dal menu in gioco, senza "
                                + "toccare il file.",
                        "Il nome sopra la testa si può anche nascondere del tutto senza perdere il testo scelto: "
                                + "`/mentities displayname <nome> off` lo spegne, `... on` lo riaccende — stessa "
                                + "opzione di `/mentities set <nome> nametag`, solo più comoda da qui. Su una "
                                + "statua (tipo player) spegne anche la targhetta vanilla del profilo che il "
                                + "client disegna da solo mirandola da vicino, indipendente dal nome sopra la "
                                + "testa: si toglie il nome dal profilo stesso, non solo dal cartello.",
                        "Le entità possono seguire con lo sguardo chi passa: è quello che le fa sembrare vive. "
                                + "Si accende per singola entità.")

                .section("Azioni al clic",
                        "A un'entità si può attaccare un'azione: eseguire un comando, aprire un menu, mandare un "
                                + "messaggio. È il modo con cui una guida allo spawn può portare un nuovo giocatore "
                                + "dove serve senza che debba sapere nessun comando.",
                        "Ogni azione è una riga aggiunta con /mentities cmd <nome> add. Il prefisso decide chi la "
                                + "esegue: console: la lancia dalla console (per i comandi che il giocatore non "
                                + "potrebbe usare), msg: manda un messaggio in chat solo a chi clicca (senza il "
                                + "[nome] davanti che mette /say), nessun prefisso la fa eseguire dal giocatore. "
                                + "Nel testo di msg: i token \\n e %nl% vanno a capo, così un solo messaggio può "
                                + "occupare più righe.")

                .detailedCommands()
                .commands()
                .permissions()

                .issue("Ho cambiato una chiave del config nel repo e sul server non succede niente",
                        "Il deploy porta il jar, non i config: il file nella cartella del plugin sul server non viene toccato, ed e' quello che il plugin legge. Il valore nel jar vale solo per le chiavi che li' MANCANO. Quindi un valore gia' presente si cambia sul server (a mano, o col workflow deploy-plugin-config.yml), non nel repo. Del resto si occupa il plugin, a ogni avvio e a ogni reload: aggiunge le chiavi nuove al loro posto col loro commento, applica le rinomine portandosi dietro il valore che avevi scelto, e toglie le righe morte che il codice non legge piu' dai file a schema fisso, cioe' tutti tranne i cataloghi (i menu e le sanzioni no: li' le voci in piu' sono tue). Prima di ogni modifica fa una copia del file accanto all'originale, col nome che finisce in .bak-<data>, e nel log scrive che cosa ha cambiato.")
                .issue("/me apre l'emote invece del plugin",
                        "È l'emote di Minecraft, e CMI ne registra una sua. Usa /mentities, /ment oppure la forma "
                                + "esplicita /magixentities:me.")
                .issue("Un'entità è sparita",
                        "/ment respawn <nome> la ricrea. Se ne mancano molte, /ment reload rilegge tutto il file.")
                .issue("Creo un'entità, il plugin dice «Creata» ma non si vede",
                        "La nascita di un'entità è un evento che gli altri plugin possono annullare: se qualcuno "
                                + "dice di no, la definizione resta salvata ma nel mondo non entra niente. Il "
                                + "plugin se ne accorge e lo dice (console, chat, pallino giallo in /ment list e "
                                + "/ment info). Il caso quasi sempre è WorldGuard: con il flag «mob-spawning: "
                                + "deny» su una regione (spesso __global__ del mondo dello spawn) e "
                                + "«mobs.block-plugin-spawning: true» nel suo config.yml vengono bloccate anche le "
                                + "entità create dai plugin. Metti block-plugin-spawning a false e fai /wg reload: "
                                + "i mob naturali restano bloccati dal flag, le nostre entità passano. Le entità "
                                + "già esistenti non se ne accorgono perché vivono nel salvataggio del mondo e non "
                                + "vengono ricreate — per questo il problema si vede solo creandone di nuove. "
                                + "Sistemata la protezione non serve rifare niente: il controllo periodico ricrea "
                                + "da sé le entità rimaste in sospeso (o subito con /ment respawn <nome>).")
                .issue("Ne sono comparse due uguali",
                        "È un doppione rimasto nel mondo: /ment purge toglie le copie che il plugin non riconosce.")
                .issue("In console torna «Texture skin non trovate per ...»",
                        "Quel nome non ha una skin su minecraft.net (di solito è un nome inventato). L'entità "
                                + "resta con la skin standard: cambiale nome con /ment skin <entità> <nick> oppure "
                                + "ignora il messaggio, che compare una volta sola per nome.")
                .issue("La skin di un'entità player è quella sbagliata",
                        "Il profilo viene risolto dal nome: se il nome è stato cambiato su minecraft.net, ricrea "
                                + "l'entità.")

                // Tabella con la colonna "Ora vale": i valori li legge dal config al momento di
                // pubblicare, quindi restano allineati da soli a quello che il server fa davvero.
                .settings(
                        "check-interval-seconds", "Ogni quanti secondi il plugin controlla che le entità esistano ancora e le ricrea se sono sparite (0 = mai).",
                        "defaults.invulnerable", "Le entità appena create non subiscono danni (fuoco, mob, cadute, giocatori).",
                        "defaults.nametag", "Mostra il nome sopra la testa delle entità appena create.",
                        "defaults.ai", "Lascia attiva l'intelligenza artificiale: se true l'entità cammina e insegue.",
                        "defaults.gravity", "Le entità appena create subiscono la gravità.",
                        "defaults.collidable", "Le entità appena create bloccano il passaggio dei giocatori.",
                        "mirror.halo.enabled", "Aureola VIP sulle copie a specchio skin (richiede MagixCosmetics installato e abilitato).",
                        "mirror.halo.interval-ticks", "Ogni quanti tick il puntino dell'aureola sui mirror avanza lungo il cerchio.")

                .never("Non modificare entities.yml mentre il server gira: al primo salvataggio del plugin le tue "
                        + "modifiche vengono sovrascritte.")
                .never("Non piazzare entità in chunk che nessuno tiene caricati aspettandoti che facciano qualcosa: "
                        + "fuori dai chunk caricati non esistono.")
                .write();
    }
}
