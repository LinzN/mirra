package de.linzn.mirra.core.functions;

import com.theokanning.openai.completion.chat.ChatFunctionDynamic;
import com.theokanning.openai.completion.chat.ChatFunctionProperty;
import de.linzn.homeDevices.HomeDevicesPlugin;
import de.linzn.homeDevices.devices.enums.MqttDeviceCategory;
import de.linzn.homeDevices.devices.interfaces.MqttDevice;
import de.stem.stemSystem.STEMSystemApp;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

public class EventTrigger implements IFunction{
    @Override
    public JSONObject completeRequest(JSONObject input) {
        STEMSystemApp.LOGGER.CORE("Tis is a standalone function. No external call allowed");
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("actionStatus", "failed");
        jsonObject.put("description", "This function call is not allowed to trigger external!");
        return jsonObject;
    }

    @Override
    public ChatFunctionDynamic getFunctionString() {
        return  ChatFunctionDynamic.builder()
                .name(this.functionName())
                .description("Inform Niklas about a smart home event with a description coming in json format. Format Example {\"event\":\"door ring enabled\"}' to \"Hey Niklas, someone knocked at the door.\"")
                .build();
    }

    @Override
    public String functionName() {
        return "trigger_event";
    }

}
