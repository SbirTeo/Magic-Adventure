package com.teolo.magixguard.collect;

import com.destroystokyo.paper.ClientOption;
import com.destroystokyo.paper.SkinParts;
import com.destroystokyo.paper.event.player.PlayerClientOptionsChangeEvent;
import com.teolo.magixguard.GuardConfig;
import com.teolo.magixguard.analyze.CorrelationEngine;
import com.teolo.magixguard.db.DbExecutor;
import com.teolo.magixguard.db.GuardDao;
import com.teolo.magixguard.model.SessionSnapshot;
import com.teolo.magixguard.util.Hashing;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raccolta dei segnali.
 *
 * Il giocatore non vede nulla: nessun messaggio, nessun ritardo al login, nessun effetto.
 * L'unica cosa che il client "subisce" e' un cookie da 32 caratteri, che nemmeno il gioco
 * mostra da nessuna parte.
 *
 * Tempi: i dati di rete ci sono gia' al login, le impostazioni del client arrivano qualche
 * secondo dopo e la lista dei canali plugin (le mod) puo' impiegare mezzo minuto sui client
 * molto moddati. Per questo si fanno due passaggi.
 */
public final class SignalCollector implements Listener {

    private final Plugin plugin;
    private final GuardConfig config;
    private final GuardDao dao;
    private final DbExecutor executor;
    private final Hashing hashing;
    private final CorrelationEngine engine;
    private final CookieService cookies;

    /** Hostname dell'handshake, disponibile solo nell'evento di pre-login. */
    private final Map<UUID, String> pendingHostname = new ConcurrentHashMap<>();
    private final Map<UUID, SessionSnapshot> active = new ConcurrentHashMap<>();
    private final Map<UUID, List<BukkitTask>> tasks = new ConcurrentHashMap<>();

    public SignalCollector(Plugin plugin, GuardConfig config, GuardDao dao, DbExecutor executor,
                           Hashing hashing, CorrelationEngine engine, CookieService cookies) {
        this.plugin = plugin;
        this.config = config;
        this.dao = dao;
        this.executor = executor;
        this.hashing = hashing;
        this.engine = engine;
        this.cookies = cookies;
    }

    // ============================== eventi ==============================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        // L'indirizzo con cui il giocatore si e' connesso (dominio, sottodominio o IP nudo):
        // dopo il login non e' piu' recuperabile.
        pendingHostname.put(event.getUniqueId(), event.getHostname());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        beginSession(player, pendingHostname.remove(player.getUniqueId()));
    }

    /**
     * Apre la sessione di profilazione. Chiamato dal join e, all'avvio del plugin, per chi e'
     * gia' collegato (dopo un reload non arriva nessun PlayerJoinEvent).
     */
    public void beginSession(Player player, String hostname) {
        if (player.hasPermission("magixguard.exempt")) return;
        if (active.containsKey(player.getUniqueId())) return;

        SessionSnapshot s = new SessionSnapshot();
        s.uuid = player.getUniqueId();
        s.name = player.getName();
        s.joinAt = System.currentTimeMillis();
        s.hostname = hostname;
        if (player.getAddress() != null && player.getAddress().getAddress() != null) {
            s.ip = player.getAddress().getAddress().getHostAddress();
            s.ipHash = hashing.hmac(s.ip);
            s.subnet = Hashing.subnet(s.ip);
            s.subnetHash = hashing.hmac(s.subnet);
        }
        active.put(player.getUniqueId(), s);

        executor.submit("apertura sessione " + s.name, () -> {
            s.playerId = dao.upsertPlayer(s.uuid, s.name, s.joinAt);
            s.sessionId = dao.insertSession(s);
        });

        if (config.reverseDns && s.ip != null) {
            Rdns.lookup(s.ip, config.reverseDnsTimeoutMs).thenAccept(host -> {
                if (host != null) {
                    s.rdns = host;
                    s.rdnsHash = hashing.hmac(host);
                }
            });
        }

        List<BukkitTask> playerTasks = new ArrayList<>();
        playerTasks.add(Bukkit.getScheduler().runTaskLater(plugin,
                () -> capture(player, s, true), 20L * config.clientSnapshotDelaySeconds));
        // Secondo passaggio: i client con molte mod registrano i canali con calma.
        playerTasks.add(Bukkit.getScheduler().runTaskLater(plugin,
                () -> capture(player, s, true), 20L * config.clientSecondPassSeconds));
        if (config.pingSampling) {
            long period = 20L * Math.max(10, config.pingIntervalSeconds);
            playerTasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (player.isOnline()) s.pingSamples.add(player.getPing());
            }, period, period));
        }
        tasks.put(player.getUniqueId(), playerTasks);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingHostname.remove(uuid);
        List<BukkitTask> playerTasks = tasks.remove(uuid);
        if (playerTasks != null) playerTasks.forEach(BukkitTask::cancel);

        SessionSnapshot s = active.remove(uuid);
        if (s == null) return;
        long quitAt = System.currentTimeMillis();
        Integer pingMedian = s.computePingMedian();
        executor.submit("chiusura sessione " + s.name, () -> {
            if (s.sessionId > 0) {
                dao.closeSession(s.sessionId, s.playerId, quitAt, pingMedian, (quitAt - s.joinAt) / 1000);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onResourcePack(PlayerResourcePackStatusEvent event) {
        SessionSnapshot s = active.get(event.getPlayer().getUniqueId());
        if (s == null) return;
        String status = event.getStatus().name();
        int ms = (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - s.joinAt);
        s.packStatus = status;
        s.packMs = ms;
        executor.submit("stato resource pack " + s.name, () -> {
            if (s.sessionId > 0) dao.updateSessionPack(s.sessionId, status, ms);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClientOptions(PlayerClientOptionsChangeEvent event) {
        SessionSnapshot s = active.get(event.getPlayer().getUniqueId());
        if (s == null) return;
        // Aggiorna la fotografia in memoria: viene riscritta sul database al passaggio successivo.
        s.locale = event.getLocale();
        s.viewDistance = event.getViewDistance();
        SkinParts parts = event.getSkinParts();
        if (parts != null) s.skinParts = parts.getRaw();
        if (event.getMainHand() != null) s.mainHand = event.getMainHand().name();
    }

    // ============================== raccolta client ==============================

    /**
     * Fotografa le impostazioni del client, risolve il token di installazione e, se richiesto,
     * lancia l'analisi delle correlazioni.
     */
    private void capture(Player player, SessionSnapshot s, boolean analyze) {
        if (!player.isOnline()) return;
        try {
            s.brand = player.getClientBrandName();
            s.channels = Fingerprints.normalizeChannels(player.getListeningPluginChannels());
            s.channelsHash = s.channels == null ? null : hashing.hmac(s.channels);
            s.locale = player.getLocale();
            s.viewDistance = player.getClientOption(ClientOption.VIEW_DISTANCE);
            SkinParts parts = player.getClientOption(ClientOption.SKIN_PARTS);
            if (parts != null) s.skinParts = parts.getRaw();
            if (player.getClientOption(ClientOption.MAIN_HAND) != null) {
                s.mainHand = player.getClientOption(ClientOption.MAIN_HAND).name();
            }
            s.chatFlags = chatFlags(player);
        } catch (Throwable t) {
            // Un client che non espone una delle opzioni non deve impedire la raccolta del resto.
            plugin.getLogger().fine("Opzioni client non leggibili per " + player.getName() + ": " + t.getMessage());
        }
        s.fingerprint = Fingerprints.build(s, hashing);

        cookies.resolve(player).thenAccept(token -> {
            if (token != null) s.cookieToken = token;
            executor.submit("profilo client " + s.name, () -> {
                if (s.sessionId <= 0) return;
                dao.updateSessionClient(s);
                if (analyze && !dao.isExempt(s.playerId)) engine.analyze(s);
            });
        });
    }

    private static String chatFlags(Player player) {
        StringBuilder sb = new StringBuilder();
        ClientOption.ChatVisibility visibility = player.getClientOption(ClientOption.CHAT_VISIBILITY);
        sb.append("V=").append(visibility == null ? "?" : visibility.name().charAt(0));
        sb.append(",C=").append(bool(player.getClientOption(ClientOption.CHAT_COLORS_ENABLED)));
        sb.append(",F=").append(bool(player.getClientOption(ClientOption.TEXT_FILTERING_ENABLED)));
        sb.append(",L=").append(bool(player.getClientOption(ClientOption.ALLOW_SERVER_LISTINGS)));
        ClientOption.ParticleVisibility particles = player.getClientOption(ClientOption.PARTICLE_VISIBILITY);
        sb.append(",P=").append(particles == null ? "?" : particles.name().charAt(0));
        return sb.toString();
    }

    private static char bool(Boolean value) {
        return value == null ? '?' : (value ? '1' : '0');
    }

    // ============================== spegnimento ==============================

    /** Apre la profilazione per chi e' gia' collegato (avvio del plugin o ricarica della config). */
    public void trackOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) beginSession(player, null);
    }

    /** Allo stop del server chiude le sessioni ancora aperte, cosi' il conteggio ore resta corretto. */
    public void closeAllOpenSessions() {
        long now = System.currentTimeMillis();
        for (SessionSnapshot s : active.values()) {
            Integer pingMedian = s.computePingMedian();
            executor.submit("chiusura sessione (stop) " + s.name, () -> {
                if (s.sessionId > 0) {
                    dao.closeSession(s.sessionId, s.playerId, now, pingMedian, (now - s.joinAt) / 1000);
                }
            });
        }
        active.clear();
        tasks.values().forEach(list -> list.forEach(BukkitTask::cancel));
        tasks.clear();
    }
}
