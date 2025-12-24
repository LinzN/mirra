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
import de.linzn.mirra.identitySystem.AiPermissions;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.IFunctionCall;
import de.linzn.mirra.openai.models.FunctionParameters;
import de.linzn.mirra.openai.models.FunctionProperties;
import org.json.JSONObject;

public class FuelPrice implements IFunctionCall {
    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        JSONObject jsonObject = new JSONObject();
        if (identityUser.hasPermission(AiPermissions.GET_FUEL_PRICE)) {
            jsonObject.put("success", true);
            jsonObject.put("status", "healthy");
        } else {
            jsonObject.put("success", false);
            jsonObject.put("reason", "No permissions");
        }
        return jsonObject;
    }

    @Override
    public FunctionDefinition getFunctionString() {
        return new FunctionDefinition(this.functionName())
                .setDescription("Get the price list of the cheapest fuel station near given location")
                .setParameters(new FunctionParameters()
                        .setType("object")
                        .addProperty(new FunctionProperties()
                                .setName("location")
                                .setType("string")
                                .setDescription("The location to get the cheapest fuel station around! Like 'Blieskastel'")
                                .setRequired(true))
                        .build());
    }

    @Override
    public String functionName() {
        return "get_fuel_price";
    }

}
