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
public final class OtpCodici {

    /** Alfabeto base32 (RFC 4648), lo stesso che usano le app OTP. */
    private static final String ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** Durata di un codice e tolleranza sull'orologio: devono combaciare col sito. */
    public static final int PASSO_SECONDI = 30;
    public static final int TOLLERANZA = 1;
    private static final int CIFRE = 6;

    private OtpCodici() {
    }

    /** L'intervallo di trenta secondi in cui siamo adesso. */
    public static long passoAdesso() {
        return System.currentTimeMillis() / 1000L / PASSO_SECONDI;
    }

    /** Da base32 ai byte veri del segreto. */
    public static byte[] base32Decode(String testo) {
        String pulito = testo.toUpperCase().replaceAll("[^A-Z2-7]", "");
        if (pulito.isEmpty()) {
            return new byte[0];
        }
        ByteBuffer buf = ByteBuffer.allocate(pulito.length() * 5 / 8 + 1);
        int accumulatore = 0;
        int bitDisponibili = 0;
        for (char c : pulito.toCharArray()) {
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
    public static String codice(byte[] segreto, long passo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(segreto, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(passo).array());

            // Troncamento dinamico (RFC 4226): gli ultimi quattro bit dicono da dove pescare.
            int inizio = hash[hash.length - 1] & 0x0F;
            int numero = ((hash[inizio] & 0x7F) << 24)
                    | ((hash[inizio + 1] & 0xFF) << 16)
                    | ((hash[inizio + 2] & 0xFF) << 8)
                    | (hash[inizio + 3] & 0xFF);

            int modulo = (int) Math.pow(10, CIFRE);
            return String.format("%0" + CIFRE + "d", numero % modulo);
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
    public static long verifica(byte[] segreto, String digitato, Long ultimoPasso) {
        String pulito = digitato == null ? "" : digitato.replaceAll("\\D", "");
        if (pulito.length() != CIFRE || segreto.length == 0) {
            return -1;
        }
        long adesso = passoAdesso();
        for (long d = -TOLLERANZA; d <= TOLLERANZA; d++) {
            long passo = adesso + d;
            // Un intervallo gia' speso non vale piu': e' la stessa regola del sito, e vale
            // fra i due mondi (codice usato sul sito = codice bruciato anche in gioco).
            if (ultimoPasso != null && passo <= ultimoPasso) {
                continue;
            }
            if (uguali(codice(segreto, passo), pulito)) {
                return passo;
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
    public static String decifraSegreto(String dalDatabase, String chiaveBase64) {
        if (dalDatabase == null || dalDatabase.isEmpty() || chiaveBase64 == null || chiaveBase64.isEmpty()) {
            return null;
        }
        try {
            byte[] chiave = Base64.getDecoder().decode(chiaveBase64.trim());
            if (chiave.length != 32) {
                return null;
            }
            byte[] busta = Base64.getDecoder().decode(dalDatabase);
            if (busta.length < 29) {
                return null;
            }
            byte[] iv = Arrays.copyOfRange(busta, 0, 12);
            byte[] marchio = Arrays.copyOfRange(busta, 12, 28);
            byte[] cifrato = Arrays.copyOfRange(busta, 28, busta.length);

            // Java vuole il marchio d'integrita' attaccato in fondo al testo cifrato,
            // PHP lo tiene a parte: qui si rimettono nell'ordine che si aspetta Java.
            byte[] insieme = new byte[cifrato.length + marchio.length];
            System.arraycopy(cifrato, 0, insieme, 0, cifrato.length);
            System.arraycopy(marchio, 0, insieme, cifrato.length, marchio.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(chiave, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(insieme), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Confronto a tempo costante: non deve trapelare "quante cifre erano giuste". */
    private static boolean uguali(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
