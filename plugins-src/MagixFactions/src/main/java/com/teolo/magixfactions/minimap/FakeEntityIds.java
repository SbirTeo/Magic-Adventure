package com.teolo.magixfactions.minimap;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Genera ID di entita' "fittizie" (mai spawnate come vere Entity Bukkit, solo pacchetti) che non
 * collidano con gli ID reali assegnati da Bukkit al mondo. Bukkit assegna ID interi CRESCENTI da un
 * contatore interno per-server: partendo da un valore molto alto e DECRESCENDO, la probabilita'
 * pratica di collisione e' trascurabile per qualunque sessione di gioco reale — ma non e' una
 * garanzia matematica assoluta, e va trattata come tale (rischio accettato a bassa probabilita'),
 * non come certezza.
 */
final class FakeEntityIds {

    private static final AtomicInteger NEXT = new AtomicInteger(2_000_000_000);

    private FakeEntityIds() {}

    static int next() {
        return NEXT.decrementAndGet();
    }
}
