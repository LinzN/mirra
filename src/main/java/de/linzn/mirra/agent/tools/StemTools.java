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

import de.linzn.mirra.MirraConfig;
import de.linzn.stem.api.StemApi;
import de.linzn.stem.api.command.CommandResult;
import de.linzn.stem.api.script.ScriptFailedException;
import de.linzn.stem.api.script.ScriptResult;
import de.linzn.stem.modules.healthModule.HealthCheck;
import de.linzn.stem.modules.healthModule.HealthCheckFeedback;
import de.linzn.stem.modules.healthModule.HealthCheckLevel;
import de.linzn.stem.modules.informationModule.InformationBlock;
import de.linzn.stem.modules.informationModule.InformationIntent;
import de.linzn.stem.modules.notificationModule.NotificationPriority;
import de.linzn.stem.modules.notificationModule.archive.ArchivedNotification;
import de.linzn.stem.modules.pluginModule.STEMPlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static de.linzn.mirra.agent.tools.Schema.*;
import static de.linzn.mirra.agent.tools.SimpleTool.anyOf;
import static de.linzn.mirra.identitySystem.AiPermissions.*;

/**
 * Tools for STEM itself, built on the STEM plugin API.
 */
public final class StemTools {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private StemTools() {
    }

    public static List<AgentTool> create(STEMPlugin plugin, MirraConfig config) {
        StemApi api = plugin.getStemApi();
        ZoneId zone = api.system().getZone();
        List<AgentTool> tools = new ArrayList<>();

        tools.add(new SimpleTool("stem_status",
                "Status of the STEM smart home system: version, uptime, number of plugins and failed health checks.",
                Schema.empty(), anyOf(STATUS_STEM, STEM_READ), (chat, args) -> {
            long seconds = api.system().getUptime().toSeconds();
            int errors = 0;
            for (HealthCheck check : api.health().getChecks()) {
                errors += check.getHealthCheckFeedbacks(HealthCheckLevel.ERROR).size();
            }
            return new JSONObject()
                    .put("version", api.system().getVersion())
                    .put("uptime", String.format("%d days, %02d:%02d:%02d", seconds / 86400, (seconds % 86400) / 3600, (seconds % 3600) / 60, seconds % 60))
                    .put("started", DATE_FORMAT.format(api.system().getStartTime().atZone(zone)))
                    .put("timeZone", zone.toString())
                    .put("active", api.system().isActive())
                    .put("plugins", api.plugins().getPlugins().size())
                    .put("failedHealthChecks", errors);
        }));

        tools.add(new SimpleTool("stem_list_plugins",
                "List all loaded STEM plugins with version and build number.",
                Schema.empty(), anyOf(STEM_READ), (chat, args) -> {
            JSONArray list = new JSONArray();
            for (STEMPlugin stemPlugin : api.plugins().getPlugins()) {
                list.put(new JSONObject().put("name", stemPlugin.getPluginName()).put("version", stemPlugin.getVersion())
                        .put("build", stemPlugin.getBuildNumber()).put("enabled", api.plugins().isEnabled(stemPlugin.getPluginName())));
            }
            return new JSONObject().put("plugins", list);
        }));

        tools.add(new SimpleTool("stem_health_checks",
                "Results of the last STEM health checks, grouped by level (DONE, WARNING, ERROR, UNKNOWN).",
                Schema.object().prop("only_problems", bool("true to return only WARNING and ERROR entries")).build(),
                anyOf(STEM_READ), (chat, args) -> {
            boolean onlyProblems = Boolean.TRUE.equals(optBool(args, "only_problems"));
            JSONArray checks = new JSONArray();
            for (HealthCheck check : api.health().getChecks()) {
                JSONObject levels = new JSONObject();
                for (HealthCheckLevel level : HealthCheckLevel.values()) {
                    if (onlyProblems && level != HealthCheckLevel.WARNING && level != HealthCheckLevel.ERROR) {
                        continue;
                    }
                    JSONArray entries = new JSONArray();
                    for (HealthCheckFeedback feedback : check.getHealthCheckFeedbacks(level)) {
                        entries.put(feedback.getDescription());
                    }
                    if (!entries.isEmpty()) {
                        levels.put(level.name(), entries);
                    }
                }
                if (!onlyProblems || !levels.isEmpty()) {
                    checks.put(new JSONObject().put("name", check.getName()).put("plugin", check.getStemPlugin().getPluginName()).put("results", levels));
                }
            }
            return new JSONObject().put("healthChecks", checks);
        }));

        tools.add(new SimpleTool("stem_run_health_checks",
                "Start all STEM health checks in the background. Results are available later with stem_health_checks; some checks take a few minutes.",
                Schema.empty(), anyOf(STEM_ADMIN), (chat, args) -> {
            api.scheduler().runAsync(() -> api.health().runNow());
            return new JSONObject().put("success", true).put("message", "Health checks started");
        }));

        tools.add(new SimpleTool("stem_information_blocks",
                "Information blocks of STEM (messages shown on the displays, e.g. open doors, finished prints, warnings). "
                        + "Active blocks can be removed with stem_remove_information.",
                Schema.object()
                        .prop("include_archived", bool("true to include expired blocks"))
                        .prop("limit", nullable(integer("maximum number of archived blocks, default 10")))
                        .build(),
                anyOf(STEM_READ), (chat, args) -> {
            JSONObject result = new JSONObject();
            result.put("active", blocks(api.information().getActive(), 50, zone));
            if (Boolean.TRUE.equals(optBool(args, "include_archived"))) {
                Integer limit = optInt(args, "limit");
                List<InformationBlock> archived = new ArrayList<>(api.information().getArchived());
                archived.sort(Comparator.comparingLong(InformationBlock::getCreationTime).reversed());
                result.put("archived", blocks(archived, limit == null ? 10 : Math.max(1, Math.min(100, limit)), zone));
            }
            return result;
        }));

        tools.add(new SimpleTool("stem_notifications",
                "Recent STEM notifications, newest first.",
                Schema.object().prop("limit", nullable(integer("maximum number, default 20"))).build(),
                anyOf(STEM_READ), (chat, args) -> {
            Integer limit = optInt(args, "limit");
            List<ArchivedNotification> recent = new ArrayList<>(api.notifications().getRecent());
            recent.sort(Comparator.comparing(ArchivedNotification::getDate, Comparator.nullsLast(Comparator.reverseOrder())));
            JSONArray list = new JSONArray();
            for (ArchivedNotification notification : recent.subList(0, Math.min(recent.size(), limit == null ? 20 : Math.max(1, Math.min(100, limit))))) {
                list.put(new JSONObject().put("source", notification.getSource()).put("text", notification.getNotification())
                        .put("date", notification.getDate() == null ? "" : DATE_FORMAT.format(notification.getDate().toInstant().atZone(zone))));
            }
            return new JSONObject().put("notifications", list);
        }));

        tools.add(new SimpleTool("stem_list_commands",
                "List all STEM console commands. Use 'help' with stem_run_command to get details if needed.",
                Schema.empty(), anyOf(STEM_READ), (chat, args) ->
                new JSONObject().put("commands", new JSONArray(api.commands().getCommands()))
                        .put("blocked", new JSONArray(config.blockedCommands))));

        tools.add(new SimpleTool("stem_run_command",
                "Run a STEM console command with arguments, exactly like typed into the STEM console, and return its output. "
                        + "Examples: 'fuel 5', 'heating status', 'computer status workstation', 'maintenance backup pbs01'. "
                        + "Only run commands the person asked for.",
                Schema.object().prop("command_line", string("command and arguments separated by spaces")).build(),
                anyOf(STEM_ADMIN), (chat, args) -> {
            String commandLine = requireString(args, "command_line");
            String commandName = commandLine.split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (config.blockedCommands.contains(commandName)) {
                return new JSONObject().put("success", false).put("error", "The command '" + commandName + "' is blocked for Mirra");
            }
            CommandResult result = api.commands().execute(commandLine, Duration.ofSeconds(config.commandTimeoutSeconds));
            return new JSONObject().put("found", result.found()).put("finished", result.finished())
                    .put("success", result.success()).put("output", new JSONArray(lastLines(result.output(), 200)));
        }));

        tools.add(new SimpleTool("stem_run_script",
                "Run a STEM script (.stemsh) with parameters and return exit code and output.",
                Schema.object()
                        .prop("script", string("name of the script without extension"))
                        .prop("parameters", array("script parameters", Schema.object()
                                .prop("name", string("parameter name"))
                                .prop("value", string("parameter value")).build()))
                        .build(),
                anyOf(STEM_ADMIN), (chat, args) -> {
            String script = requireString(args, "script");
            if (!script.matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalArgumentException("Invalid script name");
            }
            Map<String, String> parameters = new LinkedHashMap<>();
            JSONArray list = args.optJSONArray("parameters");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject parameter = list.getJSONObject(i);
                    parameters.put(parameter.getString("name"), parameter.getString("value"));
                }
            }
            try {
                ScriptResult result = api.scripts().run(script, parameters, Duration.ofSeconds(config.scriptTimeoutSeconds));
                return new JSONObject().put("success", result.isSuccess()).put("exitCode", result.exitCode())
                        .put("output", new JSONArray(lastLines(result.output(), 200)));
            } catch (ScriptFailedException e) {
                return new JSONObject().put("success", false).put("error", e.getMessage());
            }
        }));

        tools.add(new SimpleTool("stem_mqtt_publish",
                "Publish an MQTT message through STEM, e.g. to control a device that has no own tool.",
                Schema.object()
                        .prop("topic", string("MQTT topic"))
                        .prop("payload", string("message payload, often JSON"))
                        .prop("retained", bool("true to keep the message on the broker"))
                        .build(),
                anyOf(STEM_ADMIN), (chat, args) -> {
            String topic = requireString(args, "topic");
            if (topic.contains("#") || topic.contains("+")) {
                throw new IllegalArgumentException("Wildcards are not allowed in a publish topic");
            }
            String payload = Objects.requireNonNullElse(optString(args, "payload"), "");
            boolean published = api.mqtt().publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, Boolean.TRUE.equals(optBool(args, "retained")));
            return new JSONObject().put("success", published);
        }));

        tools.add(new SimpleTool("stem_push_notification",
                "Send a notification through STEM (forwarded to Telegram and the STEM clients).",
                Schema.object()
                        .prop("message", string("notification text"))
                        .prop("priority", enumeration("priority", List.of("LOW", "DEFAULT", "HIGH", "ASAP")))
                        .build(),
                anyOf(STEM_ADMIN), (chat, args) -> {
            api.notifications().push(requireString(args, "message"), NotificationPriority.valueOf(requireString(args, "priority")));
            return new JSONObject().put("success", true);
        }));

        tools.add(new SimpleTool("stem_show_information",
                "Show an information block on the STEM displays, optionally also as notification to the users.",
                Schema.object()
                        .prop("title", string("short title"))
                        .prop("text", string("text of the block"))
                        .prop("minutes", nullable(integer("how long it is shown, default 60")))
                        .prop("notify_user", bool("true to also notify the users (Discord, WhatsApp, Telegram)"))
                        .build(),
                anyOf(STEM_ADMIN), (chat, args) -> {
            String text = requireString(args, "text");
            InformationBlock block = new InformationBlock(requireString(args, "title"), text, plugin, text);
            Integer minutes = optInt(args, "minutes");
            block.setExpireTime(Instant.now().plus(minutes == null ? 60 : Math.max(1, Math.min(10080, minutes)), ChronoUnit.MINUTES));
            block.setIcon("INFO");
            block.addIntent(InformationIntent.SHOW_DISPLAY);
            if (Boolean.TRUE.equals(optBool(args, "notify_user"))) {
                block.addIntent(InformationIntent.NOTIFY_USER);
            }
            long id = api.information().queue(block);
            return new JSONObject().put("success", true).put("id", id);
        }));
        return tools;
    }

    private static JSONArray blocks(List<InformationBlock> blocks, int limit, ZoneId zone) {
        JSONArray list = new JSONArray();
        for (InformationBlock block : blocks.subList(0, Math.min(blocks.size(), limit))) {
            list.put(new JSONObject().put("id", block.getId()).put("title", block.getName()).put("text", block.getDescription())
                    .put("plugin", block.getSourcePlugin() == null ? "" : block.getSourcePlugin().getPluginName())
                    .put("created", DATE_FORMAT.format(Instant.ofEpochMilli(block.getCreationTime()).atZone(zone))));
        }
        return list;
    }

    private static List<String> lastLines(List<String> lines, int max) {
        return lines.size() <= max ? lines : lines.subList(lines.size() - max, lines.size());
    }
}
