package com.teolo.magixessentials.currency;

import com.teolo.magixessentials.currency.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Saldi CONDIVISI su tutta la rete (currencies.yml, "shared: true"): il server sta dietro
 * Velocity con piu' backend (hub, factions...), quindi il saldo vive nel database, non in un
 * file che un backend solo potrebbe leggere. Ogni operazione gira su un thread suo, mai sul tick
 * del server, e il risultato arriva nel {@link CompletableFuture}.
 *
 * <p>{@code give} e' l'unica operazione che tocca DUE righe: lo fa dentro una transazione con
 * blocco di riga ({@code SELECT ... FOR UPDATE}), bloccate sempre nello stesso ordine (per UUID)
 * cosi' due /give opposti fra la stessa coppia di giocatori non si bloccano a vicenda — il
 * blocco di riga serve davvero qui, perche' l'altro backend puo' scrivere nello stesso istante:
 * non basta serializzare dentro questo processo solo.</p>
 */
final class SharedCurrencyStore implements CurrencyStore {

    private static final String TABLE = "me_currency_balances";

    private final Database db;
    private final Logger logger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MagixEssentials-Currency-DB");
        t.setDaemon(true);
        return t;
    });

    SharedCurrencyStore(Database db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public CompletableFuture<Long> balance(Currency currency, UUID player) {
        return supply("leggere il saldo di " + currency.id(), () -> {
            try (Connection c = db.getConnection()) {
                return select(c, currency.id(), player).orElse(currency.startingBalance());
            }
        });
    }

    @Override
    public CompletableFuture<Long> add(Currency currency, UUID player, long delta) {
        return supply("aggiornare il saldo di " + currency.id(), () -> {
            try (Connection c = db.getConnection()) {
                long current = select(c, currency.id(), player).orElse(currency.startingBalance());
                long updated = Math.max(0, current + delta);
                upsert(c, currency.id(), player, updated);
                return updated;
            }
        });
    }

    @Override
    public CompletableFuture<Long> set(Currency currency, UUID player, long amount) {
        return supply("impostare il saldo di " + currency.id(), () -> {
            try (Connection c = db.getConnection()) {
                upsert(c, currency.id(), player, amount);
                return amount;
            }
        });
    }

    @Override
    public CompletableFuture<GiveOutcome> give(Currency currency, UUID from, UUID to, long amount) {
        return supply("spostare " + currency.id() + " fra giocatori", () -> {
            try (Connection c = db.getConnection()) {
                c.setAutoCommit(false);
                try {
                    // Sempre lo stesso ordine (per UUID), non quello della chiamata: cosi' un
                    // /give A->B e un /give B->A nello stesso istante non si bloccano a vicenda.
                    UUID first = from.compareTo(to) <= 0 ? from : to;
                    UUID second = first.equals(from) ? to : from;
                    long balFirst = selectForUpdate(c, currency.id(), first, currency.startingBalance());
                    long balSecond = selectForUpdate(c, currency.id(), second, currency.startingBalance());
                    long senderBalance = first.equals(from) ? balFirst : balSecond;
                    long targetBalance = first.equals(from) ? balSecond : balFirst;

                    if (senderBalance < amount) {
                        c.rollback();
                        return new GiveOutcome(false, senderBalance, targetBalance);
                    }
                    long senderLeft = senderBalance - amount;
                    long targetNow = targetBalance + amount;
                    upsert(c, currency.id(), from, senderLeft);
                    upsert(c, currency.id(), to, targetNow);
                    c.commit();
                    return new GiveOutcome(true, senderLeft, targetNow);
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            }
        });
    }

    // ------------------------------------------------------------------ SQL

    private static java.util.Optional<Long> select(Connection c, String currencyId, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT balance FROM " + TABLE + " WHERE currency_id = ? AND player_uuid = ?")) {
            ps.setString(1, currencyId);
            ps.setString(2, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? java.util.Optional.of(rs.getLong(1)) : java.util.Optional.empty();
            }
        }
    }

    /** Come {@link #select}, ma blocca la riga (o il punto dove nascerebbe) fino al commit/rollback. */
    private static long selectForUpdate(Connection c, String currencyId, UUID player, long startingBalance)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT balance FROM " + TABLE + " WHERE currency_id = ? AND player_uuid = ? FOR UPDATE")) {
            ps.setString(1, currencyId);
            ps.setString(2, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : startingBalance;
            }
        }
    }

    private static void upsert(Connection c, String currencyId, UUID player, long balance) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + TABLE + " (currency_id, player_uuid, balance) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE balance = VALUES(balance)")) {
            ps.setString(1, currencyId);
            ps.setString(2, player.toString());
            ps.setLong(3, balance);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ appoggio

    @FunctionalInterface
    private interface SqlSupplier<T> { T get() throws SQLException; }

    private <T> CompletableFuture<T> supply(String what, SqlSupplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.submit(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                logger.warning("[Valute] errore nel database per " + what + ": " + t.getMessage());
                future.completeExceptionally(t);
            }
        });
        return future;
    }
}
