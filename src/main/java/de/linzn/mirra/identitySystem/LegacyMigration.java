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

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * One-time migration of the identity tables of earlier Mirra versions into the user profiles:
 * <ul>
 *     <li>{@code plugin_mirra_identity_user} becomes {@code plugin_mirra_agent_user}</li>
 *     <li>{@code plugin_mirra_identity_user_tokens} becomes {@code plugin_mirra_agent_user_channel}</li>
 *     <li>{@code plugin_mirra_identity_user_permissions} becomes {@code plugin_mirra_agent_user_permission}</li>
 *     <li>active rows of {@code plugin_mirra_reminder} become {@code plugin_mirra_agent_reminder}</li>
 *     <li>long term memory owners {@code identity:<name>} and {@code token:<source>:<address>} become {@code user:<id>};
 *         guests with memories get a guest profile</li>
 * </ul>
 * The old tables are not changed. The migration runs in one transaction and only while there are no profiles yet.
 */
public final class LegacyMigration {

    public record Result(int users, int channels, int permissions, int reminders, int memoryOwners, int guestProfiles) {
        @Override
        public String toString() {
            return users + " users, " + channels + " channels, " + permissions + " permissions, " + reminders
                    + " reminders, " + memoryOwners + " memory owners, " + guestProfiles + " guest profiles";
        }
    }

    private LegacyMigration() {
    }

    public static Result run(UserRepository repository, StemLogger logger) throws SQLException {
        return repository.data().withConnection(connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Result result = migrate(connection, repository, logger);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    private record Channel(int userId, int channelId) {
    }

    private static Result migrate(Connection connection, UserRepository repository, StemLogger logger) throws SQLException {
        LocalDateTime now = LocalDateTime.now();
        Map<Integer, Integer> userIds = new HashMap<>();
        Map<Integer, String> userNames = new HashMap<>();
        Set<String> takenNames = new HashSet<>();
        Map<Integer, Channel> channelsByOldToken = new HashMap<>();
        Map<String, Channel> channelsByAddress = new HashMap<>();
        int users = 0;
        int channels = 0;
        int permissions = 0;
        int reminders = 0;
        int memoryOwners = 0;
        int guestProfiles = 0;

        if (tableExists(connection, "plugin_mirra_identity_user")) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT id, identity_name FROM plugin_mirra_identity_user ORDER BY id")) {
                while (rs.next()) {
                    String name = unique(rs.getString("identity_name"), takenNames);
                    int newId = repository.insertUser(connection, name, false, now);
                    userIds.put(rs.getInt("id"), newId);
                    userNames.put(newId, rs.getString("identity_name"));
                    users++;
                }
            }
        }
        if (tableExists(connection, "plugin_mirra_identity_user_tokens")) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT id, identity_user_id, identity_token, token_source FROM plugin_mirra_identity_user_tokens ORDER BY id")) {
                while (rs.next()) {
                    Integer userId = userIds.get(rs.getInt("identity_user_id"));
                    TokenSource source = source(rs.getString("token_source"));
                    String address = rs.getString("identity_token");
                    if (userId == null || source == null || address == null || address.isBlank()) {
                        logger.warning("Migration: skip token " + rs.getInt("id") + " (unknown user or source)");
                        continue;
                    }
                    String key = source.name() + ":" + address.toLowerCase(Locale.ROOT);
                    Channel channel = channelsByAddress.get(key);
                    if (channel == null) {
                        channel = new Channel(userId, repository.insertChannel(connection, userId, source, address, null));
                        channelsByAddress.put(key, channel);
                        channels++;
                    } else {
                        logger.warning("Migration: " + key + " is assigned twice, kept for the first user");
                    }
                    channelsByOldToken.put(rs.getInt("id"), channel);
                }
            }
        }
        if (tableExists(connection, "plugin_mirra_identity_user_permissions")) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT identity_user_id, permission FROM plugin_mirra_identity_user_permissions")) {
                while (rs.next()) {
                    Integer userId = userIds.get(rs.getInt("identity_user_id"));
                    List<AiPermissions> mapped = AiPermissions.parse(rs.getString("permission"));
                    if (mapped.isEmpty()) {
                        if (AiPermissions.fromLegacy(rs.getString("permission")) == null) {
                            logger.warning("Migration: skip unknown permission " + rs.getString("permission"));
                        }
                        continue;
                    }
                    if (userId != null) {
                        for (AiPermissions permission : mapped) {
                            repository.setPermission(connection, userId, permission, true);
                        }
                        permissions++;
                    }
                }
            }
        }
        if (tableExists(connection, "plugin_mirra_reminder")) {
            String insert = "INSERT INTO plugin_mirra_agent_reminder (user_id, channel_id, content, remind_at, active, created) VALUES (?, ?, ?, ?, 1, ?)";
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT * FROM plugin_mirra_reminder WHERE active = 1");
                 PreparedStatement target = connection.prepareStatement(insert)) {
                while (rs.next()) {
                    Channel channel = channelsByOldToken.get(rs.getInt("user_token_id"));
                    Timestamp at = rs.getTimestamp("reminder_datetime");
                    if (channel == null || at == null) {
                        logger.warning("Migration: skip reminder " + rs.getInt("id") + " (no user channel)");
                        continue;
                    }
                    Timestamp created = rs.getTimestamp("created");
                    target.setInt(1, channel.userId());
                    target.setInt(2, channel.channelId());
                    target.setString(3, rs.getString("reminder_content"));
                    target.setTimestamp(4, at);
                    target.setTimestamp(5, created == null ? Timestamp.valueOf(now) : created);
                    target.executeUpdate();
                    reminders++;
                }
            }
        }
        if (tableExists(connection, "plugin_mirra_agent_memory")) {
            /* registered users: identity:<name> */
            for (Map.Entry<Integer, String> entry : userNames.entrySet()) {
                memoryOwners += updateOwner(connection, "identity:" + entry.getValue().toLowerCase(Locale.ROOT), "user:" + entry.getKey());
            }
            /* guests: token:<source>:<address> */
            List<String> tokenOwners = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT DISTINCT owner FROM plugin_mirra_agent_memory WHERE owner LIKE 'token:%'")) {
                while (rs.next()) {
                    tokenOwners.add(rs.getString(1));
                }
            }
            for (String owner : tokenOwners) {
                String[] parts = owner.split(":", 3);
                TokenSource source = parts.length == 3 ? source(parts[1].toUpperCase(Locale.ROOT)) : null;
                if (source == null || source == TokenSource.INTERNAL) {
                    continue;
                }
                String key = source.name() + ":" + parts[2].toLowerCase(Locale.ROOT);
                Channel channel = channelsByAddress.get(key);
                if (channel == null) {
                    String name = unique("Guest " + tail(parts[2]), takenNames);
                    int userId = repository.insertUser(connection, name, true, now);
                    channel = new Channel(userId, repository.insertChannel(connection, userId, source, parts[2], null));
                    channelsByAddress.put(key, channel);
                    guestProfiles++;
                }
                memoryOwners += updateOwner(connection, owner, "user:" + channel.userId());
            }
        }
        return new Result(users, channels, permissions, reminders, memoryOwners, guestProfiles);
    }

    private static int updateOwner(Connection connection, String from, String to) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE plugin_mirra_agent_memory SET owner = ? WHERE owner = ?")) {
            statement.setString(1, to);
            statement.setString(2, from);
            return statement.executeUpdate() > 0 ? 1 : 0;
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getTables(connection.getCatalog(), null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private static TokenSource source(String value) {
        try {
            return value == null ? null : TokenSource.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String unique(String base, Set<String> taken) {
        String name = base == null || base.isBlank() ? "User" : base.trim();
        String candidate = name;
        int counter = 2;
        while (taken.contains(candidate.toLowerCase(Locale.ROOT))) {
            candidate = name + " (" + counter++ + ")";
        }
        taken.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }

    private static String tail(String address) {
        String plain = address.contains("@") ? address.substring(0, address.indexOf('@')) : address;
        return plain.length() <= 4 ? plain : plain.substring(plain.length() - 4);
    }
}
