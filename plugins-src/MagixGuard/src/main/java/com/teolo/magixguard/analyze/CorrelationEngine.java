package com.teolo.magixguard.analyze;

import com.teolo.magixguard.GuardConfig;
import com.teolo.magixguard.alert.AlertService;
import com.teolo.magixguard.db.GuardDao;
import com.teolo.magixguard.model.PlayerRef;
import com.teolo.magixguard.model.Rows;
import com.teolo.magixguard.model.SessionSnapshot;
import com.teolo.magixguard.util.Detail;
import com.teolo.magixguard.util.Hashing;

import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Il motore: da una sessione appena raccolta ricava gli indizi verso gli altri account e
 * aggiorna i punteggi di collegamento.
 *
 * Gira sempre sul thread del database, mai sul tick del server.
 */
public final class CorrelationEngine {

    /** Entro questa finestra due accessi dallo stesso IP sono "nello stesso periodo", non storia vecchia. */
    private static final long ACTIVE_WINDOW_MS = 24L * 60 * 60 * 1000;

    /** Quanti account al massimo confrontare per singolo segnale: evita esplosioni su IP molto affollati. */
    private static final int MAX_CANDIDATES_PER_SIGNAL = 40;

    /** Sotto questo numero di sessioni per parte, "mai visti insieme" non significa niente. */
    private static final int MIN_SESSIONS_FOR_NEVER_TOGETHER = 5;

    /** Primo accesso ravvicinato: quanto vicino deve essere per contare. */
    private static final long REGISTERED_CLOSE_MS = 10L * 60 * 1000;

    private final GuardDao dao;
    private final GuardConfig config;
    private final LinkScorer scorer;
    private final AlertService alerts;

    public CorrelationEngine(GuardDao dao, GuardConfig config, LinkScorer scorer, AlertService alerts) {
        this.dao = dao;
        this.config = config;
        this.scorer = scorer;
        this.alerts = alerts;
    }

    /** Analizza una sessione completa di dati client e aggiorna tutte le coppie coinvolte. */
    public void analyze(SessionSnapshot s) throws SQLException {
        if (s.playerId <= 0) return;
        long now = System.currentTimeMillis();

        Set<Long> byCookie = dao.playersMatching(GuardDao.MatchColumn.COOKIE_TOKEN, s.cookieToken, s.playerId, MAX_CANDIDATES_PER_SIGNAL);
        Set<Long> byIp = dao.playersMatching(GuardDao.MatchColumn.IP_HASH, s.ipHash, s.playerId, MAX_CANDIDATES_PER_SIGNAL);
        Set<Long> bySubnet = dao.playersMatching(GuardDao.MatchColumn.SUBNET_HASH, s.subnetHash, s.playerId, MAX_CANDIDATES_PER_SIGNAL);
        Set<Long> byRdns = dao.playersMatching(GuardDao.MatchColumn.RDNS_HASH, s.rdnsHash, s.playerId, MAX_CANDIDATES_PER_SIGNAL);
        Set<Long> byFingerprint = dao.playersMatching(GuardDao.MatchColumn.FINGERPRINT, s.fingerprint, s.playerId, MAX_CANDIDATES_PER_SIGNAL);
        Set<Long> byChannels = dao.playersMatching(GuardDao.MatchColumn.CHANNELS_HASH, s.channelsHash, s.playerId, MAX_CANDIDATES_PER_SIGNAL);

        Set<Long> candidates = new LinkedHashSet<>();
        candidates.addAll(byCookie);
        candidates.addAll(byIp);
        candidates.addAll(bySubnet);
        candidates.addAll(byRdns);
        candidates.addAll(byFingerprint);
        candidates.addAll(byChannels);

        int accountsOnIp = dao.countPlayersOnIpHash(s.ipHash);

        // Staffetta: chi e' uscito da questo stesso IP pochi secondi prima che entrasse lui.
        for (long[] pair : dao.handoffCandidates(s.ipHash, s.joinAt, config.handoffWindowSeconds, s.playerId)) {
            long otherId = pair[0];
            long quitAt = pair[1];
            candidates.add(otherId);
            long gapSeconds = Math.max(0, (s.joinAt - quitAt) / 1000);
            dao.upsertEvidence(s.playerId, otherId, EvidenceType.HANDOFF, now, new Detail()
                    .put("ip", maskedIp(s.ip))
                    .put("account_su_ip", accountsOnIp)
                    .put("intervallo_secondi", gapSeconds)
                    .put("uscito", com.teolo.magixguard.util.Fmt.dateTime(quitAt))
                    .put("entrato", com.teolo.magixguard.util.Fmt.dateTime(s.joinAt)));
        }

        Optional<PlayerRef> selfRef = dao.findPlayer(s.playerId);

        for (long otherId : candidates) {
            if (otherId == s.playerId) continue;
            Optional<PlayerRef> other = dao.findPlayer(otherId);
            if (other.isEmpty()) continue;

            if (byCookie.contains(otherId) && s.cookieToken != null) {
                dao.upsertEvidence(s.playerId, otherId, EvidenceType.SAME_COOKIE, now, new Detail()
                        .put("token", abbreviate(s.cookieToken))
                        .put("nota", "token scritto nel client dal plugin, identico sui due account"));
            }

            if (byIp.contains(otherId) && s.ipHash != null) {
                Long lastJoin = dao.lastJoinOnIpHash(otherId, s.ipHash);
                boolean active = lastJoin != null && Math.abs(s.joinAt - lastJoin) <= ACTIVE_WINDOW_MS;
                EvidenceType type = active ? EvidenceType.SAME_IP_ACTIVE : EvidenceType.SAME_IP_HISTORY;
                dao.upsertEvidence(s.playerId, otherId, type, now, new Detail()
                        .put("ip", maskedIp(s.ip))
                        .put("account_su_ip", accountsOnIp)
                        .put("ultimo_accesso_altro_account", lastJoin == null ? "-"
                                : com.teolo.magixguard.util.Fmt.dateTime(lastJoin)));
            } else if (bySubnet.contains(otherId) && s.subnet != null) {
                // La /24 conta solo se NON hanno gia' condiviso l'IP esatto: sarebbe la stessa prova due volte.
                dao.upsertEvidence(s.playerId, otherId, EvidenceType.SAME_SUBNET24, now, new Detail()
                        .put("sottorete", s.subnet)
                        .put("account_su_ip", accountsOnIp));
            }

            if (byRdns.contains(otherId) && s.rdns != null) {
                dao.upsertEvidence(s.playerId, otherId, EvidenceType.SAME_RDNS, now, new Detail()
                        .put("rete", s.rdns)
                        .put("nota", "stesso nome di rete inverso: tipicamente la stessa linea internet"));
            }

            if (byFingerprint.contains(otherId) && s.fingerprint != null) {
                dao.upsertEvidence(s.playerId, otherId, EvidenceType.SAME_FINGERPRINT, now, new Detail()
                        .put("impronta", abbreviate(s.fingerprint))
                        .put("client", s.brand == null ? "?" : s.brand)
                        .put("lingua", s.locale == null ? "?" : s.locale)
                        .put("distanza_visiva", s.viewDistance)
                        .put("parti_skin", s.skinParts)
                        .put("mano", s.mainHand));
            } else if (byChannels.contains(otherId) && s.channelsHash != null) {
                dao.upsertEvidence(s.playerId, otherId, EvidenceType.SAME_CHANNELS, now, new Detail()
                        .put("mod_dichiarate", abbreviateList(s.channels)));
            }

            // Nickname costruiti sullo stesso schema (Pippo / Pippo2 / Pipp0).
            if (selfRef.isPresent()) {
                int distance = Hashing.levenshtein(selfRef.get().name(), other.get().name());
                if (distance > 0 && distance <= config.similarNameMaxDistance) {
                    dao.setEvidenceOccurrences(s.playerId, otherId, EvidenceType.SIMILAR_NAME, 1, now, new Detail()
                            .put("nickname", selfRef.get().name() + " / " + other.get().name())
                            .put("differenza_caratteri", distance));
                }

                // Primo accesso al server ravvicinato, dallo stesso IP: account creati in serie.
                if (byIp.contains(otherId)
                        && Math.abs(selfRef.get().firstSeen() - other.get().firstSeen()) <= REGISTERED_CLOSE_MS) {
                    long minutes = Math.abs(selfRef.get().firstSeen() - other.get().firstSeen()) / 60000;
                    dao.setEvidenceOccurrences(s.playerId, otherId, EvidenceType.REGISTERED_CLOSE, 1, now, new Detail()
                            .put("primo_accesso_a", com.teolo.magixguard.util.Fmt.dateTime(selfRef.get().firstSeen()))
                            .put("primo_accesso_b", com.teolo.magixguard.util.Fmt.dateTime(other.get().firstSeen()))
                            .put("distanza_minuti", minutes));
                }
            }

            updateExculpatory(s, otherId, other.get(), selfRef.orElse(null), now);
            recomputeLink(s.playerId, otherId, now);
        }
    }

    /**
     * Aggiorna le prove a favore del giocatore. Vengono ricalcolate ogni volta (non incrementate):
     * se due account si mostrano online insieme, la difesa deve aggiornarsi subito.
     */
    private void updateExculpatory(SessionSnapshot s, long otherId, PlayerRef other, PlayerRef self, long now)
            throws SQLException {
        int overlaps = dao.countOverlappingSessions(s.playerId, otherId);
        if (overlaps > 0) {
            dao.setEvidenceOccurrences(s.playerId, otherId, EvidenceType.ONLINE_TOGETHER, overlaps, now, new Detail()
                    .put("sessioni_sovrapposte", overlaps)
                    .put("nota", "i due account sono stati collegati nello stesso momento"));
            // "Mai online insieme" e' smentito: la ritiriamo azzerandone le occorrenze.
            dao.setEvidenceOccurrences(s.playerId, otherId, EvidenceType.NEVER_TOGETHER, 0, now, new Detail()
                    .put("nota", "ritirata: successivamente sono stati visti online insieme"));
        } else if (self != null
                && self.sessionCount() >= MIN_SESSIONS_FOR_NEVER_TOGETHER
                && other.sessionCount() >= MIN_SESSIONS_FOR_NEVER_TOGETHER) {
            dao.setEvidenceOccurrences(s.playerId, otherId, EvidenceType.NEVER_TOGETHER, 1, now, new Detail()
                    .put("sessioni_a", self.sessionCount())
                    .put("sessioni_b", other.sessionCount())
                    .put("nota", "nessuna sessione sovrapposta nonostante entrambi giochino con continuita'"));
        }

        String otherFingerprint = dao.latestFingerprint(otherId);
        if (s.fingerprint != null && otherFingerprint != null && !s.fingerprint.equals(otherFingerprint)) {
            dao.setEvidenceOccurrences(s.playerId, otherId, EvidenceType.DIFFERENT_FINGERPRINT, 1, now, new Detail()
                    .put("nota", "le impostazioni e le mod dei due client non coincidono"));
        } else if (s.fingerprint != null && s.fingerprint.equals(otherFingerprint)) {
            dao.setEvidenceOccurrences(s.playerId, otherId, EvidenceType.DIFFERENT_FINGERPRINT, 0, now, null);
        }
    }

    /**
     * Ricalcola il punteggio della coppia dalle prove presenti e, se supera la soglia di
     * segnalazione, avvisa lo staff. Le coppie in whitelist restano registrate ma non allertano.
     */
    public double recomputeLink(long aId, long bId, long now) throws SQLException {
        var evidence = dao.evidenceFor(aId, bId);
        LinkScorer.Result result = scorer.score(evidence, now);
        dao.upsertLink(aId, bId, result.score(), now);

        if (result.score() >= config.alertThreshold && !dao.isWhitelisted(aId, bId)) {
            Optional<Rows.Link> link = dao.getLink(aId, bId);
            Long alertedAt = link.map(Rows.Link::alertedAt).orElse(null);
            long cooldown = config.alertCooldownHours * 3600_000L;
            if (alertedAt == null || now - alertedAt >= cooldown) {
                alerts.raise(aId, bId, result, now);
                dao.markAlerted(aId, bId, now);
            }
        }
        return result.score();
    }

    private String maskedIp(String ip) {
        return ip == null ? "?" : Hashing.maskIp(ip, config.publicIpOctets);
    }

    private static String abbreviate(String s) {
        return s == null ? "?" : (s.length() <= 12 ? s : s.substring(0, 12) + "...");
    }

    private static String abbreviateList(String s) {
        if (s == null) return "?";
        return s.length() <= 120 ? s : s.substring(0, 120) + "... (elenco completo nelle sessioni)";
    }
}
