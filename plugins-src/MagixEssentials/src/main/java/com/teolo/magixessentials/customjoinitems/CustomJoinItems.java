package com.teolo.magixessentials.customjoinitems;

import com.teolo.magixessentials.hook.Papi;
import com.teolo.magixessentials.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gli oggetti fissi nell'inventario: li si mette al login (o alla rinascita, o al cambio di
 * mondo) e si decide cosa i giocatori possono farci. Gira sia sull'hub (un inventario pulito con
 * la bussola dei server) sia sul faction (un oggetto fisso nella barra rapida), perche' ogni
 * server ha il suo {@code customjoinitems.yml} e il suo {@code items.yml}.
 *
 * <h2>Come riconosce i suoi oggetti</h2>
 * Ogni oggetto che crea porta nei suoi dati persistenti l'id della voce di {@code items.yml}.
 * Non conta il nome ne' il materiale: quello che lo staff rinomina o cambia nel file non fa
 * perdere il filo agli oggetti gia' in giro.
 *
 * <h2>Chi puo' fare cosa</h2>
 * Due livelli. Le regole <b>per oggetto</b> ({@code movable}, {@code droppable},
 * {@code vanilla-use}) riguardano solo gli oggetti del modulo; le regole <b>generali</b>
 * ({@code rules.allow-*}) riguardano tutto quello che il giocatore fa in quei mondi (buttare,
 * raccogliere, rompere, piazzare...) e servono a un hub dove nessuno deve toccare niente. Il
 * permesso {@link #PERM_BYPASS} le salta tutte, per chi costruisce.
 */
public final class CustomJoinItems implements Listener {

    public static final String PERM_BYPASS = "magixessentials.customjoinitems.bypass";

    /** Gli slot dell'inventario del giocatore: 0-35 zaino, 36-39 armatura, 40 seconda mano. */
    private static final int SLOTS = 41;

    private final JavaPlugin plugin;
    private final YamlConfiguration settings;
    private final Messages messages;
    private final NamespacedKey marker;
    private final Map<String, JoinItem> items = new LinkedHashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private boolean registered;

    private final List<String> worlds;
    private final boolean onJoin;
    private final boolean onRespawn;
    private final boolean onWorldChange;
    private final int delayTicks;
    private final boolean waitForLogin;
    private final int loginWaitTicks;
    private final boolean clearInventory;
    private final boolean removeOrphans;
    private final String occupied;
    private final boolean allowMove;
    private final boolean allowDrop;
    private final boolean allowPickup;
    private final boolean allowBreak;
    private final boolean allowPlace;
    private final boolean allowSwapHands;
    private final boolean allowItemDamage;

    public CustomJoinItems(JavaPlugin plugin, YamlConfiguration settings, Messages messages) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.marker = new NamespacedKey(plugin, "join_item");
        this.worlds = settings.getStringList("worlds");
        this.onJoin = settings.getBoolean("give-on.join", true);
        this.onRespawn = settings.getBoolean("give-on.respawn", true);
        this.onWorldChange = settings.getBoolean("give-on.world-change", false);
        this.delayTicks = Math.max(1, settings.getInt("give-delay-ticks", 5));
        this.waitForLogin = settings.getBoolean("wait-for-login", true);
        this.loginWaitTicks = Math.max(20, settings.getInt("login-wait-seconds", 300) * 20);
        this.clearInventory = settings.getBoolean("clear-inventory", false);
        this.removeOrphans = settings.getBoolean("remove-orphans", true);
        this.occupied = settings.getString("if-slot-occupied", "move").toLowerCase(Locale.ROOT);
        this.allowMove = settings.getBoolean("rules.allow-move", true);
        this.allowDrop = settings.getBoolean("rules.allow-drop", true);
        this.allowPickup = settings.getBoolean("rules.allow-pickup", true);
        this.allowBreak = settings.getBoolean("rules.allow-break-blocks", true);
        this.allowPlace = settings.getBoolean("rules.allow-place-blocks", true);
        this.allowSwapHands = settings.getBoolean("rules.allow-swap-hands", true);
        this.allowItemDamage = settings.getBoolean("rules.allow-item-damage", true);
    }

    // ------------------------------------------------------------- ciclo di vita

    public void start() {
        loadItems();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registered = true;
        plugin.getLogger().info("[CustomJoinItems] " + items.size() + " oggetti caricati ("
                + (worlds.isEmpty() ? "tutti i mondi" : "mondi: " + String.join(", ", worlds)) + ").");
        // Un reload a caldo (o un avvio con gente online) deve valere subito per chi c'e' gia'.
        for (Player p : Bukkit.getOnlinePlayers()) {
            giveLater(p, 1, false);
        }
    }

    public void stop() {
        if (!registered) return;
        HandlerList.unregisterAll(this);
        registered = false;
        cooldowns.clear();
    }

    /** items.yml e' un catalogo dello staff: nasce dal jar se manca e non si ripulisce. */
    private void loadItems() {
        File f = new File(plugin.getDataFolder(), "items.yml");
        if (!f.isFile() && plugin.getResource("items.yml") != null) {
            plugin.saveResource("items.yml", false);
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = y.getConfigurationSection("items");
        items.clear();
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            String[] problem = new String[1];
            JoinItem item = JoinItem.parse(id, s, problem);
            if (item == null) {
                plugin.getLogger().warning("[CustomJoinItems] items.yml: \"" + id + "\" saltato: " + problem[0] + ".");
                continue;
            }
            items.put(id, item);
        }
    }

    /** Descrizione per la guida dello staff: quanti oggetti ci sono adesso. */
    public int itemCount() {
        return items.size();
    }

    // ------------------------------------------------------------- comandi dello staff

    /** /mess joinitems give [giocatore|all]: rimette gli oggetti adesso. Torna quanti giocatori. */
    public int giveNow(Collection<? extends Player> targets) {
        int n = 0;
        for (Player p : targets) {
            if (inScope(p)) {
                give(p);
                n++;
            }
        }
        return n;
    }

    /** /mess joinitems remove [giocatore|all]: toglie solo gli oggetti del modulo. */
    public int removeNow(Collection<? extends Player> targets) {
        int n = 0;
        for (Player p : targets) {
            removeManaged(p, null);
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------- consegna

    private boolean inScope(Player p) {
        return worlds.isEmpty() || worlds.contains(p.getWorld().getName());
    }

    /** Le regole valgono per chi e' in un mondo del modulo e non ha il permesso di saltarle. */
    private boolean applies(Player p) {
        return inScope(p) && !p.hasPermission(PERM_BYPASS);
    }

    private void giveLater(Player p, int delay, boolean waitAuth) {
        giveLater(p, delay, waitAuth, 0);
    }

    private void giveLater(Player p, int delay, boolean waitAuth, int waited) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !registered) return;
            // Chi deve ancora fare il login (MagixAuth) non e' arrivato davvero: gli oggetti si
            // danno quando il cancello si apre, non prima.
            if (waitAuth && waitForLogin && waited < loginWaitTicks && frozenByAuth(p)) {
                giveLater(p, 20, true, waited + 20);
                return;
            }
            if (inScope(p)) {
                give(p);
            } else {
                removeManaged(p, null);
            }
        }, delay);
    }

    /** MagixAuth.gate().isFrozen(p), per riflessione: i plugin restano indipendenti. */
    private boolean frozenByAuth(Player p) {
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

    /** Mette gli oggetti nell'inventario di p, come dice items.yml. */
    private void give(Player p) {
        PlayerInventory inv = p.getInventory();
        if (clearInventory) {
            inv.clear();
        } else if (removeOrphans) {
            removeManaged(p, id -> {
                JoinItem it = items.get(id);
                return it == null || !it.allowedFor(p);
            });
        }
        for (JoinItem item : items.values()) {
            if (!item.allowedFor(p)) continue;
            // Niente doppioni: una copia spostata altrove (se movable) torna al suo posto.
            removeManaged(p, id -> id.equals(item.id));
            ItemStack old = inv.getItem(item.slot);
            if (old != null && !old.getType().isAir()) {
                switch (occupied) {
                    case "keep" -> {
                        continue;
                    }
                    case "move" -> {
                        int free = firstFree(inv, item.slot);
                        if (free < 0) continue;   // zaino pieno: non si butta niente di suo
                        inv.setItem(free, old);
                    }
                    default -> { /* replace: si sovrascrive */ }
                }
            }
            inv.setItem(item.slot, item.build(p, marker));
        }
    }

    private static int firstFree(PlayerInventory inv, int except) {
        for (int i = 0; i < 36; i++) {
            if (i == except) continue;
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType().isAir()) return i;
        }
        return -1;
    }

    /** Toglie gli oggetti del modulo; {@code which} sceglie per id (null = tutti). */
    private void removeManaged(Player p, java.util.function.Predicate<String> which) {
        PlayerInventory inv = p.getInventory();
        for (int slot = 0; slot < SLOTS; slot++) {
            String id = idOf(inv.getItem(slot));
            if (id != null && (which == null || which.test(id))) {
                inv.setItem(slot, null);
            }
        }
    }

    // ------------------------------------------------------------- riconoscimento

    private String idOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(marker, PersistentDataType.STRING);
    }

    /** Dell'oggetto del modulo, la voce; null se e' un oggetto qualunque (o di una voce tolta). */
    private JoinItem itemOf(ItemStack stack) {
        String id = idOf(stack);
        return id == null ? null : items.get(id);
    }

    // ------------------------------------------------------------- quando si danno

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (onJoin) giveLater(e.getPlayer(), delayTicks, true);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (onRespawn) giveLater(e.getPlayer(), delayTicks, false);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (inScope(p) ? onWorldChange : true) giveLater(p, delayTicks, false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        String prefix = e.getPlayer().getUniqueId() + ":";
        cooldowns.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Gli oggetti del modulo non cadono mai a terra: alla rinascita si rimettono (give-on.respawn). */
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(s -> idOf(s) != null);
    }

    // ------------------------------------------------------------- regole

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !applies(p)) return;
        if (!allowMove) {
            e.setCancelled(true);
            return;
        }
        ClickType click = e.getClick();
        JoinItem touched = itemOf(e.getCurrentItem());
        if (touched == null) touched = itemOf(e.getCursor());
        if (touched == null && click == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0) {
            touched = itemOf(p.getInventory().getItem(e.getHotbarButton()));
        }
        if (touched == null && click == ClickType.SWAP_OFFHAND) {
            touched = itemOf(p.getInventory().getItemInOffHand());
        }
        if (touched == null) return;
        boolean dropping = click == ClickType.DROP || click == ClickType.CONTROL_DROP;
        if (dropping ? !touched.droppable : !touched.movable) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !applies(p)) return;
        JoinItem dragged = itemOf(e.getOldCursor());
        if (!allowMove || (dragged != null && !dragged.movable)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (!applies(p)) return;
        JoinItem it = itemOf(e.getItemDrop().getItemStack());
        if (it != null ? !it.droppable : !allowDrop) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!applies(p)) return;
        JoinItem a = itemOf(e.getMainHandItem());
        JoinItem b = itemOf(e.getOffHandItem());
        if (!allowSwapHands || (a != null && !a.movable) || (b != null && !b.movable)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && !allowPickup && applies(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!allowBreak && applies(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (!applies(p)) return;
        JoinItem it = itemOf(e.getItemInHand());
        if (it != null ? !it.vanillaUse : !allowPlace) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent e) {
        if (!allowItemDamage && applies(e.getPlayer())) e.setCancelled(true);
    }

    // ------------------------------------------------------------- clic sull'oggetto

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Action action = e.getAction();
        if (action == Action.PHYSICAL) return;
        JoinItem it = itemOf(e.getItem());
        if (it == null) return;
        Player p = e.getPlayer();
        boolean left = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        if (applies(p) && !it.vanillaUse) {
            e.setUseItemInHand(Event.Result.DENY);
        }
        if (it.commands.isEmpty() || !it.matches(left, right)) return;
        // Il clic destro su un blocco arriva due volte (una per mano): si agisce per quella
        // in cui l'oggetto sta davvero.
        EquipmentSlot hand = e.getHand();
        if (hand == null) return;
        long now = System.currentTimeMillis();
        String key = p.getUniqueId() + ":" + it.id;
        Long until = cooldowns.get(key);
        if (until != null && until > now) {
            if (hand == EquipmentSlot.HAND) {
                messages.send(p, "customjoinitems.cooldown",
                        "seconds", String.valueOf((until - now + 999) / 1000));
            }
            return;
        }
        cooldowns.put(key, now + it.cooldownMillis);
        for (String raw : it.commands) {
            run(p, raw);
        }
    }

    /** "console: say ciao {player}" o "player: spawn": senza prefisso vale come il giocatore. */
    private void run(Player p, String raw) {
        String line = raw.trim();
        boolean console = false;
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.startsWith("console:")) {
            console = true;
            line = line.substring(8).trim();
        } else if (lower.startsWith("player:")) {
            line = line.substring(7).trim();
        }
        line = line.replace("{player}", p.getName()).replace("{uuid}", p.getUniqueId().toString())
                .replace("{world}", p.getWorld().getName());
        if (line.indexOf('%') >= 0) line = Papi.resolve(p, line);
        if (line.startsWith("/")) line = line.substring(1);
        if (line.isEmpty()) return;
        if (console) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
        } else {
            p.performCommand(line);
        }
    }

    /** Per il comando: i giocatori online il cui nome comincia per {@code prefix}. */
    public static List<String> onlineNames(String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix.toLowerCase(Locale.ROOT);
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (pl.getName().toLowerCase(Locale.ROOT).startsWith(p)) out.add(pl.getName());
        }
        return out;
    }

    /** Il giocatore online con questo nome (uguaglianza senza maiuscole), o null. */
    public static Player find(String name) {
        return Bukkit.getPlayerExact(name);
    }

}
