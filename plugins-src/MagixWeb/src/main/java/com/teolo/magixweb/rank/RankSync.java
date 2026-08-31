package com.teolo.magixweb.rank;

import com.teolo.magixweb.MagixWeb;
import com.teolo.magixweb.db.Database;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.event.group.GroupDataRecalculateEvent;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Copia nel database del sito il gruppo di permessi LuckPerms di ogni giocatore,
 * insieme al testo e al COLORE del suo prefisso in gioco (es. "&cAdmin " -> "Admin" + #FF5555),
 * cosi' il sito puo' mostrare lo stesso tag con lo stesso colore che si vede in chat.
 *
 * Scrive nella tabella mc_ranks (chiave = uuid), NON in users: cosi' il dato esiste anche
 * per chi non ha ancora collegato l'account, e resta valido se lo collega in seguito.
 */
public class RankSync implements Listener {

    /** Colori "vanilla" della chat Minecraft (&0-&f), gli stessi che il client rende in gioco. */
    private static final Map<Character, String> LEGACY_COLORS = new HashMap<>();
    /** Nomi colore in stile MiniMessage (<red>, <dark_purple>, ...), usati da alcuni setup. */
    private static final Map<String, String> NAMED_COLORS = new HashMap<>();

    static {
        LEGACY_COLORS.put('0', "#000000");
        LEGACY_COLORS.put('1', "#0000AA");
        LEGACY_COLORS.put('2', "#00AA00");
        LEGACY_COLORS.put('3', "#00AAAA");
        LEGACY_COLORS.put('4', "#AA0000");
        LEGACY_COLORS.put('5', "#AA00AA");
        LEGACY_COLORS.put('6', "#FFAA00");
        LEGACY_COLORS.put('7', "#AAAAAA");
        LEGACY_COLORS.put('8', "#555555");
        LEGACY_COLORS.put('9', "#5555FF");
        LEGACY_COLORS.put('a', "#55FF55");
        LEGACY_COLORS.put('b', "#55FFFF");
        LEGACY_COLORS.put('c', "#FF5555");
        LEGACY_COLORS.put('d', "#FF55FF");
        LEGACY_COLORS.put('e', "#FFFF55");
        LEGACY_COLORS.put('f', "#FFFFFF");

        NAMED_COLORS.put("black", "#000000");
        NAMED_COLORS.put("dark_blue", "#0000AA");
        NAMED_COLORS.put("dark_green", "#00AA00");
        NAMED_COLORS.put("dark_aqua", "#00AAAA");
        NAMED_COLORS.put("dark_red", "#AA0000");
        NAMED_COLORS.put("dark_purple", "#AA00AA");
        NAMED_COLORS.put("gold", "#FFAA00");
        NAMED_COLORS.put("gray", "#AAAAAA");
        NAMED_COLORS.put("grey", "#AAAAAA");
        NAMED_COLORS.put("dark_gray", "#555555");
        NAMED_COLORS.put("dark_grey", "#555555");
        NAMED_COLORS.put("blue", "#5555FF");
        NAMED_COLORS.put("green", "#55FF55");
        NAMED_COLORS.put("aqua", "#55FFFF");
        NAMED_COLORS.put("red", "#FF5555");
        NAMED_COLORS.put("light_purple", "#FF55FF");
        NAMED_COLORS.put("yellow", "#FFFF55");
        NAMED_COLORS.put("white", "#FFFFFF");
    }

    /**
     * Trova la PRIMA indicazione di colore in un prefisso, in una qualsiasi delle notazioni in uso:
     * &x&f&f&0&0&0&0 (hex "bungee"), &#ff0000, <#ff0000> / <color:#ff0000> (MiniMessage),
     * <red> (MiniMessage con nome), &c (legacy). Il carattere & vale anche come §.
     */
    private static final Pattern COLOR_TOKEN = Pattern.compile(
            "(?i)[&§]x(?:[&§][0-9a-f]){6}"
                    + "|[&§]#[0-9a-f]{6}"
                    + "|<(?:color:)?#[0-9a-f]{6}>"
                    + "|<[a-z_]{3,20}>"
                    + "|[&§][0-9a-fk-or]");

    private final MagixWeb plugin;
    private final Database database;
    /** Giocatori con una sincronizzazione gia' in coda (vedi scheduleSync). */
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    /** Ultimo colore-nome calcolato per giocatore, letto dal placeholder senza ricalcolare. */
    private final Map<UUID, String> nameColors = new ConcurrentHashMap<>();
    private LuckPerms luckPerms;

    public RankSync(MagixWeb plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    /** @return true se LuckPerms e' presente e la sincronizzazione puo' partire. */
    public boolean hook() {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            plugin.getLogger().warning("LuckPerms non trovato: i tag dei gruppi non verranno sincronizzati col sito.");
            return false;
        }
        try {
            this.luckPerms = LuckPermsProvider.get();
            return true;
        } catch (IllegalStateException e) {
            plugin.getLogger().warning("LuckPerms non ancora pronto: tag dei gruppi non sincronizzati (" + e.getMessage() + ").");
            return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        // Ritardo di 2s: al momento del join LuckPerms puo' non aver ancora ricalcolato i dati
        // (e altri plugin, es. CMI, possono ancora cambiare il gruppo).
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = event.getPlayer();
            if (p.isOnline()) {
                syncPlayer(p);
            }
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        nameColors.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Sync IMMEDIATO: LuckPerms notifica ogni ricalcolo dei dati di un utente (cambio di gruppo,
     * di prefisso, del prefisso di un gruppo che eredita...), quindi il sito si aggiorna nell'istante
     * in cui cambia qualcosa, senza aspettare il giro periodico.
     */
    public void subscribeToChanges() {
        if (luckPerms == null) {
            return;
        }
        luckPerms.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class,
                event -> scheduleSync(event.getUser().getUniqueId()));
        // Creazione/modifica di un gruppo (peso, prefisso, nome): riallinea l'elenco sul sito
        luckPerms.getEventBus().subscribe(plugin, GroupDataRecalculateEvent.class,
                event -> Bukkit.getScheduler().runTaskLater(plugin, this::syncGroups, 20L));
    }

    /**
     * Un solo cambio puo' far scattare piu' ricalcoli di fila: accodo una sola sincronizzazione
     * per giocatore e la eseguo dopo 1 secondo, quando la raffica si e' esaurita.
     */
    private void scheduleSync(UUID uuid) {
        if (!pending.add(uuid)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                syncPlayer(player);
            }
        }, 20L);
    }

    /**
     * Specchia sul sito l'ELENCO dei gruppi del gioco (tabella web_groups): serve al gestionale
     * per assegnare i permessi web gruppo per gruppo, senza doverli ricreare a mano.
     * I gruppi spariti dal gioco vengono rimossi anche qui (i permessi assegnati restano,
     * cosi' ricreando un gruppo con lo stesso nome si ritrova la sua configurazione).
     */
    public void syncGroups() {
        if (luckPerms == null) {
            return;
        }

        List<String[]> rows = new ArrayList<>();
        for (Group g : luckPerms.getGroupManager().getLoadedGroups()) {
            String display = g.getDisplayName() != null && !g.getDisplayName().isBlank()
                    ? g.getDisplayName() : g.getName();
            String color = extractColor(g.getCachedData().getMetaData().getPrefix());
            rows.add(new String[]{g.getName(), display, color, String.valueOf(g.getWeight().orElse(0))});
        }
        if (rows.isEmpty()) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeGroups(rows));
    }

    private void writeGroups(List<String[]> rows) {
        try (Connection c = database.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO web_groups (name, display, color, weight, updated_at) VALUES (?, ?, ?, ?, NOW()) "
                            + "ON DUPLICATE KEY UPDATE display = VALUES(display), color = VALUES(color), "
                            + "weight = VALUES(weight), updated_at = NOW()")) {
                for (String[] row : rows) {
                    ps.setString(1, row[0]);
                    ps.setString(2, row[1]);
                    ps.setString(3, row[2]);
                    ps.setInt(4, Integer.parseInt(row[3]));
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            String placeholders = String.join(",", java.util.Collections.nCopies(rows.size(), "?"));
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM web_groups WHERE name NOT IN (" + placeholders + ")")) {
                for (int i = 0; i < rows.size(); i++) {
                    del.setString(i + 1, rows.get(i)[0]);
                }
                del.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixWeb: errore sincronizzando l'elenco gruppi: " + e.getMessage());
        }
    }

    /** Risincronizza tutti i giocatori online (rete di sicurezza periodica, vedi ranks.sync-interval-minutes). */
    public void syncOnlinePlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            syncPlayer(p);
        }
    }

    public void syncPlayer(Player player) {
        RankInfo info = resolve(player);
        if (info == null) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(info));
    }

    /**
     * Colore del grado piu' alto di un giocatore, in formato "#RRGGBB" (null se non ne ha).
     * Usato dal placeholder %magixweb_namecolor% per colorare il nome in chat come sul sito.
     * Legge dalla cache riempita all'ultimo sync; se manca (giocatore appena entrato) risolve subito.
     */
    public String nameColorOf(Player player) {
        String cached = nameColors.get(player.getUniqueId());
        if (cached != null) {
            return cached;
        }
        RankInfo info = resolve(player);
        return info == null ? null : info.nameColor;
    }

    /** Legge gruppo primario + prefisso dalla cache di LuckPerms (main thread). */
    private RankInfo resolve(Player player) {
        if (luckPerms == null) {
            return null;
        }
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return null;
        }

        QueryOptions options = luckPerms.getContextManager().getQueryOptions(player);

        CachedMetaData meta = user.getCachedData().getMetaData(options);
        String prefix = meta.getPrefix();

        String groupName = user.getPrimaryGroup();
        Group group = luckPerms.getGroupManager().getGroup(groupName);
        String groupDisplay = groupName;
        int weight = 0;
        if (group != null) {
            weight = group.getWeight().orElse(0);
            if (group.getDisplayName() != null && !group.getDisplayName().isBlank()) {
                groupDisplay = group.getDisplayName();
            }
            if (prefix == null || prefix.isBlank()) {
                // Nessun prefisso risolto sull'utente: ripiega su quello del gruppo.
                prefix = group.getCachedData().getMetaData(options).getPrefix();
            }
        }

        // Il prefisso puo' contenere PIU' gradi impilati (meta-formatting di LuckPerms:
        // es. "&cAdmin &6VIP "), quindi lo spezzo in segmenti, uno per ogni cambio di colore.
        List<String[]> segments = splitSegments(prefix);

        // Tutti i gruppi posseduti (non solo il primario): il sito ci calcola sopra i permessi web.
        List<String> groupNames = new ArrayList<>();
        for (Group g : user.getInheritedGroups(options)) {
            groupNames.add(g.getName());
        }

        String nameColor = topGroupColor(user, options, segments);
        if (nameColor != null) {
            nameColors.put(player.getUniqueId(), nameColor);
        } else {
            nameColors.remove(player.getUniqueId());
        }

        return new RankInfo(
                player.getUniqueId().toString(),
                player.getName(),
                groupName,
                groupDisplay,
                segments.isEmpty() ? "" : segments.get(0)[0],
                segments.isEmpty() ? null : segments.get(0)[1],
                toJson(segments),
                nameColor,
                toJsonStrings(groupNames),
                prefix == null ? "" : prefix,
                weight,
                player.getFirstPlayed()
        );
    }

    /**
     * Colore con cui scrivere il NOME del giocatore: quello del grado di peso piu' alto tra
     * TUTTI quelli che ha, track o non track (es. un vip con peso 200 batte un helper con 50).
     * Non e' deducibile dall'ordine del prefisso, che segue invece il meta-formatting.
     */
    private String topGroupColor(User user, QueryOptions options, List<String[]> segments) {
        String color = null;
        int best = Integer.MIN_VALUE;

        for (Group g : user.getInheritedGroups(options)) {
            String groupColor = extractColor(g.getCachedData().getMetaData(options).getPrefix());
            if (groupColor == null) {
                continue; // gruppo senza prefisso (o senza colore): non concorre
            }
            int w = g.getWeight().orElse(0);
            if (w > best) {
                best = w;
                color = groupColor;
            }
        }

        if (color == null && !segments.isEmpty()) {
            color = segments.get(0)[1]; // nessun gruppo utile: ripiego sul primo grado del prefisso
        }
        return color;
    }

    /**
     * Recupera il primo accesso di chi ha GIA' giocato prima che questa colonna esistesse:
     * Bukkit lo sa per ogni giocatore mai entrato (getFirstPlayed), quindi all'avvio si
     * riempiono le righe rimaste vuote. Si tocca solo chi ha first_join NULL, quindi
     * rilanciarlo non cambia nulla.
     */
    public void backfillFirstJoins() {
        int riempiti = 0;
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE mc_ranks SET first_join = ? WHERE mc_uuid = ? AND first_join IS NULL")) {
            for (org.bukkit.OfflinePlayer p : Bukkit.getOfflinePlayers()) {
                long quando = p.getFirstPlayed();
                if (quando <= 0) {
                    continue;
                }
                ps.setTimestamp(1, new java.sql.Timestamp(quando));
                ps.setString(2, p.getUniqueId().toString());
                riempiti += ps.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixWeb: non sono riuscito a recuperare i primi accessi: " + e.getMessage());
            return;
        }
        if (riempiti > 0) {
            plugin.getLogger().info("MagixWeb: primo accesso recuperato per " + riempiti + " giocatori.");
        }
    }

    private void write(RankInfo info) {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     // first_join con COALESCE: si scrive solo se manca, cosi' resta la PRIMA
                     // data conosciuta e non viene sovrascritta a ogni sincronizzazione.
                     "INSERT INTO mc_ranks (mc_uuid, mc_username, group_name, group_display, tag_text, tag_color, tags_json, name_color, groups_json, prefix_raw, weight, updated_at, first_join) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?) "
                             + "ON DUPLICATE KEY UPDATE mc_username = VALUES(mc_username), group_name = VALUES(group_name), "
                             + "group_display = VALUES(group_display), tag_text = VALUES(tag_text), tag_color = VALUES(tag_color), "
                             + "tags_json = VALUES(tags_json), name_color = VALUES(name_color), groups_json = VALUES(groups_json), "
                             + "prefix_raw = VALUES(prefix_raw), "
                             + "weight = VALUES(weight), updated_at = NOW(), "
                             + "first_join = COALESCE(first_join, VALUES(first_join))")) {
            ps.setString(1, info.uuid);
            ps.setString(2, info.username);
            ps.setString(3, info.groupName);
            ps.setString(4, info.groupDisplay);
            ps.setString(5, info.tagText);
            ps.setString(6, info.tagColor);
            ps.setString(7, info.tagsJson);
            ps.setString(8, info.nameColor);
            ps.setString(9, info.groupsJson);
            ps.setString(10, info.prefixRaw);
            ps.setInt(11, info.weight);
            if (info.firstJoin > 0) {
                ps.setTimestamp(12, new java.sql.Timestamp(info.firstJoin));
            } else {
                ps.setNull(12, java.sql.Types.TIMESTAMP);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixWeb: errore sincronizzando il grado di " + info.username + ": " + e.getMessage());
        }
    }

    /**
     * Spezza un prefisso in segmenti [testo, colore], uno per ogni grado impilato.
     * "&cAdmin &6VIP " -> [["Admin","#FF5555"], ["VIP","#FFAA00"]].
     * Un prefisso con un solo grado produce un solo segmento; uno vuoto, nessuno.
     */
    static List<String[]> splitSegments(String prefix) {
        List<String[]> out = new ArrayList<>();
        if (prefix == null || prefix.isEmpty()) {
            return out;
        }

        Matcher m = COLOR_TOKEN.matcher(prefix);
        StringBuilder buffer = new StringBuilder();
        String currentColor = null;
        int last = 0;

        while (m.find()) {
            buffer.append(prefix, last, m.start());
            last = m.end();

            String color = tokenColor(m.group());
            if (color == null) {
                continue; // &l, &o, <bold>...: formattazione, non apre un nuovo grado
            }
            if (!buffer.toString().trim().isEmpty()) {
                out.add(segment(buffer.toString(), currentColor));
                buffer.setLength(0);
            }
            currentColor = color;
        }
        buffer.append(prefix.substring(last));
        if (!buffer.toString().trim().isEmpty()) {
            out.add(segment(buffer.toString(), currentColor));
        }
        return out;
    }

    private static String[] segment(String text, String color) {
        String clean = text.replaceAll("\\s+", " ").trim();
        if (clean.length() > 64) {
            clean = clean.substring(0, 64);
        }
        // Grigio "vanilla" (&7) quando il grado non dichiara nessun colore
        return new String[]{clean, color != null ? color : "#AAAAAA"};
    }

    /** JSON minimale per una lista di stringhe: ["admin","vip","default"]. */
    static String toJsonStrings(List<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escapeJson(values.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    /** JSON minimale (nessuna dipendenza): [{"text":"Admin","color":"#FF5555"}, ...]. */
    static String toJson(List<String[]> segments) {
        if (segments.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"text\":\"").append(escapeJson(segments.get(i)[0]))
                    .append("\",\"color\":\"").append(escapeJson(segments.get(i)[1])).append("\"}");
        }
        return sb.append(']').toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n', '\r', '\t' -> sb.append(' ');
                default -> {
                    if (c < 0x20) {
                        sb.append(' ');
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Colore di un singolo token, o null se il token e' solo formattazione. */
    private static String tokenColor(String token) {
        String lower = token.toLowerCase();

        if (lower.startsWith("<")) {
            String inner = lower.substring(1, lower.length() - 1);
            if (inner.startsWith("color:")) {
                inner = inner.substring(6);
            }
            if (inner.startsWith("#")) {
                return inner.toUpperCase();
            }
            return NAMED_COLORS.get(inner);
        }

        char code = lower.charAt(1);
        if (code == 'x') {
            // &x&f&f&0&0&0&0 -> #FF0000 (una cifra ogni due caratteri)
            StringBuilder hex = new StringBuilder("#");
            for (int i = 3; i < token.length(); i += 2) {
                hex.append(token.charAt(i));
            }
            return hex.toString().toUpperCase();
        }
        if (code == '#') {
            return token.substring(1).toUpperCase();
        }
        return LEGACY_COLORS.get(code);
    }

    /** Estrae il colore del prefisso come "#RRGGBB", oppure null se il prefisso non ne dichiara nessuno. */
    static String extractColor(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return null;
        }
        Matcher m = COLOR_TOKEN.matcher(prefix);
        while (m.find()) {
            String color = tokenColor(m.group());
            if (color != null) {
                return color;
            }
            // &l, &o, <bold> ecc.: formattazione, non colore -> continua a cercare
        }
        return null;
    }

    /** Toglie ogni codice colore/formattazione dal prefisso, lasciando il solo testo (es. "Admin"). */
    static String stripCodes(String prefix) {
        if (prefix == null) {
            return "";
        }
        String out = prefix
                .replaceAll("(?i)[&§]x(?:[&§][0-9a-f]){6}", "")
                .replaceAll("(?i)[&§]#[0-9a-f]{6}", "")
                .replaceAll("(?i)[&§][0-9a-fk-or]", "")
                .replaceAll("</?[a-z_:#0-9]{1,30}>", "")
                .replaceAll("\\s+", " ")
                .trim();
        return out.length() > 64 ? out.substring(0, 64) : out;
    }

    /** Dati di un giocatore pronti per il DB (immutabili, passati al thread async). */
    private static final class RankInfo {
        final String uuid;
        final String username;
        final String groupName;
        final String groupDisplay;
        final String tagText;
        final String tagColor;
        final String tagsJson;
        final String nameColor;
        final String groupsJson;
        /** Prefisso COMPLETO come lo restituisce LuckPerms (codici colore inclusi): serve alla
         *  chat del sito per rendere il grado anche quando chi scrive non e' in partita. */
        final String prefixRaw;
        final int weight;

        /** Primo accesso al server (millisecondi, 0 se Bukkit non lo sa): lo mostra il sito. */
        final long firstJoin;

        RankInfo(String uuid, String username, String groupName, String groupDisplay,
                 String tagText, String tagColor, String tagsJson, String nameColor,
                 String groupsJson, String prefixRaw, int weight, long firstJoin) {
            this.firstJoin = firstJoin;
            this.uuid = uuid;
            this.username = username;
            this.groupName = groupName;
            this.groupDisplay = groupDisplay;
            this.tagText = tagText;
            this.tagColor = tagColor;
            this.tagsJson = tagsJson;
            this.nameColor = nameColor;
            this.groupsJson = groupsJson;
            this.prefixRaw = prefixRaw;
            this.weight = weight;
        }
    }
}
