package com.teolo.magixguard.sanctions;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Il registro dei punti: quanto pesa, oggi, quello che un giocatore ha fatto finora.
 *
 * <p>I punti li portano le <b>violazioni</b> (un messaggio bloccato, un verdetto dell'anticheat,
 * uno scavo che non torna), non i provvedimenti: un ban dato a mano dallo staff non aggiunge
 * punti, perche' sarebbe contare due volte lo stesso fatto.</p>
 *
 * <p>I punti <b>decadono</b>: ogni violazione vale la meta' dopo il numero di giorni indicato in
 * {@code punti/dimezzamento-giorni}. E' la stessa curva del punteggio multi-account, e la ragione
 * e' la stessa: una cosa fatta a marzo non deve pesare come una fatta stamattina, altrimenti chi
 * gioca da anni parte sempre in colpa e chi e' arrivato ieri e' sempre pulito.</p>
 *
 * <p>Le violazioni <b>annullate non contano</b>: se un ricorso e' stato accolto, quei punti non
 * sono mai esistiti.</p>
 */
public final class PointsLog {

    private final ViolationsDao violations;
    private final SanctionsConfig cfg;

    public PointsLog(ViolationsDao violations, SanctionsConfig cfg) {
        this.violations = violations;
        this.cfg = cfg;
    }

    /** I punti che quel giocatore ha ADESSO, decadimento gia' applicato. */
    public double points(UUID uuid) throws SQLException {
        return points(violations.perPoints(uuid), System.currentTimeMillis());
    }

    /** Il conto vero e proprio, separato per poterlo leggere (e provare) senza database. */
    public double points(List<Violation> history, long now) {
        double totale = 0;
        for (Violation v : history) {
            totale += v.points() * fattoreDecadimento(v.quando(), now);
        }
        return totale;
    }

    /** Quanto vale oggi una cosa successa allora: 1 appena fatta, 0.5 dopo un'emivita, e cosi' via. */
    public double fattoreDecadimento(long quando, long now) {
        double giorni = (now - quando) / 86_400_000.0;
        if (giorni <= 0) {
            return 1.0;
        }
        return Math.pow(0.5, giorni / cfg.halfLifeDays);
    }

    /**
     * Il provvedimento che spetta a chi si trova con questi punti: la soglia piu' alta superata.
     * Null se non ne ha superata nessuna — e allora non succede niente, il punteggio resta li'.
     */
    public SanctionsConfig.Threshold threshold(double points) {
        for (SanctionsConfig.Threshold s : cfg.thresholds) {   // gia' ordinate dalla piu' alta
            if (points >= s.points()) {
                return s;
            }
        }
        return null;
    }

    /**
     * Quanto manca alla prossima soglia: serve a dirlo allo staff, e a un giocatore che chiede
     * "quanto sono messo male". Ritorna -1 se e' gia' oltre l'ultima.
     */
    public double mancanteAllaProssima(double points) {
        double prossima = -1;
        for (SanctionsConfig.Threshold s : cfg.thresholds) {
            if (s.points() > points && (prossima < 0 || s.points() < prossima)) {
                prossima = s.points();
            }
        }
        return prossima < 0 ? -1 : prossima - points;
    }
}
