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
import de.stem.stemSystem.STEMSystemApp;
import org.json.JSONObject;

import java.util.Collection;

public class StatusLight implements IFunctionCall {
    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        STEMSystemApp.LOGGER.CORE(input);
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
                        STEMSystemApp.LOGGER.ERROR(e);
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
