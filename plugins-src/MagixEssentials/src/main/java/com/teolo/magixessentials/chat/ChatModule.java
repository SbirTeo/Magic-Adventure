package com.teolo.magixessentials.chat;

import com.teolo.magixessentials.util.CmiModules;
import com.teolo.magixessentials.util.TextFormat;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * La chat pubblica, su ogni server della rete: chi scrive, come si legge la riga, i messaggi che
 * arrivano dalla chat del sito.
 *
 * <h2>Come arriva una riga</h2>
 * Ogni messaggio pubblico si intercetta a priorita' {@code HIGH}, quando chi doveva dire la sua ha
 * gia' parlato: MagixGuard toglie i silenziati ({@code LOWEST}) e filtra pubblicita', spam e insulti
 * ({@code LOW}), MagixFactions porta via i messaggi dei canali fazione e alleati e MagixBridge copia
 * sul sito la chat pubblica ({@code NORMAL}). A quel punto l'evento si annulla e la riga si manda a
 * ciascun lettore come messaggio di sistema, composta per lui: il colore della relazione e' quello
 * di chi LEGGE verso chi scrive. E' lo stesso sistema che MagixFactions usava prima (lo si era
 * scelto perche' il render per destinatario di Paper arrivava al client senza formato).
 *
 * <h2>La fazione</h2>
 * Il pezzo di fazione ({faction}, {relcolor}, {rank}) lo da' MagixFactions, se c'e' sullo stesso
 * server, con {@code chatTokens(lettore, mittente)} chiamato per riflessione: i due plugin restano
 * indipendenti e sull'hub, dove MagixFactions non c'e', vale per tutti {@code format}.
 *
 * <h2>CMI</h2>
 * La chat di CMI (formato, colori, filtri, menzioni, fumetti) con questo modulo va spenta: all'avvio
 * si spengono i suoi interruttori nei suoi file (vedi {@link #disableCmiChat}).
 */
public final class ChatModule implements Listener {

    /** Link cliccabili nella chat pubblica. */
    public static final String PERM_LINKS = "magixessentials.chat.links";
    /** Il nome che il permesso aveva in MagixFactions, fino alla 0.58: chi ce l'ha lo tiene. */
    private static final String PERM_LINKS_OLD = "magixfactions.chat.links";

    private static final String DEFAULT_FORMAT = "%luckperms_prefix%&7%magixweb_namecolor%{name} &8» &f{message}";
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.)?[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+(?:/[-a-zA-Z0-9@:%._+~#=?&/]*)?");

    /** I controlli della chat di CMI spenti da questo modulo, file per file (percorsi interi). */
    private static final String[][] CMI_SWITCHES = {
            {"Settings/Chat.yml", "Chat.ModifyChatFormat.Enabled", "Chat.ModifyChatFormat.ClickHoverMessages",
                    "Chat.Colors.PublicMessage.Enabled", "Chat.HoverItems.Enabled", "Chat.Tag.Enabled",
                    "ChatBubble.PublicMessages"},
            {"Settings/ChatFilter.yml", "ChatFilter.Enabled", "ChatFilter.DuplicatedMessagePrevention.Use",
                    "ChatFilter.Caps.Filter", "ChatFilter.SimpleReplacer.Enabled"},
            {"Settings/Modules.yml", "playerChatTag", "chatBubble"},
    };

    private final JavaPlugin plugin;
    private final YamlConfiguration cfg;

    /** chatTokens di MagixFactions, cercato una volta per istanza del plugin (un /reload la cambia). */
    private Plugin tokensOwner;
    private Method tokensMethod;

    public ChatModule(JavaPlugin plugin, YamlConfiguration cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        disableCmiChat();
        plugin.getLogger().info("[Chat] formato della chat pubblica attivo"
                + (Bukkit.getPluginManager().getPlugin("MagixFactions") != null ? " (con le fazioni di MagixFactions)." : "."));
    }

    public void stop() {
        HandlerList.unregisterAll(this);
    }

    // ------------------------------------------------------------------ chat del gioco

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player sender = e.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(e.message());
        List<Audience> viewers = new ArrayList<>(e.viewers());
        e.setCancelled(true);
        for (Audience a : viewers) {
            if (a instanceof Player viewer) {
                viewer.sendMessage(row(viewer, sender, sender.getUniqueId(), sender.getName(), displayName(sender),
                        message, null, false));
            }
        }
        // La console sempre: e' il log della chat. Legge dal punto di vista di chi scrive (la sua
        // fazione in verde), come faceva MagixFactions.
        Bukkit.getConsoleSender().sendMessage(row(sender, sender, sender.getUniqueId(), sender.getName(),
                displayName(sender), message, null, false));
    }

    @SuppressWarnings("deprecation")   // getDisplayName: il nome col soprannome, come scriveva MagixFactions
    private static String displayName(Player p) {
        return p.getDisplayName();
    }

    // ------------------------------------------------------------------ chat del sito

    /**
     * Un messaggio scritto sul sito, a tutti, con lo stesso formato della chat pubblica (e l'icona
     * web-prefix davanti). Chi l'ha scritto di solito non e' in partita: si parte dall'UUID.
     *
     * @param prefix il grado di chi scrive gia' risolto (MagixBridge lo legge dal sito): sostituisce
     *               %luckperms_prefix%, che per un giocatore offline PlaceholderAPI non risolve
     */
    public void broadcastWeb(UUID senderUuid, String senderName, String message, String prefix) {
        Player senderOnline = Bukkit.getPlayer(senderUuid);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.sendMessage(row(viewer, senderOnline, senderUuid, senderName, senderName, message, prefix, true));
        }
        Bukkit.getConsoleSender().sendMessage(
                row(senderOnline, senderOnline, senderUuid, senderName, senderName, message, prefix, true));
    }

    // ------------------------------------------------------------------ la riga

    /**
     * La riga come la legge {@code viewer} (null = nessuno in particolare). Il testo del messaggio
     * entra per ULTIMO e come testo semplice: niente colori ne' placeholder da chi scrive.
     */
    private Component row(Player viewer, Player senderOnline, UUID senderUuid, String senderName, String shownName,
                          String message, String prefix, boolean fromSite) {
        Map<String, String> faction = factionTokens(viewer == null ? null : viewer.getUniqueId(), senderUuid);
        String fmt = faction.isEmpty()
                ? cfg.getString("format", DEFAULT_FORMAT)
                : cfg.getString("faction-format", cfg.getString("format", DEFAULT_FORMAT));
        if (fromSite) fmt = cfg.getString("web-prefix", "") + fmt;
        for (Map.Entry<String, String> t : faction.entrySet()) {
            fmt = fmt.replace("{" + t.getKey() + "}", t.getValue() == null ? "" : t.getValue());
        }
        fmt = fmt.replace("{faction}", "").replace("{relcolor}", "").replace("{rank}", "")
                .replace("{name}", shownName);
        if (prefix != null && !prefix.isEmpty()) fmt = fmt.replace("%luckperms_prefix%", prefix);
        fmt = placeholders(viewer, senderOnline, senderUuid, fmt);

        int at = fmt.indexOf("{message}");
        String before = at < 0 ? fmt : fmt.substring(0, at);
        String after = at < 0 ? "" : fmt.substring(at + "{message}".length());
        Component text = Component.text(message);
        TextColor color = TextFormat.endColor(before);
        if (color != null) text = text.color(color);
        if (senderOnline != null && (senderOnline.hasPermission(PERM_LINKS) || senderOnline.hasPermission(PERM_LINKS_OLD))) {
            text = linkify(text);
        }
        Component row = Component.empty().append(TextFormat.component(before)).append(text);
        if (!after.isEmpty()) row = row.append(TextFormat.component(after));
        return withHover(row, fromSite);
    }

    private static String placeholders(Player viewer, Player senderOnline, UUID senderUuid, String fmt) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null || fmt.indexOf('%') < 0) return fmt;
        if (viewer != null && senderOnline != null && fmt.contains("%rel_")) {
            fmt = me.clip.placeholderapi.PlaceholderAPI.setRelationalPlaceholders(viewer, senderOnline, fmt);
        }
        OfflinePlayer who = senderOnline != null ? senderOnline : Bukkit.getOfflinePlayer(senderUuid);
        return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(who, fmt);
    }

    /** Gli indirizzi nel messaggio diventano cliccabili (apertura nel browser, il link come suggerimento). */
    private static Component linkify(Component text) {
        return text.replaceText(TextReplacementConfig.builder()
                .match(URL_PATTERN)
                .replacement((match, builder) -> {
                    String url = match.group();
                    String target = url.toLowerCase(Locale.ROOT).startsWith("http") ? url : "https://" + url;
                    return builder.clickEvent(ClickEvent.openUrl(target))
                            .hoverEvent(HoverEvent.showText(Component.text(url)));
                })
                .build());
    }

    /** Ora e provenienza nel suggerimento al passaggio del mouse (tooltip.* di chat.yml). */
    private Component withHover(Component row, boolean fromSite) {
        if (!cfg.getBoolean("tooltip.enabled", true)) return row;
        // Nessun testo scritto qui: i valori mancanti sul server li da' il chat.yml del jar (Modules).
        String origin = cfg.getString(fromSite ? "tooltip.web" : "tooltip.game", "");
        String text = cfg.getString("tooltip.format", "{origine}")
                .replace("{ora}", LocalTime.now().format(HH_MM))
                .replace("{origine}", origin);
        return row.hoverEvent(HoverEvent.showText(TextFormat.component(text)));
    }

    // ------------------------------------------------------------------ MagixFactions

    /**
     * {faction}, {relcolor} e {rank} di chi scrive come li vede chi legge, da MagixFactions; vuoto se
     * sul server non c'e' MagixFactions, se chi scrive non ha fazione o se la nasconde.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> factionTokens(UUID viewer, UUID sender) {
        Plugin mf = Bukkit.getPluginManager().getPlugin("MagixFactions");
        if (mf == null || !mf.isEnabled()) return Map.of();
        try {
            if (mf != tokensOwner) {
                tokensOwner = mf;
                tokensMethod = mf.getClass().getMethod("chatTokens", UUID.class, UUID.class);
            }
            Object out = tokensMethod.invoke(mf, viewer, sender);
            return out instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of();
        } catch (NoSuchMethodException e) {
            tokensMethod = null;
            return Map.of();   // MagixFactions piu' vecchio della 0.59: niente fazione in chat
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Map.of();
        }
    }

    // ------------------------------------------------------------------ CMI

    /** Spegne i controlli della chat di CMI nei suoi file (cmi.disable-module), una volta sola. */
    private void disableCmiChat() {
        if (!CmiModules.installed() || !cfg.getBoolean("cmi.disable-module", true)) return;
        List<String> changed = new ArrayList<>();
        for (String[] file : CMI_SWITCHES) {
            String[] paths = new String[file.length - 1];
            System.arraycopy(file, 1, paths, 0, paths.length);
            for (String p : CmiModules.disablePaths(plugin, file[0], paths)) changed.add(file[0] + ": " + p);
        }
        if (!changed.isEmpty()) {
            plugin.getLogger().info("[Chat] spenti i controlli della chat di CMI (copia di scorta in .bak/CMI/Settings/): "
                    + String.join(", ", changed) + ". CMI li rilegge al prossimo riavvio; fino ad allora la chat "
                    + "pubblica la scrive comunque questo modulo.");
        }
    }
}
