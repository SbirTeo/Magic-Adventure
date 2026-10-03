package com.teolo.magixguard.afk;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * La chat in pausa per chi e' fermo, al livello dei pacchetti: ferma ogni messaggio di chat che
 * il server sta per mandare a quel giocatore, chiunque l'abbia scritto (giocatori, automessaggi
 * di CMI, annunci, chat del sito). Resta fuori solo l'action bar, che non e' chat.
 *
 * <p>Classe a parte perche' tocca ProtocolLib: si carica solo se ProtocolLib c'e' (vedi
 * {@link #install}), cosi' senza di lui MagixGuard parte lo stesso.</p>
 */
final class AfkChatBlock {

    private AfkChatBlock() {
    }

    static void install(JavaPlugin plugin, AfkGuard guard) {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                PacketType.Play.Server.SYSTEM_CHAT, PacketType.Play.Server.DISGUISED_CHAT) {
            @Override
            public void onPacketSending(PacketEvent e) {
                if (e.isPlayerTemporary()) {
                    return;
                }
                Player p = e.getPlayer();
                if (p == null || !guard.chatPaused(p.getUniqueId())) {
                    return;
                }
                if (e.getPacketType() == PacketType.Play.Server.SYSTEM_CHAT) {
                    Boolean overlay = e.getPacket().getBooleans().readSafely(0);
                    if (Boolean.TRUE.equals(overlay)) {
                        return;   // action bar
                    }
                }
                e.setCancelled(true);
            }
        });
    }
}
