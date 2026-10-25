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
import de.linzn.mirra.identitySystem.IdentityGuest;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.TokenSource;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.IFunctionCall;
import de.linzn.mirra.openai.models.FunctionParameters;
import de.linzn.mirra.openai.models.FunctionProperties;
import org.json.JSONObject;

public class UpdateMirraStatus implements IFunctionCall {

    /* discord allows 128 characters for an activity */
    private static final int MAX_STATUS_LENGTH = 128;

    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        JSONObject jsonObject = new JSONObject();
        boolean internal = userToken != null && userToken.getSource() == TokenSource.INTERNAL;
        if (!internal && identityUser instanceof IdentityGuest) {
            /* guests must not post a WhatsApp story to all contacts */
            jsonObject.put("success", false);
            jsonObject.put("reason", "No permissions");
            return jsonObject;
        }
        String newStatus = input.optString("new_status", "").trim();
        if (newStatus.isEmpty()) {
            jsonObject.put("success", false);
            jsonObject.put("reason", "No status given");
            return jsonObject;
        }
        if (newStatus.length() > MAX_STATUS_LENGTH) {
            newStatus = newStatus.substring(0, MAX_STATUS_LENGTH);
        }
        MirraPlugin plugin = MirraPlugin.mirraPlugin;
        plugin.getDiscordManager().setActivity(newStatus);
        if (plugin.getWhatsappManager().isEnabled()) {
            try {
                plugin.getWhatsappManager().getEvolutionApi().getWebApiProvider().CreateStatusStorie(newStatus, plugin.getWhatsappManager().getEvolutionApi().getWebApiProvider().getContacts());
            } catch (Exception e) {
                plugin.getPluginLogger().error("Unable to set WhatsApp status", e);
            }
        }
        jsonObject.put("success", true);
        jsonObject.put("new_status", newStatus);
        return jsonObject;
    }

    @Override
    public FunctionDefinition getFunctionString() {
        return new FunctionDefinition(this.functionName())
                .setDescription("Set your (Mirra) social status in Discord and Whatsapp")
                .setParameters(new FunctionParameters()
                        .setType("object")
                        .addProperty(new FunctionProperties()
                                .setName("new_status")
                                .setType("string")
                                .setDescription("The new Status message for Discord/Whatsapp")
                                .setRequired(true))
                        .build());
    }

    @Override
    public String functionName() {
        return "set_mirra_status";
    }

}
