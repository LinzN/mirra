package de.linzn.mirra.core.functions;

import com.azure.ai.openai.models.FunctionDefinition;
import de.linzn.homeDevices.HomeDevicesPlugin;
import de.linzn.homeDevices.devices.enums.MqttDeviceCategory;
import de.linzn.homeDevices.devices.interfaces.MqttDevice;
import de.linzn.homeDevices.devices.interfaces.MqttSwitch;
import de.linzn.mirra.identitySystem.AiPermissions;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.mirra.openai.IFunctionCall;
import de.linzn.mirra.openai.models.FunctionParameters;
import de.linzn.mirra.openai.models.FunctionProperties;
import de.stem.stemSystem.STEMSystemApp;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class SwitchLight implements IFunctionCall {
    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        STEMSystemApp.LOGGER.CORE(input);
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("room", input.getString("room"));

        if (identityUser.hasPermission(AiPermissions.SWITCH_LIGHT)) {
            MqttSwitch mqttSwitch = (MqttSwitch) HomeDevicesPlugin.homeDevicesPlugin.getDeviceManager().getMqttDevice(input.getString("room"));

            if (mqttSwitch != null) {
                mqttSwitch.switchDevice(input.getBoolean("status"));
                jsonObject.put("success", true);
                jsonObject.put("reason", "light switched to new status");
            } else {
                jsonObject.put("success", false);
                jsonObject.put("reason", "Device with that name not found");
            }
        } else {
            jsonObject.put("success", false);
            jsonObject.put("reason", "No permissions");
        }
        return jsonObject;
    }

    @Override
    public FunctionDefinition getFunctionString() {
        List<String> availableDevices = new ArrayList<>();
        Collection<MqttDevice> devices = HomeDevicesPlugin.homeDevicesPlugin.getDeviceManager().getAllDevices();
        for (MqttDevice device : devices) {
            if (device.getDeviceProfile().getMqttDeviceCategory() == MqttDeviceCategory.SWITCH) {
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
