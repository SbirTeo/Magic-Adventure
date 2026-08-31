package com.teolo.magixguard.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tutti i segnali raccolti per un singolo accesso.
 *
 * Viene creato al join con i soli dati di rete (gli unici disponibili subito) e completato
 * qualche secondo dopo con le impostazioni del client, che arrivano dopo il login.
 */
public final class SessionSnapshot {

    public long sessionId = -1;
    public long playerId = -1;
    public UUID uuid;
    public String name;

    public long joinAt;
    public Long quitAt;

    // --- rete ---
    public String ip;
    public String ipHash;
    public String subnet;
    public String subnetHash;
    public String rdns;
    public String rdnsHash;
    /** Indirizzo con cui il giocatore si e' connesso (handshake): dominio, sottodominio o IP nudo. */
    public String hostname;

    // --- client ---
    public String brand;
    public String channels;
    public String channelsHash;
    public String locale;
    public Integer viewDistance;
    public Integer skinParts;
    public String mainHand;
    public String chatFlags;
    public String fingerprint;

    // --- resource pack e latenza ---
    public String packStatus;
    public Integer packMs;
    public Integer pingMedian;

    /** Token persistente letto/scritto nel client (API cookie). */
    public String cookieToken;

    public final List<Integer> pingSamples = new ArrayList<>();

    public Integer computePingMedian() {
        if (pingSamples.isEmpty()) return null;
        List<Integer> sorted = new ArrayList<>(pingSamples);
        sorted.sort(Integer::compareTo);
        return sorted.get(sorted.size() / 2);
    }
}
