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
 * Intercetta la chat e la instrada se il giocatore e' su un canale fazione/alleati, o la colora per
 * relazione sul canale pubblico.
 *
 * <p><b>Migrato dall'evento LEGACY {@code AsyncPlayerChatEvent} (2026-07-19):</b> su questa versione
 * del server quell'evento non arriva piu' — ne' a noi ne' a CMI (bug segnalato: la chat restava
 * completamente vanilla, {@code <Nome> testo}, nessun formato di nessuno). Il rimpiazzo moderno di
 * Paper e' {@link AsyncChatEvent}.
 *
 * <p><b>Niente {@code ChatRenderer} (tolto lo stesso giorno):</b> il renderer per-destinatario produceva
 * il testo giusto (verificato nel log console) ma non arrivava formattato al CLIENT reale. Fix:
 * canceliamo SEMPRE l'evento e reinviamo noi il messaggio come system message via
 * {@link ChatService#broadcastPublic}, esattamente come gia' si fa per fazione/alleati con
 * {@link ChatService#route} (che infatti ha sempre funzionato).
 *
 * <p><b>Priorita' LOW, non HIGHEST (fix "messaggio doppio", stesso giorno):</b> il vero colpevole del
 * doppio (e verosimilmente anche del vecchio mistero "il renderer produce il testo giusto ma il client
 * vede vanilla") era CMI: {@code Chat.ModifyChatFormat.ClickHoverMessages} (Priority NORMAL nel suo
 * config) rilancia comunque il messaggio in stile vanilla per aggiungerci hover/click, A PRESCINDERE
 * da cosa facciamo noi con l'evento — MA solo se arriva a lui ancora non cancellato. Girando a
 * {@code LOW} (prima di NORMAL) canceliamo l'evento PRIMA che CMI lo veda, quindi il suo listener
 * (che rispetta la cancellazione, come da convenzione Bukkit) non rilancia piu' nulla — niente piu'
 * bisogno di escludere il mittente dal nostro broadcast (si e' provato: senza alcuna fonte, un
 * giocatore solo in test non vedeva NULLA dei propri messaggi, prova che non esiste un "eco locale"
 * automatico del client — l'unica riga vanilla vista finora era sempre e solo quella di CMI).
 * <b>CMI non va toccato</b> (richiesta esplicita dell'utente) — questo fix agisce solo sul NOSTRO lato.
 *
 * <p><b>Conseguenza (2026-09-23): i link in chat non erano piu' cliccabili.</b> Con l'evento
 * sempre cancellato prima di CMI, {@code ClickHoverMessages} non vede piu' NESSUN messaggio
 * pubblico — e siccome tutte le righe (pubblica, fazione/alleati, sito) le mandiamo noi via
 * {@link org.bukkit.entity.Player#sendMessage(net.kyori.adventure.text.Component)} come
 * messaggio di sistema, senza mai aggiungere un {@code ClickEvent}, un link scritto da un
 * giocatore restava testo semplice per chiunque. Rimesso a posto in {@link ChatService}: chi ha
 * il permesso {@link ChatService#PERM_CLICKABLE_LINKS} manda link cliccabili (apertura URL +
 * suggerimento col link), chi non ce l'ha li manda come testo — CMI resta comunque fuori dai
 * giochi, come richiesto.
 */
public final class ChatListener implements Listener {

    private final ChatService chat;
    private final Messages M;

    public ChatListener(ChatService chat, Messages messages) { this.chat = chat; this.M = messages; }

    /**
     * Il canale scelto sopravvive al riconnessione (la mappa e' per UUID, non per sessione), ma i
     * metadata no: vanno riscritti a ogni ingresso, altrimenti MagixWeb — che legge di li' per
     * capire se un messaggio e' pubblico — vedrebbe "nessun dato" (cioe' pubblico) per un giocatore
     * che invece e' rimasto sul canale della sua fazione, e lo pubblicherebbe sul sito.
     */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        chat.publishChannelMeta(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onChat(AsyncChatEvent e) {
        Player sender = e.getPlayer();
        ChatChannel ch = chat.get(sender.getUniqueId());
        String plainMessage = PlainTextComponentSerializer.plainText().serialize(e.message());

        if (ch == ChatChannel.PUBLIC) {
            if (!chat.relationalFormatEnabled()) return; // lascia fare a CMI/vanilla
            e.setCancelled(true);
            chat.broadcastPublic(sender, plainMessage);
            return;
        }

        e.setCancelled(true);
        boolean ok = chat.route(sender, ch, plainMessage);
        if (!ok) {
            chat.set(sender.getUniqueId(), ChatChannel.PUBLIC);
            sender.sendMessage(M.prefix() + Papi.resolve(sender, M.get("chat.no-faction-public")));
        }
    }
}
