package com.teolo.magixessentials;

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
                        "**Il limite, ed e' importante saperlo:** valgono intestazione, fondo e nomi. Le caselle "
                                + "**FINTE** che CMI inietta — le 80 slot con la testa e le tacchette di connessione — "
                                + "sono voci sue, mandate al client via pacchetto: da qui **non si tolgono**, nessuna "
                                + "priorita' le raggiunge. O si spegne il suo modulo, o si tocca la sua configurazione. "
                                + "All'avvio il plugin controlla da solo il Modules.yml di CMI e mette un avviso nel "
                                + "log se il conflitto c'e'.",
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

                .section("Le 80 slot fisse: non ci sono ancora",
                        "**tablist.fixed-slots** e' solo predisposto. Mostrare sempre 80 caselle (4 colonne da 20) "
                                + "per poterci mettere una pergamena di sfondo richiede l'invio di caselle finte al "
                                + "client (ProtocolLib), e non e' ancora scritto. Se lo si accende, nel log compare "
                                + "un avviso e il plugin tira dritto col tablist normale.")

                .commands()
                .permissions()
                .settings(
                        "tablist.enabled", "Interruttore generale: spento, il tablist resta quello di CMI (o del gioco).",
                        "tablist.update-interval-ticks", "Ogni quanti tick si riscrivono intestazione, fondo e nomi.",
                        "tablist.player-name", "Come appare il nome nella lista: {name} e' il nome, valgono colori e placeholder.",
                        "tablist.priority.enabled", "Riscrive una seconda volta per arrivare dopo CMI. Spegnila se il tablist e' solo nostro.",
                        "tablist.priority.reassert-delay-ticks", "Quanti tick dopo arriva la seconda scrittura: alzalo se CMI si vede ancora per un istante.",
                        "tablist.fixed-slots.enabled", "Predisposizione per le 80 caselle fisse: NON ancora implementata, lasciare false.")

                .issue("Le caselle vuote hanno una testa e le tacchette di connessione",
                        "Non sono nostre: sono le voci FINTE con cui CMI riempie il tablist a 80 slot, e non si "
                                + "possono togliere da qui — nessun valore di tablist.priority le raggiunge. Si "
                                + "spegne il suo modulo tablist (Modules.yml → tablist: false), oppure si toglie il "
                                + "riempimento nel suo TabList.yml. Quando le 80 slot le fara' questo plugin, le "
                                + "caselle vuote saranno senza testa (profilo senza texture) e senza tacchette "
                                + "(latenza -1): oggi non lo sono perche' non sono nostre.")
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
