package com.teolo.magixguard.privacy;

import com.teolo.magixguard.collect.Fingerprints;
import com.teolo.magixguard.db.Database;
import com.teolo.magixguard.model.SessionSnapshot;
import com.teolo.magixguard.util.Hashing;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Il segreto degli hash (privacy.pepper): che ci sia, che non sia pubblico, e che gli hash nel
 * database seguano quello in uso.
 *
 * <p>Fino alla 0.4.8 il config.yml del jar aveva un pepper vero scritto dentro, e il repository e'
 * pubblico: chiunque poteva leggerlo e, con una copia del database, risalire agli IP provando tutti
 * gli indirizzi possibili. Dalla 0.4.9 il jar non ne ha nessuno, e:</p>
 * <ol>
 *   <li><b>se manca</b> (installazione nuova) se ne genera uno casuale e lo si scrive nel config.yml
 *       del server, con la copia in .bak/. Su una rete di piu' server deve essere LO STESSO su
 *       tutti: lo mette uguale ovunque il workflow ruota-segreti.yml;</li>
 *   <li><b>se e' quello pubblicato</b> lo si dice in console a ogni avvio, finche' non si cambia;</li>
 *   <li><b>se e' cambiato</b> dall'ultimo avvio (rotazione) si ricalcolano gli hash delle sessioni
 *       con quello nuovo, cosi' il riconoscimento degli account multipli non si azzera. Si puo' per
 *       tutto cio' di cui il database tiene ancora il dato grezzo: subnet, reverse DNS, canali e
 *       impronta del client sempre; l'IP solo per le sessioni ancora nel periodo di conservazione
 *       (dopo, resta solo l'hash vecchio, che non si puo' rifare e smette di combaciare).</li>
 * </ol>
 *
 * <p>Il controllo usa una riga in mg_meta: l'HMAC di una frase fissa fatto col pepper in uso. Non
 * rivela il pepper, ma cambia appena il pepper cambia. Il ricalcolo lo fa solo il server con
 * network.site-jobs (il database e' condiviso: basta una volta); gli altri usano gia' il pepper
 * nuovo per le sessioni che scrivono, che danno lo stesso risultato.</p>
 */
public final class PepperGuard {

    /** SHA-256 del pepper che stava nel jar fino alla 0.4.8 (pubblico sul repository). */
    private static final String PUBLISHED_SHA256 = "a4935b3c18ee41cdb4e0653dffb55d3712d39f663f550ce21e0006cb2542d241";

    private static final String CHECK_PHRASE = "magixguard:pepper-check";
    private static final Pattern PEPPER_LINE = Pattern.compile("^([ \t]*)pepper:.*$");

    private PepperGuard() {
    }

    /** True se il pepper e' quello pubblicato nel repository fino alla 0.4.8. */
    public static boolean isPublished(String pepper) {
        return pepper != null && PUBLISHED_SHA256.equals(Hashing.sha256(pepper));
    }

    /**
     * Se il config.yml del server non ha un pepper, ne scrive uno casuale (64 caratteri) al posto
     * della riga {@code pepper:}, lasciando intatto il resto del file. Ritorna il pepper scritto, o
     * null se non c'era bisogno o se non e' riuscito (in quel caso il file non si tocca).
     */
    public static String generateIfMissing(JavaPlugin plugin, String current) {
        if (current != null && !current.isBlank() && !current.startsWith("CAMBIAMI")) {
            return null;
        }
        File file = new File(plugin.getDataFolder(), "config.yml");
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            String fresh = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            boolean replaced = false;
            List<String> out = new ArrayList<>(lines.size());
            for (String line : lines) {
                Matcher m = PEPPER_LINE.matcher(line);
                if (!replaced && m.matches()) {
                    out.add(m.group(1) + "pepper: \"" + fresh + "\"");
                    replaced = true;
                } else {
                    out.add(line);
                }
            }
            if (!replaced) {
                plugin.getLogger().warning("privacy.pepper vuoto ma nessuna riga 'pepper:' in config.yml: non lo genero.");
                return null;
            }
            if (!backup(plugin, file)) {
                plugin.getLogger().warning("privacy.pepper vuoto: copia di config.yml in .bak/ non riuscita, non lo genero.");
                return null;
            }
            Files.write(file.toPath(), out, StandardCharsets.UTF_8);
            return fresh;
        } catch (IOException e) {
            plugin.getLogger().warning("privacy.pepper vuoto e non generato: " + e.getMessage());
            return null;
        }
    }

    /**
     * Confronta il pepper in uso con quello dell'ultimo avvio e, se e' cambiato, ricalcola gli hash
     * delle sessioni. Da chiamare fuori dal thread principale, con lo schema gia' creato.
     *
     * @param rehash false sui server senza network.site-jobs: guardano e basta
     */
    public static void checkRotation(Logger log, Database database, Hashing hashing, boolean rehash)
            throws SQLException {
        String check = hashing.hmac(CHECK_PHRASE);
        String stored;
        try (Connection c = database.getConnection()) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS mg_meta (k VARCHAR(64) PRIMARY KEY, v VARCHAR(255))");
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT v FROM mg_meta WHERE k = 'pepper_check'");
                 ResultSet rs = ps.executeQuery()) {
                stored = rs.next() ? rs.getString(1) : null;
            }
        }
        if (check.equals(stored)) {
            return;
        }
        if (!rehash) {
            if (stored != null) {
                log.info("privacy.pepper diverso da quello registrato: il ricalcolo degli hash "
                        + "lo fa il server con network.site-jobs.");
            }
            return;
        }
        if (stored == null) {
            // Primo avvio con questo controllo: si registra il pepper in uso, niente da ricalcolare.
            writeCheck(database, check, null);
            return;
        }
        long t0 = System.currentTimeMillis();
        int rows = rehashSessions(database, hashing);
        writeCheck(database, check, stored);
        log.warning("privacy.pepper cambiato: ricalcolati gli hash di " + rows + " sessioni in "
                + (System.currentTimeMillis() - t0) + " ms (IP solo dove c'è ancora in chiaro).");
    }

    private static void writeCheck(Database database, String check, String previous) throws SQLException {
        try (Connection c = database.getConnection()) {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM mg_meta WHERE k = 'pepper_check'")) {
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO mg_meta (k, v) VALUES ('pepper_check', ?)")) {
                ins.setString(1, check);
                ins.executeUpdate();
            }
        }
    }

    /** Rifa gli hash di ogni sessione partendo dai dati grezzi ancora presenti. */
    private static int rehashSessions(Database database, Hashing hashing) throws SQLException {
        int done = 0;
        long lastId = 0;
        String select = "SELECT id, ip, subnet, rdns, brand, channels, locale, view_distance, skin_parts, "
                + "main_hand, chat_flags FROM mg_sessions WHERE id > ? ORDER BY id LIMIT 1000";
        String update = "UPDATE mg_sessions SET ip_hash = COALESCE(?, ip_hash), subnet_hash = COALESCE(?, subnet_hash), "
                + "rdns_hash = COALESCE(?, rdns_hash), channels_hash = COALESCE(?, channels_hash), "
                + "fingerprint = COALESCE(?, fingerprint) WHERE id = ?";
        try (Connection c = database.getConnection()) {
            while (true) {
                int batch = 0;
                try (PreparedStatement ps = c.prepareStatement(select);
                     PreparedStatement up = c.prepareStatement(update)) {
                    ps.setLong(1, lastId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            lastId = rs.getLong("id");
                            SessionSnapshot s = new SessionSnapshot();
                            s.ip = rs.getString("ip");
                            s.subnet = rs.getString("subnet");
                            s.rdns = rs.getString("rdns");
                            s.brand = rs.getString("brand");
                            s.channels = rs.getString("channels");
                            s.locale = rs.getString("locale");
                            int vd = rs.getInt("view_distance");
                            s.viewDistance = rs.wasNull() ? null : vd;
                            int sp = rs.getInt("skin_parts");
                            s.skinParts = rs.wasNull() ? null : sp;
                            s.mainHand = rs.getString("main_hand");
                            s.chatFlags = rs.getString("chat_flags");
                            up.setString(1, hashing.hmac(s.ip));
                            up.setString(2, hashing.hmac(s.subnet));
                            up.setString(3, hashing.hmac(s.rdns));
                            up.setString(4, hashing.hmac(s.channels));
                            up.setString(5, Fingerprints.build(s, hashing));
                            up.setLong(6, lastId);
                            up.addBatch();
                            batch++;
                        }
                    }
                    if (batch > 0) {
                        up.executeBatch();
                    }
                }
                done += batch;
                if (batch < 1000) {
                    return done;
                }
            }
        }
    }

    /** Copia del file in .bak/<Plugin>/ (plugins/ sostituita con .bak/), come ConfigAlign. */
    private static boolean backup(JavaPlugin plugin, File file) {
        try {
            File pluginsDir = plugin.getDataFolder().getParentFile();
            File bakDir = new File(new File(pluginsDir.getParentFile(), ".bak"), plugin.getDataFolder().getName());
            if (!bakDir.isDirectory() && !bakDir.mkdirs()) {
                return false;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            Files.copy(file.toPath(), new File(bakDir, file.getName() + ".bak-" + stamp).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
