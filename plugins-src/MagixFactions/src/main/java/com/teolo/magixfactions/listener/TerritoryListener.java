package com.teolo.magixfactions.listener;

import com.teolo.magixfactions.manage.ClaimManager;
import com.teolo.magixfactions.manage.FactionManager;
import com.teolo.magixfactions.model.Faction;
import com.teolo.magixfactions.model.RelationType;
import com.teolo.magixfactions.util.Colors;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Mostra un titolo al centro dello schermo quando un giocatore ENTRA (fade-in) o ESCE (farewell) da
 * un territorio, distinto per relazione: proprio (own), alleato (ally) o nemico (enemy).
 * Testi e tempi configurabili in {@code territory-titles}. In terreno neutrale nessun titolo.
 *
 * <p>La cache {@link #currentTerritory} si aggiorna sui movimenti (chunk-crossing), ma un chunk puo'
 * cambiare proprietario/relazione mentre il giocatore ci sta FERMO (es. lui stesso fa {@code /f claim}/
 * {@code /unclaim}/{@code unclaimall} sul chunk in cui si trova, il decadimento automatico glielo toglie,
 * o un'alleanza si rompe mentre e' in territorio alleato) — senza un evento di movimento la cache
 * resterebbe "vecchia" e, uscendo poco dopo, il confronto risulterebbe erroneamente "nessun cambio"
 * (bug osservato: claim da fermi in zona neutrale, poi uscita senza il fade-in "ZONA NEUTRALE").
 * {@link #tick()} riallinea periodicamente la cache di TUTTI gli online riusando la stessa {@link
 * #update} (nessuna logica duplicata), cosi' l'eventuale titolo mancato scatta comunque a breve.
 */
public final class TerritoryListener implements Listener {

    private final JavaPlugin plugin;
    private final FactionManager fm;
    private final ClaimManager claims;
    private final Map<UUID, Long> currentTerritory = new HashMap<>(); // fazione proprietaria del territorio attuale

    public TerritoryListener(JavaPlugin plugin, FactionManager fm, ClaimManager claims) {
        this.plugin = plugin; this.fm = fm; this.claims = claims;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Location f = e.getFrom(), t = e.getTo();
        if (t == null) return;
        if (f.getWorld() == t.getWorld()
                && (f.getBlockX() >> 4) == (t.getBlockX() >> 4)
                && (f.getBlockZ() >> 4) == (t.getBlockZ() >> 4)) return; // stesso chunk: ignora
        update(e.getPlayer(), t.getChunk());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo() != null) update(e.getPlayer(), e.getTo().getChunk());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        setSilent(e.getPlayer(), e.getPlayer().getLocation().getChunk()); // stato iniziale senza titolo
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { currentTerritory.remove(e.getPlayer().getUniqueId()); }

    /** Riallinea (senza aspettare un movimento) la cache di tutti gli online: fa scattare i titoli
     *  "mancati" quando il chunk in cui stanno FERMI cambia proprietario/relazione da sotto i piedi. */
    public void tick() {
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) update(p, p.getLocation().getChunk());
    }

    /** Fazione proprietaria del chunk (qualsiasi relazione), o null se neutrale. */
    private Long ownerAt(Chunk ch) {
        return claims.owner(ch.getWorld().getName(), ch.getX(), ch.getZ());
    }

    /** Categoria di relazione del giocatore verso la fazione proprietaria: own | ally | enemy. */
    private String category(Player p, long ownerId) {
        Faction own = fm.getFaction(p.getUniqueId());
        if (own == null) return "enemy";                 // senza fazione: territorio altrui = nemico
        if (own.getId() == ownerId) return "own";
        return fm.effectiveRelation(own.getId(), ownerId) == RelationType.ALLY ? "ally" : "enemy";
    }

    private void setSilent(Player p, Chunk ch) {
        Long o = ownerAt(ch);
        if (o == null) currentTerritory.remove(p.getUniqueId()); else currentTerritory.put(p.getUniqueId(), o);
    }

    private void update(Player p, Chunk ch) {
        if (!plugin.getConfig().getBoolean("territory-titles.enabled", true)) { setSilent(p, ch); return; }
        Long now = ownerAt(ch);
        Long prev = currentTerritory.get(p.getUniqueId());
        if (Objects.equals(now, prev)) return;

        // Mostra il titolo della zona in cui si ENTRA (neutrale inclusa). Cosi' uscendo dalla propria
        // terra verso il nulla si vede "zona neutrale", non un addio alla propria fazione.
        if (now == null) {
            sendTitle(p, "neutral-enter", "");                    // entrato in terreno neutrale
            currentTerritory.remove(p.getUniqueId());
        } else {
            Faction nf = fm.getById(now);
            if (nf != null) sendTitle(p, category(p, now) + "-enter", nf.getName());
            currentTerritory.put(p.getUniqueId(), now);
        }
    }

    private void sendTitle(Player p, String node, String faction) {
        String base = "territory-titles." + node + ".";
        // NB: leggere SENZA default esplicito, cosi' se la sezione manca nel config dell'utente
        // si usano i valori di default inclusi nel jar (altrimenti un default "" darebbe titoli vuoti).
        String rawTitle = plugin.getConfig().getString(base + "title");
        String rawSub   = plugin.getConfig().getString(base + "subtitle");
        String title = color(rawTitle == null ? "" : rawTitle).replace("{faction}", faction);
        String sub   = color(rawSub == null ? "" : rawSub).replace("{faction}", faction);
        int in   = plugin.getConfig().getInt("territory-titles.fade-in", 10);
        int stay = plugin.getConfig().getInt("territory-titles.stay", 40);
        int out  = plugin.getConfig().getInt("territory-titles.fade-out", 10);
        p.sendTitle(title, sub, in, stay, out);
    }

    private static String color(String s) { return Colors.translate(s == null ? "" : s); }
}
