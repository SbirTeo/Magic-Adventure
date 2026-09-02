package com.teolo.magixguard.sanctions;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tutto quello che sta in {@code sanzioni.yml}, letto una volta e tenuto qui.
 *
 * <p>Questo file e' anche la <b>fonte del regolamento pubblico</b>: la tabella delle sanzioni
 * sul sito viene generata da questi stessi valori, quindi non puo' raccontare soglie diverse
 * da quelle applicate davvero.</p>
 */
public final class SanctionsConfig {

    /** Una categoria di violazione, con quanto pesa e dove vale. */
    public record Category(String code, String name, String description, int points,
                            Scope scope, boolean automatic) { }

    /** Una soglia del registro punti: raggiunti i punti, scatta il provvedimento. */
    public record Threshold(int points, Type type, long duration) { }

    /** Il tetto di durata di un grado dello staff. */
    public record Tetto(long mute, long ban) {

        /** Il grado puo' dare questa sanzione con questa durata? */
        public boolean consente(Type type, long duration) {
            long limite = type == Type.BAN ? ban : mute;
            if (limite < 0) {
                return true;                 // -1 = nessun limite
            }
            if (limite == 0) {
                return false;                // 0 = non puo' proprio
            }
            if (duration == Duration.PERMANENTE) {
                return false;                // il permanente sta sopra qualunque tetto finito
            }
            return duration <= limite;
        }
    }

    // --- collegamento al sito ---
    public final String siteHost;
    public final int sitePort;
    public final String siteDatabase;
    public final String siteUser;
    public final String sitePassword;
    public final int sitePool;
    public final int controlloSecondi;

    // --- applicazione ---
    /** misto | automatico | proposta */
    public final String mode;
    public final long maxAutomaticDuration;
    public final String banMessage;
    public final String kickMessage;
    public final String muteMessage;

    // --- punti ---
    public final double halfLifeDays;
    public final List<Threshold> thresholds = new ArrayList<>();

    // --- categorie e poteri ---
    public final Map<String, Category> categories = new LinkedHashMap<>();
    public final Map<String, Tetto> powers = new LinkedHashMap<>();

    // --- segnalazioni ---
    public final boolean reportActive;
    public final int reportCooldownSeconds;
    public final int reportMaxOpen;
    public final int reportMinReason;

    // --- regolamento ---
    public final boolean generateRules;
    public final String rulesIntro;


    /**
     * Carica {@code sanzioni.yml} con il separatore di percorso spostato da '.' a '/'.
     *
     * <p>Le categorie hanno il punto nel nome ({@code chat.spam}, {@code cheat.xray}) perche'
     * e' il codice che finisce nel database e che il sito conosce. Con il separatore di serie,
     * YAML le leggerebbe come sezioni annidate e la tabella delle categorie uscirebbe vuota —
     * cosa che e' successa davvero, ed e' la ragione per cui questo metodo esiste. Chi legge
     * questo file deve passare SEMPRE di qui.</p>
     */
    public static org.bukkit.configuration.file.FileConfiguration load(java.io.File file) {
        org.bukkit.configuration.file.YamlConfiguration yml =
                new org.bukkit.configuration.file.YamlConfiguration();
        yml.options().pathSeparator('/');
        try {
            yml.load(file);
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            // File mancante o malformato: si torna una configurazione vuota, e chi la usa
            // finisce sui valori di ripiego. Meglio che non partire affatto.
        }
        return yml;
    }

    public SanctionsConfig(FileConfiguration c) {
        this.siteHost = c.getString("site/host", "127.0.0.1");
        this.sitePort = c.getInt("site/port", 3306);
        this.siteDatabase = c.getString("site/database", "magicadventure_web");
        this.siteUser = c.getString("site/user", "magicweb");
        this.sitePassword = c.getString("site/password", "");
        this.sitePool = Math.max(1, c.getInt("site/pool-size", 3));
        this.controlloSecondi = Math.max(3, c.getInt("site/check-seconds", 10));

        this.mode = c.getString("application/mode", "misto").trim().toLowerCase();
        this.maxAutomaticDuration = Duration.read(c.getString("application/auto-max-duration", "30d"));
        this.banMessage = c.getString("application/ban-message", "&cSei stato bandito.\n&7{motivo}");
        this.kickMessage = c.getString("application/kick-message", "&eSei stato espulso.\n&7{motivo}");
        this.muteMessage = c.getString("application/mute-message", "&cNon puoi scrivere in chat: &f{motivo}");

        this.halfLifeDays = Math.max(1, c.getDouble("points/halving-days", 90));

        for (Map<?, ?> row : c.getMapList("points/thresholds")) {
            Object p = row.get("points");
            Type t = Type.da(String.valueOf(row.get("type")));
            if (p == null || t == null) {
                continue;
            }
            long duration = Duration.read(String.valueOf(row.get("duration")));
            thresholds.add(new Threshold(((Number) p).intValue(), t, duration));
        }
        // Ordinate dalla piu' alta: cosi' basta prendere la prima superata.
        thresholds.sort((a, b) -> Integer.compare(b.points(), a.points()));

        ConfigurationSection cat = c.getConfigurationSection("categories");
        if (cat != null) {
            for (String code : cat.getKeys(false)) {
                ConfigurationSection s = cat.getConfigurationSection(code);
                if (s == null) {
                    continue;
                }
                categories.put(code, new Category(
                        code,
                        s.getString("name", code),
                        s.getString("description", ""),
                        s.getInt("points", 0),
                        Scope.da(s.getString("scope", "entrambi")),
                        s.getBoolean("automatic", false)));
            }
        }

        ConfigurationSection pot = c.getConfigurationSection("powers");
        if (pot != null) {
            for (String rank : pot.getKeys(false)) {
                ConfigurationSection s = pot.getConfigurationSection(rank);
                if (s == null) {
                    continue;
                }
                powers.put(rank.toLowerCase(), new Tetto(
                        Duration.read(s.getString("mute", "0")),
                        Duration.read(s.getString("ban", "0"))));
            }
        }

        this.reportActive = c.getBoolean("report/active", true);
        this.reportCooldownSeconds = Math.max(0, c.getInt("report/pause-seconds", 60));
        this.reportMaxOpen = Math.max(1, c.getInt("report/max-open", 3));
        this.reportMinReason = Math.max(1, c.getInt("report/min-reason", 15));

        this.generateRules = c.getBoolean("rulebook/generate", true);
        this.rulesIntro = c.getString("rulebook/intro", "");
    }

    /** La categoria, o quella "manuale" se il codice non e' fra quelle dichiarate. */
    public Category category(String code) {
        Category k = categories.get(code);
        if (k != null) {
            return k;
        }
        Category manuale = categories.get("manuale");
        return manuale != null ? manuale
                : new Category(code, code, "", 0, Scope.ENTRAMBI, false);
    }

    /**
     * Il tetto del grado indicato. Se il grado non e' elencato in {@code poteri} non puo'
     * sanzionare: e' la scelta prudente, un grado sconosciuto non deve ereditare poteri.
     */
    public Tetto tetto(String rank) {
        Tetto t = rank == null ? null : powers.get(rank.toLowerCase());
        return t != null ? t : new Tetto(0, 0);
    }
}
