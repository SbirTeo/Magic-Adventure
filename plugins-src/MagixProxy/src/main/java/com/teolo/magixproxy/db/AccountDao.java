package com.teolo.magixproxy.db;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * The only question the proxy asks the site database: which UUID does this name have?
 *
 * It must give EXACTLY the answer of MagixAuth's gate (AuthDao.byName + uuidOffline): the
 * UUID is a property of the account, not a function of the name. Who already has a row takes
 * back the UUID they always had (the premium one too, from the online-mode days); a name never
 * seen gets the offline UUID the server would compute by itself.
 */
public final class AccountDao {

    private final Database database;

    public AccountDao(Database database) {
        this.database = database;
    }

    /** The UUID of the account with this name, or null if the name has no account. */
    public UUID uuidOf(String name) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT mc_uuid FROM users WHERE mc_username = ? LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                // Parsed exactly like AuthDao.read(): a malformed UUID means "no usable account",
                // so both sides fall back to the same offline UUID.
                try {
                    return UUID.fromString(rs.getString(1));
                } catch (IllegalArgumentException | NullPointerException e) {
                    return null;
                }
            }
        }
    }

    /** The UUID an offline-mode server would give to a never-seen name. */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
