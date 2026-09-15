package com.teolo.magixessentials.nametag;

import com.teolo.magixessentials.util.TextFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * La targhetta <b>del gioco</b>: quella che il client disegna da se' sopra la testa, e che si scrive
 * con le <b>squadre</b> dello scoreboard — prefisso, nome vero, suffisso.
 *
 * <h2>Cosa si puo' e cosa non si puo'</h2>
 * Costa quasi niente, sfuma con la distanza e sparisce da sola quando il giocatore si accuccia o e'
 * invisibile: sono regole del gioco, non nostre. In cambio e' <b>una riga sola</b>, e il nome vero —
 * il pezzo in mezzo — puo' avere solo i 16 colori storici, perche' nel protocollo la squadra porta un
 * colore scelto fra quelli, non un esadecimale. Per le righe in piu' e per il colore esatto c'e'
 * {@link DisplayLines}.
 *
 * <h2>Su quale lavagna si scrive</h2>
 * Una squadra vive in una <b>lavagna</b> (scoreboard), e ogni giocatore vede quella che gli e' stata
 * assegnata: di norma quella principale, ma un plugin col pannello laterale (CMI) gliene da' una sua.
 * Percio' qui non si sceglie una lavagna e basta: si scrive su <b>tutte quelle che i giocatori online
 * hanno davvero</b>. Cosi' la targhetta si vede anche insieme al pannello di qualcun altro, e non
 * gliene togliamo il posto.
 *
 * <p>Con la targhetta <b>per spettatore</b> (i placeholder {@code %rel_...%}) questo non basta: due
 * giocatori che guardano la stessa lavagna vedono per forza la stessa cosa. Li' serve una lavagna
 * <b>nostra</b> per ciascuno — e allora si', il pannello di un altro plugin salta. Lo decide il
 * config, e la guida lo dice.</p>
 *
 * <h2>Si riscrive solo quello che cambia</h2>
 * Una squadra aggiornata manda un pacchetto a tutti quelli che guardano quella lavagna: riscrivere a
 * ogni giro ottanta targhette identiche sarebbero migliaia di pacchetti al secondo per non cambiare
 * niente. Qui si tiene da parte cio' che e' stato scritto (per lavagna, per giocatore) e si scrive
 * solo alla differenza. La cache e' anche la rete di sicurezza contro un altro plugin che cambia la
 * lavagna sotto i piedi: una lavagna nuova non ha cache, quindi viene riscritta tutta.
 */
public final class NameTeams {

    /** Separatore della firma: un carattere che nel testo di una targhetta non puo' esserci. */
    private static final char SEPARATOR = '\0';

    /** Le lavagne nostre, una per giocatore: solo quando la targhetta e' per spettatore. */
    private final Map<UUID, Scoreboard> own = new HashMap<>();
    /** Quello che abbiamo scritto: lavagna -> (giocatore -> firma di prefisso, suffisso e visibilita'). */
    private final Map<Scoreboard, Map<UUID, String>> written = new IdentityHashMap<>();
    /** Le squadre create da noi: all'uscita si disfa solo il nostro, non quelle di altri plugin. */
    private final Map<Scoreboard, Set<String>> created = new IdentityHashMap<>();

    private boolean perViewer;

    /**
     * Targhetta diversa per ogni spettatore: da qui in poi ogni giocatore riceve una lavagna nostra.
     * Da chiamare all'avvio del modulo, prima del primo giro.
     */
    public void perViewer(boolean perViewer) {
        this.perViewer = perViewer;
    }

    /**
     * Le lavagne su cui scrivere, cioe' quelle che i giocatori online stanno guardando adesso (senza
     * ripetizioni: quasi sempre e' una sola, la principale). Dimentica insieme quelle sparite —
     * altrimenti la cache si porterebbe dietro la lavagna di ogni plugin che ne ha cambiata una.
     */
    public List<Scoreboard> boards(Collection<? extends Player> online) {
        List<Scoreboard> out = new ArrayList<>();
        Set<Scoreboard> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Player viewer : online) {
            Scoreboard board = boardOf(viewer);
            if (seen.add(board)) {
                out.add(board);
            }
        }
        written.keySet().retainAll(seen);
        created.keySet().retainAll(seen);
        return out;
    }

    /**
     * La lavagna di un giocatore: la sua, quella che ha adesso — oppure, con la targhetta per
     * spettatore, quella nostra, assegnata la prima volta che serve.
     */
    public Scoreboard boardOf(Player viewer) {
        if (!perViewer) {
            return viewer.getScoreboard();
        }
        Scoreboard board = own.computeIfAbsent(viewer.getUniqueId(),
                id -> Bukkit.getScoreboardManager().getNewScoreboard());
        if (viewer.getScoreboard() != board) {
            viewer.setScoreboard(board);
        }
        return board;
    }

    /**
     * Scrive la targhetta di un giocatore su una lavagna. {@code prefix} e {@code suffix} sono i due
     * pezzi intorno al nome vero, gia' coi placeholder risolti; {@code hideName} nasconde la targhetta
     * del gioco per intero — serve quando le righe le disegniamo noi e il nome vero sarebbe un doppione.
     *
     * @param nameColor se true il nome vero prende l'ultimo colore scritto nel prefisso, ridotto al
     *                  piu' vicino fra i 16 del gioco (un esadecimale, li', non esiste)
     */
    public void write(Scoreboard board, Player target, String prefix, String suffix,
                      boolean hideName, boolean nameColor) {
        String signature = prefix + SEPARATOR + suffix + SEPARATOR + hideName + SEPARATOR + nameColor;
        Map<UUID, String> onBoard = written.computeIfAbsent(board, b -> new HashMap<>());
        if (signature.equals(onBoard.get(target.getUniqueId()))) {
            return;
        }
        // Il nome del giocatore come nome della squadra: e' unico, e' stabile fra un avvio e l'altro,
        // sta nei 16 caratteri che il protocollo concede, e lascia l'ordine del tablist com'era
        // (alfabetico) invece di rimescolarlo.
        String name = target.getName();
        try {
            Team team = board.getTeam(name);
            if (team == null) {
                team = board.registerNewTeam(name);
                created.computeIfAbsent(board, b -> new HashSet<>()).add(name);
            }
            team.prefix(TextFormat.component(prefix));
            team.suffix(TextFormat.component(suffix));
            NamedTextColor color = nameColor ? TextFormat.nearestNamed(prefix) : null;
            team.color(color != null ? color : NamedTextColor.WHITE);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY,
                    hideName ? Team.OptionStatus.NEVER : Team.OptionStatus.ALWAYS);
            // Ogni giocatore sta nella squadra da solo: "compagni" non ce ne sono, e vedere la sagoma
            // di un invisibile perche' e' della propria squadra sarebbe un difetto, non una funzione.
            team.setCanSeeFriendlyInvisibles(false);
            if (!team.hasEntry(name)) {
                team.addEntry(name);
            }
            onBoard.put(target.getUniqueId(), signature);
        } catch (IllegalStateException e) {
            // La lavagna (o la squadra) e' stata buttata via da chi l'aveva: si dimentica quel che
            // credevamo di aver scritto e al giro dopo si riscrive da zero.
            written.remove(board);
            created.remove(board);
        }
    }

    /** Dimentica un giocatore uscito: la sua squadra sparisce da tutte le lavagne che l'avevano. */
    public void forget(Player target) {
        for (Map.Entry<Scoreboard, Map<UUID, String>> e : written.entrySet()) {
            e.getValue().remove(target.getUniqueId());
            undo(e.getKey(), target.getName());
        }
        own.remove(target.getUniqueId());
    }

    /**
     * Rimette tutto com'era: le squadre che abbiamo creato si cancellano, quelle di altri plugin
     * tornano senza prefisso e con la targhetta visibile, e chi aveva una lavagna nostra torna a
     * quella principale. Senza questo, un reload lascerebbe in giro prefissi che nessuno aggiorna piu'.
     */
    public void clear() {
        for (Map.Entry<Scoreboard, Map<UUID, String>> e : new ArrayList<>(written.entrySet())) {
            for (UUID id : new ArrayList<>(e.getValue().keySet())) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    undo(e.getKey(), p.getName());
                }
            }
        }
        written.clear();
        created.clear();
        if (own.isEmpty()) {
            return;
        }
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (UUID id : own.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getScoreboard() != main) {
                p.setScoreboard(main);
            }
        }
        own.clear();
    }

    /**
     * Disfa quello che abbiamo fatto a una squadra: se l'avevamo creata noi la si cancella, se era di
     * un altro plugin le si tolgono soltanto prefisso, suffisso e il nascondi-targhetta.
     */
    private void undo(Scoreboard board, String name) {
        try {
            Team team = board.getTeam(name);
            if (team == null) {
                return;
            }
            Set<String> mine = created.get(board);
            if (mine != null && mine.remove(name)) {
                team.unregister();
                return;
            }
            team.prefix(Component.empty());
            team.suffix(Component.empty());
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
        } catch (IllegalStateException e) {
            // Squadra o lavagna gia' spariti: niente da disfare.
        }
    }
}
