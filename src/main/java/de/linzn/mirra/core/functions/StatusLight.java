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
import de.linzn.homeDevices.devices.enums.MqttDeviceCategory;
import de.linzn.homeDevices.devices.enums.SwitchCategory;
import de.linzn.homeDevices.devices.exceptions.DeviceNotInitializedException;
import de.linzn.homeDevices.devices.interfaces.MqttDevice;
import de.linzn.homeDevices.devices.interfaces.MqttSwitch;
import de.linzn.mirra.identitySystem.AiPermissions;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.IFunctionCall;
import de.linzn.mirra.openai.models.FunctionParameters;
import de.linzn.stem.STEMApp;
import org.json.JSONObject;

import java.util.Collection;

public class StatusLight implements IFunctionCall {
    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        STEMApp.LOGGER.CORE(input);
        JSONObject jsonObject = new JSONObject();
        if (identityUser.hasPermission(AiPermissions.STATUS_LIGHT)) {
            jsonObject.put("success", true);
            Collection<MqttDevice> devices = HomeDevicesPlugin.homeDevicesPlugin.getDeviceManager().getAllDevices();
            for (MqttDevice device : devices) {
                if (device.getDeviceProfile().getMqttDeviceCategory() == MqttDeviceCategory.SWITCH) {
                    try {
                        MqttSwitch mqttSwitch = (MqttSwitch) device;
                        if (mqttSwitch.switchCategory == SwitchCategory.LIGHT) {
                            jsonObject.put(device.getDeviceProfile().getName(), mqttSwitch.getDeviceStatus());
                        }
                    } catch (DeviceNotInitializedException e) {
                        STEMApp.LOGGER.ERROR(e);
                    }
                }
            }
        } else {
            jsonObject.put("success", false);
            jsonObject.put("reason", "No permissions");
        }
        return jsonObject;
    }

    @Override
    public FunctionDefinition getFunctionString() {
        return new FunctionDefinition(this.functionName())
                .setDescription("Get the information about the light status of all rooms. True = ON False = OFF")
                .setParameters(new FunctionParameters()
                        .setType("object")
                        .build());
    }

    @Override
    public String functionName() {
        return "get_light";
    }

}
