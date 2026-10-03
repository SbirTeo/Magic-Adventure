package com.teolo.magixbridge.voice;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;



/**
 * Sanctions that hold in the site's voice rooms at once, not only from the next time someone joins.
 *
 * The site checks bans and mutes when it signs the token to enter a room; somebody already inside
 * when the sanction comes would keep talking until they left. Every few seconds this pass reads
 * who has an active ban or mute right now (MagixGuard's punishments, plus the voice-only mutes the
 * staff gives from the admin panel, voice_mutes) and walks every room of the voice server:
 *
 *  - an active ban: out of the room;
 *  - an active mute: microphone taken away (the voice server takes it off the air at once);
 *  - no mute any more but no microphone either: given back, so a mute that expires or is revoked
 *    lets them talk again without leaving the room.
 *
 * Runs on ONE server of the network (network.site-jobs): the rooms are the same for everybody.
 */
public final class VoiceModeration {

    private final Logger log;
    /** The site database (MagixBridge: database::getConnection). */
    private final Callable<Connection> database;
    private final VoiceApi api;
    private final AtomicBoolean running = new AtomicBoolean();
    private long lastErrorLog;

    public VoiceModeration(Logger log, Callable<Connection> database, VoiceApi api) {
        this.log = log;
        this.database = database;
        this.api = api;
    }

    /** Off the main thread, every few seconds. Never two passes at once. */
    public void pass() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Set<String> banned = new HashSet<>();
            Set<String> muted = new HashSet<>();
            readSanctions(banned, muted);
            for (String room : api.listRooms().get()) {
                List<VoiceApi.Participant> people = api.listParticipants(room).get();
                for (VoiceApi.Participant p : people) {
                    String id = p.identity();
                    if (banned.contains(id)) {
                        api.remove(room, id).get();
                        log.info("Voice: " + id + " tolto dalla stanza " + room + " (ban attivo).");
                    } else if (muted.contains(id) && p.canPublish()) {
                        api.setCanPublish(room, id, false).get();
                        log.info("Voice: microfono tolto a " + id + " nella stanza " + room + " (mute attivo).");
                    } else if (!muted.contains(id) && !p.canPublish()) {
                        api.setCanPublish(room, id, true).get();
                        log.info("Voice: microfono ridato a " + id + " nella stanza " + room + " (nessun mute).");
                    }
                }
            }
        } catch (Exception e) {
            long now = System.currentTimeMillis();
            if (now - lastErrorLog > 60_000) {
                lastErrorLog = now;
                log.warning("Voice: controllo delle sanzioni nelle stanze non riuscito ("
                        + e.getClass().getSimpleName() + ": " + e.getMessage() + ").");
            }
        } finally {
            running.set(false);
        }
    }

    /**
     * Who has an active ban or mute now. Any scope counts: voice is a piece of the game reached
     * through the site, so a game ban holds here too (same rule as the site, includes/voice.php).
     */
    private void readSanctions(Set<String> banned, Set<String> muted) throws Exception {
        try (Connection c = database.call()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT mc_uuid, type FROM punishments WHERE status = 'attiva' AND type IN ('ban','mute') "
                            + "AND (ends_at IS NULL OR ends_at > NOW())");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ("ban".equals(rs.getString(2)) ? banned : muted).add(rs.getString(1));
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT mc_uuid FROM voice_mutes WHERE lifted_at IS NULL AND ends_at > NOW()");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    muted.add(rs.getString(1));
                }
            }
        }
    }
}
