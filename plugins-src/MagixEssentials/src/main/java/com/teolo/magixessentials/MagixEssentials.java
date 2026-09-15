package com.teolo.magixessentials;

import com.teolo.magixessentials.util.ConfigAlign;
import com.teolo.magixessentials.tab.TabManager;
import com.teolo.magixessentials.util.ConfigValues;
import com.teolo.magixessentials.util.StaffGuide;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * MagixEssentials: raccoglie le utilita' "di base" del server. Per ora gestisce SOLO il tablist
 * (la lista giocatori, tasto Tab); l'idea a lungo termine e' che assorba cio' che oggi fa CMI.
 *
 * <p>Il tablist e' volutamente separato in {@link TabManager}: la classe principale si limita ad
 * accenderlo/spegnerlo e a offrire {@code /magixessentials reload}.
 */
public final class MagixEssentials extends JavaPlugin {

    private TabManager tabManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // I file di configurazione SUL SERVER allineati a quelli del jar: le chiavi nuove
        // compaiono da sole, al loro posto e col loro commento, senza toccare i valori
        // gia' scelti. Il deploy porta solo il jar, quindi senza questo il file del server
        // resterebbe indietro in silenzio (vedi util/ConfigAlign).
        ConfigAlign.alignAll(this);
        reloadConfig();
        // Il capitolo della guida per lo staff sul sito + il README nella cartella del plugin:
        // stessa scrittura, letta dal config vivo. Puro I/O, fuori dal tick d'avvio.
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);

        tabManager = new TabManager(this);
        tabManager.start();
        getLogger().info("MagixEssentials abilitato (tablist "
                + (getConfig().getBoolean("tablist.enabled", true) ? "attivo" : "disattivato") + ").");
    }

    @Override
    public void onDisable() {
        if (tabManager != null) tabManager.stop();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            // Come all'avvio: prima si allineano i file del server a quelli del jar, poi si
            // rilegge. Cosi' un reload dopo un deploy vede anche le chiavi nuove.
            ConfigAlign.alignAll(this);
            reloadConfig();
            if (tabManager != null) { tabManager.stop(); tabManager.start(); }
            // La guida riporta i valori VIVI del config: se non la riscrivessimo qui, dopo un
            // reload resterebbe indietro fino al prossimo riavvio.
            Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);
            sender.sendMessage("§dMagixEssentials §8» §7Configurazione ricaricata.");
            return true;
        }
        sender.sendMessage("§dMagixEssentials §8» §7Uso: §f/" + label + " reload");
        return true;
    }

    // ------------------------------------------------- GUIDA PER LO STAFF

    /**
     * Capitolo di MagixEssentials nella guida del gestionale + README. Comandi, permessi e
     * valori di configurazione non si ricopiano: li legge da solo. Vedi plugins-src/GUIDA-STAFF.md.
     */
    private void writeStaffGuide() {
        StaffGuide.create(this, "MagixEssentials — tablist e utilita' del server", 90)
                // I numeri (intervallo, slot...) vengono dal config vero: cambiando una chiave,
                // questo capitolo cambia da solo (vedi util/ConfigValues).
                .values(new ConfigValues(this))
                .intro("Raccoglie le utilita' di base del server. Per ora fa una cosa sola: il "
                        + "**tablist**, cioe' la lista giocatori che si apre col tasto Tab. "
                        + "A lungo andare dovrebbe assorbire cio' che oggi fa CMI.")

                .section("Chi comanda il tablist",
                        "Il tablist non ha un proprietario: **ce l'ha chi ha scritto per ultimo**. Se anche CMI lo "
                                + "gestisce, i due si sovrascrivono a vicenda e il risultato dipende dall'ordine, "
                                + "cioe' dal caso. La via pulita resta spegnere il suo modulo "
                                + "(plugins/CMI/Settings/Modules.yml → **tablist: false**).",
                        "Finche' resta acceso, **tablist.priority** ci fa scrivere dopo di lui: ogni aggiornamento "
                                + "viene riscritto una seconda volta **{{cfg:tablist.priority.reassert-delay-ticks}}** "
                                + "tick piu' tardi, e l'aggancio al join e' a priorita' MONITOR, cioe' dopo gli altri "
                                + "plugin. Se vedi ancora comparire per un istante il tablist di CMI, alza quel ritardo.",
                        "**Fin dove arriva la priorita':** intestazione, fondo e nomi. Le voci **FINTE** che un "
                                + "altro plugin inietta via pacchetto — le 80 slot di CMI, con la testa e le "
                                + "tacchette — quelle no: sono sue, e nessuna priorita' le raggiunge. Per quelle "
                                + "l'unica via e' spegnere il suo modulo. Le nostre slot fisse (sotto) fanno la "
                                + "stessa cosa ma **senza testa e senza tacchette**: se vedi ancora caselle con la "
                                + "faccia di Steve, stai guardando le sue, non le nostre.",
                        "All'avvio il plugin legge da solo il Modules.yml di CMI e mette un avviso nel log se il "
                                + "conflitto c'e', invece di lasciarti a indovinare perche' il tab «torna come prima».",
                        "Intestazione (**header**) e fondo (**footer**) sono liste di righe nel config: una voce "
                                + "della lista, una riga a schermo. Il nome del giocatore nella lista si compone a "
                                + "parte con **tablist.player-name**, dove {name} e' il suo nome.")

                .section("Colori, placeholder e il logo del server",
                        "Nelle righe valgono i codici colore **&** e **&#RRGGBB**, quindi anche "
                                + "%magixweb_namecolor% (il colore del grado, lo stesso che si vede sul sito) finisce "
                                + "dritto nel nome.",
                        "I **%placeholder%** li risolve PlaceholderAPI, che e' softdepend: se PAPI non c'e' il "
                                + "tablist funziona lo stesso, ma i %...% restano scritti cosi' come sono. I "
                                + "placeholder per-giocatore (ping, fazione, coordinate) sono ricalcolati per "
                                + "ciascuno, non una volta sola per tutti.",
                        "Il segnaposto **{logo}** diventa il carattere del logo del server. Il logo NON e' roba di "
                                + "questo plugin: e' un glifo del resource pack di **MagixFactions**, e li' si "
                                + "regolano dimensione e altezza (tablist.logo.height / tablist.logo.ascent). Qui si "
                                + "decide solo dove metterlo.")

                .section("Quando si aggiorna",
                        "Ogni **{{cfg:tablist.update-interval-ticks}}** tick (20 tick = 1 secondo) e, in piu', un "
                                + "tick dopo ogni ingresso — cosi' mondo e coordinate sono gia' pronti e il nuovo "
                                + "arrivato non vede un tablist a meta'.",
                        "Abbassare l'intervallo rende il ping piu' reattivo ma fa lavorare il server piu' spesso, "
                                + "una volta per giocatore online: sotto i 10 tick non serve a niente che si veda.")

                .section("Le 80 slot fisse",
                        "Il gioco decide da solo quante colonne disegnare in base a quante voci ci sono: con pochi "
                                + "giocatori il tab e' una colonna sottile, con tanti si allarga. Se sotto ci deve "
                                + "stare una pergamena, quella misura non puo' ballare. Con **tablist.fixed-slots** "
                                + "acceso il tab mostra sempre **{{cfg:tablist.fixed-slots.total}}** caselle, "
                                + "riempiendo con voci decorative quelle senza giocatore.",
                        "Le caselle vuote sono **senza testa** (portano una skin trasparente: un profilo senza "
                                + "texture non e' invisibile, il gioco ci metterebbe ottanta teste di Steve) e "
                                + "**senza tacchette di connessione** (latenza -1, il valore che il client disegna "
                                + "come barra vuota). Cosa c'e' scritto dentro lo decide **empty-text**.",
                        "**Serve ProtocolLib**, perche' una voce del tablist senza un giocatore vero dietro non "
                                + "esiste nell'API di Bukkit: va mandata al client come pacchetto. E' l'unico punto "
                                + "di questo plugin che parla di pacchetti. Se ProtocolLib manca, o se la struttura "
                                + "del pacchetto non e' quella attesa, la funzione **si spegne da sola** e resta il "
                                + "tablist dinamico: lo dice nel log. Un tab di misura variabile e' un difetto "
                                + "estetico, un tab che sparisce e' un guasto.",
                        "Oltre 80 non si va: il gioco disegna al massimo 4 colonne da 20, e il plugin taglia li'. "
                                + "E se i giocatori veri sono piu' del totale non si riempie niente — il tab e' gia' "
                                + "pieno di gente vera, che e' meglio.")

                .commands()
                .permissions()
                .settings(
                        "tablist.enabled", "Interruttore generale: spento, il tablist resta quello di CMI (o del gioco).",
                        "tablist.update-interval-ticks", "Ogni quanti tick si riscrivono intestazione, fondo e nomi.",
                        "tablist.player-name", "Come appare il nome nella lista: {name} e' il nome, valgono colori e placeholder.",
                        "tablist.priority.enabled", "Riscrive una seconda volta per arrivare dopo CMI. Spegnila se il tablist e' solo nostro.",
                        "tablist.priority.reassert-delay-ticks", "Quanti tick dopo arriva la seconda scrittura: alzalo se CMI si vede ancora per un istante.",
                        "tablist.fixed-slots.enabled", "Caselle fisse: il tab resta sempre della stessa misura. Serve ProtocolLib.",
                        "tablist.fixed-slots.total", "Quante caselle in tutto: il gioco ne disegna al massimo 80 (4 colonne x 20).",
                        "tablist.fixed-slots.empty-text", "Cosa c'e' scritto in una casella vuota: uno spazio la lascia muta.")

                .issue("Le caselle vuote hanno una testa e le tacchette di connessione",
                        "Allora non sono le nostre, sono quelle di CMI: le nostre nascono senza testa e senza "
                                + "tacchette. Vuol dire che il suo modulo tablist e' ancora acceso e sta riempiendo "
                                + "lui. Spegnilo (Modules.yml → tablist: false) e riavvia: il tab torna nostro.")
                .issue("Ho cambiato una chiave del config nel repo e sul server non succede niente",
                        "Il deploy porta il jar, non i config: il file nella cartella del plugin sul server non viene toccato, ed e' quello che il plugin legge. Il valore nel jar vale solo per le chiavi che li' MANCANO. Quindi un valore gia' presente si cambia sul server (a mano, o col workflow deploy-plugin-config.yml), non nel repo. Le chiavi NUOVE invece arrivano da sole: a ogni avvio e a ogni reload il plugin confronta il file del server con quello del jar e ci aggiunge quelle che mancano, al loro posto e col loro commento, senza toccare i valori gia' scelti; nel log scrive quali ha aggiunto, e quali sul server non corrispondono piu' a niente (di solito una chiave rinominata, che va sistemata con mode=rename).")
                .issue("Le slot fisse non compaiono",
                        "Guarda il log all'avvio: il plugin scrive «slot fisse attive: N caselle» quando ci "
                                + "riesce, e il motivo quando no (ProtocolLib assente, o struttura del pacchetto "
                                + "diversa). Se il messaggio dice che sono attive ma a schermo non si vedono, il "
                                + "tablist lo sta ancora riscrivendo CMI.")
                .issue("Il tablist lampeggia o torna com'era",
                        "Lo sta riscrivendo anche CMI: spegni il suo modulo tablist "
                                + "(plugins/CMI/Settings/Modules.yml → tablist: false) e riavvia.")
                .issue("Nel tablist si legge %magixfactions_faction% invece della fazione",
                        "Manca PlaceholderAPI, oppure manca l'espansione di quel plugin: controlla che PAPI sia "
                                + "avviato e che il plugin che fornisce quel placeholder sia acceso.")
                .issue("Al posto del logo c'e' un quadratino bianco",
                        "Il giocatore non ha il resource pack di MagixFactions (rifiutato o non ancora scaricato). "
                                + "Il carattere del logo esiste solo dentro quel pacchetto.")
                .issue("Il logo copre le righe di informazioni",
                        "Aggiungi righe vuote (' ') sotto {logo} nell'header, oppure abbassa tablist.logo.height "
                                + "nel config di MagixFactions.")

                .never("Non lasciare acceso anche il tablist di CMI: due plugin sullo stesso tablist non si "
                        + "spartiscono il lavoro, se lo strappano di mano.")
                .never("Non scrivere i numeri del logo qui: dimensione e posizione stanno nel config di "
                        + "MagixFactions, che e' anche il plugin che costruisce il resource pack.")
                .write();
    }
}
