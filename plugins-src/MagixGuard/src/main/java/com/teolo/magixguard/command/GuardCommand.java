package com.teolo.magixguard.command;

import com.teolo.magixguard.GuardConfig;
import com.teolo.magixguard.MagixGuard;
import com.teolo.magixguard.analyze.LinkScorer;
import com.teolo.magixguard.db.DbExecutor;
import com.teolo.magixguard.db.GuardDao;
import com.teolo.magixguard.dossier.DossierBuilder;
import com.teolo.magixguard.lang.Messages;
import com.teolo.magixguard.model.PlayerRef;
import com.teolo.magixguard.model.Rows;
import com.teolo.magixguard.sanctions.Text;
import com.teolo.magixguard.util.Fmt;
import com.teolo.magixguard.util.Help;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Comandi staff. Tutto quello che tocca il database gira sul thread dedicato e risponde poi
 * sul thread principale: nessun comando puo' far scattare un lag spike.
 */
public final class GuardCommand implements CommandExecutor, TabCompleter {

    private static final TextColor TEXT = Help.GREY;
    private static final NamedTextColor HIGHLIGHT = NamedTextColor.WHITE;

    private final MagixGuard plugin;
    private final GuardConfig config;
    private final GuardDao dao;
    private final DbExecutor executor;
    private final LinkScorer scorer;
    private final DossierBuilder dossier;
    private final Messages messages;

    public GuardCommand(MagixGuard plugin, GuardConfig config, GuardDao dao, DbExecutor executor,
                        LinkScorer scorer, DossierBuilder dossier, Messages messages) {
        this.plugin = plugin;
        this.config = config;
        this.dao = dao;
        this.executor = executor;
        this.scorer = scorer;
        this.dossier = dossier;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // "?" e il numero da solo sfogliano l'aiuto, come negli altri plugin Magix: e' quello
        // che mandano le frecce in fondo all'elenco (vedi plugins-src/STILE-MAGIX.md).
        if (args.length == 0 || args[0].equalsIgnoreCase("help") || args[0].equals("?")) {
            help(sender, args.length >= 2 ? page(args[1]) : 1);
            return true;
        }
        if (args[0].chars().allMatch(Character::isDigit)) {
            help(sender, page(args[0]));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "alts", "profilo" -> alts(sender, args);
            case "dossier" -> dossier(sender, args);
            case "sessions", "accessi" -> sessions(sender, args);
            case "alerts", "segnalazioni" -> alerts(sender, args);
            case "link" -> link(sender, args);
            case "unlink", "whitelist" -> unlink(sender, args);
            case "verify", "verifica" -> verify(sender);
            case "stats" -> stats(sender);
            case "exempt", "escludi" -> exempt(sender, args);
            case "reload" -> reload(sender);
            default -> help(sender, 1);
        }
        return true;
    }

    /** Il numero di pagina scritto dall'utente; qualsiasi cosa strana vale 1. */
    private static int page(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 1; }
    }

    // ============================== sottocomandi ==============================

    private void alts(CommandSender sender, String[] args) {
        if (args.length < 2) {
            reply(sender, error("Uso: /mg alts <nickname>"));
            return;
        }
        String name = args[1];
        executor.submit("comando alts " + name, () -> {
            Optional<PlayerRef> target = dao.findPlayerByName(name);
            if (target.isEmpty()) {
                reply(sender, error("Nessun account profilato con il nome " + name + "."));
                return;
            }
            PlayerRef player = target.get();
            List<Rows.Link> links = dao.linksOf(player.id(), config.linkThreshold);
            long now = System.currentTimeMillis();

            List<Component> out = new ArrayList<>();
            out.add(title("Collegamenti di " + player.name()));
            out.add(Component.text("Primo accesso " + Fmt.dateTime(player.firstSeen())
                    + " - accessi " + player.sessionCount()
                    + " - gioco " + Fmt.duration(player.playtimeSeconds() * 1000), TEXT));

            if (links.isEmpty()) {
                out.add(Component.text("Nessun account collegato sopra "
                        + Math.round(config.linkThreshold) + " punti.", TEXT));
            } else {
                for (Rows.Link link : links) {
                    long otherId = link.aId() == player.id() ? link.bId() : link.aId();
                    Optional<PlayerRef> other = dao.findPlayer(otherId);
                    if (other.isEmpty()) continue;
                    LinkScorer.Result result = scorer.score(dao.evidenceFor(player.id(), otherId), now);
                    boolean whitelisted = dao.isWhitelisted(player.id(), otherId);

                    StringBuilder hover = new StringBuilder();
                    for (LinkScorer.Line line : result.lines()) {
                        hover.append(line.appliedWeight() >= 0 ? "+" : "")
                                .append(Math.round(line.appliedWeight())).append("  ")
                                .append(line.type().description())
                                .append(line.note() == null || line.note().isBlank() ? "" : "  (" + line.note() + ")")
                                .append('\n');
                    }
                    hover.append("\nClicca per generare il dossier completo.");

                    Component row = Component.text(" - ", TEXT)
                            .append(Component.text(other.get().name(), HIGHLIGHT))
                            .append(Component.text("  " + Math.round(link.score()) + "/100 ", scoreColor(link.score())))
                            .append(whitelisted
                                    ? Component.text("[legittimo]", NamedTextColor.GREEN)
                                    : Component.text(result.hasCertainty() ? "[stessa installazione]" : "", NamedTextColor.RED))
                            .hoverEvent(HoverEvent.showText(Component.text(hover.toString().trim(), TEXT)))
                            .clickEvent(ClickEvent.suggestCommand(
                                    "/mg dossier " + player.name() + " " + other.get().name()));
                    out.add(row);
                }
            }
            reply(sender, out);
        });
    }

    private void dossier(CommandSender sender, String[] args) {
        if (args.length < 2) {
            reply(sender, error("Uso: /mg dossier <nickname> [nickname2] [pubblico]"));
            return;
        }
        boolean publicVersion = Arrays.stream(args).anyMatch(a -> a.equalsIgnoreCase("pubblico"));
        String nameA = args[1];
        String nameB = (args.length > 2 && !args[2].equalsIgnoreCase("pubblico")) ? args[2] : null;
        String actor = sender.getName();

        executor.submit("dossier " + nameA, () -> {
            Optional<PlayerRef> a = dao.findPlayerByName(nameA);
            if (a.isEmpty()) {
                reply(sender, error("Nessun account profilato con il nome " + nameA + "."));
                return;
            }
            DossierBuilder.Dossier result;
            if (nameB == null) {
                result = dossier.profile(a.get(), publicVersion, actor);
            } else {
                Optional<PlayerRef> b = dao.findPlayerByName(nameB);
                if (b.isEmpty()) {
                    reply(sender, error("Nessun account profilato con il nome " + nameB + "."));
                    return;
                }
                result = dossier.pair(a.get(), b.get(), publicVersion, actor);
            }
            reply(sender, List.of(
                    title("Dossier generato"),
                    Component.text("File: ", TEXT).append(Component.text(result.file().toString(), HIGHLIGHT)),
                    Component.text("Impronta: ", TEXT).append(Component.text(result.hash().substring(0, 16) + "...", HIGHLIGHT)),
                    Component.text(publicVersion
                                    ? "Versione pubblica: IP mascherati, si puo' allegare al ricorso sul forum."
                                    : "Versione interna: contiene IP completi, non pubblicarla. Aggiungi 'pubblico' per la versione da allegare.",
                            TEXT)));
        });
    }

    private void sessions(CommandSender sender, String[] args) {
        if (args.length < 2) {
            reply(sender, error("Uso: /mg sessions <nickname> [quante]"));
            return;
        }
        String name = args[1];
        int limit = args.length > 2 ? parseInt(args[2], 10) : 10;
        executor.submit("comando sessions " + name, () -> {
            Optional<PlayerRef> target = dao.findPlayerByName(name);
            if (target.isEmpty()) {
                reply(sender, error("Nessun account profilato con il nome " + name + "."));
                return;
            }
            List<Rows.Session> sessions = dao.sessionsOf(target.get().id(), limit);
            List<Component> out = new ArrayList<>();
            out.add(title("Ultimi accessi di " + target.get().name()));
            for (Rows.Session s : sessions) {
                String hover = "IP: " + nvl(s.ip())
                        + "\nRete: " + nvl(s.rdns())
                        + "\nConnesso a: " + nvl(s.hostname())
                        + "\nClient: " + nvl(s.brand())
                        + "\nMod: " + nvl(s.channels())
                        + "\nLingua: " + nvl(s.locale())
                        + "\nDistanza visiva: " + nvl(String.valueOf(s.viewDistance()))
                        + "\nParti skin: " + nvl(String.valueOf(s.skinParts()))
                        + "\nMano: " + nvl(s.mainHand())
                        + "\nResource pack: " + nvl(s.packStatus())
                        + "\nToken installazione: " + nvl(s.cookieToken());
                out.add(Component.text(" - " + Fmt.shortDateTime(s.joinAt()), TEXT)
                        .append(Component.text("  " + nvl(s.brand()) + "  " + nvl(s.locale()), HIGHLIGHT))
                        .hoverEvent(HoverEvent.showText(Component.text(hover, TEXT))));
            }
            if (sessions.isEmpty()) out.add(Component.text("Nessun accesso registrato.", TEXT));
            reply(sender, out);
        });
    }

    private void alerts(CommandSender sender, String[] args) {
        int limit = args.length > 1 ? parseInt(args[1], 10) : 10;
        executor.submit("comando alerts", () -> {
            List<Rows.Alert> alerts = dao.recentAlerts(limit);
            List<Component> out = new ArrayList<>();
            out.add(title("Ultime segnalazioni"));
            for (Rows.Alert a : alerts) {
                Optional<PlayerRef> pa = dao.findPlayer(a.aId());
                Optional<PlayerRef> pb = dao.findPlayer(a.bId());
                if (pa.isEmpty() || pb.isEmpty()) continue;
                out.add(Component.text(" - " + Fmt.shortDateTime(a.createdAt()) + "  ", TEXT)
                        .append(Component.text(pa.get().name() + " / " + pb.get().name(), HIGHLIGHT))
                        .append(Component.text("  " + Math.round(a.score()) + "/100", scoreColor(a.score())))
                        .append(Component.text("  " + a.status(), TEXT))
                        .clickEvent(ClickEvent.suggestCommand(
                                "/mg dossier " + pa.get().name() + " " + pb.get().name())));
            }
            if (alerts.isEmpty()) out.add(Component.text("Nessuna segnalazione registrata.", TEXT));
            reply(sender, out);
        });
    }

    private void link(CommandSender sender, String[] args) {
        if (!sender.hasPermission("magixguard.admin")) {
            reply(sender, error("Serve il permesso magixguard.admin."));
            return;
        }
        if (args.length < 3) {
            reply(sender, error("Uso: /mg link <nickname1> <nickname2> [motivo]"));
            return;
        }
        String reason = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "collegamento manuale";
        withPair(sender, args[1], args[2], (a, b) -> {
            long now = System.currentTimeMillis();
            dao.setLinkManual(a.id(), b.id(), true, 100, now);
            dao.appendAudit(sender.getName(), "LINK_MANUALE", a.name() + " <-> " + b.name(), reason, now);
            reply(sender, Text.msg("&#A8DC2CCollegamento forzato fra &f" + a.name() + " &7e &f" + b.name()
                    + "&7. Resta registrato nel registro firmato."));
        });
    }

    private void unlink(CommandSender sender, String[] args) {
        if (!sender.hasPermission("magixguard.admin")) {
            reply(sender, error("Serve il permesso magixguard.admin."));
            return;
        }
        if (args.length < 3) {
            reply(sender, error("Uso: /mg unlink <nickname1> <nickname2> [motivo]"));
            return;
        }
        String reason = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                : "coppia dichiarata legittima";
        withPair(sender, args[1], args[2], (a, b) -> {
            long now = System.currentTimeMillis();
            dao.addWhitelist(a.id(), b.id(), sender.getName(), reason, now);
            dao.appendAudit(sender.getName(), "WHITELIST", a.name() + " <-> " + b.name(), reason, now);
            reply(sender, Text.msg("&#A8DC2C&f" + a.name() + " &7e &f" + b.name()
                    + " &7sono ora dichiarati legittimi: niente piu' segnalazioni per questa coppia."));
        });
    }

    private void verify(CommandSender sender) {
        executor.submit("verifica registro", () -> {
            GuardDao.ChainCheck check = dao.verifyChain();
            if (check.ok()) {
                reply(sender, List.of(
                        title("Registro integro"),
                        Component.text(check.rows() + " righe verificate: nessuna manomissione.", Help.GREEN),
                        Component.text("Ultima impronta: " + (check.lastHash() == null ? "-"
                                : check.lastHash().substring(0, 16) + "..."), TEXT)));
            } else {
                reply(sender, List.of(
                        title("Registro NON integro"),
                        Component.text("La catena si interrompe alla riga " + check.firstBrokenId()
                                + ": qualcuno ha modificato il database fuori dal plugin.", NamedTextColor.RED)));
            }
        });
    }

    private void stats(CommandSender sender) {
        executor.submit("statistiche", () -> reply(sender, List.of(
                title("MagixGuard"),
                Component.text("Account profilati: ", TEXT).append(Component.text(String.valueOf(dao.count("mg_players")), HIGHLIGHT)),
                Component.text("Accessi registrati: ", TEXT).append(Component.text(String.valueOf(dao.count("mg_sessions")), HIGHLIGHT)),
                Component.text("Indizi raccolti: ", TEXT).append(Component.text(String.valueOf(dao.count("mg_evidence")), HIGHLIGHT)),
                Component.text("Coppie collegate: ", TEXT).append(Component.text(String.valueOf(dao.count("mg_links")), HIGHLIGHT)),
                Component.text("Segnalazioni: ", TEXT).append(Component.text(String.valueOf(dao.count("mg_alerts")), HIGHLIGHT)),
                Component.text("Coppie legittime: ", TEXT).append(Component.text(String.valueOf(dao.count("mg_whitelist")), HIGHLIGHT)),
                Component.text("Soglie: collegamento " + Math.round(config.linkThreshold)
                        + ", segnalazione " + Math.round(config.alertThreshold), TEXT))));
    }

    private void exempt(CommandSender sender, String[] args) {
        if (!sender.hasPermission("magixguard.admin")) {
            reply(sender, error("Serve il permesso magixguard.admin."));
            return;
        }
        if (args.length < 3) {
            reply(sender, error("Uso: /mg exempt <nickname> <on|off>"));
            return;
        }
        boolean on = args[2].equalsIgnoreCase("on");
        String name = args[1];
        executor.submit("exempt " + name, () -> {
            Optional<PlayerRef> target = dao.findPlayerByName(name);
            if (target.isEmpty()) {
                reply(sender, error("Nessun account profilato con il nome " + name + "."));
                return;
            }
            dao.setExempt(target.get().id(), on);
            dao.appendAudit(sender.getName(), on ? "ESCLUSO" : "REINCLUSO", target.get().name(),
                    "profilazione " + (on ? "disattivata" : "riattivata"), System.currentTimeMillis());
            reply(sender, Text.msg("&#A8DC2C&f" + target.get().name() + (on
                    ? " &7non verra' piu' analizzato."
                    : " &7torna sotto analisi.")));
        });
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("magixguard.admin")) {
            reply(sender, error("Serve il permesso magixguard.admin."));
            return;
        }
        plugin.reloadGuard();
        reply(sender, Text.msg("&#A8DC2CConfigurazione ricaricata."));
    }

    /**
     * /mg help [pagina] - l'elenco dei comandi.
     *
     * Le voci stanno in messages.yml (help.sections) e le impagina {@link Help}, la stessa
     * classe degli altri plugin Magix: sezioni, frecce per sfogliare, ogni riga cliccabile per
     * scriversi il comando in chat. La sezione di amministrazione la vede solo chi ha
     * magixguard.admin.
     */
    private void help(CommandSender sender, int page) {
        org.bukkit.configuration.ConfigurationSection h = messages.section("help");
        String title = h != null ? h.getString("title", "MagixGuard") : "MagixGuard";
        Help.show(sender, messages::forPlayer, title, "/mg help",
                Help.fromConfig(messages.section("help.sections"), sender, messages::forPlayer, messages::listForPlayer),
                page, sender.hasPermission("magixguard.admin"));
    }

    // ============================== utilita' ==============================

    private interface PairAction { void run(PlayerRef a, PlayerRef b) throws Exception; }

    private void withPair(CommandSender sender, String nameA, String nameB, PairAction action) {
        executor.submit("coppia " + nameA + "/" + nameB, () -> {
            Optional<PlayerRef> a = dao.findPlayerByName(nameA);
            Optional<PlayerRef> b = dao.findPlayerByName(nameB);
            if (a.isEmpty() || b.isEmpty()) {
                reply(sender, error("Uno dei due nickname non risulta profilato."));
                return;
            }
            if (a.get().id() == b.get().id()) {
                reply(sender, error("Sono lo stesso account."));
                return;
            }
            action.run(a.get(), b.get());
        });
    }

    /** Intestazione di un pannello: niente cartellino davanti, si ripeterebbe in ogni riga. */
    private static Component title(String text) {
        return Text.panel("&#C046E8&l" + text);
    }

    /** Risposta d'errore a un comando: col cartellino davanti, come negli altri plugin Magix. */
    private static Component error(String text) {
        return Text.msg("&#FF6B6B" + text);
    }

    private static NamedTextColor scoreColor(double score) {
        if (score >= 100) return NamedTextColor.RED;
        if (score >= 85) return NamedTextColor.GOLD;
        return NamedTextColor.YELLOW;
    }

    private void reply(CommandSender sender, Component message) {
        reply(sender, List.of(message));
    }

    /** Le risposte tornano sempre sul thread principale: i comandi girano sul thread del database. */
    private void reply(CommandSender sender, List<Component> messages) {
        Bukkit.getScheduler().runTask(plugin, () -> messages.forEach(sender::sendMessage));
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() || s.equals("null") ? "-" : s;
    }

    private static int parseInt(String s, int fallback) {
        try { return Math.max(1, Math.min(200, Integer.parseInt(s))); } catch (NumberFormatException e) { return fallback; }
    }

    // ============================== completamento ==============================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("help", "alts", "dossier", "sessions", "alerts", "link", "unlink",
                    "exempt", "verify", "stats", "reload"), args[0]);
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("alerts") && !args[0].equalsIgnoreCase("help")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("exempt")) {
            return filter(List.of("on", "off"), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("dossier")) {
            return filter(List.of("pubblico"), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }
}
