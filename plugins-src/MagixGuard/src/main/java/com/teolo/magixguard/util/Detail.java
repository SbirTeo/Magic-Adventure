package com.teolo.magixguard.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Piccolo contenitore chiave=valore per il campo "detail" delle prove.
 *
 * Volutamente NON e' JSON: quel testo finisce tale e quale dentro il dossier che lo staff
 * allega a un ricorso, quindi deve essere leggibile da un essere umano senza formattazione
 * ("ip=87.1.2.3; account_su_ip=3"). Resta comunque ri-analizzabile dal plugin per ricalcolare
 * i pesi (vedi affollamento IP).
 */
public final class Detail {

    private final Map<String, String> values = new LinkedHashMap<>();

    public static Detail of(String key, Object value) { return new Detail().put(key, value); }

    public static Detail parse(String s) {
        Detail d = new Detail();
        if (s == null || s.isBlank()) return d;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) d.values.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return d;
    }

    public Detail put(String key, Object value) {
        if (value != null) values.put(key, String.valueOf(value));
        return this;
    }

    public String get(String key) { return values.get(key); }

    public int getInt(String key, int fallback) {
        try { return Integer.parseInt(values.getOrDefault(key, "")); } catch (NumberFormatException e) { return fallback; }
    }

    public Map<String, String> asMap() { return values; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
