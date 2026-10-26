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

package de.linzn.mirra.agent.tasks;

import de.linzn.stem.api.StemLogger;
import de.linzn.stem.api.data.DataService;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores agent tasks in {@code plugin_mirra_agent_task}. The table is created by the memory repository.
 */
public class TaskRepository {

    private final DataService data;
    private final StemLogger logger;

    public TaskRepository(DataService data, StemLogger logger) {
        this.data = data;
        this.logger = logger;
    }

    public List<AgentTask> loadActive() {
        List<AgentTask> tasks = new ArrayList<>();
        try {
            this.data.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM plugin_mirra_agent_task WHERE active = 1 ORDER BY id");
                     ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        tasks.add(new AgentTask(rs.getInt("id"), rs.getString("owner_source"), rs.getString("owner_token"),
                                rs.getString("owner_name"), rs.getString("instruction"), rs.getString("trigger_type"),
                                time(rs.getTimestamp("run_at")), rs.getString("cron"), rs.getString("event_type"),
                                rs.getString("device"), rs.getString("match_value"), rs.getString("topic"),
                                rs.getBoolean("repeating"), time(rs.getTimestamp("created")), time(rs.getTimestamp("last_run")),
                                rs.getInt("run_count")));
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            this.logger.error("Could not load agent tasks", e);
        }
        return tasks;
    }

    public AgentTask insert(AgentTask task) throws SQLException {
        return this.data.withConnection(connection -> {
            String sql = "INSERT INTO plugin_mirra_agent_task (owner_source, owner_token, owner_name, instruction, trigger_type, run_at, cron, "
                    + "event_type, device, match_value, topic, repeating, active, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, task.ownerSource());
                statement.setString(2, task.ownerToken());
                statement.setString(3, task.ownerName());
                statement.setString(4, task.instruction());
                statement.setString(5, task.triggerType());
                statement.setTimestamp(6, task.runAt() == null ? null : Timestamp.valueOf(task.runAt()));
                statement.setString(7, task.cron());
                statement.setString(8, task.eventType());
                statement.setString(9, task.device());
                statement.setString(10, task.matchValue());
                statement.setString(11, task.topic());
                statement.setBoolean(12, task.repeating());
                statement.setTimestamp(13, Timestamp.valueOf(task.created()));
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        return task.withId(keys.getInt(1));
                    }
                }
            }
            throw new SQLException("No id returned for the new task");
        });
    }

    public void deactivate(int id) {
        this.update("UPDATE plugin_mirra_agent_task SET active = 0 WHERE id = ?", id);
    }

    public void markRun(int id) {
        this.update("UPDATE plugin_mirra_agent_task SET last_run = NOW(), run_count = run_count + 1 WHERE id = ?", id);
    }

    private void update(String sql, int id) {
        try {
            this.data.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setInt(1, id);
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            this.logger.error("Could not update agent task #" + id, e);
        }
    }

    private static LocalDateTime time(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
