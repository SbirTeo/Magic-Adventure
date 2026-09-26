package com.teolo.magixpack.command;

import com.teolo.magixpack.MagixPack;
import com.teolo.magixpack.glyph.GlyphEntry;
import com.teolo.magixpack.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** {@code /mpack}: reload (magixpack.admin), oggetti custom (magixpack.item.give), icone custom
 *  via font (magixpack.glyph.list) — vedi README.md per items.yml/glyphs.yml. */
public final class MagixPackCommand implements CommandExecutor, TabCompleter {

    private final MagixPack plugin;
    private final Messages messages;

    public MagixPackCommand(MagixPack plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(messages.get(sender, "usage"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "item" -> item(sender, args);
            case "glyph" -> glyph(sender, args);
            default -> sender.sendMessage(messages.get(sender, "usage"));
        }
        return true;
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("magixpack.admin")) {
            sender.sendMessage(messages.get(sender, "no-permission"));
            return;
        }
        plugin.reload();
        sender.sendMessage(messages.get(sender, "reloaded"));
    }

    // --------------------------------------------------------------------------------------- item

    private void item(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(messages.get(sender, "item-usage"));
            return;
        }
        if (!sender.hasPermission("magixpack.item.give")) {
            sender.sendMessage(messages.get(sender, "no-permission"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "give" -> itemGive(sender, args);
            case "list" -> itemList(sender);
            default -> sender.sendMessage(messages.get(sender, "item-usage"));
        }
    }

    private void itemGive(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(messages.get(sender, "item-usage"));
            return;
        }
        String id = args[2];
        if (!plugin.itemCatalog().has(id)) {
            sender.sendMessage(messages.get(sender, "item-unknown").replace("{item}", id));
            return;
        }
        Player target;
        if (args.length >= 4) {
            target = Bukkit.getPlayerExact(args[3]);
            if (target == null) {
                sender.sendMessage(messages.get(sender, "player-not-found").replace("{player}", args[3]));
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(messages.get(sender, "player-required"));
            return;
        }
        if (plugin.itemCatalog().entry(id).playerAvatar()) {
            // The avatar in the lore needs the skin: wait for the download (usually already done
            // at join), then give on the main thread. Without a skin the item is given anyway.
            plugin.avatarService().fetch(target).thenAccept(avatar -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (!target.isOnline()) return;
                if (avatar == null) {
                    sender.sendMessage(messages.get(sender, "item-avatar-no-skin").replace("{player}", target.getName()));
                }
                give(sender, target, id);
            }));
            return;
        }
        give(sender, target, id);
    }

    private void give(CommandSender sender, Player target, String id) {
        ItemStack stack = plugin.itemCatalog().build(id, target);
        if (stack == null) return;
        target.getInventory().addItem(stack);
        sender.sendMessage(messages.get(sender, "item-given")
                .replace("{item}", id).replace("{player}", target.getName()));
    }

    private void itemList(CommandSender sender) {
        List<String> ids = plugin.itemCatalog().ids();
        if (ids.isEmpty()) {
            sender.sendMessage(messages.get(sender, "item-list-empty"));
            return;
        }
        sender.sendMessage(messages.get(sender, "item-list-header").replace("{count}", String.valueOf(ids.size())));
        for (String id : ids) sender.sendMessage(messages.get(sender, "item-list-row").replace("{item}", id));
    }

    // -------------------------------------------------------------------------------------- glyph

    private void glyph(CommandSender sender, String[] args) {
        if (!sender.hasPermission("magixpack.glyph.list")) {
            sender.sendMessage(messages.get(sender, "no-permission"));
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "list" -> glyphList(sender);
            case "show" -> glyphShow(sender, args);
            default -> sender.sendMessage(messages.get(sender, "glyph-usage"));
        }
    }

    private void glyphList(CommandSender sender) {
        List<String> ids = plugin.glyphCatalog().ids();
        if (ids.isEmpty()) {
            sender.sendMessage(messages.get(sender, "glyph-list-empty"));
            return;
        }
        sender.sendMessage(messages.get(sender, "glyph-list-header").replace("{count}", String.valueOf(ids.size())));
        for (String id : ids) {
            GlyphEntry e = plugin.glyphCatalog().entry(id);
            if (e.playerAvatar()) {
                sender.sendMessage(messages.get(sender, "glyph-list-row-avatar").replace("{glyph}", id));
                continue;
            }
            sender.sendMessage(messages.get(sender, "glyph-list-row")
                    .replace("{glyph}", id)
                    .replace("{codepoint}", String.format("U+%X", e.codepoint())));
        }
    }

    /** Shows a glyph in the sender's chat; for a player-avatar entry, the avatar of a player (the
     *  sender by default), after waiting for the skin download. */
    private void glyphShow(CommandSender sender, String[] args) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(messages.get(sender, "glyph-player-only"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(messages.get(sender, "glyph-usage"));
            return;
        }
        String id = args[2];
        GlyphEntry e = plugin.glyphCatalog().entry(id);
        if (e == null) {
            sender.sendMessage(messages.get(sender, "glyph-unknown").replace("{glyph}", id));
            return;
        }
        Component caption = LegacyComponentSerializer.legacySection()
                .deserialize(messages.get(viewer, "glyph-show-caption").replace("{glyph}", id));
        if (!e.playerAvatar()) {
            for (int i = 0; i < e.emptyLinesAbove(9); i++) viewer.sendMessage(Component.empty());
            viewer.sendMessage(plugin.glyphCatalog().component(id).append(caption));
            return;
        }
        String name = args.length >= 4 ? args[3] : viewer.getName();
        CompletableFuture<int[][]> future = plugin.avatarService().fetch(name);
        if (!future.isDone()) viewer.sendMessage(messages.get(viewer, "glyph-avatar-loading").replace("{player}", name));
        future.thenAccept(face -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline()) return;
            if (face == null) {
                viewer.sendMessage(messages.get(viewer, "glyph-avatar-no-skin").replace("{player}", name));
                return;
            }
            Component avatar = plugin.avatarService().render(e, face);
            // A glyph taller than a letter (scale, offset-y) sticks out above its line: leave room.
            for (int i = 0; i < e.emptyLinesAbove(9); i++) viewer.sendMessage(Component.empty());
            viewer.sendMessage(avatar.append(caption));
        }));
    }

    // ------------------------------------------------------------------------------ completamento

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("reload", "item", "glyph")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("item")) {
            for (String s : List.of("give", "list")) {
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("glyph")) {
            for (String s : List.of("list", "show")) {
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("glyph") && args[1].equalsIgnoreCase("show")) {
            for (String id : plugin.glyphCatalog().ids()) {
                if (id.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(id);
            }
            return out;
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("glyph") && args[1].equalsIgnoreCase("show")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[3].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("item") && args[1].equalsIgnoreCase("give")) {
            for (String id : plugin.itemCatalog().ids()) {
                if (id.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(id);
            }
            return out;
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("item") && args[1].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[3].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
            return out;
        }
        return out;
    }
}
