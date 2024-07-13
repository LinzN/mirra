package de.linzn.mirra.core.functions.memory;

import com.theokanning.openai.completion.chat.ChatFunctionDynamic;
import com.theokanning.openai.completion.chat.ChatFunctionProperty;
import de.linzn.mirra.core.functions.IFunction;
import de.linzn.mirra.identitySystem.AiPermissions;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.stem.stemSystem.STEMSystemApp;
import de.stem.stemSystem.modules.databaseModule.DatabaseModule;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Date;

public class WriteLongTermMEMORY implements IFunction {
    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        STEMSystemApp.LOGGER.CORE(input);
        JSONObject jsonObject = new JSONObject();
        if (identityUser.hasPermission(AiPermissions.LONG_TERM_MEMORY_WRITE)) {
            jsonObject.put("success", true);
            this.writeMemory(input.getString("memory_data_english"));
            STEMSystemApp.LOGGER.CORE(input);
        } else {
            jsonObject.put("success", false);
            jsonObject.put("reason", "No permissions");
        }
        return jsonObject;
    }

    @Override
    public ChatFunctionDynamic getFunctionString() {
        return ChatFunctionDynamic.builder()
                .name(this.functionName())
                .description("Save every useful information to long term memory. This makes it possible to access this data later again. Use this ALWAYS if you get new information from user. Example {\"memory_data\":\"The Birthday of Niklas is the 4th May\"}")
                .addProperty(ChatFunctionProperty.builder()
                        .name("memory_data_english")
                        .type("string")
                        .description("The memory to save. Important: Save the information in english only.")
                        .required(true)
                        .build())
                .build();
    }

    @Override
    public String functionName() {
        return "write_long_term_memory";
    }

    private void writeMemory(String memory_data) {
        java.sql.Date date = new java.sql.Date(new Date().getTime());

        DatabaseModule databaseModule = STEMSystemApp.getInstance().getDatabaseModule();

        try {
            Connection conn = databaseModule.getConnection();

            String query = "INSERT INTO plugin_mirra_longterm_memory (memory_data, date)"
                    + " VALUES (?, ?)";
            PreparedStatement preparedStmt = conn.prepareStatement(query);
            preparedStmt.setString(1, memory_data);
            preparedStmt.setDate(2, date);
            preparedStmt.execute();

            databaseModule.releaseConnection(conn);
        } catch (SQLException e) {
            STEMSystemApp.LOGGER.ERROR(e);
        }
    }

}
