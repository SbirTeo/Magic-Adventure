package com.teolo.magixessentials.customjoinitems;

import com.teolo.magixessentials.hook.Papi;
import com.teolo.magixessentials.util.TextFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Una voce della sezione {@code items} di {@code customjoinitems.yml}: l'oggetto che il modulo mette in un certo slot, con le regole che
 * lo riguardano (si puo' spostare? buttare? cosa fa al clic?).
 *
 * <p>L'oggetto vero non vive qui: {@link #build(Player, NamespacedKey)} ne costruisce uno nuovo per
 * ogni giocatore (nome e descrizione passano da PlaceholderAPI) e ci scrive sopra l'id della voce,
 * cosi' il modulo lo riconosce anche dopo un riavvio, dentro qualunque inventario.</p>
 */
final class JoinItem {

    /** Il clic che fa partire i comandi della lista {@code commands}. */
    enum Click { ANY, LEFT, RIGHT }

    /** Le liste di azioni per clic, dalla piu' precisa alla piu' generica (vedi {@link #actionsFor}). */
    static final String[] CLICK_KEYS = {"shift-left-click", "shift-right-click", "left-click", "right-click"};

    final String id;
    final int slot;
    final Material material;
    final int amount;
    final String name;
    final List<String> lore;
    final boolean glow;
    final boolean hideFlags;
    final boolean unbreakable;
    final int customModelData;
    final NamespacedKey itemModel;
    final String permission;
    final boolean movable;
    final boolean droppable;
    final boolean vanillaUse;
    final Click click;
    final List<String> commands;
    /** Le azioni per clic: shift-left-click, shift-right-click, left-click, right-click. */
    final java.util.Map<String, List<String>> clickActions;
    final boolean runInInventory;
    final long cooldownMillis;

    private JoinItem(String id, int slot, Material material, ConfigurationSection s) {
        this.id = id;
        this.slot = slot;
        this.material = material;
        this.amount = Math.max(1, Math.min(64, s.getInt("amount", 1)));
        this.name = s.getString("name", "");
        this.lore = s.getStringList("lore");
        this.glow = s.getBoolean("glow", false);
        this.hideFlags = s.getBoolean("hide-flags", true);
        this.unbreakable = s.getBoolean("unbreakable", true);
        this.customModelData = s.getInt("custom-model-data", 0);
        this.itemModel = key(s.getString("item-model", ""));
        this.permission = s.getString("permission", "");
        this.movable = s.getBoolean("movable", false);
        this.droppable = s.getBoolean("droppable", false);
        this.vanillaUse = s.getBoolean("vanilla-use", false);
        this.click = parseClick(s.getString("click", "any"));
        this.commands = s.getStringList("commands");
        java.util.Map<String, List<String>> byClick = new java.util.HashMap<>();
        for (String k : CLICK_KEYS) {
            List<String> l = s.getStringList(k);
            if (!l.isEmpty()) byClick.put(k, l);
        }
        this.clickActions = java.util.Map.copyOf(byClick);
        this.runInInventory = s.getBoolean("run-in-inventory", false);
        this.cooldownMillis = Math.max(0, (long) (s.getDouble("cooldown-seconds", 1.0) * 1000));
    }

    /**
     * Legge una voce. Torna {@code null} (e il motivo finisce in {@code problem[0]}) se manca il
     * materiale o lo slot: una voce sbagliata si salta, le altre funzionano.
     */
    static JoinItem parse(String id, ConfigurationSection s, String[] problem) {
        Material material = Material.matchMaterial(s.getString("material", ""));
        if (material == null || material.isAir() || !material.isItem()) {
            problem[0] = "material \"" + s.getString("material", "") + "\" sconosciuto";
            return null;
        }
        int slot = parseSlot(s.getString("slot", ""));
        if (slot < 0) {
            problem[0] = "slot \"" + s.getString("slot", "") + "\" non valido (0-40, o helmet/chestplate/leggings/boots/offhand)";
            return null;
        }
        return new JoinItem(id, slot, material, s);
    }

    /** 0-8 barra rapida, 9-35 zaino, 36-39 armatura (stivali...elmo), 40 seconda mano. */
    static int parseSlot(String raw) {
        if (raw == null) return -1;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "boots": return 36;
            case "leggings": return 37;
            case "chestplate": return 38;
            case "helmet": return 39;
            case "offhand": return 40;
            default:
        }
        try {
            int n = Integer.parseInt(v);
            return n >= 0 && n <= 40 ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Click parseClick(String raw) {
        try {
            return Click.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return Click.ANY;
        }
    }

    private static NamespacedKey key(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return NamespacedKey.fromString(raw.trim().toLowerCase(Locale.ROOT));
    }

    /** Se il giocatore la riceve: nessun permesso scritto = tutti. */
    boolean allowedFor(Player p) {
        return permission == null || permission.isBlank() || p.hasPermission(permission);
    }

    /** Se l'oggetto ha almeno un'azione legata a un clic. */
    boolean hasActions() {
        return !commands.isEmpty() || !clickActions.isEmpty();
    }

    /**
     * Le azioni per questo clic. Vince la lista piu' precisa: con shift premuto prima
     * {@code shift-left-click}/{@code shift-right-click}, poi {@code left-click}/{@code right-click},
     * e per ultima {@code commands} (filtrata da {@code click}). Mai due liste insieme.
     */
    List<String> actionsFor(boolean left, boolean right, boolean shift) {
        String side = left ? "left-click" : right ? "right-click" : null;
        if (side == null) return List.of();
        if (shift) {
            List<String> l = clickActions.get("shift-" + side);
            if (l != null) return l;
        }
        List<String> l = clickActions.get(side);
        if (l != null) return l;
        boolean ok = switch (click) {
            case ANY -> true;
            case LEFT -> left;
            case RIGHT -> right;
        };
        return ok ? commands : List.of();
    }

    /** L'oggetto per questo giocatore, marchiato con l'id della voce. */
    ItemStack build(Player p, NamespacedKey marker) {
        ItemStack stack = new ItemStack(material, amount);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        if (!name.isEmpty()) {
            meta.displayName(line(p, name));
        }
        if (!lore.isEmpty()) {
            List<Component> out = new ArrayList<>();
            for (String l : lore) out.add(line(p, l));
            meta.lore(out);
        }
        if (glow) meta.setEnchantmentGlintOverride(true);
        if (unbreakable) meta.setUnbreakable(true);
        if (hideFlags) meta.addItemFlags(ItemFlag.values());
        if (customModelData != 0) meta.setCustomModelData(customModelData);
        if (itemModel != null) meta.setItemModel(itemModel);
        meta.getPersistentDataContainer().set(marker, org.bukkit.persistence.PersistentDataType.STRING, id);
        stack.setItemMeta(meta);
        return stack;
    }

    /** Una riga di nome/descrizione: {player}, poi PlaceholderAPI, poi i colori, senza il corsivo di serie. */
    private static Component line(Player p, String raw) {
        String text = com.teolo.magixessentials.lang.Messages.phrase(p, raw).replace("{player}", p.getName());
        if (text.indexOf('%') >= 0) text = Papi.resolve(p, text);
        return TextFormat.component(text).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
