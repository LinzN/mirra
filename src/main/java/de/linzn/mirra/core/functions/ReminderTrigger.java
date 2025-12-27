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

package de.linzn.mirra.core.functions;

import com.azure.ai.openai.models.FunctionDefinition;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.IFunctionCall;
import de.linzn.mirra.openai.models.FunctionParameters;
import de.linzn.stem.STEMApp;
import org.json.JSONObject;

public class ReminderTrigger implements IFunctionCall {
    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        STEMApp.LOGGER.CORE("Tis is a standalone function. No external call allowed");
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("success", false);
        jsonObject.put("description", "This function call is not allowed to trigger external!");
        return jsonObject;
    }

    @Override
    public FunctionDefinition getFunctionString() {
        return new FunctionDefinition(this.functionName())
                .setDescription("Trigger a reminder that was set before by the reminderTargetUsername")
                .setParameters(new FunctionParameters()
                        .setType("object")
                        .build());
    }

    @Override
    public String functionName() {
        return "trigger_reminder";
    }

}
