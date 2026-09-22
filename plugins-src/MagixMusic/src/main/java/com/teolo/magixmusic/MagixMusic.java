package com.teolo.magixmusic;

import com.teolo.magixmusic.command.MagixMusicCommand;
import com.teolo.magixmusic.lang.Messages;
import com.teolo.magixmusic.radio.RadioListener;
import com.teolo.magixmusic.radio.RadioService;
import com.teolo.magixmusic.radio.VolumeStore;
import com.teolo.magixmusic.util.ConfigAlign;
import com.teolo.magixmusic.util.ConfigValues;
import com.teolo.magixmusic.util.StaffGuide;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MagixMusic — radio musicale sincronizzata allo spawn.
 *
 * <p>Un "jukebox" invisibile che suona a ciclo i dischi di Minecraft ai giocatori nella zona spawn:
 * tutti sullo stesso brano nello stesso momento (li sincronizza il server), con il suono ancorato allo
 * spawn che cala con la distanza. Ogni giocatore lo regola o lo spegne per sé con /radio.
 *
 * <p>Nasce come modulo a sé (staccato da MagixFactions) così la musica dello spawn non dipende dalla
 * modalità di gioco e può essere spostata/spenta senza toccare le fazioni.
 */
public final class MagixMusic extends JavaPlugin {

    private Messages messages;
    private VolumeStore volumes;
    private RadioService radio;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // I file di configurazione SUL SERVER allineati a quelli del jar: le chiavi nuove compaiono da
        // sole, al loro posto e col loro commento, senza toccare i valori già scelti. Il deploy porta
        // solo il jar, quindi senza questo il file del server resterebbe indietro (vedi util/ConfigAlign).
        ConfigAlign.alignAll(this);
        reloadConfig();
        getDataFolder().mkdirs();
        // Capitolo della guida per lo staff sul sito + README nella cartella del plugin. Puro I/O su file.
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);

        messages = new Messages(this);
        volumes = new VolumeStore(this);
        radio = new RadioService(this, volumes);
        radio.start();
        getServer().getPluginManager().registerEvents(new RadioListener(this, radio), this);

        PluginCommand cmd = getCommand("magixmusic");
        if (cmd != null) {
            MagixMusicCommand executor = new MagixMusicCommand(this, messages, radio, volumes);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        // Placeholder %magixmusic_...% (volume, barra, brano, stato): li usa il menu di MagixMenus per
        // mostrare la radio in diretta. Softdepend: se PlaceholderAPI non c'e', la radio funziona lo
        // stesso, solo il menu non vede i valori live.
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new com.teolo.magixmusic.hook.Placeholders(this, radio).register();
                getLogger().info("Placeholder %magixmusic_...% registrati su PlaceholderAPI.");
            } catch (Throwable t) {
                getLogger().warning("Registrazione dei placeholder fallita: " + t.getMessage());
            }
        }

        // Se c'e' MagixMenus, installa da solo il menu grafico /musica (vedi installMenu).
        installMenu();

        getLogger().info("MagixMusic avviato.");
    }

    /**
     * Installa il menu grafico {@code /musica} SOLO se MagixMenus è presente: copia la risorsa
     * {@code menus/musica.yml} del jar nella cartella menu di MagixMenus (se non c'è già) e ricarica i
     * menu, così il comando si registra senza riavvio. Senza MagixMenus non fa nulla — restano i comandi
     * {@code /radio}. Non sovrascrive un file già presente: le modifiche dello staff al menu restano.
     */
    private void installMenu() {
        org.bukkit.plugin.Plugin menus = Bukkit.getPluginManager().getPlugin("MagixMenus");
        if (menus == null) return; // MagixMenus non c'è: niente menu, solo i comandi /radio
        try {
            java.io.File dir = new java.io.File(menus.getDataFolder(), "menus");
            if (!dir.exists() && !dir.mkdirs()) {
                getLogger().warning("Non riesco a creare " + dir.getPath() + ": menu /musica non installato.");
                return;
            }
            java.io.File target = new java.io.File(dir, "musica.yml");
            if (target.exists()) return; // già presente: rispetto le eventuali modifiche dello staff
            try (java.io.InputStream in = getResource("menus/musica.yml")) {
                if (in == null) { getLogger().warning("menus/musica.yml non incluso nel jar."); return; }
                java.nio.file.Files.copy(in, target.toPath());
            }
            getLogger().info("MagixMenus rilevato: installato il menu /musica, ricarico i menu.");
            // Ricarica i menu di MagixMenus così /musica si registra subito, senza aspettare un riavvio.
            Bukkit.getScheduler().runTask(this, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "menus reload"));
        } catch (Exception e) {
            getLogger().warning("Installazione del menu /musica fallita: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (radio != null) radio.shutdown();
    }

    /** Ricarica config.yml e messages.yml e riavvia la radio con le nuove impostazioni (/radio reload). */
    public void reloadAll() {
        ConfigAlign.alignAll(this);
        reloadConfig();
        if (messages != null) messages.reload();
        if (radio != null) radio.reload();
    }

    // ------------------------------------------------- GUIDA PER LO STAFF

    /** Capitolo di MagixMusic nella guida del gestionale. Comandi, permessi e valori del config non si
     *  ricopiano: li legge da solo. Vedi plugins-src/GUIDA-STAFF.md. */
    private void writeStaffGuide() {
        StaffGuide.create(this, "MagixMusic — radio musicale a spawn", 70)
                // Numeri presi dal config vero: cambiando una chiave, questo capitolo cambia da solo.
                .values(new ConfigValues(this))
                .intro("Allo spawn c'è una radio che suona a ciclo i dischi di Minecraft. Tutti quelli nella "
                        + "zona sentono lo stesso brano nello stesso momento; ognuno può alzarla, abbassarla o "
                        + "spegnerla per sé con /radio.")

                .section("Come funziona",
                        "Il server fa da orologio comune: a ogni brano manda a tutti i giocatori nella zona lo "
                                + "stesso stop+play, quindi sono sincronizzati al secondo. Il suono è ancorato allo "
                                + "spawn del mondo «{{cfg:world}}» e cala con la distanza, come un altoparlante: si "
                                + "sente entro {{cfg:radius}} blocchi, fuori dalla zona e negli altri mondi no.",
                        "La scaletta gira all'infinito: finito un brano parte il successivo dopo i suoi seconds. Il "
                                + "ciclo totale è la somma delle durate. Cambi a caldo con /radio reload: la radio "
                                + "riparte dall'inizio della scaletta.")

                .section("I controlli del giocatore",
                        "Ogni giocatore regola la radio per sé con /radio: on/off, up/down o un numero 0-100. È una "
                                + "preferenza PERSONALE salvata (file volumes.yml nella cartella del plugin), di serie "
                                + "{{cfg:default-volume}}. Spegnerla mette il suo volume a 0 e non tocca gli altri. "
                                + "Aperta a tutti (permesso magixmusic.use, di serie sì).")

                .section("Il limite del \"da capo\" (non è un guasto)",
                        "Un suono di Minecraft parte sempre dall'inizio: non si può \"riprendere\" a metà. Quindi chi "
                                + "entra a brano già iniziato lo sente dall'inizio (se play-on-join è attivo), non dal "
                                + "secondo in corso; al primo cambio di brano si riallinea con tutti. Se un giocatore "
                                + "chiede «perché sento la canzone da capo mentre gli altri sono avanti», la risposta è "
                                + "questa: è un limite del gioco, non del plugin.")

                .section("Il menu grafico e i placeholder",
                        "Oltre al comando c'è un menu grafico: /musica (o /radiomenu) apre una finestra con i "
                                + "pulsanti per alzare, abbassare, accendere o spegnere la radio, i preset di volume e "
                                + "la barra che mostra il livello in diretta. Il menu compare **solo se MagixMenus è "
                                + "installato**: in quel caso MagixMusic lo installa da solo all'avvio (copia il file in "
                                + "plugins/MagixMenus/menus/musica.yml se manca e ricarica i menu), senza passi manuali. "
                                + "Senza MagixMenus restano solo i comandi /radio. Il file lo puoi modificare come "
                                + "qualunque menu: MagixMusic non lo sovrascrive più una volta creato.",
                        "Per mostrare i valori in diretta MagixMusic espone dei placeholder PlaceholderAPI, usabili "
                                + "anche in tablist, scoreboard o sul sito: %magixmusic_volume% (0-100), %magixmusic_bar% "
                                + "(la barra), %magixmusic_state% (Accesa/Spenta), %magixmusic_track% (brano in onda) e "
                                + "%magixmusic_enabled% (1/0, radio accesa in generale). Se PlaceholderAPI non è "
                                + "installato la radio funziona lo stesso, ma il menu non vede i valori live.")

                .detailedCommands()
                .commands()
                .permissions()
                .settings(
                        "world", "Mondo in cui si sente la radio (di norma quello dello spawn).",
                        "radius", "Raggio in blocchi dallo spawn entro cui si sente la radio.",
                        "default-volume", "Volume iniziale (0-100) per chi non l'ha ancora regolato con /radio.",
                        "sound-category", "Canale audio del suono: records (jukebox) o music (slider Musica).",
                        "play-on-join", "Se chi entra sente subito il brano in corso (dall'inizio, non sincronizzato).",
                        "playlist", "Scaletta dei brani: ogni voce ha sound (id del disco) e seconds (durata).")

                .issue("Ho cambiato una chiave del config nel repo e sul server non succede niente",
                        "Il deploy porta il jar, non i config: il file nella cartella del plugin sul server non viene toccato, ed e' quello che il plugin legge. Il valore nel jar vale solo per le chiavi che li' MANCANO. Quindi un valore gia' presente si cambia sul server (a mano, o col workflow deploy-plugin-config.yml), non nel repo. Del resto si occupa il plugin, a ogni avvio e a ogni reload: aggiunge le chiavi nuove al loro posto col loro commento, applica le rinomine portandosi dietro il valore che avevi scelto, e toglie le righe morte che il codice non legge piu' dai file a schema fisso. Prima di ogni modifica fa una copia del file accanto all'originale, col nome che finisce in .bak-<data>, e nel log scrive che cosa ha cambiato.")
                .issue("Un giocatore dice che «non sente la radio»",
                        "Controlla che sia nel mondo giusto e dentro il raggio dallo spawn, che non l'abbia spenta "
                                + "lui (/radio mostra il suo stato) e che la radio non sia spenta in generale (config "
                                + "enabled). Ricorda che la sente solo attorno allo spawn.")
                .issue("La musica si accavalla o parte da capo per chi entra",
                        "È il limite del \"da capo\": Minecraft non riprende un suono a metà. Al cambio di brano tutti "
                                + "si riallineano. Se le durate in playlist non sono giuste, un brano può partire sopra "
                                + "al precedente: correggi il valore seconds del brano.")

                .never("Non mettere un raggio enorme pensando di coprire tutto il mondo: la radio è pensata per la "
                        + "zona spawn, un raggio troppo grande fa sentire la musica anche a chi è lontano.")
                .never("Non aggiungere un brano senza mettere la sua durata giusta in seconds: il ciclo si sfasa.")
                .write();
    }
}
