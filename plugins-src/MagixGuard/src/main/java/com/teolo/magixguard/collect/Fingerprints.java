package com.teolo.magixguard.collect;

import com.teolo.magixguard.model.SessionSnapshot;
import com.teolo.magixguard.util.Hashing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Impronta del client.
 *
 * Nessuno di questi campi, da solo, identifica qualcuno: la lingua e' it_it per quasi tutti,
 * la distanza visiva ha una decina di valori comuni. La loro COMBINAZIONE invece genera
 * migliaia di varianti, e la lista delle mod dichiarate dal client (canali plugin) e' spesso
 * unica quanto un'impronta digitale.
 */
public final class Fingerprints {

    private Fingerprints() {}

    /**
     * Elenco ordinato e normalizzato dei canali plugin: l'ordine con cui il client li registra
     * non e' stabile fra un avvio e l'altro, quindi va ordinato prima di confrontarlo.
     */
    public static String normalizeChannels(Set<String> channels) {
        if (channels == null || channels.isEmpty()) return null;
        Set<String> sorted = new TreeSet<>();
        for (String ch : channels) {
            if (ch == null || ch.isBlank()) continue;
            sorted.add(ch.toLowerCase());
        }
        return sorted.isEmpty() ? null : String.join(",", sorted);
    }

    /**
     * Impronta complessiva della sessione. Volutamente NON include l'IP: serve a riconoscere
     * la stessa installazione di gioco anche quando cambia rete (VPN, hotspot, casa di un amico).
     */
    public static String build(SessionSnapshot s, Hashing hashing) {
        List<String> parts = new ArrayList<>();
        parts.add(nvl(s.brand));
        parts.add(nvl(s.channels));
        parts.add(nvl(s.locale));
        parts.add(s.viewDistance == null ? "-" : String.valueOf(s.viewDistance));
        parts.add(s.skinParts == null ? "-" : String.valueOf(s.skinParts));
        parts.add(nvl(s.mainHand));
        parts.add(nvl(s.chatFlags));
        // Se non abbiamo raccolto nulla di significativo non inventiamo un'impronta:
        // un hash di soli trattini accomunerebbe tutti i client muti.
        if (nvl(s.brand).equals("-") && nvl(s.channels).equals("-") && s.skinParts == null) return null;
        return hashing.hmac(String.join("|", parts));
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
