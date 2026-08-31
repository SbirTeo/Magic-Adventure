package com.teolo.magixauth.db;

import com.teolo.magixauth.model.Account;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Tutte le domande che il gate fa al database, in un posto solo.
 *
 * Due regole valgono per ogni metodo qui dentro:
 *
 *  - si legge e si scrive `users`, la tabella del sito, non una copia. Il prezzo e' che le
 *    colonne hanno i nomi che il sito ha scelto a suo tempo (`mc_uuid`, `mc_username`); il
 *    guadagno e' che non esiste nessuna sincronizzazione da mantenere fra le due porte, e
 *    che login.php funziona con le credenziali di qui senza modifiche.
 *  - niente password in chiaro, mai, nemmeno di passaggio: da qui escono solo impronte
 *    bcrypt, ed e' Password a confrontarle.
 */
public final class AuthDao {

    /** Le colonne di `users` che interessano al gate. */
    private static final String CAMPI =
            "id, mc_uuid, mc_username, password_hash, totp_secret, totp_last_step, " +
            "totp_attempts, totp_locked_until, is_admin";

    private final Database database;

    public AuthDao(Database database) {
        this.database = database;
    }

    // -----------------------------------------------------------------------------
    // Lettura dell'account
    // -----------------------------------------------------------------------------

    /**
     * L'account di un nome, se esiste.
     *
     * E' la domanda del pre-login, e si cerca per NOME e non per UUID di proposito: in
     * offline mode l'UUID lo decidiamo noi, quindi il nome e' l'unica cosa che il giocatore
     * porta con se' quando bussa.
     */
    public Account perNome(String nome) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + CAMPI + " FROM users WHERE mc_username = ? LIMIT 1")) {
            ps.setString(1, nome);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? leggi(rs) : null;
            }
        }
    }

    public Account perUuid(UUID uuid) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + CAMPI + " FROM users WHERE mc_uuid = ? LIMIT 1")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? leggi(rs) : null;
            }
        }
    }

    private Account leggi(ResultSet rs) throws SQLException {
        UUID uuid;
        try {
            uuid = UUID.fromString(rs.getString("mc_uuid"));
        } catch (IllegalArgumentException | NullPointerException e) {
            // UUID storto in tabella: l'account c'e' ma non e' agganciabile a un giocatore.
            // Meglio trattarlo come inesistente che far entrare qualcuno per sbaglio.
            return null;
        }
        long ultimoPasso = rs.getLong("totp_last_step");
        boolean passoNullo = rs.wasNull();
        Timestamp bloccato = rs.getTimestamp("totp_locked_until");
        return new Account(
                rs.getInt("id"),
                uuid,
                rs.getString("mc_username"),
                rs.getString("password_hash"),
                rs.getString("totp_secret"),
                passoNullo ? null : ultimoPasso,
                rs.getInt("totp_attempts"),
                bloccato == null ? null : bloccato.toLocalDateTime(),
                rs.getInt("is_admin") == 1);
    }

    // -----------------------------------------------------------------------------
    // Registrazione e password
    // -----------------------------------------------------------------------------

    /**
     * Crea l'account. Da questo momento le stesse credenziali aprono anche il sito:
     * login.php cerca per `mc_username` e verifica `password_hash`, cioe' esattamente
     * le due colonne che si scrivono qui.
     *
     * @return l'id assegnato, o -1 se il nome era gia' stato preso nel frattempo
     */
    public int registra(UUID uuid, String nome, String passwordHash, UUID premiumUuid) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO users (mc_uuid, mc_username, password_hash, premium_uuid, " +
                     "premium_checked_at, registered_in_game_at, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, NOW(), NOW())", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, nome);
            ps.setString(3, passwordHash);
            ps.setString(4, premiumUuid == null ? null : premiumUuid.toString());
            ps.setTimestamp(5, premiumUuid == null ? null : Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (SQLException e) {
            // 1062 = chiave duplicata: due connessioni con lo stesso nome nello stesso istante.
            if (e.getErrorCode() == 1062) {
                return -1;
            }
            throw e;
        }
    }

    /**
     * Cambia la password.
     *
     * `session_epoch` va incrementata insieme, o chi avesse rubato l'account resterebbe
     * comodamente collegato sul sito anche dopo che il proprietario ha rimesso a posto le
     * chiavi. E' la colonna che il sito usa proprio per far cadere le sessioni aperte, e i
     * "resta collegato" vanno buttati per lo stesso motivo.
     */
    public void cambiaPassword(int idSito, String passwordHash) throws SQLException {
        try (Connection c = database.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET password_hash = ?, session_epoch = session_epoch + 1 WHERE id = ?")) {
                ps.setString(1, passwordHash);
                ps.setInt(2, idSito);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM remember_tokens WHERE user_id = ?")) {
                ps.setInt(1, idSito);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Fa cadere ogni sessione aperta sul sito, senza cambiare la password.
     *
     * Due cose separate, e servono entrambe: `session_epoch` invalida le sessioni in corso,
     * mentre i "resta collegato" vivono in una tabella a parte e sopravvivrebbero alla
     * chiusura del browser — sono proprio quelli che riaprirebbero l'accesso da soli.
     */
    public void chiudiSessioniSito(int idSito) throws SQLException {
        try (Connection c = database.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET session_epoch = session_epoch + 1 WHERE id = ?")) {
                ps.setInt(1, idSito);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM remember_tokens WHERE user_id = ?")) {
                ps.setInt(1, idSito);
                ps.executeUpdate();
            }
        }
    }

    /** Il nome cambia solo di maiuscole/minuscole: si allinea la riga, senza toccare l'UUID. */
    public void allineaNome(int idSito, String nome) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE users SET mc_username = ?, last_login = NOW() WHERE id = ?")) {
            ps.setString(1, nome);
            ps.setInt(2, idSito);
            ps.executeUpdate();
        }
    }

    // -----------------------------------------------------------------------------
    // Dispositivi riconosciuti
    // -----------------------------------------------------------------------------

    /**
     * Questo dispositivo ha ancora una sessione buona?
     *
     * @return null se non ce n'e' una valida; altrimenti se aveva superato anche l'OTP
     */
    public Boolean sessione(UUID uuid, String device) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT otp_ok_at FROM auth_sessions " +
                     "WHERE mc_uuid = ? AND device = ? AND expires_at > NOW() LIMIT 1")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, device);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getTimestamp("otp_ok_at") != null;
            }
        }
    }

    public void salvaSessione(UUID uuid, String device, String ip, int ore, boolean otpOk) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO auth_sessions (mc_uuid, device, ip, password_ok_at, otp_ok_at, expires_at) " +
                     "VALUES (?, ?, ?, NOW(), ?, DATE_ADD(NOW(), INTERVAL ? HOUR)) " +
                     "ON DUPLICATE KEY UPDATE ip = VALUES(ip), password_ok_at = NOW(), " +
                     "otp_ok_at = VALUES(otp_ok_at), expires_at = VALUES(expires_at)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, device);
            ps.setString(3, ip);
            ps.setTimestamp(4, otpOk ? Timestamp.valueOf(LocalDateTime.now()) : null);
            ps.setInt(5, ore);
            ps.executeUpdate();
        }
    }

    /**
     * Dimentica UN dispositivo solo.
     *
     * Serve alla rotazione del gettone: appena il client ne riceve uno nuovo, il vecchio non
     * deve piu' aprire niente, altrimenti chi l'avesse intercettato lo userebbe per sempre.
     */
    public void dimenticaDispositivo(UUID uuid, String device) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM auth_sessions WHERE mc_uuid = ? AND device = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, device);
            ps.executeUpdate();
        }
    }

    /** Al cambio password cadono tutti i dispositivi: era il senso del cambio. */
    public void revocaSessioni(UUID uuid) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM auth_sessions WHERE mc_uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    /** Manutenzione all'avvio: le righe scadute non servono a nessuno. */
    public int potaSessioniScadute() throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM auth_sessions WHERE expires_at < NOW()")) {
            return ps.executeUpdate();
        }
    }

    // -----------------------------------------------------------------------------
    // Posizione
    // -----------------------------------------------------------------------------

    /**
     * Mette da parte dov'era il giocatore, prima di farlo comparire allo spawn.
     *
     * Deve stare nel database e non nel file .dat: se si disconnette mentre e' fermo al
     * cancello, il server salverebbe lo spawn come sua ultima posizione e quella vera
     * sparirebbe per sempre.
     */
    public void salvaPosizione(UUID uuid, String world, double x, double y, double z,
                               float yaw, float pitch) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO auth_positions (mc_uuid, world, x, y, z, yaw, pitch, saved_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, NOW()) " +
                     "ON DUPLICATE KEY UPDATE world = VALUES(world), x = VALUES(x), y = VALUES(y), " +
                     "z = VALUES(z), yaw = VALUES(yaw), pitch = VALUES(pitch), saved_at = NOW()")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, world);
            ps.setDouble(3, x);
            ps.setDouble(4, y);
            ps.setDouble(5, z);
            ps.setFloat(6, yaw);
            ps.setFloat(7, pitch);
            ps.executeUpdate();
        }
    }

    /** @return {world, x, y, z, yaw, pitch} oppure null se non c'e' niente da ripristinare */
    public Object[] leggiPosizione(UUID uuid) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT world, x, y, z, yaw, pitch FROM auth_positions WHERE mc_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Object[]{rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"),
                        rs.getDouble("z"), rs.getFloat("yaw"), rs.getFloat("pitch")};
            }
        }
    }

    public void cancellaPosizione(UUID uuid) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM auth_positions WHERE mc_uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    // -----------------------------------------------------------------------------
    // Tentativi falliti
    // -----------------------------------------------------------------------------

    /**
     * Fino a quando e' bloccato questo indirizzo, se lo e'.
     *
     * Il conto sta sull'INDIRIZZO e non sul nome: bloccare per nome vorrebbe dire che
     * chiunque, sbagliando apposta la password di un altro, puo' chiuderlo fuori dal server
     * sapendone soltanto il nick.
     */
    public LocalDateTime bloccatoFino(String ip) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT locked_until FROM auth_attempts WHERE ip = ? AND locked_until > NOW()")) {
            ps.setString(1, ip);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getTimestamp("locked_until").toLocalDateTime() : null;
            }
        }
    }

    /**
     * @param nome l'ultimo nick provato da questo indirizzo, annotato per poter poi ritrovare
     *             la riga da sbloccare: chi amministra conosce il giocatore, non il suo IP,
     *             e il giocatore appena bloccato non e' online per poterglielo chiedere.
     *             Non cambia di una virgola a chi si applica il blocco, che resta l'indirizzo.
     * @return quanti tentativi falliti risultano ora a questo indirizzo
     */
    public int registraFallimento(String ip, String nome, int massimo, int minutiBlocco) throws SQLException {
        try (Connection c = database.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO auth_attempts (ip, failures, last_at, last_username) VALUES (?, 1, NOW(), ?) " +
                    "ON DUPLICATE KEY UPDATE failures = failures + 1, last_at = NOW(), last_username = VALUES(last_username)")) {
                ps.setString(1, ip);
                ps.setString(2, nome);
                ps.executeUpdate();
            }
            int failures = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT failures FROM auth_attempts WHERE ip = ?")) {
                ps.setString(1, ip);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        failures = rs.getInt(1);
                    }
                }
            }
            if (failures >= massimo) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE auth_attempts SET locked_until = DATE_ADD(NOW(), INTERVAL ? MINUTE), " +
                        "failures = 0 WHERE ip = ?")) {
                    ps.setInt(1, minutiBlocco);
                    ps.setString(2, ip);
                    ps.executeUpdate();
                }
            }
            return failures;
        }
    }

    public void azzeraTentativi(String ip) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM auth_attempts WHERE ip = ?")) {
            ps.setString(1, ip);
            ps.executeUpdate();
        }
    }

    /**
     * Gli indirizzi bloccati ADESSO da cui si e' provato per ultimo questo nome.
     *
     * Sono piu' d'uno quando la stessa persona ha provato da casa e dal telefono: si
     * sbloccano tutti, altrimenti la si libera da una porta e la si lascia chiusa dall'altra.
     */
    public List<String> indirizziBloccatiDi(String nome) throws SQLException {
        List<String> fuori = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ip FROM auth_attempts WHERE last_username = ? AND locked_until > NOW()")) {
            ps.setString(1, nome);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    fuori.add(rs.getString("ip"));
                }
            }
        }
        return fuori;
    }

    /**
     * Gli indirizzi bloccati ADESSO da cui questo giocatore era gia' entrato in passato.
     *
     * E' la seconda rete: l'annotazione del nome esiste solo dai tentativi fatti dopo
     * l'aggiornamento, mentre i dispositivi ricordati raccontano da dove gioca di solito.
     */
    public List<String> indirizziBloccatiNoti(UUID uuid) throws SQLException {
        List<String> fuori = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT t.ip FROM auth_attempts t " +
                     "JOIN auth_sessions s ON s.ip = t.ip " +
                     "WHERE s.mc_uuid = ? AND t.locked_until > NOW()")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    fuori.add(rs.getString("ip"));
                }
            }
        }
        return fuori;
    }

    /** Toglie il blocco del codice in due passaggi (la stessa riga che guarda il sito). */
    public boolean sbloccaOtp(int idSito) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE users SET totp_attempts = 0, totp_locked_until = NULL " +
                     "WHERE id = ? AND (totp_locked_until IS NOT NULL OR totp_attempts > 0)")) {
            ps.setInt(1, idSito);
            return ps.executeUpdate() > 0;
        }
    }

    // -----------------------------------------------------------------------------
    // OTP: le stesse colonne che usa il sito
    // -----------------------------------------------------------------------------

    /**
     * Segna l'intervallo di trenta secondi appena speso.
     *
     * E' cio' che impedisce di riusare un codice gia' visto, e vale fra i due mondi: un
     * codice bruciato sul sito risulta bruciato anche in gioco, perche' la riga e' la stessa.
     */
    public void otpPassoSpeso(int idSito, long passo) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE users SET totp_last_step = ?, totp_attempts = 0, " +
                     "totp_locked_until = NULL WHERE id = ?")) {
            ps.setLong(1, passo);
            ps.setInt(2, idSito);
            ps.executeUpdate();
        }
    }

    public void otpFallito(int idSito, int massimo, int minutiBlocco) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE users SET totp_attempts = totp_attempts + 1, " +
                     "totp_locked_until = IF(totp_attempts + 1 >= ?, " +
                     "DATE_ADD(NOW(), INTERVAL ? MINUTE), totp_locked_until) WHERE id = ?")) {
            ps.setInt(1, massimo);
            ps.setInt(2, minutiBlocco);
            ps.setInt(3, idSito);
            ps.executeUpdate();
        }
    }

    // -----------------------------------------------------------------------------
    // Revoche chieste dal sito
    // -----------------------------------------------------------------------------

    /**
     * Raccoglie i "chiudi la sessione di gioco" premuti sul sito.
     *
     * La tabella e' una casella della posta, non uno stato: il sito ci lascia un biglietto,
     * noi lo leggiamo e lo strappiamo. Per questo la lettura e la cancellazione stanno nella
     * stessa transazione — se il server si spegnesse nel mezzo, il biglietto deve restare
     * li' per la prossima volta, non sparire senza aver fatto effetto.
     */
    public java.util.List<UUID> raccogliRevoche() throws SQLException {
        java.util.List<UUID> fuori = new java.util.ArrayList<>();
        try (Connection c = database.getConnection()) {
            boolean autoPrima = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT mc_uuid FROM otp_game_revoke FOR UPDATE");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            fuori.add(UUID.fromString(rs.getString(1)));
                        } catch (IllegalArgumentException ignored) {
                            // Biglietto con un UUID storto: si butta insieme agli altri.
                        }
                    }
                }
                if (!fuori.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM otp_game_revoke")) {
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoPrima);
            }
        }
        return fuori;
    }

    // -----------------------------------------------------------------------------
    // Annotazione premium
    // -----------------------------------------------------------------------------

    /**
     * Annota l'UUID Mojang del nome. Annota e basta: in gioco non viene usato.
     *
     * Serve al giorno in cui ci sara' la verifica premium vera: si sapra' gia' chi va
     * migrato e su quali nomi aspettarsi una contesa fra chi li ha registrati qui e chi li
     * possiede davvero.
     */
    public void annotaPremium(int idSito, UUID premiumUuid) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE users SET premium_uuid = ?, premium_checked_at = NOW() WHERE id = ?")) {
            ps.setString(1, premiumUuid == null ? null : premiumUuid.toString());
            ps.setInt(2, idSito);
            ps.executeUpdate();
        }
    }

    // -----------------------------------------------------------------------------
    // Utilita'
    // -----------------------------------------------------------------------------

    /** L'UUID che il server userebbe da solo per un nome mai visto, in offline mode. */
    public static UUID uuidOffline(String nome) {
        return UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + nome).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** I nomi si confrontano senza badare alle maiuscole, come fa il server. */
    public static String normalizza(String nome) {
        return nome == null ? "" : nome.toLowerCase(Locale.ROOT);
    }
}
