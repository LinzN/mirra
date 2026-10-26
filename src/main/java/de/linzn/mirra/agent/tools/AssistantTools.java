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

import de.linzn.gasstation.GasStationPlugin;
import de.linzn.gasstation.data.FuelData;
import de.linzn.gasstation.data.GasStation;
import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.core.reminder.MirraReminder;
import de.linzn.mirra.memory.LongTermMemory;
import de.linzn.mirra.memory.MemoryEntry;
import de.linzn.mirra.messaging.OutgoingFile;
import de.linzn.weather.WeatherPlugin;
import de.linzn.weather.engine.ForeCastDay;
import de.linzn.weather.engine.WeatherContainer;
import de.linzn.weather.engine.WeatherEngine;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static de.linzn.mirra.agent.tools.Schema.*;
import static de.linzn.mirra.agent.tools.SimpleTool.anyOf;
import static de.linzn.mirra.agent.tools.SimpleTool.notGuest;
import static de.linzn.mirra.identitySystem.AiPermissions.*;

/**
 * Everyday tools: weather, fuel prices, reminders, memory, images and the social status of Mirra.
 */
public final class AssistantTools {

    private static final DateTimeFormatter REMINDER_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter OUTPUT_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private AssistantTools() {
    }

    public static List<AgentTool> create(MirraPlugin plugin) {
        List<AgentTool> tools = new ArrayList<>();
        ZoneId zone = plugin.getStemApi().system().getZone();

        /* weather */
        tools.add(new SimpleTool("get_weather",
                "Current weather for a location in Germany. Without location: weather and 5 day forecast of the home location.",
                Schema.object().prop("location", nullable(string("town, e.g. 'Blieskastel'; null for the home location"))).build(),
                anyOf(GET_WEATHER), (chat, args) -> {
            String location = optString(args, "location");
            WeatherContainer weather;
            if (location == null || location.isBlank()) {
                weather = WeatherPlugin.weatherPlugin.getWeatherData();
            } else {
                String key = WeatherPlugin.weatherPlugin.getDefaultConfig().getString("weather.apiKey");
                weather = new WeatherEngine(key).getCurrentWeather(location.trim());
            }
            if (weather == null) {
                return new JSONObject().put("success", false).put("error", "No weather data available");
            }
            JSONObject json = new JSONObject()
                    .put("location", weather.getLocation())
                    .put("temperature", weather.getTemp())
                    .put("temperature_min", weather.getTemp_min())
                    .put("temperature_max", weather.getTemp_max())
                    .put("humidity", weather.getHumidity())
                    .put("pressure", weather.getPressure())
                    .put("weather", weather.getWeatherMain())
                    .put("description", weather.getWeatherDescription());
            if (weather.hasForecast()) {
                JSONArray days = new JSONArray();
                for (int i = 0; i < weather.getForecast().getCnt(); i++) {
                    ForeCastDay day = weather.getForecast().getForecast(i);
                    days.put(new JSONObject().put("date", new SimpleDateFormat("EEEE dd.MM.", Locale.GERMAN).format(day.getDate())).put("min", day.getMinTemp())
                            .put("max", day.getMaxTemp()).put("description", day.getDescription()));
                }
                json.put("forecast", days);
            }
            return json;
        }));

        /* fuel */
        tools.add(new SimpleTool("get_fuel_prices",
                "Fuel prices near home: the cheapest open gas station, or all stations within a radius.",
                Schema.object().prop("radius_km", nullable(number("list all stations within this distance; null for only the cheapest"))).build(),
                anyOf(GET_FUEL_PRICE), (chat, args) -> {
            FuelData fuelData = GasStationPlugin.gasStationPlugin.fuelData;
            Double radius = optDouble(args, "radius_km");
            if (radius == null) {
                GasStation cheapest = fuelData.getCheapestGasStation();
                return cheapest == null ? new JSONObject().put("success", false).put("error", "No fuel data yet") : station(cheapest);
            }
            JSONArray list = new JSONArray();
            for (GasStation station : fuelData.getGasStationsInRange(radius)) {
                list.put(station(station));
            }
            return new JSONObject().put("stations", list);
        }));

        /* reminders */
        tools.add(new SimpleTool("create_reminder",
                "Create a reminder. At the given time Mirra writes a message to the person in this chat.",
                Schema.object()
                        .prop("content", string("what to remind of"))
                        .prop("datetime", string("local date and time, format yyyy-MM-ddTHH:mm, e.g. 2026-11-03T08:30"))
                        .build(),
                chat -> chat.has(CREATE_REMINDER) && chat.userToken().getId() >= 0, (chat, args) -> {
            LocalDateTime time;
            try {
                time = LocalDateTime.parse(requireString(args, "datetime"), REMINDER_FORMAT);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid datetime, use yyyy-MM-ddTHH:mm");
            }
            if (time.atZone(zone).toInstant().isBefore(java.time.Instant.now())) {
                throw new IllegalArgumentException("The time is in the past");
            }
            MirraReminder reminder = plugin.getReminderEngine().createMirraReminder(chat.identityUser(), chat.userToken(),
                    requireString(args, "content"), Date.from(time.atZone(zone).toInstant()));
            return new JSONObject().put("success", reminder.getId() >= 0).put("id", reminder.getId()).put("time", OUTPUT_FORMAT.format(time));
        }));

        tools.add(new SimpleTool("list_reminders",
                "List the open reminders of the person.",
                Schema.empty(), chat -> chat.has(CREATE_REMINDER) && chat.userToken().getId() >= 0, (chat, args) -> {
            JSONArray list = new JSONArray();
            for (MirraReminder reminder : plugin.getReminderEngine().getReminders(chat.userToken())) {
                list.put(new JSONObject().put("id", reminder.getId()).put("content", reminder.getContent())
                        .put("time", OUTPUT_FORMAT.format(reminder.getReminderDate().toInstant().atZone(zone))));
            }
            return new JSONObject().put("reminders", list);
        }));

        tools.add(new SimpleTool("cancel_reminder",
                "Cancel an open reminder of the person.",
                Schema.object().prop("id", integer("id from list_reminders")).build(),
                chat -> chat.has(CREATE_REMINDER) && chat.userToken().getId() >= 0, (chat, args) ->
                new JSONObject().put("success", plugin.getReminderEngine().cancelReminder(args.getInt("id"), chat.userToken()))));

        /* memory */
        tools.add(new SimpleTool("remember",
                "Save something in the long term memory when the person explicitly asks you to remember it.",
                Schema.object()
                        .prop("content", string("short standalone statement in English with the name of the person"))
                        .prop("scope", enumeration("user = about this person, global = about the household", List.of("user", "global")))
                        .prop("category", enumeration("kind of memory", LongTermMemory.CATEGORIES))
                        .prop("importance", integer("1 (minor) to 5 (essential)"))
                        .build(),
                anyOf(LONG_TERM_MEMORY_WRITE), (chat, args) -> {
            String owner = "global".equals(optString(args, "scope")) ? LongTermMemory.GLOBAL : chat.memoryOwner();
            MemoryEntry entry = plugin.getAgent().getMemory().remember(owner, requireString(args, "category"),
                    requireString(args, "content"), args.optInt("importance", 3), "tool");
            return new JSONObject().put("success", true).put("id", entry.id());
        }));

        tools.add(new SimpleTool("recall",
                "Search the long term memory for things that are not in your context.",
                Schema.object()
                        .prop("query", string("what you are looking for, e.g. 'birthday of Max'"))
                        .prop("limit", nullable(integer("maximum results, default 10")))
                        .build(),
                anyOf(LONG_TERM_MEMORY_ACCESS, LONG_TERM_MEMORY_WRITE), (chat, args) -> {
            String query = requireString(args, "query");
            Integer limit = optInt(args, "limit");
            LongTermMemory memory = plugin.getAgent().getMemory();
            JSONArray results = new JSONArray();
            for (LongTermMemory.ScoredMemory scored : memory.search(List.of(chat.memoryOwner(), LongTermMemory.GLOBAL), query,
                    memory.embedQuery(query), limit == null ? 10 : Math.max(1, Math.min(30, limit)))) {
                MemoryEntry entry = scored.entry();
                results.put(new JSONObject().put("id", entry.id()).put("scope", entry.isGlobal() ? "global" : "user")
                        .put("category", entry.category()).put("content", entry.content())
                        .put("saved", entry.created() == null ? "" : OUTPUT_FORMAT.format(entry.created().atZone(zone))));
            }
            return new JSONObject().put("memories", results);
        }));

        tools.add(new SimpleTool("forget",
                "Delete a memory when the person asks you to forget something.",
                Schema.object().prop("id", integer("id of the memory")).build(),
                anyOf(LONG_TERM_MEMORY_WRITE), (chat, args) -> {
            int id = args.getInt("id");
            LongTermMemory memory = plugin.getAgent().getMemory();
            MemoryEntry entry = memory.get(id).orElseThrow(() -> new IllegalArgumentException("No memory with id " + id));
            if (!entry.isGlobal() && !entry.owner().equals(chat.memoryOwner())) {
                return new JSONObject().put("success", false).put("error", "This memory belongs to somebody else");
            }
            return new JSONObject().put("success", memory.forget(id));
        }));

        /* images */
        tools.add(new SimpleTool("create_image",
                "Create an image from a description. In Discord and WhatsApp chats the image is sent directly into the chat, "
                        + "otherwise a link is returned.",
                Schema.object()
                        .prop("description", string("detailed description of the image"))
                        .prop("caption", nullable(string("short text sent with the image")))
                        .build(),
                anyOf(CREATE_IMAGE), (chat, args) -> {
            String description = requireString(args, "description");
            if (plugin.getMessenger() != null && plugin.getMessenger().canSend(chat.userToken())) {
                byte[] png = plugin.getImageService().generate(description);
                plugin.getMessenger().sendFile(chat.userToken(), new OutgoingFile("Mirra.png", "image/png", png), optString(args, "caption"));
                return new JSONObject().put("sent", true).put("note", "The image is already in the chat, do not send a link");
            }
            return new JSONObject().put("url", plugin.getImageService().create(description));
        }));

        /* social status */
        tools.add(new SimpleTool("set_mirra_status",
                "Set your own status: Discord activity and a WhatsApp status story.",
                Schema.object().prop("status", string("new status, at most 128 characters")).build(),
                notGuest(), (chat, args) -> {
            String status = plugin.updateSocialStatus(requireString(args, "status"));
            return new JSONObject().put("success", true).put("status", status);
        }));
        return tools;
    }

    private static JSONObject station(GasStation station) {
        return new JSONObject()
                .put("name", station.getName())
                .put("brand", station.getBrand())
                .put("address", station.getStreet() + ", " + station.getPlace())
                .put("distance_km", station.getDist())
                .put("open", station.isOpen())
                .put("price", station.hasPrice() ? station.getPrice() : JSONObject.NULL);
    }
}
