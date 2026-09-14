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

    // ---- Chat PUBBLICA relazionale ---------------------------------------------------------------
    // CMI non puo' risolvere i placeholder relazionali (%rel_...%): il suo jar non contiene
    // setRelationalPlaceholders (verificato). Quindi il formato pubblico "colorato per relazione"
    // lo rendiamo NOI, messaggio per messaggio e DESTINATARIO per destinatario (vedi ChatListener).

    /** Config {@code chat.relational-format}: se attivo, la chat PUBBLICA e' formattata da noi. */
    public boolean relationalFormatEnabled() {
        return plugin.getConfig().getBoolean("chat.relational-format", true);
    }

    /** Chi ha questo permesso (default op, vedi plugin.yml) appare in chat SENZA tag fazione — pensato
     *  per staff/admin che vogliono un prefisso proprio (es. LuckPerms "Admin") invece del tag fazione. */
    public static final String PERM_HIDE_FACTION = "magixfactions.chat.hidefactions";

    /**
     * Riga di chat pubblica come la vede {@code viewer}: tag fazione + nome del mittente colorati con
     * la relazione del LETTORE verso il mittente (verde = tua fazione, magenta = alleato, rosso =
     * nemico), formato da config {@code chat.public-format} (o {@code -no-faction}). Il formato puo'
     * contenere QUALSIASI placeholder di PlaceholderAPI (es. %vault_prefix%, %luckperms_prefix%,
     * %player_ping%...), risolto nel contesto del MITTENTE (e' lui che il formato descrive) — cosi'
     * si puo' usare CMI/LuckPerms per prefissi/gradi e lasciare a noi solo la parte relazionale.
     * Il testo digitato resta SEMPRE letterale: {message} viene sostituito per ULTIMO, DOPO sia la
     * risoluzione PAPI sia la traduzione colori, quindi il giocatore non puo' iniettare placeholder
     * ne' codici colore nel proprio messaggio.
     *
     * <p>Se il mittente ha {@link #PERM_HIDE_FACTION}, il tag fazione viene ignorato (usa
     * {@code public-format-no-faction} anche se e' in una fazione) — pensato per lo staff, che puo'
     * mostrare invece un proprio prefisso (es. %luckperms_prefix%) via config.
     */
    public String formatPublicFor(Player viewer, Player sender, String message) {
        Faction fs = fm.getFaction(sender.getUniqueId());
        boolean showFaction = fs != null && !sender.hasPermission(PERM_HIDE_FACTION);
        String relColor = fm.relationColor(fm.getFaction(viewer.getUniqueId()), fs);
        String fmt = showFaction
                ? plugin.getConfig().getString("chat.public-format", "&8[{rank}{relcolor}{faction}&8] {relcolor}{name}&7: &f{message}")
                : plugin.getConfig().getString("chat.public-format-no-faction", "&7{name}&7: &f{message}");
        // Il tag del grado prende SEMPRE il colore della RELAZIONE (come [fazione] e nome): togliamo
        // il colore proprio del tag e gli mettiamo davanti relColor. Cosi' ** e' verde/rosso/magenta,
        // non il giallo del config.
        String rankRaw = showFaction ? rankTag(fs, sender.getUniqueId()) : "";
        String rankPart = rankRaw.isEmpty() ? "" : relColor + com.teolo.magixfactions.util.Colors.stripCodes(rankRaw);
        fmt = fmt.replace("{rank}", rankPart)
                .replace("{relcolor}", relColor)
                .replace("{faction}", showFaction ? fs.getName() : "")
                .replace("{name}", sender.getDisplayName());
        fmt = Papi.resolve(sender, fmt); // qualsiasi %placeholder% di PAPI, nel contesto del mittente
        String head = com.teolo.magixfactions.util.Colors.translate(fmt);
        return head.replace("{message}", message);
    }

    /**
     * Manda la chat pubblica a OGNI giocatore online, incluso il mittente, uno per uno, gia' formattata
     * col SUO colore di relazione verso il mittente — come messaggio di SISTEMA
     * ({@link Player#sendMessage(String)}, non firmato). Necessario perche' {@code AsyncChatEvent.renderer()}
     * produce il testo giusto (verificato nel log console) ma non arriva formattato al CLIENT reale.
     * Inviare come messaggio di sistema bypassa del tutto firma/render locale — lo stesso trucco che
     * {@link #route} usa gia' per fazione/alleati (dove infatti il formato arriva sempre correttamente).
     *
     * <p><b>Il mittente NON va escluso</b> (era stato escluso il 2026-07-19 per un fix sbagliato: si
     * pensava esistesse un "eco locale" lato client non sopprimibile che duplicava il messaggio — FALSO,
     * verificato empiricamente: la riga vanilla in piu' era CMI ({@code ClickHoverMessages}, vedi
     * {@link ChatListener} sulla priorita' LOW), non un eco del client. Con l'evento cancellato PRIMA
     * che CMI lo veda, non c'e' piu' nessuna fonte doppia — il mittente va incluso come chiunque altro.
     */
    /**
     * Riga di chat per un messaggio scritto dal SITO: stesso identico formato della chat
     * pubblica ({@link #formatPublicFor}) — prefisso del grado, {@code [fazione]} e nome
     * colorati con la relazione del LETTORE — preceduto dall'icona web (config
     * {@code chat.web-prefix}).
     *
     * <p>Il mittente qui NON e' per forza online (scrive dal browser): si parte dall'UUID,
     * la fazione arriva comunque dalla cache di {@link FactionManager} e i placeholder si
     * risolvono con l'overload OfflinePlayer di {@link Papi}. Il testo digitato resta
     * LETTERALE come nella chat normale: {@code {message}} si sostituisce per ULTIMO.
     */
    public String formatWebFor(Player viewer, UUID senderUuid, String senderName, String message, String prefix) {
        org.bukkit.OfflinePlayer sender = Bukkit.getOfflinePlayer(senderUuid);
        Faction fs = fm.getFaction(senderUuid);
        Player senderOnline = Bukkit.getPlayer(senderUuid);
        // Il permesso "nascondi fazione" si puo' leggere solo se il mittente e' in partita;
        // da offline si mostra la fazione (caso normale per chi scrive dal sito).
        boolean showFaction = fs != null && (senderOnline == null || !senderOnline.hasPermission(PERM_HIDE_FACTION));
        // viewer null = riga per la console (nessuna fazione che legge): stessa resa che vede
        // un giocatore senza fazione, cioe' relationColor(null, ...) -> "enemy".
        String relColor = fm.relationColor(viewer == null ? null : fm.getFaction(viewer.getUniqueId()), fs);
        String fmt = showFaction
                ? plugin.getConfig().getString("chat.public-format", "&8[{rank}{relcolor}{faction}&8] {relcolor}{name}&7: &f{message}")
                : plugin.getConfig().getString("chat.public-format-no-faction", "&7{name}&7: &f{message}");
        fmt = plugin.getConfig().getString("chat.web-prefix", "&b☁ ") + fmt;
        // Il nome ha SEMPRE un colore esplicito nel formato (&7 grigio: solo il TAG fazione e' colorato
        // per relazione, il nome del giocatore no), quindi non serve piu' iniettare {relcolor} sul nome
        // per evitare che erediti il celeste dell'icona web — vecchio workaround, ora obsoleto.
        // Il tag del grado fazione ({rank}) si legge dalla cache di FactionManager, che c'e' anche per un
        // mittente OFFLINE (chi scrive dal sito): %magixfactions_rank% via PAPI invece qui non risolverebbe.
        // Come nella chat pubblica, il tag prende il colore della RELAZIONE (togliamo il suo colore proprio).
        String rankRaw = showFaction ? rankTag(fs, senderUuid) : "";
        String rankPart = rankRaw.isEmpty() ? "" : relColor + com.teolo.magixfactions.util.Colors.stripCodes(rankRaw);
        fmt = fmt.replace("{rank}", rankPart)
                .replace("{relcolor}", relColor)
                .replace("{faction}", showFaction ? fs.getName() : "")
                .replace("{name}", senderName);
        // Grado di chi scrive: per un giocatore OFFLINE PlaceholderAPI non risolve
        // %luckperms_prefix% (l'utente non e' caricato), quindi chi chiama ci passa il
        // prefisso che ha gia' (MagixWeb lo legge da mc_ranks) e lo sostituiamo prima.
        if (prefix != null && !prefix.isEmpty()) {
            fmt = fmt.replace("%luckperms_prefix%", prefix);
        }
        fmt = Papi.resolve(sender, fmt);
        String head = com.teolo.magixfactions.util.Colors.translate(fmt);
        return head.replace("{message}", message);
    }

    /**
     * API usata da MagixWeb (via reflection sulla classe principale, cosi' i due plugin
     * restano indipendenti): manda a tutti un messaggio scritto dal sito, gia' formattato
     * per ciascun destinatario con il SUO colore di relazione verso il mittente.
     */
    public void broadcastWeb(UUID senderUuid, String senderName, String message, String prefix) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.sendMessage(withSuggestion(formatWebFor(viewer, senderUuid, senderName, message, prefix), true));
        }
        // Console: se chi ha scritto e' anche in partita si usa il suo punto di vista, altrimenti
        // nessuno (viewer null) — la riga resta comunque quella vera, fazione e grado compresi.
        Bukkit.getConsoleSender().sendMessage(
                formatWebFor(Bukkit.getPlayer(senderUuid), senderUuid, senderName, message, prefix));
    }

    public void broadcastPublic(Player sender, String message) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.sendMessage(withSuggestion(formatPublicFor(viewer, sender, message), false));
        }
        Bukkit.getConsoleSender().sendMessage(formatPublicFor(sender, sender, message));
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
     * @param dalSito true se il messaggio arriva dalla chat del sito
     */
    private net.kyori.adventure.text.Component withSuggestion(String legacy, boolean fromSite) {
        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer serializer =
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
        net.kyori.adventure.text.Component row = serializer.deserialize(legacy);
        if (!plugin.getConfig().getBoolean("chat.hover-info", true)) return row;

        String ora = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        String origine = plugin.getConfig().getString(
                fromSite ? "chat.hover-web" : "chat.hover-gioco",
                fromSite ? "&b☁ scritto dal sito" : "&a⛏ scritto in gioco");
        String text = plugin.getConfig().getString("chat.hover-format", "&7{ora}  &8•  {origine}")
                .replace("{ora}", ora)
                .replace("{origine}", origine);

        return row.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                serializer.deserialize(com.teolo.magixfactions.util.Colors.translate(text))));
    }

    /**
     * Chiave di metadata con cui pubblichiamo il canale corrente del giocatore.
     *
     * <p>Serve a MagixWeb, che specchia la chat sul sito: senza questo, un messaggio scritto sul
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
            String prefix = Papi.resolve(sender, M.get(prefixKey, "faction", facName));
            p.sendMessage(withSuggestion(prefix + body, false));
        }
        return true;
    }
}
