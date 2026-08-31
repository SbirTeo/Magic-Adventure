package com.teolo.magixguard.collect;

import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Risoluzione inversa dell'indirizzo IP (rDNS).
 *
 * Sulle linee domestiche italiane il nome inverso resta stabile anche quando l'IP cambia
 * (tipo host-87-1-2-3.retail.telecomitalia.it), quindi collega sessioni che l'IP da solo
 * non collegherebbe. E' una chiamata di rete bloccante: mai sul thread del server.
 */
public final class Rdns {

    private Rdns() {}

    public static CompletableFuture<String> lookup(String ip, int timeoutMs) {
        if (ip == null || ip.isBlank()) return CompletableFuture.completedFuture(null);
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                String host = InetAddress.getByName(ip).getCanonicalHostName();
                // Se non esiste un record PTR, Java restituisce l'IP stesso: non e' un nome di rete.
                return host == null || host.equalsIgnoreCase(ip) ? null : host.toLowerCase();
            } catch (Exception e) {
                return null;
            }
        });
        return future.completeOnTimeout(null, Math.max(500, timeoutMs), TimeUnit.MILLISECONDS);
    }
}
