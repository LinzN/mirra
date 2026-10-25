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

package de.linzn.mirra.agent.openai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed result of the Responses API.
 *
 * @param id            response id, used as previous_response_id for the next request of a tool loop
 * @param status        completed, incomplete, failed, ...
 * @param text          concatenated output text of all assistant messages
 * @param functionCalls function calls the model wants to run
 * @param raw           the full response
 */
public record AgentResponse(String id, String status, String text, List<FunctionCall> functionCalls, JSONObject raw) {

    public static AgentResponse parse(JSONObject json) {
        StringBuilder text = new StringBuilder();
        List<FunctionCall> calls = new ArrayList<>();
        JSONArray output = json.optJSONArray("output");
        if (output != null) {
            for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                switch (item.optString("type")) {
                    case "message" -> {
                        JSONArray content = item.optJSONArray("content");
                        if (content == null) {
                            continue;
                        }
                        for (int j = 0; j < content.length(); j++) {
                            JSONObject part = content.optJSONObject(j);
                            if (part != null && "output_text".equals(part.optString("type"))) {
                                if (!text.isEmpty()) {
                                    text.append('\n');
                                }
                                text.append(part.optString("text", ""));
                            }
                        }
                    }
                    case "function_call" -> calls.add(new FunctionCall(
                            item.optString("call_id"), item.optString("name"), item.optString("arguments", "{}")));
                    default -> {
                        /* reasoning, web_search_call, ... are handled by OpenAI */
                    }
                }
            }
        }
        if (text.isEmpty() && json.has("output_text")) {
            text.append(json.optString("output_text", ""));
        }
        return new AgentResponse(json.optString("id"), json.optString("status", "completed"), text.toString().trim(), List.copyOf(calls), json);
    }

    public boolean hasFunctionCalls() {
        return !this.functionCalls.isEmpty();
    }

    public String errorMessage() {
        JSONObject error = this.raw.optJSONObject("error");
        return error == null ? "unknown" : error.optString("message", "unknown");
    }

    /**
     * A function call of the model.
     *
     * @param callId    id that must be used for the function_call_output
     * @param name      function name
     * @param arguments JSON encoded arguments
     */
    public record FunctionCall(String callId, String name, String arguments) {
    }
}
