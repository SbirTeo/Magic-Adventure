package com.teolo.magixpack.command;

import com.teolo.magixpack.MagixPack;
import com.teolo.magixpack.avatar.AvatarGlyphRegistry;
import com.teolo.magixpack.glyph.GlyphEntry;
import com.teolo.magixpack.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
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
            case "model" -> model(sender, args);
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
        sender.sendMessage(messages.get(sender, "glyph-list-heading").replace("{count}", String.valueOf(ids.size())));
        for (String id : ids) {
            GlyphEntry e = plugin.glyphCatalog().entry(id);
            String placeholder = "%magixpack_glyph_" + id + "%";
            // What can be pasted: the character of an icon (it works in any text), the placeholder
            // of an avatar (it is a different drawing for every player). Two buttons, because the
            // clipboard is up to the client: the chat bar one always shows what it took.
            String line;
            String value;
            if (e.playerAvatar()) {
                line = messages.get(sender, "glyph-list-entry-avatar");
                value = placeholder;
            } else {
                value = plugin.glyphCatalog().text(e);
                line = messages.get(sender, "glyph-list-entry").replace("{char}", value)
                        .replace("{codepoint}", String.format("U+%04X", e.codepoint()));
            }
            LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
            line = line.replace("{glyph}", id).replace("{placeholder}", placeholder);
            Component copy = legacy.deserialize(messages.get(sender, "glyph-list-button-copy"))
                    .clickEvent(ClickEvent.copyToClipboard(value))
                    .hoverEvent(HoverEvent.showText(legacy.deserialize(
                            messages.get(sender, e.playerAvatar() ? "glyph-list-hover-copy-avatar" : "glyph-list-hover-copy")
                                    .replace("{value}", value))));
            // Un placeholder player-avatar non lo risolve mai la chat digitata (il testo del
            // giocatore non passa da PlaceholderAPI): il secondo pulsante qui NON suggerisce il
            // placeholder da incollare (resterebbe testo letterale), ma il comando di anteprima
            // vero, l'unico modo in cui un giocatore lo vede davvero in chat.
            Component second = e.playerAvatar()
                    ? previewButton(sender, legacy, id)
                    : legacy.deserialize(messages.get(sender, "glyph-list-button-chat"))
                            .clickEvent(ClickEvent.suggestCommand(value))
                            .hoverEvent(HoverEvent.showText(legacy.deserialize(
                                    messages.get(sender, "glyph-list-hover-chat").replace("{value}", value))));
            sender.sendMessage(legacy.deserialize(line).append(copy).append(second));
        }
    }

    /** Il pulsante di anteprima per una voce player-avatar: suggerisce {@code /mpack glyph show
     *  <id>} (che risolve l'avatar lato server e lo manda in chat gia' pronto), non il placeholder
     *  crudo — quello, digitato o incollato in chat, non verrebbe mai risolto. */
    private Component previewButton(CommandSender sender, LegacyComponentSerializer legacy, String id) {
        String command = "/mpack glyph show " + id;
        return legacy.deserialize(messages.get(sender, "glyph-list-button-preview"))
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(legacy.deserialize(
                        messages.get(sender, "glyph-list-hover-preview").replace("{command}", command))));
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
        if (id.contains(",")) {
            glyphShowStack(viewer, id, args.length >= 4 ? args[3] : viewer.getName());
            return;
        }
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
            // Un codice colore & sect; incollato/digitato in un vero messaggio di chat resta testo
            // letterale (chat firmata dal 1.19: verificato in gioco, non e' una supposizione) - MAI
            // il colore. Serve un carattere VERO, senza nessun codice di formattazione: come
            // Oraxen per le teste custom, la faccia intera diventa una texture assegnata al volo a
            // UN punto di codice (AvatarGlyphRegistry), non piu' 8 caratteri riga + colore.
            AvatarGlyphRegistry chars = plugin.avatarChars();
            boolean isNewFace = chars.isNew(face);
            char glyph = chars.glyphFor(face);
            if (glyph == 0) {
                viewer.sendMessage(messages.get(viewer, "glyph-avatar-chars-full"));
                viewer.sendMessage(avatar.append(caption));
                return;
            }
            if (isNewFace) plugin.resendDynamicPackContent();
            Component copy = LegacyComponentSerializer.legacySection()
                    .deserialize(messages.get(viewer, "glyph-show-copy-button"))
                    .clickEvent(ClickEvent.copyToClipboard(String.valueOf(glyph)))
                    .hoverEvent(HoverEvent.showText(LegacyComponentSerializer.legacySection()
                            .deserialize(messages.get(viewer, "glyph-show-copy-hover"))));
            viewer.sendMessage(avatar.append(caption).append(copy));
        }));
    }

    /** {@code /mpack glyph show a,b [giocatore]}: the glyphs stacked in one spot, the one with the
     *  highest priority on top (see GlyphCatalog#stackOrder). */
    private void glyphShowStack(Player viewer, String ids, String name) {
        List<String> list = List.of(ids.split(","));
        List<GlyphEntry> order = plugin.glyphCatalog().stackOrder(list);
        if (order == null || order.isEmpty()) {
            viewer.sendMessage(messages.get(viewer, "glyph-unknown").replace("{glyph}", ids));
            return;
        }
        int lines = 0;
        for (GlyphEntry e : order) lines = Math.max(lines, e.emptyLinesAbove(9));
        final int emptyLines = lines;
        Component caption = LegacyComponentSerializer.legacySection()
                .deserialize(messages.get(viewer, "glyph-show-caption").replace("{glyph}", ids));
        if (!com.teolo.magixpack.glyph.GlyphCatalog.needsFace(order)) {
            for (int i = 0; i < emptyLines; i++) viewer.sendMessage(Component.empty());
            viewer.sendMessage(plugin.glyphCatalog().stackComponent(order, null).append(caption));
            return;
        }
        CompletableFuture<int[][]> future = plugin.avatarService().fetch(name);
        if (!future.isDone()) viewer.sendMessage(messages.get(viewer, "glyph-avatar-loading").replace("{player}", name));
        future.thenAccept(face -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline()) return;
            if (face == null) {
                viewer.sendMessage(messages.get(viewer, "glyph-avatar-no-skin").replace("{player}", name));
                return;
            }
            for (int i = 0; i < emptyLines; i++) viewer.sendMessage(Component.empty());
            viewer.sendMessage(plugin.glyphCatalog().stackComponent(order, face).append(caption));
        }));
    }

    // -------------------------------------------------------------------------------------- model

    /** /mpack model list | spawn <id> [scale] [animation|none] | remove [radius] | rotate <degrees> | scale <size> | glow on|off. */
    private void model(CommandSender sender, String[] args) {
        if (!sender.hasPermission("magixpack.model")) {
            sender.sendMessage(messages.get(sender, "no-permission"));
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("list")) {
            modelList(sender);
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "model-player-only"));
            return;
        }
        switch (sub) {
            case "spawn" -> modelSpawn(player, args);
            case "remove" -> {
                double radius = args.length >= 3 ? parse(args[2], 10) : 10;
                String id = plugin.modelDisplays().removeNearest(player.getLocation(), radius);
                player.sendMessage(id == null
                        ? messages.get(player, "model-none-near").replace("{radius}", fmt(radius))
                        : messages.get(player, "model-removed").replace("{model}", id));
            }
            case "rotate" -> {
                if (args.length < 3) {
                    player.sendMessage(messages.get(player, "model-usage"));
                    return;
                }
                float degrees = (float) parse(args[2], 0);
                String id = plugin.modelDisplays().rotateNearest(player.getLocation(), 16, degrees);
                player.sendMessage(id == null
                        ? messages.get(player, "model-none-near").replace("{radius}", "16")
                        : messages.get(player, "model-rotated").replace("{model}", id).replace("{degrees}", fmt(degrees)));
            }
            case "glow" -> {
                if (args.length < 3 || !(args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("off"))) {
                    player.sendMessage(messages.get(player, "model-usage"));
                    return;
                }
                boolean glow = args[2].equalsIgnoreCase("on");
                String id = plugin.modelDisplays().glowNearest(player.getLocation(), 24, glow);
                player.sendMessage(id == null
                        ? messages.get(player, "model-none-near").replace("{radius}", "24")
                        : messages.get(player, glow ? "model-glow-on" : "model-glow-off").replace("{model}", id));
            }
            case "scale" -> {
                if (args.length < 3) {
                    player.sendMessage(messages.get(player, "model-usage"));
                    return;
                }
                double scale = Math.max(0.05, Math.min(16, parse(args[2], 1)));
                String id = plugin.modelDisplays().scaleNearest(player.getLocation(), 16, scale);
                player.sendMessage(id == null
                        ? messages.get(player, "model-none-near").replace("{radius}", "16")
                        : messages.get(player, "model-scaled").replace("{model}", id).replace("{scale}", fmt(scale)));
            }
            default -> player.sendMessage(messages.get(player, "model-usage"));
        }
    }

    private void modelSpawn(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(messages.get(player, "model-usage"));
            return;
        }
        String id = args[2].toLowerCase(Locale.ROOT);
        com.teolo.magixpack.model.BbModel m = plugin.modelCatalog().get(id);
        if (m == null) {
            player.sendMessage(messages.get(player, "model-unknown").replace("{model}", id));
            return;
        }
        double scale = args.length >= 4 ? Math.max(0.05, Math.min(16, parse(args[3], 1))) : 1;
        String animation;
        if (args.length >= 5) {
            animation = args[4].equalsIgnoreCase("none") ? null : args[4];
            if (animation != null && !m.animations.containsKey(animation)) {
                player.sendMessage(messages.get(player, "model-animation-unknown").replace("{animation}", animation)
                        .replace("{animations}", m.animations.isEmpty() ? "-" : String.join(", ", m.animations.keySet())));
                return;
            }
        } else {
            // the looping "idle" if there is one, else the first looping animation, else none
            animation = m.animations.containsKey("idle") ? "idle" : null;
            if (animation == null) {
                for (com.teolo.magixpack.model.BbModel.Animation a : m.animations.values()) {
                    if (a.loop()) {
                        animation = a.name();
                        break;
                    }
                }
            }
        }
        // in front of the player's feet, facing the player
        org.bukkit.Location at = player.getLocation();
        at.setYaw(at.getYaw() + 180);
        int pieces = plugin.modelDisplays().spawn(id, at, scale, animation);
        player.sendMessage(messages.get(player, "model-spawned").replace("{model}", id)
                .replace("{pieces}", String.valueOf(pieces)).replace("{animation}", animation == null ? "-" : animation));
    }

    private void modelList(CommandSender sender) {
        List<String> ids = plugin.modelCatalog().ids();
        if (ids.isEmpty()) {
            sender.sendMessage(messages.get(sender, "model-list-empty"));
            return;
        }
        java.util.Map<String, Integer> placed = plugin.modelDisplays().placedCounts();
        sender.sendMessage(messages.get(sender, "model-list-header").replace("{count}", String.valueOf(ids.size())));
        for (String id : ids) {
            com.teolo.magixpack.model.BbModel m = plugin.modelCatalog().get(id);
            sender.sendMessage(messages.get(sender, "model-list-row").replace("{model}", id)
                    .replace("{pieces}", String.valueOf(m.pieces.size()))
                    .replace("{animations}", m.animations.isEmpty() ? "-" : String.join(", ", m.animations.keySet()))
                    .replace("{placed}", String.valueOf(placed.getOrDefault(id, 0))));
        }
    }

    private static double parse(String s, double def) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // ------------------------------------------------------------------------------ completamento

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("reload", "item", "glyph", "model")) {
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
        if (args.length == 2 && args[0].equalsIgnoreCase("model")) {
            for (String s : List.of("list", "spawn", "remove", "rotate", "scale", "glow")) {
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("model") && args[1].equalsIgnoreCase("spawn")) {
            for (String id : plugin.modelCatalog().ids()) {
                if (id.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(id);
            }
            return out;
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("model") && args[1].equalsIgnoreCase("spawn")) {
            com.teolo.magixpack.model.BbModel m = plugin.modelCatalog().get(args[2].toLowerCase(Locale.ROOT));
            if (m != null) {
                for (String a : m.animations.keySet()) if (a.startsWith(args[4])) out.add(a);
            }
            if ("none".startsWith(args[4].toLowerCase(Locale.ROOT))) out.add("none");
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("model") && args[1].equalsIgnoreCase("glow")) {
            for (String s : List.of("on", "off")) {
                if (s.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(s);
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
