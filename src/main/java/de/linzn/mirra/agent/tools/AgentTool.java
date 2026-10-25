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
import org.json.JSONObject;

/**
 * A function the agent can call.
 */
public interface AgentTool {

    /**
     * @return unique function name, {@code [a-z0-9_]}
     */
    String name();

    /**
     * @return description for the model: what the tool does and when to use it
     */
    String description();

    /**
     * @return JSON schema of the arguments in strict mode (all properties required, no additional properties)
     */
    JSONObject parameters();

    /**
     * Tools the sender is not allowed to use are not offered to the model at all.
     */
    boolean isAllowed(ChatContext chat);

    /**
     * Run the tool. Exceptions are reported to the model as error.
     *
     * @return result for the model: JSONObject, JSONArray or String
     */
    Object execute(ChatContext chat, JSONObject arguments) throws Exception;
}
