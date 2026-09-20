package com.teolo.magixentities.manage;

import com.teolo.magixentities.model.NpcDef;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Nasconde la targhetta VANILLA di un Mannequin: quella che il client disegna DA SOLO, col nome
 * del suo PROFILO ({@link NpcDef#skinNick()}), quando ci si punta vicino — un meccanismo diverso
 * e indipendente dal customName "mob" che {@code setCustomNameVisible} gia' spegne (quello sparisce
 * a qualunque distanza appena l'opzione {@code nametag} e' off; questo no, perche' e' lo stesso
 * usato per un giocatore VERO: vedi {@code MagixEssentials/nametag/NameTeams}). L'unico modo senza
 * ProtocolLib e' la stessa squadra dello scoreboard con {@code NAME_TAG_VISIBILITY=NEVER}.
 *
 * <p><b>Solo per entita' NON a specchio-skin.</b> Su uno specchio il profilo porta il nome VERO
 * del giocatore che sta guardando in quel momento ({@code owner.getName()}): nasconderlo cosi'
 * spegnerebbe (o gli scambierebbe la squadra) anche la SUA targhetta personale ovunque sul
 * server, non solo su questa statua. Per quel caso non c'e' un modo sicuro senza ProtocolLib: da
 * vicino, mirandola, resta visibile anche con {@code nametag off}.</p>
 *
 * <p>Lo stato desiderato si ricalcola per INTERO a ogni controllo periodico (vedi
 * {@link NpcManager#ensureAll()}), mai inseguendo la singola modifica: cosi' una rinomina, un
 * cambio skin o una rimozione non lasciano MAI un nome dimenticato nella squadra — si allinea da
 * solo, come {@code util/ConfigAlign} fa per i config. Le stesse chiamate vengono ripetute anche
 * subito dopo i comandi che cambiano nome/skin/tipo, per un effetto immediato in chat.</p>
 */
final class EntityNameTags {

    private static final String TEAM = "mentities_hide";

    private Set<String> hidden = Set.of();

    /** Ricalcola chi deve essere nascosto adesso e allinea tutte le lavagne in uso. */
    void sync(Collection<NpcDef> all) {
        Set<String> want = new HashSet<>();
        for (NpcDef d : all) {
            if (d.isPlayerType() && !d.isSkinMirror() && !d.opt("nametag", true)) {
                want.add(d.skinNick());
            }
        }
        if (want.equals(hidden)) return;
        for (Scoreboard board : boards()) {
            applyTo(board, want);
        }
        hidden = want;
    }

    /** Toglie tutto quello che avevamo nascosto (spegnimento del plugin). */
    void clear() {
        if (hidden.isEmpty()) return;
        for (Scoreboard board : boards()) {
            applyTo(board, Set.of());
        }
        hidden = Set.of();
    }

    private void applyTo(Scoreboard board, Set<String> want) {
        try {
            Team team = board.getTeam(TEAM);
            if (want.isEmpty()) {
                if (team != null) team.unregister();
                return;
            }
            if (team == null) {
                team = board.registerNewTeam(TEAM);
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            }
            for (String name : new HashSet<>(team.getEntries())) {
                if (!want.contains(name)) team.removeEntry(name);
            }
            for (String name : want) {
                if (!team.hasEntry(name)) team.addEntry(name);
            }
        } catch (IllegalStateException ignored) {
            // Lavagna o squadra sparita sotto i piedi (un altro plugin l'ha disfatta): si
            // riprova al prossimo sync, che sia il controllo periodico o un comando.
        }
    }

    /**
     * Le lavagne che i giocatori online stanno DAVVERO guardando adesso: quasi sempre solo la
     * principale, ma un plugin col pannello laterale (CMI) puo' darne una diversa a qualcuno —
     * senza scrivere anche li' quel giocatore continuerebbe a vedere la targhetta.
     */
    private static List<Scoreboard> boards() {
        List<Scoreboard> out = new ArrayList<>();
        Set<Scoreboard> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        if (seen.add(main)) out.add(main);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (seen.add(p.getScoreboard())) out.add(p.getScoreboard());
        }
        return out;
    }
}
