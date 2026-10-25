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

import de.linzn.mirra.agent.ChatContext;
import de.linzn.mirra.agent.openai.AgentResponse;
import de.linzn.stem.api.StemLogger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All tools of the agent. Builds the tool list per sender and runs function calls with permission checks.
 */
public class ToolRegistry {

    /* tool results longer than this are cut, so a big log or device list cannot flood the context */
    private static final int MAX_OUTPUT_CHARS = 20_000;

    private final StemLogger logger;
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(StemLogger logger) {
        this.logger = logger;
    }

    public void register(AgentTool tool) {
        if (this.tools.put(tool.name(), tool) != null) {
            throw new IllegalStateException("Duplicate tool " + tool.name());
        }
    }

    public void registerAll(Collection<? extends AgentTool> tools) {
        tools.forEach(this::register);
    }

    public Collection<AgentTool> getTools() {
        return this.tools.values();
    }

    /**
     * @return function definitions of all tools the sender may use
     */
    public JSONArray definitions(ChatContext chat) {
        JSONArray definitions = new JSONArray();
        for (AgentTool tool : this.tools.values()) {
            if (!this.allowed(tool, chat)) {
                continue;
            }
            definitions.put(new JSONObject()
                    .put("type", "function")
                    .put("name", tool.name())
                    .put("description", tool.description())
                    .put("parameters", tool.parameters())
                    .put("strict", true));
        }
        return definitions;
    }

    /**
     * @return names of the tools the sender may use
     */
    public List<String> allowedToolNames(ChatContext chat) {
        List<String> names = new ArrayList<>();
        for (AgentTool tool : this.tools.values()) {
            if (this.allowed(tool, chat)) {
                names.add(tool.name());
            }
        }
        return names;
    }

    private boolean allowed(AgentTool tool, ChatContext chat) {
        try {
            return tool.isAllowed(chat);
        } catch (Exception e) {
            this.logger.error("Permission check of tool " + tool.name() + " failed", e);
            return false;
        }
    }

    /**
     * Run a function call. Never throws: errors are returned to the model as JSON.
     *
     * @return output for the function_call_output item
     */
    public String execute(AgentResponse.FunctionCall call, ChatContext chat) {
        AgentTool tool = this.tools.get(call.name());
        if (tool == null) {
            return error("Unknown tool " + call.name());
        }
        if (!this.allowed(tool, chat)) {
            this.logger.warning("Tool " + call.name() + " denied for " + chat.name());
            return error("Permission denied: " + chat.name() + " is not allowed to use " + call.name());
        }
        JSONObject arguments;
        try {
            String raw = call.arguments();
            arguments = raw == null || raw.isBlank() ? new JSONObject() : new JSONObject(raw);
        } catch (JSONException e) {
            return error("Invalid arguments: " + e.getMessage());
        }
        this.logger.info("Tool " + call.name() + " for " + chat.name() + " " + shorten(arguments.toString(), 300));
        try {
            Object result = tool.execute(chat, arguments);
            String output = result == null ? new JSONObject().put("success", true).toString() : String.valueOf(result);
            return shorten(output, MAX_OUTPUT_CHARS);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("Interrupted");
        } catch (Exception e) {
            this.logger.error("Tool " + call.name() + " failed", e);
            return error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String error(String message) {
        return new JSONObject().put("success", false).put("error", String.valueOf(message)).toString();
    }

    private static String shorten(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "... (truncated)";
    }
}
