package com.teolo.magixguard.db;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Un unico thread seriale per tutte le scritture e le analisi sul database: non tocca mai il
 * tick del server e mantiene l'ordine delle operazioni (una sessione viene chiusa dopo essere
 * stata aperta, un'analisi gira dopo che la sessione e' stata scritta).
 */
public final class DbExecutor {

    private final ExecutorService executor;
    private final Logger logger;

    public DbExecutor(Logger logger) {
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MagixGuard-DB");
            t.setDaemon(true);
            return t;
        });
    }

    public void submit(String what, ThrowingRunnable task) {
        executor.submit(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                logger.log(Level.WARNING, "MagixGuard: errore durante " + what + ": " + t.getMessage(), t);
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable { void run() throws Exception; }
}
