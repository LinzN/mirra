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
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Date;

public class WriteLongTermMEMORY implements IFunctionCall {
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
