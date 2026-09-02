package com.teolo.magixauth.gate;

import io.papermc.paper.connection.ReadablePlayerCookieConnection;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Il biscotto: un gettone che il client si tiene in tasca e ci ripresenta al rientro.
 *
 * Serve a rispondere a "sei tu, dallo stesso posto di prima?" senza guardare l'INDIRIZZO di
 * rete, che sulle connessioni mobili cambia ogni poche ore e faceva richiedere la password a
 * chi era uscito cinque minuti prima.
 *
 * Il gettone NON sostituisce l'indirizzo, gli si affianca: sparisce quando il giocatore chiude
 * il gioco (i cookie di Minecraft vivono nella memoria del client), quindi da solo non
 * riconoscerebbe mai chi riapre il gioco il giorno dopo. Insieme, uno copre il buco dell'altro.
 *
 * Tre precauzioni, perche' un gettone e' pur sempre una chiave che vale da sola — e in offline
 * mode la connessione non e' cifrata, quindi va trattato come una cosa che qualcuno potrebbe
 * vedere passare:
 * <ul>
 *   <li>e' 32 byte casuali da {@link SecureRandom}: non lo si indovina e non si ricava da altro;</li>
 *   <li>nel database non finisce lui ma la sua IMPRONTA (SHA-256): chi legge la tabella non
 *       ottiene niente di riutilizzabile;</li>
 *   <li>si RINNOVA a ogni uso, e il vecchio viene cancellato. Un gettone intercettato vale
 *       fino al rientro successivo del proprietario, e se qualcuno lo usa prima di lui e' il
 *       proprietario a ritrovarsi la password da digitare — cioe' se ne accorge.</li>
 * </ul>
 */
public final class Cookie {

    /** Il prefisso distingue le righe da gettone da quelle da indirizzo nella stessa tabella. */
    private static final String PREFISSO = "cookie:";

    private static final SecureRandom CASO = new SecureRandom();

    private final NamespacedKey key;
    private final long attesaMillis;

    public Cookie(Plugin plugin, long attesaMillis) {
        this.key = new NamespacedKey(plugin, "sessione");
        this.attesaMillis = attesaMillis;
    }

    /** Un gettone nuovo di zecca. */
    public static byte[] newCookieToken() {
        byte[] b = new byte[32];
        CASO.nextBytes(b);
        return b;
    }

    /** Il nome con cui questo gettone e' conosciuto nella tabella dei dispositivi. */
    public static String deviceKey(byte[] cookieToken) {
        try {
            byte[] fingerprint = MessageDigest.getInstance("SHA-256").digest(cookieToken);
            return PREFISSO + Base64.getUrlEncoder().withoutPadding().encodeToString(fingerprint);
        } catch (Exception e) {
            // SHA-256 c'e' sempre: se mancasse, sarebbe una JVM da buttare.
            throw new IllegalStateException(e);
        }
    }

    /**
     * Il dispositivo di chi sta bussando, se ha un gettone da mostrare.
     *
     * Si aspetta al massimo {@code attesaMillis}: la risposta arriva dal client, e un client
     * che non risponde non deve tenere fermo nessuno al cancello. Chi non ha il gettone — prima
     * volta, gioco appena riaperto, client che i cookie non li gestisce — semplicemente non ne
     * ha uno, e si passa al riconoscimento per indirizzo.
     *
     * @return il nome del dispositivo, oppure null
     */
    public String deviceOf(ReadablePlayerCookieConnection conn) {
        if (conn == null) {
            return null;
        }
        try {
            CompletableFuture<byte[]> richiesta = conn.retrieveCookie(key);
            byte[] cookieToken = richiesta.get(attesaMillis, TimeUnit.MILLISECONDS);
            return cookieToken == null || cookieToken.length == 0 ? null : deviceKey(cookieToken);
        } catch (Exception e) {
            return null;
        }
    }

    /** Consegna il gettone al client, che se lo tiene finche' non chiude il gioco. */
    public void deliver(Player p, byte[] cookieToken) {
        p.storeCookie(key, cookieToken);
    }
}
