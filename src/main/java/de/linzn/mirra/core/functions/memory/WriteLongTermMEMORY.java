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
import org.json.JSONObject;

import java.sql.PreparedStatement;
import java.sql.SQLException;

public class WriteLongTermMEMORY implements IFunctionCall {

    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        MirraPlugin.mirraPlugin.getPluginLogger().debug("Write long term memory: " + input);
        JSONObject jsonObject = new JSONObject();
        String memoryData = input.optString("memory_data_english", "").trim();
        if (!identityUser.hasPermission(AiPermissions.LONG_TERM_MEMORY_WRITE)) {
            jsonObject.put("success", false);
            jsonObject.put("reason", "No permissions");
        } else if (memoryData.isEmpty()) {
            jsonObject.put("success", false);
            jsonObject.put("reason", "No memory data");
        } else {
            jsonObject.put("success", this.writeMemory(memoryData));
        }
        return jsonObject;
    }

    @Override
    public FunctionDefinition getFunctionString() {
        return new FunctionDefinition(this.functionName())
                .setDescription("Save every useful information to long term memory. This makes it possible to access this data later again. Use this ALWAYS if you get new information from user. Example {\"memory_data\":\"The Birthday of Niklas is the 4th May\"}")
                .setParameters(new FunctionParameters()
                        .setType("object")
                        .addProperty(new FunctionProperties()
                                .setName("memory_data_english")
                                .setType("string")
                                .setDescription("The memory to save. Important: Save the information in english only.")
                                .setRequired(true))
                        .build());
    }

    @Override
    public String functionName() {
        return "write_long_term_memory";
    }

    private boolean writeMemory(String memory_data) {
        java.sql.Date date = new java.sql.Date(System.currentTimeMillis());
        try {
            return MirraPlugin.mirraPlugin.getStemApi().data().withConnection(conn -> {
                try (PreparedStatement preparedStmt = conn.prepareStatement("INSERT INTO plugin_mirra_longterm_memory (memory_data, date) VALUES (?, ?)")) {
                    preparedStmt.setString(1, memory_data);
                    preparedStmt.setDate(2, date);
                    preparedStmt.execute();
                    return true;
                }
            });
        } catch (SQLException e) {
            MirraPlugin.mirraPlugin.getPluginLogger().error("Unable to write long term memory", e);
            return false;
        }
    }
}
