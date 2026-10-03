package com.teolo.magixessentials.hublobby;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * La lobby dell'hub: le regole di un server che fa da punto di arrivo della rete. Ogni funzione ha
 * la sua sezione in {@code hub-lobby.yml}, col suo interruttore. Per ora ce n'e' una sola.
 *
 * <h2>Spawn a ogni ingresso</h2>
 * Chi entra compare allo spawn della lobby, non dove era uscito. Si fa in due tempi:
 * <ol>
 *   <li>Su {@link AsyncPlayerSpawnLocationEvent}, prima che il giocatore entri nel mondo, si cambia
 *       il punto in cui comparira'. Gira a priorita' LOW, cioe' PRIMA di MagixAuth (HIGH): MagixAuth
 *       legge quel punto come la "posizione vera" da restituire dopo il login, e quindi dopo il
 *       login riporta il giocatore allo spawn invece che alla vecchia posizione. Nessun salto
 *       visibile e nessuna gara fra due teletrasporti.</li>
 *   <li>Dopo il login (o subito, senza MagixAuth) si controlla che sia davvero allo spawn, e se no
 *       ce lo si porta: copre chi entra per la prima volta (MagixAuth lo lascia al cancello) e
 *       qualunque altro plugin che al join lo sposti altrove.</li>
 * </ol>
 */
public final class HubLobby implements Listener {

    /** Il file delle impostazioni, come si chiama nel jar e nella cartella dati. */
    public static final String FILE = "hub-lobby.yml";

    /** Ogni quanto si ricontrolla se il login di MagixAuth e' fatto (20 tick = 1 secondo). */
    private static final int LOGIN_POLL_TICKS = 20;
    /** Al massimo quanto si aspetta il login prima di lasciar perdere: dopo, lo fa il join successivo. */
    private static final int LOGIN_WAIT_TICKS = 300 * 20;
    /** Quanti tick dopo il login: MagixAuth rimette il giocatore al suo posto dopo 5. */
    private static final int AFTER_LOGIN_TICKS = 10;
    /** Entro questa distanza (al quadrato, 1.5 blocchi) e' gia' allo spawn: non si teletrasporta. */
    private static final double NEAR_SQUARED = 2.25;

    private final JavaPlugin plugin;
    private final boolean spawnOnJoin;
    private final String worldName;
    private final boolean useWorldSpawn;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    /** Il mondo dello spawn, risolto all'avvio sul thread principale (l'evento di spawn e' asincrono). */
    private volatile World world;
    private boolean registered;

    public HubLobby(JavaPlugin plugin, YamlConfiguration settings) {
        this.plugin = plugin;
        this.spawnOnJoin = settings.getBoolean("spawn-on-join.enabled", true);
        this.worldName = settings.getString("spawn-on-join.world", "").trim();
        this.useWorldSpawn = settings.getBoolean("spawn-on-join.use-world-spawn", true);
        this.x = settings.getDouble("spawn-on-join.x", 0.5);
        this.y = settings.getDouble("spawn-on-join.y", 64.0);
        this.z = settings.getDouble("spawn-on-join.z", 0.5);
        this.yaw = (float) settings.getDouble("spawn-on-join.yaw", 0.0);
        this.pitch = (float) settings.getDouble("spawn-on-join.pitch", 0.0);
    }

    // ------------------------------------------------------------- ciclo di vita

    public void start() {
        World main = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        World chosen = worldName.isEmpty() ? main : Bukkit.getWorld(worldName);
        if (chosen == null) {
            plugin.getLogger().warning("[HubLobby] il mondo '" + worldName + "' di spawn-on-join.world "
                    + "non esiste: uso il mondo principale.");
            chosen = main;
        }
        this.world = chosen;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registered = true;
        plugin.getLogger().info("[HubLobby] spawn a ogni ingresso: " + (spawnOnJoin ? describe() : "spento") + ".");
    }

    public void stop() {
        if (!registered) return;
        HandlerList.unregisterAll(this);
        registered = false;
    }

    /** Una riga per il log e per la guida staff: dove compare chi entra. */
    public String describe() {
        if (!spawnOnJoin) return "spento";
        Location l = spawn();
        if (l == null) return "nessun mondo caricato";
        return (useWorldSpawn ? "lo spawn del mondo " : "il punto fisso in ") + l.getWorld().getName()
                + " " + Math.round(l.getX()) + "/" + Math.round(l.getY()) + "/" + Math.round(l.getZ());
    }

    /** Dove compare chi entra: lo spawn del mondo (centrato nel blocco) o il punto fisso del file. */
    private Location spawn() {
        World w = world;
        if (w == null) return null;
        if (!useWorldSpawn) return new Location(w, x, y, z, yaw, pitch);
        // Al centro del blocco, non sul suo spigolo (stessa scelta del cancello di MagixAuth);
        // yaw e pitch sono quelli che /setworldspawn ha salvato.
        Location l = w.getSpawnLocation().clone();
        l.setX(l.getBlockX() + 0.5);
        l.setZ(l.getBlockZ() + 0.5);
        return l;
    }

    /**
     * /mess lobby setspawn: scrive in hub-lobby.yml il punto in cui sta chi da' il comando, sguardo
     * compreso, e passa al punto fisso (use-world-spawn: false). Si scrive anche a modulo spento:
     * il punto e' pronto per quando lo si accende. I commenti del file restano (li conserva il
     * parser YAML, e comunque ConfigAlign li riallinea al sorgente al prossimo reload).
     *
     * @return false se il file non si e' potuto scrivere (il motivo finisce nel log)
     */
    public static boolean saveSpawn(JavaPlugin plugin, Location where) {
        File f = new File(plugin.getDataFolder(), FILE);
        if (!f.isFile()) plugin.saveResource(FILE, false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        y.set("spawn-on-join.world", where.getWorld().getName());
        y.set("spawn-on-join.use-world-spawn", false);
        y.set("spawn-on-join.x", round(where.getX()));
        y.set("spawn-on-join.y", round(where.getY()));
        y.set("spawn-on-join.z", round(where.getZ()));
        y.set("spawn-on-join.yaw", round(where.getYaw()));
        y.set("spawn-on-join.pitch", round(where.getPitch()));
        try {
            y.save(f);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("[HubLobby] spawn non salvato in " + FILE + " (" + e.getMessage() + ").");
            return false;
        }
    }

    /** Due decimali bastano (un centesimo di blocco, di grado) e il file resta leggibile. */
    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ------------------------------------------------------------- spawn a ogni ingresso

    /** Prima che il giocatore entri nel mondo: comparira' allo spawn (e MagixAuth lo ricordera' cosi'). */
    @EventHandler(priority = EventPriority.LOW)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent e) {
        if (!spawnOnJoin) return;
        Location l = spawn();
        if (l != null) e.setSpawnLocation(l);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!spawnOnJoin) return;
        checkLater(e.getPlayer(), AFTER_LOGIN_TICKS, 0);
    }

    /** Dopo il login di MagixAuth: se non e' allo spawn (primo ingresso, altri plugin), ce lo porta. */
    private void checkLater(Player p, int delay, int waited) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !registered) return;
            if (frozenByAuth(p)) {
                if (waited < LOGIN_WAIT_TICKS) checkLater(p, LOGIN_POLL_TICKS, waited + LOGIN_POLL_TICKS);
                return;
            }
            Location l = spawn();
            if (l == null) return;
            Location at = p.getLocation();
            if (at.getWorld() != l.getWorld() || at.distanceSquared(l) > NEAR_SQUARED) {
                p.teleportAsync(l);
            }
        }, delay);
    }

    /**
     * Chi e' ancora al cancello di MagixAuth non e' entrato davvero: il controllo si fa dopo il
     * login, altrimenti lo si sposterebbe via dal cancello. Per riflessione (MagixAuth.gate()
     * .isFrozen), come fa customjoinitems: i plugin restano indipendenti.
     */
    private static boolean frozenByAuth(Player p) {
        Plugin auth = Bukkit.getPluginManager().getPlugin("MagixAuth");
        if (auth == null || !auth.isEnabled()) return false;
        try {
            Object gate = auth.getClass().getMethod("gate").invoke(auth);
            Method m = gate.getClass().getMethod("isFrozen", Player.class);
            return Boolean.TRUE.equals(m.invoke(gate, p));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
