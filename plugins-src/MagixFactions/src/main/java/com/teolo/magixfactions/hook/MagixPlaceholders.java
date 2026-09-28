package com.teolo.magixfactions.hook;

import com.teolo.magixfactions.manage.ClaimManager;
import com.teolo.magixfactions.manage.FactionManager;
import com.teolo.magixfactions.manage.PowerManager;
import com.teolo.magixfactions.manage.ScoreManager;
import com.teolo.magixfactions.model.Faction;
import com.teolo.magixfactions.model.Member;
import com.teolo.magixfactions.util.Colors;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.Relational;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * Espansione PlaceholderAPI di MagixFactions: rende disponibili i placeholder
 * %magixfactions_...% in QUALSIASI plugin/config che usi PlaceholderAPI (es. CMI).
 *
 *   %magixfactions_faction%             -> nome della fazione del giocatore
 *   %magixfactions_factionstot%         -> numero totale di fazioni
 *   %magixfactions_rank%                -> tag del grado del giocatore
 *   %magixfactions_rank_name%           -> nome per intero del grado del giocatore
 *   %magixfactions_leader%              -> nome del leader della fazione
 *   %magixfactions_members%             -> numero di membri della fazione
 *   %magixfactions_members_online%      -> membri della fazione connessi adesso
 *   %magixfactions_status%              -> stato della propria fazione (testo di /f info)
 *   %magixfactions_allies%              -> numero di fazioni alleate
 *   %magixfactions_enemies%             -> numero di fazioni nemiche
 *   %magixfactions_power%               -> Potenza attuale della fazione
 *   %magixfactions_maxpower%            -> Potenza massima della fazione
 *   %magixfactions_claims%              -> territori posseduti dalla fazione
 *   %magixfactions_score%               -> punteggio composito della fazione (classifica)
 *   %magixfactions_position%            -> posizione della fazione nella classifica (1 = prima)
 *   %magixfactions_top_<n>_name%        -> nome della n-esima fazione in classifica (es. top_1_name)
 *   %magixfactions_top_<n>_score%       -> punteggio della n-esima fazione in classifica
 *   %magixfactions_maxclaims_fazione%   -> tetto territori attuale (20% del maxpower)
 *   %magixfactions_power_player%        -> Potenza del singolo giocatore
 *   %magixfactions_maxpower_player%     -> Potenza massima del singolo giocatore
 *   %magixfactions_relation_<fazione>%  -> relazione (testo) del lettore con quella fazione
 *
 * Placeholder RELAZIONALE (dipende da due giocatori, prefisso "rel_"):
 *   %rel_magixfactions_relation_color%  -> colore che il lettore vede verso un altro
 *                                          giocatore in base alla relazione fra le fazioni.
 *                                          (configurabile in config.yml -> relations.colors)
 */
public final class MagixPlaceholders extends PlaceholderExpansion implements Relational {

    /** Elenco per la guida staff (StaffGuide.placeholders): coppie placeholder, cosa mostra.
     *  check_config.py [9] blocca il commit se qui manca un placeholder risolto sotto. */
    public static final String[] DOCS = {
            "%magixfactions_faction%", "Nome della fazione del giocatore (vuoto se non ne ha una).",
            "%magixfactions_rank%", "Tag colorato del grado del giocatore nella sua fazione (es. [U], "
                    + "[L]), preso da ranks.*.tag e leader.tag del config.",
            "%magixfactions_rank_name%", "Nome per intero del grado del giocatore nella sua fazione (es. "
                    + "Ufficiale, Leader), preso da ranks.*.name e leader.name del config.",
            "%magixfactions_status%", "Stato della fazione del giocatore, lo stesso testo di /f info sulla "
                    + "propria fazione (forte, debole o senza territori: info.status-*-self di messages.yml).",
            "%magixfactions_leader%", "Nome del leader della fazione del giocatore.",
            "%magixfactions_members%", "Quanti membri ha la fazione del giocatore.",
            "%magixfactions_status_raw%", "si/no: la fazione del giocatore e' al sicuro (non conquistabile)? "
                    + "Valore fisso e non tradotto, adatto alle condizioni (es. == si). Senza territori: si.",
            "%magixfactions_leaderboard_points%", "Punteggio della fazione in classifica (uguale a score).",
            "%magixfactions_leaderboard_position%", "Posizione della fazione in classifica, 1 = prima "
                    + "(uguale a position).",
            "%magixfactions_members_online%", "Quanti membri della fazione del giocatore sono connessi adesso.",
            "%magixfactions_allies%", "Quante fazioni alleate ha.",
            "%magixfactions_enemies%", "Quante fazioni nemiche ha.",
            "%magixfactions_power%", "Potenza attuale della fazione.",
            "%magixfactions_maxpower%", "Potenza massima della fazione.",
            "%magixfactions_claims%", "Territori posseduti dalla fazione.",
            "%magixfactions_maxclaims_fazione%", "Tetto di territori che la fazione puo' avere adesso.",
            "%magixfactions_score%", "Punteggio della fazione in classifica.",
            "%magixfactions_position%", "Posizione della fazione in classifica (1 = prima).",
            "%magixfactions_power_player%", "Potenza del singolo giocatore.",
            "%magixfactions_maxpower_player%", "Potenza massima del singolo giocatore.",
            "%magixfactions_factionstot%", "Quante fazioni esistono sul server.",
            "%magixfactions_top_<n>_name%", "Nome della n-esima fazione in classifica (es. top_1_name).",
            "%magixfactions_top_<n>_score%", "Punteggio della n-esima fazione in classifica.",
            "%magixfactions_relation_<fazione>%", "Relazione di chi legge con quella fazione, a parole "
                    + "(relations.names del config).",
            "%rel_magixfactions_relation_color%", "Placeholder relazionale (fra due giocatori): il colore "
                    + "con cui chi legge vede l'altro, secondo la relazione (relations.colors del config).",
    };

    private final JavaPlugin plugin;
    private final FactionManager fm;
    private final PowerManager power;
    private final ClaimManager claims;
    private final ScoreManager score;
    private final com.teolo.magixfactions.lang.Messages messages;

    public MagixPlaceholders(JavaPlugin plugin, FactionManager fm, PowerManager power, ClaimManager claims,
                             ScoreManager score, com.teolo.magixfactions.lang.Messages messages) {
        this.plugin = plugin;
        this.fm = fm;
        this.power = power;
        this.claims = claims;
        this.score = score;
        this.messages = messages;
    }

    @Override
    public String getIdentifier() { return "magixfactions"; }

    @Override
    public String getAuthor() { return "teolo"; }

    @Override
    public String getVersion() { return plugin.getDescription().getVersion(); }

    @Override
    public boolean persist() { return true; } // resta registrato dopo /papi reload

    @Override
    public boolean canRegister() { return true; }

    // ----------------------- placeholder normali ------------------------
    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params.equalsIgnoreCase("factionstot")) {
            return String.valueOf(fm.all().size());
        }
        String lp = params.toLowerCase(Locale.ROOT);
        // %magixfactions_relation_<nomefazione>% : relazione del lettore con quella fazione
        if (lp.startsWith("relation_")) {
            String facName = params.substring("relation_".length());
            return relationName(player, facName);
        }
        // %magixfactions_top_<n>_name% / %magixfactions_top_<n>_score% : n-esima fazione in classifica.
        // Non dipende dal giocatore, quindi sta prima del controllo player==null (utile in una scoreboard
        // globale o sul sito).
        if (lp.startsWith("top_")) {
            String[] parts = lp.substring("top_".length()).split("_", 2);
            if (parts.length == 2) {
                int n;
                try { n = Integer.parseInt(parts[0]); } catch (NumberFormatException e) { return ""; }
                java.util.List<ScoreManager.Entry> rank = score.ranking();
                if (n < 1 || n > rank.size()) return "";
                ScoreManager.Entry e = rank.get(n - 1);
                if (parts[1].equals("name")) return e.faction.getName();
                if (parts[1].equals("score")) return score.formatScore(e.score);
            }
            return "";
        }
        if (player == null) {
            return "";
        }
        Faction f = fm.getFaction(player.getUniqueId());
        switch (lp) {
            case "faction":
                return f != null ? f.getName() : "";
            case "members":
                return f != null ? String.valueOf(f.size()) : "";
            case "members_online": {
                if (f == null) return "";
                int online = 0;
                for (java.util.UUID u : f.getMembers().keySet()) if (Bukkit.getPlayer(u) != null) online++;
                return String.valueOf(online);
            }
            case "power":
                return f != null ? String.valueOf(power.factionPower(f)) : "";
            case "maxpower":
                return f != null ? String.valueOf(power.factionMaxPower(f)) : "";
            case "claims":
                return f != null ? String.valueOf(claims.count(f.getId())) : "";
            case "score":
            case "leaderboard_points":
                return f != null ? score.formatScore(score.score(f)) : "";
            case "position":
            case "leaderboard_position":
                return f != null ? String.valueOf(score.position(f)) : "";
            case "maxclaims_fazione":
                return f != null ? String.valueOf(claims.maxClaims(power.factionMaxPower(f))) : "";
            case "power_player":
                return String.valueOf(power.getPower(player.getUniqueId()));
            case "maxpower_player":
                return String.valueOf(power.getMaxPower(player.getUniqueId()));
            case "allies":
                return f != null ? String.valueOf(fm.alliesOf(f).size()) : "";
            case "enemies":
                return f != null ? String.valueOf(fm.enemiesOf(f).size()) : "";
            case "leader": {
                if (f == null || f.getLeader() == null) return "";
                String name = Bukkit.getOfflinePlayer(f.getLeader()).getName();
                return name != null ? name : "";
            }
            case "rank": {
                if (f == null) return "";
                Member m = f.getMember(player.getUniqueId());
                if (m == null) return "";
                return color(fm.ranks().resolve(m.getRankId()).getTag());
            }
            case "status_raw": {
                // Valore tecnico fisso (non tradotto), pensato per le condizioni: es. in MagixScoreboard
                // "%magixfactions_status_raw% == si". Senza territori non c'e' nulla da conquistare: sicura.
                if (f == null) return "";
                int owned = claims.count(f.getId());
                return owned == 0 || power.factionPower(f) >= owned ? "si" : "no";
            }
            case "status":
                return f != null ? statusText(player, f) : "";
            case "rank_name": {
                if (f == null) return "";
                Member m = f.getMember(player.getUniqueId());
                if (m == null) return "";
                return color(fm.ranks().resolve(m.getRankId()).getName());
            }
            default:
                return null; // placeholder sconosciuto
        }
    }

    // --------------------- placeholder relazionale ----------------------
    @Override
    public String onPlaceholderRequest(Player one, Player two, String identifier) {
        if (identifier == null) return "";
        // colore che 'one' (il lettore) vede verso 'two' in base alla relazione
        if (identifier.equalsIgnoreCase("relation_color")) {
            String key = (one == null || two == null) ? "none" : relationKey(one, two);
            return color(plugin.getConfig().getString("relations.colors." + key, defaultColor(key)));
        }
        return null;
    }

    // ------------------------------ helper ------------------------------
    /** Chiave di relazione fra le fazioni di due giocatori: member|ally|enemy|none. Il lettore senza
     *  fazione propria e' nemico di tutti (nessuno stato neutro); "none" resta solo per il bersaglio
     *  senza fazione (non c'e' relazione da mostrare per chi non e' in nessuna fazione). */
    private String relationKey(Player viewer, Player target) {
        Faction fv = fm.getFaction(viewer.getUniqueId());
        Faction ft = fm.getFaction(target.getUniqueId());
        if (ft == null) return "none";
        if (fv == null) return "enemy";
        if (fv.getId() == ft.getId()) return "member";
        return fm.effectiveRelation(fv.getId(), ft.getId()).name().toLowerCase(Locale.ROOT);
    }

    /** Testo della relazione del lettore verso la fazione indicata per nome. Senza fazione propria si
     *  e' nemici di tutti (nessuno stato neutro). */
    private String relationName(OfflinePlayer reader, String facName) {
        Faction target = fm.getByName(facName);
        if (target == null) return "";
        Faction own = reader == null ? null : fm.getFaction(reader.getUniqueId());
        String key;
        if (own == null) key = "enemy";
        else if (own.getId() == target.getId()) key = "member";
        else key = fm.effectiveRelation(own.getId(), target.getId()).name().toLowerCase(Locale.ROOT);
        return color(plugin.getConfig().getString("relations.names." + key, defaultName(key)));
    }

    /** La riga di /f info sullo stato della PROPRIA fazione, nella lingua del giocatore se e' online. */
    private String statusText(OfflinePlayer player, Faction f) {
        int owned = claims.count(f.getId());
        String key = owned == 0 ? "info.status-none-self"
                : power.factionPower(f) >= owned ? "info.status-strong-self" : "info.status-weak-self";
        Player online = player.getPlayer();
        return online != null ? messages.get(online, key) : messages.get(key);
    }

    private static String defaultColor(String key) {
        return switch (key) {
            case "member" -> "&a";
            case "ally" -> "&d";
            case "enemy" -> "&c";
            default -> "&f"; // none (senza fazione)
        };
    }

    private static String defaultName(String key) {
        return switch (key) {
            case "member" -> "&aStessa fazione";
            case "ally" -> "&dAlleata";
            case "enemy" -> "&cNemica";
            default -> "&7Senza fazione"; // none
        };
    }

    private static String color(String s) {
        return Colors.translate(s);
    }
}
