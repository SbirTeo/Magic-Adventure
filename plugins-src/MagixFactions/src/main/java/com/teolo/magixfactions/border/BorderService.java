package com.teolo.magixfactions.border;

import com.teolo.magixfactions.manage.ClaimManager;
import com.teolo.magixfactions.manage.FactionManager;
import com.teolo.magixfactions.manage.PowerManager;
import com.teolo.magixfactions.model.Faction;
import com.teolo.magixfactions.model.RelationType;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;

/**
 * Confini a particelle. Disegna una linea di particelle lungo i BORDI dei territori di fazione vicini al
 * giocatore, cosi' si vede a colpo d'occhio dove finisce una land e ne inizia un'altra (o il terreno
 * libero). E' una preferenza PERSONALE di ogni giocatore (colonna {@code players.borders_enabled}, comando
 * {@code /f borders}), indipendente dalla fazione: di serie e' spenta, ognuno la accende per se'.
 *
 * <p>Il COLORE del bordo dipende dalla RELAZIONE di chi guarda con la fazione proprietaria di quella land:
 * verde la propria, magenta le alleate, rosso le nemiche (nel modello non esistono stati neutri: senza
 * fazione propria si e' nemici di tutti, come nel resto del plugin — vedi {@code MapContentBuilder.relKey}).
 *
 * <p>Le particelle sono inviate col metodo {@link Player#spawnParticle} del SINGOLO giocatore: le vede
 * solo lui, non appaiono agli altri e non pesano sul resto del server. Nessuna Entity vera, nessun
 * pacchetto personalizzato — solo particelle client-side attorno a chi ha acceso l'effetto.
 *
 * <p><b>Come trova i bordi:</b> per ogni giocatore acceso guarda i chunk in un piccolo raggio
 * ({@code borders.radius}) attorno a lui; per ogni chunk CLAIMATO controlla i quattro vicini ortogonali:
 * se un vicino appartiene a una fazione diversa (o e' libero) quel lato e' un CONFINE e ci disegna una
 * linea, del colore della fazione proprietaria del chunk. Ogni confine e' disegnato una volta sola
 * (deduplica per lato condiviso). L'altezza segue il giocatore (parte dai suoi piedi), cosi' l'effetto
 * e' leggero e non richiede di scandire il terreno.
 */
public final class BorderService {

    private final JavaPlugin plugin;
    private final PowerManager power;
    private final ClaimManager claims;
    private final FactionManager factions;

    public BorderService(JavaPlugin plugin, PowerManager power, ClaimManager claims, FactionManager factions) {
        this.plugin = plugin; this.power = power; this.claims = claims; this.factions = factions;
    }

    /** La feature e' accesa lato server (interruttore generale {@code borders.enabled}, default true). */
    public boolean isEnabled() { return plugin.getConfig().getBoolean("borders.enabled", true); }

    /** Ogni quanti tick gira il disegno (default 10 = mezzo secondo). Minimo 1. */
    public int intervalTicks() { return Math.max(1, plugin.getConfig().getInt("borders.interval-ticks", 10)); }

    /**
     * Un giro di disegno: per ogni giocatore online che ha acceso i confini, disegna le particelle dei
     * bordi attorno a lui. Gira sul main thread (lo richiede {@link Player#spawnParticle} e la lettura
     * della cache dei territori/relazioni).
     */
    public void tick() {
        if (!isEnabled()) return;
        int radius = Math.max(0, plugin.getConfig().getInt("borders.radius", 2));
        double spacing = Math.max(0.25, plugin.getConfig().getDouble("borders.point-spacing", 2.0));
        int wallHeight = Math.max(0, plugin.getConfig().getInt("borders.wall-height", 1));
        float size = (float) Math.max(0.1, plugin.getConfig().getDouble("borders.size", 1.0));
        // Un DustOptions per relazione: verde propria, magenta alleati, rosso nemici.
        Particle.DustOptions own   = dust("borders.color-own",   "#33FF33", size);
        Particle.DustOptions ally  = dust("borders.color-ally",  "#FF33FF", size);
        Particle.DustOptions enemy = dust("borders.color-enemy", "#FF3333", size);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!power.isBordersEnabled(p.getUniqueId())) continue;
            drawFor(p, radius, spacing, wallHeight, own, ally, enemy);
        }
    }

    private Particle.DustOptions dust(String key, String def, float size) {
        return new Particle.DustOptions(parseColor(plugin.getConfig().getString(key, def)), size);
    }

    private void drawFor(Player p, int radius, double spacing, int wallHeight,
                         Particle.DustOptions own, Particle.DustOptions ally, Particle.DustOptions enemy) {
        World world = p.getWorld();
        String wname = world.getName();
        Location loc = p.getLocation();
        int pcx = loc.getBlockX() >> 4;
        int pcz = loc.getBlockZ() >> 4;
        int feetY = loc.getBlockY();
        Faction viewer = factions.getFaction(p.getUniqueId());   // fazione di chi guarda (null = senza fazione)
        // Lati gia' disegnati in questo giro (chiave canonica del bordo): un confine condiviso da due
        // chunk nel raggio non va disegnato due volte.
        Set<String> done = new HashSet<>();

        for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
            for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                Long owner = claims.owner(wname, cx, cz);
                if (owner == null) continue;   // solo dai chunk claimati
                long o = owner;
                Particle.DustOptions dust = relDust(viewer, o, own, ally, enemy);
                // Ovest (X = cx*16), lungo Z
                if (o != nullSafe(claims.owner(wname, cx - 1, cz)))
                    edgeVertical(p, cx * 16, cz, feetY, spacing, wallHeight, dust, done);
                // Est (X = cx*16+16), lungo Z
                if (o != nullSafe(claims.owner(wname, cx + 1, cz)))
                    edgeVertical(p, cx * 16 + 16, cz, feetY, spacing, wallHeight, dust, done);
                // Nord (Z = cz*16), lungo X
                if (o != nullSafe(claims.owner(wname, cx, cz - 1)))
                    edgeHorizontal(p, cz * 16, cx, feetY, spacing, wallHeight, dust, done);
                // Sud (Z = cz*16+16), lungo X
                if (o != nullSafe(claims.owner(wname, cx, cz + 1)))
                    edgeHorizontal(p, cz * 16 + 16, cx, feetY, spacing, wallHeight, dust, done);
            }
        }
    }

    /** Colore in base alla relazione di {@code viewer} con la fazione proprietaria {@code ownerId}: propria
     *  (verde), alleata (magenta) o nemica (rosso). Senza fazione propria si e' nemici di tutti, coerente
     *  col resto del plugin (nessuno stato neutro). */
    private Particle.DustOptions relDust(Faction viewer, long ownerId,
                                         Particle.DustOptions own, Particle.DustOptions ally, Particle.DustOptions enemy) {
        if (viewer == null) return enemy;
        if (viewer.getId() == ownerId) return own;
        if (factions.effectiveRelation(viewer.getId(), ownerId) == RelationType.ALLY) return ally;
        return enemy;
    }

    /** -1 (fazione inesistente) quando il chunk e' libero: cosi' il confronto col proprietario vero e'
     *  sempre "diverso" e il lato verso terreno libero conta come confine. */
    private static long nullSafe(Long v) { return v == null ? -1L : v; }

    /** Bordo verticale: linea a X fisso, che corre lungo Z per i 16 blocchi del chunk {@code cz}. */
    private void edgeVertical(Player p, int x, int cz, int feetY, double spacing, int wallHeight,
                              Particle.DustOptions dust, Set<String> done) {
        if (!done.add("V:" + x + ":" + cz)) return;
        double z0 = cz * 16;
        for (double z = z0; z <= z0 + 16; z += spacing)
            column(p, x, z, feetY, wallHeight, dust);
    }

    /** Bordo orizzontale: linea a Z fisso, che corre lungo X per i 16 blocchi del chunk {@code cx}. */
    private void edgeHorizontal(Player p, int z, int cx, int feetY, double spacing, int wallHeight,
                                Particle.DustOptions dust, Set<String> done) {
        if (!done.add("H:" + z + ":" + cx)) return;
        double x0 = cx * 16;
        for (double x = x0; x <= x0 + 16; x += spacing)
            column(p, x, z, feetY, wallHeight, dust);
    }

    /** Una "colonnina" di particelle a (x,z): dai piedi del giocatore fino a {@code wallHeight} blocchi
     *  sopra, un punto per livello. */
    private void column(Player p, double x, double z, int feetY, int wallHeight, Particle.DustOptions dust) {
        for (int dy = 0; dy <= wallHeight; dy++)
            p.spawnParticle(Particle.DUST, x, feetY + dy + 0.5, z, 1, 0, 0, 0, 0, dust);
    }

    /** Colore da stringa esadecimale {@code #RRGGBB} (o {@code RRGGBB}); verde di ripiego se non valida. */
    private static Color parseColor(String hex) {
        Color green = Color.fromRGB(0x33, 0xFF, 0x33);
        if (hex == null) return green;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return green;
        try {
            return Color.fromRGB(Integer.parseInt(s, 16));
        } catch (NumberFormatException e) {
            return green;
        }
    }
}
