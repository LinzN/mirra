/*
 * Copyright (c) 2026 MirraNET, Niklas Linz. All rights reserved.
 *
 * This file is part of the MirraNET project and is licensed under the
 * GNU Lesser General Public License v3.0 (LGPLv3).
 *
 * You may use, distribute and modify this code under the terms
 * of the LGPLv3 license. You should have received a copy of the
 * license along with this file. If not, see <https://www.gnu.org/licenses/lgpl-3.0.html>
 * or contact: niklas.linz@mirranet.de
 */

package de.linzn.mirra.identitySystem;

import de.linzn.stem.api.StemLogger;
import de.linzn.stem.api.data.DataService;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Database access for user profiles ({@code plugin_mirra_agent_user*}) and reminders ({@code plugin_mirra_agent_reminder}).
 */
public class UserRepository {

    static final String[] SCHEMA = {
            """
            CREATE TABLE IF NOT EXISTS plugin_mirra_agent_user (
              id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              name VARCHAR(191) NOT NULL,
              guest TINYINT NOT NULL DEFAULT 0,
              full_name VARCHAR(191) NULL,
              nickname VARCHAR(191) NULL,
              birthday VARCHAR(16) NULL,
              language VARCHAR(32) NULL,
              timezone VARCHAR(64) NULL,
              location VARCHAR(191) NULL,
              preferred_channel VARCHAR(32) NULL,
              notes TEXT NULL,
              created DATETIME NOT NULL,
              updated DATETIME NOT NULL,
              last_seen DATETIME NULL,
              UNIQUE KEY uk_mirra_agent_user_name (name)
            ) DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS plugin_mirra_agent_user_channel (
              id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              user_id INT NOT NULL,
              source VARCHAR(32) NOT NULL,
              address VARCHAR(191) NOT NULL,
              display_name VARCHAR(191) NULL,
              created DATETIME NOT NULL,
              last_seen DATETIME NULL,
              UNIQUE KEY uk_mirra_agent_user_channel (source, address),
              INDEX idx_mirra_agent_user_channel_user (user_id)
            ) DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS plugin_mirra_agent_user_permission (
              user_id INT NOT NULL,
              permission VARCHAR(64) NOT NULL,
              PRIMARY KEY (user_id, permission)
            ) DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS plugin_mirra_agent_user_attribute (
              user_id INT NOT NULL,
              attr_key VARCHAR(64) NOT NULL,
              attr_value TEXT NOT NULL,
              source VARCHAR(16) NOT NULL,
              updated DATETIME NOT NULL,
              PRIMARY KEY (user_id, attr_key)
            ) DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS plugin_mirra_agent_reminder (
              id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              user_id INT NOT NULL,
              channel_id INT NOT NULL,
              content TEXT NOT NULL,
              remind_at DATETIME NOT NULL,
              active TINYINT NOT NULL DEFAULT 1,
              created DATETIME NOT NULL,
              INDEX idx_mirra_agent_reminder_active (active)
            ) DEFAULT CHARSET=utf8mb4"""
    };

    private final DataService data;
    private final StemLogger logger;

    public UserRepository(DataService data, StemLogger logger) {
        this.data = data;
        this.logger = logger;
    }

    public DataService data() {
        return data;
    }

    public void createSchema() throws SQLException {
        this.data.withConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String sql : SCHEMA) {
                    statement.execute(sql);
                }
            }
            return null;
        });
    }

    public int countUsers() throws SQLException {
        return this.data.withConnection(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM plugin_mirra_agent_user")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /**
     * @return all users with channels, permissions and attributes
     */
    public Map<Integer, IdentityUser> loadUsers() throws SQLException {
        Map<Integer, IdentityUser> users = new LinkedHashMap<>();
        this.data.withConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                try (ResultSet rs = statement.executeQuery("SELECT * FROM plugin_mirra_agent_user ORDER BY id")) {
                    while (rs.next()) {
                        IdentityUser user = new IdentityUser(rs.getInt("id"), rs.getString("name"), rs.getBoolean("guest"));
                        for (String field : IdentityUser.PROFILE_FIELDS) {
                            user.setField(field, rs.getString(field));
                        }
                        user.setCreated(time(rs.getTimestamp("created")));
                        user.setLastSeen(time(rs.getTimestamp("last_seen")));
                        users.put(user.getId(), user);
                    }
                }
                try (ResultSet rs = statement.executeQuery("SELECT * FROM plugin_mirra_agent_user_channel ORDER BY id")) {
                    while (rs.next()) {
                        IdentityUser user = users.get(rs.getInt("user_id"));
                        TokenSource source;
                        try {
                            source = TokenSource.valueOf(rs.getString("source"));
                        } catch (IllegalArgumentException e) {
                            this.logger.warning("Unknown channel source " + rs.getString("source") + " of channel " + rs.getInt("id"));
                            continue;
                        }
                        if (user != null) {
                            user.assignUserToken(new UserToken(rs.getInt("id"), rs.getString("address"), source, user.getId(), rs.getString("display_name")));
                        }
                    }
                }
                try (ResultSet rs = statement.executeQuery("SELECT * FROM plugin_mirra_agent_user_permission")) {
                    while (rs.next()) {
                        IdentityUser user = users.get(rs.getInt("user_id"));
                        if (user == null) {
                            continue;
                        }
                        try {
                            user.addPermission(AiPermissions.valueOf(rs.getString("permission")));
                        } catch (IllegalArgumentException e) {
                            this.logger.warning("Unknown permission " + rs.getString("permission") + " of user " + user.getIdentityName());
                        }
                    }
                }
                try (ResultSet rs = statement.executeQuery("SELECT * FROM plugin_mirra_agent_user_attribute")) {
                    while (rs.next()) {
                        IdentityUser user = users.get(rs.getInt("user_id"));
                        if (user != null) {
                            user.setAttribute(rs.getString("attr_key"), rs.getString("attr_value"));
                        }
                    }
                }
            }
            return null;
        });
        return users;
    }

    public int insertUser(Connection connection, String name, boolean guest, LocalDateTime created) throws SQLException {
        String sql = "INSERT INTO plugin_mirra_agent_user (name, guest, created, updated) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setBoolean(2, guest);
            statement.setTimestamp(3, Timestamp.valueOf(created));
            statement.setTimestamp(4, Timestamp.valueOf(created));
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    public int insertUser(String name, boolean guest) throws SQLException {
        return this.data.withConnection(connection -> this.insertUser(connection, name, guest, LocalDateTime.now()));
    }

    public int insertChannel(Connection connection, int userId, TokenSource source, String address, String displayName) throws SQLException {
        String sql = "INSERT INTO plugin_mirra_agent_user_channel (user_id, source, address, display_name, created) VALUES (?, ?, ?, ?, NOW())";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, userId);
            statement.setString(2, source.name());
            statement.setString(3, address);
            statement.setString(4, displayName);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    public int insertChannel(int userId, TokenSource source, String address, String displayName) throws SQLException {
        return this.data.withConnection(connection -> this.insertChannel(connection, userId, source, address, displayName));
    }

    public void moveChannel(int channelId, int userId) throws SQLException {
        this.execute("UPDATE plugin_mirra_agent_user_channel SET user_id = ? WHERE id = ?", userId, channelId);
    }

    public void deleteChannel(int channelId) throws SQLException {
        this.execute("DELETE FROM plugin_mirra_agent_user_channel WHERE id = ?", channelId);
    }

    public void setPermission(Connection connection, int userId, AiPermissions permission, boolean granted) throws SQLException {
        String sql = granted
                ? "INSERT IGNORE INTO plugin_mirra_agent_user_permission (user_id, permission) VALUES (?, ?)"
                : "DELETE FROM plugin_mirra_agent_user_permission WHERE user_id = ? AND permission = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            statement.setString(2, permission.name());
            statement.executeUpdate();
        }
    }

    public void setPermission(int userId, AiPermissions permission, boolean granted) throws SQLException {
        this.data.withConnection(connection -> {
            this.setPermission(connection, userId, permission, granted);
            return null;
        });
    }

    /**
     * @param field one of {@link IdentityUser#PROFILE_FIELDS}
     */
    public void setField(int userId, String field, String value) throws SQLException {
        if (!IdentityUser.PROFILE_FIELDS.contains(field)) {
            throw new IllegalArgumentException("Unknown profile field " + field);
        }
        this.data.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE plugin_mirra_agent_user SET " + field + " = ?, updated = NOW() WHERE id = ?")) {
                statement.setString(1, value == null || value.isBlank() ? null : value);
                statement.setInt(2, userId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void setGuest(int userId, boolean guest) throws SQLException {
        this.execute("UPDATE plugin_mirra_agent_user SET guest = ?, updated = NOW() WHERE id = ?", guest ? 1 : 0, userId);
    }

    public void setAttribute(int userId, String key, String value, String source) throws SQLException {
        this.data.withConnection(connection -> {
            if (value == null || value.isBlank()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM plugin_mirra_agent_user_attribute WHERE user_id = ? AND attr_key = ?")) {
                    statement.setInt(1, userId);
                    statement.setString(2, key);
                    statement.executeUpdate();
                }
                return null;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO plugin_mirra_agent_user_attribute (user_id, attr_key, attr_value, source, updated) VALUES (?, ?, ?, ?, NOW()) "
                            + "ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), source = VALUES(source), updated = NOW()")) {
                statement.setInt(1, userId);
                statement.setString(2, key);
                statement.setString(3, value);
                statement.setString(4, source);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void touch(int userId, int channelId, String displayName) throws SQLException {
        this.data.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE plugin_mirra_agent_user SET last_seen = NOW() WHERE id = ?")) {
                statement.setInt(1, userId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE plugin_mirra_agent_user_channel SET last_seen = NOW(), display_name = COALESCE(?, display_name) WHERE id = ?")) {
                statement.setString(1, displayName);
                statement.setInt(2, channelId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Move everything of one user to another and delete the first: channels, permissions, attributes (existing
     * ones of the target win), reminders and long term memories.
     */
    public void mergeUsers(int fromId, int intoId) throws SQLException {
        this.data.withConnection(connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String[] statements = {
                        "UPDATE plugin_mirra_agent_user_channel SET user_id = ? WHERE user_id = ?",
                        "INSERT IGNORE INTO plugin_mirra_agent_user_permission (user_id, permission) SELECT ?, permission FROM plugin_mirra_agent_user_permission WHERE user_id = ?",
                        "INSERT IGNORE INTO plugin_mirra_agent_user_attribute (user_id, attr_key, attr_value, source, updated) SELECT ?, attr_key, attr_value, source, updated FROM plugin_mirra_agent_user_attribute WHERE user_id = ?",
                        "UPDATE plugin_mirra_agent_reminder SET user_id = ? WHERE user_id = ?"
                };
                for (String sql : statements) {
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setInt(1, intoId);
                        statement.setInt(2, fromId);
                        statement.executeUpdate();
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("UPDATE plugin_mirra_agent_memory SET owner = ? WHERE owner = ?")) {
                    statement.setString(1, "user:" + intoId);
                    statement.setString(2, "user:" + fromId);
                    statement.executeUpdate();
                }
                for (String table : new String[]{"plugin_mirra_agent_user_permission", "plugin_mirra_agent_user_attribute"}) {
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE user_id = ?")) {
                        statement.setInt(1, fromId);
                        statement.executeUpdate();
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM plugin_mirra_agent_user WHERE id = ?")) {
                    statement.setInt(1, fromId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
            return null;
        });
    }

    /* reminders */

    public record StoredReminder(int id, int userId, int channelId, String content, LocalDateTime remindAt) {
    }

    public List<StoredReminder> loadActiveReminders() throws SQLException {
        return this.data.withConnection(connection -> {
            List<StoredReminder> reminders = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT * FROM plugin_mirra_agent_reminder WHERE active = 1 ORDER BY remind_at")) {
                while (rs.next()) {
                    reminders.add(new StoredReminder(rs.getInt("id"), rs.getInt("user_id"), rs.getInt("channel_id"),
                            rs.getString("content"), time(rs.getTimestamp("remind_at"))));
                }
            }
            return reminders;
        });
    }

    public int insertReminder(int userId, int channelId, String content, LocalDateTime remindAt) throws SQLException {
        return this.data.withConnection(connection -> {
            String sql = "INSERT INTO plugin_mirra_agent_reminder (user_id, channel_id, content, remind_at, active, created) VALUES (?, ?, ?, ?, 1, NOW())";
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setInt(1, userId);
                statement.setInt(2, channelId);
                statement.setString(3, content);
                statement.setTimestamp(4, Timestamp.valueOf(remindAt));
                statement.executeUpdate();
                return generatedId(statement);
            }
        });
    }

    public void deactivateReminder(int id) throws SQLException {
        this.execute("UPDATE plugin_mirra_agent_reminder SET active = 0 WHERE id = ?", id);
    }

    /* helpers */

    private void execute(String sql, int... values) throws SQLException {
        this.data.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < values.length; i++) {
                    statement.setInt(i + 1, values[i]);
                }
                statement.executeUpdate();
            }
            return null;
        });
    }

    static int generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (keys.next()) {
                return keys.getInt(1);
            }
        }
        throw new SQLException("No id generated");
    }

    static LocalDateTime time(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
