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

import java.util.Objects;

/**
 * La scoreboard laterale personale di UN giocatore: un {@link Scoreboard} Bukkit tutto suo, con un
 * solo obiettivo e fino a {@value #MAX_LINES} righe (il massimo che Minecraft mostra).
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

    private final Scoreboard scoreboard;
    private final Objective objective;
    private final Team[] teams = new Team[MAX_LINES];
    private final String[] entries = new String[MAX_LINES];
    private final Component[] shownLines = new Component[MAX_LINES];
    private Component shownTitle;
    private boolean visible;
    private String currentBoardId;

    PlayerBoard(Player player) {
        this.scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        this.objective = scoreboard.registerNewObjective("msb", Criteria.DUMMY, Component.empty());
        for (int i = 0; i < MAX_LINES; i++) {
            entries[i] = invisibleEntry(i);
            Team team = scoreboard.registerNewTeam("msb" + i);
            team.addEntry(entries[i]);
            teams[i] = team;
        }
        player.setScoreboard(scoreboard);
    }

    /** Un'entry invisibile e unica per lo slot i (0-14): un codice colore legacy diverso ciascuna. */
    private static String invisibleEntry(int i) {
        char[] hex = "0123456789abcdef".toCharArray();
        return "" + ChatColor.COLOR_CHAR + hex[i] + ChatColor.COLOR_CHAR + 'r';
    }

    String currentBoardId() { return currentBoardId; }

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
            if (team != null) team.unregister();
        }
        objective.unregister();
        if (player.isOnline() && player.getScoreboard() == scoreboard) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }
}
