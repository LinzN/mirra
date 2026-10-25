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

package de.linzn.mirra.agent.tools;

import de.linzn.homeDevices.HomeDevicesPlugin;
import de.linzn.homeDevices.devices.enums.MqttDeviceCategory;
import de.linzn.homeDevices.devices.enums.SmartHomeProfile;
import de.linzn.homeDevices.devices.enums.SwitchCategory;
import de.linzn.homeDevices.devices.interfaces.MqttDevice;
import de.linzn.homeDevices.devices.interfaces.MqttSwitch;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static de.linzn.mirra.agent.tools.Schema.*;
import static de.linzn.mirra.agent.tools.SimpleTool.anyOf;
import static de.linzn.mirra.identitySystem.AiPermissions.*;

/**
 * Tools for the devices of the home-devices plugin.
 */
public final class HomeTools {

    private HomeTools() {
    }

    public static List<AgentTool> create() {
        List<AgentTool> tools = new ArrayList<>();
        List<String> categories = Arrays.stream(MqttDeviceCategory.values()).map(Enum::name).toList();
        List<String> profiles = Arrays.stream(SmartHomeProfile.values()).map(Enum::name).toList();

        tools.add(new SimpleTool("home_list_devices",
                "List the smart home devices with category, description and current data (switch status, temperatures, printer state, ...).",
                Schema.object().prop("category", nullable(enumeration("only devices of this category", categories))).build(),
                anyOf(STEM_READ), (chat, args) -> {
            String category = optString(args, "category");
            JSONArray list = new JSONArray();
            for (MqttDevice device : devices()) {
                if (category != null && !device.getDeviceProfile().getMqttDeviceCategory().name().equals(category)) {
                    continue;
                }
                list.put(describe(device));
            }
            return new JSONObject().put("devices", list);
        }));

        tools.add(new SimpleTool("home_get_device",
                "Current data of one smart home device.",
                Schema.object().prop("name", string("device name as listed by home_list_devices")).build(),
                anyOf(STEM_READ), (chat, args) -> describe(device(requireString(args, "name")))));

        tools.add(new SimpleTool("home_control_device",
                "Change a smart home device. Set only the fields that fit the device, all others null: "
                        + "switches: status (on/off) and brightness (Zigbee lights 0-254); thermostats: temperature in °C; "
                        + "LED strips: led_mode with led_red/led_green/led_blue (0-255); air conditioner: ac_commands as JSON object text "
                        + "for midea2mqtt; garage: trigger_garage true.",
                Schema.object()
                        .prop("name", string("device name"))
                        .prop("status", nullable(bool("switch on (true) or off (false)")))
                        .prop("brightness", nullable(integer("brightness of a dimmable switch")))
                        .prop("temperature", nullable(number("target temperature of a thermostat in °C")))
                        .prop("led_mode", nullable(integer("LED mode")))
                        .prop("led_red", nullable(integer("red 0-255")))
                        .prop("led_green", nullable(integer("green 0-255")))
                        .prop("led_blue", nullable(integer("blue 0-255")))
                        .prop("ac_commands", nullable(string("JSON object with midea2mqtt commands, e.g. {\"power\":true,\"target_temperature\":22}")))
                        .prop("trigger_garage", nullable(bool("true to trigger the garage door")))
                        .build(),
                chat -> chat.has(STEM_ADMIN) || chat.has(SWITCH_LIGHT), (chat, args) -> {
            MqttDevice device = device(requireString(args, "name"));
            if (!chat.has(STEM_ADMIN) && !isLight(device)) {
                return new JSONObject().put("success", false).put("error", "Only lights may be switched with your permissions");
            }
            JSONObject command = new JSONObject();
            Boolean status = optBool(args, "status");
            if (status != null) {
                command.put("status", status);
            }
            Integer brightness = optInt(args, "brightness");
            if (brightness != null) {
                command.put("brightness", brightness);
            }
            Double temperature = optDouble(args, "temperature");
            if (temperature != null) {
                command.put("current_heating_setpoint", temperature);
            }
            Integer mode = optInt(args, "led_mode");
            if (mode != null) {
                command.put("mode", mode).put("r", orZero(optInt(args, "led_red"))).put("g", orZero(optInt(args, "led_green"))).put("b", orZero(optInt(args, "led_blue")));
            }
            String acCommands = optString(args, "ac_commands");
            if (acCommands != null && !acCommands.isBlank()) {
                command.put("commands", new JSONObject(acCommands));
            }
            if (Boolean.TRUE.equals(optBool(args, "trigger_garage"))) {
                command.put("trigger_garage_event", true);
            }
            if (command.isEmpty()) {
                throw new IllegalArgumentException("Nothing to change");
            }
            JSONObject result = device.setJSONData(command);
            return new JSONObject().put("device", device.getConfigName()).put("result", result == null ? JSONObject.NULL : result);
        }));

        tools.add(new SimpleTool("home_get_lights",
                "Status of all lights: true = on, false = off.",
                Schema.empty(), anyOf(STATUS_LIGHT, STEM_READ), (chat, args) -> {
            JSONObject lights = new JSONObject();
            for (MqttDevice device : devices()) {
                if (isLight(device) && device.hasData()) {
                    lights.put(device.getConfigName(), ((MqttSwitch) device).isSwitchedOn());
                }
            }
            return lights;
        }));

        tools.add(new SimpleTool("home_get_profile",
                "Current smart home profile and the available profiles. The profile changes automatic switch-off, time switches and thermostats.",
                Schema.empty(), anyOf(STEM_READ), (chat, args) -> new JSONObject()
                .put("current", plugin().getProfileController().getCurrentProfile().name())
                .put("available", new JSONArray(profiles))));

        tools.add(new SimpleTool("home_set_profile",
                "Change the smart home profile, e.g. STANDBY when everybody left or HOLYDAY for vacation.",
                Schema.object().prop("profile", enumeration("new profile", profiles)).build(),
                anyOf(STEM_ADMIN), (chat, args) -> {
            SmartHomeProfile profile = SmartHomeProfile.valueOf(requireString(args, "profile"));
            boolean success = plugin().getProfileController().requestProfileChange(profile);
            return new JSONObject().put("success", success).put("profile", profile.name());
        }));
        return tools;
    }

    private static HomeDevicesPlugin plugin() {
        HomeDevicesPlugin plugin = HomeDevicesPlugin.homeDevicesPlugin;
        if (plugin == null || plugin.getDeviceManager() == null) {
            throw new IllegalStateException("home-devices is not loaded");
        }
        return plugin;
    }

    private static List<MqttDevice> devices() {
        return new ArrayList<>(plugin().getDeviceManager().getAllDevices());
    }

    private static MqttDevice device(String name) {
        MqttDevice device = plugin().getDeviceManager().getMqttDevice(name);
        if (device == null) {
            throw new IllegalArgumentException("No device with the name " + name);
        }
        return device;
    }

    private static boolean isLight(MqttDevice device) {
        return device instanceof MqttSwitch mqttSwitch && mqttSwitch.switchCategory == SwitchCategory.LIGHT;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static JSONObject describe(MqttDevice device) {
        JSONObject json = new JSONObject()
                .put("name", device.getConfigName())
                .put("category", device.getDeviceProfile().getMqttDeviceCategory().name())
                .put("type", device.getDeviceProfile().getSubDeviceCategory().toLowerCase(Locale.ROOT))
                .put("description", device.getDescription() == null ? "" : device.getDescription())
                .put("hasData", device.hasData());
        try {
            json.put("data", device.getJSONData());
        } catch (RuntimeException e) {
            json.put("data", JSONObject.NULL);
        }
        return json;
    }
}
