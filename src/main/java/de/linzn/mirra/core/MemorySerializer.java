package de.linzn.mirra.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.completion.chat.ChatFunctionCall;
import com.theokanning.openai.completion.chat.ChatMessage;
import de.stem.stemSystem.STEMSystemApp;
import de.stem.stemSystem.modules.databaseModule.DatabaseModule;

import java.sql.*;
import java.util.Date;
import java.util.LinkedList;

public class MemorySerializer {

    private final String identity;
    private final LinkedList<ChatMessage> dataMemory;
    private AIModel aiModel;

    public MemorySerializer(AIModel aiModel, String identity) {
        this.aiModel = aiModel;
        this.identity = identity;
        this.dataMemory = new LinkedList<>();
        this.remindFromDatabase();
    }


    public void memorizeData(ChatMessage chatMessage) {
        this.dataMemory.addLast(chatMessage);
        /* TODO maybe save function calls also in database to prevent unwantet promt caches */
        //if(chatMessage.getFunctionCall()== null && !chatMessage.getRole().equalsIgnoreCase("function")) {
        this.storeDatabase(chatMessage);
        //}
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
        String functionArguments = null;

        if (chatMessage.getFunctionCall() != null) {
            functionName = chatMessage.getFunctionCall().getName();
            if (chatMessage.getFunctionCall().getArguments() != null) {
                functionArguments = chatMessage.getFunctionCall().getArguments().toString();
            }
        } else if (chatMessage.getRole().equalsIgnoreCase("function")) {
            functionName = chatMessage.getName();
        }

        try {
            Connection conn = databaseModule.getConnection();

            String query = " INSERT INTO plugin_mirra_memory (model, identity, role, content, date, function_name, function_arguments)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)";
            PreparedStatement preparedStmt = conn.prepareStatement(query);
            preparedStmt.setString(1, this.aiModel.getName());
            preparedStmt.setString(2, identity);
            preparedStmt.setString(3, chatMessage.getRole());
            preparedStmt.setString(4, chatMessage.getContent());
            preparedStmt.setDate(5, date);
            preparedStmt.setString(6, functionName);
            preparedStmt.setString(7, functionArguments);
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

            String query = "SELECT * FROM plugin_mirra_memory WHERE model = '" + this.aiModel.getName() + "' AND identity = '" + identity + "' ORDER BY id DESC LIMIT 50";
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery(query);

            while (rs.next()) {
                STEMSystemApp.LOGGER.DEBUG("Loading gptID " + rs.getInt("id") + " from database");
                String role = rs.getString("role");
                String content = rs.getString("content");
                String functionName = rs.getString("function_name");
                String functionArguments = rs.getString("function_arguments");

                ChatMessage chatMessage = new ChatMessage();
                chatMessage.setRole(role);
                chatMessage.setContent(content);

                if (functionName != null) {
                    if (chatMessage.getRole().equalsIgnoreCase("function")) {
                        chatMessage.setName(functionName);
                    } else {
                        ChatFunctionCall chatFunctionCall = new ChatFunctionCall();
                        chatFunctionCall.setName(functionName);
                        try {
                            chatFunctionCall.setArguments(new ObjectMapper().readTree(functionArguments));
                        } catch (JsonProcessingException e) {
                            STEMSystemApp.LOGGER.ERROR(e);
                        }
                        chatMessage.setFunctionCall(chatFunctionCall);
                    }
                }

                this.dataMemory.addFirst(chatMessage);
            }

            databaseModule.releaseConnection(conn);
        } catch (SQLException e) {
            STEMSystemApp.LOGGER.ERROR(e);
        }
    }

    public String getIdentity() {
        return identity;
    }
}
