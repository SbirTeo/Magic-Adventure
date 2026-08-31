package com.teolo.magixguard.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Hash e mascheramento dei dati sensibili.
 *
 * <p>Gli indirizzi IP sono dati personali: nel database vengono conservati anche in forma
 * di HMAC-SHA256 con un segreto (pepper) che NON sta nel database. Gli hash permettono di
 * riconoscere "stesso IP" senza che chi legge una copia del DB possa risalire all'IP.
 * L'IP in chiaro resta nella sessione solo finche' non scade la retention configurata.
 */
public final class Hashing {

    private final byte[] pepper;

    public Hashing(String pepper) {
        this.pepper = (pepper == null ? "" : pepper).getBytes(StandardCharsets.UTF_8);
    }

    /** HMAC-SHA256 del valore, in esadecimale (64 caratteri). Null-safe: null -> null. */
    public String hmac(String value) {
        if (value == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return hex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC non disponibile", e);
        }
    }

    /** SHA-256 puro (senza pepper): usato per la catena di controllo, che deve essere verificabile. */
    public static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 non disponibile", e);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    /**
     * Maschera un IP per i dossier pubblici: {@code 87.12.34.56} con octets=2 -> {@code 87.12.x.x}.
     * Per IPv6 taglia dopo i primi gruppi corrispondenti.
     */
    public static String maskIp(String ip, int keepOctets) {
        if (ip == null || ip.isEmpty()) return "?";
        if (ip.contains(":")) {
            String[] parts = ip.split(":");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                sb.append(i < keepOctets ? parts[i] : "x");
                if (i < parts.length - 1) sb.append(':');
            }
            return sb.toString();
        }
        String[] parts = ip.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            sb.append(i < keepOctets ? parts[i] : "x");
            if (i < parts.length - 1) sb.append('.');
        }
        return sb.toString();
    }

    /** Prefisso /24 di un IPv4 (per IPv6: primi 4 gruppi, all'incirca una /64). */
    public static String subnet(String ip) {
        if (ip == null || ip.isEmpty()) return null;
        if (ip.contains(":")) {
            String[] p = ip.split(":");
            return (p.length >= 4 ? String.join(":", p[0], p[1], p[2], p[3]) : ip) + "::/64";
        }
        int last = ip.lastIndexOf('.');
        return last > 0 ? ip.substring(0, last) + ".0/24" : ip;
    }

    /** Distanza di Levenshtein, per riconoscere nickname costruiti sullo stesso schema. */
    public static int levenshtein(String a, String b) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        a = a.toLowerCase();
        b = b.toLowerCase();
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }
}
