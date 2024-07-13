package de.linzn.mirra.core.functions;

import com.theokanning.openai.completion.chat.ChatFunctionDynamic;
import com.theokanning.openai.completion.chat.ChatFunctionProperty;
import de.linzn.homeDevices.HomeDevicesPlugin;
import de.linzn.homeDevices.devices.enums.MqttDeviceCategory;
import de.linzn.homeDevices.devices.interfaces.MqttDevice;
import de.linzn.homeDevices.devices.interfaces.MqttSwitch;
import de.linzn.mirra.identitySystem.AiPermissions;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.stem.stemSystem.STEMSystemApp;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

public class SwitchLight implements IFunction {
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
    public ChatFunctionDynamic getFunctionString() {
        HashSet<String> availableDevices = new HashSet<>();
        Collection<MqttDevice> devices = HomeDevicesPlugin.homeDevicesPlugin.getDeviceManager().getAllDevices();
        for (MqttDevice device : devices) {
            if (device.getDeviceProfile().getMqttDeviceCategory() == MqttDeviceCategory.SWITCH) {
                availableDevices.add(device.getConfigName());
            }
        }

        return ChatFunctionDynamic.builder()
                .name(this.functionName())
                .description("Turn the light on or of in a room")
                .addProperty(ChatFunctionProperty.builder()
                        .name("room")
                        .type("string")
                        .description("The room name where the light is located. Only rooms from the given list are available. Check which rome name fits the best")
                        .enumValues(availableDevices)
                        .required(true)
                        .build())
                .addProperty(ChatFunctionProperty.builder()
                        .name("status")
                        .type("string")
                        .description("The status to switch the light. 'Turn on' = 'true' and 'Turn off' = 'false'")
                        .enumValues(new HashSet<>(Arrays.asList("true", "false")))
                        .required(true)
                        .build())
                .build();
    }

    @Override
    public String functionName() {
        return "set_light";
    }

}
