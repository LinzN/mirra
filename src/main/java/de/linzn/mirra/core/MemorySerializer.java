/*
 * Copyright (c) 2025 MirraNET, Niklas Linz. All rights reserved.
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
import de.stem.stemSystem.STEMSystemApp;
import de.stem.stemSystem.modules.databaseModule.DatabaseModule;

import java.io.IOException;
import java.sql.*;
import java.util.Date;
import java.util.LinkedList;

public class MemorySerializer {

    private final UserToken userToken;
    private final LinkedList<ChatMessage> dataMemory;
    private final AIModel aiModel;

    public MemorySerializer(AIModel aiModel, UserToken userToken) {
        this.aiModel = aiModel;
        this.userToken = userToken;
        this.dataMemory = new LinkedList<>();
        this.remindFromDatabase();
    }


    public void memorizeData(ChatMessage chatMessage) {
        this.dataMemory.addLast(chatMessage);
        this.storeDatabase(chatMessage);
    }

    public LinkedList<ChatMessage> accessMemory() {
        LinkedList<ChatMessage> trimmedMemory = new LinkedList<>(this.dataMemory);
        if (this.dataMemory.size() > 20) {
            trimmedMemory = new LinkedList<>(trimmedMemory.subList(trimmedMemory.size() - 20, trimmedMemory.size()));
        }
        return trimmedMemory;
    }


    private void storeDatabase(ChatMessage chatMessage) {
        java.sql.Date date = new java.sql.Date(new Date().getTime());

        DatabaseModule databaseModule = STEMSystemApp.getInstance().getDatabaseModule();

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

        try {
            Connection conn = databaseModule.getConnection();

            String query = " INSERT INTO plugin_mirra_memory (model, identity, role, content, date, function_name, function_json)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)";
            PreparedStatement preparedStmt = conn.prepareStatement(query);
            preparedStmt.setString(1, this.aiModel.getName());
            preparedStmt.setString(2, this.userToken.getName());
            preparedStmt.setString(3, chatMessage.getRole().getValue());
            preparedStmt.setString(4, chatMessage.getContent());
            preparedStmt.setDate(5, date);
            preparedStmt.setString(6, functionName);
            preparedStmt.setString(7, functionJson);
            preparedStmt.execute();

            databaseModule.releaseConnection(conn);
        } catch (SQLException e) {
            STEMSystemApp.LOGGER.ERROR(e);
        }
    }

    private void remindFromDatabase() {

        DatabaseModule databaseModule = STEMSystemApp.getInstance().getDatabaseModule();

        try {
            Connection conn = databaseModule.getConnection();

            String query = "SELECT * FROM plugin_mirra_memory WHERE model = '" + this.aiModel.getName() + "' AND identity = '" + this.userToken.getName() + "' ORDER BY id DESC LIMIT 50";
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery(query);

            while (rs.next()) {
                STEMSystemApp.LOGGER.DEBUG("Loading gptID " + rs.getInt("id") + " from database");
                String role = rs.getString("role");
                String content = rs.getString("content");
                String functionName = rs.getString("function_name");
                String functionJson = rs.getString("function_json");

                ChatMessage chatMessage = new ChatMessage(content, ChatRole.fromString(role));

                if (functionName != null) {

                    if (chatMessage.getRole() == ChatRole.FUNCTION) {
                        chatMessage = new ChatMessage(new ChatRequestFunctionMessage(functionName, content));
                    } else {
                        ChatRequestAssistantMessage chatRequestAssistantMessage = new ChatRequestAssistantMessage("");
                        if (functionJson != null) {
                            try {
                                FunctionCall functionCall = FunctionCall.fromJson(JsonProviders.createReader(functionJson));
                                chatRequestAssistantMessage.setFunctionCall(functionCall);
                            } catch (IOException ignored) {
                                STEMSystemApp.LOGGER.WARNING("Not possible to convert functionCall");
                            }
                        }
                        chatMessage = new ChatMessage(chatRequestAssistantMessage);
                    }
                }

                this.dataMemory.addFirst(chatMessage);
            }

            databaseModule.releaseConnection(conn);
        } catch (SQLException e) {
            STEMSystemApp.LOGGER.ERROR(e);
        }
    }

    public UserToken getIdentity() {
        return userToken;
    }
}
