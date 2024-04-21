package de.linzn.mirra.core.functions;

import com.theokanning.openai.completion.chat.ChatFunctionDynamic;
import com.theokanning.openai.completion.chat.ChatFunctionProperty;
import de.linzn.homeDevices.HomeDevicesPlugin;
import de.linzn.homeDevices.devices.enums.MqttDeviceCategory;
import de.linzn.homeDevices.devices.exceptions.DeviceNotInitializedException;
import de.linzn.homeDevices.devices.interfaces.MqttDevice;
import de.linzn.homeDevices.devices.interfaces.MqttSwitch;
import de.stem.stemSystem.STEMSystemApp;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

public class StatusLight implements IFunction{
    @Override
    public JSONObject completeRequest(JSONObject input) {
        STEMSystemApp.LOGGER.CORE(input);
        JSONObject jsonObject = new JSONObject();
        Collection<MqttDevice> devices =  HomeDevicesPlugin.homeDevicesPlugin.getDeviceManager().getAllDevices();
        for(MqttDevice device : devices){
            if(device.getDeviceProfile().getMqttDeviceCategory() == MqttDeviceCategory.SWITCH){
                try {
                    jsonObject.put(device.getDeviceProfile().getName(), ((MqttSwitch)device).getDeviceStatus());
                } catch (DeviceNotInitializedException e) {
                    STEMSystemApp.LOGGER.ERROR(e);
                }
            }
        }
        return jsonObject;
    }

    @Override
    public ChatFunctionDynamic getFunctionString() {
        return  ChatFunctionDynamic.builder()
                .name(this.functionName())
                .description("Get the information about the light status of all rooms. True = ON False = OFF")
                .build();
    }

    @Override
    public String functionName() {
        return "get_light";
    }

}
