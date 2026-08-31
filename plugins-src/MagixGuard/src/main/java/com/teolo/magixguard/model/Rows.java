package com.teolo.magixguard.model;

import com.teolo.magixguard.analyze.EvidenceType;

/**
 * Righe lette dal database, raggruppate qui per non moltiplicare i file:
 * sono strutture dati pure, senza logica.
 */
public final class Rows {

    private Rows() {}

    /** Un indizio fra due account. */
    public record Evidence(long id, long aId, long bId, EvidenceType type, int occurrences,
                           long firstSeen, long lastSeen, String detail) {}

    /** Punteggio complessivo di collegamento fra due account. */
    public record Link(long aId, long bId, double score, long updatedAt, Long alertedAt, boolean manual) {}

    /** Una segnalazione allo staff. */
    public record Alert(long id, long aId, long bId, double score, long createdAt, String status, String notes) {}

    /** Una sessione, come rileggiamo dal database per i dossier. */
    public record Session(long id, long playerId, long joinAt, Long quitAt, String ip, String rdns,
                          String hostname, String brand, String channels, String locale,
                          Integer viewDistance, Integer skinParts, String mainHand,
                          Integer pingMedian, String packStatus, String cookieToken, String fingerprint) {}

    /** Riga di whitelist: coppia dichiarata legittima dallo staff. */
    public record Whitelist(long aId, long bId, String staff, String reason, long createdAt) {}

    /** Riga del registro firmato. */
    public record Audit(long id, long ts, String actor, String action, String subject,
                        String detail, String prevHash, String hash) {}
}
