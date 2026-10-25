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

package de.linzn.mirra.core;

import com.azure.ai.openai.models.ChatRequestAssistantMessage;
import com.azure.ai.openai.models.ChatRequestFunctionMessage;
import com.azure.ai.openai.models.ChatRole;
import com.azure.ai.openai.models.FunctionCall;
import com.azure.json.JsonProviders;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.ChatMessage;
import de.linzn.stem.modules.pluginModule.STEMPlugin;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.LinkedList;

/**
 * Short term chat memory of one user. Every message is also stored in the database.
 * Not thread safe, callers synchronize on the instance.
 */
public class MemorySerializer {

    /* messages sent to the model */
    private static final int CONTEXT_SIZE = 20;
    /* messages kept in RAM, older ones only stay in the database */
    private static final int MAX_MEMORY = 50;

    private final STEMPlugin plugin;
    private final UserToken userToken;
    private final LinkedList<ChatMessage> dataMemory;
    private final AIModel aiModel;

    public MemorySerializer(STEMPlugin plugin, AIModel aiModel, UserToken userToken) {
        this.plugin = plugin;
        this.aiModel = aiModel;
        this.userToken = userToken;
        this.dataMemory = new LinkedList<>();
        this.remindFromDatabase();
    }

    public void memorizeData(ChatMessage chatMessage) {
        this.dataMemory.addLast(chatMessage);
        while (this.dataMemory.size() > MAX_MEMORY) {
            this.dataMemory.removeFirst();
        }
        this.storeDatabase(chatMessage);
    }

    /**
     * @return the last messages for the model; never starts with a function result whose call was cut off
     */
    public LinkedList<ChatMessage> accessMemory() {
        LinkedList<ChatMessage> trimmedMemory = new LinkedList<>(this.dataMemory);
        while (trimmedMemory.size() > CONTEXT_SIZE) {
            trimmedMemory.removeFirst();
        }
        while (!trimmedMemory.isEmpty() && trimmedMemory.getFirst().getRole() == ChatRole.FUNCTION) {
            trimmedMemory.removeFirst();
        }
        return trimmedMemory;
    }

    private void storeDatabase(ChatMessage chatMessage) {
        java.sql.Date date = new java.sql.Date(new Date().getTime());
        String functionName = null;
        String functionJson = null;
        if (chatMessage.getFunctionCall() != null) {
            functionName = chatMessage.getFunctionCall().getName();
            if (chatMessage.getFunctionCall().getArguments() != null) {
                try {
                    functionJson = chatMessage.getFunctionCall().toJsonString();
                } catch (IOException ignored) {
                }
            }
        } else if (chatMessage.getRole() == ChatRole.FUNCTION) {
            functionName = ((ChatRequestFunctionMessage) chatMessage.convertToRequestMessage()).getName();
        }
        final String finalFunctionName = functionName;
        final String finalFunctionJson = functionJson;
        try {
            this.plugin.getStemApi().data().withConnection(conn -> {
                String query = "INSERT INTO plugin_mirra_memory (model, identity, role, content, date, function_name, function_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement preparedStmt = conn.prepareStatement(query)) {
                    preparedStmt.setString(1, this.aiModel.getName());
                    preparedStmt.setString(2, this.userToken.getName());
                    preparedStmt.setString(3, chatMessage.getRole().getValue());
                    preparedStmt.setString(4, chatMessage.getContent());
                    preparedStmt.setDate(5, date);
                    preparedStmt.setString(6, finalFunctionName);
                    preparedStmt.setString(7, finalFunctionJson);
                    return preparedStmt.execute();
                }
            });
        } catch (SQLException e) {
            this.plugin.getPluginLogger().error("Unable to store chat memory", e);
        }
    }

    private void remindFromDatabase() {
        try {
            this.plugin.getStemApi().data().withConnection(conn -> {
                String query = "SELECT * FROM plugin_mirra_memory WHERE model = ? AND identity = ? ORDER BY id DESC LIMIT " + MAX_MEMORY;
                try (PreparedStatement st = conn.prepareStatement(query)) {
                    st.setString(1, this.aiModel.getName());
                    st.setString(2, this.userToken.getName());
                    try (ResultSet rs = st.executeQuery()) {
                        while (rs.next()) {
                            ChatMessage chatMessage = this.readMessage(rs);
                            if (chatMessage != null) {
                                this.dataMemory.addFirst(chatMessage);
                            }
                        }
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            this.plugin.getPluginLogger().error("Unable to load chat memory", e);
        }
    }

    private ChatMessage readMessage(ResultSet rs) throws SQLException {
        String role = rs.getString("role");
        String content = rs.getString("content");
        String functionName = rs.getString("function_name");
        String functionJson = rs.getString("function_json");
        ChatRole chatRole = ChatRole.fromString(role);
        if (functionName == null) {
            return content != null ? new ChatMessage(content, chatRole) : null;
        }
        if (chatRole == ChatRole.FUNCTION) {
            return new ChatMessage(new ChatRequestFunctionMessage(functionName, content != null ? content : "{}"));
        }
        ChatRequestAssistantMessage chatRequestAssistantMessage = new ChatRequestAssistantMessage("");
        if (functionJson != null) {
            try {
                FunctionCall functionCall = FunctionCall.fromJson(JsonProviders.createReader(functionJson));
                chatRequestAssistantMessage.setFunctionCall(functionCall);
            } catch (IOException ignored) {
                this.plugin.getPluginLogger().warning("Not possible to convert functionCall");
            }
        }
        return new ChatMessage(chatRequestAssistantMessage);
    }

    public UserToken getIdentity() {
        return userToken;
    }
}
