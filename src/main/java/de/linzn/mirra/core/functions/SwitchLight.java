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
import de.linzn.homeDevices.HomeDevicesPlugin;
import de.linzn.homeDevices.devices.interfaces.MqttDevice;
import de.linzn.homeDevices.devices.interfaces.MqttSwitch;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.identitySystem.AiPermissions;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.IFunctionCall;
import de.linzn.mirra.openai.models.FunctionParameters;
import de.linzn.mirra.openai.models.FunctionProperties;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SwitchLight implements IFunctionCall {

    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        MirraPlugin.mirraPlugin.getPluginLogger().debug("Switch light: " + input);
        JSONObject jsonObject = new JSONObject();
        String room = input.optString("room", "");
        jsonObject.put("room", room);
        if (!identityUser.hasPermission(AiPermissions.SWITCH_LIGHT)) {
            jsonObject.put("success", false);
            jsonObject.put("reason", "No permissions");
            return jsonObject;
        }
        MqttDevice device = HomeDevicesPlugin.homeDevicesPlugin.getDeviceManager().getMqttDevice(room);
        if (!(device instanceof MqttSwitch mqttSwitch)) {
            jsonObject.put("success", false);
            jsonObject.put("reason", "Device with that name not found");
            return jsonObject;
        }
        /* the model sends the status as string "true"/"false" */
        String status = input.optString("status", "");
        if (!status.equalsIgnoreCase("true") && !status.equalsIgnoreCase("false")) {
            jsonObject.put("success", false);
            jsonObject.put("reason", "Status must be true or false");
            return jsonObject;
        }
        mqttSwitch.switchDevice(Boolean.parseBoolean(status));
        jsonObject.put("success", true);
        jsonObject.put("reason", "light switched to new status");
        return jsonObject;
    }

    @Override
    public FunctionDefinition getFunctionString() {
        List<String> availableDevices = new ArrayList<>();
        for (MqttDevice device : HomeDevicesPlugin.homeDevicesPlugin.getDeviceManager().getAllDevices()) {
            if (device instanceof MqttSwitch) {
                availableDevices.add(device.getConfigName());
            }
        }
        return new FunctionDefinition(this.functionName())
                .setDescription("Turn the light on or of in a room")
                .setParameters(new FunctionParameters()
                        .setType("object")
                        .addProperty(new FunctionProperties()
                                .setName("room")
                                .setType("string")
                                .setDescription("The room name where the light is located. Only rooms from the given list are available. Check which rome name fits the best")
                                .setEnumString(availableDevices)
                                .setRequired(true))
                        .addProperty(new FunctionProperties()
                                .setName("status")
                                .setType("string")
                                .setDescription("The status to switch the light. 'Turn on' = 'true' and 'Turn off' = 'false'")
                                .setEnumString(Arrays.asList("true", "false"))
                                .setRequired(true))
                        .build());
    }

    @Override
    public String functionName() {
        return "set_light";
    }

}
