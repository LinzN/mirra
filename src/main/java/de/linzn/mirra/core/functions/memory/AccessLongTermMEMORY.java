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

package de.linzn.mirra.core.functions.memory;

import com.azure.ai.openai.models.FunctionDefinition;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.identitySystem.AiPermissions;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.IFunctionCall;
import de.linzn.mirra.openai.models.FunctionParameters;
import de.linzn.mirra.openai.models.FunctionProperties;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class AccessLongTermMEMORY implements IFunctionCall {

    private static final int MAX_KEYWORDS = 10;

    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        MirraPlugin.mirraPlugin.getPluginLogger().debug("Access long term memory: " + input);
        JSONObject jsonObject = new JSONObject();
        if (identityUser.hasPermission(AiPermissions.LONG_TERM_MEMORY_ACCESS)) {
            JSONArray keywords = input.optJSONArray("keywordArray_english");
            jsonObject.put("success", true);
            jsonObject.put("memory_search_result_jsonArray", accessMemory(keywords != null ? keywords : new JSONArray()));
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

    /* keywords come from the language model, so they are always bound as parameters */
    private JSONArray accessMemory(JSONArray keywords) {
        JSONArray jsonArray = new JSONArray();
        List<String> searchTerms = new ArrayList<>();
        for (int i = 0; i < keywords.length() && searchTerms.size() < MAX_KEYWORDS; i++) {
            String keyword = keywords.optString(i, "").trim();
            if (!keyword.isEmpty()) {
                searchTerms.add(keyword);
            }
        }
        if (searchTerms.isEmpty()) {
            return jsonArray;
        }
        StringBuilder query = new StringBuilder("SELECT * FROM plugin_mirra_longterm_memory WHERE ");
        for (int i = 0; i < searchTerms.size(); i++) {
            query.append(i == 0 ? "" : " OR ").append("memory_data LIKE ? ESCAPE '!'");
        }
        query.append(" ORDER BY id DESC LIMIT 10");
        try {
            MirraPlugin.mirraPlugin.getStemApi().data().withConnection(conn -> {
                try (PreparedStatement st = conn.prepareStatement(query.toString())) {
                    for (int i = 0; i < searchTerms.size(); i++) {
                        st.setString(i + 1, "%" + escapeLike(searchTerms.get(i)) + "%");
                    }
                    try (ResultSet rs = st.executeQuery()) {
                        while (rs.next()) {
                            Date date = rs.getDate("date");
                            JSONObject jsonObject = new JSONObject();
                            jsonObject.put("id", rs.getInt("id"));
                            jsonObject.put("keywords", keywords);
                            jsonObject.put("memory_data", rs.getString("memory_data"));
                            jsonObject.put("information_saved_on", date);
                            jsonArray.put(jsonObject);
                        }
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            MirraPlugin.mirraPlugin.getPluginLogger().error("Unable to read long term memory", e);
        }
        return jsonArray;
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
