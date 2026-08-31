package com.teolo.magixweb.guida;

import com.teolo.magixweb.MagixWeb;
import com.teolo.magixweb.db.Database;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Raccoglie i capitoli della "Guida per amministratori" e li porta sul sito.
 *
 * Ogni plugin nostro scrive il proprio capitolo in {@code plugins/<Nome>/guida-staff.html}
 * (lo fa la classe comune {@code util/GuidaStaff}); qui li leggiamo e li riversiamo nella
 * tabella {@code guide_staff}, che il gestionale mostra in /manage?section=guida.
 *
 * Perche' passare da un FILE e non da un servizio Bukkit: cosi' non conta l'ordine di
 * avvio dei plugin, non serve che si conoscano fra loro, e se qualcosa non arriva sul sito
 * il capitolo resta li' sul disco da guardare. Le credenziali del database del sito
 * restano dove sono sempre state: solo qui dentro.
 */
public class GuidaSync {

    /** Nome del file che ogni plugin lascia nella propria cartella. */
    private static final String NOME_FILE = "guida-staff.html";

    private final MagixWeb plugin;
    private final Database database;

    public GuidaSync(MagixWeb plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    /** Legge i capitoli dal disco e li scrive sul sito. Tutto fuori dal thread principale. */
    public void sincronizza() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Capitolo> capitoli = raccogli();
            if (capitoli.isEmpty()) {
                return;
            }
            int scritti = salva(capitoli);
            if (scritti > 0) {
                plugin.getLogger().info("MagixWeb: guida per amministratori aggiornata (" + scritti + " capitoli).");
            }
        });
    }

    /** Tutti i guida-staff.html presenti nelle cartelle dei plugin. */
    private List<Capitolo> raccogli() {
        List<Capitolo> out = new ArrayList<>();
        File cartellaPlugin = plugin.getDataFolder().getParentFile();
        File[] cartelle = cartellaPlugin == null ? null : cartellaPlugin.listFiles(File::isDirectory);
        if (cartelle == null) {
            return out;
        }
        for (File cartella : cartelle) {
            File f = new File(cartella, NOME_FILE);
            if (!f.isFile()) {
                continue;
            }
            try {
                Capitolo c = leggi(cartella.getName(), Files.readString(f.toPath(), StandardCharsets.UTF_8));
                if (c != null) {
                    out.add(c);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("MagixWeb: capitolo illeggibile in " + cartella.getName() + ": " + e.getMessage());
            }
        }
        return out;
    }

    /**
     * Il file comincia con un'intestazione fra commenti HTML:
     * <pre>
     * &lt;!--guida
     * titolo: MagixTime — ora e stagioni
     * versione: 0.3.4
     * ordine: 60
     * --&gt;
     * </pre>
     * Quello che segue e' il corpo del capitolo. Senza intestazione il file si ignora:
     * meglio nessun capitolo che uno senza nome.
     */
    private Capitolo leggi(String nomePlugin, String contenuto) {
        int inizio = contenuto.indexOf("<!--guida");
        int fine = contenuto.indexOf("-->", inizio + 1);
        if (inizio < 0 || fine < 0) {
            return null;
        }
        String testata = contenuto.substring(inizio + "<!--guida".length(), fine);
        String corpo = contenuto.substring(fine + 3).trim();

        String titolo = nomePlugin;
        String versione = "";
        int ordine = 100;
        for (String riga : testata.split("\\R")) {
            int duepunti = riga.indexOf(':');
            if (duepunti < 0) {
                continue;
            }
            String chiave = riga.substring(0, duepunti).trim().toLowerCase();
            String valore = riga.substring(duepunti + 1).trim();
            switch (chiave) {
                case "title" -> titolo = valore;
                case "version" -> versione = valore;
                case "sort_order" -> {
                    try {
                        ordine = Integer.parseInt(valore);
                    } catch (NumberFormatException ignored) {
                        // ordine non valido: resta quello di serie, in fondo all'elenco
                    }
                }
                default -> { }
            }
        }
        return corpo.isEmpty() ? null : new Capitolo(nomePlugin, titolo, versione, ordine, corpo);
    }

    /** Scrive (o riscrive) i capitoli sul sito. Una riga per plugin: conta solo l'ultima verita'. */
    private int salva(List<Capitolo> capitoli) {
        String sql = "INSERT INTO guide_staff (plugin, title, version, sort_order, body_html, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, NOW()) "
                + "ON DUPLICATE KEY UPDATE title = VALUES(title), version = VALUES(version), "
                + "sort_order = VALUES(sort_order), body_html = VALUES(body_html), updated_at = NOW()";

        int fatti = 0;
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (Capitolo cap : capitoli) {
                ps.setString(1, cap.plugin());
                ps.setString(2, cap.titolo());
                ps.setString(3, cap.versione());
                ps.setInt(4, cap.ordine());
                ps.setString(5, cap.corpo());
                ps.addBatch();
                fatti++;
            }
            ps.executeBatch();
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixWeb: guida per amministratori non salvata: " + e.getMessage());
            return 0;
        }
        return fatti;
    }

    private record Capitolo(String plugin, String titolo, String versione, int ordine, String corpo) { }
}
