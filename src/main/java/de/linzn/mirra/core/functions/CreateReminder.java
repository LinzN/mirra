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

package de.linzn.mirra.core.functions;

import com.azure.ai.openai.models.FunctionDefinition;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.identitySystem.AiPermissions;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.IFunctionCall;
import de.linzn.mirra.openai.models.FunctionParameters;
import de.linzn.mirra.openai.models.FunctionProperties;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class CreateReminder implements IFunctionCall {

    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        MirraPlugin.mirraPlugin.getPluginLogger().debug("Create reminder: " + input);
        JSONObject jsonObject = new JSONObject();
        if (!identityUser.hasPermission(AiPermissions.CREATE_REMINDER)) {
            jsonObject.put("success", false);
            jsonObject.put("reason", "No permissions");
            return jsonObject;
        }
        if (userToken == null || userToken.getId() < 0) {
            /* reminders are stored with the token id, guest and internal tokens are not in the database */
            jsonObject.put("success", false);
            jsonObject.put("reason", "Reminders are only possible for registered users");
            return jsonObject;
        }
        String inputContent = input.optString("reminderContent", "");
        try {
            SimpleDateFormat format = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
            format.setLenient(false);
            Date date = format.parse(input.optString("reminderDatetime", ""));
            MirraPlugin.mirraPlugin.getReminderEngine().createMirraReminder(identityUser, userToken, inputContent, date);
            jsonObject.put("success", true);
        } catch (ParseException e) {
            jsonObject.put("success", false);
            jsonObject.put("reason", "Error: " + e.getLocalizedMessage());
        }
        return jsonObject;
    }

    @Override
    public FunctionDefinition getFunctionString() {
        return new FunctionDefinition(this.functionName())
                .setDescription("Create a reminder for something")
                .setParameters(new FunctionParameters()
                        .setType("object")
                        .addProperty(new FunctionProperties()
                                .setName("reminderContent")
                                .setType("string")
                                .setDescription("The content of the reminder")
                                .setRequired(true))
                        .addProperty(new FunctionProperties()
                                .setName("reminderDatetime")
                                .setType("string")
                                .setDescription("The time for the reminder as datetime. Format example: 13-06-2024 16:45:00 (dd-MM-yyyy HH:mm:ss)")
                                .setRequired(true))
                        .build());
    }

    @Override
    public String functionName() {
        return "create_reminder";
    }

}
