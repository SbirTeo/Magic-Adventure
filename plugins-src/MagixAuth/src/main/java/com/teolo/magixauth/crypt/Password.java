package com.teolo.magixauth.crypt;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;

/**
 * Le password, nello stesso identico formato che usa il sito.
 *
 * Questo e' il punto in cui l'account unico sta in piedi o cade: la riga e' una sola
 * (`users.password_hash`), e viene scritta da due programmi diversi — il PHP del sito e
 * questo plugin. Se i due non producono lo stesso formato, chi cambia la password da una
 * parte si ritrova chiuso fuori dall'altra.
 *
 * Il formato e' bcrypt con marchio "$2y$", quello che PHP genera con
 * `password_hash($pw, PASSWORD_BCRYPT)`. Da PHP si verifica con `password_verify()` senza
 * altre accortezze, e da qui si verifica qualunque hash bcrypt il sito abbia gia' scritto.
 *
 * NOTA per il sito: `set-password.php` usa `PASSWORD_DEFAULT`, che oggi e' bcrypt ma che
 * PHP si riserva di cambiare nelle versioni future. Va fissato a `PASSWORD_BCRYPT`, o il
 * giorno in cui cambia le due porte smettono di capirsi.
 */
public final class Password {

    /** Costo del bcrypt. 12 e' un compromesso: ~250ms per verifica su hardware da VPS. */
    private static final int COSTO = 12;

    private Password() {
    }

    /** L'impronta da scrivere nel database. Mai la password in chiaro, mai un log. */
    public static String fingerprint(String inChiaro) {
        return BCrypt.with(BCrypt.Version.VERSION_2Y).hashToString(COSTO, inChiaro.toCharArray());
    }

    /**
     * La password digitata corrisponde all'impronta salvata?
     *
     * Accetta qualunque variante di bcrypt ($2a$, $2b$, $2y$): il sito puo' aver scritto
     * quella riga anni fa, con una versione di PHP diversa da quella di oggi.
     */
    public static boolean matchesHash(String inChiaro, String fingerprint) {
        if (inChiaro == null || fingerprint == null || fingerprint.isEmpty()) {
            return false;
        }
        try {
            return BCrypt.verifyer().verify(inChiaro.getBytes(StandardCharsets.UTF_8),
                    fingerprint.getBytes(StandardCharsets.UTF_8)).verified;
        } catch (IllegalArgumentException e) {
            // Impronta illeggibile (riga rovinata a mano, o formato che non conosciamo):
            // si nega l'accesso, non si tenta di indovinare.
            return false;
        }
    }

    /** Il rifiuto di una password: la chiave del messaggio da mostrare, con i suoi placeholder. */
    public record Rejection(String key, String... kv) {}

    /**
     * La password e' accettabile?
     *
     * Volutamente poche regole: la lunghezza minima e il divieto di usare il proprio nome.
     * Le regole barocche (una maiuscola, un simbolo, una cifra) non rendono le password
     * piu' difficili da indovinare, rendono piu' probabile che vengano scritte su un
     * foglietto — o, qui, nella chat pubblica.
     *
     * @return null se va bene, altrimenti la chiave del messaggio da mostrare (vedi
     *         {@code Messages.get}) invece del testo gia' pronto: cosi' chi chiama puo'
     *         tradurlo per il giocatore che ha sbagliato.
     */
    public static Rejection whyNot(String inChiaro, String playerName, int minima) {
        if (inChiaro == null || inChiaro.length() < minima) {
            return new Rejection("password.too-short", "min", String.valueOf(minima));
        }
        if (inChiaro.length() > 64) {
            return new Rejection("password.too-long");
        }
        if (playerName != null && inChiaro.equalsIgnoreCase(playerName)) {
            return new Rejection("password.same-as-name");
        }
        return null;
    }
}
