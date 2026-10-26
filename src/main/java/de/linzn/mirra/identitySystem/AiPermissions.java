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

/**
 * Permissions of a user, stored in plugin_mirra_agent_user_permission. Each permission has a description in
 * plain language, so Mirra can map requests like "Dennis darf Lichter schalten" to the right permission.
 */
public enum AiPermissions {
    STATUS_STEM("See the status of STEM (version, uptime). Guests have it too."),
    STATUS_LIGHT("See which lights are on."),
    SWITCH_LIGHT("Switch and dim lights."),
    GET_WEATHER("Ask for the weather."),
    GET_FUEL_PRICE("Ask for fuel prices."),
    CREATE_IMAGE("Let Mirra create images."),
    CREATE_REMINDER("Create, list and cancel reminders."),
    LONG_TERM_MEMORY_ACCESS("Search Mirra's long term memory (recall)."),
    LONG_TERM_MEMORY_WRITE("Let Mirra remember and forget things explicitly, and add household facts everybody can see."),
    /**
     * Read the state of STEM: plugins, health checks, information blocks, notifications, devices, smart home profile.
     */
    STEM_READ("See everything in STEM and the smart home: devices, sensors, plugins, health checks, information blocks, "
            + "notifications, MQTT values, users, events. No changes."),
    /**
     * Control STEM: console commands, scripts, MQTT messages, notifications, information blocks, all devices,
     * smart home profile, health checks, users. Includes everything of STEM_READ.
     */
    STEM_ADMIN("Administrator: control all devices, console commands, scripts, MQTT, database, cloud, information blocks, "
            + "messages to other users, user management and permissions. Includes STEM_READ."),
    /**
     * Run shell commands on the STEM host and via SSH on other hosts. Not included in STEM_ADMIN.
     */
    SHELL("Run shell commands on the STEM server and via SSH on other machines. Not part of STEM_ADMIN.");

    private final String description;

    AiPermissions(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
