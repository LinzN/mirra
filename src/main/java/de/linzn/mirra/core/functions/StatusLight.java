package de.linzn.mirra.core.functions;

import com.theokanning.openai.completion.chat.ChatFunctionDynamic;
import de.linzn.homeDevices.HomeDevicesPlugin;
import de.linzn.homeDevices.devices.enums.MqttDeviceCategory;
import de.linzn.homeDevices.devices.enums.SwitchCategory;
import de.linzn.homeDevices.devices.exceptions.DeviceNotInitializedException;
import de.linzn.homeDevices.devices.interfaces.MqttDevice;
import de.linzn.homeDevices.devices.interfaces.MqttSwitch;
import de.stem.stemSystem.STEMSystemApp;
import org.json.JSONObject;

import java.util.Collection;

public class StatusLight implements IFunction {
    @Override
    public JSONObject completeRequest(JSONObject input) {
        STEMSystemApp.LOGGER.CORE(input);
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("success", true);
        Collection<MqttDevice> devices = HomeDevicesPlugin.homeDevicesPlugin.getDeviceManager().getAllDevices();
        for (MqttDevice device : devices) {
            if (device.getDeviceProfile().getMqttDeviceCategory() == MqttDeviceCategory.SWITCH) {
                try {
                    MqttSwitch mqttSwitch = (MqttSwitch) device;
                    if(mqttSwitch.switchCategory == SwitchCategory.LIGHT) {
                        jsonObject.put(device.getDeviceProfile().getName(), mqttSwitch.getDeviceStatus());
                    }
                } catch (DeviceNotInitializedException e) {
                    STEMSystemApp.LOGGER.ERROR(e);
                }
            }
        }
        return jsonObject;
    }

    @Override
    public ChatFunctionDynamic getFunctionString() {
        return ChatFunctionDynamic.builder()
                .name(this.functionName())
                .description("Get the information about the light status of all rooms. True = ON False = OFF")
                .build();
    }

    @Override
    public String functionName() {
        return "get_light";
    }

}
