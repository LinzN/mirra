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

package de.linzn.mirra.ssh;

import java.util.Locale;

/**
 * What Mirra's login may do on a server. Enforced on the server by the sudoers file written by
 * {@code mirra-ssh-install.sh}, not by Mirra.
 */
public enum SshLevel {
    /**
     * Look only: commands as the unprivileged login, journal and /var/log, systemctl status, docker ps/logs/inspect.
     */
    READ,
    /**
     * Also start, stop and restart services and containers, apt upgrade, reboot.
     */
    OPS,
    /**
     * sudo for everything.
     */
    FULL;

    public String id() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    public static SshLevel parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Level missing, use read, ops or full");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown level '" + value + "', use read, ops or full");
        }
    }
}
