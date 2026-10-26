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

import de.linzn.mirra.MirraPlugin;
import de.linzn.mirra.agent.tasks.AgentTask;
import de.linzn.mirra.agent.tasks.EventCatalog;
import de.linzn.mirra.agent.tasks.TaskManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static de.linzn.mirra.agent.tools.Schema.*;
import static de.linzn.mirra.agent.tools.SimpleTool.anyOf;
import static de.linzn.mirra.agent.tools.SimpleTool.notGuest;
import static de.linzn.mirra.identitySystem.AiPermissions.STEM_ADMIN;
import static de.linzn.mirra.identitySystem.AiPermissions.STEM_READ;

/**
 * Tools to plan tasks Mirra carries out later or when something happens.
 */
public final class TaskTools {

    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter OUTPUT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private TaskTools() {
    }

    public static List<AgentTool> create(MirraPlugin plugin) {
        List<AgentTool> tools = new ArrayList<>();

        tools.add(new SimpleTool("create_task",
                "Plan a task you carry out yourself later: at a time ('heute um 22 Uhr'), repeatedly on a cron schedule "
                        + "('jeden Morgen um 7'), or when an event happens ('wenn der 3D-Druck fertig ist'). When it triggers you get "
                        + "the instruction as if the person had just asked, with the same permissions, and the person gets your answer. "
                        + "Write the instruction as a complete, standalone order, e.g. 'Schalte die Steckdose des 3D-Druckers aus'. "
                        + "Use it for actions and checks; for a plain reminder text use create_reminder. Events: "
                        + "print_finished/print_failed/print_cancelled (3D printers, filter with device), device_on/device_off "
                        + "(a switch changed, filter with device), door_ring, information (an information block appears, filter its "
                        + "text with match_value), mqtt (a message on topic, optional match_value in the payload), stem_event (any "
                        + "event of any STEM plugin by its event_name, see list_event_types; device and match_value search the event "
                        + "data). stem_event and mqtt tasks can have a condition on a field (e.g. environmentSensor.temperature < 18); "
                        + "they then trigger when the condition becomes true, not on every event. For periodic checks use cron with an "
                        + "instruction like 'Prüfe ..., melde dich nur wenn ...'.",
                Schema.object()
                        .prop("instruction", string("what to do when the task triggers"))
                        .prop("trigger_type", enumeration("time, cron or event", List.of(AgentTask.TIME, AgentTask.CRON, AgentTask.EVENT)))
                        .prop("run_at", nullable(string("time tasks: local time, format yyyy-MM-dd HH:mm")))
                        .prop("cron", nullable(string("cron tasks: 5 fields 'minute hour day month weekday', e.g. '0 7 * * *'")))
                        .prop("event", nullable(enumeration("event tasks: the event", AgentTask.EVENTS)))
                        .prop("device", nullable(string("event tasks: part of the device name, e.g. 'u1' or 'drucker'")))
                        .prop("match_value", nullable(string("information/mqtt events: text that must be contained")))
                        .prop("topic", nullable(string("mqtt events: topic filter, + and # allowed")))
                        .prop("repeat", nullable(bool("event tasks: true to run on every event, default once")))
                        .prop("event_name", nullable(string("stem_event tasks: event name from list_event_types")))
                        .prop("condition_field", nullable(string("stem_event/mqtt: dotted field path in the event data or JSON payload")))
                        .prop("condition_operator", nullable(enumeration("comparison", AgentTask.Condition.OPERATORS)))
                        .prop("condition_value", nullable(string("value to compare with")))
                        .build(),
                notGuest(), (chat, args) -> {
            String trigger = requireString(args, "trigger_type");
            LocalDateTime runAt = null;
            String runAtText = optString(args, "run_at");
            if (runAtText != null) {
                try {
                    runAt = LocalDateTime.parse(runAtText.trim(), INPUT);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("run_at must have the format yyyy-MM-dd HH:mm");
                }
            }
            AgentTask task = manager(plugin).create(chat, requireString(args, "instruction"), trigger, runAt,
                    optString(args, "cron"), optString(args, "event"), optString(args, "device"), optString(args, "match_value"),
                    optString(args, "topic"), Boolean.TRUE.equals(optBool(args, "repeat")), optString(args, "event_name"),
                    condition(args));
            return new JSONObject().put("created", true).put("task", describe(task));
        }));

        tools.add(new SimpleTool("list_event_types",
                "List the STEM events that happened since Mirra runs (from all plugins), with how often they occur and an "
                        + "example of their data. Use it to choose event_name, device, match_value or a condition for stem_event tasks.",
                Schema.object().prop("filter", nullable(string("part of the event name"))).build(),
                anyOf(STEM_READ), (chat, args) -> {
            String filter = optString(args, "filter");
            JSONArray list = new JSONArray();
            for (EventCatalog.Entry entry : manager(plugin).getCatalog().list()) {
                if (filter != null && !entry.name().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                list.put(new JSONObject().put("event_name", entry.name()).put("count", entry.count())
                        .put("last_seen", OUTPUT.format(entry.lastSeen().atZone(plugin.getStemApi().system().getZone())))
                        .put("example", entry.example()));
                if (list.length() >= 60) {
                    break;
                }
            }
            return new JSONObject().put("events", list);
        }));

        tools.add(new SimpleTool("list_tasks",
                "List the planned tasks of the person (administrators can list all).",
                Schema.object().prop("all_users", nullable(bool("administrators: true to list the tasks of everybody"))).build(),
                notGuest(), (chat, args) -> {
            boolean all = Boolean.TRUE.equals(optBool(args, "all_users")) && chat.has(STEM_ADMIN);
            JSONArray list = new JSONArray();
            manager(plugin).list(all ? null : chat.name()).forEach(task -> list.put(describe(task)));
            return new JSONObject().put("tasks", list);
        }));

        tools.add(new SimpleTool("cancel_task",
                "Cancel a planned task by id.",
                Schema.object().prop("id", integer("id of the task, see list_tasks")).build(),
                notGuest(), (chat, args) -> {
            int id = args.getInt("id");
            AgentTask task = manager(plugin).get(id).orElseThrow(() -> new IllegalArgumentException("No active task #" + id));
            if (!task.ownerName().equalsIgnoreCase(chat.name()) && !chat.has(STEM_ADMIN)) {
                throw new IllegalArgumentException("Task #" + id + " belongs to " + task.ownerName());
            }
            manager(plugin).cancel(id);
            return new JSONObject().put("cancelled", true).put("task", describe(task));
        }));
        return tools;
    }

    private static AgentTask.Condition condition(JSONObject args) {
        String field = optString(args, "condition_field");
        if (field == null) {
            return null;
        }
        String operator = optString(args, "condition_operator");
        String value = optString(args, "condition_value");
        if (operator == null || value == null) {
            throw new IllegalArgumentException("A condition needs condition_field, condition_operator and condition_value");
        }
        return new AgentTask.Condition(field, operator, value);
    }

    private static TaskManager manager(MirraPlugin plugin) {
        TaskManager manager = plugin.getTaskManager();
        if (manager == null) {
            throw new IllegalStateException("Tasks are not available");
        }
        return manager;
    }

    private static JSONObject describe(AgentTask task) {
        JSONObject json = new JSONObject().put("id", task.id()).put("owner", task.ownerName()).put("instruction", task.instruction())
                .put("trigger", task.describeTrigger()).put("created", task.created().format(OUTPUT)).put("runs", task.runCount());
        if (task.runAt() != null) {
            json.put("run_at", task.runAt().format(OUTPUT));
        }
        if (task.lastRun() != null) {
            json.put("last_run", task.lastRun().format(OUTPUT));
        }
        return json;
    }
}
