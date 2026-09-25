package com.teolo.magixentities.command;

import com.teolo.magixentities.util.ConfigAlign;
import com.teolo.magixentities.lang.Messages;
import com.teolo.magixentities.manage.EditorMenu;
import com.teolo.magixentities.manage.EquipMenu;
import com.teolo.magixentities.manage.MirrorManager;
import com.teolo.magixentities.manage.NpcManager;
import com.teolo.magixentities.model.NpcDef;
import com.teolo.magixentities.util.Help;
import com.teolo.magixentities.util.Colors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Tutti i comandi /magixentities (alias /mentities, /ment, /me). */
public final class MeCommand implements TabExecutor {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final List<String> SUBS = List.of(
            "create", "remove", "list", "info", "editor", "tp", "here", "position", "name", "displayname",
            "type", "skin", "pose", "scale", "set", "equip", "cmd", "respawn", "reload", "help");
    private static final int PAGE_SIZE = 8;
    private static final List<String> AXES = List.of("x", "y", "z");
    /** Spostamento massimo per /mentities position, in blocchi. */
    private static final double MAX_NUDGE = 16;

    private final JavaPlugin plugin;
    private final NpcManager npcs;
    private final MirrorManager mirror;
    private final EquipMenu equipMenu;
    private final EditorMenu editorMenu;
    private final Messages M;

    public MeCommand(JavaPlugin plugin, NpcManager npcs, MirrorManager mirror,
                     EquipMenu equipMenu, EditorMenu editorMenu, Messages messages) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.mirror = mirror;
        this.equipMenu = equipMenu;
        this.editorMenu = editorMenu;
        this.M = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("magixentities.use")) {
            M.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help") || args[0].equals("?")) {
            help(sender, args.length >= 2 ? page(args[1]) : 1);
            return true;
        }
        // Il numero da solo sfoglia l'aiuto: e' quello che mandano le frecce in fondo all'elenco.
        if (args[0].chars().allMatch(Character::isDigit)) {
            help(sender, page(args[0]));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> create(sender, args);
            case "remove", "delete" -> remove(sender, args);
            case "list" -> list(sender, args);
            case "info" -> info(sender, args);
            case "tp", "teleport" -> tp(sender, args);
            case "here", "move" -> here(sender, args);
            case "position", "pos" -> position(sender, args);
            case "editor", "gui" -> editor(sender, args);
            case "name", "rename" -> rename(sender, args);
            case "displayname", "display" -> displayname(sender, args);
            case "type", "tipo" -> type(sender, args);
            case "skin" -> skin(sender, args);
            case "pose" -> pose(sender, args);
            case "scale" -> scale(sender, args);
            case "set", "toggle" -> set(sender, args);
            case "equip", "equipaggia" -> equip(sender, args);
            case "cmd", "command", "comandi" -> commands(sender, args);
            case "respawn" -> respawn(sender, args);
            case "reload" -> reload(sender);
            default -> M.send(sender, "unknown-sub", "sub", sub);
        }
        return true;
    }

    // ------------------------------------------------------------- comandi

    /**
     * /mentities help [pagina] - l'elenco dei comandi.
     *
     * Le voci stanno in messages.yml (help.sections) e le impagina {@link Help}, la stessa classe
     * degli altri plugin Magix: sezioni, frecce per sfogliare, ogni riga cliccabile per scriversi
     * il comando in chat. La sezione Staff la vede solo chi ha magixentities.admin.
     */
    private void help(CommandSender sender, int page) {
        ConfigurationSection h = M.section("help");
        String title = h != null ? h.getString("title", "MagixEntities") : "MagixEntities";
        Help.show(sender, M::forPlayer, title, "/mentities help",
                Help.fromConfig(M.section("help.sections"), sender, M::forPlayer, M::listForPlayer),
                page, sender.hasPermission("magixentities.admin"));
    }

    /** Il numero di pagina scritto dall'utente; qualsiasi cosa strana vale 1. */
    private static int page(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 1; }
    }

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            M.send(sender, "players-only");
            return;
        }
        if (args.length < 3) {
            M.send(sender, "usage-create");
            return;
        }
        String name = args[1];
        if (!VALID_NAME.matcher(name).matches()) {
            M.send(sender, "name-invalid");
            return;
        }
        if (npcs.get(name) != null) {
            M.send(sender, "already-exists", "name", name);
            return;
        }
        EntityType type = parseType(args[2]);
        if (type == null) {
            M.send(sender, "type-invalid", "type", args[2]);
            return;
        }
        // Senza displayname esplicito resta null: la scritta sopra la testa segue il nome
        // dell'entita' anche dopo un /mentities name.
        String display = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : null;

        NpcDef d = npcs.create(name, type, display, p.getLocation());
        M.send(sender, "created", "name", d.name, "type", typeLabel(d), "display", Colors.translate(d.displayText()));
        if (d.isPlayerType()) M.send(sender, "created-player-hint", "skin", d.skinNick(), "name", d.name);
        warnIfRefused(sender, d);
    }

    private void remove(CommandSender sender, String[] args) {
        NpcDef d = require(sender, args, "usage-remove");
        if (d == null) return;
        npcs.delete(d);
        M.send(sender, "removed", "name", d.name);
    }

    private void list(CommandSender sender, String[] args) {
        List<NpcDef> all = new ArrayList<>(npcs.all());
        if (all.isEmpty()) {
            M.send(sender, "list-empty");
            return;
        }
        int pages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = 1;
        if (args.length > 1) {
            try { page = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
        }
        page = Math.max(1, Math.min(pages, page));

        sender.sendMessage(M.get("list-header", "count", String.valueOf(all.size()),
                "page", String.valueOf(page), "pages", String.valueOf(pages)));
        for (int i = (page - 1) * PAGE_SIZE; i < Math.min(all.size(), page * PAGE_SIZE); i++) {
            NpcDef d = all.get(i);
            String[] data = {
                    "name", d.name,
                    "type", typeLabel(d),
                    "display", Colors.translate(d.displayText()),
                    "world", d.world,
                    "x", String.valueOf(Math.round(d.x)),
                    "y", String.valueOf(Math.round(d.y)),
                    "z", String.valueOf(Math.round(d.z)),
                    "status", status(d)};
            sender.sendMessage(M.component("list-entry", data)
                    .clickEvent(ClickEvent.runCommand("/mentities info " + d.name))
                    .hoverEvent(HoverEvent.showText(M.component("list-entry-hover", data))));
        }
        sender.sendMessage(M.get("status-legend"));
        if (pages > 1) sender.sendMessage(navBar(page, pages));
    }

    /** Frecce di pagina cliccabili, mostrate solo se c'e' piu' di una pagina. */
    private Component navBar(int page, int pages) {
        Component bar = Component.text("   ");
        if (page > 1) {
            bar = bar.append(M.component("list-nav-prev")
                    .clickEvent(ClickEvent.runCommand("/mentities list " + (page - 1))));
        }
        if (page > 1 && page < pages) bar = bar.append(Colors.component("  &8·  "));
        if (page < pages) {
            bar = bar.append(M.component("list-nav-next")
                    .clickEvent(ClickEvent.runCommand("/mentities list " + (page + 1))));
        }
        return bar;
    }

    private void info(CommandSender sender, String[] args) {
        NpcDef d = require(sender, args, "usage-info");
        if (d == null) return;
        String clones = String.valueOf(mirror.cloneCount(d));
        sender.sendMessage(M.get("info-header", "name", d.name, "type", typeLabel(d)));
        if (d.isDisplayMirror()) {
            line(sender, "displayname", M.get("info-mirror-display", "clones", clones));
        } else {
            line(sender, "displayname", Colors.translate(d.displayText())
                    + (d.hasCustomDisplay() ? "" : " " + M.get("info-follows-name")));
        }
        if (d.isPlayerType()) {
            line(sender, "skin", d.isSkinMirror() ? M.get("info-mirror-skin", "clones", clones) : d.skinNick());
            line(sender, "posa", d.pose == null ? "standing" : d.pose.toLowerCase(Locale.ROOT));
        }
        if (d.scale != 1.0) line(sender, "scala", trim(d.scale));
        line(sender, "posizione", d.world + " &8· &f" + Math.round(d.x) + " " + Math.round(d.y) + " " + Math.round(d.z));
        line(sender, "stato", status(d));
        StringBuilder opts = new StringBuilder();
        for (String o : NpcDef.OPTIONS) {
            if (o.equals("immovable") && !d.isPlayerType()) continue;
            opts.append(d.opt(o, false) ? "&#5BE37D" : "&8").append(o).append("&8 ");
        }
        line(sender, "opzioni", Colors.translate(opts.toString()));
        if (!d.commands.isEmpty()) {
            line(sender, "al clic", M.get("info-commands", "count", String.valueOf(d.commands.size()),
                    "name", d.name));
        }
        line(sender, "uuid", "&8" + (d.uuid == null ? "-" : d.uuid.toString()));
        warnIfRefused(sender, d);
        sender.sendMessage(infoActions(d));
    }

    /** Riga di pulsanti sotto /mentities info: vai, porta qui, rimuovi. */
    private Component infoActions(NpcDef d) {
        Component tp = M.component("info-actions-tp")
                .clickEvent(ClickEvent.runCommand("/mentities tp " + d.name));
        Component here = M.component("info-actions-here")
                .clickEvent(ClickEvent.suggestCommand("/mentities here " + d.name));
        // Rimozione: solo suggerita, cosi' serve un invio consapevole.
        Component remove = M.component("info-actions-remove")
                .clickEvent(ClickEvent.suggestCommand("/mentities remove " + d.name));
        Component editor = M.component("info-actions-editor")
                .clickEvent(ClickEvent.runCommand("/mentities editor " + d.name));
        Component sep = Colors.component(" &8· ");
        return Colors.component(" &8└ ").append(editor).append(sep).append(tp).append(sep)
                .append(here).append(sep).append(remove);
    }

    private void line(CommandSender to, String key, String value) {
        to.sendMessage(M.get("info-line", "key", key, "value", value));
    }

    private void tp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            M.send(sender, "players-only");
            return;
        }
        NpcDef d = require(sender, args, "usage-tp");
        if (d == null) return;
        Location loc = d.location();
        if (loc == null) {
            M.send(sender, "world-missing", "world", d.world);
            return;
        }
        p.teleport(loc);
        M.send(sender, "teleported", "name", d.name);
    }

    private void here(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            M.send(sender, "players-only");
            return;
        }
        NpcDef d = require(sender, args, "usage-here");
        if (d == null) return;
        npcs.relocate(d, p.getLocation());
        M.send(sender, "moved", "name", d.name);
        warnIfRefused(sender, d);
    }

    /**
     * /mentities position &lt;nome&gt; &lt;x|y|z&gt; &lt;blocchi&gt; - sposta l'entita' di pochi blocchi
     * su un asse, tenendo la rotazione. E' anche quello che usa la sezione Posizione dell'editor.
     */
    private void position(CommandSender sender, String[] args) {
        if (args.length < 4) {
            M.send(sender, "usage-position");
            return;
        }
        NpcDef d = npcs.get(args[1]);
        if (d == null) {
            M.send(sender, "not-found", "name", args[1]);
            return;
        }
        String axis = args[2].toLowerCase(Locale.ROOT);
        double delta;
        try {
            delta = Double.parseDouble(args[3].replace(',', '.'));
        } catch (NumberFormatException ex) {
            delta = Double.NaN;
        }
        // Il tetto evita che un errore di battitura (100 invece di 1.0) spedisca l'entita' lontano:
        // per gli spostamenti grandi c'e' /mentities here.
        if (!AXES.contains(axis) || Double.isNaN(delta) || delta == 0 || Math.abs(delta) > MAX_NUDGE) {
            M.send(sender, "position-invalid", "max", trim(MAX_NUDGE));
            return;
        }
        Location loc = d.location();
        if (loc == null) {
            M.send(sender, "world-missing", "world", d.world);
            return;
        }
        switch (axis) {
            case "x" -> loc.add(delta, 0, 0);
            case "y" -> loc.add(0, delta, 0);
            default -> loc.add(0, 0, delta);
        }
        npcs.relocate(d, loc);
        M.send(sender, "position-set", "name", d.name, "axis", axis.toUpperCase(Locale.ROOT),
                "delta", (delta > 0 ? "+" : "") + trim(delta),
                "x", coord(d.x), "y", coord(d.y), "z", coord(d.z));
        warnIfRefused(sender, d);
    }

    /** Coordinata con due decimali: gli spostamenti fini si vedono, i numeri lunghi no. */
    public static String coord(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** /mentities editor &lt;nome&gt; - il pannello con tutti i comandi dell'entita'. */
    private void editor(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            M.send(sender, "players-only");
            return;
        }
        NpcDef d = require(sender, args, "usage-editor");
        if (d == null) return;
        editorMenu.open(p, d);
    }

    /** Rinomina l'entita' in se'. Per il tipo player la skin segue il nome (salvo skin esplicita). */
    private void rename(CommandSender sender, String[] args) {
        if (args.length < 3) {
            M.send(sender, "usage-name");
            return;
        }
        NpcDef d = npcs.get(args[1]);
        if (d == null) {
            M.send(sender, "not-found", "name", args[1]);
            return;
        }
        String newName = args[2];
        if (!VALID_NAME.matcher(newName).matches()) {
            M.send(sender, "name-invalid");
            return;
        }
        NpcDef other = npcs.get(newName);
        if (other != null && other != d) {
            M.send(sender, "already-exists", "name", newName);
            return;
        }
        String old = d.name;
        npcs.rename(d, newName);
        M.send(sender, "renamed", "old", old, "new", d.name);
        if (d.isPlayerType() && d.skin == null) {
            M.send(sender, "renamed-skin-hint", "skin", d.skinNick());
        }
        if (!d.hasCustomDisplay()) {
            M.send(sender, "renamed-display-hint", "display", Colors.translate(d.displayText()));
        }
    }

    /**
     * Cambia solo la scritta sopra la testa. "reset" la fa tornare a seguire il nome; "off"/"on"
     * la nascondono/rimostrano senza toccare il testo (stessa opzione di
     * {@code /mentities set <nome> nametag <on|off>}, solo piu' comoda da qui).
     */
    private void displayname(CommandSender sender, String[] args) {
        if (args.length < 3) {
            M.send(sender, "usage-displayname");
            return;
        }
        NpcDef d = npcs.get(args[1]);
        if (d == null) {
            M.send(sender, "not-found", "name", args[1]);
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (text.equalsIgnoreCase("off") || text.equalsIgnoreCase("on")) {
            boolean visible = text.equalsIgnoreCase("on");
            d.options.put("nametag", visible);
            applyLive(sender, d);
            npcs.save();
            M.send(sender, visible ? "display-on" : "display-off",
                    "name", d.name, "display", Colors.translate(d.displayText()));
            return;
        }
        boolean reset = text.equalsIgnoreCase("reset");
        boolean hadClones = d.needsClones();
        d.display = reset ? null : text;
        mirror.clear(d);
        // Entrare/uscire dalla modalita' specchio cambia la visibilita' dell'entita' vera:
        // nasconderla a caldo non la toglie a chi la sta gia' vedendo, quindi la ricreiamo.
        if (hadClones != d.needsClones() && d.chunkLoaded()) npcs.spawn(d);
        else applyLive(sender, d);
        npcs.save();
        if (d.isDisplayMirror()) M.send(sender, "display-mirror", "name", d.name);
        else if (reset) M.send(sender, "display-reset", "name", d.name, "display", Colors.translate(d.displayText()));
        else M.send(sender, "display-set", "name", d.name, "display", Colors.translate(d.displayText()));
    }

    /**
     * /mentities type &lt;nome&gt; &lt;tipo&gt; - cambia il tipo di un'entita' gia' creata.
     *
     * Prima bisognava cancellarla e rifarla da zero, perdendo displayname, opzioni, vestiti e
     * comandi al clic. Qui cambia solo il tipo: l'entita' viene rifatta sul posto con tutto
     * il resto identico.
     */
    private void type(CommandSender sender, String[] args) {
        if (args.length < 3) {
            M.send(sender, "usage-type");
            return;
        }
        NpcDef d = npcs.get(args[1]);
        if (d == null) {
            M.send(sender, "not-found", "name", args[1]);
            return;
        }
        EntityType nuovo = parseType(args[2]);
        if (nuovo == null) {
            M.send(sender, "type-invalid", "type", args[2]);
            return;
        }
        if (nuovo == d.type) {
            M.send(sender, "type-same", "name", d.name, "type", typeLabel(d));
            return;
        }
        if (d.location() == null) {
            M.send(sender, "world-missing", "world", d.world);
            return;
        }

        String vecchio = typeLabel(d);
        npcs.changeType(d, nuovo);
        M.send(sender, "type-set", "name", d.name, "old", vecchio, "type", typeLabel(d));
        if (d.isPlayerType()) {
            M.send(sender, "type-player-hint", "skin", d.skinNick(), "name", d.name);
        } else if (d.skin != null || d.pose != null) {
            // Skin e posa non si vedono su un mob, ma restano scritte: tornando a player si rivedono.
            M.send(sender, "type-kept-hint");
        }
        warnIfRefused(sender, d);
    }

    private void skin(CommandSender sender, String[] args) {
        if (args.length < 3) {
            M.send(sender, "usage-skin");
            return;
        }
        NpcDef d = npcs.get(args[1]);
        if (d == null) {
            M.send(sender, "not-found", "name", args[1]);
            return;
        }
        setSkin(sender, d, args[2]);
    }

    private void setSkin(CommandSender sender, NpcDef d, String value) {
        if (!d.isPlayerType()) {
            M.send(sender, "skin-not-player", "name", d.name);
            return;
        }
        boolean hadClones = d.needsClones();
        d.skin = value;
        // La skin nuova va richiesta subito, anche se quella vecchia era gia' stata bocciata.
        npcs.forgetSkins();
        // Le copie mirror vanno buttate: o non servono piu', o vanno rifatte sulla nuova entita'.
        mirror.clear(d);
        if (hadClones != d.needsClones() && d.chunkLoaded()) {
            // Entrando/uscendo da mirror cambia la visibilita' dell'entita' vera: nasconderla al
            // volo non la toglie a chi la sta gia' vedendo, quindi la ricreiamo da zero.
            npcs.spawn(d);
        } else {
            applyLive(sender, d);
        }
        npcs.save();
        if (d.isSkinMirror()) M.send(sender, "skin-mirror", "name", d.name);
        else M.send(sender, "skin-set", "name", d.name, "skin", d.skinNick());
    }

    private void pose(CommandSender sender, String[] args) {
        if (args.length < 3) {
            M.send(sender, "usage-pose");
            return;
        }
        NpcDef d = npcs.get(args[1]);
        if (d == null) {
            M.send(sender, "not-found", "name", args[1]);
            return;
        }
        if (!d.isPlayerType()) {
            M.send(sender, "pose-not-player");
            return;
        }
        String pose = args[2].toLowerCase(Locale.ROOT);
        if (!npcs.validPoses().contains(pose)) {
            M.send(sender, "pose-invalid", "poses", String.join(", ", npcs.validPoses()));
            return;
        }
        d.pose = pose;
        applyLive(sender, d);
        npcs.save();
        M.send(sender, "pose-set", "name", d.name, "pose", pose);
    }

    /**
     * /mentities scale &lt;nome&gt; &lt;valore|reset&gt; - ingrandisce/rimpicciolisce l'entita'
     * (1 = normale). Vale per qualunque tipo, non solo per il tipo player: usa l'attributo
     * vanilla "scale", che ridimensiona l'intera entita' skin compresa.
     */
    private void scale(CommandSender sender, String[] args) {
        double min = plugin.getConfig().getDouble("scale.min", 0.0625);
        double max = plugin.getConfig().getDouble("scale.max", 10.0);
        if (args.length < 3) {
            M.send(sender, "usage-scale", "min", trim(min), "max", trim(max));
            return;
        }
        NpcDef d = npcs.get(args[1]);
        if (d == null) {
            M.send(sender, "not-found", "name", args[1]);
            return;
        }
        String raw = args[2].toLowerCase(Locale.ROOT);
        double value;
        if (raw.equals("reset")) {
            value = 1.0;
        } else {
            try {
                value = Double.parseDouble(raw.replace(',', '.'));
            } catch (NumberFormatException ex) {
                M.send(sender, "scale-invalid", "min", trim(min), "max", trim(max));
                return;
            }
        }
        if (value < min || value > max) {
            M.send(sender, "scale-invalid", "min", trim(min), "max", trim(max));
            return;
        }
        d.scale = value;
        applyLive(sender, d);
        npcs.save();
        M.send(sender, "scale-set", "name", d.name, "scale", trim(value));
    }

    /** Un numero senza zeri decimali inutili (2.0 -> "2", 1.5 resta "1.5"). */
    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private void set(CommandSender sender, String[] args) {
        if (args.length < 4) {
            M.send(sender, "usage-set");
            return;
        }
        NpcDef d = npcs.get(args[1]);
        if (d == null) {
            M.send(sender, "not-found", "name", args[1]);
            return;
        }
        String option = args[2].toLowerCase(Locale.ROOT);
        if (!NpcDef.OPTIONS.contains(option)) {
            M.send(sender, "option-unknown", "options", String.join(", ", NpcDef.OPTIONS));
            return;
        }
        boolean value = args[3].equalsIgnoreCase("on") || args[3].equalsIgnoreCase("true")
                || args[3].equalsIgnoreCase("si") || args[3].equalsIgnoreCase("yes");
        d.options.put(option, value);
        applyLive(sender, d);
        npcs.save();
        M.send(sender, "option-set", "name", d.name, "option", option, "value", value ? "on" : "off");
    }

    /** Apre il menu di equipaggiamento dell'entita'. */
    private void equip(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            M.send(sender, "players-only");
            return;
        }
        NpcDef d = require(sender, args, "usage-equip");
        if (d == null) return;
        equipMenu.open(p, d);
    }

    /** /mentities cmd <nome> <add|list|remove|clear> - comandi eseguiti al clic sull'entita'. */
    private void commands(CommandSender sender, String[] args) {
        if (args.length < 3) {
            M.send(sender, "usage-cmd");
            return;
        }
        NpcDef d = npcs.get(args[1]);
        if (d == null) {
            M.send(sender, "not-found", "name", args[1]);
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        switch (action) {
            case "add" -> {
                if (args.length < 4) {
                    M.send(sender, "usage-cmd-add");
                    return;
                }
                d.commands.add(String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
                npcs.save();
                M.send(sender, "cmd-added", "name", d.name, "index", String.valueOf(d.commands.size()));
            }
            case "remove", "del" -> {
                int index = parseIndex(args.length > 3 ? args[3] : "");
                if (index < 1 || index > d.commands.size()) {
                    M.send(sender, "cmd-index-invalid", "max", String.valueOf(d.commands.size()));
                    return;
                }
                String removed = d.commands.remove(index - 1);
                npcs.save();
                M.send(sender, "cmd-removed", "name", d.name, "cmd", removed);
            }
            case "clear" -> {
                d.commands.clear();
                npcs.save();
                M.send(sender, "cmd-cleared", "name", d.name);
            }
            case "list" -> listCommands(sender, d);
            default -> M.send(sender, "usage-cmd");
        }
    }

    private void listCommands(CommandSender sender, NpcDef d) {
        if (d.commands.isEmpty()) {
            M.send(sender, "cmd-empty", "name", d.name);
            return;
        }
        sender.sendMessage(M.get("cmd-header", "name", d.name, "count", String.valueOf(d.commands.size())));
        for (int i = 0; i < d.commands.size(); i++) {
            sender.sendMessage(M.component("cmd-entry",
                            "index", String.valueOf(i + 1), "cmd", d.commands.get(i))
                    .clickEvent(ClickEvent.suggestCommand("/mentities cmd " + d.name + " remove " + (i + 1)))
                    .hoverEvent(HoverEvent.showText(M.component("cmd-entry-hover"))));
        }
    }

    private int parseIndex(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void respawn(CommandSender sender, String[] args) {
        NpcDef d = require(sender, args, "usage-respawn");
        if (d == null) return;
        if (d.location() == null) {
            M.send(sender, "world-missing", "world", d.world);
            return;
        }
        mirror.clear(d);
        npcs.spawn(d);
        npcs.save();
        M.send(sender, "respawned", "name", d.name);
        warnIfRefused(sender, d);
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("magixentities.admin")) {
            M.send(sender, "no-permission");
            return;
        }
        // Prima si allineano i file del server a quelli del jar (le chiavi nuove di un
        // deploy compaiono anche senza riavvio), poi si rilegge.
        ConfigAlign.alignAll(plugin);
        plugin.reloadConfig();
        M.reload();
        mirror.clearAll();
        npcs.forgetSkins();
        npcs.load();
        npcs.ensureAll();
        M.send(sender, "reloaded", "count", String.valueOf(npcs.all().size()));
    }

    // ------------------------------------------------------------- utility

    /** Legge args[1] come nome entita', segnalando uso errato o entita' inesistente. */
    private NpcDef require(CommandSender sender, String[] args, String usageKey) {
        if (args.length < 2) {
            M.send(sender, usageKey);
            return null;
        }
        NpcDef d = npcs.get(args[1]);
        if (d == null) M.send(sender, "not-found", "name", args[1]);
        return d;
    }

    /** Riapplica le proprieta' all'entita' viva; avvisa se il chunk e' scarico. */
    private void applyLive(CommandSender sender, NpcDef d) {
        // le copie mirror sono usa e getta: MirrorManager le rifa' entro un tick di controllo
        mirror.clear(d);
        Entity e = npcs.entityOf(d);
        if (e != null) {
            npcs.apply(d, e);
        } else if (!d.chunkLoaded()) {
            M.send(sender, "not-loaded", "name", d.name);
        } else {
            npcs.ensure(d);
            warnIfRefused(sender, d);
        }
    }

    /**
     * Avvisa in chat quando la nascita dell'entita' e' stata annullata da un altro plugin: la
     * definizione c'e' (ed e' salvata), ma nel mondo non e' entrato niente e senza questo
     * messaggio sembrerebbe tutto a posto.
     */
    private void warnIfRefused(CommandSender sender, NpcDef d) {
        if (npcs.refused(d)) M.send(sender, "spawn-refused", "name", d.name, "world", d.world);
    }

    private String status(NpcDef d) {
        if (npcs.entityOf(d) != null) return M.get("status-ok");
        // Nascita rifiutata da un altro plugin: non e' un'entita' "sparita", e' una che non
        // riesce a nascere — il pallino diverso evita di mandare lo staff a cercare il perche'.
        if (npcs.refused(d)) return M.get("status-refused");
        return d.chunkLoaded() ? M.get("status-missing") : M.get("status-unloaded");
    }

    private String typeLabel(NpcDef d) {
        return d.isPlayerType() ? "player" : d.type.name().toLowerCase(Locale.ROOT);
    }

    /** "player"/"mannequin" -> MANNEQUIN; altrimenti un mob vivo e spawnabile. */
    private EntityType parseType(String raw) {
        String t = raw.toLowerCase(Locale.ROOT);
        if (t.startsWith("minecraft:")) t = t.substring("minecraft:".length());
        if (t.equals("player") || t.equals("npc") || t.equals("mannequin")) return EntityType.MANNEQUIN;
        try {
            EntityType type = EntityType.valueOf(t.toUpperCase(Locale.ROOT));
            return (type.isAlive() && type.isSpawnable()) ? type : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<String> allTypes() {
        List<String> out = new ArrayList<>();
        out.add("player");
        for (EntityType t : EntityType.values()) {
            if (t.isAlive() && t.isSpawnable() && t != EntityType.MANNEQUIN) {
                out.add(t.name().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    // --------------------------------------------------------- tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("magixentities.use")) return List.of();
        if (args.length == 1) return filter(SUBS, args[0]);

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (sub.equals("create") || sub.equals("list") || sub.equals("reload") || sub.equals("help")) return List.of();
            return filter(names(), args[1]);
        }
        if (args.length == 3) {
            return switch (sub) {
                case "create", "type", "tipo" -> filter(allTypes(), args[2]);
                case "skin" -> filter(skinSuggestions(), args[2]);
                case "displayname", "display" -> filter(List.of(NpcDef.MIRROR, "reset", "off", "on"), args[2]);
                case "pose" -> filter(npcs.validPoses(), args[2]);
                case "scale" -> filter(List.of("0.5", "1", "2", "4", "8", "reset"), args[2]);
                case "position", "pos" -> filter(AXES, args[2]);
                case "set", "toggle" -> filter(NpcDef.OPTIONS, args[2]);
                case "cmd", "command", "comandi" -> filter(List.of("add", "list", "remove", "clear"), args[2]);
                default -> List.of();
            };
        }
        if (args.length == 4 && (sub.equals("set") || sub.equals("toggle"))) {
            return filter(List.of("on", "off"), args[3]);
        }
        if (args.length == 4 && (sub.equals("position") || sub.equals("pos"))) {
            return filter(List.of("0.1", "0.5", "1", "-0.1", "-0.5", "-1"), args[3]);
        }
        return List.of();
    }

    /** Per /mentities skin: la parola chiave mirror piu' i giocatori online. */
    private List<String> skinSuggestions() {
        List<String> out = new ArrayList<>();
        out.add(NpcDef.MIRROR);
        for (Player p : plugin.getServer().getOnlinePlayers()) out.add(p.getName());
        return out;
    }

    private List<String> names() {
        List<String> out = new ArrayList<>();
        for (NpcDef d : npcs.all()) out.add(d.name);
        return out;
    }

    private List<String> filter(List<String> source, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : source) {
            if (s.toLowerCase(Locale.ROOT).startsWith(p)) out.add(s);
        }
        return out;
    }
}
