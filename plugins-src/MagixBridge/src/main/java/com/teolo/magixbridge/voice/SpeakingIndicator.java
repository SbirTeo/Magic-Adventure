package com.teolo.magixbridge.voice;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;


/**
 * Who is talking in this server's proximity room, shown in game: a few music notes above the head,
 * which the players around see (particles travel only to players nearby, the same ones who hear
 * the voice).
 *
 * The voice server does not tell anybody who is speaking; each browser knows it for its own
 * microphone and tells the site (api/voice.php, action speaking), which writes voice_speaking with
 * a short expiry. Here that table is read a few times a second. Only the proximity room counts:
 * talking in the faction room must not give away anything to the players standing nearby.
 * Nothing shows over an invisible, vanished or spectating player.
 */
public final class SpeakingIndicator {

    private final Logger log;
    /** The site database (MagixBridge: database::getConnection). */
    private final Callable<Connection> database;
    private final String room;
    private final AtomicBoolean reading = new AtomicBoolean();
    private volatile Set<UUID> speaking = Set.of();
    private long lastErrorLog;

    public SpeakingIndicator(Logger log, Callable<Connection> database, String room) {
        this.log = log;
        this.database = database;
        this.room = room;
    }

    /** Off the main thread: who is speaking now (the browser renews the row while it lasts). */
    public void read() {
        if (!reading.compareAndSet(false, true)) {
            return;
        }
        try (Connection c = database.call();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT mc_uuid FROM voice_speaking WHERE room = ? AND until_at > NOW(3)")) {
            ps.setString(1, room);
            Set<UUID> now = new HashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        now.add(UUID.fromString(rs.getString(1)));
                    } catch (IllegalArgumentException ignored) {
                        // not a UUID: the site never writes one, nothing to show
                    }
                }
            }
            speaking = now;
        } catch (Exception e) {
            long t = System.currentTimeMillis();
            if (t - lastErrorLog > 60_000) {
                lastErrorLog = t;
                log.warning("Voice: lettura di chi parla non riuscita (" + e.getMessage() + ").");
            }
        } finally {
            reading.set(false);
        }
    }

    /** Main thread: the notes above the head of whoever is speaking and online here. */
    public void show() {
        for (UUID id : speaking) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || hidden(p)) {
                continue;
            }
            Location at = p.getEyeLocation().add(0, 0.75, 0);
            // NOTE with count 0: the offset picks the colour, a different one each time.
            p.getWorld().spawnParticle(Particle.NOTE, at, 0, Math.random(), 0, 0, 1);
        }
    }

    private static boolean hidden(Player p) {
        return p.getGameMode() == GameMode.SPECTATOR
                || p.isInvisible()
                || p.hasPotionEffect(PotionEffectType.INVISIBILITY)
                || p.getMetadata("vanished").stream().anyMatch(v -> v.asBoolean());
    }
}
