package com.teolo.magixessentials.currency;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Saldi LOCALI a questo server, su {@code balances.yml} (vedi {@link Balances}). Ogni operazione
 * e' gia' finita quando il {@link CompletableFuture} viene creato — e' solo I/O su file, niente
 * rete — ma la forma resta asincrona come {@link SharedCurrencyStore}, cosi' {@link CurrencyCommand}
 * non deve sapere quale delle due valute sta usando.
 */
final class LocalCurrencyStore implements CurrencyStore {

    private final Balances balances;

    LocalCurrencyStore(Balances balances) {
        this.balances = balances;
    }

    @Override
    public CompletableFuture<Long> balance(Currency currency, UUID player) {
        return CompletableFuture.completedFuture(
                balances.get(currency.id(), player, currency.startingBalance()));
    }

    @Override
    public CompletableFuture<Long> add(Currency currency, UUID player, long delta) {
        synchronized (balances) {
            long updated = Math.max(0, balances.get(currency.id(), player, currency.startingBalance()) + delta);
            balances.set(currency.id(), player, updated);
            return CompletableFuture.completedFuture(updated);
        }
    }

    @Override
    public CompletableFuture<Long> set(Currency currency, UUID player, long amount) {
        balances.set(currency.id(), player, amount);
        return CompletableFuture.completedFuture(amount);
    }

    @Override
    public CompletableFuture<GiveOutcome> give(Currency currency, UUID from, UUID to, long amount) {
        synchronized (balances) {
            long senderBalance = balances.get(currency.id(), from, currency.startingBalance());
            if (senderBalance < amount) {
                long targetBalance = balances.get(currency.id(), to, currency.startingBalance());
                return CompletableFuture.completedFuture(new GiveOutcome(false, senderBalance, targetBalance));
            }
            long senderLeft = senderBalance - amount;
            long targetNow = balances.get(currency.id(), to, currency.startingBalance()) + amount;
            balances.set(currency.id(), from, senderLeft);
            balances.set(currency.id(), to, targetNow);
            return CompletableFuture.completedFuture(new GiveOutcome(true, senderLeft, targetNow));
        }
    }
}
