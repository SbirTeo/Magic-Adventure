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
            byte[] bundledBytes;
            try (java.io.InputStream in = getResource("menus/musica.yml")) {
                if (in == null) { getLogger().warning("menus/musica.yml non incluso nel jar."); return; }
                bundledBytes = in.readAllBytes();
            }
            String bundled = new String(bundledBytes, java.nio.charset.StandardCharsets.UTF_8);
            String bundledVer = menuVersion(bundled);

            java.io.File target = new java.io.File(dir, "musica.yml");
            String reason;
            if (!target.exists()) {
                reason = "installato";
            } else {
                String existingVer = menuVersion(java.nio.file.Files.readString(target.toPath()));
                // Stessa versione: non tocco il file, così le modifiche dello staff restano.
                if (bundledVer == null || bundledVer.equals(existingVer)) return;
                // Versione diversa: copia di sicurezza col timestamp, poi aggiorno. Va in .bak/,
                // fuori da plugins/ sul server, come fa util/ConfigAlign per i suoi file.
                String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                // Assoluto PRIMA di risalire i genitori: getDataFolder() e' quasi sempre relativo
                // ("plugins/<Plugin>") su un server vero, e getParentFile() su un singolo segmento
                // come "plugins" da' null - senza questo il backup finiva sempre accanto al file.
                java.io.File pluginsDir = menus.getDataFolder().getAbsoluteFile().getParentFile();
                java.io.File serverRoot = pluginsDir == null ? null : pluginsDir.getParentFile();
                java.io.File bakDir = serverRoot == null ? dir
                        : new java.io.File(new java.io.File(serverRoot, ".bak"),
                                pluginsDir.toPath().relativize(dir.getAbsoluteFile().toPath()).toString());
                if (!bakDir.exists()) bakDir.mkdirs();
                java.nio.file.Files.copy(target.toPath(),
                        new java.io.File(bakDir, "musica.yml.bak-" + stamp).toPath());
                reason = "aggiornato (vecchio salvato come .bak-" + stamp + ")";
            }
            java.nio.file.Files.write(target.toPath(), bundledBytes);
            getLogger().info("MagixMenus rilevato: menu /musica " + reason + ", ricarico i menu.");
            // Ricarica i menu di MagixMenus così /musica si registra/aggiorna subito, senza riavvio.
            Bukkit.getScheduler().runTask(this, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "menus reload"));
        } catch (Exception e) {
            getLogger().warning("Installazione del menu /musica fallita: " + e.getMessage());
        }
    }

    /** Legge la riga "# menu-version: N" dal testo di un menu; null se non c'è. */
    private static String menuVersion(String yaml) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^#\\s*menu-version:\\s*(\\S+)").matcher(yaml);
        return m.find() ? m.group(1) : null;
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
                .intro("Nel mondo spawn c'è una radio che suona a ciclo i dischi di Minecraft. Tutti quelli nel "
                        + "mondo sentono lo stesso brano nello stesso momento, allo stesso volume ovunque; ognuno può "
                        + "alzarla, abbassarla o spegnerla per sé con /radio.")

                .section("Come funziona",
                        "Il server fa da orologio comune: a ogni brano manda a tutti i giocatori del mondo lo "
                                + "stesso stop+play, quindi sono sincronizzati al secondo. Il suono è NON posizionale "
                                + "(emesso dal giocatore stesso): si sente in TUTTO il mondo «{{cfg:world}}», allo stesso "
                                + "volume ovunque, e segue il giocatore mentre cammina — niente raggio, niente calo con la "
                                + "distanza. Negli altri mondi non si sente.",
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
                                + "qualunque menu: MagixMusic lo riscrive solo quando cambia la versione del menu (la riga "
                                + "menu-version nel file), facendone prima una copia in .bak/ (fuori da plugins/ sul "
                                + "server); finché la versione è la stessa, le tue modifiche restano.",
                        "Dal menu (e dai comandi) si fa un po' tutto: accendere/spegnere, alzare/abbassare, i "
                                + "preset di volume, **riascoltare** il brano dall'inizio (/radio replay, utile a chi è "
                                + "appena entrato) e, per lo staff, **saltare** al brano successivo per tutti (/radio "
                                + "next). Il pulsante «Salta brano» nel menu compare solo a chi ha magixmusic.admin.",
                        "Per mostrare i valori in diretta MagixMusic espone dei placeholder PlaceholderAPI, usabili "
                                + "anche in tablist, scoreboard o sul sito: %magixmusic_volume% (0-100), %magixmusic_bar% "
                                + "(barra volume), %magixmusic_state% (Accesa/Spenta), %magixmusic_track% (brano in onda), "
                                + "%magixmusic_next% (brano successivo), %magixmusic_time% (trascorso/durata), "
                                + "%magixmusic_progress% (barra avanzamento), %magixmusic_elapsed%, %magixmusic_remaining%, "
                                + "%magixmusic_duration%, %magixmusic_index%, %magixmusic_count% e %magixmusic_enabled% "
                                + "(1/0). Se PlaceholderAPI non è installato la radio funziona lo stesso, ma il menu non "
                                + "vede i valori live.")

                .detailedCommands()
                .commands()
                .permissions()
                .settings(
                        "world", "Mondo in cui si sente la radio: si sente in TUTTO il mondo, allo stesso volume.",
                        "default-volume", "Volume iniziale (0-100) per chi non l'ha ancora regolato con /radio.",
                        "sound-category", "Canale audio del suono: records (jukebox) o music (slider Musica).",
                        "play-on-join", "Se chi entra sente subito il brano in corso (dall'inizio, non sincronizzato).",
                        "playlist", "Scaletta dei brani: ogni voce ha sound (id del disco) e seconds (durata).")

                .issue("Ho cambiato una chiave del config nel repo e sul server non succede niente",
                        "Il deploy porta il jar, non i config: il file nella cartella del plugin sul server non viene toccato, ed e' quello che il plugin legge. Il valore nel jar vale solo per le chiavi che li' MANCANO. Quindi un valore gia' presente si cambia sul server (a mano, o col workflow deploy-plugin-config.yml), non nel repo. Del resto si occupa il plugin, a ogni avvio e a ogni reload: aggiunge le chiavi nuove al loro posto col loro commento, applica le rinomine portandosi dietro il valore che avevi scelto, e toglie le righe morte che il codice non legge piu' dai file a schema fisso. Prima di ogni modifica fa una copia del file in .bak/ (fuori da plugins/ sul server), col nome che finisce in .bak-<data>, e nel log scrive che cosa ha cambiato.")
                .issue("Un giocatore dice che «non sente la radio»",
                        "Controlla che sia nel mondo giusto (config world), che non l'abbia spenta lui (/radio mostra "
                                + "il suo stato, o /musica) e che la radio non sia spenta in generale (config enabled). "
                                + "Nel mondo giusto la sente ovunque, allo stesso volume.")
                .issue("La musica si accavalla o parte da capo per chi entra",
                        "È il limite del \"da capo\": Minecraft non riprende un suono a metà. Al cambio di brano tutti "
                                + "si riallineano. Se le durate in playlist non sono giuste, un brano può partire sopra "
                                + "al precedente: correggi il valore seconds del brano.")

                .never("Non mettere come world un mondo enorme di sopravvivenza se non vuoi la musica ovunque lì: la "
                        + "radio si sente in TUTTO il mondo indicato. Metti il mondo dove vuoi davvero la musica.")
                .never("Non aggiungere un brano senza mettere la sua durata giusta in seconds: il ciclo si sfasa.")
                .write();
    }
}
