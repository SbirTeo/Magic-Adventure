package com.teolo.magixscoreboard.board;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Arrays;
import java.util.Objects;

/**
 * La scoreboard laterale personale di UN giocatore: un obiettivo con fino a {@value #MAX_LINES}
 * righe (il massimo che Minecraft mostra), dentro qualunque {@link Scoreboard} il giocatore abbia
 * ATTUALMENTE — non una nostra imposta con la forza.
 *
 * <h2>Perche' ci si aggancia invece di imporsi</h2>
 * Un giocatore puo' avere UNA sola scoreboard attiva alla volta. Altri plugin (tipicamente quelli
 * che colorano i nomi in tablist, qui CMI) gliene assegnano una propria a loro volta: se anche noi
 * rispondiamo riassegnando SEMPRE la nostra con {@code player.setScoreboard(...)}, i due plugin si
 * riprendono il possesso a turno, e il risultato visibile e' un lampeggio continuo (la sidebar
 * appare e sparisce a ogni giro dell'uno o dell'altro). La strada giusta e' non litigare per il
 * possesso: ci si mette il PROPRIO obiettivo/squadre SOPRA qualunque scoreboard il giocatore abbia
 * in quel momento, propria o di qualcun altro, e si ricostruiscono al volo solo quando cambia
 * l'oggetto sotto (vedi {@link #ensureAttached}). L'unico caso in cui gliene serve una nuova di
 * zecca e' quando non ne ha ancora una sua (ha ancora quella CONDIVISA di tutti, che non puo'
 * portare contenuti diversi da giocatore a giocatore).
 *
 * <h2>Perche' le righe sono squadre, non punteggi diretti</h2>
 * Un punteggio della sidebar vanilla e' legato a un "entry" (di norma il nome di un giocatore/team):
 * per avere un testo lungo, colorato e magari ripetuto identico su piu' righe (due frame diversi
 * possono produrre lo stesso testo) si usa il trucco standard: 15 entry invisibili e uniche (un
 * codice colore diverso per riga), ciascuna dentro una squadra il cui PREFISSO e' la riga vera. Cosi'
 * il testo mostrato non ha alcun vincolo di unicita' ne' di lunghezza.
 *
 * <h2>Come si evita lo sfarfallio</h2>
 * Titolo e ogni riga si riscrivono SOLO se il {@link Component} e' cambiato rispetto all'ultimo
 * render: a ogni refresh quasi tutte le righe sono identiche a un attimo prima (l'animazione avanza
 * ogni tot tick, non a ogni tick), e riscrivere comunque manderebbe un pacchetto a vuoto per ognuna.
 */
final class PlayerBoard {

    static final int MAX_LINES = 15;
    private static final String OBJECTIVE_NAME = "magixscoreboard";
    private static final String TEAM_PREFIX = "msb";

    private Scoreboard scoreboard;
    private Objective objective;
    private final Team[] teams = new Team[MAX_LINES];
    private final String[] entries = new String[MAX_LINES];
    private final Component[] shownLines = new Component[MAX_LINES];
    private Component shownTitle;
    private boolean visible;
    private String currentBoardId;

    PlayerBoard(Player player) {
        ensureAttached(player);
    }

    /** Un'entry invisibile e unica per lo slot i (0-14): un codice colore legacy diverso ciascuna. */
    private static String invisibleEntry(int i) {
        char[] hex = "0123456789abcdef".toCharArray();
        return "" + ChatColor.COLOR_CHAR + hex[i] + ChatColor.COLOR_CHAR + 'r';
    }

    String currentBoardId() { return currentBoardId; }

    /**
     * Si assicura che il nostro obiettivo esista sulla scoreboard ATTUALE del giocatore, senza
     * portargliela via se e' gia' sua (vedi la spiegazione della classe). Va chiamato a ogni refresh:
     * e' economico quando non e' cambiato niente (un solo confronto di riferimento).
     */
    void ensureAttached(Player player) {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        Scoreboard current = player.getScoreboard();
        if (current == null || current.equals(main)) {
            // Sulla scoreboard condivisa non ci si puo' mettere contenuto diverso per giocatore:
            // gliene serve una personale. Se ne avevamo gia' creata una prima, e' ancora valida
            // (il giocatore e' solo tornato sulla condivisa nel frattempo): la si riassegna.
            if (scoreboard == null || scoreboard.equals(main)) {
                attachTo(Bukkit.getScoreboardManager().getNewScoreboard());
            }
            player.setScoreboard(scoreboard);
            return;
        }
        if (!current.equals(scoreboard)) {
            // Il giocatore ha ADESSO una scoreboard diversa dall'ultima che conoscevamo (sua, o di
            // un altro plugin): ci si aggancia sopra quella, senza rimpiazzarla.
            attachTo(current);
        }
    }

    /** (Ri)registra il nostro obiettivo e le nostre squadre sulla scoreboard indicata, riusando
     *  quelli gia' presenti se ci siamo gia' stati (es. il giocatore e' tornato su una nostra di
     *  prima) invece di ricrearli da capo. */
    private void attachTo(Scoreboard target) {
        this.scoreboard = target;
        Objective existing = target.getObjective(OBJECTIVE_NAME);
        this.objective = existing != null ? existing
                : target.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, Component.empty());
        for (int i = 0; i < MAX_LINES; i++) {
            entries[i] = invisibleEntry(i);
            Team team = target.getTeam(TEAM_PREFIX + i);
            if (team == null) team = target.registerNewTeam(TEAM_PREFIX + i);
            if (!team.hasEntry(entries[i])) team.addEntry(entries[i]);
            teams[i] = team;
        }
        // L'obiettivo e' un oggetto NUOVO (anche se il contenuto logico e' lo stesso di prima): va
        // riscritto per intero al prossimo render, e la visibilita' va riapplicata su di lui.
        shownTitle = null;
        Arrays.fill(shownLines, null);
        if (visible) objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    void setVisible(boolean visible) {
        if (this.visible == visible) return;
        this.visible = visible;
        objective.setDisplaySlot(visible ? DisplaySlot.SIDEBAR : null);
    }

    /** Riscrive titolo e righe SOLO dove sono davvero cambiati rispetto all'ultimo render. */
    void render(String boardId, Component title, java.util.List<Component> lines) {
        currentBoardId = boardId;
        if (!title.equals(shownTitle)) {
            objective.displayName(title);
            shownTitle = title;
        }
        int n = Math.min(lines.size(), MAX_LINES);
        for (int i = 0; i < MAX_LINES; i++) {
            Component wanted = i < n ? lines.get(i) : null;
            if (Objects.equals(wanted, shownLines[i])) continue;
            shownLines[i] = wanted;
            if (wanted == null) {
                scoreboard.resetScores(entries[i]);
            } else {
                teams[i].prefix(wanted);
                // La riga piu' in alto ha il punteggio piu' alto: e' cosi' che la sidebar vanilla ordina.
                objective.getScore(entries[i]).setScore(n - i);
            }
        }
    }

    /** Sganciata dal giocatore (disconnessione, o plugin che si ferma): niente resta appeso. */
    void destroy(Player player) {
        for (Team team : teams) {
            if (team != null) {
                try { team.unregister(); } catch (IllegalStateException ignored) {}
            }
        }
        if (objective != null) {
            try { objective.unregister(); } catch (IllegalStateException ignored) {}
        }
    }
}
