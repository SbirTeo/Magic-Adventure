package com.teolo.magixentities.manage;

import com.teolo.magixentities.lang.Messages;
import com.teolo.magixentities.model.NpcDef;
import com.teolo.magixentities.util.Colors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu (cassa 3x9) per equipaggiare un'entita': testa, corpo, gambe, piedi, mano e mano secondaria.
 *
 * Tutti i clic sono gestiti a mano (l'evento viene sempre annullato): i vetri di riempimento e le
 * etichette non devono poter finire nell'inventario del giocatore, e negli slot buoni si deve poter
 * solo posare, prendere o scambiare un oggetto.
 */
public final class EquipMenu implements Listener {

    /** Slot del menu, nell'ordine di {@link NpcDef#EQUIPMENT}. */
    private static final int[] SLOTS = {10, 11, 12, 13, 15, 16};
    private static final String[] LABELS = {
            "&fTesta", "&fCorpo", "&fGambe", "&fPiedi", "&fMano principale", "&fMano secondaria"};

    private final JavaPlugin plugin;
    private final NpcManager npcs;
    private final MirrorManager mirror;
    private final Messages M;
    /** Marchio sugli oggetti decorativi del menu: cosi' un vetro VERO puo' essere equipaggiato. */
    private final NamespacedKey menuKey;

    public EquipMenu(JavaPlugin plugin, NpcManager npcs, MirrorManager mirror, Messages messages) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.mirror = mirror;
        this.M = messages;
        this.menuKey = new NamespacedKey(plugin, "menu");
    }

    /** Contenitore che ci permette di riconoscere il nostro menu negli eventi. */
    private static final class Holder implements InventoryHolder {
        private final NpcDef def;
        private Inventory inventory;

        private Holder(NpcDef def) {
            this.def = def;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public void open(Player player, NpcDef d) {
        Holder holder = new Holder(d);
        Inventory inv = Bukkit.createInventory(holder, 27, Colors.component(M.get("equip-title", "name", d.name)));
        holder.inventory = inv;

        ItemStack filler = decor(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        for (int i = 0; i < SLOTS.length; i++) {
            ItemStack current = d.equipment.get(NpcDef.EQUIPMENT.get(i));
            inv.setItem(SLOTS[i], current != null ? current.clone() : label(i));
        }
        inv.setItem(4, decor(Material.PAPER, M.get("equip-guide-title"), M.getList("equip-guide-lore")));

        player.openInventory(inv);
    }

    /** Etichetta dello slot vuoto (vetro azzurro con il nome del pezzo). */
    private ItemStack label(int index) {
        return decor(Material.LIGHT_BLUE_STAINED_GLASS_PANE, LABELS[index], List.of(M.get("equip-slot-hint")));
    }

    private ItemStack decor(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Colors.component(name).decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) {
                List<Component> lines = new ArrayList<>();
                for (String l : lore) lines.add(Colors.component(l).decoration(TextDecoration.ITALIC, false));
                meta.lore(lines);
            }
            meta.getPersistentDataContainer().set(menuKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** true se l'oggetto e' decorazione del menu (e quindi non equipaggiamento). */
    private boolean isDecor(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(menuKey, PersistentDataType.BYTE);
    }

    private int equipIndex(int rawSlot) {
        for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == rawSlot) return i;
        return -1;
    }

    private int firstFreeEquipSlot(Inventory inv) {
        for (int slot : SLOTS) if (isDecor(inv.getItem(slot))) return slot;
        return -1;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) return;
        Player player = (Player) e.getWhoClicked();
        Inventory menu = e.getInventory();
        int raw = e.getRawSlot();
        boolean inMenu = raw >= 0 && raw < menu.getSize();

        // Il doppio clic raccoglie oggetti uguali da TUTTE le griglie, decorazioni comprese.
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }

        if (!inMenu) {
            // Nel proprio inventario si fa quello che si vuole, tranne lo shift-clic: quello
            // manda l'oggetto nel primo slot libero del menu.
            if (!e.isShiftClick()) return;
            e.setCancelled(true);
            ItemStack moving = e.getCurrentItem();
            if (moving == null || moving.getType().isAir()) return;
            int target = firstFreeEquipSlot(menu);
            if (target < 0) return;
            menu.setItem(target, moving.clone());
            e.setCurrentItem(null);
            refresh(player);
            return;
        }

        // Da qui in poi siamo dentro il menu: gestiamo tutto noi.
        e.setCancelled(true);
        int index = equipIndex(raw);
        if (index < 0) return; // riempimento o guida: intoccabili

        ItemStack inSlot = menu.getItem(raw);
        ItemStack cursor = e.getCursor();
        boolean slotEmpty = inSlot == null || inSlot.getType().isAir() || isDecor(inSlot);
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();

        if (e.isShiftClick()) {
            if (slotEmpty) return;
            player.getInventory().addItem(inSlot.clone());
            menu.setItem(raw, label(index));
        } else if (cursorEmpty) {
            if (slotEmpty) return; // l'etichetta non si prende
            player.setItemOnCursor(inSlot.clone());
            menu.setItem(raw, label(index));
        } else {
            // posa l'oggetto (e ti restituisce sul cursore quello che c'era)
            menu.setItem(raw, cursor.clone());
            player.setItemOnCursor(slotEmpty ? null : inSlot.clone());
        }
        refresh(player);
    }

    /** Nessun trascinamento dentro il menu: la posa passa dai clic gestiti sopra. */
    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) return;
        for (int raw : e.getRawSlots()) {
            if (raw < e.getInventory().getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** Alla chiusura salviamo i sei slot e li applichiamo all'entita'. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder holder)) return;
        NpcDef d = holder.def;
        Inventory menu = e.getInventory();

        for (int i = 0; i < SLOTS.length; i++) {
            String key = NpcDef.EQUIPMENT.get(i);
            ItemStack item = menu.getItem(SLOTS[i]);
            if (item == null || item.getType().isAir() || isDecor(item)) d.equipment.remove(key);
            else d.equipment.put(key, item.clone());
        }
        npcs.save();

        mirror.clear(d); // le copie vanno rifatte con il nuovo equipaggiamento
        Entity entity = npcs.entityOf(d);
        if (entity != null) npcs.apply(d, entity);

        if (e.getPlayer() instanceof Player player) {
            cleanupDecor(player);
            // Un oggetto rimasto sul cursore andrebbe perso: lo restituiamo.
            ItemStack cursor = player.getItemOnCursor();
            if (cursor != null && !cursor.getType().isAir() && !isDecor(cursor)) {
                player.setItemOnCursor(null);
                player.getInventory().addItem(cursor).values()
                        .forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
            } else if (isDecor(cursor)) {
                player.setItemOnCursor(null);
            }
        }
        M.send(e.getPlayer(), "equip-saved", "name", d.name);
    }

    /** Toglie dall'inventario i vetri del menu finiti li' con le versioni precedenti. */
    private void cleanupDecor(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            if (isDecor(contents[i])) {
                contents[i] = null;
                changed = true;
            }
        }
        if (changed) {
            player.getInventory().setContents(contents);
            M.send(player, "equip-cleanup");
        }
    }

    /** Abbiamo modificato l'inventario ad evento annullato: il client va risincronizzato. */
    private void refresh(Player player) {
        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
    }
}
