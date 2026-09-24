package com.teolo.magixlanguage.api;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Un giocatore ha una lingua nuova (o appena rilevata la prima volta): rilevazione GeoIP al
 * primo ingresso (thread asincrono), o /language set (proprio o di un altro giocatore, thread
 * principale). Un altro plugin che tiene una copia della lingua altrove (es. il sito, come per
 * il rank di LuckPerms) si registra su questo evento invece di interrogare MagixLanguageAPI a
 * ripetizione.
 *
 * <p>Puo' arrivare anche per un giocatore OFFLINE ({@link #getPlayerId()} senza un {@code Player}
 * online): /language set accetta anche chi non e' collegato in quel momento.</p>
 *
 * <p>L'evento e' sincrono o asincrono a seconda di CHI lo genera in quel momento (si legge dal
 * thread vero al momento della creazione, non e' fisso): chi lo ascolta puo' registrarsi con un
 * solo listener e controllare {@link #isAsynchronous()} se gli serve saperlo.</p>
 */
public final class PlayerLanguageChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String language;
    private final boolean manual;

    public PlayerLanguageChangeEvent(UUID playerId, String language, boolean manual) {
        super(!Bukkit.isPrimaryThread());
        this.playerId = playerId;
        this.language = language;
        this.manual = manual;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    /** Codice lingua (it/en/es/de) appena impostato. */
    public String getLanguage() {
        return language;
    }

    /** Vero se scelta a mano (/language set), falso se rilevata da sola (GeoIP al primo ingresso). */
    public boolean isManual() {
        return manual;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
