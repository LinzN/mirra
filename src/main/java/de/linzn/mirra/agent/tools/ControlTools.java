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
import de.linzn.mirra.MirraPlugin;
import de.linzn.stem.api.StemApi;
import de.linzn.stem.api.cloud.CloudFileHandle;
import de.linzn.stem.api.mqtt.MqttSubscription;
import de.linzn.stem.api.system.LogEntry;
import de.linzn.stem.modules.databaseModule.DataContainer;
import de.linzn.stem.modules.informationModule.InformationBlock;
import de.linzn.stem.utils.ProcessOutput;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static de.linzn.mirra.agent.tools.Schema.*;
import static de.linzn.mirra.agent.tools.SimpleTool.anyOf;
import static de.linzn.mirra.identitySystem.AiPermissions.*;

/**
 * Deep control of STEM: information blocks, cloud, data store, database, MQTT and the shell.
 */
public final class ControlTools {

    private static final int MAX_SQL_ROWS = 200;
    private static final int MAX_OUTPUT_LINES = 300;

    private ControlTools() {
    }

    public static List<AgentTool> create(MirraPlugin plugin, MirraConfig config) {
        StemApi api = plugin.getStemApi();
        List<AgentTool> tools = new ArrayList<>();

        /* information blocks */
        tools.add(new SimpleTool("stem_remove_information",
                "Remove (expire) an active information block, e.g. after the person handled it. Get the id from stem_information_blocks.",
                Schema.object().prop("id", integer("id of the information block")).build(),
                anyOf(ADMIN), (chat, args) -> {
            long id = args.getLong("id");
            InformationBlock block = api.information().get(id)
                    .orElseThrow(() -> new IllegalArgumentException("No information block with id " + id));
            if (!block.isActive()) {
                return new JSONObject().put("removed", false).put("note", "The block was not active anymore");
            }
            block.expire();
            return new JSONObject().put("removed", true).put("title", block.getName());
        }));

        /* cloud */
        tools.add(new SimpleTool("stem_cloud_share",
                "Create a public share link for a file in the STEM cloud.",
                Schema.object().prop("path", string("absolute path in the cloud, e.g. /GeneratedImages/x.png")).build(),
                anyOf(ADMIN), (chat, args) -> {
            requireCloud(api);
            CloudFileHandle handle = api.cloud().getFile(requireString(args, "path"))
                    .orElseThrow(() -> new IllegalArgumentException("File not found in the cloud"));
            return new JSONObject().put("path", handle.getPath()).put("url", handle.createPublicShareLink());
        }));

        tools.add(new SimpleTool("stem_cloud_upload",
                "Store a text file in the STEM cloud and return a public link. Source is either 'content' or 'path' on the STEM server.",
                Schema.object()
                        .prop("file_name", string("file name with extension"))
                        .prop("folder", string("cloud folder, e.g. /Mirra/"))
                        .prop("content", nullable(string("text content")))
                        .prop("path", nullable(string("absolute path of a file on the STEM server (needs SHELL permission)")))
                        .build(),
                anyOf(ADMIN), (chat, args) -> {
            requireCloud(api);
            String content = optString(args, "content");
            String path = optString(args, "path");
            if ((content == null) == (path == null)) {
                throw new IllegalArgumentException("Give exactly one of content or path");
            }
            if (path != null && !chat.has(SHELL)) {
                throw new IllegalArgumentException("Reading files of the server needs the SHELL permission");
            }
            String fileName = requireString(args, "file_name").replaceAll("[/\\\\]", "_");
            String folder = requireString(args, "folder");
            Path temp = Files.createTempDirectory(plugin.getTempFolder().toPath(), "upload");
            Path file = temp.resolve(fileName);
            try {
                if (content != null) {
                    Files.writeString(file, content, StandardCharsets.UTF_8);
                } else {
                    Files.copy(Path.of(path), file);
                }
                CloudFileHandle handle = api.cloud().upload(file.toFile(), folder.endsWith("/") ? folder : folder + "/", fileName)
                        .orElseThrow(() -> new IllegalStateException("Upload failed"));
                return new JSONObject().put("path", handle.getPath()).put("url", handle.createPublicShareLink());
            } finally {
                Files.deleteIfExists(file);
                Files.deleteIfExists(temp);
            }
        }));

        /* key value store of STEM */
        tools.add(new SimpleTool("stem_data_get",
                "Read a JSON data container of the STEM data store by key.",
                Schema.object().prop("key", string("key of the container")).build(),
                anyOf(STEM_VIEW), (chat, args) -> {
            Optional<DataContainer> container = api.data().get(requireString(args, "key"));
            return container.<Object>map(DataContainer::getJSON).orElse(new JSONObject().put("found", false));
        }));

        tools.add(new SimpleTool("stem_data_set",
                "Write a JSON data container of the STEM data store. Replaces the whole container.",
                Schema.object()
                        .prop("key", string("key of the container"))
                        .prop("json", string("JSON object as text"))
                        .build(),
                anyOf(ADMIN), (chat, args) -> {
            api.data().set(requireString(args, "key"), new JSONObject(requireString(args, "json")));
            return new JSONObject().put("saved", true);
        }));

        /* database */
        tools.add(new SimpleTool("stem_sql",
                "Run one SQL statement on the STEM database (MySQL/MariaDB). SELECT returns up to " + MAX_SQL_ROWS + " rows, "
                        + "other statements return the number of changed rows. Use SHOW TABLES and DESCRIBE to explore. "
                        + "Confirm with the person before changing or deleting data.",
                Schema.object().prop("query", string("SQL statement")).build(),
                anyOf(ADMIN), (chat, args) -> {
            String query = requireString(args, "query");
            plugin.getPluginLogger().info("SQL by " + chat.name() + ": " + query);
            return api.data().withConnection(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(30);
                    statement.setMaxRows(MAX_SQL_ROWS + 1);
                    if (!statement.execute(query)) {
                        return new JSONObject().put("changed_rows", statement.getUpdateCount());
                    }
                    try (ResultSet rs = statement.getResultSet()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        JSONArray rows = new JSONArray();
                        boolean truncated = false;
                        while (rs.next()) {
                            if (rows.length() >= MAX_SQL_ROWS) {
                                truncated = true;
                                break;
                            }
                            JSONObject row = new JSONObject();
                            for (int i = 1; i <= meta.getColumnCount(); i++) {
                                Object value = rs.getObject(i);
                                row.put(meta.getColumnLabel(i), value == null ? JSONObject.NULL : value instanceof Number || value instanceof Boolean ? value : value.toString());
                            }
                            rows.put(row);
                        }
                        return new JSONObject().put("rows", rows).put("truncated", truncated);
                    }
                }
            });
        }));

        /* MQTT */
        tools.add(new SimpleTool("stem_mqtt_read",
                "Listen to MQTT topics for some seconds and return the messages, including retained values. "
                        + "Wildcards + and # are allowed, e.g. zigbee2mqtt/+ or uvr/#.",
                Schema.object()
                        .prop("topic_filter", string("MQTT topic filter"))
                        .prop("seconds", nullable(integer("how long to listen, 1-30, default 3")))
                        .build(),
                anyOf(STEM_VIEW), (chat, args) -> {
            Integer seconds = optInt(args, "seconds");
            int wait = seconds == null ? 3 : Math.max(1, Math.min(30, seconds));
            List<JSONObject> messages = Collections.synchronizedList(new ArrayList<>());
            MqttSubscription subscription = api.mqtt().subscribe(requireString(args, "topic_filter"), (topic, payload) -> {
                if (messages.size() < 200) {
                    String text = new String(payload, StandardCharsets.UTF_8);
                    messages.add(new JSONObject().put("topic", topic).put("payload", text.length() > 2000 ? text.substring(0, 2000) + "..." : text));
                }
            });
            try {
                Thread.sleep(wait * 1000L);
            } finally {
                subscription.unsubscribe();
            }
            synchronized (messages) {
                return new JSONObject().put("messages", new JSONArray(messages)).put("connected", api.mqtt().isConnected());
            }
        }));

        /* log */
        tools.add(new SimpleTool("stem_read_log",
                "Read the latest entries of the STEM log (all plugins). Use it to find the cause of errors.",
                Schema.object()
                        .prop("max", nullable(integer("number of entries, default 50, at most 300")))
                        .prop("level", nullable(enumeration("minimum level, default warning", List.of("all", "info", "warning", "error"))))
                        .prop("contains", nullable(string("only entries containing this text, e.g. a plugin name")))
                        .build(),
                anyOf(STEM_VIEW), (chat, args) -> {
            Integer maxArg = optInt(args, "max");
            int max = maxArg == null ? 50 : Math.max(1, Math.min(300, maxArg));
            String level = Optional.ofNullable(optString(args, "level")).orElse("warning");
            int minLevel = switch (level) {
                case "all" -> 0;
                case "info" -> 800;
                case "error" -> 1000;
                default -> 900;
            };
            String contains = optString(args, "contains");
            JSONArray entries = new JSONArray();
            List<LogEntry> log = api.system().getRecentLog(contains == null ? max : 5000, minLevel);
            List<LogEntry> filtered = new ArrayList<>();
            for (LogEntry entry : log) {
                if (contains == null || (entry.message() != null && entry.message().toLowerCase(Locale.ROOT).contains(contains.toLowerCase(Locale.ROOT)))) {
                    filtered.add(entry);
                }
            }
            for (LogEntry entry : filtered.subList(Math.max(0, filtered.size() - max), filtered.size())) {
                String message = entry.message() == null ? "" : entry.message();
                entries.put(new JSONObject().put("time", entry.time().atZone(api.system().getZone()).toLocalDateTime().toString())
                        .put("level", entry.level()).put("message", message.length() > 1000 ? message.substring(0, 1000) + "..." : message));
            }
            return new JSONObject().put("entries", entries);
        }));

        /* OpenAI credits and the watchdog */
        tools.add(new SimpleTool("openai_usage",
                "Mirra's own OpenAI usage: tokens per model today and this month and, with an admin key, costs, budget and the "
                        + "estimated remaining credit. Use it when somebody asks about tokens, credits, costs or the budget.",
                Schema.empty(), anyOf(ADMIN), (chat, args) -> plugin.getBillingMonitor().report()));

        tools.add(new SimpleTool("watchdog_status",
                "Status of Mirra's automatic STEM watchdog (which reacts to failed health checks and errors in the log); "
                        + "optionally run a check now.",
                Schema.object().prop("check_now", nullable(bool("true to look for new problems right now"))).build(),
                anyOf(ADMIN), (chat, args) -> {
            if (Boolean.TRUE.equals(optBool(args, "check_now"))) {
                api.scheduler().runAsync(plugin.getWatchdog()::check);
            }
            return new JSONObject(plugin.getWatchdog().status()).put("enabled", config.autonomyEnabled);
        }));

        /* shell */
        tools.add(new SimpleTool("shell_run",
                "Run a shell command on the STEM server, or with 'host' via SSH on another machine (key based login of the "
                        + "STEM user, no password prompts). Returns exit code and output. Ask before commands that change or "
                        + "delete something, restart services or machines, unless the person clearly asked for exactly that.",
                Schema.object()
                        .prop("command", string("shell command, e.g. 'df -h' or 'systemctl status nginx'"))
                        .prop("host", nullable(string("SSH host name or IP; null to run on the STEM server")))
                        .prop("user", nullable(string("SSH user; null for the configured default")))
                        .prop("port", nullable(integer("SSH port; null for the configured default")))
                        .prop("timeout_seconds", nullable(integer("timeout, default from the configuration")))
                        .build(),
                chat -> config.shellEnabled && chat.has(SHELL), (chat, args) -> {
            String command = requireString(args, "command");
            String host = optString(args, "host");
            Integer timeoutArg = optInt(args, "timeout_seconds");
            int timeout = timeoutArg == null ? config.shellTimeoutSeconds : Math.max(1, Math.min(3600, timeoutArg));
            List<String> process = new ArrayList<>();
            if (host != null) {
                String user = Optional.ofNullable(optString(args, "user")).orElse(config.shellSshUser);
                Integer portArg = optInt(args, "port");
                int port = portArg == null ? config.shellSshPort : portArg;
                if (!safeArgument(host) || !safeArgument(user)) {
                    throw new IllegalArgumentException("Invalid host or user");
                }
                if (!config.shellAllowedHosts.isEmpty() && !config.shellAllowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Host not allowed, allowed are " + config.shellAllowedHosts);
                }
                process.addAll(List.of("ssh", "-p", String.valueOf(port), "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
                        user + "@" + host, command));
            } else {
                process.addAll(List.of("/bin/sh", "-c", command));
            }
            plugin.getPluginLogger().info("Shell by " + chat.name() + (host == null ? "" : " on " + host) + ": " + command);
            return runProcess(process, timeout);
        }));
        return tools;
    }

    private static JSONObject runProcess(List<String> command, int timeoutSeconds) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            ProcessOutput output = ProcessOutput.drain(process, "Mirra-Shell");
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            output.awaitCompletion(5, TimeUnit.SECONDS);
            List<String> lines = output.getLines();
            boolean truncated = lines.size() > MAX_OUTPUT_LINES;
            if (truncated) {
                lines = lines.subList(lines.size() - MAX_OUTPUT_LINES, lines.size());
            }
            JSONObject result = new JSONObject().put("finished", finished).put("output", new JSONArray(lines));
            if (finished) {
                result.put("exit_code", process.exitValue());
            } else {
                result.put("error", "Timeout after " + timeoutSeconds + " s, process killed");
            }
            if (truncated) {
                result.put("note", "Only the last " + MAX_OUTPUT_LINES + " lines");
            }
            return result;
        } finally {
            ProcessOutput.closeStreams(process);
        }
    }

    private static boolean safeArgument(String value) {
        return value != null && !value.isBlank() && !value.startsWith("-") && value.chars().noneMatch(c -> Character.isWhitespace(c) || c == '@');
    }

    private static void requireCloud(StemApi api) {
        if (!api.cloud().isEnabled()) {
            throw new IllegalStateException("No STEM cloud configured");
        }
    }
}
