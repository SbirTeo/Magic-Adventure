package com.teolo.magixentities.manage;

import com.teolo.magixentities.command.MeCommand;
import com.teolo.magixentities.lang.Messages;
import com.teolo.magixentities.model.NpcDef;
import com.teolo.magixentities.util.Colors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * /mentities editor &lt;nome&gt;: un pannello (cassa 6x9) con tutto quello che si puo' fare a
 * un'entita', piu' una schermata "Posizione" per spostarla di pochi blocchi alla volta.
 *
 * I pulsanti non rifanno la logica dei comandi: li eseguono per conto del giocatore
 * ({@code performCommand}). Cosi' editor e comandi non possono comportarsi in modo diverso, e
 * messaggi, permessi e controlli sono gli stessi. Quello che richiede un testo libero (nome,
 * displayname, skin, tipo, comandi al clic) chiude il pannello e propone il comando in chat.
 */
public final class EditorMenu implements Listener {

    private static final int SIZE = 54;
    /** Passi della sezione Posizione, in blocchi: il piu' fine serve ad allineare una statua seduta. */
    private static final double[] STEPS = {0.05, 0.1, 0.25, 0.5, 1.0};
    private static final int DEFAULT_STEP = 1;
    private static final double SCALE_STEP = 0.25;
    /** Slot delle opzioni on/off, nell'ordine di {@link NpcDef#OPTIONS} (il 33 e' il raggio del follow). */
    private static final int[] OPTION_SLOTS = {19, 20, 21, 22, 23, 24, 25, 30, 31, 32};

    private enum View { MAIN, POSITION }

    /** Stato di un pannello aperto: quale entita', quale schermata, che passo. */
    private static final class Holder implements InventoryHolder {
        private final String name;
        private View view = View.MAIN;
        private int step = DEFAULT_STEP;
        private final Map<Integer, BiConsumer<Player, ClickType>> actions = new HashMap<>();
        private Inventory inventory;

        private Holder(String name) {
            this.name = name;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final JavaPlugin plugin;
    private final NpcManager npcs;
    private final Messages M;

    public EditorMenu(JavaPlugin plugin, NpcManager npcs, Messages messages) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.M = messages;
    }

    public void open(Player player, NpcDef d) {
        Holder h = new Holder(d.name);
        h.inventory = Bukkit.createInventory(h, SIZE,
                Colors.component(M.forPlayer(player, "editor-title", "name", d.name)));
        render(player, h);
        player.openInventory(h.inventory);
    }

    // ------------------------------------------------------------------ disegno

    private void render(Player p, Holder h) {
        NpcDef d = npcs.get(h.name);
        if (d == null) {
            later(p::closeInventory);
            return;
        }
        h.actions.clear();
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < SIZE; i++) h.inventory.setItem(i, filler);
        if (h.view == View.MAIN) renderMain(p, h, d);
        else renderPosition(p, h, d);
    }

    private void renderMain(Player p, Holder h, NpcDef d) {
        String n = d.name;
        put(h, 4, item(Material.NAME_TAG, text(p, "editor-info-name", "name", n), lines(p, "editor-info-lore", info(d))),
                (pl, c) -> run(pl, "info " + n));

        // --- aspetto
        put(h, 10, item(Material.OAK_SIGN, text(p, "editor-display-name"),
                        lines(p, "editor-display-lore", "display", Colors.translate(d.displayText()))),
                (pl, c) -> suggest(pl, "/mentities displayname " + n + " "));
        put(h, 11, item(Material.WRITABLE_BOOK, text(p, "editor-rename-name"), lines(p, "editor-rename-lore", "name", n)),
                (pl, c) -> suggest(pl, "/mentities name " + n + " "));
        if (d.isPlayerType()) {
            put(h, 12, item(Material.PLAYER_HEAD, text(p, "editor-skin-name"),
                            lines(p, "editor-skin-lore", "skin", d.isSkinMirror() ? NpcDef.MIRROR : d.skinNick())),
                    (pl, c) -> {
                        if (c.isRightClick()) run(pl, "skin " + n + " " + NpcDef.MIRROR);
                        else suggest(pl, "/mentities skin " + n + " ");
                    });
            put(h, 13, item(Material.ARMOR_STAND, text(p, "editor-pose-name"), lines(p, "editor-pose-lore", "pose", pose(d))),
                    (pl, c) -> run(pl, "pose " + n + " " + nextPose(d, c.isRightClick() ? -1 : 1)));
        }
        put(h, 14, item(Material.SLIME_BALL, text(p, "editor-scale-name"),
                        lines(p, "editor-scale-lore", "scale", num(d.scale), "step", num(SCALE_STEP))),
                (pl, c) -> run(pl, "scale " + n + " " + scaleAfter(d, c)));
        put(h, 15, item(Material.SPAWNER, text(p, "editor-type-name"), lines(p, "editor-type-lore", "type", typeLabel(d))),
                (pl, c) -> suggest(pl, "/mentities type " + n + " "));
        put(h, 16, item(Material.IRON_CHESTPLATE, text(p, "editor-equip-name"), lines(p, "editor-equip-lore")),
                (pl, c) -> later(() -> pl.performCommand("mentities equip " + n)));

        // --- opzioni on/off (immovable vale solo per il tipo player)
        int i = 0;
        for (String o : NpcDef.OPTIONS) {
            if (o.equals("immovable") && !d.isPlayerType()) continue;
            boolean on = d.opt(o, false);
            put(h, OPTION_SLOTS[i++], item(on ? Material.LIME_DYE : Material.GRAY_DYE,
                            text(p, "editor-option-name", "option", o),
                            lines(p, "editor-option-lore", "desc", text(p, "editor-option-desc-" + o),
                                    "state", text(p, on ? "editor-option-on" : "editor-option-off"))),
                    (pl, c) -> run(pl, "set " + n + " " + o + " " + (on ? "off" : "on")));
        }

        // --- raggio del follow di questa entita'
        double defRadius = plugin.getConfig().getDouble("follow.radius", 12.0);
        double radius = d.followRadiusOr(defRadius);
        put(h, 33, item(Material.SPYGLASS, text(p, "editor-radius-name"),
                        lines(p, "editor-radius-lore", "radius", num(radius),
                                "source", text(p, d.followRadius == null ? "editor-radius-default" : "editor-radius-own"),
                                "default", num(defRadius))),
                (pl, c) -> run(pl, "followradius " + n + " " + radiusAfter(radius, c)));

        // --- posizione e azioni
        put(h, 37, item(Material.COMPASS, text(p, "editor-position-name"),
                        lines(p, "editor-position-lore", "world", d.world,
                                "x", MeCommand.coord(d.x), "y", MeCommand.coord(d.y), "z", MeCommand.coord(d.z))),
                (pl, c) -> h.view = View.POSITION);
        put(h, 38, item(Material.ENDER_PEARL, text(p, "editor-tp-name"), lines(p, "editor-tp-lore")),
                (pl, c) -> later(() -> {
                    pl.closeInventory();
                    pl.performCommand("mentities tp " + n);
                }));
        put(h, 39, item(Material.LEAD, text(p, "editor-here-name"), lines(p, "editor-here-lore")),
                (pl, c) -> run(pl, "here " + n));
        put(h, 40, item(Material.COMMAND_BLOCK, text(p, "editor-commands-name"),
                        lines(p, "editor-commands-lore", "count", String.valueOf(d.commands.size()))),
                (pl, c) -> {
                    if (c.isRightClick()) {
                        suggest(pl, "/mentities cmd " + n + " add ");
                    } else {
                        later(() -> {
                            pl.closeInventory();
                            pl.performCommand("mentities cmd " + n + " list");
                        });
                    }
                });
        put(h, 41, item(Material.RECOVERY_COMPASS, text(p, "editor-respawn-name"), lines(p, "editor-respawn-lore")),
                (pl, c) -> run(pl, "respawn " + n));
        // Rimozione: solo proposta in chat, serve un invio consapevole (come sotto /mentities info).
        put(h, 43, item(Material.TNT, text(p, "editor-remove-name"), lines(p, "editor-remove-lore")),
                (pl, c) -> suggest(pl, "/mentities remove " + n));
        put(h, 49, item(Material.BARRIER, text(p, "editor-close-name"), List.of()),
                (pl, c) -> later(pl::closeInventory));
    }

    private void renderPosition(Player p, Holder h, NpcDef d) {
        String n = d.name;
        double step = STEPS[h.step];
        String s = num(step);
        put(h, 4, item(Material.COMPASS, text(p, "editor-pos-header-name", "name", n),
                        lines(p, "editor-pos-header-lore", "world", d.world, "x", MeCommand.coord(d.x),
                                "y", MeCommand.coord(d.y), "z", MeCommand.coord(d.z), "step", s)),
                null);

        axisRow(p, h, n, "x", d.x, 11, s, step, false);
        // Con la gravita' accesa un'entita' sollevata ricade: lo si dice sui pulsanti di Y. Una
        // statua seduta sta sul sedile (senza gravita') e quindi resta dove la metti.
        boolean falls = d.opt("gravity", true) && !"sitting".equalsIgnoreCase(d.pose);
        axisRow(p, h, n, "y", d.y, 20, s, step, falls);
        axisRow(p, h, n, "z", d.z, 29, s, step, false);

        put(h, 39, item(Material.LEAD, text(p, "editor-here-name"), lines(p, "editor-here-lore")),
                (pl, c) -> run(pl, "here " + n));
        put(h, 40, item(Material.CLOCK, text(p, "editor-pos-step-name", "step", s), lines(p, "editor-pos-step-lore")),
                (pl, c) -> h.step = Math.floorMod(h.step + (c.isRightClick() ? -1 : 1), STEPS.length));
        put(h, 45, item(Material.ARROW, text(p, "editor-back-name"), List.of()),
                (pl, c) -> h.view = View.MAIN);
        put(h, 49, item(Material.BARRIER, text(p, "editor-close-name"), List.of()),
                (pl, c) -> later(pl::closeInventory));
    }

    /** Una riga "meno · asse · piu'" a partire dallo slot del meno. */
    private void axisRow(Player p, Holder h, String n, String axis, double value, int slot,
                         String s, double step, boolean falls) {
        String label = axis.toUpperCase(Locale.ROOT);
        List<String> hint = new ArrayList<>(lines(p, "editor-pos-axis-lore-" + axis));
        if (falls) hint.add(text(p, "editor-pos-gravity-hint"));
        put(h, slot, item(Material.RED_CONCRETE, text(p, "editor-pos-minus-name", "axis", label, "step", s), hint),
                (pl, c) -> run(pl, "position " + n + " " + axis + " " + num(-step)));
        put(h, slot + 2, item(Material.PAPER, text(p, "editor-pos-axis-name", "axis", label,
                        "value", MeCommand.coord(value)), hint),
                null);
        put(h, slot + 4, item(Material.LIME_CONCRETE, text(p, "editor-pos-plus-name", "axis", label, "step", s), hint),
                (pl, c) -> run(pl, "position " + n + " " + axis + " " + num(step)));
    }

    // ------------------------------------------------------------------ eventi

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        // Tutto annullato, anche nel proprio inventario: con il pannello aperto non deve entrare
        // ne' uscire nessun oggetto (shift-clic, tasti numerici, doppio clic).
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        BiConsumer<Player, ClickType> action = h.actions.get(e.getRawSlot());
        if (action == null) return;
        if (npcs.get(h.name) == null) {
            later(p::closeInventory);
            return;
        }
        action.accept(p, e.getClick());
        if (p.getOpenInventory().getTopInventory().getHolder() == h) render(p, h);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ azioni

    /** Esegue un sottocomando di /mentities per il giocatore: stessi controlli e messaggi. */
    private void run(Player p, String sub) {
        p.performCommand("mentities " + sub);
    }

    /** Chiude il pannello e propone il comando in chat, da completare e inviare. */
    private void suggest(Player p, String cmd) {
        later(() -> {
            p.closeInventory();
            p.sendMessage(Colors.component(text(p, "editor-suggest", "cmd", cmd))
                    .clickEvent(ClickEvent.suggestCommand(cmd))
                    .hoverEvent(HoverEvent.showText(Colors.component(text(p, "editor-suggest-hover")))));
        });
    }

    /** Aprire o chiudere un inventario dentro un InventoryClickEvent va rimandato al tick dopo. */
    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    /** Posa successiva (dir 1) o precedente (-1) fra quelle accettate da /mentities pose. */
    private String nextPose(NpcDef d, int dir) {
        List<String> poses = npcs.validPoses();
        int at = poses.indexOf(pose(d));
        return poses.get(Math.floorMod(at + dir, poses.size()));
    }

    /** Sinistro +passo, destro -passo, shift = di 1 intero, Q = torna a 1. */
    private static String scaleAfter(NpcDef d, ClickType c) {
        if (c == ClickType.DROP || c == ClickType.CONTROL_DROP) return "reset";
        double delta = c.isShiftClick() ? 1.0 : SCALE_STEP;
        double v = d.scale + (c.isRightClick() ? -delta : delta);
        return num(Math.round(v * 100) / 100.0);
    }

    /** Sinistro +2, destro -2, shift = di 8, Q = torna al default; sempre dentro i limiti del comando. */
    private static String radiusAfter(double radius, ClickType c) {
        if (c == ClickType.DROP || c == ClickType.CONTROL_DROP) return "reset";
        double delta = c.isShiftClick() ? 8 : 2;
        double v = radius + (c.isRightClick() ? -delta : delta);
        v = Math.max(MeCommand.MIN_FOLLOW_RADIUS, Math.min(MeCommand.MAX_FOLLOW_RADIUS, v));
        return num(v);
    }

    // ------------------------------------------------------------------ utilita'

    private void put(Holder h, int slot, ItemStack item, BiConsumer<Player, ClickType> action) {
        h.inventory.setItem(slot, item);
        if (action != null) h.actions.put(slot, action);
    }

    private String text(Player p, String key, String... kv) {
        return M.forPlayer(p, key, kv);
    }

    private List<String> lines(Player p, String key, String... kv) {
        return M.listForPlayer(p, key, kv);
    }

    private static String[] info(NpcDef d) {
        return new String[] {
                "type", typeLabel(d),
                "display", Colors.translate(d.displayText()),
                "world", d.world,
                "x", MeCommand.coord(d.x), "y", MeCommand.coord(d.y), "z", MeCommand.coord(d.z),
                "scale", num(d.scale),
                "pose", d.isPlayerType() ? pose(d) : "-"};
    }

    private static String pose(NpcDef d) {
        return d.pose == null || d.pose.isBlank() ? "standing" : d.pose.toLowerCase(Locale.ROOT);
    }

    private static String typeLabel(NpcDef d) {
        return d.isPlayerType() ? "player" : d.type.name().toLowerCase(Locale.ROOT);
    }

    /** Numero senza zeri inutili (1.0 -> "1", 0.25 resta "0.25"). */
    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Colors.component(name).decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) {
                List<Component> out = new ArrayList<>();
                for (String l : lore) out.add(Colors.component(l).decoration(TextDecoration.ITALIC, false));
                meta.lore(out);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
