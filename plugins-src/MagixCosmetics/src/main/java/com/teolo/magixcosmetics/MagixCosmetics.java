package com.teolo.magixcosmetics;

import com.teolo.magixcosmetics.util.ConfigAlign;
import com.teolo.magixcosmetics.command.HaloCommand;
import com.teolo.magixcosmetics.command.MagixCosmeticsCommand;
import com.teolo.magixcosmetics.cosmetic.HaloCombatListener;
import com.teolo.magixcosmetics.cosmetic.HaloManager;
import com.teolo.magixcosmetics.lang.Messages;
import com.teolo.magixcosmetics.util.ConfigValues;
import com.teolo.magixcosmetics.util.StaffGuide;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MagixCosmetics — cosmetici a particelle per i giocatori.
 *
 * <p>Per ora c'e' una cosa sola: l'aureola colorata che gira sopra la testa dei VIP
 * (vedi {@link HaloManager}). E' nato come plugin a se' apposta per poterci aggiungere
 * gli altri cosmetici — scie, ali, cappelli — senza appesantire i plugin gia' esistenti.</p>
 */
public final class MagixCosmetics extends JavaPlugin {

    private Messages messages;
    private HaloManager halo;

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
        // Il capitolo della guida per lo staff sul sito + il README nella cartella del plugin:
        // stessa scrittura, letta dal config vivo. Puro I/O, fuori dal tick d'avvio.
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);

        messages = new Messages(this);

        halo = new HaloManager(this);
        halo.load();
        halo.start();
        Bukkit.getPluginManager().registerEvents(new HaloCombatListener(halo), this);

        PluginCommand cmd = getCommand("magixcosmetics");
        if (cmd != null) {
            MagixCosmeticsCommand executor = new MagixCosmeticsCommand(this, messages);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        PluginCommand haloCmd = getCommand("halo");
        if (haloCmd != null) {
            HaloCommand executor = new HaloCommand(this, messages);
            haloCmd.setExecutor(executor);
            haloCmd.setTabCompleter(executor);
        }

        getLogger().info("Avviato: aureola " + (halo.enabled() ? "attiva" : "disattivata") + ".");
    }

    @Override
    public void onDisable() {
        if (halo != null) halo.stop();
    }

    /** Ricarica config.yml e messages.yml e fa ripartire l'aureola col nuovo intervallo. */
    public void reloadEverything() {
        // Come all'avvio: prima si allineano i file del server a quelli del jar, poi si
        // rilegge. Cosi' un reload dopo un deploy vede anche le chiavi nuove.
        ConfigAlign.alignAll(this);
        reloadConfig();
        messages.reload();
        halo.load();
        halo.start();
        getLogger().info("Configurazione ricaricata: aureola " + (halo.enabled() ? "attiva" : "disattivata") + ".");
    }

    public Messages messages() { return messages; }
    public HaloManager halo() { return halo; }

    // ------------------------------------------------- GUIDA PER LO STAFF

    /**
     * Capitolo di MagixCosmetics nella guida del gestionale + README. Comandi, permessi e
     * valori di configurazione non si ricopiano: li legge da solo. Vedi plugins-src/GUIDA-STAFF.md.
     */
    private void writeStaffGuide() {
        StaffGuide.create(this, "MagixCosmetics — cosmetici a particelle", 80)
                // I numeri (raggio, altezza, colore...) vengono dal config vero: cambiando una
                // chiave, questo capitolo cambia da solo (vedi util/ConfigValues).
                .values(new ConfigValues(this))
                .intro("Aggiunge cosmetici a particelle sopra i giocatori. Per ora ce n'e' uno solo: "
                        + "un'aureola colorata che gira sopra la testa dei VIP.")

                .section("L'aureola",
                        "È un **solo puntino** che orbita in cerchio a {{cfg:halo.height}} blocchi da terra, poco "
                                + "sopra la testa: a ogni passo avanza lungo il cerchio, così ruota, e le particelle "
                                + "che sfumano gli lasciano una breve scia. Non un anello pieno ridisegnato ogni "
                                + "volta — quello, da fermo, sembrerebbe pulsare.",
                        "La vede chi ha il permesso **magixcosmetics.halo**: è così che si dà ai VIP, di norma con "
                                + "LuckPerms sul grado VIP. Non serve nessun comando per accenderla — appena il permesso "
                                + "c'è, l'aureola compare, MA senza un colore resta invisibile: vedi sotto.",
                        "Un VIP che non la vuole può spegnersela con **/halo off** e riaccenderla con "
                                + "**/halo on**. La scelta resta salvata (`players.yml`): un riavvio del server "
                                + "non la riaccende da solo.")

                .section("Il colore: nessuno di serie, dipende dal permesso",
                        "Non c'è un colore di default: ogni colore della tavolozza (**halo.colors** nel config) ha "
                                + "il suo permesso, **magixcosmetics.halo.color.<nome>** (es. `magixcosmetics.halo."
                                + "color.yellow` per il giallo). Un VIP con **magixcosmetics.halo** ma nessun permesso "
                                + "colore ha l'aureola \"accesa\" ma senza colore: in pratica non si disegna. Basta "
                                + "dargli UN permesso colore perché l'aureola si accenda da sola in quel colore, senza "
                                + "nessun comando.",
                        "Se un giocatore ha più permessi colore (es. un rango top-donatore sbloccato su tutti), sceglie "
                                + "quale mostrare con **/halo setcolor <nome>** — l'elenco dei nomi validi è quello "
                                + "delle chiavi sotto **halo.colors**. Senza una scelta esplicita si usa il primo "
                                + "colore della tavolozza per cui ha il permesso.")

                .section("Sparisce in combattimento",
                        "{{se:halo.combat.hide-while-fighting=true}}Appena il giocatore dà o subisce un colpo PvP "
                                + "l'aureola sparisce, e torna a vedersi solo {{secondi:halo.combat.cooldown-after-"
                                + "combat-seconds}} dopo l'ultimo colpo: da bersaglio colorato diventerebbe un "
                                + "vantaggio per chi lo insegue in mezzo a un mondo pieno di gente.{{/se}}"
                                + "{{se:halo.combat.hide-while-fighting!=true}}Con **halo.combat.hide-while-fighting** "
                                + "spento l'aureola resta visibile anche in combattimento.{{/se}}")

                .section("L'aureola sulle statue (MagixEntities)",
                        "Una statua di MagixEntities con la skin di un giocatore mostra la **sua** aureola, nel suo "
                                + "colore, a chiunque la guardi — anche se lui è offline: i suoi permessi si leggono da "
                                + "LuckPerms (su un altro thread, senza pesare sul server) e si rileggono ogni 10 minuti, "
                                + "quindi un permesso dato o tolto mentre è offline si vede sulla statua entro quel tempo. "
                                + "Il colore resta anche ricordato in `players.yml`: senza LuckPerms vale quello dell'ultima "
                                + "volta che era online. **/halo off** vale anche per la statua; combattimento, vanish e "
                                + "invisibilità no, perché riguardano il giocatore, non la statua.",
                        "Su una statua a specchio (skin \"mirror\") ognuno vede la propria copia con la propria "
                                + "aureola, e solo lui la vede: le copie stanno tutte nello stesso punto. Su una statua "
                                + "ingrandita l'aureola cresce con lei.")

                .section("Perché è un plugin a parte",
                        "I cosmetici non c'entrano con le fazioni, con l'ora o con le sanzioni: tenerli qui evita di "
                                + "gonfiare gli altri plugin, e domani ci si aggiungono scie, ali o cappelli senza "
                                + "toccare nient'altro.")

                .section("Prestazioni",
                        "Il disegno è un unico task che passa in rassegna i giocatori online e spawna **una sola** "
                                + "particella per VIP a ogni passo: pesa solo su chi l'aureola ce l'ha davvero, ed è "
                                + "leggerissimo. Se servisse alleggerire ancora, alza **update-interval-ticks** (il "
                                + "puntino avanza meno spesso).")

                .commands()
                .permissions()
                .settings(
                        "halo.enabled", "Interruttore generale: spento, nessuno vede l'aureola e il task non gira.",
                        "halo.combat.hide-while-fighting", "Nascondi l'aureola durante un combattimento PvP.",
                        "halo.combat.cooldown-after-combat-seconds", "Quanti secondi dopo l'ultimo colpo l'aureola torna a vedersi.",
                        "halo.spin-speed", "Quanto avanza lungo il cerchio a ogni passo: più alto = orbita più veloce.",
                        "halo.update-interval-ticks", "Ogni quanti tick il puntino avanza: 1 = più fluido; più alto = più leggero.")

                .issue("Ho cambiato una chiave del config nel repo e sul server non succede niente",
                        "Il deploy porta il jar, non i config: il file nella cartella del plugin sul server non viene toccato, ed e' quello che il plugin legge. Il valore nel jar vale solo per le chiavi che li' MANCANO. Quindi un valore gia' presente si cambia sul server (a mano, o col workflow deploy-plugin-config.yml), non nel repo. Del resto si occupa il plugin, a ogni avvio e a ogni reload: aggiunge le chiavi nuove al loro posto col loro commento, applica le rinomine portandosi dietro il valore che avevi scelto, e toglie le righe morte che il codice non legge piu' dai file a schema fisso, cioe' tutti tranne i cataloghi (i menu e le sanzioni no: li' le voci in piu' sono tue). Prima di ogni modifica fa una copia del file in .bak/ (fuori da plugins/ sul server), col nome che finisce in .bak-<data>, e nel log scrive che cosa ha cambiato.")
                .issue("Un VIP non vede la sua aureola",
                        "Controlla, in ordine: che abbia davvero il permesso magixcosmetics.halo (LuckPerms); che "
                                + "abbia ANCHE almeno un permesso colore (magixcosmetics.halo.color.<nome>) — senza "
                                + "nessuno l'aureola non ha un colore e non si disegna; che non se la sia spenta con "
                                + "/halo off; che non sia in combattimento PvP (sparisce da sola per qualche secondo); "
                                + "che non sia in spettatore, in vanish o invisibile.")
                .issue("L'aureola si vede su uno staff in vanish",
                        "Non dovrebbe: hide-when-vanished la nasconde a chi ha il metadata di vanish (CMI). "
                                + "Se succede, verifica che il vanish in uso imposti quel metadata.")
                .issue("Il puntino va troppo veloce o troppo piano",
                        "Regola halo.spin-speed (radianti per passo) e poi /cosmetics reload.")

                .never("Non dare magixcosmetics.halo a default true: diventerebbe di tutti, non più un segno dei VIP.")
                .never("Non dare un permesso colore (magixcosmetics.halo.color.<nome>) a default true: accenderebbe "
                        + "quel colore a chiunque abbia anche solo magixcosmetics.halo.")
                .write();
    }
}
