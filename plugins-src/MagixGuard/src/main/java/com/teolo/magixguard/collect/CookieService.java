package com.teolo.magixguard.collect;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Token di installazione scritto nel client tramite i cookie di Minecraft (1.20.5+).
 *
 * E' il segnale piu' forte a disposizione di un server non premium: due nickname che
 * presentano lo stesso token stanno girando sulla stessa copia del gioco, e nessuna VPN
 * o cambio di IP lo nasconde.
 *
 * Limite da tenere presente (e da scrivere nei dossier): in vanilla il cookie vive nella
 * memoria del client, non su disco. Sopravvive al cambio di server e al riconnettersi,
 * ma si perde quando il giocatore chiude Minecraft. Quindi la sua ASSENZA non prova nulla,
 * la sua presenza si'.
 */
public final class CookieService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final NamespacedKey key;
    private final boolean enabled;
    private final Plugin plugin;

    public CookieService(Plugin plugin, String rawKey, boolean enabled) {
        this.plugin = plugin;
        NamespacedKey parsed = NamespacedKey.fromString(rawKey == null ? "magixguard:id" : rawKey);
        this.key = parsed != null ? parsed : NamespacedKey.fromString("magixguard:id");
        this.enabled = enabled;
    }

    /**
     * Legge il token dal client; se non c'e' ne genera uno nuovo e lo scrive.
     * Da chiamare sul thread principale. Il future si completa con il token, o con null
     * se il client non risponde entro il tempo massimo.
     */
    public CompletableFuture<String> resolve(Player player) {
        if (!enabled || key == null) return CompletableFuture.completedFuture(null);
        return player.retrieveCookie(key)
                .completeOnTimeout(null, 5, TimeUnit.SECONDS)
                .thenApply(bytes -> {
                    if (bytes != null && bytes.length > 0) {
                        return new String(bytes, StandardCharsets.UTF_8);
                    }
                    String token = newToken();
                    // Il future si completa su un thread di rete: la scrittura del cookie e'
                    // API Bukkit e va rimandata al thread principale.
                    org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) player.storeCookie(key, token.getBytes(StandardCharsets.UTF_8));
                    });
                    return token;
                })
                .exceptionally(t -> null);
    }

    private static String newToken() {
        byte[] buf = new byte[16];
        RANDOM.nextBytes(buf);
        StringBuilder sb = new StringBuilder(buf.length * 2);
        for (byte b : buf) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}
