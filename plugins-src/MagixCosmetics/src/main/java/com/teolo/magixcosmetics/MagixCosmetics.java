package com.teolo.magixcosmetics;

import com.teolo.magixcosmetics.util.ConfigAlign;
import com.teolo.magixcosmetics.command.MagixCosmeticsCommand;
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
 * <p>Per ora c'e' una cosa sola: l'aureola gialla che gira sopra la testa dei VIP
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

        PluginCommand cmd = getCommand("magixcosmetics");
        if (cmd != null) {
            MagixCosmeticsCommand executor = new MagixCosmeticsCommand(this, messages);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
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
                        + "un'aureola gialla che gira sopra la testa dei VIP.")

                .section("L'aureola",
                        "È un **solo puntino** giallo (**{{cfg:halo.color}}**) che orbita in cerchio a "
                                + "{{cfg:halo.height}} blocchi da terra, poco sopra la testa: a ogni passo avanza lungo "
                                + "il cerchio, così ruota, e le particelle che sfumano gli lasciano una breve scia. Non "
                                + "un anello pieno ridisegnato ogni volta — quello, da fermo, sembrerebbe pulsare.",
                        "La vede chi ha il permesso **magixcosmetics.halo**: è così che si dà ai VIP, di norma con "
                                + "LuckPerms sul grado VIP. Non serve nessun comando per accenderla — appena il permesso "
                                + "c'è, l'aureola compare.",
                        "Un VIP che non la vuole può spegnersela con **/cosmetics halo off** e riaccenderla con "
                                + "**/cosmetics halo on**. Questa scelta vive in memoria: a un riavvio del server torna "
                                + "accesa per tutti.")

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
                        "halo.color", "Colore del puntino in #RRGGBB. Il giallo dei VIP è #FFDD33.",
                        "halo.spin-speed", "Quanto avanza lungo il cerchio a ogni passo: più alto = orbita più veloce.",
                        "halo.update-interval-ticks", "Ogni quanti tick il puntino avanza: 1 = più fluido; più alto = più leggero.")

                .issue("Ho cambiato una chiave del config nel repo e sul server non succede niente",
                        "Il deploy porta il jar, non i config: il file nella cartella del plugin sul server non viene toccato, ed e' quello che il plugin legge. Il valore nel jar vale solo per le chiavi che li' MANCANO. Quindi un valore gia' presente si cambia sul server (a mano, o col workflow deploy-plugin-config.yml), non nel repo. Del resto si occupa il plugin, a ogni avvio e a ogni reload: aggiunge le chiavi nuove al loro posto col loro commento, applica le rinomine portandosi dietro il valore che avevi scelto, e toglie da config.yml e messages.yml le righe morte che il codice non legge piu' (i menu e le sanzioni no: li' le voci in piu' sono tue). Prima di ogni modifica fa una copia del file accanto all'originale, col nome che finisce in .bak-<data>, e nel log scrive che cosa ha cambiato.")
                .issue("Un VIP non vede la sua aureola",
                        "Controlla che abbia davvero il permesso magixcosmetics.halo (LuckPerms), che non se la sia "
                                + "spenta con /cosmetics halo off, e che non sia in spettatore, in vanish o invisibile.")
                .issue("L'aureola si vede su uno staff in vanish",
                        "Non dovrebbe: hide-when-vanished la nasconde a chi ha il metadata di vanish (CMI). "
                                + "Se succede, verifica che il vanish in uso imposti quel metadata.")
                .issue("Il puntino va troppo veloce o troppo piano",
                        "Regola halo.spin-speed (radianti per passo) e poi /cosmetics reload.")

                .never("Non dare magixcosmetics.halo a default true: diventerebbe di tutti, non più un segno dei VIP.")
                .write();
    }
}
