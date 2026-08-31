package com.teolo.magixguard.sanzioni;

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
public final class SanzioniConfig {

    /** Una categoria di violazione, con quanto pesa e dove vale. */
    public record Categoria(String codice, String nome, String descrizione, int punti,
                            Ambito ambito, boolean automatico) { }

    /** Una soglia del registro punti: raggiunti i punti, scatta il provvedimento. */
    public record Soglia(int punti, Tipo tipo, long durata) { }

    /** Il tetto di durata di un grado dello staff. */
    public record Tetto(long mute, long ban) {

        /** Il grado puo' dare questa sanzione con questa durata? */
        public boolean consente(Tipo tipo, long durata) {
            long limite = tipo == Tipo.BAN ? ban : mute;
            if (limite < 0) {
                return true;                 // -1 = nessun limite
            }
            if (limite == 0) {
                return false;                // 0 = non puo' proprio
            }
            if (durata == Durata.PERMANENTE) {
                return false;                // il permanente sta sopra qualunque tetto finito
            }
            return durata <= limite;
        }
    }

    // --- collegamento al sito ---
    public final String sitoHost;
    public final int sitoPort;
    public final String sitoDatabase;
    public final String sitoUser;
    public final String sitoPassword;
    public final int sitoPool;
    public final int controlloSecondi;

    // --- applicazione ---
    /** misto | automatico | proposta */
    public final String modo;
    public final long durataMassimaAutomatica;
    public final String messaggioBan;
    public final String messaggioKick;
    public final String messaggioMute;

    // --- punti ---
    public final double dimezzamentoGiorni;
    public final List<Soglia> soglie = new ArrayList<>();

    // --- categorie e poteri ---
    public final Map<String, Categoria> categorie = new LinkedHashMap<>();
    public final Map<String, Tetto> poteri = new LinkedHashMap<>();

    // --- segnalazioni ---
    public final boolean reportAttivo;
    public final int reportPausaSecondi;
    public final int reportMassimoAperti;
    public final int reportMotivoMinimo;

    // --- regolamento ---
    public final boolean generaRegolamento;
    public final String introduzioneRegolamento;


    /**
     * Carica {@code sanzioni.yml} con il separatore di percorso spostato da '.' a '/'.
     *
     * <p>Le categorie hanno il punto nel nome ({@code chat.spam}, {@code cheat.xray}) perche'
     * e' il codice che finisce nel database e che il sito conosce. Con il separatore di serie,
     * YAML le leggerebbe come sezioni annidate e la tabella delle categorie uscirebbe vuota —
     * cosa che e' successa davvero, ed e' la ragione per cui questo metodo esiste. Chi legge
     * questo file deve passare SEMPRE di qui.</p>
     */
    public static org.bukkit.configuration.file.FileConfiguration carica(java.io.File file) {
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

    public SanzioniConfig(FileConfiguration c) {
        this.sitoHost = c.getString("sito/host", "127.0.0.1");
        this.sitoPort = c.getInt("sito/port", 3306);
        this.sitoDatabase = c.getString("sito/database", "magicadventure_web");
        this.sitoUser = c.getString("sito/user", "magicweb");
        this.sitoPassword = c.getString("sito/password", "");
        this.sitoPool = Math.max(1, c.getInt("sito/pool-size", 3));
        this.controlloSecondi = Math.max(3, c.getInt("sito/controllo-secondi", 10));

        this.modo = c.getString("applicazione/modo", "misto").trim().toLowerCase();
        this.durataMassimaAutomatica = Durata.leggi(c.getString("applicazione/durata-massima-automatica", "30d"));
        this.messaggioBan = c.getString("applicazione/messaggio-ban", "&cSei stato bandito.\n&7{motivo}");
        this.messaggioKick = c.getString("applicazione/messaggio-kick", "&eSei stato espulso.\n&7{motivo}");
        this.messaggioMute = c.getString("applicazione/messaggio-mute", "&cNon puoi scrivere in chat: &f{motivo}");

        this.dimezzamentoGiorni = Math.max(1, c.getDouble("punti/dimezzamento-giorni", 90));

        for (Map<?, ?> riga : c.getMapList("punti/soglie")) {
            Object p = riga.get("punti");
            Tipo t = Tipo.da(String.valueOf(riga.get("tipo")));
            if (p == null || t == null) {
                continue;
            }
            long durata = Durata.leggi(String.valueOf(riga.get("durata")));
            soglie.add(new Soglia(((Number) p).intValue(), t, durata));
        }
        // Ordinate dalla piu' alta: cosi' basta prendere la prima superata.
        soglie.sort((a, b) -> Integer.compare(b.punti(), a.punti()));

        ConfigurationSection cat = c.getConfigurationSection("categorie");
        if (cat != null) {
            for (String codice : cat.getKeys(false)) {
                ConfigurationSection s = cat.getConfigurationSection(codice);
                if (s == null) {
                    continue;
                }
                categorie.put(codice, new Categoria(
                        codice,
                        s.getString("nome", codice),
                        s.getString("descrizione", ""),
                        s.getInt("punti", 0),
                        Ambito.da(s.getString("ambito", "entrambi")),
                        s.getBoolean("automatico", false)));
            }
        }

        ConfigurationSection pot = c.getConfigurationSection("poteri");
        if (pot != null) {
            for (String grado : pot.getKeys(false)) {
                ConfigurationSection s = pot.getConfigurationSection(grado);
                if (s == null) {
                    continue;
                }
                poteri.put(grado.toLowerCase(), new Tetto(
                        Durata.leggi(s.getString("mute", "0")),
                        Durata.leggi(s.getString("ban", "0"))));
            }
        }

        this.reportAttivo = c.getBoolean("report/attivo", true);
        this.reportPausaSecondi = Math.max(0, c.getInt("report/pausa-secondi", 60));
        this.reportMassimoAperti = Math.max(1, c.getInt("report/massimo-aperte", 3));
        this.reportMotivoMinimo = Math.max(1, c.getInt("report/motivo-minimo", 15));

        this.generaRegolamento = c.getBoolean("regolamento/genera", true);
        this.introduzioneRegolamento = c.getString("regolamento/introduzione", "");
    }

    /** La categoria, o quella "manuale" se il codice non e' fra quelle dichiarate. */
    public Categoria categoria(String codice) {
        Categoria k = categorie.get(codice);
        if (k != null) {
            return k;
        }
        Categoria manuale = categorie.get("manuale");
        return manuale != null ? manuale
                : new Categoria(codice, codice, "", 0, Ambito.ENTRAMBI, false);
    }

    /**
     * Il tetto del grado indicato. Se il grado non e' elencato in {@code poteri} non puo'
     * sanzionare: e' la scelta prudente, un grado sconosciuto non deve ereditare poteri.
     */
    public Tetto tetto(String grado) {
        Tetto t = grado == null ? null : poteri.get(grado.toLowerCase());
        return t != null ? t : new Tetto(0, 0);
    }
}
