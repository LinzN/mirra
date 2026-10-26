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
 * Permissions of an identity, stored in plugin_mirra_identity_user_permissions.
 */
public enum AiPermissions {
    LONG_TERM_MEMORY_WRITE, LONG_TERM_MEMORY_ACCESS, SWITCH_LIGHT, STATUS_LIGHT, STATUS_STEM, CREATE_IMAGE, CREATE_REMINDER, GET_FUEL_PRICE, GET_WEATHER,
    /**
     * Read the state of STEM: plugins, health checks, information blocks, notifications, devices, smart home profile.
     */
    STEM_READ,
    /**
     * Control STEM: console commands, scripts, MQTT messages, notifications, information blocks, all devices,
     * smart home profile, health checks. Includes everything of STEM_READ.
     */
    STEM_ADMIN,
    /**
     * Run shell commands on the STEM host and via SSH on other hosts. Not included in STEM_ADMIN.
     */
    SHELL
}
