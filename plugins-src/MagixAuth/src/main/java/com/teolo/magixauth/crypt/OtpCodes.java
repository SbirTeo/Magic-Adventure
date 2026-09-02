package com.teolo.magixauth.crypt;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * I conti della verifica in due passaggi, gli stessi che fa il sito in PHP.
 *
 * Qui non si decide niente: si sa solo (a) come si legge un segreto base32,
 * (b) come si ricava il codice a sei cifre di un dato mezzo minuto (TOTP, RFC 6238) e
 * (c) come si apre la busta cifrata in cui il sito tiene il segreto dentro al database.
 *
 * Il segreto non viene mai copiato qui dentro: si legge dalla stessa riga che usa il sito,
 * quindi un codice speso sul sito risulta speso anche in gioco e viceversa (chi scrive
 * totp_last_step e' AuthDao).
 *
 * ATTENZIONE: questo file e' la copia esatta di OtpCodici in MagixWeb, e deve restarlo.
 * Sono tre programmi — il PHP del sito, MagixWeb e questo — che fanno gli stessi conti
 * sulla stessa riga del database: se uno dei tre cambia passo, tolleranza o alfabeto, i
 * codici smettono di combaciare e nessuno degli amministratori entra piu'. Se serve una
 * modifica, va fatta in tutti e tre insieme.
 */
public final class OtpCodes {

    /** Alfabeto base32 (RFC 4648), lo stesso che usano le app OTP. */
    private static final String ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** Durata di un codice e tolleranza sull'orologio: devono combaciare col sito. */
    public static final int STEP_SECONDS = 30;
    public static final int TOLLERANZA = 1;
    private static final int DIGITS = 6;

    private OtpCodes() {
    }

    /** L'intervallo di trenta secondi in cui siamo adesso. */
    public static long stepNow() {
        return System.currentTimeMillis() / 1000L / STEP_SECONDS;
    }

    /** Da base32 ai byte veri del segreto. */
    public static byte[] base32Decode(String text) {
        String clean = text.toUpperCase().replaceAll("[^A-Z2-7]", "");
        if (clean.isEmpty()) {
            return new byte[0];
        }
        ByteBuffer buf = ByteBuffer.allocate(clean.length() * 5 / 8 + 1);
        int accumulatore = 0;
        int bitDisponibili = 0;
        for (char c : clean.toCharArray()) {
            accumulatore = (accumulatore << 5) | ALFABETO.indexOf(c);
            bitDisponibili += 5;
            if (bitDisponibili >= 8) {
                bitDisponibili -= 8;
                buf.put((byte) ((accumulatore >> bitDisponibili) & 0xFF));
            }
        }
        byte[] out = new byte[buf.position()];
        buf.rewind();
        buf.get(out);
        return out;
    }

    /** Il codice a sei cifre valido in un certo intervallo. */
    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());

            // Troncamento dinamico (RFC 4226): gli ultimi quattro bit dicono da dove pescare.
            int start = hash[hash.length - 1] & 0x0F;
            int number = ((hash[start] & 0x7F) << 24)
                    | ((hash[start + 1] & 0xFF) << 16)
                    | ((hash[start + 2] & 0xFF) << 8)
                    | (hash[start + 3] & 0xFF);

            int module = (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", number % module);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Il codice digitato e' giusto?
     *
     * @param ultimoPasso l'ultimo intervallo gia' speso da questo account (o null)
     * @return l'intervallo accettato, oppure -1 se il codice non va bene
     */
    public static long checkPassword(byte[] secret, String digitato, Long lastStep) {
        String clean = digitato == null ? "" : digitato.replaceAll("\\D", "");
        if (clean.length() != DIGITS || secret.length == 0) {
            return -1;
        }
        long now = stepNow();
        for (long d = -TOLLERANZA; d <= TOLLERANZA; d++) {
            long step = now + d;
            // Un intervallo gia' speso non vale piu': e' la stessa regola del sito, e vale
            // fra i due mondi (codice usato sul sito = codice bruciato anche in gioco).
            if (lastStep != null && step <= lastStep) {
                continue;
            }
            if (uguali(code(secret, step), clean)) {
                return step;
            }
        }
        return -1;
    }

    /**
     * Apre la busta in cui il sito tiene il segreto: AES-256-GCM, con davanti dodici byte
     * di vettore iniziale e sedici di marchio d'integrita' (vedi otp_cifra in PHP).
     *
     * @return il segreto in base32, oppure null se la chiave e' sbagliata o il dato e' rotto
     */
    public static String decryptSecret(String dalDatabase, String keyBase64) {
        if (dalDatabase == null || dalDatabase.isEmpty() || keyBase64 == null || keyBase64.isEmpty()) {
            return null;
        }
        try {
            byte[] key = Base64.getDecoder().decode(keyBase64.trim());
            if (key.length != 32) {
                return null;
            }
            byte[] envelope = Base64.getDecoder().decode(dalDatabase);
            if (envelope.length < 29) {
                return null;
            }
            byte[] iv = Arrays.copyOfRange(envelope, 0, 12);
            byte[] brand = Arrays.copyOfRange(envelope, 12, 28);
            byte[] cifrato = Arrays.copyOfRange(envelope, 28, envelope.length);

            // Java vuole il marchio d'integrita' attaccato in fondo al testo cifrato,
            // PHP lo tiene a parte: qui si rimettono nell'ordine che si aspetta Java.
            byte[] bundle = new byte[cifrato.length + brand.length];
            System.arraycopy(cifrato, 0, bundle, 0, cifrato.length);
            System.arraycopy(brand, 0, bundle, cifrato.length, brand.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(bundle), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Confronto a tempo costante: non deve trapelare "quante cifre erano giuste". */
    private static boolean uguali(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
