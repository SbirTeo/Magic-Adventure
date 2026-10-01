package com.teolo.magixessentials.currency;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Dove vive il saldo di una valuta: {@link LocalCurrencyStore} (file sul server, un'economia per
 * server) o {@link SharedCurrencyStore} (database, condiviso su tutta la rete dietro Velocity).
 * {@link CurrencyCommand} non sa quale delle due sta usando — lo decide {@link Currency#shared()}
 * — e tratta sempre il risultato come asincrono: il database puo' metterci un istante, il file
 * no, ma il codice che chiama e' lo stesso.
 */
interface CurrencyStore {

    /** Il saldo di {@code player} per questa valuta, o {@code startingBalance} se non l'ha mai vista. */
    CompletableFuture<Long> balance(Currency currency, UUID player);

    /** Somma {@code delta} (negativo per togliere) al saldo, senza farlo scendere sotto zero. Torna il nuovo saldo. */
    CompletableFuture<Long> add(Currency currency, UUID player, long delta);

    /** Fissa il saldo a {@code amount}. Torna {@code amount}. */
    CompletableFuture<Long> set(Currency currency, UUID player, long amount);

    /**
     * Sposta {@code amount} da {@code from} a {@code to}, atomicamente: o tolto e dato insieme, o
     * niente (saldo insufficiente). Vedi {@link GiveOutcome}.
     */
    CompletableFuture<GiveOutcome> give(Currency currency, UUID from, UUID to, long amount);
}
