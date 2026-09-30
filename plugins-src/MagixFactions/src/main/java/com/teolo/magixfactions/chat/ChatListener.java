package com.teolo.magixfactions.chat;

import com.teolo.magixfactions.hook.Papi;
import com.teolo.magixfactions.lang.Messages;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * I canali di fazione e alleati (/f chat): un messaggio scritto su uno dei due non e' chat pubblica,
 * va solo ai membri (e agli alleati), e qui si annulla l'evento e lo si instrada.
 *
 * <p>La chat PUBBLICA non passa piu' di qui (dalla 0.59): la scrive MagixEssentials (modulo chat, su
 * ogni server della rete), che chiede a MagixFactions solo i pezzi di fazione della riga
 * ({@link ChatService#chatTokens}).
 *
 * <p><b>Priorita' NORMAL:</b> dopo MagixGuard (silenziati a LOWEST, filtro a LOW: un messaggio di
 * canale passa dal filtro come gli altri) e prima di MagixEssentials, che scrive la chat pubblica a
 * HIGH. Un messaggio di canale annullato qui non arriva ne' a lui ne' al sito (MagixBridge salta gli
 * eventi annullati e comunque legge il canale dai metadata).
 *
 * <p>Storia: l'evento legacy {@code AsyncPlayerChatEvent} su questa versione del server non arriva
 * piu' (2026-07-19), e il render per destinatario di Paper non arrivava formattato al client: per
 * questo il messaggio si annulla e si rimanda a ciascuno come messaggio di sistema.
 */
public final class ChatListener implements Listener {

    private final ChatService chat;
    private final Messages M;

    public ChatListener(ChatService chat, Messages messages) { this.chat = chat; this.M = messages; }

    /**
     * Il canale scelto sopravvive al riconnessione (la mappa e' per UUID, non per sessione), ma i
     * metadata no: vanno riscritti a ogni ingresso, altrimenti MagixBridge — che legge di li' per
     * capire se un messaggio e' pubblico — vedrebbe "nessun dato" (cioe' pubblico) per un giocatore
     * che invece e' rimasto sul canale della sua fazione, e lo pubblicherebbe sul sito.
     */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        chat.publishChannelMeta(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onChat(AsyncChatEvent e) {
        Player sender = e.getPlayer();
        ChatChannel ch = chat.get(sender.getUniqueId());
        if (ch == ChatChannel.PUBLIC) return;   // la chat pubblica e' di MagixEssentials
        String plainMessage = PlainTextComponentSerializer.plainText().serialize(e.message());

        e.setCancelled(true);
        boolean ok = chat.route(sender, ch, plainMessage);
        if (!ok) {
            chat.set(sender.getUniqueId(), ChatChannel.PUBLIC);
            sender.sendMessage(Papi.resolve(sender, M.get(sender, "chat.no-faction-public")));
        }
    }
}
