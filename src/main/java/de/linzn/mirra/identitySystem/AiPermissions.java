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

package de.linzn.mirra.identitySystem;

import java.util.*;

/**
 * Permissions of a user, stored in plugin_mirra_agent_user_permission.
 * <p>
 * Every registered user can chat, send files and voice messages, keep a profile and plan tasks without any
 * permission. Permissions unlock features; higher permissions include lower ones:
 * <pre>
 * OWNER        everything, also SHELL and permissions added later; only assigned in the database
 * ADMIN        everything except SHELL and OWNER
 * HOME_CONTROL includes HOME_LIGHTS and HOME_VIEW
 * HOME_LIGHTS  includes HOME_VIEW
 * </pre>
 * Each permission has a description, so Mirra can map requests like "Dennis darf Lichter schalten".
 */
public enum AiPermissions {
    ASSISTANT("Everyday assistant features: weather and fuel prices."),
    IMAGES("Let Mirra create images."),
    REMINDERS("Create, list and cancel reminders."),
    MEMORY("Ask Mirra to remember, recall and forget things explicitly, including household facts everybody can see."),
    HOME_VIEW("See the smart home: devices, lights, sensors and the active smart home profile."),
    HOME_LIGHTS("Switch and dim lights. Includes HOME_VIEW."),
    HOME_CONTROL("Control all smart home devices (switches, sockets, thermostats, air conditioners, printers) and change "
            + "the smart home profile. Includes HOME_LIGHTS and HOME_VIEW."),
    STEM_VIEW("See the STEM system: plugins, health checks, information blocks, notifications, events, MQTT values, data "
            + "store, user list."),
    ADMIN("Administrator: everything except SHELL. Console commands, scripts, MQTT, database, cloud, information blocks, "
            + "messages to other users, user management and permissions."),
    SHELL("Run shell commands on the STEM server and via SSH on other machines. Not part of ADMIN."),
    OWNER("Owner: everything, including SHELL and permissions added later. Only assigned in the database, never by Mirra.");

    /**
     * Permissions that can only be given in the database.
     */
    public static final Set<AiPermissions> DATABASE_ONLY = EnumSet.of(OWNER);

    private static final Map<String, List<AiPermissions>> LEGACY = Map.ofEntries(
            Map.entry("STATUS_STEM", List.of()),
            Map.entry("STATUS_LIGHT", List.of(HOME_VIEW)),
            Map.entry("SWITCH_LIGHT", List.of(HOME_LIGHTS)),
            Map.entry("GET_WEATHER", List.of(ASSISTANT)),
            Map.entry("GET_FUEL_PRICE", List.of(ASSISTANT)),
            Map.entry("CREATE_IMAGE", List.of(IMAGES)),
            Map.entry("CREATE_REMINDER", List.of(REMINDERS)),
            Map.entry("LONG_TERM_MEMORY_ACCESS", List.of(MEMORY)),
            Map.entry("LONG_TERM_MEMORY_WRITE", List.of(MEMORY)),
            Map.entry("STEM_READ", List.of(STEM_VIEW, HOME_VIEW)),
            Map.entry("STEM_ADMIN", List.of(ADMIN)));

    private final String description;

    AiPermissions(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * @return true if a user with this permission also has {@code other}
     */
    public boolean includes(AiPermissions other) {
        if (this == other || this == OWNER) {
            return true;
        }
        return switch (this) {
            case ADMIN -> other != SHELL && other != OWNER;
            case HOME_CONTROL -> other == HOME_LIGHTS || other == HOME_VIEW;
            case HOME_LIGHTS -> other == HOME_VIEW;
            default -> false;
        };
    }

    /**
     * Permission names of earlier versions and their replacements.
     *
     * @return the new permissions, empty for dropped ones, or null if the name is not an old permission
     */
    public static List<AiPermissions> fromLegacy(String name) {
        return name == null ? null : LEGACY.get(name.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * @return the permissions for a stored name: the permission itself, the replacements of an old name, or empty
     */
    public static List<AiPermissions> parse(String name) {
        if (name == null) {
            return List.of();
        }
        try {
            return List.of(AiPermissions.valueOf(name.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            List<AiPermissions> legacy = fromLegacy(name);
            return legacy == null ? List.of() : legacy;
        }
    }
}
