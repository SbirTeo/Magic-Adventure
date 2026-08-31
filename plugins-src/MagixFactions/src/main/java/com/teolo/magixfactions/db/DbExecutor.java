package com.teolo.magixfactions.db;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Esecutore SERIALE per le scritture al database "fire-and-forget".
 *
 * <p>Perche' un unico thread invece del pool asincrono di Bukkit: le scritture qui sotto rispecchiano
 * su disco uno stato che e' GIA' aggiornato in cache sul main thread; devono solo non bloccare il tick.
 * Un solo thread garantisce che vengano applicate NELLO STESSO ORDINE in cui sono state richieste
 * (il pool di Bukkit non lo garantisce), evitando race del tipo "UPDATE piu' vecchio applicato dopo uno
 * piu' nuovo". Con SQLite (pool Hikari da 1 connessione) e' anche l'unico modo per non contendere la
 * singola connessione fra piu' task paralleli. I valori da scrivere vengono catturati sul main thread
 * PRIMA di accodare il task, quindi qui non si tocca mai una struttura Bukkit ne' la cache condivisa.
 */
public final class DbExecutor {

    private final ExecutorService pool;
    private final Logger log;

    public DbExecutor(Logger log) {
        this.log = log;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "MagixFactions-DB");
            t.setDaemon(true);
            return t;
        };
        // Executors.newSingleThreadExecutor() ritorna un wrapper (AutoShutdownDelegatedExecutorService),
        // NON un ThreadPoolExecutor: non castarlo (ClassCastException a runtime, non un errore di compilazione).
        // Bastano i metodi di ExecutorService (execute/shutdown/awaitTermination/shutdownNow), gia' tutti qui usati.
        this.pool = Executors.newSingleThreadExecutor(tf);
    }

    /** Accoda una scrittura. Se l'esecutore e' gia' chiuso (onDisable), la esegue subito (sincrona). */
    public void submit(Runnable r) {
        try {
            pool.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            r.run(); // in chiusura: garantisce comunque la persistenza
        }
    }

    /** Svuota la coda e attende il termine delle scritture pendenti (chiamare in onDisable). */
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
