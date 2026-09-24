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

        // Puro I/O su file: non deve bloccare il tick di avvio.
        Bukkit.getScheduler().runTaskAsynchronously(this, this::writeStaffGuide);

        getLogger().info("Avviato: " + boardManager.definitions().size() + " scoreboard configurate, "
                + "PlaceholderAPI " + (Papi.enabled() ? "collegato" : "non trovato") + ", WorldGuard "
                + (worldGuardHook.enabled() ? "collegato" : "non trovato") + ".");
    }

    @Override
    public void onDisable() {
        if (boardManager != null) boardManager.shutdown();
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
                .intro("Mostra a ogni giocatore una scoreboard laterale diversa a seconda di chi e', "
                        + "dov'e' e in che regione si trova, coi placeholder di PlaceholderAPI (compresi "
                        + "quelli degli altri plugin Magix) gia' risolti.")

                .section("Come si sceglie la scoreboard",
                        "Ogni scoreboard ha condizioni facoltative (permesso, mondi, regioni WorldGuard) e un "
                                + "peso (weight). Fra tutte quelle che corrispondono al giocatore vince quella col "
                                + "peso piu' alto.",
                        "A parita' di peso decide l'ordine di priorita' del config (priority-order): una "
                                + "scoreboard con una condizione piu' in alto in quell'elenco batte una che ne ha "
                                + "solo una piu' in basso — di serie una regione batte un permesso, che batte un "
                                + "mondo. /mscoreboard debug <giocatore> mostra il punteggio di ognuna, utile "
                                + "quando il risultato non torna.")

                .section("Titolo, righe, animazioni",
                        "Titolo e ogni riga hanno una lista di \"frames\" (uno o piu' testi) e un "
                                + "interval-ticks: con un solo frame il testo e' fisso (i placeholder si "
                                + "aggiornano comunque), con piu' di uno si alternano in ordine ogni tot tick — "
                                + "sia per un'animazione del singolo testo sia per righe del tutto diverse che "
                                + "si alternano nello stesso posto.",
                        "L'avanzamento e' sincronizzato fra tutti i giocatori (un orologio comune, non uno a "
                                + "testa): due giocatori che vedono la stessa riga animata la vedono nello stesso "
                                + "fotogramma.")

                .detailedCommands()
                .commands()
                .permissions()
                .settings(
                        "update-interval-ticks", "Ogni quanti tick si ricalcola la scoreboard giusta e si fa avanzare l'animazione.",
                        "priority-order", "Lo spareggio a parita' di peso: quale condizione conta di piu'.")

                .issue("Un giocatore non vede nessuna scoreboard",
                        "O l'ha nascosta con /mscoreboard toggle, o nessuna scoreboard configurata corrisponde a "
                                + "lui in quel momento (nessuna scoreboard di riserva senza condizioni). "
                                + "/mscoreboard debug <giocatore> mostra quale, se una, dovrebbe vedere e perche'.")
                .issue("Le condizioni \"regions\" non funzionano mai",
                        "WorldGuard non e' installato sul server: senza, quelle condizioni non possono mai "
                                + "corrispondere (il plugin lo scrive in console all'avvio). /mscoreboard list mostra "
                                + "se WorldGuard risulta collegato.")
                .issue("I placeholder %...% compaiono cosi' come sono, non risolti",
                        "PlaceholderAPI non e' installato. Senza, ne' i placeholder standard ne' quelli degli "
                                + "altri plugin Magix vengono risolti: il testo resta letterale.")

                .never("Non mettere interval-ticks piu' basso di update-interval-ticks: l'animazione avanza "
                        + "comunque solo a ogni giro del refresh generale, un valore piu' fine non cambia niente.")
                .never("Non affidarti all'ordine nel config per lo spareggio: usa priority-order (o un peso "
                        + "diverso), altrimenti un riordino accidentale delle scoreboard cambia chi vince.")
                .write();
    }
}
