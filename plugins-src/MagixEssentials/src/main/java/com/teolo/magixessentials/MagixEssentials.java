package com.teolo.magixessentials;

import com.teolo.magixessentials.module.Modules;
import com.teolo.magixessentials.motd.MotdListener;
import com.teolo.magixessentials.tab.TabManager;
import com.teolo.magixessentials.util.ConfigAlign;
import com.teolo.magixessentials.util.ConfigValues;
import com.teolo.magixessentials.util.StaffGuide;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * MagixEssentials: raccoglie le utilita' "di base" del server — oggi il <b>tablist</b> (la lista
 * giocatori del tasto Tab) e la <b>MOTD</b> (le righe che si leggono nella lista server);
 * l'idea a lungo termine e' che assorba cio' che oggi fa CMI.
 *
 * <p>Ogni funzione si accende e si spegne dal {@code modules.yml}, come nel Modules.yml di CMI, e
 * si regola nel file che porta il suo nome ({@code tablist.yml}, {@code motd.yml}); il
 * {@code config.yml} tiene solo cio' che vale per il plugin intero. Vedi {@link Modules}.
 *
 * <p>Ogni funzione sta per conto suo ({@link TabManager}, {@link MotdListener}): questa classe si
 * limita ad accenderle e spegnerle e a offrire {@code /magixessentials reload}.
 */
public final class MagixEssentials extends JavaPlugin {

    private Modules modules;
    private TabManager tabManager;
    private MotdListener motd;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Gli interruttori delle funzioni e i file di impostazioni delle funzioni stesse PRIMA
        // dell'allineamento: ConfigAlign tocca solo i file che nella cartella dati esistono gia',
        // e a crearli e' questa riga (vedi module/Modules).
        modules = new Modules(this);
        // I file di configurazione SUL SERVER allineati a quelli del jar: le chiavi nuove
        // compaiono da sole, al loro posto e col loro commento, senza toccare i valori
        // gia' scelti. Il deploy porta solo il jar, quindi senza questo il file del server
        // resterebbe indietro in silenzio (vedi util/ConfigAlign).
        ConfigAlign.alignAll(this);
        reloadConfig();
        modules.ricarica();   // dopo l'allineamento: cosi' legge anche le chiavi appena aggiunte
        // Il capitolo della guida per lo staff sul sito + il README nella cartella del plugin:
        // stessa scrittura, letta dal config vivo. Puro I/O, fuori dal tick d'avvio.
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);

        avviaModuli();
        getLogger().info("MagixEssentials abilitato (moduli: " + modules.riepilogo() + ").");
    }

    @Override
    public void onDisable() {
        spegniModuli();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            // Come all'avvio: prima si allineano i file del server a quelli del jar, poi si
            // rilegge. Cosi' un reload dopo un deploy vede anche le chiavi nuove.
            ConfigAlign.alignAll(this);
            reloadConfig();
            modules.ricarica();
            // Sempre tutto spento e poi riacceso solo quello che il modules.yml dice adesso:
            // cosi' una funzione spenta un attimo fa sparisce davvero, invece di restare appesa
            // com'era prima del reload.
            spegniModuli();
            avviaModuli();
            // La guida riporta i valori VIVI del config: se non la riscrivessimo qui, dopo un
            // reload resterebbe indietro fino al prossimo riavvio.
            Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);
            sender.sendMessage("§dMagixEssentials §8» §7Configurazione ricaricata §8(§7moduli: §f"
                    + modules.riepilogo() + "§8)§7.");
            return true;
        }
        sender.sendMessage("§dMagixEssentials §8» §7Uso: §f/" + label + " reload");
        return true;
    }

    /**
     * Accende le funzioni accese nel {@code modules.yml}, ciascuna col suo file di impostazioni
     * riletto adesso. Una funzione spenta non viene nemmeno costruita.
     */
    private void avviaModuli() {
        if (modules.attivo(Modules.TABLIST)) {
            tabManager = new TabManager(this, modules.configurazioneDi(Modules.TABLIST));
            tabManager.start();
        }
        if (modules.attivo(Modules.MOTD)) {
            motd = new MotdListener(this, modules.configurazioneDi(Modules.MOTD));
            motd.start();
        }
    }

    /** Spegne tutto: al reload si riparte da zero, allo spegnimento non si lascia niente appeso. */
    private void spegniModuli() {
        if (tabManager != null) { tabManager.stop(); tabManager = null; }
        if (motd != null) { motd.stop(); motd = null; }
    }

    // ------------------------------------------------- GUIDA PER LO STAFF

    /**
     * Capitolo di MagixEssentials nella guida del gestionale + README. Comandi, permessi e
     * valori di configurazione non si ricopiano: li legge da solo. Vedi plugins-src/GUIDA-STAFF.md.
     */
    private void writeStaffGuide() {
        StaffGuide.create(this, "MagixEssentials — tablist, MOTD e utilita' del server", 90)
                // I numeri (intervallo, slot...) vengono dai file veri: cambiando una chiave,
                // questo capitolo cambia da solo (vedi util/ConfigValues).
                .values(new ConfigValues(this)
                        .also(modules.configurazioneDi(Modules.TABLIST))
                        .also(modules.configurazioneDi(Modules.MOTD)))
                .intro("Raccoglie le utilita' di base del server. Oggi ne fa due: il **tablist**, "
                        + "cioe' la lista giocatori che si apre col tasto Tab, e la **MOTD**, le "
                        + "righe che si leggono nella lista server prima di entrare. A lungo "
                        + "andare dovrebbe assorbire cio' che oggi fa CMI.")

                .section("I moduli: cosa e' acceso e cosa no",
                        "Come in CMI, ogni funzione ha il suo interruttore in un file a parte: "
                                + "**plugins/MagixEssentials/modules.yml**. Li' si accende o si spegne una "
                                + "funzione INTERA (oggi c'e' solo **tablist**, domani ce ne saranno altre). "
                                + "Le sue impostazioni stanno nel file che porta il suo nome — il tablist si "
                                + "regola in **tablist.yml** — e il **config.yml** tiene solo cio' che vale per "
                                + "il plugin intero.",
                        "La divisione serve a una domanda sola: «che cosa sta facendo il plugin adesso?». La "
                                + "risposta e' un file lungo quanto le funzioni che esistono, non venti pagine di "
                                + "impostazioni da leggere per trovare un true. E per cambiare COME lo fa si apre "
                                + "il file di quella funzione: nessun file cresce all'infinito.",
                        "Spegnere una funzione non e' buttarne via la configurazione: il suo file resta dov'e', "
                                + "intatto, e torna in uso appena la si riaccende. Dopo una "
                                + "modifica serve **/magixessentials reload** (o un riavvio): il reload risponde in "
                                + "chat con l'elenco dei moduli e com'e' andata, e lo stesso elenco finisce nel log "
                                + "a ogni avvio.",
                        "Una funzione nuova, aggiunta da un aggiornamento, compare qui da sola col suo valore di "
                                + "partenza, e col suo file di impostazioni accanto: ai file sul server ci pensa il "
                                + "plugin a ogni avvio.")

                .section("Chi comanda il tablist",
                        "Il tablist non ha un proprietario: **ce l'ha chi ha scritto per ultimo**. Se anche CMI lo "
                                + "gestisce, i due si sovrascrivono a vicenda e il risultato dipende dall'ordine, "
                                + "cioe' dal caso. La via pulita resta spegnere il suo modulo "
                                + "(plugins/CMI/Settings/Modules.yml → **tablist: false**).",
                        "Finche' resta acceso, **priority** (in tablist.yml) ci fa scrivere dopo di lui: ogni aggiornamento "
                                + "viene riscritto una seconda volta **{{cfg:priority.reassert-delay-ticks}}** "
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
                                + "parte con **player-name**, dove {name} e' il suo nome.")

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
                        "Ogni **{{cfg:update-interval-ticks}}** tick (20 tick = 1 secondo) e, in piu', un "
                                + "tick dopo ogni ingresso — cosi' mondo e coordinate sono gia' pronti e il nuovo "
                                + "arrivato non vede un tablist a meta'.",
                        "Abbassare l'intervallo rende il ping piu' reattivo ma fa lavorare il server piu' spesso, "
                                + "una volta per giocatore online: sotto i 10 tick non serve a niente che si veda.")

                .section("Le 80 slot fisse",
                        "Il gioco decide da solo quante colonne disegnare in base a quante voci ci sono: con pochi "
                                + "giocatori il tab e' una colonna sottile, con tanti si allarga. Se sotto ci deve "
                                + "stare una pergamena, quella misura non puo' ballare. Con **fixed-slots** (in tablist.yml) "
                                + "acceso il tab mostra sempre **{{cfg:fixed-slots.total}}** caselle, "
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

                .section("La MOTD della lista server",
                        "E' quello che si legge nella lista server prima di entrare: **due righe** di testo "
                                + "(la terza il client non la disegna), il numero dei giocatori e la **tendina** "
                                + "che esce passandoci sopra col mouse. Si scrive tutto in **motd.yml**.",
                        "Le due righe fisse sono **first-line** e **second-line**. Se si accende "
                                + "**random.enabled**, a ogni ping ne esce una a caso fra le voci di "
                                + "**random.messages** e le due righe fisse si ignorano: serve a non far leggere "
                                + "sempre la stessa cosa a chi apre la lista dieci volte al giorno. Acceso con la "
                                + "lista vuota, tornano le righe fisse — meglio che una MOTD vuota.",
                        "Nelle righe valgono i colori **&** e **&#RRGGBB**, e due segnaposto: **{online}** e "
                                + "**{max}**. Di segnaposto per-giocatore non ce ne sono e non possono essercene: "
                                + "al ping il server non sa CHI sta guardando, sa solo che qualcuno ha aperto la "
                                + "lista. Per lo stesso motivo qui PlaceholderAPI non c'entra.",
                        "**player-count.max** cambia il numero scritto accanto agli online senza far entrare "
                                + "nessuno in piu' (-1 = quello vero del server). **player-count.hide** nasconde "
                                + "il conto: al suo posto il client disegna le due frecce rosse dei server "
                                + "irraggiungibili, e la tendina sparisce con lui.",
                        "Questo modulo prende il posto del vecchio plugin **CustomMOTD**, che e' stato tolto dal "
                                + "server: due plugin sulla stessa MOTD si sovrascrivono a vicenda, e vince chi "
                                + "scrive per ultimo.")

                .section("MOTD e Velocity (quando ci sara' il proxy)",
                        "La MOTD la scrive **chi risponde al ping**. Oggi risponde il server, perche' il client "
                                + "ci parla diretto. Il giorno che davanti ci sara' **Velocity**, al ping "
                                + "rispondera' il proxy: il server dietro non lo vedra' nemmeno, e questo modulo "
                                + "— che e' un plugin del server — non potra' piu' farci niente.",
                        "Per quel giorno il plugin e' gia' spaccato in due: **come si compone** la MOTD (scelta "
                                + "della variante, segnaposto, colori, le due righe) sta in una classe che non "
                                + "sa niente di Bukkit, e **chi ascolta il ping** e' un file a parte di trenta "
                                + "righe. Sul proxy si riscrive solo il secondo, con lo stesso motd.yml e le "
                                + "stesse regole: la MOTD non va riscritta due volte ne' tenuta allineata a mano.")

                .commands()
                .permissions()
                // Gli interruttori stanno in un file loro (modules.yml) e le impostazioni di ogni
                // funzione nel suo (tablist.yml): qui si vedono col valore che hanno adesso sul
                // server. Il config.yml non compare finche' non ha chiavi: sarebbe una tabella vuota.
                .settingsFrom(modules.configurazione(), "Moduli (modules.yml)",
                        "tablist", "Il tablist del tasto Tab. Spento, il tablist resta quello di CMI (o del gioco).")

                .settingsFrom(modules.configurazioneDi(Modules.TABLIST), "Impostazioni del tablist (tablist.yml)",
                        "update-interval-ticks", "Ogni quanti tick si riscrivono intestazione, fondo e nomi.",
                        "player-name", "Come appare il nome nella lista: {name} e' il nome, valgono colori e placeholder.",
                        "priority.enabled", "Riscrive una seconda volta per arrivare dopo CMI. Spegnila se il tablist e' solo nostro.",
                        "priority.reassert-delay-ticks", "Quanti tick dopo arriva la seconda scrittura: alzalo se CMI si vede ancora per un istante.",
                        "fixed-slots.enabled", "Caselle fisse: il tab resta sempre della stessa misura. Serve ProtocolLib.",
                        "fixed-slots.total", "Quante caselle in tutto: il gioco ne disegna al massimo 80 (4 colonne x 20).",
                        "fixed-slots.empty-text", "Cosa c'e' scritto in una casella vuota: uno spazio la lascia muta.")

                .settingsFrom(modules.configurazioneDi(Modules.MOTD), "Impostazioni della MOTD (motd.yml)",
                        "first-line", "La prima riga della lista server. Colori & e &#RRGGBB, segnaposto {online} e {max}.",
                        "second-line", "La seconda riga. Una terza non si vedrebbe: il client ne disegna due.",
                        "random.enabled", "Acceso, a ogni ping esce una voce a caso di random.messages e le due righe fisse si ignorano.",
                        "random.messages", "Le varianti: una voce = una MOTD intera, le due righe separate da \\n.",
                        "hover", "Le righe della tendina sul numero giocatori. Vuota = resta l'elenco vero di chi e' online.",
                        "player-count.max", "Il massimo mostrato accanto agli online. -1 = quello vero; alzarlo non fa entrare nessuno in piu'.",
                        "player-count.hide", "Nasconde il conto: il client disegna le frecce dei server irraggiungibili, e niente tendina.")

                .issue("Le caselle vuote hanno una testa e le tacchette di connessione",
                        "Allora non sono le nostre, sono quelle di CMI: le nostre nascono senza testa e senza "
                                + "tacchette. Vuol dire che il suo modulo tablist e' ancora acceso e sta riempiendo "
                                + "lui. Spegnilo (Modules.yml → tablist: false) e riavvia: il tab torna nostro.")
                .issue("Ho spento tablist nel modules.yml ma il tab si vede ancora",
                        "Quello che vedi non e' piu' il nostro: col modulo spento questo plugin non scrive "
                                + "niente nel tablist, quindi resta quello di CMI (o, se anche il suo modulo e' "
                                + "spento, la lista base del gioco). Controlla di aver fatto "
                                + "/magixessentials reload dopo la modifica: la risposta in chat elenca i moduli "
                                + "e dice se il tablist risulta spento.")
                .issue("Ho cambiato una chiave del config nel repo e sul server non succede niente",
                        "Il deploy porta il jar, non i config: il file nella cartella del plugin sul server non viene toccato, ed e' quello che il plugin legge. Il valore nel jar vale solo per le chiavi che li' MANCANO. Quindi un valore gia' presente si cambia sul server (a mano, o col workflow deploy-plugin-config.yml), non nel repo. Del resto si occupa il plugin, a ogni avvio e a ogni reload: aggiunge le chiavi nuove al loro posto col loro commento, applica le rinomine portandosi dietro il valore che avevi scelto, e toglie le righe morte che il codice non legge piu' dai file a schema fisso, cioe' tutti tranne i cataloghi (i menu e le sanzioni no: li' le voci in piu' sono tue). Prima di ogni modifica fa una copia del file accanto all'originale, col nome che finisce in .bak-<data>, e nel log scrive che cosa ha cambiato.")
                .issue("Le slot fisse non compaiono",
                        "Guarda il log all'avvio: il plugin scrive «slot fisse attive: N caselle» quando ci "
                                + "riesce, e il motivo quando no (ProtocolLib assente, o struttura del pacchetto "
                                + "diversa). Se il messaggio dice che sono attive ma a schermo non si vedono, il "
                                + "tablist lo sta ancora riscrivendo CMI.")
                .issue("Ho cambiato la MOTD e nella lista server si legge ancora quella vecchia",
                        "Il client si tiene in memoria l'ultima MOTD che ha visto: finche' non ripinga, mostra "
                                + "quella. Togli il server dall'elenco e rimettilo, oppure aspetta. Se dopo un "
                                + "ping nuovo non e' cambiata, allora e' il file: hai fatto "
                                + "/magixessentials reload, e il modulo motd risulta acceso?")
                .issue("La MOTD non e' quella di motd.yml ma una che non ho scritto io",
                        "Qualcun altro sta scrivendo sullo stesso ping. Il vecchio plugin CustomMOTD e' stato "
                                + "tolto proprio per questo: se e' tornato nella cartella dei plugin, toglilo di "
                                + "nuovo. Col modulo motd spento, invece, vale la riga 'motd' di server.properties.")
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

                .never("Non rimettere CustomMOTD (o un altro plugin di MOTD) accanto a questo modulo: sulla "
                        + "stessa MOTD non si spartiscono il lavoro, vince chi scrive per ultimo e il risultato "
                        + "dipende dall'ordine di caricamento, cioe' dal caso.")
                .never("Non lasciare acceso anche il tablist di CMI: due plugin sullo stesso tablist non si "
                        + "spartiscono il lavoro, se lo strappano di mano.")
                .never("Non scrivere i numeri del logo qui: dimensione e posizione stanno nel config di "
                        + "MagixFactions, che e' anche il plugin che costruisce il resource pack.")
                .write();
    }
}
