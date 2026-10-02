package com.teolo.magixscoreboard;

import com.teolo.magixscoreboard.board.BoardManager;
import com.teolo.magixscoreboard.command.MagixScoreboardCommand;
import com.teolo.magixscoreboard.hook.Papi;
import com.teolo.magixscoreboard.hook.WorldGuardHook;
import com.teolo.magixscoreboard.lang.Messages;
import com.teolo.magixscoreboard.util.ConfigAlign;
import com.teolo.magixscoreboard.util.ConfigValues;
import com.teolo.magixscoreboard.util.StaffGuide;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MagixScoreboard - scoreboard laterale con placeholder Magix/PAPI, condizioni di permesso/mondo/
 * regione WorldGuard e un peso che decide chi vince quando piu' di una corrisponde.
 *
 * Il lavoro vero e' tutto in {@link BoardManager}: questa classe si limita ad avviarlo, a ricaricarlo
 * e a ripulire la scoreboard di chi si disconnette.
 */
public final class MagixScoreboard extends JavaPlugin implements Listener {

    private Messages messages;
    private WorldGuardHook worldGuardHook;
    private BoardManager boardManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // I file di configurazione SUL SERVER allineati a quelli del jar: le chiavi nuove
        // compaiono da sole, al loro posto e col loro commento, senza toccare i valori
        // gia' scelti (vedi util/ConfigAlign).
        ConfigAlign.alignAll(this);
        reloadConfig();
        getDataFolder().mkdirs();

        messages = new Messages(this);
        Papi.setup();
        worldGuardHook = new WorldGuardHook();
        boardManager = new BoardManager(this, worldGuardHook);
        boardManager.start();

        PluginCommand cmd = getCommand("magixscoreboard");
        if (cmd != null) {
            MagixScoreboardCommand executor = new MagixScoreboardCommand(this, messages, boardManager);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
        Bukkit.getPluginManager().registerEvents(this, this);

        // Sfondo e bordo della sidebar: uno shader nel pacchetto risorse unico di MagixPack (softdepend,
        // quindi e' gia' acceso). Senza MagixPack lo sfondo resta quello vanilla.
        com.teolo.magixscoreboard.hook.MagixPackHook.register(this);
        com.teolo.magixscoreboard.hook.MagixPackHook.refreshFactionsPack(this);

        // Puro I/O su file: non deve bloccare il tick di avvio.
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);

        getLogger().info("Avviato: " + boardManager.definitions().size() + " scoreboard configurate, "
                + "PlaceholderAPI " + (Papi.enabled() ? "collegato" : "non trovato") + ", WorldGuard "
                + (worldGuardHook.enabled() ? "collegato" : "non trovato") + ".");
    }

    @Override
    public void onDisable() {
        if (boardManager != null) boardManager.shutdown();
        com.teolo.magixscoreboard.hook.MagixPackHook.unregister(this);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (boardManager != null) boardManager.forget(event.getPlayer());
    }

    /** Ricarica config.yml e messages.yml e riparte da capo (/mscoreboard reload). */
    public void reloadEverything() {
        ConfigAlign.alignAll(this);
        reloadConfig();
        messages.reload();
        boardManager.reload();
        // Colori e opacita' dello sfondo sono nello shader: si ricostruisce il pacchetto.
        com.teolo.magixscoreboard.hook.MagixPackHook.register(this);
        com.teolo.magixscoreboard.hook.MagixPackHook.refreshFactionsPack(this);
        getLogger().info("Configurazione ricaricata: " + boardManager.definitions().size() + " scoreboard configurate.");
    }

    public Messages messages() { return messages; }
    public WorldGuardHook worldGuardHook() { return worldGuardHook; }
    public BoardManager boardManager() { return boardManager; }

    // ------------------------------------------------------------- GUIDA PER LO STAFF

    /**
     * Capitolo di MagixScoreboard nella guida del gestionale. Comandi, permessi e valori di
     * configurazione non si ricopiano: li legge da solo. Vedi plugins-src/GUIDA-STAFF.md.
     */
    private void writeStaffGuide() {
        StaffGuide.create(this, "MagixScoreboard — scoreboard laterale", 65)
                .values(new ConfigValues(this))
                .intro("Mostra a ogni giocatore una scoreboard laterale diversa a seconda di chi è, "
                        + "dov'e' e in che regione si trova, coi placeholder di PlaceholderAPI (compresi "
                        + "quelli degli altri plugin Magix) già risolti.")

                .section("Come si sceglie la scoreboard",
                        "Ogni scoreboard ha condizioni facoltative (permesso, mondi, regioni WorldGuard, "
                                + "confronti su placeholder di PlaceholderAPI) e un peso (weight). Fra tutte "
                                + "quelle che corrispondono al giocatore vince quella col peso più alto.",
                        "A parità di peso decide l'ordine di priorità del config (priority-order): una "
                                + "scoreboard con una condizione più in alto in quell'elenco batte una che ne ha "
                                + "solo una più in basso — di serie una regione batte un permesso, che batte un "
                                + "mondo, che batte un confronto su placeholder. /mscoreboard debug <giocatore> "
                                + "mostra il punteggio di ognuna, utile quando il risultato non torna.",
                        "Le condizioni sui placeholder (chiave \"placeholders\") sono nella forma "
                                + "\"%placeholder% OP valore\" (OP fra >= <= == != > <) — es. "
                                + "\"%magixfactions_faction% != \" mostra una scoreboard solo a chi ha una "
                                + "fazione. Con due lati numerici il confronto è numerico, altrimenti testuale "
                                + "(senza distinguere maiuscole/minuscole). Con più di una condizione, "
                                + "\"placeholders-mode\" decide se servono TUTTE (all, il default) o ne basta UNA "
                                + "(any).")

                .section("Titolo, righe, animazioni",
                        "Titolo e ogni riga hanno una lista di \"frames\" (uno o più testi) e un "
                                + "interval-ticks: con un solo frame il testo è fisso (i placeholder si "
                                + "aggiornano comunque), con più di uno si alternano in ordine ogni tot tick — "
                                + "sia per un'animazione del singolo testo sia per righe del tutto diverse che "
                                + "si alternano nello stesso posto.",
                        "L'avanzamento è sincronizzato fra tutti i giocatori (un orologio comune, non uno a "
                                + "testa): due giocatori che vedono la stessa riga animata la vedono nello stesso "
                                + "fotogramma.",
                        "Con \"scroll\" (width, gap, speed-ticks) sotto un titolo o una riga, il testo scorre "
                                + "da destra verso sinistra in una finestra di width caratteri, come un'insegna: "
                                + "si applica al testo già risolto, placeholder e colori compresi (es. "
                                + "%magixfactions_status% che scorre). La scoreboard si ridisegna da sola al passo "
                                + "dello scorrimento, senza toccare update-interval-ticks.")

                .section("Sfondo e bordo della sidebar",
                        "Il riquadro semitrasparente dietro la sidebar lo disegna il client, non il plugin: "
                                + "MagixScoreboard lo cambia con uno shader che registra nel pacchetto risorse di "
                                + "MagixPack (senza MagixPack resta vanilla). Da sidebar-background si sceglie colore "
                                + "e opacità (uguali per titolo e righe) oppure lo si spegne del tutto, e si può "
                                + "aggiungere un bordo sfumato da sinistra a destra sui lati sopra, sinistra e sotto.",
                        "Le modifiche entrano con /mscoreboard reload, ma i giocatori già connessi le vedono solo "
                                + "quando riscaricano il pacchetto (al prossimo ingresso). Lo shader riconosce la "
                                + "sidebar dal suo colore vanilla: se un giocatore ha tolto \"Sfondo testo solo per la "
                                + "chat\" nelle opzioni di accessibilità, per lui lo sfondo resta quello di serie.")
                .section("Posizione verticale della sidebar",
                        "Il client mette la sidebar sempre un po' sopra la metà dello schermo, e da server non "
                                + "si cambia. sidebar-position.offset-y la SPOSTA (pixel dell'interfaccia, positivo = "
                                + "in basso) con gli shader del pacchetto: lo sfondo lo sposta MagixScoreboard, le "
                                + "scritte e le icone lo shader del testo di MagixFactions, che legge lo stesso valore "
                                + "(serve MagixFactions installato, altrimenti si sposta solo lo sfondo).",
                        "Lo shader del testo riconosce le scritte della sidebar da un marchio invisibile nel colore "
                                + "(MagixScoreboard sposta ogni colore della sidebar di pochissimo, al massimo 4 "
                                + "sfumature su 255): tooltip degli oggetti, menu e chat restano al loro posto anche "
                                + "se passano nella fascia destra. Per sicurezza sposta solo le scritte marchiate "
                                + "che cadono in quella fascia (larga zone-width) all'altezza della sidebar.")
                .detailedCommands()
                .commands()
                .permissions()
                .settings(
                        "update-interval-ticks", "Ogni quanti tick si ricalcola la scoreboard giusta e si aggiornano i placeholder "
                                + "(le animazioni seguono i propri interval-ticks, anche se più bassi).",
                        "priority-order", "Lo spareggio a parità di peso: quale condizione conta di più.")

                .issue("Un giocatore non vede nessuna scoreboard",
                        "O l'ha nascosta con /mscoreboard toggle, o nessuna scoreboard configurata corrisponde a "
                                + "lui in quel momento (nessuna scoreboard di riserva senza condizioni). "
                                + "/mscoreboard debug <giocatore> mostra quale, se una, dovrebbe vedere e perché.")
                .issue("Le condizioni \"regions\" non funzionano mai",
                        "WorldGuard non è installato sul server: senza, quelle condizioni non possono mai "
                                + "corrispondere (il plugin lo scrive in console all'avvio). /mscoreboard list mostra "
                                + "se WorldGuard risulta collegato.")
                .issue("I placeholder %...% compaiono così come sono, non risolti",
                        "PlaceholderAPI non è installato. Senza, né i placeholder standard né quelli degli "
                                + "altri plugin Magix vengono risolti: il testo resta letterale.")

                .never("Non affidarti all'ordine nel config per lo spareggio: usa priority-order (o un peso "
                        + "diverso), altrimenti un riordino accidentale delle scoreboard cambia chi vince.")
                .write();
    }
}
