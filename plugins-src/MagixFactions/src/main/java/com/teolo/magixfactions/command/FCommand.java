package com.teolo.magixfactions.command;

import com.teolo.magixfactions.chat.ChatChannel;
import com.teolo.magixfactions.chat.ChatService;
import com.teolo.magixfactions.config.Ranks;
import com.teolo.magixfactions.db.Database;
import com.teolo.magixfactions.db.Migrator;
import com.teolo.magixfactions.hook.Econ;
import com.teolo.magixfactions.hook.Papi;
import com.teolo.magixfactions.lang.Messages;
import com.teolo.magixfactions.manage.ClaimManager;
import com.teolo.magixfactions.manage.FactionManager;
import com.teolo.magixfactions.manage.PowerManager;
import com.teolo.magixfactions.model.Faction;
import com.teolo.magixfactions.model.Member;
import com.teolo.magixfactions.model.Rank;
import com.teolo.magixfactions.model.RelationType;
import com.teolo.magixfactions.req.Requirements;
import com.teolo.magixfactions.util.Help;
import com.teolo.magixfactions.util.Colors;
import com.teolo.magixfactions.util.WordFilter;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Gestisce tutti i comandi /f (e /mf). I testi sono in messages.yml. */
public final class FCommand implements org.bukkit.command.TabExecutor {

    private final JavaPlugin plugin;
    private final FactionManager fm;
    private final Ranks ranks;
    private final ChatService chat;
    private final Database db;
    private final Messages M;
    private final PowerManager power;
    private final ClaimManager claims;
    private final com.teolo.magixfactions.manage.ScoreManager score;
    private final com.teolo.magixfactions.map.MapService maps;
    private final com.teolo.magixfactions.minimap.MinimapManager minimap;
    private final com.teolo.magixfactions.resourcepack.ResourcePackService resourcePack;
    private final com.teolo.magixfactions.manage.FakeDataManager fake;

    private final Map<UUID, Long> invites = new HashMap<>();
    private final Map<UUID, Long> unclaimAllConfirm = new HashMap<>(); // giocatore -> timestamp richiesta /f unclaimall

    public FCommand(JavaPlugin plugin, FactionManager fm, Ranks ranks, ChatService chat, Database db,
                    Messages messages, PowerManager power, ClaimManager claims,
                    com.teolo.magixfactions.manage.ScoreManager score,
                    com.teolo.magixfactions.map.MapService maps, com.teolo.magixfactions.minimap.MinimapManager minimap,
                    com.teolo.magixfactions.resourcepack.ResourcePackService resourcePack,
                    com.teolo.magixfactions.manage.FakeDataManager fake) {
        this.plugin = plugin; this.fm = fm; this.ranks = ranks; this.chat = chat; this.db = db; this.M = messages;
        this.power = power; this.claims = claims; this.score = score; this.maps = maps; this.minimap = minimap; this.resourcePack = resourcePack;
        this.fake = fake;
    }

    /**
     * La risposta a un comando: il cartellino del plugin davanti, poi il messaggio.
     *
     * Il prefisso sta qui e non davanti a ogni riga di messages.yml perche' i pannelli
     * (l'aiuto, /f info, /f list, /f map) devono restarne fuori: una cornice ha senso attorno a
     * una risposta, non ripetuta dodici volte dentro una scheda. Quelli passano da {@link #panel}.
     */
    private void msg(CommandSender s, String m) {
        if (s instanceof Player p) m = Papi.resolve(p, m); // risolve i %placeholder% per il giocatore
        s.sendMessage(M.prefix() + m);
    }

    /** Una riga di pannello: niente prefisso, il pannello ha gia' la sua intestazione. */
    private void panel(CommandSender s, String m) {
        if (s instanceof Player p) m = Papi.resolve(p, m);
        s.sendMessage(m);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { help(sender, 1); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);

        // L'aiuto risponde anche alla console, quindi sta prima del controllo "solo giocatori".
        // Il numero da solo sfoglia le pagine: e' quello che mandano le frecce in fondo all'elenco.
        if (sub.equals("help") || sub.equals("?")) { help(sender, args.length >= 2 ? page(args[1]) : 1); return true; }
        if (!sub.isEmpty() && sub.chars().allMatch(Character::isDigit)) { help(sender, page(sub)); return true; }

        if (sub.equals("db")) return dbCommand(sender, args);
        if (sub.equals("admin")) return adminCommand(sender, args);
        if (sub.equals("list") || sub.equals("l")) return list(sender);
        if (sub.equals("top") || sub.equals("classifica")) return top(sender);
        if (sub.equals("reload")) {
            if (!sender.hasPermission("magixfactions.admin")) { msg(sender, M.get("errors.no-permission")); return true; }
            plugin.reloadConfig();
            ranks.load(plugin.getConfig());
            M.reload();
            // Guide e tutorial riportano i valori del config: se cambia il config devono cambiare
            // anche loro, subito, senza aspettare il prossimo riavvio.
            if (plugin instanceof com.teolo.magixfactions.MagixFactions mf) mf.riscriviGuide();
            msg(sender, M.get("reload"));
            return true;
        }

        if (!(sender instanceof Player p)) { msg(sender, M.get("errors.players-only")); return true; }

        try {
            switch (sub) {
                case "create": return create(p, args);
                case "rename": return rename(p, args);
                case "disband": return disband(p);
                case "leave": return leave(p);
                case "transfer": return transfer(p, args);
                case "promote": return promote(p, args);
                case "demote": return demote(p, args);
                case "invite": return invite(p, args);
                case "join": return join(p, args);
                case "claim": return claim(p);
                case "unclaim": return unclaim(p);
                case "unclaimall": return unclaimAll(p);
                case "owner": return owner(p, args);
                case "map": return map(p);
                case "minimap": return minimapCmd(p, args);
                case "borders": case "border": case "confini": return bordersCmd(p, args);
                case "sethome": return sethome(p);
                case "unsethome": return unsethome(p);
                case "home": return home(p);
                case "description": case "desc": return description(p, args);
                case "kick": return kick(p, args);
                case "chat": return chatCmd(p, args);
                case "ally": case "a": return relationCmd(p, args, RelationType.ALLY);
                case "enemy": case "e": return relationCmd(p, args, RelationType.ENEMY);
                case "deposit": case "d": return bankCmd(p, args, true);
                case "withdraw": case "w": return bankCmd(p, args, false);
                case "info": return info(p, args);
                case "power": case "pow": return powerCmd(p, args);
                default: help(p, 1); return true;
            }
        } catch (Exception e) {
            msg(p, M.get("errors.generic", "error", String.valueOf(e.getMessage())));
            plugin.getLogger().warning("Errore comando /f " + sub + ": " + e.getMessage());
            return true;
        }
    }

    private boolean create(Player p, String[] a) throws Exception {
        if (a.length < 2) { msg(p, M.get("create.usage")); return true; }
        if (fm.getFaction(p.getUniqueId()) != null) { msg(p, M.get("create.already-in")); return true; }
        if (inProtectedSpawn(p)) {
            msg(p, M.get("protected-spawn.no-create", "radius",
                    String.valueOf(plugin.getConfig().getInt("claims.protected-spawn.radius", 500))));
            return true;
        }
        String name = a[1];
        if (!nameOk(p, name, null)) return true;

        Requirements req = new Requirements(plugin.getConfig().getConfigurationSection("create-cost"));
        String unmet = req.checkUnmet(p);
        if (unmet != null) { msg(p, org.bukkit.ChatColor.translateAlternateColorCodes('&', unmet)); return true; }
        req.consume(p);

        Faction f = fm.createFaction(name, name, p.getUniqueId());
        msg(p, M.get("create.success", "name", cname(p, f)));
        return true;
    }

    /**
     * Valida un nome fazione con le regole del config (lunghezza, caratteri, cifre, parole vietate,
     * unicità). Condiviso da /f create e /f rename. Ritorna true se valido; altrimenti manda al
     * giocatore il messaggio d'errore giusto e ritorna false.
     *
     * @param self la fazione che sta rinominando (per /f rename): un nome uguale al suo, se cambia solo
     *             maiuscole/minuscole, non conta come "gia' preso". Per /f create passare null.
     */
    private boolean nameOk(Player p, String name, Faction self) {
        int min = plugin.getConfig().getInt("faction-name.min-length", 3);
        int max = plugin.getConfig().getInt("faction-name.max-length", 15);
        int maxDigits = plugin.getConfig().getInt("faction-name.max-digits", 2);
        if (name.length() < min || name.length() > max) {
            msg(p, M.get("create.name-length", "min", String.valueOf(min), "max", String.valueOf(max))); return false;
        }
        if (!name.matches("[A-Za-z0-9]+")) { msg(p, M.get("create.name-chars")); return false; }
        long digits = name.chars().filter(Character::isDigit).count();
        if (digits > maxDigits) { msg(p, M.get("create.name-digits", "max", String.valueOf(maxDigits))); return false; }
        if (WordFilter.isForbidden(plugin.getConfig().getStringList("forbidden-words"), name)) {
            msg(p, M.get("filter.blocked")); return false;
        }
        Faction taken = fm.getByName(name);
        if (taken != null && taken != self) { msg(p, M.get("create.name-taken")); return false; }
        return true;
    }

    /**
     * True se il giocatore si trova nell'AREA PROTETTA dello spawn: un quadrato centrale (config
     * {@code claims.protected-spawn}) in cui non si possono fondare fazioni ({@code /f create}) né
     * conquistare territori ({@code /f claim}). NON è una protezione dei blocchi: lì si costruisce e si
     * rompe liberamente come nel survival, semplicemente non si stabilisce/rivendica terreno.
     * Il quadrato è centrato in (center-x, center-z) col semilato {@code radius} (radius 500 = da -500
     * a +500). {@code enabled: false} o mondo diverso da quello configurato ⇒ nessuna restrizione.
     */
    private boolean inProtectedSpawn(Player p) {
        var sec = plugin.getConfig().getConfigurationSection("claims.protected-spawn");
        if (sec == null || !sec.getBoolean("enabled", false)) return false;
        if (!p.getWorld().getName().equals(sec.getString("world", "world"))) return false;
        int cx = sec.getInt("center-x", 0);
        int cz = sec.getInt("center-z", 0);
        int radius = sec.getInt("radius", 500);
        int x = p.getLocation().getBlockX(), z = p.getLocation().getBlockZ();
        return Math.abs(x - cx) <= radius && Math.abs(z - cz) <= radius;
    }

    /**
     * /f rename &lt;nuovonome&gt; — il LEADER cambia il nome della fazione. Stesse regole di /f create per il
     * nome; il costo usa lo stesso motore di create-cost ({@code rename.cost}) e c'e' un'attesa configurabile
     * fra un cambio e il successivo ({@code rename.cooldown-days}). Nome e tag restano uguali fra loro.
     * Il sito legge la colonna factions.name/tag, quindi il nuovo nome compare da solo lì.
     */
    private boolean rename(Player p, String[] a) throws Exception {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        if (!p.getUniqueId().equals(f.getLeader())) { msg(p, M.get("rename.not-leader")); return true; }
        if (a.length < 2) { msg(p, M.get("rename.usage")); return true; }
        String name = a[1];
        if (name.equals(f.getName())) { msg(p, M.get("rename.same-name")); return true; }
        if (!nameOk(p, name, f)) return true;

        // Attesa fra un cambio nome e il successivo (0 giorni = nessun limite).
        long cooldownDays = plugin.getConfig().getLong("rename.cooldown-days", 30);
        if (cooldownDays > 0 && f.getRenamedAt() > 0) {
            long readyAt = f.getRenamedAt() + cooldownDays * 24L * 3600_000L;
            long now = System.currentTimeMillis();
            if (now < readyAt) {
                msg(p, M.get("rename.cooldown", "time",
                        com.teolo.magixfactions.util.DurationText.fromMillis(readyAt - now)));
                return true;
            }
        }

        // Costo: stesso motore di /f create (a carico del leader), sezione rename.cost del config.
        Requirements req = new Requirements(plugin.getConfig().getConfigurationSection("rename.cost"));
        String unmet = req.checkUnmet(p);
        if (unmet != null) { msg(p, org.bukkit.ChatColor.translateAlternateColorCodes('&', unmet)); return true; }
        req.consume(p);

        String old = f.getName();
        fm.renameFaction(f, name);
        broadcast(f, M.get("rename.broadcast", "old", old, "name", name));
        msg(p, M.get("rename.success", "name", cname(p, f)));
        return true;
    }

    private boolean disband(Player p) throws Exception {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        if (!p.getUniqueId().equals(f.getLeader())) { msg(p, M.get("disband.not-leader")); return true; }
        broadcast(f, M.get("disband.broadcast"));
        fm.disband(f);
        msg(p, M.get("disband.success"));
        return true;
    }

    private boolean leave(Player p) throws Exception {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        FactionManager.LeaveResult r = fm.handleLeave(f, p.getUniqueId());
        switch (r) {
            case DISBANDED -> msg(p, M.get("leave.disbanded"));
            case SUCCESSION -> {
                msg(p, M.get("leave.succession"));
                Player nl = f.getLeader() != null ? Bukkit.getPlayer(f.getLeader()) : null;
                if (nl != null) msg(nl, M.get("leave.new-leader", "name", cname(nl, f)));
            }
            case LEFT -> msg(p, M.get("leave.left"));
        }
        return true;
    }

    private boolean transfer(Player p, String[] a) throws Exception {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        if (!p.getUniqueId().equals(f.getLeader())) { msg(p, M.get("transfer.not-leader")); return true; }
        if (a.length < 2) { msg(p, M.get("transfer.usage")); return true; }
        OfflinePlayer target = resolveMember(f, a[1]);
        if (target == null) { msg(p, M.get("transfer.not-member")); return true; }
        if (target.getUniqueId().equals(p.getUniqueId())) { msg(p, M.get("transfer.already-leader")); return true; }
        fm.setRank(f, p.getUniqueId(), ranks.highest().getId());
        fm.setLeader(f, target.getUniqueId());
        fm.setRank(f, target.getUniqueId(), Rank.LEADER_ID);
        msg(p, M.get("transfer.success", "player", target.getName()));
        Player tp = target.getPlayer();
        if (tp != null) msg(tp, M.get("transfer.received", "name", cname(tp, f)));
        return true;
    }

    private boolean promote(Player p, String[] a) throws Exception {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        if (!fm.hasPerm(f, p.getUniqueId(), "promote")) { msg(p, M.get("promote.no-perm")); return true; }
        if (a.length < 2) { msg(p, M.get("promote.usage")); return true; }
        OfflinePlayer target = resolveMember(f, a[1]);
        if (target == null) { msg(p, M.get("promote.not-member")); return true; }
        Member tm = f.getMember(target.getUniqueId());
        if (tm.isLeader()) { msg(p, M.get("promote.cant-leader")); return true; }

        boolean actorLeader = p.getUniqueId().equals(f.getLeader());
        int actorOrder = ranks.rankOrder(f.getMember(p.getUniqueId()).getRankId());
        int maxIndex = actorLeader ? ranks.size() - 1 : actorOrder - 1;
        int targetIndex = ranks.indexOf(tm.getRankId());
        if (!actorLeader && targetIndex >= actorOrder) { msg(p, M.get("promote.too-high")); return true; }
        int newIndex = Math.min(targetIndex + 1, maxIndex);
        if (newIndex <= targetIndex) { msg(p, M.get("promote.max")); return true; }
        fm.setRank(f, target.getUniqueId(), ranks.byIndex(newIndex).getId());
        msg(p, M.get("promote.success", "player", target.getName(), "rank", ranks.byIndex(newIndex).getName()));
        Player tp = target.getPlayer();
        if (tp != null) msg(tp, M.get("promote.received", "rank", ranks.byIndex(newIndex).getName()));
        return true;
    }

    private boolean demote(Player p, String[] a) throws Exception {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        if (!fm.hasPerm(f, p.getUniqueId(), "demote")) { msg(p, M.get("demote.no-perm")); return true; }
        if (a.length < 2) { msg(p, M.get("demote.usage")); return true; }
        OfflinePlayer target = resolveMember(f, a[1]);
        if (target == null) { msg(p, M.get("demote.not-member")); return true; }
        Member tm = f.getMember(target.getUniqueId());
        if (tm.isLeader()) { msg(p, M.get("demote.cant-leader")); return true; }
        int targetIndex = ranks.indexOf(tm.getRankId());
        if (targetIndex <= 0) { msg(p, M.get("demote.min")); return true; }
        fm.setRank(f, target.getUniqueId(), ranks.byIndex(targetIndex - 1).getId());
        msg(p, M.get("demote.success", "player", target.getName(), "rank", ranks.byIndex(targetIndex - 1).getName()));
        return true;
    }

    private boolean invite(Player p, String[] a) {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        if (!fm.hasPerm(f, p.getUniqueId(), "invite")) { msg(p, M.get("invite.no-perm")); return true; }
        if (a.length < 2) { msg(p, M.get("invite.usage")); return true; }
        Player target = Bukkit.getPlayerExact(a[1]);
        if (target == null) { msg(p, M.get("invite.not-online")); return true; }
        if (fm.getFaction(target.getUniqueId()) != null) { msg(p, M.get("invite.already-in")); return true; }
        if (f.size() >= fm.effectiveMaxMembers(f)) { msg(p, M.get("invite.full", "max", String.valueOf(fm.effectiveMaxMembers(f)))); return true; }
        invites.put(target.getUniqueId(), f.getId());
        msg(p, M.get("invite.sent", "player", target.getName()));
        msg(target, M.get("invite.received", "name", cname(target, f)));
        return true;
    }

    private boolean join(Player p, String[] a) throws Exception {
        if (fm.getFaction(p.getUniqueId()) != null) { msg(p, M.get("join.already-in")); return true; }
        if (a.length < 2) { msg(p, M.get("join.usage")); return true; }
        Faction f = fm.getByName(a[1]);
        if (f == null) { msg(p, M.get("join.not-found")); return true; }
        Long inv = invites.get(p.getUniqueId());
        if (inv == null || inv != f.getId()) { msg(p, M.get("join.not-invited")); return true; }
        if (f.size() >= fm.effectiveMaxMembers(f)) { msg(p, M.get("join.full")); return true; }
        fm.addMember(f, p.getUniqueId(), ranks.lowest().getId());
        invites.remove(p.getUniqueId());
        msg(p, M.get("join.success", "name", cname(p, f)));
        broadcast(f, M.get("join.broadcast", "player", p.getName()));
        return true;
    }

    private boolean kick(Player p, String[] a) throws Exception {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        if (!fm.hasPerm(f, p.getUniqueId(), "kick")) { msg(p, M.get("kick.no-perm")); return true; }
        if (a.length < 2) { msg(p, M.get("kick.usage")); return true; }
        OfflinePlayer target = resolveMember(f, a[1]);
        if (target == null) { msg(p, M.get("kick.not-member")); return true; }
        Member tm = f.getMember(target.getUniqueId());
        if (tm.isLeader()) { msg(p, M.get("kick.cant-leader")); return true; }
        boolean actorLeader = p.getUniqueId().equals(f.getLeader());
        if (!actorLeader && ranks.rankOrder(tm.getRankId()) >= ranks.rankOrder(f.getMember(p.getUniqueId()).getRankId())) {
            msg(p, M.get("kick.too-high")); return true;
        }
        fm.removeMember(f, target.getUniqueId());
        msg(p, M.get("kick.success", "player", target.getName()));
        Player tp = target.getPlayer();
        if (tp != null) msg(tp, M.get("kick.received", "name", cname(tp, f)));
        return true;
    }

    private boolean chatCmd(Player p, String[] a) {
        if (fm.getFaction(p.getUniqueId()) == null) { msg(p, M.get("errors.no-faction")); return true; }
        ChatChannel ch;
        if (a.length >= 2) {
            ch = ChatChannel.parse(a[1]);
            if (ch == null) { msg(p, M.get("chat.invalid")); return true; }
        } else {
            ch = chat.get(p.getUniqueId()).next();
        }
        chat.set(p.getUniqueId(), ch);
        String name = switch (ch) {
            case PUBLIC -> M.get("chat.channel-public");
            case FACTION -> M.get("chat.channel-faction");
            case ALLY -> M.get("chat.channel-ally");
        };
        msg(p, M.get("chat.set", "channel", name));
        return true;
    }

    /**
     * /f ally|enemy &lt;fazione&gt; - imposta la relazione verso un'altra fazione.
     * Esistono solo due stati: NEMICO (default per tutte) e ALLEATO.
     * - ALLY richiede il consenso di ENTRAMBE le fazioni (richiesta + accettazione).
     * - ENEMY riporta alla relazione di default: scioglie un'alleanza, oppure annulla/rifiuta
     *   una richiesta di alleanza in sospeso. E' sempre unilaterale e immediato.
     */
    private boolean relationCmd(Player p, String[] a, RelationType desired) throws Exception {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        if (!fm.hasPerm(f, p.getUniqueId(), "relation")) { msg(p, M.get("relation.no-perm")); return true; }
        if (a.length < 2) { msg(p, M.get("relation.usage")); return true; }
        Faction other = fm.getByName(a[1]);
        if (other == null) { msg(p, M.get("relation.not-found")); return true; }
        if (other.getId() == f.getId()) { msg(p, M.get("relation.self")); return true; }

        long fi = f.getId(), oi = other.getId();
        RelationType effective = fm.effectiveRelation(fi, oi);
        RelationType myWish = fm.getRelationWish(fi, oi);
        RelationType otherWish = fm.getRelationWish(oi, fi);

        if (desired == RelationType.ALLY) {
            // Alleanza: richiede il consenso reciproco.
            if (effective == RelationType.ALLY) { msg(p, M.get("relation.ally-already", "name", cname(p, other))); return true; }
            if (myWish == RelationType.ALLY) {
                // Richiesta gia' inviata: ri-eseguendo /f ally la si ANNULLA (toggle).
                fm.setRelationWish(fi, oi, RelationType.ENEMY);
                msg(p, M.get("relation.ally-cancelled", "name", cname(p, other)));
                return true;
            }
            int max = plugin.getConfig().getInt("relations.max-allies", 0);
            if (max > 0 && fm.alliesOf(f).size() >= max) { msg(p, M.get("relation.ally-max", "max", String.valueOf(max))); return true; }
            fm.setRelationWish(fi, oi, RelationType.ALLY);
            if (fm.effectiveRelation(fi, oi) == RelationType.ALLY) {
                broadcast(f, M.get("relation.ally-formed", "name", cname(f, other)));
                broadcast(other, M.get("relation.ally-formed", "name", cname(other, f)));
            } else {
                msg(p, M.get("relation.ally-requested", "name", cname(p, other)));
                broadcast(other, M.get("relation.ally-incoming", "name", cname(other, f)));
            }
            return true;
        }

        // desired == ENEMY: torna alla relazione di default.
        if (effective == RelationType.ALLY) {
            // Sciogliere un'alleanza: immediato, dissolve entrambi i lati.
            fm.setRelationWish(fi, oi, RelationType.ENEMY);
            fm.setRelationWish(oi, fi, RelationType.ENEMY);
            broadcast(f, M.get("relation.enemy-broke-self", "name", cname(f, other)));
            broadcast(other, M.get("relation.enemy-broke-other", "name", cname(other, f)));
            return true;
        }
        if (myWish == RelationType.ALLY) {
            // Avevo una richiesta di alleanza in sospeso: annullala.
            fm.setRelationWish(fi, oi, RelationType.ENEMY);
            msg(p, M.get("relation.ally-cancelled", "name", cname(p, other)));
            return true;
        }
        if (otherWish == RelationType.ALLY) {
            // L'altra fazione mi aveva chiesto alleanza: rifiuta.
            fm.setRelationWish(oi, fi, RelationType.ENEMY);
            msg(p, M.get("relation.ally-rejected-self", "name", cname(p, other)));
            broadcast(other, M.get("relation.ally-rejected-other", "name", cname(other, f)));
            return true;
        }
        // Gia' nemici di default.
        msg(p, M.get("relation.enemy-already", "name", cname(p, other)));
        return true;
    }

    /** /f description <testo> (alias /f desc) - imposta la descrizione della fazione (max configurabile). */
    private boolean description(Player p, String[] a) throws Exception {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        if (!fm.hasPerm(f, p.getUniqueId(), "description")) { msg(p, M.get("description.no-perm")); return true; }
        if (a.length < 2) { msg(p, M.get("description.usage")); return true; }
        String desc = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)).trim();
        int max = plugin.getConfig().getInt("faction-description.max-length", 100);
        if (desc.length() > max) { msg(p, M.get("description.too-long", "max", String.valueOf(max))); return true; }
        if (WordFilter.isForbidden(plugin.getConfig().getStringList("forbidden-words"), desc)) {
            msg(p, M.get("filter.blocked")); return true;
        }
        fm.setDescription(f, desc);
        msg(p, M.get("description.success"));
        return true;
    }

    /** /f sethome - imposta la home della fazione nella posizione attuale (dev'essere nel proprio territorio). */
    private boolean sethome(Player p) throws Exception {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        if (!fm.hasPerm(f, p.getUniqueId(), "sethome")) { msg(p, M.get("home.no-perm-set")); return true; }
        org.bukkit.Chunk ch = p.getLocation().getChunk();
        Long owner = claims.owner(ch.getWorld().getName(), ch.getX(), ch.getZ());
        if (owner == null || owner != f.getId()) { msg(p, M.get("home.not-own-land")); return true; }
        // Costo (stesso motore di /f create e /f claim; default gratis)
        Requirements req = new Requirements(plugin.getConfig().getConfigurationSection("sethome-cost"));
        String unmet = req.checkUnmet(p);
        if (unmet != null) { msg(p, org.bukkit.ChatColor.translateAlternateColorCodes('&', unmet)); return true; }
        req.consume(p);
        fm.setHome(f, p.getLocation());
        msg(p, M.get("home.set"));
        return true;
    }

    /** /f unsethome - rimuove la home della fazione (stesso permesso di /f sethome). */
    private boolean unsethome(Player p) {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        if (!fm.hasPerm(f, p.getUniqueId(), "sethome")) { msg(p, M.get("home.no-perm-set")); return true; }
        if (fm.getHome(f.getId()) == null) { msg(p, M.get("home.not-set")); return true; }
        fm.unsetHome(f);
        msg(p, M.get("home.unset"));
        return true;
    }

    /** /f home - teletrasporto alla home della fazione. */
    private boolean home(Player p) {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        if (!fm.hasPerm(f, p.getUniqueId(), "home")) { msg(p, M.get("home.no-perm")); return true; }
        FactionManager.Home h = fm.getHome(f.getId());
        if (h == null) { msg(p, M.get("home.not-set")); return true; }
        org.bukkit.Location loc = h.toLocation();
        if (loc == null) { msg(p, M.get("home.world-missing")); return true; }
        p.teleport(loc);
        msg(p, M.get("home.teleported"));
        return true;
    }

    /**
     * /f claim - conquista il chunk in cui si trova il giocatore.
     * Permesso interno di fazione "claim" (default: solo il leader).
     * Territorio neutrale: territori posseduti < tetto (20% del maxpower) e power fazione > territori.
     * Territorio nemico (overclaim): oltre a cio', il nemico dev'essere raidabile (power < suoi territori)
     * e il chunk dev'essere ESTERNO (non si claima dall'interno). Costo come /f create (default gratis).
     */
    private boolean claim(Player p) throws Exception {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        if (!fm.hasPerm(f, p.getUniqueId(), "claim")) { msg(p, M.get("claim.no-perm")); return true; }

        org.bukkit.Chunk ch = p.getLocation().getChunk();
        String world = ch.getWorld().getName();
        int cx = ch.getX(), cz = ch.getZ();

        // Territori consentiti solo in certi mondi (config claims.allowed-worlds). Lista vuota = ovunque.
        java.util.List<String> allowedWorlds = plugin.getConfig().getStringList("claims.allowed-worlds");
        if (!allowedWorlds.isEmpty() && !allowedWorlds.contains(world)) {
            msg(p, M.get("claim.wrong-world")); return true;
        }
        // Area protetta dello spawn: nel quadrato centrale non si conquistano territori (i blocchi
        // restano comunque NON protetti: si costruisce liberamente, semplicemente non si claima).
        if (inProtectedSpawn(p)) {
            msg(p, M.get("protected-spawn.no-claim", "radius",
                    String.valueOf(plugin.getConfig().getInt("claims.protected-spawn.radius", 500))));
            return true;
        }

        Long ownerId = claims.owner(world, cx, cz);
        if (ownerId != null && ownerId == f.getId()) { msg(p, M.get("claim.already-own")); return true; }

        int fPow = power.factionPower(f);
        int owned = claims.count(f.getId());
        int cap = claims.maxClaims(power.factionMaxPower(f));

        // condizioni valide sia per neutrale sia per nemico
        if (owned >= cap) { msg(p, M.get("claim.cap-reached", "cap", String.valueOf(cap))); return true; }
        if (fPow <= owned) {
            msg(p, M.get("claim.not-enough-power", "power", String.valueOf(fPow), "claims", String.valueOf(owned)));
            return true;
        }

        Faction enemy = (ownerId == null) ? null : fm.getById(ownerId);
        if (enemy != null) {
            if (fm.effectiveRelation(f.getId(), enemy.getId()) == RelationType.ALLY) {
                msg(p, M.get("claim.ally-land", "name", cname(p, enemy))); return true;
            }
            int ePow = power.factionPower(enemy);
            int eOwned = claims.count(enemy.getId());
            if (ePow >= eOwned) {
                msg(p, M.get("claim.enemy-not-raidable", "name", cname(p, enemy),
                        "power", String.valueOf(ePow), "claims", String.valueOf(eOwned)));
                return true;
            }
            if (!claims.isBorderOf(enemy.getId(), world, cx, cz)) {
                msg(p, M.get("claim.not-border", "name", cname(p, enemy))); return true;
            }
        }

        // Requisiti NON-money del giocatore (item/placeholder/permission): stessa logica di /f create.
        // Il MONEY e' escluso di proposito: per i claim non e' piu' un costo del giocatore ma un costo
        // INCREMENTALE pagato dalla BANCA DI FAZIONE (vedi sotto e nextClaimCost).
        org.bukkit.configuration.ConfigurationSection costSec =
                plugin.getConfig().getConfigurationSection("claims.cost");
        org.bukkit.configuration.MemoryConfiguration noMoney = new org.bukkit.configuration.MemoryConfiguration();
        if (costSec != null) for (String k : costSec.getKeys(false)) if (!k.equals("money")) noMoney.set(k, costSec.get(k));
        Requirements req = new Requirements(noMoney);
        String unmet = req.checkUnmet(p);
        if (unmet != null) { msg(p, org.bukkit.ChatColor.translateAlternateColorCodes('&', unmet)); return true; }

        // Costo money incrementale dalla banca: calcolato sul numero di territori GIA' posseduti.
        double cost = nextClaimCost(costSec == null ? null : String.valueOf(costSec.get("money", "0")), owned);
        if (cost > 0) {
            if (!Econ.enabled()) { msg(p, M.get("bank.no-economy")); return true; }
            if (f.getBank() < cost) {
                msg(p, M.get("claim.bank-insufficient", "cost", Econ.format(cost), "bank", Econ.format(f.getBank())));
                return true;
            }
        }

        req.consume(p);
        if (cost > 0) {
            fm.setBank(f, f.getBank() - cost);
            msg(p, M.get("claim.paid", "cost", Econ.format(cost), "bank", Econ.format(f.getBank())));
        }

        claims.setOwner(world, cx, cz, f.getId(), cost); // registra il prezzo pagato (base del rimborso di /f unclaim)
        if (enemy != null) {
            msg(p, M.get("claim.overclaim-success", "name", cname(p, enemy),
                    "x", String.valueOf(cx), "z", String.valueOf(cz)));
            broadcast(enemy, M.get("claim.overclaimed-victim", "name", cname(enemy, f),
                    "x", String.valueOf(cx), "z", String.valueOf(cz)));
            announceOverclaim(f, enemy, cx, cz); // allerta a schermo + suono a TUTTO il server
        } else {
            msg(p, M.get("claim.success", "x", String.valueOf(cx), "z", String.valueOf(cz),
                    "count", String.valueOf(claims.count(f.getId())), "cap", String.valueOf(cap)));
        }
        return true;
    }

    /**
     * /f unclaim - rilascia (rende neutrale) il chunk in cui si trova il giocatore, se e' della sua
     * fazione. Permesso interno di fazione "unclaim" (default: solo il leader, come "claim").
     */
    private boolean unclaim(Player p) {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        if (!fm.hasPerm(f, p.getUniqueId(), "unclaim")) { msg(p, M.get("unclaim.no-perm")); return true; }

        org.bukkit.Chunk ch = p.getLocation().getChunk();
        String world = ch.getWorld().getName();
        int cx = ch.getX(), cz = ch.getZ();

        Long ownerId = claims.owner(world, cx, cz);
        if (ownerId == null || ownerId != f.getId()) { msg(p, M.get("unclaim.not-your-land")); return true; }

        // Rimborso: una % (config claims.unclaim-refund-percent) di QUANTO FU PAGATO per QUESTO chunk
        // (salvato al claim: i prezzi incrementali cambiano nel tempo). Va alla BANCA della fazione.
        double refund = unclaimRefund(claims.paidAt(world, cx, cz));
        claims.removeClaim(world, cx, cz);
        msg(p, M.get("unclaim.success", "x", String.valueOf(cx), "z", String.valueOf(cz),
                "count", String.valueOf(claims.count(f.getId()))));
        if (refund > 0) {
            fm.setBank(f, f.getBank() + refund);
            msg(p, M.get("unclaim.refund", "refund", Econ.format(refund), "bank", Econ.format(f.getBank())));
        }
        return true;
    }

    /** Rimborso per un territorio rilasciato: delega a {@link ClaimManager#refundFor} (unica fonte
     *  della regola, condivisa col decadimento da sovraccarico). */
    private double unclaimRefund(double paidAmount) {
        return claims.refundFor(paidAmount);
    }

    /**
     * /f unclaimall - rilascia TUTTI i territori della fazione. Irreversibile: il primo utilizzo mostra
     * solo un avviso a schermo (titolo) e richiede di rieseguire il comando entro {@code
     * claims.unclaim-all-confirm-seconds} (default 10s) per confermare davvero.
     */
    private boolean unclaimAll(Player p) {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        if (!fm.hasPerm(f, p.getUniqueId(), "unclaim")) { msg(p, M.get("unclaim.no-perm")); return true; }

        int owned = claims.count(f.getId());
        if (owned == 0) { msg(p, M.get("unclaimall.none")); return true; }

        long confirmWindowMs = 1000L * plugin.getConfig().getInt("claims.unclaim-all-confirm-seconds", 10);
        long now = System.currentTimeMillis();
        Long requestedAt = unclaimAllConfirm.get(p.getUniqueId());

        if (requestedAt != null && now - requestedAt < confirmWindowMs) {
            unclaimAllConfirm.remove(p.getUniqueId());
            // Rimborso: stessa % del singolo unclaim, sulla SOMMA di quanto fu pagato per tutti i
            // territori (letta PRIMA della rimozione). Va alla banca della fazione.
            double refund = unclaimRefund(claims.paidTotal(f.getId()));
            int removed = claims.removeAll(f.getId());
            msg(p, M.get("unclaimall.success", "count", String.valueOf(removed)));
            if (refund > 0) {
                fm.setBank(f, f.getBank() + refund);
                msg(p, M.get("unclaim.refund", "refund", Econ.format(refund), "bank", Econ.format(f.getBank())));
            }
            return true;
        }

        unclaimAllConfirm.put(p.getUniqueId(), now);
        String title = org.bukkit.ChatColor.translateAlternateColorCodes('&', M.get("unclaimall.confirm-title"));
        String sub = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                M.get("unclaimall.confirm-subtitle", "count", String.valueOf(owned)));
        p.sendTitle(title, sub, 10, 70, 20);
        msg(p, M.get("unclaimall.confirm-chat", "count", String.valueOf(owned)));
        String sound = plugin.getConfig().getString("claims.unclaim-all-confirm-sound", "entity.wither.spawn");
        if (sound != null && !sound.isEmpty()) {
            float vol = (float) plugin.getConfig().getDouble("claims.unclaim-all-confirm-sound-volume", 1.0);
            float pitch = (float) plugin.getConfig().getDouble("claims.unclaim-all-confirm-sound-pitch", 1.0);
            try { p.playSound(p.getLocation(), sound, vol, pitch); } catch (Exception ignored) {}
        }
        return true;
    }

    /**
     * /f owner [giocatore|clear] - PROPRIETARIO della land (chunk) in cui ti trovi.
     * <ul>
     *   <li>senza argomento: mostra il proprietario attuale del chunk;</li>
     *   <li>{@code <giocatore>}: lo imposta proprietario (dev'essere un membro della fazione). Da quel
     *       momento, in quel chunk, interagiscono solo lui e il LEADER (i compagni no): niente casse,
     *       niente posa/rottura blocchi (vedi {@link com.teolo.magixfactions.listener.ProtectionListener});</li>
     *   <li>{@code clear|none|remove}: toglie il proprietario (torna libero per tutti i membri).</li>
     * </ul>
     * Solo il LEADER puo' usarlo (e gli admin col bypass, che agiscono ovunque comunque).
     */
    private boolean owner(Player p, String[] a) {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        if (!p.getUniqueId().equals(f.getLeader())) { msg(p, M.get("owner.not-leader")); return true; }

        org.bukkit.Chunk ch = p.getLocation().getChunk();
        String world = ch.getWorld().getName();
        int cx = ch.getX(), cz = ch.getZ();
        Long ownerId = claims.owner(world, cx, cz);
        if (ownerId == null || ownerId != f.getId()) { msg(p, M.get("owner.not-your-land")); return true; }

        if (a.length < 2) {   // nessun argomento: mostra il proprietario attuale
            String co = claims.chunkOwnerUuid(world, cx, cz);
            if (co == null) { msg(p, M.get("owner.none-here")); return true; }
            String name = Bukkit.getOfflinePlayer(UUID.fromString(co)).getName();
            msg(p, M.get("owner.current", "owner", name != null ? name : "?"));
            return true;
        }

        String arg = a[1];
        if (arg.equalsIgnoreCase("clear") || arg.equalsIgnoreCase("none") || arg.equalsIgnoreCase("remove")) {
            claims.setChunkOwner(world, cx, cz, null);
            msg(p, M.get("owner.cleared"));
            return true;
        }
        OfflinePlayer target = resolveMember(f, arg);
        if (target == null) { msg(p, M.get("owner.not-member")); return true; }
        claims.setChunkOwner(world, cx, cz, target.getUniqueId().toString());
        msg(p, M.get("owner.set", "player", target.getName()));
        Player tp = target.getPlayer();
        if (tp != null && !tp.getUniqueId().equals(p.getUniqueId())) msg(tp, M.get("owner.received"));
        return true;
    }

    /**
     * /f map - mostra i territori attorno al giocatore. La modalita' (config {@code map.mode}) puo'
     * essere "item" (item filled_map dinamico, {@link com.teolo.magixfactions.map.FactionMapRenderer})
     * o "chat" (mappa testuale). Lo zoom e' quello per-giocatore in BLOCCHI PER PIXEL (default config
     * {@code map.default-zoom}, override admin con {@code /mf admin setmap <gioc> <bpp>}).
     */
    private boolean map(Player p) {
        double bpp = power.getResolvedZoomFactor(p.getUniqueId());
        if (plugin.getConfig().getString("map.mode", "item").equalsIgnoreCase("chat")) return mapChat(p, bpp);

        maps.removeExistingFactionMaps(p); // libera lo slot della vecchia mappa, se ce l'aveva
        if (p.getInventory().firstEmpty() == -1) { msg(p, M.get("map.inventory-full")); return true; }

        org.bukkit.inventory.ItemStack item = maps.create(p, bpp,
                com.teolo.magixfactions.map.MapService.itemName(plugin, bpp));
        p.getInventory().addItem(item); // c'e' sicuramente posto, appena verificato sopra
        msg(p, M.get("map.given"));
        return true;
    }

    /**
     * /f minimap [on|off] — accende o spegne la minimap HUD nell'angolo dello schermo, preferenza PERSONALE
     * di ogni giocatore. Spenta, resta comunque la mappa in chat ({@code /f map}); accesa, si vede sia la
     * minimap sia la mappa in chat. Richiede il permesso {@code magixfactions.minimap}: senza, la minimap non
     * e' disponibile. Senza argomento fa da interruttore (inverte lo stato attuale).
     */
    private boolean minimapCmd(Player p, String[] a) {
        if (!power.hasMinimapPermission(p)) { msg(p, M.get("minimap.no-permission")); return true; }
        boolean currentlyHidden = power.isMinimapHidden(p.getUniqueId());
        boolean wantHidden;
        if (a.length >= 2) {
            String v = a[1].toLowerCase(Locale.ROOT);
            if (v.equals("on") || v.equals("si") || v.equals("sì")) wantHidden = false;
            else if (v.equals("off") || v.equals("no")) wantHidden = true;
            else { msg(p, M.get("minimap.usage")); return true; }
        } else {
            wantHidden = !currentlyHidden; // nessun argomento: inverte
        }
        power.setMinimapHidden(p, wantHidden);
        msg(p, M.get(wantHidden ? "minimap.disabled" : "minimap.enabled"));
        return true;
    }

    /**
     * /f borders [on|off] — accende o spegne i confini a particelle verdi attorno ai territori, preferenza
     * PERSONALE di ogni giocatore (colonna players.borders_enabled), indipendente dalla fazione. Di serie
     * e' spenta. Aperto a tutti (come /f map): il disegno lo fa il giro periodico di BorderService, che
     * legge questo interruttore. Senza argomento fa da toggle (inverte lo stato attuale).
     */
    private boolean bordersCmd(Player p, String[] a) {
        boolean current = power.isBordersEnabled(p.getUniqueId());
        boolean want;
        if (a.length >= 2) {
            String v = a[1].toLowerCase(Locale.ROOT);
            if (v.equals("on") || v.equals("si") || v.equals("sì")) want = true;
            else if (v.equals("off") || v.equals("no")) want = false;
            else { msg(p, M.get("borders.usage")); return true; }
        } else {
            want = !current; // nessun argomento: inverte
        }
        power.setBordersEnabled(p, want);
        msg(p, M.get(want ? "borders.enabled" : "borders.disabled"));
        return true;
    }

    /**
     * /f map in modalita' CHAT: mappa testuale quadrata NxN (config {@code map.chat.rows}) centrata sul
     * giocatore. Ogni cella aggrega {@code step} chunk (derivato dai blocchi-per-pixel). Le fazioni sono
     * lettere colorate per relazione.
     */
    private boolean mapChat(Player p, double bpp) {
        int rows = Math.max(3, plugin.getConfig().getInt("map.chat.rows", 9));
        if (rows % 2 == 0) rows++;                       // dispari: il centro e' una cella
        int half = rows / 2;
        // In chat una cella e' almeno un chunk: dai blocchi-per-pixel ricaviamo i chunk/cella (min 1).
        int step = Math.max(1, (int) Math.round(bpp));
        String world = p.getWorld().getName();
        int pcx = p.getLocation().getBlockX() >> 4, pcz = p.getLocation().getBlockZ() >> 4;
        Faction own = fm.getFaction(p.getUniqueId());
        String homeKey = own != null ? fm.homeChunkKey(own.getId()) : null;

        String youSym = plugin.getConfig().getString("map.chat.symbols.you", "&f&l+");
        String homeSym = plugin.getConfig().getString("map.chat.symbols.home", "&6&l⌂");
        String neutralSym = plugin.getConfig().getString("map.chat.symbols.neutral", "&8-");

        java.util.LinkedHashMap<Long, Character> letters = new java.util.LinkedHashMap<>();
        panel(p, M.get("map.chat-header", "zoom", com.teolo.magixfactions.map.MapService.formatZoom(bpp)));
        for (int gz = -half; gz <= half; gz++) {
            StringBuilder line = new StringBuilder();
            for (int gx = -half; gx <= half; gx++) {
                int cx = pcx + gx * step, cz = pcz + gz * step;
                if (gx == 0 && gz == 0) { line.append(youSym); continue; }
                if (homeKey != null && homeKey.equals(world + ":" + cx + ":" + cz)) { line.append(homeSym); continue; }
                Long o = claims.owner(world, cx, cz);
                if (o == null) { line.append(neutralSym); continue; }
                char letter = letters.computeIfAbsent(o, k -> (char) ('A' + Math.min(letters.size(), 25)));
                line.append(mapFill(mapRelKey(own, o))).append(letter);
            }
            panel(p, Colors.translate(line.toString()));
        }
        panel(p, M.get("map.chat-legend"));
        for (Map.Entry<Long, Character> e : letters.entrySet()) {
            Faction f = fm.getById(e.getKey());
            if (f == null) continue;
            String rc = Colors.translate(mapFill(mapRelKey(own, e.getKey())));
            panel(p, M.get("map.chat-legend-entry", "relcolor", rc,
                    "letter", String.valueOf(e.getValue()), "name", f.getName()));
        }
        return true;
    }

    /** Relazione (own/ally/enemy) di chi guarda verso la fazione proprietaria di un chunk. Senza fazione
     *  propria si e' nemici di tutti (nessuno stato neutro). */
    private String mapRelKey(Faction viewer, long ownerId) {
        if (viewer == null) return "enemy";
        if (viewer.getId() == ownerId) return "own";
        return fm.effectiveRelation(viewer.getId(), ownerId) == RelationType.ALLY ? "ally" : "enemy";
    }

    /** Codice colore 'fill' (& ...) per una relazione, da config {@code map.colors.<rel>.fill}. */
    private String mapFill(String rel) {
        String def = switch (rel) { case "own" -> "&a"; case "ally" -> "&d"; case "enemy" -> "&c"; default -> "&e"; };
        return plugin.getConfig().getString("map.colors." + rel + ".fill", def);
    }

    /** /f list - elenca tutte le fazioni del server (ordinate per numero di membri). */
    private boolean list(CommandSender s) {
        Collection<Faction> all = fm.all();
        if (all.isEmpty()) { msg(s, M.get("list.empty")); return true; }
        Faction own = (s instanceof Player p) ? fm.getFaction(p.getUniqueId()) : null;
        List<Faction> sorted = new ArrayList<>(all);
        sorted.sort((x, y) -> {
            int c = Integer.compare(y.size(), x.size());
            return c != 0 ? c : x.getName().compareToIgnoreCase(y.getName());
        });
        panel(s, M.get("list.header", "count", String.valueOf(sorted.size())));
        for (Faction f : sorted) {
            panel(s, M.get("list.entry", "relcolor", relColor(own, f), "name", f.getName(),
                    "members", String.valueOf(f.size()), "max", String.valueOf(fm.effectiveMaxMembers(f))));
        }
        return true;
    }

    /**
     * /f top (alias /f classifica) - la CLASSIFICA delle fazioni per punteggio composito.
     * Il punteggio (0-100) sintetizza territori, membri, banca (giacenza media), longevita' e potenza
     * (media), ognuno pesato dal config score.weights (vedi {@link com.teolo.magixfactions.manage.ScoreManager}).
     * Aperto a tutti (anche console), come /f list. Mostra le prime score.top-size e, se la propria
     * fazione e' fuori dai primi, la sua posizione in coda.
     */
    private boolean top(CommandSender s) {
        java.util.List<com.teolo.magixfactions.manage.ScoreManager.Entry> rank = score.ranking();
        if (rank.isEmpty()) { msg(s, M.get("top.empty")); return true; }
        Faction own = (s instanceof Player p) ? fm.getFaction(p.getUniqueId()) : null;
        int size = Math.min(rank.size(), score.topSize());
        panel(s, M.get("top.header", "count", String.valueOf(rank.size())));
        boolean ownShown = false;
        for (int i = 0; i < size; i++) {
            com.teolo.magixfactions.manage.ScoreManager.Entry e = rank.get(i);
            boolean isOwn = own != null && own.getId() == e.faction.getId();
            if (isOwn) ownShown = true;
            sendScoreLine(s, e.faction, M.get(isOwn ? "top.entry-own" : "top.entry",
                    "pos", String.valueOf(i + 1),
                    "relcolor", relColor(own, e.faction), "name", e.faction.getName(),
                    "score", score.formatScore(e.score)));
        }
        // La propria fazione fuori dai primi: mostrala comunque, con la sua posizione, dopo un separatore.
        if (own != null && !ownShown) {
            for (int i = size; i < rank.size(); i++) {
                if (rank.get(i).faction.getId() == own.getId()) {
                    panel(s, M.get("top.separator"));
                    sendScoreLine(s, own, M.get("top.entry-own", "pos", String.valueOf(i + 1),
                            "relcolor", relColor(own, own), "name", own.getName(),
                            "score", score.formatScore(rank.get(i).score)));
                    break;
                }
            }
        }
        return true;
    }

    /**
     * Invia una riga che mostra il punteggio con, al passaggio del mouse, il DETTAGLIO di come ci si
     * arriva (una voce per caratteristica: valore, livello, peso, contributo). In gioco e' un tooltip
     * (hover) sul componente; alla console — che non ha hover — si manda la riga liscia.
     */
    private void sendScoreLine(CommandSender s, Faction f, String legacyLine) {
        if (!(s instanceof Player p)) { panel(s, legacyLine); return; }
        String line = Papi.resolve(p, legacyLine);
        BaseComponent[] comps = TextComponent.fromLegacyText(line);
        HoverEvent he = new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(scoreTooltip(f))));
        for (BaseComponent c : comps) c.setHoverEvent(he);
        p.spigot().sendMessage(comps);
    }

    /** Testo del tooltip del punteggio: intestazione + una riga per caratteristica (dal breakdown live). */
    private String scoreTooltip(Faction f) {
        StringBuilder sb = new StringBuilder(M.get("score-tooltip.header",
                "score", score.formatScore(score.score(f)), "max", score.maxScoreStr()));
        for (com.teolo.magixfactions.manage.ScoreManager.Component c : score.breakdown(f)) {
            String best = c.bestSelf ? M.get("score-tooltip.best-self") : c.bestName;
            sb.append("\n").append(M.get("score-tooltip.line",
                    "label", c.label, "value", c.valueText, "pct", c.pctStr(),
                    "points", c.pointsStr(), "max", c.maxStr(),
                    "best", best, "bestval", c.bestValueText));
        }
        return sb.toString();
    }

    /** Lista di nomi fazione, ognuno colorato in base alla relazione. Voce e separatore da messages.yml. */
    private String names(Faction viewer, List<Faction> list) {
        if (list.isEmpty()) return M.get("relation.none");
        String sep = M.get("relation.list-separator");
        StringBuilder sb = new StringBuilder();
        for (Faction o : list) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(M.get("relation.list-entry", "relcolor", relColor(viewer, o), "name", o.getName()));
        }
        return sb.toString();
    }

    /**
     * Riga "Alleati:" di /f info: elenca gli alleati confermati e (solo per la propria fazione) le
     * richieste di alleanza in sospeso marcate "(in attesa)", con un tooltip al passaggio del mouse.
     */
    private void sendAlliesLine(Player p, Faction f, Faction own, boolean ownView) {
        java.util.List<Faction> allies = fm.alliesOf(f);
        java.util.List<Faction> pIn = ownView ? fm.incomingAllyRequestsOf(f) : java.util.Collections.emptyList();
        java.util.List<Faction> pOut = ownView ? fm.pendingAllyRequestsOf(f) : java.util.Collections.emptyList();

        java.util.List<BaseComponent> parts = new java.util.ArrayList<>();
        legacyInto(parts, M.get("info.allies-label"));
        String sep = M.get("info.allies-separator");
        boolean first = true;
        for (Faction a : allies) {
            if (!first) legacyInto(parts, sep); first = false;
            legacyInto(parts, M.get("info.allies-ally", "relcolor", relColor(own, a), "name", a.getName()));
        }
        for (Faction o : pIn) {
            if (!first) legacyInto(parts, sep); first = false;
            interactiveInto(parts, M.get("info.allies-pending", "name", o.getName()),
                    M.get("info.allies-hover-in", "name", o.getName()), "/f ally " + o.getName());
        }
        for (Faction o : pOut) {
            if (!first) legacyInto(parts, sep); first = false;
            interactiveInto(parts, M.get("info.allies-pending", "name", o.getName()),
                    M.get("info.allies-hover-out", "name", o.getName()), "/f ally " + o.getName());
        }
        if (first) legacyInto(parts, M.get("info.allies-none"));
        p.spigot().sendMessage(parts.toArray(new BaseComponent[0]));
    }

    private void legacyInto(java.util.List<BaseComponent> parts, String legacy) {
        java.util.Collections.addAll(parts, TextComponent.fromLegacyText(legacy));
    }

    /** Come legacyInto ma con tooltip (hover) e click che SUGGERISCE un comando in chat. */
    private void interactiveInto(java.util.List<BaseComponent> parts, String legacy, String hoverLegacy, String suggest) {
        BaseComponent[] comps = TextComponent.fromLegacyText(legacy);
        HoverEvent he = new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(hoverLegacy)));
        ClickEvent ce = new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, suggest);
        for (BaseComponent c : comps) { c.setHoverEvent(he); c.setClickEvent(ce); }
        java.util.Collections.addAll(parts, comps);
    }

    /** Nome della fazione colorato con la relazione del LETTORE (giocatore) verso quella fazione. */
    private String cname(CommandSender reader, Faction f) {
        Faction own = (reader instanceof Player p) ? fm.getFaction(p.getUniqueId()) : null;
        return relColor(own, f) + f.getName();
    }

    /** Nome della fazione colorato con la relazione di una fazione-lettore (per i broadcast ai membri). */
    private String cname(Faction readerFaction, Faction f) {
        return relColor(readerFaction, f) + f.getName();
    }

    private String relationName(RelationType rel) {
        return rel == RelationType.ALLY ? M.get("relation.name-ally") : M.get("relation.name-enemy");
    }

    /** Colore (da config relations.colors) della relazione tra la fazione che guarda e un'altra. Senza
     *  fazione propria si e' nemici di tutti (nessuno stato neutro, coerente col resto del plugin). */
    private String relColor(Faction viewer, Faction target) {
        String key;
        if (target == null) key = "none";
        else if (viewer == null) key = "enemy";
        else if (viewer.getId() == target.getId()) key = "member";
        else key = fm.effectiveRelation(viewer.getId(), target.getId()) == RelationType.ALLY ? "ally" : "enemy";
        String def = switch (key) { case "member" -> "&a"; case "ally" -> "&d"; case "enemy" -> "&c"; default -> "&f"; };
        return Colors.translate(plugin.getConfig().getString("relations.colors." + key, def));
    }

    private boolean info(Player p, String[] a) {
        Faction f = (a.length >= 2) ? fm.getByName(a[1]) : fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("info.not-found")); return true; }
        Faction own = fm.getFaction(p.getUniqueId());
        panel(p, M.get("info.line"));
        panel(p, M.get("info.name", "relcolor", relColor(own, f), "name", f.getName(), "tag", f.getTag()));
        if (!f.getDescription().isEmpty()) panel(p, M.get("info.description", "desc", f.getDescription()));
        panel(p, M.get("info.members", "count", String.valueOf(f.size()), "max", String.valueOf(fm.effectiveMaxMembers(f))));
        // Elenco membri (tag di rank + nome) ordinati per rank decrescente, in grigio dopo il conteggio.
        java.util.List<Member> ms = new java.util.ArrayList<>(f.getMembers().values());
        ms.sort((x, y) -> Integer.compare(ranks.rankOrder(y.getRankId()), ranks.rankOrder(x.getRankId())));
        String memSep = M.get("info.members-list-separator");
        StringBuilder memList = new StringBuilder();
        for (Member m : ms) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(m.getUuid());
            String tag = org.bukkit.ChatColor.translateAlternateColorCodes('&', ranks.resolve(m.getRankId()).getTag());
            String nm = op.getName() != null ? op.getName() : m.getUuid().toString();
            if (memList.length() > 0) memList.append(memSep);
            memList.append(M.get("info.members-list-entry", "tag", tag, "player", nm));
        }
        if (memList.length() > 0) panel(p, M.get("info.members-list", "list", memList.toString()));
        int fPow = power.factionPower(f);
        int fMax = power.factionMaxPower(f);
        int owned = claims.count(f.getId());
        // Riga Stato territori/potenza/potenza-max + riga descrittiva:
        //  - senza territori: bianco, "non ha ancora un territorio";
        //  - verde se sicura (potenza >= territori), rossa se raidabile.
        String statusColor, statusDesc;
        if (owned == 0) {
            statusColor = M.get("info.status-none-color");
            statusDesc = M.get("info.status-none");
        } else if (fPow >= owned) {
            statusColor = M.get("info.status-safe-color");
            statusDesc = M.get("info.status-strong");
        } else {
            statusColor = M.get("info.status-raid-color");
            statusDesc = M.get("info.status-weak");
        }
        panel(p, M.get("info.status", "statuscolor", statusColor,
                "claims", String.valueOf(owned), "power", String.valueOf(fPow), "maxpower", String.valueOf(fMax)));
        panel(p, statusDesc);
        // Banca di fazione: mostrata solo se c'e' un'economia attiva (senza, il saldo non avrebbe senso).
        if (Econ.enabled()) panel(p, M.get("info.bank", "bank", Econ.format(f.getBank())));
        // Punteggio composito + posizione in classifica (/f top). Un tooltip spiega da cosa e' composto.
        // Una fazione INATTIVA (tutti i membri assenti da troppo) e' oscurata: riga senza posizione + avviso.
        boolean active = score.isActive(f);
        String scoreLine = active
                ? M.get("info.score", "score", score.formatScore(score.score(f)), "pos", String.valueOf(score.position(f)))
                : M.get("info.score-unranked", "score", score.formatScore(score.score(f)));
        sendScoreLine(p, f, scoreLine);
        if (!active) panel(p, M.get("info.inactive", "days", String.valueOf(score.inactiveDays())));
        sendAlliesLine(p, f, own, own != null && own.getId() == f.getId());
        if (own != null && own.getId() != f.getId()) {
            RelationType rel = fm.effectiveRelation(own.getId(), f.getId());
            panel(p, M.get("info.your-relation", "relation", relationName(rel)));
        }
        panel(p, M.get("info.line"));
        return true;
    }

    /**
     * /f power [giocatore] (alias /f pow) - mostra la Potenza di un giocatore (attuale/massima).
     * Senza argomento mostra la propria. Il bersaglio dev'essere entrato almeno una volta (ha una riga).
     */
    private boolean powerCmd(Player p, String[] a) {
        if (a.length >= 2) {
            UUID target = power.findByName(a[1]);
            if (target == null) { msg(p, M.get("power.not-found", "player", a[1])); return true; }
            String name = Bukkit.getOfflinePlayer(target).getName();
            msg(p, M.get("power.other", "player", name != null ? name : a[1],
                    "power", String.valueOf(power.getPower(target)),
                    "maxpower", String.valueOf(power.getMaxPower(target))));
        } else {
            msg(p, M.get("power.self",
                    "power", String.valueOf(power.getPower(p.getUniqueId())),
                    "maxpower", String.valueOf(power.getMaxPower(p.getUniqueId()))));
            // Righe in piu' solo per chi ha velocita' diverse dal normale (permessi VIP): al giocatore
            // comune non serve sapere che esistono, a chi paga si', ed e' il modo piu' semplice per
            // verificare che il permesso sia davvero attivo.
            com.teolo.magixfactions.manage.PowerManager.Vantaggi vip = power.vantaggi(p);
            if (vip.speed != 100) {
                msg(p, M.get("power.speed",
                        "speed", String.valueOf(vip.speed),
                        "minutes", com.teolo.magixfactions.util.DurationText.fromSeconds(power.secondsPerGain(p))));
            }
            if (vip.loss != 100) {
                msg(p, M.get("power.loss-speed", "speed", String.valueOf(vip.loss)));
            }
        }
        return true;
    }


    /**
     * Allerta a TUTTO il server quando una fazione conquista (overclaim) un territorio nemico: titolo
     * al centro dello schermo + suono forte a ogni giocatore online. Configurabile in
     * {@code claims.overclaim-alert} (default attivo). Non riguarda i claim su terreno neutrale.
     */
    private void announceOverclaim(Faction attacker, Faction victim, int x, int z) {
        if (!plugin.getConfig().getBoolean("claims.overclaim-alert.enabled", true)) return;
        String title = M.get("claim.overclaim-alert-title", "attacker", attacker.getName(), "victim", victim.getName());
        String sub = M.get("claim.overclaim-alert-subtitle", "attacker", attacker.getName(),
                "victim", victim.getName(), "x", String.valueOf(x), "z", String.valueOf(z));
        int in = plugin.getConfig().getInt("claims.overclaim-alert.fade-in", 10);
        int stay = plugin.getConfig().getInt("claims.overclaim-alert.stay", 60);
        int out = plugin.getConfig().getInt("claims.overclaim-alert.fade-out", 20);
        String sound = plugin.getConfig().getString("claims.overclaim-alert.sound", "entity.wither.spawn");
        float vol = (float) plugin.getConfig().getDouble("claims.overclaim-alert.sound-volume", 1.0);
        float pitch = (float) plugin.getConfig().getDouble("claims.overclaim-alert.sound-pitch", 0.8);
        boolean playSound = sound != null && !sound.isEmpty();
        for (Player pl : Bukkit.getOnlinePlayers()) {
            pl.sendTitle(title, sub, in, stay, out);
            if (playSound) {
                try { pl.playSound(pl.getLocation(), sound, vol, pitch); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * /f deposit|d <soldi> (versa) e /f withdraw|w <soldi> (preleva) — BANCA DI FAZIONE.
     * Il versamento e' aperto a OGNI membro (far crescere la fazione e' di tutti); il prelievo e'
     * riservato a chi ha il permesso di grado {@code withdraw} (di default officer e leader — il
     * leader ce l'ha via wildcard '*'). Richiede un'economia Vault attiva ({@link Econ}); il saldo
     * vive in {@code factions.bank} (cache subito, DB async — {@link FactionManager#setBank}).
     * L'ordine delle operazioni non lascia mai soldi "nel limbo": prima si TOGLIE dalla sorgente
     * (giocatore o banca), e solo se riesce si ACCREDITA alla destinazione.
     */
    private boolean bankCmd(Player p, String[] a, boolean deposit) {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) { msg(p, M.get("errors.no-faction")); return true; }
        if (!Econ.enabled()) { msg(p, M.get("bank.no-economy")); return true; }
        if (!deposit && !fm.hasPerm(f, p.getUniqueId(), "withdraw")) { msg(p, M.get("bank.no-perm")); return true; }
        if (a.length < 2) { msg(p, M.get(deposit ? "bank.usage-deposit" : "bank.usage-withdraw")); return true; }

        double amount;
        try { amount = Double.parseDouble(a[1].replace(',', '.')); }
        catch (NumberFormatException e) { msg(p, M.get("bank.bad-amount")); return true; }
        // due decimali max, sempre positivo (evita giochetti con -x o frazioni di centesimo)
        amount = Math.floor(amount * 100) / 100.0;
        if (amount <= 0 || !Double.isFinite(amount)) { msg(p, M.get("bank.bad-amount")); return true; }

        if (deposit) {
            if (!Econ.has(p, amount)) { msg(p, M.get("bank.not-enough-money", "amount", Econ.format(amount))); return true; }
            if (!Econ.withdraw(p, amount)) { msg(p, M.get("bank.pay-failed")); return true; }
            fm.setBank(f, f.getBank() + amount);
            msg(p, M.get("bank.deposited", "amount", Econ.format(amount), "bank", Econ.format(f.getBank())));
        } else {
            if (f.getBank() < amount) { msg(p, M.get("bank.insufficient", "bank", Econ.format(f.getBank()))); return true; }
            if (!Econ.deposit(p, amount)) { msg(p, M.get("bank.pay-failed")); return true; }
            fm.setBank(f, f.getBank() - amount);
            msg(p, M.get("bank.withdrawn", "amount", Econ.format(amount), "bank", Econ.format(f.getBank())));
        }
        return true;
    }

    /**
     * Costo del PROSSIMO territorio (la fazione ne possiede gia' {@code owned}), dalla spec
     * {@code claims.cost.money} in formato <b>"base,incremento,blocco,moltiplicatore"</b>:
     * <ul>
     *   <li><b>base</b> = costo del primo territorio;</li>
     *   <li><b>incremento</b> = aumento a ogni territorio successivo;</li>
     *   <li><b>blocco</b> = ogni quanti territori scatta la moltiplicazione;</li>
     *   <li><b>moltiplicatore</b> = per quanto vengono moltiplicati base (= ultimo costo del blocco
     *       precedente x moltiplicatore) e incremento a ogni scatto. Se omesso (forma a 3 valori,
     *       compatibilita' con la spec precedente) vale quanto il blocco.</li>
     * </ul>
     * Esempio "10,10,5,5" (sequenza di riferimento concordata con l'utente):
     * 10, 20, 30, 40, 50, 250, 300, 350, 400, 450, 2250, 2500, 2750, 3000, 3250, ...
     * Forme brevi: "0"/vuoto = gratis; "100" = costo fisso; "10,10" = incremento senza blocchi.
     * Spec malformata = gratis (non blocca mai il gioco per un refuso nel config).
     */
    static double nextClaimCost(String spec, int owned) {
        if (spec == null || spec.isBlank()) return 0;
        String[] parts = spec.split(",");
        double base, inc = 0, mult = 0;
        int block = 0;
        try {
            base = Double.parseDouble(parts[0].trim());
            if (parts.length >= 2) inc = Double.parseDouble(parts[1].trim());
            if (parts.length >= 3) block = (int) Double.parseDouble(parts[2].trim());
            mult = parts.length >= 4 ? Double.parseDouble(parts[3].trim()) : block;
        } catch (NumberFormatException e) {
            return 0;
        }
        if (base <= 0 && inc <= 0) return 0;
        if (block <= 0 || mult <= 0) return base + inc * owned; // nessun blocco/moltiplicatore: lineare
        int blocks = owned / block, pos = owned % block;
        double curBase = base, curInc = inc;
        for (int i = 0; i < blocks; i++) {
            double last = curBase + curInc * (block - 1); // ultimo costo del blocco corrente
            curBase = last * mult;
            curInc = curInc * mult;
            if (curBase > 1.0e15) return 1.0e15;          // tetto anti-overflow: di fatto inarrivabile
        }
        return Math.floor((curBase + curInc * pos) * 100) / 100.0;
    }

    /**
     * /f help [pagina] - l'elenco dei comandi.
     *
     * Le voci stanno in messages.yml (help.sections) e le impagina {@link Help}, la stessa classe
     * degli altri plugin Magix: sezioni, frecce per sfogliare, ogni riga cliccabile per scriversi
     * il comando in chat. I comandi di /mf li vede solo chi ha magixfactions.admin.
     */
    private void help(CommandSender s, int page) {
        List<Help.Entry> entries = Help.fromConfig(M.section("help.sections"));
        if (entries.isEmpty()) {
            // messages.yml di una versione precedente (elenco piatto): meglio quello che niente.
            for (String line : M.getList("help")) panel(s, line);
            if (s.hasPermission("magixfactions.admin")) for (String line : M.getList("help-admin")) panel(s, line);
            return;
        }
        ConfigurationSection h = M.section("help");
        String title = h != null ? h.getString("title", "MagixFactions") : "MagixFactions";
        Help.show(s, title, "/f help", entries, page, s.hasPermission("magixfactions.admin"));
    }

    // ---- TAB COMPLETION -----------------------------------------------------------------------------
    // Suggerimenti CONTESTUALI e filtrati sull'ACCESSO REALE di chi scrive: un giocatore vede solo i
    // sottocomandi che puo' davvero usare (in fazione o no, permessi del suo grado, leader, admin) e
    // per gli argomenti riceve i valori giusti (nomi giocatori/fazioni/canali/valori ammessi).

    @Override
    public List<String> onTabComplete(CommandSender s, Command command, String alias, String[] args) {
        if (args.length == 1) return filterPrefix(args[0], visibleSubcommands(s));
        String sub = args[0].toLowerCase(Locale.ROOT);
        Player p = s instanceof Player pl ? pl : null;
        switch (sub) {
            case "invite": case "power": case "pow":
                if (args.length == 2) return filterPrefix(args[1], onlineNames());
                break;
            case "kick": case "promote": case "demote": case "transfer":
                // bersagli sensati = i MEMBRI della propria fazione (se stessi esclusi)
                if (args.length == 2 && p != null) return filterPrefix(args[1], memberNames(p));
                break;
            case "owner":
                // membri della fazione + "clear" per togliere il proprietario
                if (args.length == 2 && p != null) {
                    List<String> opts = new ArrayList<>(memberNames(p));
                    opts.add("clear");
                    return filterPrefix(args[1], opts);
                }
                break;
            case "join": case "ally": case "a": case "enemy": case "e": case "info":
                if (args.length == 2) return filterPrefix(args[1], factionNames());
                break;
            case "chat":
                if (args.length == 2) return filterPrefix(args[1], List.of("public", "faction", "ally"));
                break;
            case "minimap":
                if (args.length == 2) return filterPrefix(args[1], List.of("on", "off"));
                break;
            case "borders": case "border": case "confini":
                if (args.length == 2) return filterPrefix(args[1], List.of("on", "off"));
                break;
            case "deposit": case "d": case "withdraw": case "w":
                if (args.length == 2) return filterPrefix(args[1], List.of("10", "100", "1000"));
                break;
            case "admin":
                if (!s.hasPermission("magixfactions.admin")) break;
                if (args.length == 2)
                    return filterPrefix(args[1], List.of("setpower", "setmap", "fake"));
                if (args.length == 3) {
                    if (args[1].equalsIgnoreCase("fake"))
                        return filterPrefix(args[2], List.of("create", "clear", "info"));
                    return filterPrefix(args[2], onlineNames());
                }
                if (args.length == 4) {
                    switch (args[1].toLowerCase(Locale.ROOT)) {
                        case "setmap": return filterPrefix(args[3],
                                List.of("0.25", "0.5", "1", "2", "4", "8", "16", "32", "64", "reset"));
                        case "setpower": return filterPrefix(args[3], List.of("reset"));
                        case "fake":
                            if (args[2].equalsIgnoreCase("create"))
                                return filterPrefix(args[3], List.of("6", "10", "20"));
                            break;
                    }
                }
                break;
            case "db":
                if (!s.hasPermission("magixfactions.admin")) break;
                if (args.length == 2) return filterPrefix(args[1], List.of("info", "migrate"));
                if (args.length == 3 && args[1].equalsIgnoreCase("migrate"))
                    return filterPrefix(args[2], List.of("sqlite", "mariadb"));
                break;
        }
        return List.of();
    }

    /** Sottocomandi visibili a CHI scrive: senza fazione solo create/join/base; in fazione quelli
     *  permessi dal proprio grado (il leader, via wildcard '*', li vede tutti); admin/db/reload solo
     *  col permesso magixfactions.admin. */
    private List<String> visibleSubcommands(CommandSender s) {
        List<String> out = new ArrayList<>();
        out.add("help"); out.add("list"); out.add("top"); out.add("info");
        if (s instanceof Player p) {
            out.add("map"); out.add("power"); out.add("borders"); // confini a particelle: aperto a tutti
            if (power.hasMinimapPermission(p)) out.add("minimap"); // interruttore HUD, solo a chi ha il permesso

            Faction f = fm.getFaction(p.getUniqueId());
            if (f == null) {
                out.add("create"); out.add("join");
            } else {
                UUID u = p.getUniqueId();
                out.add("chat"); out.add("leave");
                if (fm.hasPerm(f, u, "claim")) out.add("claim");
                if (fm.hasPerm(f, u, "unclaim")) { out.add("unclaim"); out.add("unclaimall"); }
                if (fm.hasPerm(f, u, "sethome")) { out.add("sethome"); out.add("unsethome"); }
                if (fm.hasPerm(f, u, "home")) out.add("home");
                if (fm.hasPerm(f, u, "invite")) out.add("invite");
                if (fm.hasPerm(f, u, "kick")) out.add("kick");
                if (fm.hasPerm(f, u, "promote")) out.add("promote");
                if (fm.hasPerm(f, u, "demote")) out.add("demote");
                if (fm.hasPerm(f, u, "relation")) { out.add("ally"); out.add("enemy"); }
                if (fm.hasPerm(f, u, "description")) out.add("description");
                if (Econ.enabled()) {
                    out.add("deposit"); // versare e' di tutti
                    if (fm.hasPerm(f, u, "withdraw")) out.add("withdraw");
                }
                if (u.equals(f.getLeader())) { out.add("rename"); out.add("transfer"); out.add("disband"); out.add("owner"); }
            }
        }
        if (s.hasPermission("magixfactions.admin")) { out.add("admin"); out.add("db"); out.add("reload"); }
        return out;
    }

    /** Filtra le opzioni per prefisso (case-insensitive) e le ordina: e' il contratto standard del tab. */
    /** Il numero di pagina scritto dall'utente; qualsiasi cosa strana vale 1. */
    private static int page(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 1; }
    }

    private static List<String> filterPrefix(String prefix, List<String> options) {
        String low = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(low)) out.add(o);
        java.util.Collections.sort(out);
        return out;
    }

    private static List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Player pl : Bukkit.getOnlinePlayers()) out.add(pl.getName());
        return out;
    }

    private List<String> factionNames() {
        List<String> out = new ArrayList<>();
        for (Faction f : fm.all()) out.add(f.getName());
        return out;
    }

    /** Nomi dei membri della fazione di {@code p} (se stesso escluso), dalla usercache (mai bloccante). */
    private List<String> memberNames(Player p) {
        Faction f = fm.getFaction(p.getUniqueId());
        if (f == null) return List.of();
        List<String> out = new ArrayList<>();
        for (UUID u : f.getMembers().keySet()) {
            if (u.equals(p.getUniqueId())) continue;
            String n = Bukkit.getOfflinePlayer(u).getName();
            if (n != null) out.add(n);
        }
        return out;
    }

    /** /mf admin ... - comandi amministrativi (permesso magixfactions.admin, default OP). */
    private boolean adminCommand(CommandSender s, String[] a) {
        if (!s.hasPermission("magixfactions.admin")) { msg(s, M.get("errors.no-permission")); return true; }
        String sc = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "";
        switch (sc) {
            // Tetto di Potenza e minimap NON hanno piu' un comando: sono permessi
            // (magixfactions.power.powermax.<n> e magixfactions.minimap), si danno dal gruppo VIP.
            case "setpower": return adminSetPower(s, a);
            case "setmap": return adminSetMap(s, a);
            case "fake": return adminFake(s, a);
            case "minimapdump": minimap.dumpMapPacketStructure(s); return true;
            case "minimaprptest": return adminMinimapResourcePackTest(s);
            case "minimapmarker": return adminMinimapMarkerTest(s);
            default: msg(s, M.get("admin.usage")); return true;
        }
    }

    /**
     * /mf admin minimapmarker - comando TEMPORANEO di debug (Fase 2 del piano minimap v2): da' una
     * mappa di prova (sfondo ciano + 2 pixel marcatore) a chi lo esegue, per verificare lo shader del
     * resource pack SENZA dover prima costruire il quadro fittizio montato — se lo shader funziona, la
     * mappa dovrebbe "saltare" in un angolo fisso dello schermo invece di comportarsi come una normale
     * mappa in mano.
     */
    private boolean adminMinimapMarkerTest(CommandSender s) {
        if (!(s instanceof Player p)) { msg(s, M.get("errors.players-only")); return true; }
        org.bukkit.map.MapView view = Bukkit.createMap(p.getWorld());
        for (org.bukkit.map.MapRenderer r : new java.util.ArrayList<>(view.getRenderers())) view.removeRenderer(r);
        view.addRenderer(new com.teolo.magixfactions.resourcepack.MarkerTestRenderer());
        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.FILLED_MAP);
        org.bukkit.inventory.meta.MapMeta meta = (org.bukkit.inventory.meta.MapMeta) item.getItemMeta();
        meta.setMapView(view);
        meta.setDisplayName(Colors.translate("&bMappa di test (marker shader)"));
        item.setItemMeta(meta);
        p.getInventory().addItem(item);
        msg(s, Colors.translate("&aMappa di test data. Tienila in mano e guarda i valori RGB nel log/chat."));
        return true;
    }

    /**
     * /mf admin minimaprptest - comando TEMPORANEO di debug (Fase 0/1 del piano minimap v2): invia il
     * resource pack della minimap a chi lo esegue, per verificare che l'intera pipeline funzioni
     * (build zip -> server HTTP -> download client -> accettazione) PRIMA di aggiungere qualunque logica
     * di shader/marcatore. L'esito (accettato/rifiutato/fallito) va controllato in console, vedi
     * {@link com.teolo.magixfactions.resourcepack.ResourcePackListener}.
     */
    private boolean adminMinimapResourcePackTest(CommandSender s) {
        if (!(s instanceof Player p)) { msg(s, M.get("errors.players-only")); return true; }
        if (resourcePack == null || !resourcePack.isAvailable()) {
            msg(s, Colors.translate("&cResource pack non disponibile (controlla minimap.resourcepack.public-host in config.yml)."));
            return true;
        }
        resourcePack.sendTo(p);
        msg(s, Colors.translate("&aResource pack inviato. Controlla se il client chiede conferma, poi guarda la console per l'esito."));
        return true;
    }

    /** /mf admin setmap <giocatore> <closest|close|normal|far|farthest|reset>. */
    private boolean adminSetMap(CommandSender s, String[] a) {
        if (a.length < 4) { msg(s, M.get("admin.setmap-usage")); return true; }
        UUID target = power.findByName(a[2]);
        if (target == null) { msg(s, M.get("admin.not-found", "player", a[2])); return true; }
        boolean reset = a[3].equalsIgnoreCase("reset");
        double bpp = 0;
        if (!reset) {
            try {
                bpp = Double.parseDouble(a[3].replace(',', '.'));
            } catch (NumberFormatException e) { msg(s, M.get("admin.setmap-bad")); return true; }
            if (bpp < 0.05 || bpp > 64) { msg(s, M.get("admin.setmap-bad")); return true; }
        }
        power.setMapZoomBpp(target, reset ? 0 : bpp);
        // Se il giocatore e' online e ha gia' una Mappa Fazioni in inventario, aggiornane lo zoom sul
        // posto: non deve dover rifare /f map per vedere il nuovo zoom.
        Player targetPlayer = Bukkit.getPlayer(target);
        if (targetPlayer != null) {
            double rz = power.getResolvedZoomFactor(target);
            maps.updateScale(targetPlayer, rz, com.teolo.magixfactions.map.MapService.itemName(plugin, rz));
            // La minimap deve SEMPRE mostrare la stessa area della mappa cartacea: se e' gia' attiva,
            // riattivarla la ricrea con lo zoom appena cambiato.
            if (minimap.isActive(targetPlayer)) minimap.activate(targetPlayer);
        }
        String label = com.teolo.magixfactions.map.MapService.formatZoom(power.getResolvedZoomFactor(target));
        msg(s, M.get(reset ? "admin.setmap-reset" : "admin.setmap-ok", "player", a[2], "zoom", label));
        return true;
    }

    /**
     * /mf admin setpower &lt;giocatore&gt; &lt;valore|reset&gt; - corregge la Potenza ATTUALE (uno sconto
     * dopo una morte ingiusta, o un azzeramento). Il TETTO invece non si tocca piu' da qui: e' il
     * permesso {@code magixfactions.power.powermax.<numero>}.
     */
    private boolean adminSetPower(CommandSender s, String[] a) {
        if (a.length < 4) { msg(s, M.get("admin.usage")); return true; }
        UUID target = power.findByName(a[2]);
        if (target == null) { msg(s, M.get("admin.not-found", "player", a[2])); return true; }
        boolean reset = a[3].equalsIgnoreCase("reset");
        int value;
        if (reset) {
            value = plugin.getConfig().getInt("power.start", 0);
        } else {
            try { value = Integer.parseInt(a[3]); } catch (NumberFormatException e) { msg(s, M.get("admin.bad-value")); return true; }
        }
        power.setPower(target, value);
        msg(s, M.get(reset ? "admin.setpower-reset" : "admin.setpower-ok",
                "player", a[2], "value", String.valueOf(power.getPower(target))));
        return true;
    }

    /**
     * /mf admin fake &lt;create [n] [minMembri] [maxMembri] | clear | info&gt; — DATI DI TEST: fazioni e
     * giocatori finti per popolare server e sito prima dell'apertura, rimovibili tutti insieme quando si
     * apre al pubblico. Vedi {@link com.teolo.magixfactions.manage.FakeDataManager}.
     */
    private boolean adminFake(CommandSender s, String[] a) {
        String op = a.length >= 3 ? a[2].toLowerCase(Locale.ROOT) : "";
        switch (op) {
            case "create": {
                int count = a.length >= 4 ? parseIntOr(a[3], 6) : 6;
                int minM = a.length >= 5 ? parseIntOr(a[4], 2) : 2;
                int maxM = a.length >= 6 ? parseIntOr(a[5], 5) : 5;
                count = Math.max(1, Math.min(100, count));
                minM = Math.max(1, Math.min(30, minM));
                maxM = Math.max(minM, Math.min(30, maxM));
                try {
                    com.teolo.magixfactions.manage.FakeDataManager.Result r = fake.generate(count, minM, maxM);
                    msg(s, M.get("admin.fake.created",
                            "factions", String.valueOf(r.factions), "players", String.valueOf(r.players)));
                } catch (Exception e) {
                    msg(s, M.get("errors.generic", "error", String.valueOf(e.getMessage())));
                }
                return true;
            }
            case "clear": {
                if (!fake.hasAny()) { msg(s, M.get("admin.fake.none")); return true; }
                com.teolo.magixfactions.manage.FakeDataManager.Result r = fake.clearAll();
                msg(s, M.get("admin.fake.cleared",
                        "factions", String.valueOf(r.factions), "players", String.valueOf(r.players)));
                return true;
            }
            case "info": {
                msg(s, M.get("admin.fake.info",
                        "factions", String.valueOf(fake.factionCount()),
                        "players", String.valueOf(fake.playerCount())));
                return true;
            }
            default:
                msg(s, M.get("admin.fake.usage"));
                return true;
        }
    }

    /** Un intero dal testo, o il default se non è un numero valido. */
    private static int parseIntOr(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private boolean dbCommand(CommandSender s, String[] a) {
        if (!s.hasPermission("magixfactions.admin")) { msg(s, M.get("db.no-perm")); return true; }
        if (a.length >= 2 && a[1].equalsIgnoreCase("info")) {
            msg(s, M.get("db.info-header", "type", String.valueOf(db.getType())));
            try { for (String t : Database.TABLES) panel(s, M.get("db.info-row", "table", t, "count", String.valueOf(db.countRows(t)))); }
            catch (Exception e) { msg(s, M.get("errors.generic", "error", String.valueOf(e.getMessage()))); }
            return true;
        }
        if (a.length >= 3 && a[1].equalsIgnoreCase("migrate")) {
            Database.Type target = Database.parseType(a[2]);
            if (target == db.getType()) { msg(s, M.get("db.same")); return true; }
            msg(s, M.get("db.migrating", "from", String.valueOf(db.getType()), "to", String.valueOf(target)));
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                HikariDataSource tds = null;
                try {
                    tds = Database.buildDataSource(target, plugin.getConfig().getConfigurationSection("storage"),
                            plugin.getDataFolder().getAbsolutePath());
                    Database targetDb = new Database(target, tds);
                    Map<String, Integer> res = Migrator.migrate(db, targetDb, plugin.getLogger());
                    int tot = res.values().stream().mapToInt(Integer::intValue).sum();
                    final HikariDataSource toClose = tds;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        msg(s, M.get("db.migrated", "rows", String.valueOf(tot), "to", String.valueOf(target)));
                        msg(s, M.get("db.hint", "type", target.name().toLowerCase()));
                        toClose.close();
                    });
                } catch (Exception e) {
                    final String em = String.valueOf(e.getMessage());
                    if (tds != null) tds.close();
                    Bukkit.getScheduler().runTask(plugin, () -> msg(s, M.get("db.failed", "error", em)));
                }
            });
            return true;
        }
        msg(s, M.get("db.usage"));
        return true;
    }

    private OfflinePlayer resolveMember(Faction f, String name) {
        for (UUID u : f.getMembers().keySet()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
            if (op.getName() != null && op.getName().equalsIgnoreCase(name)) return op;
        }
        return null;
    }

    private void broadcast(Faction f, String message) {
        for (UUID u : f.getMembers().keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) msg(p, message);
        }
    }
}
