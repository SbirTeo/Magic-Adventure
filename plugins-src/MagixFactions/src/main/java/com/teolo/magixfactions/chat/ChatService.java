package com.teolo.magixfactions.chat;

import com.teolo.magixfactions.hook.Papi;
import com.teolo.magixfactions.lang.Messages;
import com.teolo.magixfactions.manage.FactionManager;
import com.teolo.magixfactions.model.Faction;
import com.teolo.magixfactions.model.Member;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Tiene il canale di chat scelto da ogni giocatore e instrada i messaggi. */
public final class ChatService {

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final FactionManager fm;
    private final Messages M;
    private final Map<UUID, ChatChannel> channels = new HashMap<>();

    public ChatService(org.bukkit.plugin.java.JavaPlugin plugin, FactionManager fm, Messages messages) {
        this.plugin = plugin; this.fm = fm; this.M = messages;
    }

    /**
     * Tag del GRADO che {@code uuid} ha dentro la fazione {@code f} (es. {@code **} per il Leader,
     * o il tag configurato in {@code ranks[].tag} / {@code leader.tag}), coi codici colore ancora in
     * forma {@code &} — la traduzione avviene dopo, sull'intero formato. Stringa vuota se il giocatore
     * non risulta membro. Serve al token {@code {rank}} della chat pubblica/web.
     */
    private String rankTag(Faction f, UUID uuid) {
        if (f == null) return "";
        Member m = f.getMember(uuid);
        if (m == null) return "";
        String tag = fm.ranks().resolve(m.getRankId()).getTag();
        return tag != null ? tag : "";
    }

    // ---- Chat PUBBLICA ---------------------------------------------------------------------------
    // Dalla 0.59 la chat pubblica la scrive MagixEssentials (modulo chat, su ogni server della rete):
    // qui resta solo il pezzo che sa di fazioni, che lui chiede con chatTokens (vedi MagixFactions).

    /** Chi ha questo permesso (default op, vedi plugin.yml) appare in chat SENZA tag fazione — pensato
     *  per staff/admin che vogliono un prefisso proprio (es. LuckPerms "Admin") invece del tag fazione. */
    public static final String PERM_HIDE_FACTION = "magixfactions.chat.hidefactions";

    /**
     * I pezzi di fazione di una riga di chat pubblica come li vede {@code viewer} (null = nessuno in
     * particolare, es. la console): {@code faction} (il nome), {@code relcolor} (il colore della
     * relazione del LETTORE verso chi scrive: verde la sua fazione, magenta un'alleata, rosso le
     * altre) e {@code rank} (il tag del grado di chi scrive, nel colore della relazione, non nel suo).
     * Vuota se chi scrive non ha fazione, o la nasconde ({@link #PERM_HIDE_FACTION}, controllabile
     * solo se e' in partita: chi scrive dal sito la mostra).
     */
    public Map<String, String> chatTokens(UUID viewer, UUID sender) {
        if (sender == null) return Map.of();
        Faction fs = fm.getFaction(sender);
        if (fs == null) return Map.of();
        Player senderOnline = Bukkit.getPlayer(sender);
        if (senderOnline != null && senderOnline.hasPermission(PERM_HIDE_FACTION)) return Map.of();
        String relColor = fm.relationColor(viewer == null ? null : fm.getFaction(viewer), fs);
        String rankRaw = rankTag(fs, sender);
        String rank = rankRaw.isEmpty() ? "" : relColor + com.teolo.magixfactions.util.Colors.stripCodes(rankRaw);
        Map<String, String> out = new HashMap<>();
        out.put("faction", fs.getName());
        out.put("relcolor", relColor);
        out.put("rank", rank);
        return out;
    }

    /** Chi ha questo permesso manda link cliccabili (apribili al click) nei canali fazione e alleati; senza, un link resta testo semplice. Nella chat pubblica lo fa MagixEssentials (che accetta anche questo permesso). */
    public static final String PERM_CLICKABLE_LINKS = "magixfactions.chat.links";

    private static final java.util.regex.Pattern URL_PATTERN = java.util.regex.Pattern.compile(
            "(?:https?://)?(?:www\\.)?[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+(?:/[-a-zA-Z0-9@:%._+~#=?&/]*)?");

    /**
     * Rende cliccabili (apertura URL nel browser, col link stesso come suggerimento al passaggio
     * del mouse) gli indirizzi trovati nel testo di {@code row}, SENZA toccare il resto della riga
     * (prefisso, nome, tag fazione restano come sono — colore e stile compresi, ereditati dal punto
     * in cui il link compare).
     */
    private net.kyori.adventure.text.Component linkify(net.kyori.adventure.text.Component row) {
        return row.replaceText(net.kyori.adventure.text.TextReplacementConfig.builder()
                .match(URL_PATTERN)
                .replacement((matchResult, builder) -> {
                    String url = matchResult.group();
                    String target = url.toLowerCase(java.util.Locale.ROOT).startsWith("http") ? url : "https://" + url;
                    return builder
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(target))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                    net.kyori.adventure.text.Component.text(url)));
                })
                .build());
    }

    /**
     * La riga di chat con ORA e PROVENIENZA nascoste nel suggerimento del passaggio del mouse.
     *
     * <p>In chat quelle due informazioni servono di rado, ma quando servono servono: messe in
     * chiaro davanti a ogni riga rubano spazio e rumore (la chat di gioco e' larga poco), messe
     * nel tooltip si vedono solo quando ci passi sopra. E' la stessa scelta fatta sulla chat del
     * sito, dove ora e icona compaiono al passaggio del cursore.
     *
     * <p>Si spegne con {@code chat.hover-info: false}. Il testo del suggerimento e' configurabile
     * ({@code chat.hover-format}, {@code chat.hover-gioco}, {@code chat.hover-web}).
     *
     * @param legacy riga gia' formattata e tradotta (con i codici sezione)
     * @param fromSite true se il messaggio arriva dalla chat del sito
     * @param sender chi ha scritto il messaggio (per {@link #PERM_CLICKABLE_LINKS}); null se non e'
     *               in partita (es. scrive dal sito ed e' offline) — in quel caso niente link cliccabili,
     *               non c'e' un giocatore online su cui controllare il permesso
     * @param reader chi legge la riga: il suggerimento esce nella sua lingua
     */
    private net.kyori.adventure.text.Component withSuggestion(String legacy, boolean fromSite, Player sender,
                                                             Player reader) {
        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer serializer =
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
        net.kyori.adventure.text.Component row = serializer.deserialize(legacy);
        if (sender != null && sender.hasPermission(PERM_CLICKABLE_LINKS)) {
            row = linkify(row);
        }
        if (!plugin.getConfig().getBoolean("chat.hover-info", true)) return row;

        String ora = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        String origine = com.teolo.magixfactions.lang.Messages.phrase(reader, plugin.getConfig().getString(
                fromSite ? "chat.hover-web" : "chat.hover-gioco",
                fromSite ? "&b☁ scritto dal sito" : "&a⛏ scritto in gioco"));
        String text = plugin.getConfig().getString("chat.hover-format", "&7{ora}  &8•  {origine}")
                .replace("{ora}", ora)
                .replace("{origine}", origine);

        return row.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                serializer.deserialize(com.teolo.magixfactions.util.Colors.translate(text))));
    }

    /**
     * Chiave di metadata con cui pubblichiamo il canale corrente del giocatore.
     *
     * <p>Serve a MagixBridge, che specchia la chat sul sito: senza questo, un messaggio scritto sul
     * canale fazione/alleati finirebbe sulla home pubblica. Passa dai metadata invece che da una
     * dipendenza tra i due plugin (che si caricano in ordine non garantito): la chiave e' una
     * semplice stringa, chi legge non ha bisogno di nessuna nostra classe.
     */
    public static final String META_CHANNEL = "magixfactions:chat-channel";

    /** Scrive il canale corrente nei metadata del giocatore (vedi {@link #META_CHANNEL}). */
    public void publishChannelMeta(Player p) {
        if (p == null) return;
        p.setMetadata(META_CHANNEL, new org.bukkit.metadata.FixedMetadataValue(plugin, get(p.getUniqueId()).name()));
    }

    public ChatChannel get(UUID u) { return channels.getOrDefault(u, ChatChannel.PUBLIC); }

    public void set(UUID u, ChatChannel ch) {
        channels.put(u, ch);
        publishChannelMeta(Bukkit.getPlayer(u));
    }

    /** Instrada un messaggio di canale (fazione/alleati). Ritorna false se il giocatore non ha una fazione. */
    public boolean route(Player sender, ChatChannel ch, String message) {
        Faction f = fm.getFaction(sender.getUniqueId());
        if (f == null) return false;

        // Destinatari: sempre la propria fazione; sul canale ALLEATI anche le fazioni alleate.
        Set<UUID> recipients = new HashSet<>(f.getMembers().keySet());
        if (ch == ChatChannel.ALLY) {
            for (Faction ally : fm.alliesOf(f)) recipients.addAll(ally.getMembers().keySet());
        }

        // Il corpo (nome giocatore + testo) e' uguale per tutti; il testo digitato resta LETTERALE (sicurezza).
        String body = Papi.resolve(sender, M.get("chat.body", "player", sender.getName(), "message", "")) + message;
        String prefixKey = ch == ChatChannel.ALLY ? "chat.ally-prefix" : "chat.faction-prefix";
        for (UUID u : recipients) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            // Nome della fazione del mittente colorato con la relazione del DESTINATARIO che legge.
            String facName = fm.relationColor(fm.getFaction(u), f) + f.getName();
            String prefix = Papi.resolve(sender, M.get(p, prefixKey, "faction", facName));
            p.sendMessage(withSuggestion(prefix + body, false, sender, p));
        }
        return true;
    }
}
