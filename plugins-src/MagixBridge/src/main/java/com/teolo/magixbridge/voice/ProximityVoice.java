package com.teolo.magixbridge.voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Proximity voice on the site (magicadventure.it/voice): this server's room is near-&lt;server&gt;,
 * and in it everybody hears only the players close to them in game, louder the closer they are,
 * from the side they stand on.
 *
 * A few times a second the positions of the players online here are snapshotted on the main
 * thread; off the main thread, for every player who is also in the voice room, the plugin works
 * out who is within earshot and sends THAT player alone a small packet: for each neighbour a
 * volume (0-1) and a left/right pan (-1..1). No coordinates ever leave the server, and nobody gets
 * the packet of somebody else: the page cannot be turned into a radar.
 *
 * The packet also tells each browser whom to let hear its own microphone (the neighbours within
 * reach, with a margin so a voice does not cut in and out at the edge): the voice server enforces
 * that, so a modified page still cannot listen to far-away players.
 */
public final class ProximityVoice {

    /** One player as snapshotted on the main thread. */
    record Spot(String identity, String world, double x, double y, double z, float yaw) {}

    /** What one listener receives about one neighbour. */
    record Heard(String identity, double gain, double pan) {}

    /** Beyond the hearing distance a voice is silent, but still allowed for this many blocks more. */
    static final double PERMISSION_MARGIN = 8.0;

    private final JavaPlugin plugin;
    private final VoiceApi api;
    private final double hearDistance;
    private final double fullVolumeDistance;

    /** Participants of the voice room (identity = game UUID), refreshed every couple of seconds. */
    private volatile Map<String, Boolean> inRoom = Map.of();
    private final AtomicBoolean listing = new AtomicBoolean();
    private long lastListing;

    /** Last packet sent to each listener, and when: an unchanged packet is resent once a second. */
    private final Map<String, String> lastPayload = new HashMap<>();
    private final Map<String, Long> lastSent = new HashMap<>();
    private final AtomicBoolean sending = new AtomicBoolean();
    private long lastErrorLog;

    public ProximityVoice(JavaPlugin plugin, VoiceApi api, double hearDistance, double fullVolumeDistance) {
        this.plugin = plugin;
        this.api = api;
        this.hearDistance = Math.max(4.0, hearDistance);
        this.fullVolumeDistance = Math.max(0.0, Math.min(fullVolumeDistance, this.hearDistance - 1.0));
    }

    /** Main thread, every few ticks: snapshot positions, then do the rest off the main thread. */
    public void tick() {
        long now = System.currentTimeMillis();
        if (now - lastListing > 2000 && listing.compareAndSet(false, true)) {
            lastListing = now;
            api.listParticipants().whenComplete((list, err) -> {
                if (err != null) {
                    logError("elenco della stanza", err);
                } else {
                    Map<String, Boolean> m = new HashMap<>();
                    for (VoiceApi.Participant p : list) {
                        m.put(p.identity(), p.speaking());
                    }
                    inRoom = m;
                }
                listing.set(false);
            });
        }

        Map<String, Boolean> room = inRoom;
        if (room.isEmpty()) {
            return;
        }
        List<Spot> spots = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            String id = p.getUniqueId().toString();
            if (room.containsKey(id)) {
                Location l = p.getLocation();
                spots.add(new Spot(id, l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw()));
            }
        }
        if (spots.isEmpty() || !sending.compareAndSet(false, true)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                send(spots, room, now);
            } finally {
                sending.set(false);
            }
        });
    }

    private void send(List<Spot> spots, Map<String, Boolean> room, long now) {
        Set<String> here = new HashSet<>();
        for (Spot listener : spots) {
            here.add(listener.identity());
            String payload = payload(neighbours(listener, spots, room));
            boolean changed = !payload.equals(lastPayload.get(listener.identity()));
            if (!changed && now - lastSent.getOrDefault(listener.identity(), 0L) < 1000) {
                continue;
            }
            lastPayload.put(listener.identity(), payload);
            lastSent.put(listener.identity(), now);
            api.sendTo(listener.identity(), payload).whenComplete((r, err) -> {
                if (err != null) {
                    logError("invio al browser", err);
                }
            });
        }
        lastPayload.keySet().retainAll(here);
        lastSent.keySet().retainAll(here);
    }

    /** The neighbours of one listener within reach, with volume and pan. Pure: easy to check. */
    List<Heard> neighbours(Spot listener, List<Spot> spots, Map<String, Boolean> room) {
        List<Heard> out = new ArrayList<>();
        double yaw = Math.toRadians(listener.yaw());
        // Minecraft: yaw 0 looks towards +Z; the right hand then points to -X.
        double rightX = -Math.cos(yaw);
        double rightZ = -Math.sin(yaw);
        for (Spot s : spots) {
            if (s.identity().equals(listener.identity()) || !s.world().equals(listener.world())) {
                continue;
            }
            double dx = s.x() - listener.x();
            double dy = s.y() - listener.y();
            double dz = s.z() - listener.z();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > hearDistance + PERMISSION_MARGIN) {
                continue;
            }
            double gain = gain(distance);
            if (!Boolean.TRUE.equals(room.get(s.identity()))) {
                gain = 0;   // microphone off: still allowed, nothing to hear
            }
            double flat = Math.sqrt(dx * dx + dz * dz);
            double pan = flat < 1.0 ? 0 : 0.8 * ((dx * rightX + dz * rightZ) / flat);
            out.add(new Heard(s.identity(), gain, pan));
        }
        return out;
    }

    /** Full volume up close, then down to silence at the hearing distance, a bit faster at first. */
    double gain(double distance) {
        if (distance <= fullVolumeDistance) {
            return 1.0;
        }
        if (distance >= hearDistance) {
            return 0.0;
        }
        double t = (distance - fullVolumeDistance) / (hearDistance - fullVolumeDistance);
        double g = 1.0 - t;
        return g * g;
    }

    /** {"n":[["uuid",0.83,-0.40],...]}: two decimals are plenty for a volume and a pan. */
    static String payload(List<Heard> heard) {
        StringBuilder sb = new StringBuilder("{\"n\":[");
        for (int i = 0; i < heard.size(); i++) {
            Heard h = heard.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("[\"").append(h.identity()).append("\",")
                    .append(String.format(Locale.ROOT, "%.2f", h.gain())).append(',')
                    .append(String.format(Locale.ROOT, "%.2f", h.pan())).append(']');
        }
        return sb.append("]}").toString();
    }

    /** At most one line a minute: a voice server that is down must not flood the console. */
    private void logError(String what, Throwable err) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLog > 60_000) {
            lastErrorLog = now;
            plugin.getLogger().warning("Voice: " + what + " non riuscito (" + err.getClass().getSimpleName()
                    + ": " + err.getMessage() + "). Il server della voce è acceso? (predisponi-voce.yml controlla)");
        }
    }
}
