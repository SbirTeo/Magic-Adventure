package com.teolo.magixscoreboard.model;

import com.teolo.magixscoreboard.hook.WorldGuardHook;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Una scoreboard configurata: le condizioni che un giocatore deve soddisfare per vederla, il peso
 * che decide chi vince quando piu' di una corrisponde, e il contenuto (titolo + righe).
 *
 * <h2>Come si sceglie la scoreboard di un giocatore</h2>
 * Fra tutte quelle che corrispondono (vedi {@link #matches}), vince quella col {@link #weight()}
 * piu' alto. A parita' di peso decide {@link #specificity}, in base all'ordine di priorita' scelto
 * nel config ({@code priority-order}): una scoreboard con una condizione "region" batte una con solo
 * "permission" se region viene prima nell'elenco, e cosi' via. A parita' anche di quello vince la
 * prima definita nel config (vedi board/BoardManager.select).
 */
public final class BoardDefinition {

    private final String id;
    private final boolean enabled;
    private final int weight;
    private final String permission;
    private final Set<String> worlds;
    private final Set<String> regions;
    private final BoardLine title;
    private final List<BoardLine> lines;

    public BoardDefinition(String id, boolean enabled, int weight, String permission,
                            Set<String> worlds, Set<String> regions,
                            BoardLine title, List<BoardLine> lines) {
        this.id = id;
        this.enabled = enabled;
        this.weight = weight;
        this.permission = permission == null ? "" : permission;
        this.worlds = Set.copyOf(worlds);
        this.regions = Set.copyOf(regions);
        this.title = title;
        this.lines = List.copyOf(lines);
    }

    public String id() { return id; }
    public boolean enabled() { return enabled; }
    public int weight() { return weight; }
    public BoardLine title() { return title; }
    public List<BoardLine> lines() { return lines; }
    public String permission() { return permission; }
    public Set<String> worlds() { return worlds; }
    public Set<String> regions() { return regions; }

    /** true se il giocatore soddisfa TUTTE le condizioni impostate (quelle vuote non contano). */
    public boolean matches(Player player, WorldGuardHook worldGuard) {
        if (!enabled) return false;
        if (!permission.isEmpty() && !player.hasPermission(permission)) return false;
        if (!worlds.isEmpty() && !worlds.contains(player.getWorld().getName().toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (!regions.isEmpty()) {
            Set<String> here = worldGuard.regionsAt(player.getLocation());
            if (here.stream().noneMatch(regions::contains)) return false;
        }
        return true;
    }

    /**
     * Punteggio di specificita' usato SOLO per spareggiare scoreboard a parita' di peso: somma, per
     * ogni categoria di condizione impostata (region/permission/world), i punti che le da'
     * {@code priorityOrder} (prima nell'elenco = piu' punti). Una scoreboard senza nessuna
     * condizione (quella di riserva) vale 0: perde sempre lo spareggio contro una piu' specifica.
     */
    public int specificity(List<String> priorityOrder) {
        int n = priorityOrder.size();
        int score = 0;
        if (!regions.isEmpty()) score += pointsFor("region", priorityOrder, n);
        if (!permission.isEmpty()) score += pointsFor("permission", priorityOrder, n);
        if (!worlds.isEmpty()) score += pointsFor("world", priorityOrder, n);
        return score;
    }

    private static int pointsFor(String category, List<String> priorityOrder, int n) {
        int index = priorityOrder.indexOf(category);
        return index < 0 ? 0 : n - index;
    }
}
