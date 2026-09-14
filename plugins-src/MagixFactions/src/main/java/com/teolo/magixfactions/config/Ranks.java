package com.teolo.magixfactions.config;

import com.teolo.magixfactions.model.Rank;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Carica e gestisce i gradi dal config.
 * I gradi "normali" sono ordinati dal piu' basso (indice 0) al piu' alto.
 * Il leader e' un grado speciale, sopra tutti.
 */
public final class Ranks {

    private final List<Rank> ladder = new ArrayList<>(); // dal piu' basso al piu' alto
    private Rank leader;

    public void load(FileConfiguration cfg) {
        ladder.clear();
        List<?> list = cfg.getList("ranks");
        if (list != null) {
            // I permessi si EREDITANO dal basso verso l'alto: ogni grado include automaticamente
            // quelli di tutti i gradi sotto di lui (la scala e' ordinata dal piu' basso al piu' alto).
            // Cosi' nel config basta scrivere i permessi NUOVI di ogni grado, senza ripetere quelli
            // dei gradi inferiori.
            java.util.Set<String> inherited = new java.util.HashSet<>();
            for (Object o : list) {
                if (o instanceof java.util.Map<?, ?> m) {
                    String id = String.valueOf(m.get("id"));
                    String name = m.get("name") != null ? String.valueOf(m.get("name")) : id;
                    String tag = m.get("tag") != null ? String.valueOf(m.get("tag")) : "";
                    Object p = m.get("permissions");
                    if (p instanceof List<?> pl) for (Object x : pl) inherited.add(String.valueOf(x));
                    ladder.add(new Rank(id, name, tag, new java.util.HashSet<>(inherited), false));
                }
            }
        }
        if (ladder.isEmpty()) {
            // default minimo
            ladder.add(new Rank("recruit", "Recluta", "&7[R]", java.util.Set.of(), false));
            ladder.add(new Rank("member", "Membro", "&a[M]", java.util.Set.of("invite"), false));
            ladder.add(new Rank("officer", "Ufficiale", "&b[U]", java.util.Set.of("invite", "kick", "promote", "demote"), false));
        }
        ConfigurationSection ls = cfg.getConfigurationSection("leader");
        String ln = ls != null ? ls.getString("name", "Leader") : "Leader";
        String lt = ls != null ? ls.getString("tag", "&6[L]") : "&6[L]";
        leader = new Rank(Rank.LEADER_ID, ln, lt, java.util.Set.of("*"), true);
    }

    public Rank leader() { return leader; }
    public Rank lowest() { return ladder.get(0); }
    public Rank highest() { return ladder.get(ladder.size() - 1); } // un gradino sotto il leader
    public int size() { return ladder.size(); }

    public int indexOf(String id) {
        for (int i = 0; i < ladder.size(); i++) if (ladder.get(i).getId().equals(id)) return i;
        return -1;
    }

    public Rank byIndex(int i) {
        if (i < 0 || i >= ladder.size()) return null;
        return ladder.get(i);
    }

    /** Grado effettivo dato l'id memorizzato (gestisce il leader). */
    public Rank resolve(String rankId) {
        if (Rank.LEADER_ID.equals(rankId)) return leader;
        int i = indexOf(rankId);
        return i >= 0 ? ladder.get(i) : lowest();
    }

    /** Indice "logico": i normali 0..n-1, il leader n (sopra tutti). */
    public int rankOrder(String rankId) {
        if (Rank.LEADER_ID.equals(rankId)) return ladder.size();
        int i = indexOf(rankId);
        return i >= 0 ? i : 0;
    }
}
