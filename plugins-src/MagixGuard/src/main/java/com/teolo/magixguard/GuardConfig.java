package com.teolo.magixguard;

import com.teolo.magixguard.analyze.EvidenceType;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.Map;

/**
 * Lettura tipizzata di config.yml: un solo punto in cui si sbaglia il nome di una chiave.
 */
public final class GuardConfig {

    // privacy
    public final String pepper;
    public final int retentionDays;
    public final int publicIpOctets;

    // raccolta
    public final int clientSnapshotDelaySeconds;
    public final int clientSecondPassSeconds;
    public final boolean reverseDns;
    public final int reverseDnsTimeoutMs;
    public final boolean cookieEnabled;
    public final String cookieKey;
    public final boolean pingSampling;
    public final int pingIntervalSeconds;

    // analisi
    public final double linkThreshold;
    public final double alertThreshold;
    public final boolean crowdingEnabled;
    public final int crowdingFullWeightUpTo;
    public final int crowdingWorthlessAbove;
    public final boolean decayEnabled;
    public final double decayHalfLifeDays;
    public final int handoffWindowSeconds;
    public final int similarNameMaxDistance;
    public final double onlineTogetherEach;
    public final double onlineTogetherMax;
    public final double differentFingerprint;
    private final Map<EvidenceType, Double> weights = new EnumMap<>(EvidenceType.class);

    // alert
    public final boolean alertInGame;
    public final int alertCooldownHours;
    public final boolean alertConsole;
    public final String discordWebhook;

    // dossier
    public final String dossierFolder;
    public final boolean dossierSign;
    public final int dossierMaxSessions;

    public GuardConfig(FileConfiguration c) {
        pepper = c.getString("privacy.pepper", "");
        retentionDays = c.getInt("privacy.session-retention-days", 180);
        publicIpOctets = c.getInt("privacy.public-dossier-ip-octets", 2);

        clientSnapshotDelaySeconds = c.getInt("collect.client-snapshot-delay-seconds", 6);
        clientSecondPassSeconds = c.getInt("collect.client-snapshot-second-pass-seconds", 45);
        reverseDns = c.getBoolean("collect.reverse-dns", true);
        reverseDnsTimeoutMs = c.getInt("collect.reverse-dns-timeout-ms", 3000);
        cookieEnabled = c.getBoolean("collect.cookie.enabled", true);
        cookieKey = c.getString("collect.cookie.key", "magixguard:id");
        pingSampling = c.getBoolean("collect.ping-sampling", true);
        pingIntervalSeconds = c.getInt("collect.ping-sample-interval-seconds", 60);

        linkThreshold = c.getDouble("analysis.link-threshold", 60);
        alertThreshold = c.getDouble("analysis.alert-threshold", 85);
        crowdingEnabled = c.getBoolean("analysis.ip-crowding.enabled", true);
        crowdingFullWeightUpTo = c.getInt("analysis.ip-crowding.full-weight-up-to", 2);
        crowdingWorthlessAbove = c.getInt("analysis.ip-crowding.worthless-above", 12);
        decayEnabled = c.getBoolean("analysis.decay.enabled", true);
        decayHalfLifeDays = c.getDouble("analysis.decay.half-life-days", 90);
        handoffWindowSeconds = c.getInt("analysis.handoff-window-seconds", 120);
        similarNameMaxDistance = c.getInt("analysis.similar-name-max-distance", 2);
        onlineTogetherEach = c.getDouble("analysis.exculpatory.online-together-each", -8);
        onlineTogetherMax = c.getDouble("analysis.exculpatory.online-together-max", -60);
        differentFingerprint = c.getDouble("analysis.exculpatory.different-fingerprint", -15);

        for (EvidenceType t : EvidenceType.values()) {
            if (t == EvidenceType.ONLINE_TOGETHER) { weights.put(t, onlineTogetherEach); continue; }
            if (t == EvidenceType.DIFFERENT_FINGERPRINT) { weights.put(t, differentFingerprint); continue; }
            weights.put(t, c.getDouble("analysis.weights." + t.configKey(), 0));
        }

        alertInGame = c.getBoolean("alerts.in-game", true);
        alertCooldownHours = c.getInt("alerts.cooldown-hours", 24);
        alertConsole = c.getBoolean("alerts.console", true);
        discordWebhook = c.getString("alerts.discord-webhook", "");

        dossierFolder = c.getString("dossier.folder", "dossier");
        dossierSign = c.getBoolean("dossier.sign", true);
        dossierMaxSessions = c.getInt("dossier.max-sessions-listed", 50);
    }

    public double weight(EvidenceType type) {
        return weights.getOrDefault(type, 0.0);
    }

    /** true se il pepper e' ancora quello di esempio: va segnalato in console, non e' un dettaglio. */
    public boolean pepperIsDefault() {
        return pepper == null || pepper.isBlank() || pepper.startsWith("CAMBIAMI");
    }
}
