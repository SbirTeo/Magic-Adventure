package com.teolo.magixproxy.motd;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.Favicon;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Velocity side of the MOTD: the twin of MagixEssentials' MotdListener.
 *
 * With the proxy in front, the ping is answered by Velocity and the server behind never sees it.
 * The MOTD is the same one: the same motd.yml of MagixEssentials (read from the faction folder,
 * database.shared_with style, so it is edited in one place), composed by the same MotdText and
 * chosen by the same MotdRotation. The file is re-read when it changes on disk: an edit is live at
 * the next ping, with no reload.
 *
 * What changes on the proxy: the player count is the one of the whole network, and the icon, when
 * motd.yml has none, is the default one set in MagixProxy's config (the faction's server-icon.png).
 */
public final class MotdListener {

    /** A tooltip line is not a real player: the id must not look like anyone. */
    private static final UUID NO_ONE = new UUID(0L, 0L);

    /** The protocol number no client can have: see MagixEssentials, version.always-show. */
    private static final int PROTOCOL_MISMATCH = -1;

    private final ProxyServer proxy;
    private final Logger log;
    private final Path file;
    private final Path defaultIcon;

    private volatile long loadedAt = -1;
    private volatile Map<String, Object> cfg = Map.of();
    private volatile MotdRotation textRotation = new MotdRotation(MotdRotation.RANDOM, 0, true);
    private volatile MotdRotation iconRotation = new MotdRotation(MotdRotation.RANDOM, 0, true);
    private volatile List<Favicon> icons = List.of();
    private volatile Favicon fallbackIcon;

    public MotdListener(ProxyServer proxy, Logger log, Path file, Path defaultIcon) {
        this.proxy = proxy;
        this.log = log;
        this.file = file;
        this.defaultIcon = defaultIcon;
        this.fallbackIcon = loadIcon(defaultIcon, "icona di serie");
        reloadIfChanged();
    }

    /** Last among the listeners: whoever writes last is what the player sees. */
    @Subscribe(order = PostOrder.LATE)
    public void onPing(ProxyPingEvent event) {
        reloadIfChanged();
        Map<String, Object> c = cfg;
        ServerPing ping = event.getPing();
        ServerPing.Builder b = ping.asBuilder();

        // The count is decided BEFORE the text: {max} must say what the player reads next to it.
        int online = proxy.getPlayerCount();
        b.onlinePlayers(online);
        int extra = number(c, "player-count.extra", 0);
        int max = number(c, "player-count.max", -1);
        if (extra > 0) b.maximumPlayers(online + extra);
        else if (max > 0) b.maximumPlayers(max);
        int shown = b.getMaximumPlayers();
        String version = ping.getVersion().getName();

        List<String> messages = strings(c, "messages");
        int which = textRotation.indice(messages.size());
        if (which >= 0) {
            b.description(MotdText.component(messages.get(which), online, shown, version));
        }

        String versionText = string(c, "version.text", "");
        if (!versionText.isEmpty()) {
            int protocol = bool(c, "version.always-show", false) ? PROTOCOL_MISMATCH : ping.getVersion().getProtocol();
            b.version(new ServerPing.Version(protocol, MotdText.semplice(versionText)));
        }

        List<Favicon> list = icons;
        int whichIcon = iconRotation.indice(list.size());
        if (whichIcon >= 0) b.favicon(list.get(whichIcon));
        else if (fallbackIcon != null) b.favicon(fallbackIcon);

        if (bool(c, "player-count.hide", false)) {
            b.nullPlayers();
        } else {
            List<String> hover = MotdText.tendina(strings(c, "hover"), online, shown, version);
            if (!hover.isEmpty()) {
                b.clearSamplePlayers();
                List<ServerPing.SamplePlayer> sample = new ArrayList<>();
                for (String line : hover) sample.add(new ServerPing.SamplePlayer(line, NO_ONE));
                b.samplePlayers(sample);
            }
        }
        event.setPing(b.build());
    }

    /** motd.yml re-read only when its date changes: cheap enough to check at every ping. */
    private void reloadIfChanged() {
        long modified;
        try {
            modified = Files.isReadable(file) ? Files.getLastModifiedTime(file).toMillis() : -2;
        } catch (Exception e) {
            modified = -2;
        }
        if (modified == loadedAt) return;
        synchronized (this) {
            if (modified == loadedAt) return;
            if (modified == -2) {
                if (loadedAt != -2) log.warn("MagixProxy: MOTD {} non leggibile: resta quella di Velocity.", file);
                cfg = Map.of();
                icons = List.of();
                loadedAt = -2;
                return;
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Map<String, Object> m = new Yaml().load(r);
                cfg = m == null ? Map.of() : m;
            } catch (Exception e) {
                // A broken file must not take the MOTD away: the previous one stays.
                log.warn("MagixProxy: MOTD {} illeggibile ({}): tengo quella di prima.", file, e.toString());
                loadedAt = modified;
                return;
            }
            String mode = string(cfg, "selection", MotdRotation.RANDOM);
            int every = number(cfg, "change-every-seconds", 0);
            boolean avoid = bool(cfg, "avoid-repeat", true);
            textRotation = new MotdRotation(mode, every, avoid);
            iconRotation = new MotdRotation(mode, every, avoid);
            List<Favicon> loaded = new ArrayList<>();
            if (bool(cfg, "icons.enabled", false)) {
                for (String name : strings(cfg, "icons.files")) {
                    Favicon f = loadIcon(file.getParent().resolve(name), name);
                    if (f != null) loaded.add(f);
                }
            }
            icons = loaded;
            loadedAt = modified;
            log.info("MagixProxy: MOTD letta da {} ({} voci, {} icone).", file,
                    strings(cfg, "messages").size(), loaded.size());
        }
    }

    private Favicon loadIcon(Path path, String name) {
        if (path == null || !Files.isRegularFile(path)) {
            if (path != null) log.warn("MagixProxy: {} ({}) non trovata: la salto.", name, path);
            return null;
        }
        try {
            return Favicon.create(path);
        } catch (Exception e) {
            log.warn("MagixProxy: {} non caricata ({}): dev'essere un PNG di 64x64. La salto.", name, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ yaml helpers

    @SuppressWarnings("unchecked")
    private static Object lookup(Map<String, Object> root, String path) {
        Object node = root;
        for (String part : path.split("\\.")) {
            if (!(node instanceof Map)) return null;
            node = ((Map<String, Object>) node).get(part);
        }
        return node;
    }

    private static String string(Map<String, Object> m, String path, String def) {
        Object v = lookup(m, path);
        return v == null ? def : v.toString();
    }

    private static int number(Map<String, Object> m, String path, int def) {
        Object v = lookup(m, path);
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? def : Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean bool(Map<String, Object> m, String path, boolean def) {
        Object v = lookup(m, path);
        return v == null ? def : Boolean.parseBoolean(v.toString().trim());
    }

    private static List<String> strings(Map<String, Object> m, String path) {
        Object v = lookup(m, path);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) if (o != null) out.add(o.toString());
        }
        return out;
    }
}
