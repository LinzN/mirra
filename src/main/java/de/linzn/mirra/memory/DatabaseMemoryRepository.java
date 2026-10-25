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

package de.linzn.mirra.memory;

import de.linzn.stem.api.data.DataService;
import de.linzn.stem.api.StemLogger;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MySQL implementation on the STEM database connection. Missing tables are created at startup.
 */
public class DatabaseMemoryRepository implements MemoryRepository {

    private static final String[] SCHEMA = {
            /* tables of earlier Mirra versions, created for new installations */
            """
            CREATE TABLE IF NOT EXISTS plugin_mirra_memory (
              id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              model VARCHAR(64) NOT NULL,
              identity VARCHAR(191) NOT NULL,
              role VARCHAR(32) NOT NULL,
              content MEDIUMTEXT NULL,
              date DATE NULL,
              function_name VARCHAR(128) NULL,
              function_json MEDIUMTEXT NULL,
              INDEX idx_mirra_memory_chat (model, identity)
            ) DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS plugin_mirra_identity_user (
              id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              identity_name VARCHAR(191) NOT NULL
            ) DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS plugin_mirra_identity_user_tokens (
              id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              identity_user_id INT NOT NULL,
              identity_token VARCHAR(191) NOT NULL,
              token_source VARCHAR(32) NOT NULL
            ) DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS plugin_mirra_identity_user_permissions (
              id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              identity_user_id INT NOT NULL,
              permission VARCHAR(64) NOT NULL
            ) DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS plugin_mirra_reminder (
              id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              user_token_id INT NOT NULL,
              reminder_content TEXT NOT NULL,
              reminder_datetime DATETIME NOT NULL,
              active TINYINT NOT NULL DEFAULT 1,
              created DATETIME NOT NULL
            ) DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS plugin_mirra_longterm_memory (
              id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              memory_data TEXT NOT NULL,
              date DATE NULL
            ) DEFAULT CHARSET=utf8mb4""",
            /* agent memory */
            """
            CREATE TABLE IF NOT EXISTS plugin_mirra_agent_memory (
              id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              owner VARCHAR(191) NOT NULL,
              category VARCHAR(32) NOT NULL,
              content TEXT NOT NULL,
              importance TINYINT NOT NULL DEFAULT 3,
              embedding MEDIUMTEXT NULL,
              embedding_model VARCHAR(64) NULL,
              source VARCHAR(32) NOT NULL,
              created DATETIME NOT NULL,
              updated DATETIME NOT NULL,
              last_used DATETIME NULL,
              use_count INT NOT NULL DEFAULT 0,
              INDEX idx_mirra_agent_memory_owner (owner)
            ) DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS plugin_mirra_agent_summary (
              model VARCHAR(64) NOT NULL,
              chat_key VARCHAR(191) NOT NULL,
              summary MEDIUMTEXT NOT NULL,
              covered_until BIGINT NOT NULL,
              updated DATETIME NOT NULL,
              PRIMARY KEY (model, chat_key)
            ) DEFAULT CHARSET=utf8mb4"""
    };

    private final DataService data;
    private final StemLogger logger;

    public DatabaseMemoryRepository(DataService data, StemLogger logger) {
        this.data = data;
        this.logger = logger;
    }

    /**
     * Create missing tables.
     */
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

    /**
     * Copy the entries of the old long term memory table into the agent memory as global facts.
     *
     * @return number of imported entries
     */
    public int importLegacyMemories() throws SQLException {
        return this.data.withConnection(connection -> {
            String sql = "INSERT INTO plugin_mirra_agent_memory (owner, category, content, importance, source, created, updated) "
                    + "SELECT ?, 'fact', memory_data, 3, 'legacy', COALESCE(date, NOW()), NOW() FROM plugin_mirra_longterm_memory "
                    + "WHERE memory_data IS NOT NULL AND memory_data <> ''";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, LongTermMemory.GLOBAL);
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public long appendMessage(String model, String chatKey, String role, String content) {
        try {
            return this.data.withConnection(connection -> {
                String sql = "INSERT INTO plugin_mirra_memory (model, identity, role, content, date) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, model);
                    statement.setString(2, chatKey);
                    statement.setString(3, role);
                    statement.setString(4, content);
                    statement.setDate(5, new java.sql.Date(System.currentTimeMillis()));
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        return keys.next() ? keys.getLong(1) : -1L;
                    }
                }
            });
        } catch (SQLException e) {
            this.logger.error("Could not store chat message", e);
            return -1;
        }
    }

    @Override
    public List<StoredMessage> recentMessages(String model, String chatKey, int limit) {
        String sql = "SELECT id, role, content FROM plugin_mirra_memory WHERE model = ? AND identity = ? "
                + "AND function_name IS NULL AND role IN ('user', 'assistant') AND content IS NOT NULL ORDER BY id DESC LIMIT ?";
        List<StoredMessage> messages = this.queryMessages(sql, model, chatKey, null, limit);
        Collections.reverse(messages);
        return messages;
    }

    @Override
    public List<StoredMessage> messagesAfter(String model, String chatKey, long afterId, int limit) {
        String sql = "SELECT id, role, content FROM plugin_mirra_memory WHERE model = ? AND identity = ? AND id > ? "
                + "AND function_name IS NULL AND role IN ('user', 'assistant') AND content IS NOT NULL ORDER BY id ASC LIMIT ?";
        return this.queryMessages(sql, model, chatKey, afterId, limit);
    }

    private List<StoredMessage> queryMessages(String sql, String model, String chatKey, Long afterId, int limit) {
        List<StoredMessage> messages = new ArrayList<>();
        try {
            this.data.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    int index = 1;
                    statement.setString(index++, model);
                    statement.setString(index++, chatKey);
                    if (afterId != null) {
                        statement.setLong(index++, afterId);
                    }
                    statement.setInt(index, limit);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            messages.add(new StoredMessage(rs.getLong("id"), rs.getString("role"), rs.getString("content")));
                        }
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            this.logger.error("Could not load chat history", e);
        }
        return messages;
    }

    @Override
    public Summary loadSummary(String model, String chatKey) {
        try {
            return this.data.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT summary, covered_until FROM plugin_mirra_agent_summary WHERE model = ? AND chat_key = ?")) {
                    statement.setString(1, model);
                    statement.setString(2, chatKey);
                    try (ResultSet rs = statement.executeQuery()) {
                        return rs.next() ? new Summary(rs.getString("summary"), rs.getLong("covered_until")) : Summary.EMPTY;
                    }
                }
            });
        } catch (SQLException e) {
            this.logger.error("Could not load conversation summary", e);
            return Summary.EMPTY;
        }
    }

    @Override
    public void saveSummary(String model, String chatKey, Summary summary) {
        try {
            this.data.withConnection(connection -> {
                String sql = "INSERT INTO plugin_mirra_agent_summary (model, chat_key, summary, covered_until, updated) VALUES (?, ?, ?, ?, NOW()) "
                        + "ON DUPLICATE KEY UPDATE summary = VALUES(summary), covered_until = VALUES(covered_until), updated = NOW()";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, model);
                    statement.setString(2, chatKey);
                    statement.setString(3, summary.text());
                    statement.setLong(4, summary.coveredUntil());
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            this.logger.error("Could not store conversation summary", e);
        }
    }

    @Override
    public List<MemoryEntry> loadMemories() {
        List<MemoryEntry> entries = new ArrayList<>();
        try {
            this.data.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM plugin_mirra_agent_memory");
                     ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new MemoryEntry(rs.getInt("id"), rs.getString("owner"), rs.getString("category"),
                                rs.getString("content"), rs.getInt("importance"), VectorMath.decode(rs.getString("embedding")),
                                rs.getString("embedding_model"), rs.getString("source"), instant(rs.getTimestamp("created")),
                                instant(rs.getTimestamp("updated")), instant(rs.getTimestamp("last_used")), rs.getInt("use_count")));
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            this.logger.error("Could not load long term memory", e);
        }
        return entries;
    }

    @Override
    public MemoryEntry insertMemory(MemoryEntry entry) {
        try {
            return this.data.withConnection(connection -> {
                String sql = "INSERT INTO plugin_mirra_agent_memory (owner, category, content, importance, embedding, embedding_model, source, created, updated, use_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)";
                try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, entry.owner());
                    statement.setString(2, entry.category());
                    statement.setString(3, entry.content());
                    statement.setInt(4, entry.importance());
                    statement.setString(5, VectorMath.encode(entry.embedding()));
                    statement.setString(6, entry.embeddingModel());
                    statement.setString(7, entry.source());
                    statement.setTimestamp(8, Timestamp.from(entry.created()));
                    statement.setTimestamp(9, Timestamp.from(entry.updated()));
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("No id generated");
                        }
                        return entry.withId(keys.getInt(1));
                    }
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store memory: " + e.getMessage(), e);
        }
    }

    @Override
    public void updateMemory(MemoryEntry entry) {
        try {
            this.data.withConnection(connection -> {
                String sql = "UPDATE plugin_mirra_agent_memory SET category = ?, content = ?, importance = ?, embedding = ?, embedding_model = ?, updated = ? WHERE id = ?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, entry.category());
                    statement.setString(2, entry.content());
                    statement.setInt(3, entry.importance());
                    statement.setString(4, VectorMath.encode(entry.embedding()));
                    statement.setString(5, entry.embeddingModel());
                    statement.setTimestamp(6, Timestamp.from(entry.updated()));
                    statement.setInt(7, entry.id());
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not update memory: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteMemory(int id) {
        try {
            this.data.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM plugin_mirra_agent_memory WHERE id = ?")) {
                    statement.setInt(1, id);
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete memory: " + e.getMessage(), e);
        }
    }

    @Override
    public void markUsed(List<Integer> ids) {
        if (ids.isEmpty()) {
            return;
        }
        try {
            this.data.withConnection(connection -> {
                String sql = "UPDATE plugin_mirra_agent_memory SET last_used = NOW(), use_count = use_count + 1 WHERE id = ?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (Integer id : ids) {
                        statement.setInt(1, id);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                return null;
            });
        } catch (SQLException e) {
            this.logger.warning("Could not update memory usage: " + e.getMessage());
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
