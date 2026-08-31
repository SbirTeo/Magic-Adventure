package com.teolo.magixguard.analyze;

import com.teolo.magixguard.GuardConfig;
import com.teolo.magixguard.model.Rows;
import com.teolo.magixguard.util.Detail;
import com.teolo.magixguard.util.Fmt;

import java.util.ArrayList;
import java.util.List;

/**
 * Trasforma un elenco di indizi nel punteggio di collegamento fra due account.
 *
 * Tre correzioni, tutte spiegate riga per riga nel risultato (il dossier le riporta cosi'
 * come sono: uno staff che sanziona deve poter dire perche', e un giocatore che fa ricorso
 * deve poter contestare il singolo passaggio):
 *
 *   1. affollamento IP - un indirizzo usato da molti account vale poco (CGNAT, rete mobile,
 *      connessioni condivise), uno usato da due account vale molto;
 *   2. decadimento - una prova vecchia pesa meno, perche' gli IP domestici vengono riassegnati;
 *      NON si applica alle prove a discolpa, che restano valide per sempre;
 *   3. le occorrenze non moltiplicano il peso: descrivono il fenomeno, non lo aggravano
 *      (altrimenti chi gioca molto risulterebbe piu' colpevole di chi gioca poco).
 */
public final class LinkScorer {

    private final GuardConfig config;

    public LinkScorer(GuardConfig config) {
        this.config = config;
    }

    /** Una voce del calcolo, gia' pronta da stampare. */
    public record Line(EvidenceType type, double baseWeight, double appliedWeight,
                       int occurrences, long lastSeen, String detail, String note) {}

    public record Result(double score, List<Line> lines) {

        public boolean hasCertainty() {
            return lines.stream().anyMatch(l -> l.type() == EvidenceType.SAME_COOKIE);
        }
    }

    public Result score(List<Rows.Evidence> evidence, long now) {
        List<Line> lines = new ArrayList<>();
        double total = 0;

        for (Rows.Evidence e : evidence) {
            double base = config.weight(e.type());
            if (base == 0) continue;
            // occorrenze azzerate = prova ritirata (es. "mai online insieme" smentito da una
            // sovrapposizione successiva): resta la riga per lo storico, ma non pesa piu' nulla.
            if (e.occurrences() <= 0) continue;

            double applied = base;
            List<String> notes = new ArrayList<>();

            // --- prove a discolpa: quantita' che conta, ma con un tetto ---
            if (e.type() == EvidenceType.ONLINE_TOGETHER) {
                applied = Math.max(config.onlineTogetherMax, base * e.occurrences());
                notes.add(e.occurrences() + " sessioni sovrapposte");
            }

            // --- affollamento dell'IP ---
            if (config.crowdingEnabled && usesIp(e.type())) {
                int accounts = Detail.parse(e.detail()).getInt("account_su_ip", 0);
                if (accounts > 0) {
                    double factor = crowdingFactor(accounts);
                    if (factor < 1.0) {
                        applied *= factor;
                        notes.add(accounts + " account distinti su questo IP: peso ridotto al "
                                + Math.round(factor * 100) + "%");
                    }
                }
            }

            // --- decadimento temporale (solo sulle prove a carico) ---
            if (config.decayEnabled && !e.type().exculpatory() && config.decayHalfLifeDays > 0) {
                double days = Fmt.daysSince(e.lastSeen(), now);
                double factor = Math.pow(0.5, days / config.decayHalfLifeDays);
                if (factor < 0.99) {
                    applied *= factor;
                    notes.add("prova vecchia di " + Math.round(days) + " giorni: peso al "
                            + Math.round(factor * 100) + "%");
                }
            }

            total += applied;
            lines.add(new Line(e.type(), base, applied, e.occurrences(), e.lastSeen(),
                    e.detail(), String.join("; ", notes)));
        }

        // Il punteggio non scende sotto zero: un collegamento "molto smentito" e' semplicemente assente.
        return new Result(Math.max(0, total), lines);
    }

    private static boolean usesIp(EvidenceType type) {
        return type == EvidenceType.SAME_IP_ACTIVE
                || type == EvidenceType.SAME_IP_HISTORY
                || type == EvidenceType.SAME_SUBNET24;
    }

    /**
     * 1.0 fino a 'full-weight-up-to' account sull'IP, 0.0 da 'worthless-above' in su,
     * discesa lineare nel mezzo.
     */
    private double crowdingFactor(int accounts) {
        int full = Math.max(1, config.crowdingFullWeightUpTo);
        int none = Math.max(full + 1, config.crowdingWorthlessAbove);
        if (accounts <= full) return 1.0;
        if (accounts >= none) return 0.0;
        return 1.0 - (double) (accounts - full) / (none - full);
    }
}
