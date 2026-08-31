package com.teolo.magixguard.xray;

import com.teolo.magixguard.sanzioni.Rilevatore;
import com.teolo.magixguard.sanzioni.Testo;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-xray statistico.
 *
 * <p><b>Perche' non basta l'anticheat.</b> L'xray non e' un problema di pacchetti: il client non
 * fa niente di strano, guarda soltanto dei blocchi che il server gli ha gia' mandato. Per questo
 * la prima difesa e' l'offuscamento nativo di Paper (<code>engine-mode 2</code>), che i minerali
 * non li manda proprio; e per questo il riconoscimento non puo' che essere <b>statistico</b>:
 * si guarda cosa scava una persona, non come si muove.</p>
 *
 * <p><b>Cosa si misura.</b> Due cose, tenute separate perche' dicono cose diverse:</p>
 * <ul>
 *   <li><b>resa</b>: quanti minerali preziosi ogni mille blocchi scavati. Un minatore bravo sta
 *       largamente sotto la soglia di allarme; chi vede attraverso la pietra la sfonda.</li>
 *   <li><b>minerali chiusi</b>: minerali rotti mentre erano <i>completamente circondati</i> da
 *       blocchi pieni, cioe' invisibili fino all'istante prima. Capita anche per caso dentro una
 *       vena, quindi da solo non prova niente — ma insieme alla resa racconta una storia.</li>
 * </ul>
 *
 * <p><b>Come nasce un sospetto.</b> Mai prima di aver scavato abbastanza da avere una statistica
 * (<code>blocchi-minimi</code>): sui primi cento blocchi qualunque numero e' rumore. E di serie il
 * modulo parte in <b>sola osservazione</b>: avvisa lo staff e basta, finche' le soglie non sono
 * state tarate sui dati veri di questo server. Cambiarle prima di aver guardato i numeri vuol
 * dire accusare qualcuno con una soglia inventata.</p>
 */
public final class AnalisiScavo implements Listener {

    /** Quello che sappiamo di una sessione di scavo. */
    private static final class Sessione {
        int blocchi;                       // blocchi "di roccia" rotti
        int preziosi;                      // minerali preziosi trovati
        int chiusi;                        // preziosi rotti mentre erano circondati
        long ultimoAvviso;
        final Map<Material, Integer> perTipo = new LinkedHashMap<>();
    }

    private final JavaPlugin plugin;
    private final Rilevatore rilevatore;

    private final boolean attivo;
    private final boolean soloOsservazione;
    private final int blocchiMinimi;
    private final double allarmePerMille;
    private final double estremoPerMille;
    private final long intervalloAvvisi;
    private final Set<Material> preziosi = new HashSet<>();

    private final Map<UUID, Sessione> sessioni = new ConcurrentHashMap<>();

    public AnalisiScavo(JavaPlugin plugin, Rilevatore rilevatore, ConfigurationSection cfg) {
        this.plugin = plugin;
        this.rilevatore = rilevatore;
        this.attivo = cfg == null || cfg.getBoolean("attivo", true);
        this.soloOsservazione = cfg == null
                || !"attivo".equalsIgnoreCase(cfg.getString("modo", "osservazione"));
        this.blocchiMinimi = cfg == null ? 800 : Math.max(100, cfg.getInt("blocchi-minimi", 800));
        this.allarmePerMille = cfg == null ? 10 : cfg.getDouble("allarme-per-mille", 10);
        this.estremoPerMille = cfg == null ? 20 : cfg.getDouble("estremo-per-mille", 20);
        this.intervalloAvvisi = (cfg == null ? 30 : Math.max(5, cfg.getInt("intervallo-avvisi-minuti", 30)))
                * 60_000L;

        if (cfg != null && !cfg.getStringList("minerali-preziosi").isEmpty()) {
            for (String nome : cfg.getStringList("minerali-preziosi")) {
                Material m = Material.matchMaterial(nome.trim().toUpperCase());
                if (m != null) {
                    preziosi.add(m);
                }
            }
        } else {
            preziosi.addAll(Set.of(
                    Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
                    Material.ANCIENT_DEBRIS,
                    Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void suRottura(BlockBreakEvent e) {
        if (!attivo) {
            return;
        }
        Player p = e.getPlayer();
        if (p.getGameMode() != org.bukkit.GameMode.SURVIVAL || p.hasPermission("magixguard.exempt")) {
            return;
        }
        Block b = e.getBlock();
        Material m = b.getType();

        Sessione s = sessioni.computeIfAbsent(p.getUniqueId(), k -> new Sessione());

        if (preziosi.contains(m)) {
            s.preziosi++;
            s.perTipo.merge(m, 1, Integer::sum);
            if (eraChiuso(b)) {
                s.chiusi++;
            }
        } else if (eRoccia(m)) {
            s.blocchi++;
        } else {
            return;   // legno, terra, foglie: non c'entrano niente con lo scavo
        }

        valuta(p, s);
    }

    @EventHandler
    public void suUscita(PlayerQuitEvent e) {
        // La sessione muore con l'uscita: e' una misura di quello che ha fatto ADESSO, non di
        // una carriera. Il registro punti pensa al lungo periodo, questo no.
        sessioni.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ giudizio

    private void valuta(Player p, Sessione s) {
        int totale = s.blocchi + s.preziosi;
        if (totale < blocchiMinimi) {
            return;
        }
        double perMille = (s.preziosi * 1000.0) / totale;
        if (perMille < allarmePerMille) {
            return;
        }
        long adesso = System.currentTimeMillis();
        if (adesso - s.ultimoAvviso < intervalloAvvisi) {
            return;
        }
        s.ultimoAvviso = adesso;

        String prove = componiProve(p, s, totale, perMille);

        if (soloOsservazione || perMille < estremoPerMille) {
            // Sotto la soglia estrema, o in sola osservazione: si avvisa e basta. Nessun punto,
            // nessun provvedimento. E' il comportamento giusto finche' le soglie non sono tarate.
            Bukkit.getScheduler().runTask(plugin, () -> avvisa(p, perMille, s, totale));
            plugin.getLogger().info("[xray] " + p.getName() + ": " + arrotonda(perMille)
                    + " preziosi/1000 su " + totale + " blocchi (sola osservazione).");
            return;
        }

        rilevatore.rileva(p.getUniqueId(), p.getName(), "cheat.xray", "xray", prove,
                Math.min(3.0, perMille / estremoPerMille));
    }

    /** L'avviso allo staff: dice il numero, non la conclusione. */
    private void avvisa(Player p, double perMille, Sessione s, int totale) {
        String riga = "&#FFD166Scavo anomalo&f " + p.getName() + " &7— " + arrotonda(perMille)
                + " preziosi ogni 1000 blocchi (" + s.preziosi + " su " + totale + ")"
                + (s.chiusi > 0 ? ", di cui " + s.chiusi + " chiusi" : "");
        Bukkit.getConsoleSender().sendMessage(Testo.msg(riga));
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("magixguard.alerts")) {
                staff.sendMessage(Testo.msg(riga));
            }
        }
    }

    /**
     * Le prove, scritte perche' le legga una persona che non sa cos'e' una deviazione standard.
     * Dichiarano anche cosa NON dimostrano: e' quello che le rende utilizzabili in un ricorso.
     */
    private String componiProve(Player p, Sessione s, int totale, double perMille) {
        StringBuilder b = new StringBuilder();
        b.append("Sessione di scavo di ").append(p.getName()).append('\n');
        b.append("Blocchi di roccia scavati: ").append(totale).append('\n');
        b.append("Minerali preziosi trovati: ").append(s.preziosi)
         .append(" (").append(arrotonda(perMille)).append(" ogni 1000 blocchi)\n");
        b.append("Soglia di allarme: ").append(arrotonda(allarmePerMille))
         .append(" - soglia estrema: ").append(arrotonda(estremoPerMille)).append('\n');
        b.append("Minerali rotti mentre erano completamente circondati: ").append(s.chiusi).append('\n');
        if (!s.perTipo.isEmpty()) {
            b.append("Dettaglio: ");
            for (Map.Entry<Material, Integer> voce : s.perTipo.entrySet()) {
                b.append(voce.getKey().name().toLowerCase()).append(" x").append(voce.getValue()).append("  ");
            }
            b.append('\n');
        }
        b.append("Posizione attuale: ").append(p.getWorld().getName()).append(' ')
         .append(p.getLocation().getBlockX()).append(", ")
         .append(p.getLocation().getBlockY()).append(", ")
         .append(p.getLocation().getBlockZ()).append('\n');
        b.append('\n');
        b.append("Limiti di questa misura: e' una statistica di sessione, non una prova diretta. ")
         .append("Un minatore fortunato o molto esperto puo' superare la soglia di allarme; per ")
         .append("questo il provvedimento automatico scatta solo oltre la soglia estrema. Il conteggio ")
         .append("dei minerali 'chiusi' non distingue una vena scavata da dentro da una trovata a colpo ")
         .append("sicuro: va letto insieme alla resa, non da solo.");
        return b.toString();
    }

    // ------------------------------------------------------------------ utilita'

    /** Il blocco era invisibile? Lo e' se tutti e sei i lati danno su qualcosa di pieno. */
    private static boolean eraChiuso(Block b) {
        for (BlockFace faccia : new BlockFace[] { BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                                                  BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST }) {
            Material vicino = b.getRelative(faccia).getType();
            if (vicino.isAir() || !vicino.isOccluding()) {
                return false;
            }
        }
        return true;
    }

    /** Roccia, cioe' quello che si scava per cercare: il resto non entra nella statistica. */
    private static boolean eRoccia(Material m) {
        String n = m.name();
        return n.equals("STONE") || n.equals("DEEPSLATE") || n.equals("NETHERRACK")
                || n.equals("TUFF") || n.equals("GRANITE") || n.equals("DIORITE")
                || n.equals("ANDESITE") || n.equals("BASALT") || n.equals("BLACKSTONE")
                || n.endsWith("_ORE");   // anche i minerali comuni sono blocchi scavati
    }

    private static String arrotonda(double d) {
        return String.valueOf(Math.round(d * 10) / 10.0);
    }

    /** Le statistiche vive, per un comando di diagnostica. */
    public Map<String, String> riassunto() {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<UUID, Sessione> voce : sessioni.entrySet()) {
            Player p = Bukkit.getPlayer(voce.getKey());
            if (p == null) {
                continue;
            }
            Sessione s = voce.getValue();
            int totale = s.blocchi + s.preziosi;
            double perMille = totale == 0 ? 0 : (s.preziosi * 1000.0) / totale;
            out.put(p.getName(), totale + " blocchi, " + s.preziosi + " preziosi ("
                    + arrotonda(perMille) + "/1000)");
        }
        return out;
    }
}
