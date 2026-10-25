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
import de.linzn.mirra.identitySystem.AiPermissions;
import org.json.JSONObject;

import java.util.function.Predicate;

/**
 * Tool defined by lambdas.
 */
public final class SimpleTool implements AgentTool {

    private final String name;
    private final String description;
    private final JSONObject parameters;
    private final Predicate<ChatContext> access;
    private final Executor executor;

    @FunctionalInterface
    public interface Executor {
        Object execute(ChatContext chat, JSONObject arguments) throws Exception;
    }

    public SimpleTool(String name, String description, JSONObject parameters, Predicate<ChatContext> access, Executor executor) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.access = access;
        this.executor = executor;
    }

    /**
     * Access for everybody with one of the permissions.
     */
    public static Predicate<ChatContext> anyOf(AiPermissions... permissions) {
        return chat -> {
            for (AiPermissions permission : permissions) {
                if (chat.has(permission)) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * Access for registered users and internal STEM requests.
     */
    public static Predicate<ChatContext> notGuest() {
        return chat -> chat.isInternal() || !chat.isGuest();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public JSONObject parameters() {
        return parameters;
    }

    @Override
    public boolean isAllowed(ChatContext chat) {
        return access.test(chat);
    }

    @Override
    public Object execute(ChatContext chat, JSONObject arguments) throws Exception {
        return executor.execute(chat, arguments);
    }
}
