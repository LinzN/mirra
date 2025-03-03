package de.linzn.mirra.core.functions.memory;

import com.azure.ai.openai.models.FunctionDefinition;
import de.linzn.mirra.identitySystem.AiPermissions;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.IFunctionCall;
import de.linzn.mirra.openai.models.FunctionParameters;
import de.linzn.mirra.openai.models.FunctionProperties;
import de.stem.stemSystem.STEMSystemApp;
import de.stem.stemSystem.modules.databaseModule.DatabaseModule;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;

public class AccessLongTermMEMORY implements IFunctionCall {
    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        STEMSystemApp.LOGGER.CORE(input);
        JSONObject jsonObject = new JSONObject();
        if (identityUser.hasPermission(AiPermissions.LONG_TERM_MEMORY_ACCESS)) {
            jsonObject.put("success", true);
            jsonObject.put("memory_search_result_jsonArray", accessMemory(input.getJSONArray("keywordArray_english")));
            STEMSystemApp.LOGGER.CORE(input);
        } else {
            jsonObject.put("success", false);
            jsonObject.put("reason", "No permissions");
        }
        return jsonObject;
    }

    @Override
    public FunctionDefinition getFunctionString() {
        return new FunctionDefinition(this.functionName())
                .setDescription("Search useful information from long term memory. Use this ALWAYS if you don't know a question. Example: If you don't know the birth date of niklas use Example [\"Niklas\", \"Birthday\"]")
                .setParameters(new FunctionParameters()
                        .setType("object")
                        .addProperty(new FunctionProperties()
                                .setName("keywordArray_english")
                                .setType("array")
                                .setDescription("Some keywords written ALWAYS IN ENGLISH as array like affected person and the event for the request to search in database. Example [\"Niklas\", \"Birthday\"]")
                                .setItems(new FunctionProperties()
                                        .setType("string")
                                        .setName("keyword")
                                        .setDescription("Keyword to search in database in english"))
                                .setRequired(true))
                        .build());
    }

    @Override
    public String functionName() {
        return "access_long_term_memory";
    }

    private JSONArray accessMemory(JSONArray keywords) {

        DatabaseModule databaseModule = STEMSystemApp.getInstance().getDatabaseModule();
        JSONArray jsonArray = new JSONArray();

        try {
            Connection conn = databaseModule.getConnection();

            //String query = "SELECT * FROM plugin_mirra_longterm_memory WHERE search_identifier_english = '" + search_identifier_english + "' ORDER BY id DESC LIMIT 10";

            String likeString = "where ";

            for (int i = 0; i < keywords.length(); i++) {

                likeString = likeString + "memory_data LIKE '%" + keywords.getString(i) + "%' ";
                if (i + 1 != keywords.length()) {
                    likeString = likeString + "OR ";
                }
            }
            String query = "SELECT * FROM plugin_mirra_longterm_memory " + likeString + "ORDER BY id DESC LIMIT 10";
            STEMSystemApp.LOGGER.CONFIG(query);
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery(query);

            while (rs.next()) {
                int id = rs.getInt("id");
                String memory_data = rs.getString("memory_data");
                Date date = rs.getDate("date");
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("id", id);
                jsonObject.put("keywords", keywords);
                jsonObject.put("memory_data", memory_data);
                jsonObject.put("information_saved_on", date);
                jsonArray.put(jsonObject);
            }

            databaseModule.releaseConnection(conn);
        } catch (SQLException e) {
            STEMSystemApp.LOGGER.ERROR(e);
        }
        return jsonArray;
    }
}
