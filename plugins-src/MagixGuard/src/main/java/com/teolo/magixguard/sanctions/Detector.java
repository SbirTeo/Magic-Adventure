package com.teolo.magixguard.sanctions;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.UUID;

/**
 * La porta d'ingresso delle violazioni: <b>tutto</b> quello che il server nota — un messaggio
 * bloccato, un verdetto dell'anticheat, uno scavo che non torna, un anti-AFK aggirato — entra
 * da qui e da nessun'altra parte.
 *
 * <p>Cosa succede a ogni violazione, in ordine:</p>
 * <ol>
 *   <li>si registra il fatto con i punti della sua categoria;</li>
 *   <li>si ricalcola il totale del giocatore, <b>con il decadimento gia' applicato</b>;</li>
 *   <li>si guarda se ha appena superato una soglia — solo se l'ha <i>appena</i> superata:
 *       chi resta sopra i 50 punti non si becca un ban a ogni sciocchezza successiva;</li>
 *   <li>se si', il provvedimento passa dalla {@link Policy}, che decide se applicarlo o
 *       metterlo in coda per una persona.</li>
 * </ol>
 *
 * <p>Le classi che rilevano (chat, xray, afk, anticheat) non sanno niente di punti, soglie e
 * sanzioni: dicono solo <i>cosa hanno visto</i>. E' quello che permette di aggiungerne una
 * quinta domani senza toccare il resto.</p>
 */
public final class Detector {

    private final JavaPlugin plugin;
    private final SanctionsConfig cfg;
    private final SanctionsService service;
    private final ViolationsDao violations;
    private final PointsLog log;

    public Detector(JavaPlugin plugin, SanctionsConfig cfg, SanctionsService service,
                      ViolationsDao violations, PointsLog log) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.service = service;
        this.violations = violations;
        this.log = log;
    }

    /**
     * Registra una violazione. Si puo' chiamare da qualunque thread: il giro sul database viene
     * spostato via da solo.
     *
     * @param categoria il codice in sanzioni.yml (chat.spam, cheat.xray, afk.elusione...)
     * @param fonte     chi l'ha vista (chat, xray, afk, grim, staff)
     * @param dettaglio le prove, gia' scritte perche' le legga una persona
     */
    public void rileva(UUID uuid, String name, String category, String fonte, String dettaglio) {
        rileva(uuid, name, category, fonte, dettaglio, 1.0);
    }

    /**
     * Come sopra, ma con un moltiplicatore sui punti: serve a chi misura una <i>gravita'</i>
     * (l'anticheat manda quante volte ha sbagliato, l'anti-xray quanto e' fuori scala).
     * Resta comunque il tetto della categoria: un moltiplicatore non puo' trasformare uno
     * spam in un ban.
     */
    public void rileva(UUID uuid, String name, String category, String fonte, String dettaglio,
                       double moltiplicatore) {
        SanctionsConfig.Category cat = cfg.category(category);
        int points = (int) Math.round(cat.points() * Math.max(0.1, Math.min(3.0, moltiplicatore)));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                double prima = log.points(uuid);
                int violationId = violations.inserisci(
                        Violation.nuova(uuid, name, category, points, fonte, dettaglio));
                double dopo = prima + points;

                SanctionsConfig.Threshold threshold = thresholdJustCrossed(prima, dopo);
                if (threshold == null) {
                    notifyIfNeeded(cat, name, category, dettaglio, dopo);
                    return;
                }

                long now = System.currentTimeMillis();
                long fine = threshold.duration() == Duration.PERMANENTE || !threshold.type().hasDuration()
                        ? Duration.PERMANENTE : now + threshold.duration();

                String reason = cat.name() + " (" + Math.round(dopo) + " punti)";
                Sanction s = new Sanction(0, uuid, name, threshold.type(), category, reason,
                        cat.scope(), 0, now, fine, null, true, null);

                Policy.Outcome outcome = service.policy()
                        .checkAutomation(cat, threshold.type(), threshold.duration());

                int sanctionId = service.apply(s, outcome, fonte,
                        buildDetail(dettaglio, prima, dopo, threshold), threshold.duration());
                if (sanctionId > 0) {
                    violations.collega(violationId, sanctionId);
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Violazione non registrata (" + name + ", "
                        + category + "): " + e.getMessage());
            }
        });
    }

    /**
     * La soglia che il giocatore ha superato <b>adesso</b>, cioe' quella che prima non aveva
     * raggiunto e ora si'. Null se non ne ha superata nessuna con questa violazione.
     */
    private SanctionsConfig.Threshold thresholdJustCrossed(double prima, double dopo) {
        SanctionsConfig.Threshold trovata = null;
        for (SanctionsConfig.Threshold s : cfg.thresholds) {   // ordinate dalla piu' alta
            if (dopo >= s.points() && prima < s.points()) {
                trovata = s;
                break;   // la piu' alta fra quelle appena superate
            }
        }
        return trovata;
    }

    /**
     * Certe categorie non sanzionano mai da sole ma vanno viste subito da una persona:
     * i dati personali sono il caso per cui questa riga esiste.
     */
    private void notifyIfNeeded(SanctionsConfig.Category cat, String name, String category,
                               String dettaglio, double points) {
        if (cat.automatic()) {
            return;
        }
        service.notifyStaff("&#FFD166" + cat.name() + "&f " + name + " &7— "
                + (dettaglio == null ? "" : firstRow(dettaglio))
                + " &8(" + Math.round(points) + " punti)");
    }

    /** Il dettaglio che finisce nella coda, con il conto dei punti gia' fatto. */
    private String buildDetail(String dettaglio, double prima, double dopo,
                                    SanctionsConfig.Threshold threshold) {
        StringBuilder b = new StringBuilder();
        b.append("Punti prima: ").append(Math.round(prima))
         .append(" -> dopo: ").append(Math.round(dopo))
         .append(" (soglia superata: ").append(threshold.points()).append(")\n");
        b.append("Provvedimento previsto: ").append(threshold.type().label())
         .append(", ").append(Duration.write(threshold.duration())).append("\n\n");
        if (dettaglio != null) {
            b.append(dettaglio);
        }
        return b.toString();
    }

    private static String firstRow(String s) {
        int a_capo = s.indexOf('\n');
        String row = a_capo < 0 ? s : s.substring(0, a_capo);
        return row.length() > 90 ? row.substring(0, 90) + "..." : row;
    }

    /** Comodita' per i rilevatori: il nome da mostrare per un giocatore. */
    public static String nameOf(Player p) {
        return p.getName();
    }
}
